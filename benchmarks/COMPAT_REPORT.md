# Jolt Compatibility Matrix Report

- **Jolt Binary:** `D:\CODING NEW\Jolt\fast.cmd`
- **Python:** `3.12.2` (win32)
- **Workloads Tested:** 4

| Workload | Category | Install | Time (ms) | Imports | CLI | `uv pip check` | vs `uv` | vs `pip` |
| :--- | :--- | :---: | ---: | :---: | :---: | :---: | :---: | :---: |
| `requests` | Web & HTTP | PASS | 1913.3 | PASS | N/A | PASS | MATCH (5) | MATCH (5) |
| `google-auth` | Namespaces | PASS | 1779.7 | PASS | N/A | PASS | MATCH (6) | MATCH (6) |
| `pytest` | Console Scripts | PASS | 1867.0 | PASS | PASS | PASS | MATCH (6) | MATCH (6) |
| `httpx[http2]` | Extras & Markers | PASS | 2720.5 | PASS | N/A | PASS | MATCH (10) | MATCH (10) |
