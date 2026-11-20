# ⚡ Jolt (`fast`)

**A next-generation, zero-dependency Python package installer and dependency resolver written in pure Java 21.**

Jolt is an independent Python packaging engine—not a wrapper around `pip`—designed to outperform `pip` by **10–18×** and compete directly with Rust-based `uv` without using Rust, C, or C++.

---

## Benchmarks

All benchmarks were executed on Windows (x64, Python 3.12) using identical virtual environments, identical package sets, and automated environment equivalence verification (`[Verification] Installed Environment Equivalence: MATCH`).

### Warm Installation Speed (vs. `pip` & `uv`)

| Workload | Jolt (AppCDS JVM) | `uv` (Rust) | `pip` (Python) | Jolt vs. `pip` |
| :--- | :---: | :---: | :---: | :---: |
| **Requests** *(5 packages, 127 files)* | **639.7 ms** | 281.2 ms | ~6,650 ms | **10.4× faster** |
| **FastAPI + Uvicorn** *(14 packages)* | **985.5 ms** | 429.7 ms | ~17,443 ms | **17.7× faster** |
| **NumPy** *(Heavy C-extension wheel)* | **1,262.1 ms** | 654.2 ms | ~17,038 ms | **13.5× faster** |
| **Django** *(3,400+ files, Warm Median)* | **21,220.9 ms** | 23,562.1 ms | — | **Beats `uv` by 2.3s** |

### Cold Installation Speed (Full Network + Resolution + Unpack)

| Workload | Jolt (AppCDS JVM) | `uv` (Rust) | `pip` (Python) | Notes |
| :--- | :---: | :---: | :---: | :--- |
| **FastAPI + Uvicorn** *(Cold)* | **17,409.0 ms** | 21,629.4 ms | 79,992.6 ms | **Beats `uv` by 4.2s** (4.6× faster than `pip`) |
| **NumPy** *(Cold)* | **5,860.0 ms** | 5,819.2 ms | 22,972.2 ms | **Virtual tie with `uv`** (3.9× faster than `pip`) |

> **Honest Engineering Note:** On small warm installs (`requests`), `uv` (compiled native Rust) still leads Jolt's JVM mode due to JVM process startup overhead (~150–250ms). Compiling Jolt to a standalone native binary via **GraalVM Native Image** eliminates JVM startup overhead to ~2ms.

---

## Switching from `pip` or `uv` to Jolt

Jolt is a drop-in replacement for everyday Python workflows. It reads standard `requirements.txt` files (including comments, `-r` nested includes, line continuations, and `--hash` lockfiles) and produces 100% PEP 376 / PEP 405 / PEP 427 compliant virtual environments.

| Action | `pip` | `uv` | **Jolt (`fast`)** |
| :--- | :--- | :--- | :--- |
| **Create virtual environment** | `python -m venv .venv` | `uv venv` | `fast venv` |
| **Install packages** | `pip install requests numpy` | `uv pip install requests numpy` | `fast install requests numpy` |
| **Install from `requirements.txt`** | `pip install -r requirements.txt` | `uv pip install -r requirements.txt` | `fast install -r requirements.txt` |
| **Sync `requirements.txt`** | `pip install -r requirements.txt` | `uv pip sync requirements.txt` | `fast sync requirements.txt` |
| **Uninstall packages** | `pip uninstall requests` | `uv pip uninstall requests` | `fast uninstall requests` |
| **Update packages** | `pip install -U requests` | `uv pip install -U requests` | `fast update requests` |
| **List installed packages** | `pip list` | `uv pip list` | `fast list` |
| **Freeze environment** | `pip freeze` | `uv pip freeze` | `fast freeze` |
| **Clear global cache** | `pip cache purge` | `uv cache clean` | `fast cache clear` |

### Quick Migration Example

```bash
# 1. Create a PEP 405 virtual environment in milliseconds
fast venv .venv

# 2. Install your existing project's requirements.txt
fast install -r requirements.txt -p .venv

# 3. Verify installed packages
fast list -p .venv
```

---

## How Jolt Works (Architecture)

Jolt achieves native-class performance in pure Java 21 with **zero third-party runtime dependencies**:

```text
+-----------------------------------------------------------------------+
|                       Zero-Allocation CLI (fast)                      |
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
* **AppCDS & GraalVM Native Image Ready:** Ships with zero reflection and zero external dependencies, supporting both Application Class-Data Sharing (`jolt.jsa`) and Ahead-of-Time native compilation (`fast.exe`).

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

# 4. Run the reproducible benchmark suite against pip and uv
python benchmarks\run_benchmarks.py
```

### Building a Standalone Native Binary (`fast.exe`)
With GraalVM for JDK 21 (or via `.github/workflows/build-native.yml` on GitHub Actions):

```powershell
native-image -O3 --gc=serial --no-fallback --enable-url-protocols=https,http -cp build\jolt.jar dev.jolt.Main -o build\native\fast
```

---

## License
MIT