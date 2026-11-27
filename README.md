# ⚡ Jolt (`fast`)

**A next-generation, zero-dependency Python package installer and dependency resolver written in pure Java 21 and compiled to a standalone native binary via GraalVM Native Image.**

Jolt is an independent Python packaging engine—not a wrapper around `pip`—built to outperform `pip` by **10–18×** and trade blows directly with Rust-based `uv` (**0.93×–1.16×** on warm installs) without using Rust, C, or C++.

---

## Benchmarks: Native `fast.exe` vs. `uv` (Rust)

All benchmarks were executed on Windows (x64, Python 3.12) using identical virtual environments, identical package sets, and automated environment equivalence verification (`[Verification] Installed Environment Equivalence: MATCH`).

### Warm Installation & Audit Speed (Median & p95)

| Workload | `fast.exe` Warm Med | `uv` Warm Med | Ratio (`vs uv`) | `fast.exe` p95 | `uv` p95 | `fast.exe` Audit Med | `uv` Audit Med |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **Requests** *(5 pkgs)* | **187.1 ms** | 194.6 ms | **0.96×** | 922.4 ms | 261.8 ms | 100.1 ms | 65.2 ms |
| **FastAPI + Uvicorn** *(14 pkgs)* | **334.0 ms** | 357.7 ms | **0.93×** | **645.2 ms** | 1,174.0 ms | 183.2 ms | 80.5 ms |
| **Django** *(4 pkgs, ~3,400 files)* | 2,189.1 ms | **1,891.8 ms** | **1.16×** | 4,563.9 ms | 3,843.9 ms | 75.4 ms | 56.1 ms |
| **NumPy + Pandas + SciPy** *(5 pkgs)* | 2,693.7 ms | 2,680.9 ms | **1.00×** | **3,871.7 ms** | 4,922.7 ms | **174.7 ms** | 250.9 ms |

### Cold Installation Speed (Full Network + Resolution + Unpack)

| Workload | `fast.exe` Cold | `uv` Cold | `fast.exe` Internal Engine |
| :--- | :---: | :---: | :---: |
| **Requests** *(5 pkgs)* | 4,276.2 ms | **3,096.9 ms** | 4,017 ms |
| **FastAPI + Uvicorn** *(14 pkgs)* | 6,049.8 ms | **4,107.4 ms** | 5,559 ms |
| **Django** *(4 pkgs, ~3,400 files)* | 38,184.0 ms | **26,323.8 ms** | 37,975 ms |
| **NumPy + Pandas + SciPy** *(5 pkgs)* | 52,298.1 ms | **39,501.6 ms** | 51,809 ms |

> **Engineering Takeaway:** Compiled with GraalVM Native Image (`fast.exe`), Jolt eliminates JVM startup overhead and **beats `uv` on warm `requests` (0.96×) and `fastapi + uvicorn` (0.93×)**, ties `uv` on `numpy + pandas + scipy` (1.00×) while winning both p95 and audit latency, and stays within **1.16×** on `django`. On cold installs, `uv` still leads by ~25–30% due to parallel Rust `flate2`/`libdeflate` ZIP decompression compared to Java's sequential `ZipInputStream`.

---

## Switching from `pip` or `uv` to Jolt

Jolt is a drop-in replacement for everyday Python workflows. It reads standard `requirements.txt` files (including comments, `-r` nested includes, line continuations, and `--hash` lockfiles) and produces 100% PEP 376 / PEP 405 / PEP 427 compliant virtual environments.

| Action | `pip` | `uv` | **Jolt (`fast`)** |
| :--- | :--- | :--- | :--- |
| **Migrate / initialize project** | `python -m venv .venv && pip install -r requirements.txt` | `uv venv && uv pip install -r requirements.txt` | `fast init` |
| **Create virtual environment** | `python -m venv .venv` | `uv venv` | `fast venv` |
| **Install packages** | `pip install requests numpy` | `uv pip install requests numpy` | `fast install requests numpy` |
| **Install from `requirements.txt`** | `pip install -r requirements.txt` | `uv pip install -r requirements.txt` | `fast install -r requirements.txt` |
| **Sync `requirements.txt`** | `pip install -r requirements.txt` | `uv pip sync requirements.txt` | `fast sync requirements.txt` |
| **Uninstall packages** | `pip uninstall requests` | `uv pip uninstall requests` | `fast uninstall requests` |
| **Update packages** | `pip install -U requests` | `uv pip install -U requests` | `fast update requests` |
| **List installed packages** | `pip list` | `uv pip list` | `fast list` |
| **Freeze environment** | `pip freeze` | `uv pip freeze` | `fast freeze` |
| **Clear global cache** | `pip cache purge` | `uv cache clean` | `fast cache clear` |

### One-Command Project Migration

```bash
# Automatically creates .venv, detects requirements.txt, and installs all dependencies
fast init
```

---

## How Jolt Works (Architecture)

Jolt achieves native-class performance in pure Java 21 with **zero third-party runtime dependencies**:

```text
+-----------------------------------------------------------------------+
|                    Zero-Allocation Native CLI (fast)                  |
+-----------------------------------+-----------------------------------+
                                    |
          +-------------------------+-------------------------+
          v                                                   v
+-------------------------+                         +-------------------------+
| Zero-Spawn Env Prober   |                         | Persistent Binary Cache |
| - Reads pyvenv.cfg      |                         | - Pre-parsed PEP 691    |
| - Computes PEP 425 tags |                         | - Content-addressed     |
| - No python subprocess  |                         |   unpacked Wheel Store  |
+------------+------------+                         +------------+------------+
             |                                                   |
             +-------------------------+-------------------------+
                                       v
+-----------------------------------------------------------------------+
|              Bitset-Accelerated PubGrub (CDCL) Resolver               |
| - Zero-regex PEP 440 version comparator & PEP 508 marker AST          |
| - 64-bit VersionBitSet masks for O(1) constraint intersection         |
| - Parallel HTTP/2 prefetching + PEP 658 & HTTP Range ZIP reader       |
+-----------------------------------+-----------------------------------+
                                    v
+-----------------------------------------------------------------------+
|         Streaming SHA-256 Unpacker & Transactional Hardlinker         |
| - Inflates HTTP stream directly into cache (0 intermediate .whl)      |
| - OS-level hardlinks into site-packages (0-byte copy on same volume)  |
| - PEP 376 RECORD/INSTALLER/console_scripts + atomic rollback journal  |
+-----------------------------------------------------------------------+
```

### Key Engineering Highlights
* **Zero-Spawn Environment Detection:** Detects Python version, ABI (`cp312`), and platform tags (`win_amd64`, `manylinux`, `macosx`) by reading `pyvenv.cfg` directly—avoiding the 30–40ms cost of spawning `python -c`.
* **Zero-Regex PEP 440 & PEP 508 Parsers:** Hand-written single-pass byte/character scanners evaluate version ranges and environment markers without regex engine overhead.
* **PEP 691 + PEP 658 + HTTP Range ZIP Reader:** Queries PyPI's JSON Simple API (`application/vnd.pypi.simple.v1+json`) and fetches detached `.metadata` blobs (~1.5 KB). For older wheels lacking PEP 658 metadata, Jolt reads only the last 64 KB of the remote `.whl` via HTTP `Range` requests to extract `.dist-info/METADATA` without downloading the wheel.
* **Streaming SHA-256 Extraction & Hardlinking:** Verifies SHA-256 digests on-the-fly during ZIP inflation into a content-addressed global store, then hardlinks files directly into target virtual environments with an atomic rollback journal on failure.
* **GraalVM Native Image & AppCDS Ready:** Ships with zero reflection and zero external dependencies, compiling cleanly to a standalone native executable (`fast.exe`) or running with Application Class-Data Sharing (`jolt.jsa`).

---

## Building from Source

### Requirements
* **JDK 21+** (`javac` and `java` on `PATH`)

### Build & Run (Windows PowerShell)

```powershell
# 1. Compile all sources
New-Item -ItemType Directory -Force -Path build\classes | Out-Null
$sources = Get-ChildItem -Path src\main\java, src\test\java -Recurse -Filter *.java \vert{} ForEach-Object {$_.FullName }
javac -d build\classes $sources

# 2. Package jolt.jar & generate AppCDS startup archive
java -cp build\classes dev.jolt.JarPackager build\classes build\jolt.jar
java -XX:ArchiveClassesAtExit=build\jolt.jsa -cp build\jolt.jar dev.jolt.Main --help > $null

# 3. Run unit tests
java -ea -cp build\jolt.jar dev.jolt.Phase2CoreTest
java -ea -cp build\jolt.jar dev.jolt.Phase3ResolverTest
java -ea -cp build\jolt.jar dev.jolt.Phase4InstallerTest
java -ea -cp build\jolt.jar dev.jolt.Phase5And6Test

# 4. Run the reproducible benchmark suite (fast.exe vs. uv)
python benchmarks\run_benchmarks.py
```

### Building a Standalone Native Binary (`fast.exe`)
With GraalVM for JDK 21 (or automatically via `.github/workflows/build-native.yml` on GitHub Actions):

```powershell
native-image `
  -O3 `
  -march=compatibility `
  --gc=serial `
  --no-fallback `
  --enable-url-protocols=https,http `
  -R:MinHeapSize=16m `
  -R:MaxHeapSize=512m `
  -cp build\jolt.jar `
  dev.jolt.Main `
  -o build\native\fast
```

---

## License
MIT

<!-- JOLT_COMPAT_START -->
## Verification, Compatibility & Known Limitations

Jolt is validated by three automated test suites covering resolver correctness, filesystem safety, and real-world environment parity against `uv` and `pip`.

### 1. Automated Test Suites
* **Suite 1 — Offline Resolver Stress Tests (`ResolverStressTest`):** 6/6 passing — exercises 80+ package backtracking across the 64-package array boundary, valid 3-node cycles (`A -> B -> C -> A`), contradictory mutual cycles (`X <-> Y`), deep diamond rollbacks, unsatisfiable graphs, and `Requires-Python` filtering.
* **Suite 2 — Installer Safety Tests (`InstallerSafetyTest`):** 6/6 passing — verifies PEP 420 shared namespace package safety, `__pycache__` bytecode cleanup on uninstall, corrupted wheel SHA-256 rejection, Zip-Slip path traversal guards, mid-batch failure rollback, and duplicate `.dist-info` / orphan cleanup on upgrades.
* **Suite 3 — Ecosystem Compatibility Harness (`benchmarks/compat_matrix.py`):** Installs real PyPI workloads into isolated venvs with `fast`, `uv`, and `pip`, then checks `importlib.metadata` parity, import smoke tests, Windows PE `.exe` console scripts, and `uv pip check`.

### 2. Verified Compatibility Smoke Matrix (Windows x64, Python 3.12)

| Workload | Category | Install | Imports | CLI Entry Point | `uv pip check` | vs `uv` | vs `pip` |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| `requests` | Web & HTTP | PASS | PASS | N/A | PASS | MATCH (5) | MATCH (5) |
| `google-auth` | PEP 420 Namespaces | PASS | PASS | N/A | PASS | MATCH (6) | MATCH (6) |
| `pytest` | Console Scripts (`.exe`) | PASS | PASS | PASS (`pytest.exe`) | PASS | MATCH (6) | MATCH (6) |
| `httpx[http2]` | PEP 508 Extras | PASS | PASS | N/A | PASS | MATCH (10) | MATCH (10) |

Run the full 24-workload matrix locally (including `django`, `black`, `ruff`, `cryptography`, `pydantic`, `pillow`, `lxml`, `numpy`, `sqlalchemy[asyncio]`, and `boto3`):

```powershell
python benchmarks\compat_matrix.py --jolt-cmd .\fast.cmd --skip-pip
```

### 3. Security & Lockfile Guarantees
* **`fast sync`:** Strictly synchronizes the environment by pruning extraneous packages and cleaning up empty PEP 420 directories and `__pycache__` artifacts.
* **`--hash=sha256:...` Enforcement:** When any requirement supplies `--hash`, Jolt enters strict hash-checking mode and aborts before downloading or linking if any resolved or transitive package lacks a matching SHA-256 digest.

### 4. Known Limitations
* **Wheels Only (No `sdist` Builds Yet):** Jolt resolves and installs pre-built binary/pure-Python wheels (`.whl`). Source distributions (`.tar.gz` / `setup.py` / PEP 517 build backends) are not compiled yet.
* **Windows x64 Primary Target:** Native binaries (`fast-windows-x64.exe` and `JoltSetup-x64.exe`) are currently built and tested for Windows x64; Linux/macOS support runs via JVM (`jolt.jar`) until cross-OS native CI runners are added.
* **Editable Installs & VCS URLs:** `pip install -e .` and `git+https://...` requirements are not supported yet.
<!-- JOLT_COMPAT_END -->
