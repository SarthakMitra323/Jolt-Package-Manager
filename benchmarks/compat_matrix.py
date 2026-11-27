#!/usr/bin/env python3
"""
Suite 3: Jolt (fast) Real-World Ecosystem Compatibility Matrix.
Compares Jolt's resulting virtual environments against uv and pip across 24 real PyPI
workloads: distribution parity, import smoke tests, console script entry points,
and `uv pip check` environment validation.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple


@dataclass(frozen=True)
class Workload:
    name: str
    category: str
    specs: List[str]
    imports: List[str]
    cli_checks: List[List[str]] = field(default_factory=list)


WORKLOADS: List[Workload] = [
    # 1. Pure Python & Web Stacks
    Workload("requests", "Web & HTTP", ["requests"], ["requests"]),
    Workload("flask", "Web & HTTP", ["flask"], ["flask"], [["flask", "--version"]]),
    Workload("fastapi+uvicorn", "Web & HTTP", ["fastapi", "uvicorn"], ["fastapi", "uvicorn"], [["uvicorn", "--version"]]),
    Workload("django", "Web & HTTP", ["django"], ["django"], [["django-admin", "--version"]]),
    Workload("click", "Web & HTTP", ["click"], ["click"]),
    Workload("jinja2", "Web & HTTP", ["jinja2"], ["jinja2"]),

    # 2. Namespace Packages (PEP 420 & legacy shared namespaces)
    Workload("google-auth", "Namespaces", ["google-auth"], ["google.auth"]),
    Workload("zope.interface", "Namespaces", ["zope.interface"], ["zope.interface"]),
    Workload("jaraco.classes", "Namespaces", ["jaraco.classes"], ["jaraco.classes"]),
    Workload(
        "opentelemetry",
        "Namespaces",
        ["opentelemetry-api", "opentelemetry-sdk"],
        ["opentelemetry.trace", "opentelemetry.sdk.trace"],
    ),

    # 3. Console Scripts & CLI Tools
    Workload("pytest", "Console Scripts", ["pytest"], ["pytest"], [["pytest", "--version"]]),
    Workload("black", "Console Scripts", ["black"], ["black"], [["black", "--version"]]),
    Workload("ruff", "Console Scripts", ["ruff"], ["ruff"], [["ruff", "--version"]]),
    Workload("rich", "Console Scripts", ["rich"], ["rich"]),
    Workload("typer", "Console Scripts", ["typer"], ["typer"], [["typer", "--help"]]),

    # 4. C / Rust / ABI3 Native Extensions
    Workload("pydantic", "Native Extensions", ["pydantic"], ["pydantic", "pydantic_core"]),
    Workload("cryptography", "Native Extensions", ["cryptography"], ["cryptography", "cryptography.fernet"]),
    Workload("pillow", "Native Extensions", ["pillow"], ["PIL", "PIL.Image"]),
    Workload("orjson", "Native Extensions", ["orjson"], ["orjson"]),
    Workload("lxml", "Native Extensions", ["lxml"], ["lxml", "lxml.etree"]),
    Workload("numpy", "Native Extensions", ["numpy"], ["numpy"]),

    # 5. Extras & Environment Markers
    Workload("httpx[http2]", "Extras & Markers", ["httpx[http2]"], ["httpx", "h2"]),
    Workload("sqlalchemy[asyncio]", "Extras & Markers", ["sqlalchemy[asyncio]"], ["sqlalchemy", "greenlet"]),
    Workload("boto3", "Extras & Markers", ["boto3"], ["boto3", "botocore"]),
]

BASELINE_IGNORE = {"pip", "setuptools", "wheel"}


def norm_pkg(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def venv_python(venv_dir: Path) -> Path:
    if os.name == "nt":
        return venv_dir / "Scripts" / "python.exe"
    return venv_dir / "bin" / "python"


def venv_scripts(venv_dir: Path) -> Path:
    return venv_dir / ("Scripts" if os.name == "nt" else "bin")


def run_cmd(cmd: List[str], timeout: int = 180) -> Tuple[int, str, float]:
    t0 = time.perf_counter()
    proc = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )
    elapsed_ms = (time.perf_counter() - t0) * 1000.0
    return proc.returncode, proc.stdout.strip(), elapsed_ms


def read_installed_dists(py_exe: Path) -> Dict[str, str]:
    code = (
        "import importlib.metadata as m, json, re; "
        "d = {re.sub(r'[-_.]+', '-', (x.metadata['Name'] or '')).lower(): x.version "
        "for x in m.distributions() if x.metadata['Name']}; "
        "print(json.dumps(d))"
    )
    rc, out, _ = run_cmd([str(py_exe), "-c", code], timeout=30)
    if rc != 0:
        raise RuntimeError(f"Failed to read metadata from {py_exe}: {out}")
    raw: Dict[str, str] = json.loads(out.splitlines()[-1])
    return {k: v for k, v in raw.items() if k not in BASELINE_IGNORE}


def diff_dists(jolt_dists: Dict[str, str], ref_dists: Dict[str, str]) -> List[str]:
    diffs: List[str] = []
    all_keys = sorted(set(jolt_dists.keys()).union(ref_dists.keys()))
    for k in all_keys:
        jv = jolt_dists.get(k)
        rv = ref_dists.get(k)
        if jv is None:
            diffs.append(f"missing {k}=={rv}")
        elif rv is None:
            diffs.append(f"extra {k}=={jv}")
        elif jv != rv:
            diffs.append(f"version {k}: jolt={jv} vs ref={rv}")
    return diffs


def resolve_jolt_cmd(explicit: Optional[str]) -> List[str]:
    if explicit:
        return [explicit]
    candidates = [
        Path("build/native/fast.exe"),
        Path("fast.cmd"),
    ]
    for c in candidates:
        if c.is_file():
            return [str(c.resolve())]
    found = shutil.which("fast")
    if found:
        return [found]
    raise FileNotFoundError("Could not locate fast.exe, fast.cmd, or 'fast' on PATH.")


def test_workload(
    wl: Workload,
    jolt_cmd: List[str],
    uv_bin: Optional[str],
    use_pip: bool,
    scratch: Path,
) -> Dict[str, object]:
    safe_dir = re.sub(r"[^a-zA-Z0-9._-]+", "_", wl.name)
    root = scratch / safe_dir
    if root.exists():
        shutil.rmtree(root, ignore_errors=True)
    root.mkdir(parents=True, exist_ok=True)

    jolt_venv = root / "venv-jolt"
    uv_venv = root / "venv-uv"
    pip_venv = root / "venv-pip"

    result: Dict[str, object] = {
        "name": wl.name,
        "category": wl.category,
        "jolt_ms": 0.0,
        "jolt_install": "FAIL",
        "imports": "SKIP",
        "cli": "N/A" if not wl.cli_checks else "SKIP",
        "uv_check": "SKIP",
        "vs_uv": "SKIP",
        "vs_pip": "SKIP",
        "notes": [],
    }
    notes: List[str] = result["notes"]  # type: ignore[assignment]

    # 1. Create venv & install with Jolt
    rc, out, _ = run_cmd(jolt_cmd + ["venv", str(jolt_venv)], timeout=60)
    if rc != 0:
        notes.append(f"jolt venv failed: {out[:200]}")
        return result

    rc, out, jolt_ms = run_cmd(jolt_cmd + ["install", "-p", str(jolt_venv)] + wl.specs, timeout=180)
    result["jolt_ms"] = round(jolt_ms, 1)
    if rc != 0:
        notes.append(f"jolt install failed: {out[:260]}")
        return result
    result["jolt_install"] = "PASS"

    jolt_py = venv_python(jolt_venv)
    jolt_dists = read_installed_dists(jolt_py)

    # 2. Import smoke tests
    import_failures: List[str] = []
    for mod in wl.imports:
        irc, iout, _ = run_cmd([str(jolt_py), "-c", f"import {mod}"], timeout=30)
        if irc != 0:
            import_failures.append(f"{mod}: {iout.splitlines()[-1] if iout else 'error'}")
    if import_failures:
        result["imports"] = "FAIL"
        notes.append("imports failed -> " + "; ".join(import_failures))
    else:
        result["imports"] = "PASS"

    # 3. Console script entry points
    if wl.cli_checks:
        scripts_dir = venv_scripts(jolt_venv)
        cli_failures: List[str] = []
        for check in wl.cli_checks:
            exe_name = check[0] + (".exe" if os.name == "nt" else "")
            exe_path = scripts_dir / exe_name
            if not exe_path.is_file():
                cli_failures.append(f"missing {exe_name}")
                continue
            crc, cout, _ = run_cmd([str(exe_path)] + check[1:], timeout=30)
            if crc != 0:
                cli_failures.append(f"{exe_name} rc={crc}: {cout[:120]}")
        if cli_failures:
            result["cli"] = "FAIL"
            notes.append("cli failed -> " + "; ".join(cli_failures))
        else:
            result["cli"] = "PASS"

    # 4. uv pip check on Jolt's venv + uv distribution parity
    if uv_bin:
        crc, cout, _ = run_cmd([uv_bin, "pip", "check", "--python", str(jolt_py)], timeout=30)
        if crc == 0:
            result["uv_check"] = "PASS"
        else:
            result["uv_check"] = "FAIL"
            notes.append(f"uv pip check -> {cout[:220]}")

        rc_uv_v, _, _ = run_cmd([uv_bin, "venv", str(uv_venv), "--python", sys.executable], timeout=60)
        if rc_uv_v == 0:
            rc_uv_i, out_uv_i, _ = run_cmd(
                [uv_bin, "pip", "install", "--python", str(venv_python(uv_venv))] + wl.specs,
                timeout=180,
            )
            if rc_uv_i == 0:
                uv_dists = read_installed_dists(venv_python(uv_venv))
                diffs = diff_dists(jolt_dists, uv_dists)
                if not diffs:
                    result["vs_uv"] = f"MATCH ({len(jolt_dists)})"
                else:
                    result["vs_uv"] = f"DIFF ({len(diffs)})"
                    notes.append("vs uv -> " + ", ".join(diffs[:4]))
            else:
                result["vs_uv"] = "UV_ERR"
                notes.append(f"uv install failed: {out_uv_i[:120]}")

    # 5. pip distribution parity (optional)
    if use_pip:
        rc_pv, _, _ = run_cmd([sys.executable, "-m", "venv", str(pip_venv)], timeout=90)
        if rc_pv == 0:
            pip_py = venv_python(pip_venv)
            rc_pi, out_pi, _ = run_cmd(
                [str(pip_py), "-m", "pip", "install", "--quiet", "--disable-pip-version-check"] + wl.specs,
                timeout=240,
            )
            if rc_pi == 0:
                pip_dists = read_installed_dists(pip_py)
                diffs = diff_dists(jolt_dists, pip_dists)
                if not diffs:
                    result["vs_pip"] = f"MATCH ({len(jolt_dists)})"
                else:
                    result["vs_pip"] = f"DIFF ({len(diffs)})"
                    notes.append("vs pip -> " + ", ".join(diffs[:4]))
            else:
                result["vs_pip"] = "PIP_ERR"
                notes.append(f"pip install failed: {out_pi[:120]}")

    shutil.rmtree(root, ignore_errors=True)
    return result


def write_markdown_report(results: List[Dict[str, object]], out_path: Path, jolt_cmd: List[str]) -> None:
    lines = [
        "# Jolt Compatibility Matrix Report",
        "",
        f"- **Jolt Binary:** `{jolt_cmd[0]}`",
        f"- **Python:** `{sys.version.split()[0]}` ({sys.platform})",
        f"- **Workloads Tested:** {len(results)}",
        "",
        "| Workload | Category | Install | Time (ms) | Imports | CLI | `uv pip check` | vs `uv` | vs `pip` |",
        "| :--- | :--- | :---: | ---: | :---: | :---: | :---: | :---: | :---: |",
    ]
    for r in results:
        lines.append(
            f"| `{r['name']}` | {r['category']} | {r['jolt_install']} | {r['jolt_ms']} | "
            f"{r['imports']} | {r['cli']} | {r['uv_check']} | {r['vs_uv']} | {r['vs_pip']} |"
        )

    failures = [r for r in results if r["notes"]]
    if failures:
        lines.extend(["", "## Diagnostics & Differences", ""])
        for r in failures:
            lines.append(f"### `{r['name']}`")
            for n in r["notes"]:  # type: ignore[union-attr]
                lines.append(f"- {n}")
            lines.append("")

    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Jolt Ecosystem Compatibility Matrix (Suite 3)")
    parser.add_argument("--jolt-cmd", help="Path to fast.exe or fast.cmd")
    parser.add_argument("--filter", help="Comma-separated substrings to select specific workloads")
    parser.add_argument("--skip-pip", action="store_true", help="Skip pip baseline comparison for faster runs")
    parser.add_argument("--report", default="benchmarks/COMPAT_REPORT.md", help="Output Markdown report path")
    args = parser.parse_args()

    jolt_cmd = resolve_jolt_cmd(args.jolt_cmd)
    uv_bin = shutil.which("uv")
    selected = WORKLOADS
    if args.filter:
        tokens = [t.strip().lower() for t in args.filter.split(",") if t.strip()]
        selected = [w for w in WORKLOADS if any(t in w.name.lower() or t in w.category.lower() for t in tokens)]

    print(f"Jolt command : {jolt_cmd[0]}")
    print(f"uv binary    : {uv_bin or 'NOT FOUND (skipping uv checks)'}")
    print(f"pip baseline : {'SKIPPED' if args.skip_pip else 'ENABLED'}")
    print(f"Workloads    : {len(selected)}\n")

    header = f"{'Workload':<22} {'Install':<8} {'ms':>7} {'Imports':<8} {'CLI':<6} {'uvCheck':<8} {'vs uv':<12} {'vs pip':<12}"
    print(header)
    print("-" * len(header))

    scratch = Path(tempfile.mkdtemp(prefix="jolt-compat-"))
    results: List[Dict[str, object]] = []
    try:
        for wl in selected:
            r = test_workload(wl, jolt_cmd, uv_bin, not args.skip_pip, scratch)
            results.append(r)
            print(
                f"{str(r['name']):<22} {str(r['jolt_install']):<8} {float(r['jolt_ms']):>7.1f} "
                f"{str(r['imports']):<8} {str(r['cli']):<6} {str(r['uv_check']):<8} "
                f"{str(r['vs_uv']):<12} {str(r['vs_pip']):<12}"
            )
            for n in r["notes"]:  # type: ignore[union-attr]
                print(f"    -> {n}")
    finally:
        shutil.rmtree(scratch, ignore_errors=True)

    report_path = Path(args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    write_markdown_report(results, report_path, jolt_cmd)

    hard_fails = [
        r for r in results
        if r["jolt_install"] != "PASS"
        or r["imports"] != "PASS"
        or r["cli"] == "FAIL"
        or r["uv_check"] == "FAIL"
    ]
    print(f"\nReport written to {report_path}")
    print(f"Summary: {len(results) - len(hard_fails)}/{len(results)} passed all core checks.")
    return 1 if hard_fails else 0


if __name__ == "__main__":
    sys.exit(main())