#!/usr/bin/env python3
"""
Rigorous Benchmark & Verification Suite: jolt vs. uv vs. pip
- Invokes java.exe directly with AppCDS (no cmd.exe wrapper overhead)
- Separates Cold Install, True Warm Install (Clean Venv), and Warm Audit (Already Installed)
- Reports Median, p95, and Internal Engine Time (Resolve + Unpack + Link)
- Verifies that jolt, uv, and pip produce identical installed package sets in site-packages
"""
import math
import os
import re
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
BENCH_WORK_DIR = ROOT_DIR / "build" / "bench-work"
JOLT_CACHE_DIR = BENCH_WORK_DIR / "jolt-cache"
UV_CACHE_DIR = BENCH_WORK_DIR / "uv-cache"
PIP_CACHE_DIR = BENCH_WORK_DIR / "pip-cache"

NATIVE_EXE = ROOT_DIR / "build" / "native" / "fast.exe"
JOLT_JAR = ROOT_DIR / "build" / "jolt.jar"
JOLT_JSA = ROOT_DIR / "build" / "jolt.jsa"


def get_jolt_base_cmd():
    if NATIVE_EXE.exists():
        return [str(NATIVE_EXE)], "jolt (Native .exe)"
    if JOLT_JSA.exists():
        return [
            "java",
            f"-XX:SharedArchiveFile={JOLT_JSA}",
            "-XX:TieredStopAtLevel=1",
            "-XX:+UseSerialGC",
            "-Xms32m",
            "-cp", str(JOLT_JAR),
            "dev.jolt.Main"
        ], "jolt (AppCDS Direct)"
    return ["java", "-cp", str(JOLT_JAR), "dev.jolt.Main"], "jolt (JVM)"


def p95(values):
    if not values:
        return 0.0
    s = sorted(values)
    idx = min(len(s) - 1, math.ceil(0.95 * len(s)) - 1)
    return s[idx]


def reset_venv_packages(venv_dir: Path):
    site_pkgs = venv_dir / ("Lib/site-packages" if os.name == "nt" else "lib/python3.12/site-packages")
    if not site_pkgs.exists():
        return
    for item in site_pkgs.iterdir():
        name = item.name.lower()
        if name.startswith("pip") or name.startswith("_distutils") or name == "distutils-precedence.pth":
            continue
        if item.is_dir():
            shutil.rmtree(item, ignore_errors=True)
        else:
            try:
                item.unlink()
            except OSError:
                pass


def inspect_installed_packages(venv_dir: Path) -> dict:
    site_pkgs = venv_dir / ("Lib/site-packages" if os.name == "nt" else "lib/python3.12/site-packages")
    installed = {}
    if not site_pkgs.exists():
        return installed
    for dist_info in site_pkgs.glob("*.dist-info"):
        meta = dist_info / "METADATA"
        if not meta.is_file():
            continue
        pkg_name, pkg_ver = None, None
        for line in meta.read_text(encoding="utf-8", errors="replace").splitlines():
            if not line.strip():
                break
            if pkg_name is None and line.startswith("Name:"):
                pkg_name = re.sub(r"[-_.]+", "-", line[5:].strip().lower())
            elif pkg_ver is None and line.startswith("Version:"):
                pkg_ver = line[8:].strip()
            if pkg_name and pkg_ver:
                break
        if pkg_name and not pkg_name.startswith("pip"):
            installed[pkg_name] = pkg_ver
    return installed


def run_timed(cmd, env=None):
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)

    t0 = time.perf_counter()
    proc = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=merged_env
    )
    wall_ms = (time.perf_counter() - t0) * 1000.0
    if proc.returncode != 0:
        raise RuntimeError(f"Command failed ({cmd}):\nSTDOUT: {proc.stdout}\nSTDERR: {proc.stderr}")

    engine_ms = None
    m_res = re.search(r"Resolved \d+ packages in (\d+) ms", proc.stdout)
    m_dl = re.search(r"Unpacked \d+ wheels .* in (\d+) ms", proc.stderr)
    m_inst = re.search(r"(?:Installed|Audited) \d+ packages .* in (\d+) ms", proc.stdout)
    if m_res and m_inst:
        dl_val = int(m_dl.group(1)) if m_dl else 0
        engine_ms = int(m_res.group(1)) + dl_val + int(m_inst.group(1))

    return wall_ms, engine_ms


def benchmark_workload(title, packages, runs_warm=8):
    print(f"\n=========================================================================================================")
    print(f" WORKLOAD: {title} -> {', '.join(packages)}")
    print(f"=========================================================================================================")

    venv_dir = BENCH_WORK_DIR / "venv"
    py_exe = venv_dir / ("Scripts/python.exe" if os.name == "nt" else "bin/python")

    jolt_cmd_prefix, jolt_label = get_jolt_base_cmd()
    tools = [(jolt_label, "jolt")]
    if shutil.which("uv"):
        tools.append(("uv (Rust)", "uv"))
    tools.append(("pip (Python)", "pip"))

    results = []
    installed_snapshots = {}

    for label, kind in tools:
        reset_venv_packages(venv_dir)
        if kind == "jolt":
            shutil.rmtree(JOLT_CACHE_DIR, ignore_errors=True)
            cmd = jolt_cmd_prefix + ["install"] + packages + ["-p", str(venv_dir), "-v"]
            env = {"JOLT_CACHE_DIR": str(JOLT_CACHE_DIR)}
        elif kind == "uv":
            shutil.rmtree(UV_CACHE_DIR, ignore_errors=True)
            cmd = ["uv", "pip", "install"] + packages + ["--python", str(py_exe), "--cache-dir", str(UV_CACHE_DIR)]
            env = {}
        else:
            shutil.rmtree(PIP_CACHE_DIR, ignore_errors=True)
            cmd = [str(py_exe), "-m", "pip", "install"] + packages + ["--cache-dir", str(PIP_CACHE_DIR), "-q"]
            env = {}

        # 1. Cold Install
        print(f" -> {label:21s} [Cold Install]...", end="", flush=True)
        cold_wall, cold_eng = run_timed(cmd, env)
        eng_str = f" (Engine: {cold_eng} ms)" if cold_eng is not None else ""
        print(f" {cold_wall:7.1f} ms{eng_str}")

        # Verify installed packages snapshot
        installed_snapshots[kind] = inspect_installed_packages(venv_dir)

        # 2. Warm Install (Clean Venv, Warm Cache)
        warm_walls, warm_engs = [], []
        print(f" -> {label:21s} [Warm Install x{runs_warm}]...", end="", flush=True)
        for _ in range(runs_warm):
            reset_venv_packages(venv_dir)
            w_wall, w_eng = run_timed(cmd, env)
            warm_walls.append(w_wall)
            if w_eng is not None:
                warm_engs.append(w_eng)

        med_warm = statistics.median(warm_walls)
        p95_warm = p95(warm_walls)
        med_eng = statistics.median(warm_engs) if warm_engs else None
        eng_warm_str = f" | Engine Med: {med_eng:.0f} ms" if med_eng is not None else ""
        print(f" Med: {med_warm:6.1f} ms (p95: {p95_warm:6.1f} ms{eng_warm_str})")

        # 3. Warm Audit (Already Installed)
        audit_walls, audit_engs = [], []
        for _ in range(runs_warm):
            a_wall, a_eng = run_timed(cmd, env)
            audit_walls.append(a_wall)
            if a_eng is not None:
                audit_engs.append(a_eng)
        med_audit = statistics.median(audit_walls)
        med_audit_eng = statistics.median(audit_engs) if audit_engs else None

        results.append({
            "label": label,
            "kind": kind,
            "cold_wall": cold_wall,
            "warm_med": med_warm,
            "warm_p95": p95_warm,
            "warm_eng": med_eng,
            "audit_med": med_audit,
            "audit_eng": med_audit_eng,
        })

    # Verify environment equivalence
    jolt_pkgs = installed_snapshots.get("jolt", {})
    uv_pkgs = installed_snapshots.get("uv", jolt_pkgs)
    pip_pkgs = installed_snapshots.get("pip", jolt_pkgs)
    eq_status = "MATCH" if (jolt_pkgs == uv_pkgs == pip_pkgs) else f"DIFF (jolt={len(jolt_pkgs)}, uv={len(uv_pkgs)}, pip={len(pip_pkgs)})"
    print(f"\n [Verification] Installed Environment Equivalence ({len(jolt_pkgs)} packages): {eq_status}")

    print(f"{'Installer':<22} | {'Cold (ms)':>10} | {'Warm Med (ms)':>13} | {'Warm p95 (ms)':>13} | {'Engine Med':>10} | {'Audit Med (ms)':>14} | {'Audit Engine':>12} | {'vs pip':>7}")
    print("-" * 120)
    pip_warm = next((r["warm_med"] for r in results if r["kind"] == "pip"), results[-1]["warm_med"])
    for r in results:
        speedup = pip_warm / max(1.0, r["warm_med"])
        w_eng_s = f"{r['warm_eng']:.0f} ms" if r["warm_eng"] is not None else "N/A"
        a_eng_s = f"{r['audit_eng']:.1f} ms" if r["audit_eng"] is not None else "N/A"
        print(f"{r['label']:<22} | {r['cold_wall']:10.1f} | {r['warm_med']:13.1f} | {r['warm_p95']:13.1f} | {w_eng_s:>10} | {r['audit_med']:14.1f} | {a_eng_s:>12} | {speedup:6.1f}x")


def main():
    BENCH_WORK_DIR.mkdir(parents=True, exist_ok=True)
    venv_dir = BENCH_WORK_DIR / "venv"
    if not venv_dir.exists():
        subprocess.run([sys.executable, "-m", "venv", str(venv_dir)], check=True)

    benchmark_workload("1. Micro Tree (requests)", ["requests"], runs_warm=8)
    benchmark_workload("2. Async Web Stack (fastapi + uvicorn)", ["fastapi", "uvicorn"], runs_warm=5)
    benchmark_workload("3. Full Web Framework (django)", ["django"], runs_warm=5)
    benchmark_workload("4. Heavy Scientific Stack (numpy + pandas + scipy)", ["numpy", "pandas", "scipy"], runs_warm=3)


if __name__ == "__main__":
    main()