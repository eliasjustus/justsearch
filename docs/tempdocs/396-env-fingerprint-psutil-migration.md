---
title: "396 — Migrate `env_fingerprint.py` probes to psutil"
---

# 396 — Migrate `env_fingerprint.py` probes to psutil

**Status:** IMPLEMENTED 2026-04-22. `env_fingerprint.py` migrated to
`psutil.process_iter` for top-N processes and `psutil.win_service_get`
for services. `powercfg` retained for power plan (no psutil equivalent).
`nvidia-smi` retained for GPU. Tests updated; `top_processes` is now
cross-platform. Verified live via a `capture_env_fingerprint()` call +
pipeline run. Closes 393 § 3.3 strategically.
**Created:** 2026-04-19 evening.
**Owner:** landed in this session.
**Scope:** Replace `subprocess`-based Windows-native probes in
`scripts/jseval/jseval/env_fingerprint.py` with `psutil` equivalents
for cross-platform parity and lower per-run overhead.
**Parent context:** Tempdoc 393 § 3.3 closed the immediate hang risk
on PowerShell-disabled boxes with a silent-skip tactical fix. This
doc captures the strategic migration that 3.3's tactical fix
deliberately avoided.

> NOTE: Noncanonical doc. Small-scope migration tempdoc. One owner,
> one pass, close when done.

---

## Current state (2026-04-19)

`env_fingerprint.py` uses 4 probes, all subprocess-based:

| Probe | Mechanism | Cross-platform? | Failure mode |
|---|---|---|---|
| `_probe_gpu` | `nvidia-smi` | NVIDIA-only | sticky-fails if unavailable |
| `_probe_power_plan` | `powercfg /getactivescheme` | Windows-only | returns `None` on non-Windows |
| `_probe_services` | `sc query <name>` per service (6 names) | Windows-only | returns `{}` on non-Windows |
| `_probe_top_processes` | `powershell -NoProfile -Command Get-Process | ConvertTo-Json` | Windows + PowerShell-enabled only | After 393 § 3.3: silent-skip if PowerShell missing |

Overhead per full probe (measured in a single jseval run):
- 4 subprocess spawns (gpu + powercfg + 1 per service × 6 + powershell).
- ~50-200 ms each on Windows, ~5-10 s worst-case on PowerShell-disabled boxes before 393 § 3.3.
- String parsing for `sc query` output.

---

## Proposed state

All process/service introspection via `psutil`. `nvidia-smi` stays
(psutil can't query NVIDIA VRAM). Concrete mapping:

| Probe | psutil replacement |
|---|---|
| `_probe_gpu` | **unchanged** — nvidia-smi is the correct tool |
| `_probe_power_plan` | Windows-specific; keep `powercfg` (psutil has no equivalent) OR drop the probe entirely if the data isn't load-bearing |
| `_probe_services` | `psutil.win_service_iter()` on Windows + graceful no-op elsewhere |
| `_probe_top_processes` | `psutil.process_iter(['name', 'pid', 'cpu_times', 'memory_info'])` cross-platform |

Benefits:
- Cross-platform top-processes (Linux/macOS get data too; currently return `[]`).
- No subprocess spawn overhead (~150-1000 ms saved per probe call).
- No string parsing fragility.
- psutil handles Windows services via its own native API — no `sc query` per-name.
- The PowerShell-hang edge case disappears naturally (no PowerShell dependency at all).

Costs:
- One new dep in `scripts/jseval/pyproject.toml`: `psutil>=5.9.0` (~1 MB, ubiquitous, well-maintained).
- Minor output shape verification — psutil's data model differs slightly from the current hand-rolled PowerShell/sc query output. Contract tests in `scripts/jseval/tests/` should pin the expected shape.

---

## Action items

1. **Add dep.** Add `psutil>=5.9.0` to `scripts/jseval/pyproject.toml`
   `[project].dependencies`.
2. **Migrate `_probe_top_processes`.** Use `psutil.process_iter` with
   the same sort (CPU time) and limit (5). Return shape unchanged:
   `[{"name", "pid", "cpu_seconds", "ws_mb"}, ...]`. Remove the
   PowerShell script. Remove the 393 § 3.3 silent-skip guard (no
   longer needed).
3. **Migrate `_probe_services`.** Use `psutil.win_service_iter()` on
   Windows; iterate the curated service-name list and read the
   `status()` from each matching service. Return shape unchanged
   (`{name: state}` map).
4. **Decide on `_probe_power_plan`.** Either keep `powercfg`
   (trivial, always present on Windows, no psutil equivalent) or
   drop the probe if it's not used downstream. Quick search:
   `grep -rn "power_plan\|powerPlan" scripts/jseval/` to decide.
5. **Update tests.** `scripts/jseval/tests/` probably has a contract
   test for env_fingerprint. Pin the shape, skip the probe-content
   assertions (system-dependent), assert the keys + types.
6. **Clean up.** Remove `_PROBE_TIMEOUT_S`, `_run`, and any
   now-unused imports (`subprocess`, maybe `platform` if all probes
   become psutil-driven).

---

## Out of scope

- `nvidia-smi` migration. psutil can't replace it. Keep as-is.
- Adding new probes (GPU power draw, disk I/O stats, etc.). That's a
  separate feature ask.
- Any change to the NDJSON output schema. Keep the existing
  env_fingerprint record shape so downstream consumers (provenance,
  history comparison) don't break.

---

## Exit criteria

- `psutil` listed in deps.
- All four probes pass their contract test on Windows.
- Top-processes probe returns non-empty on Linux (manual verify via
  `python -m jseval.env_fingerprint` or equivalent).
- `grep -n "subprocess\|powershell\|sc query" scripts/jseval/jseval/env_fingerprint.py`
  returns only the nvidia-smi call (if migration complete).
- Tempdoc 393 § 3.3 gets a one-line note pointing to this tempdoc as
  the strategic follow-up, which can be removed once this closes.

---

## Sources

- `scripts/jseval/jseval/env_fingerprint.py` — target file.
- `scripts/jseval/pyproject.toml` — dep declaration.
- Tempdoc 393 § 3.3 — the tactical fix that spawned this.
- psutil documentation: https://psutil.readthedocs.io/
