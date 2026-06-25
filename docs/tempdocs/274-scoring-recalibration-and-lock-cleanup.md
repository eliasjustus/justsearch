---
title: "274 — Scoring Model Recalibration & Windows Lock Cleanup"
---

# 274 — Scoring Model Recalibration & Windows Lock Cleanup

**Status:** DONE — merged to `main` 2026-03-11 (`b8480d6f`)
**Created:** 2026-03-11
**Scope:** Two independent improvements from gap analysis of tmp/ artifacts against tempdocs 262-265/272.

---

## Summary

### Lane A: Scoring Model Recalibration

Validated the scoring model at N=123 sessions. Key findings and actions:

- **Score distribution**: Fully resolved. Range expanded from 22 points (N=11) to 81 points (N=123). Band distribution is healthy: 15% bad, 9% poor, 26% fair, 28% good, 23% excellent.
- **CONTEXT_PRESSURE flag removed**: Zero predictive validity at N=123 (flagged mean=66.0 vs non-flagged mean=66.8, delta=-0.8). Removed from `score-session.mjs`, `test-pipeline.mjs`, and `generate-dashboard.mjs`.
- **Ceiling clipping**: Well-calibrated. All 7 signals clip 0-7% of sessions.
- **Signal redundancy**: `unbounded_read_pct` vs `hot_file_concentration` correlation r=0.259 — not redundant.
- **Per-type ceilings**: Active for 2 types (unknown, investigation at N>=20), partially active for 3. The `unknown` bucket (56% at N=135) is the binding constraint — see Data Gap Analysis below.

### Lane B: Windows Lock File Cleanup

Eliminated the orphan-Worker-holds-stale-locks failure class through layered fixes:

- **B0. WindowsJobObject (root fix)**: FFM-based (`java.lang.foreign`) wrapper for Win32 Job Objects with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`. When Head exits (clean or crash), the OS kills Worker. Implemented in `app-util`, wired into `WorkerSpawner`. No external dependencies.
- **B1. Lifecycle cleanup**: Added `app.lock` and `*.index.lock` to `lifecycle-cleanup.mjs` artifact detection.
- **B2. Orphan kill before cleanup**: `killOrphanFromLockMetadata()` reads PID from lock files, kills if alive, before artifact removal.
- **B3. Backend stop**: Already comprehensive (`taskkill /T /F`, port poll, orphan reap). No changes needed.
- **B4. Stale-lock recovery in Java**: Both `AppInstanceLock` and `IndexRootLock` now detect dead PIDs via `ProcessHandle.of(pid)` + `startInstant()` mismatch, delete stale lock files, and retry once.

### Key Design Decisions

- **FFM over JNA** for Win32 calls: No dependency, AOT-compatible, JDK-standard. JNA+AOT Cache compatibility was unvalidated.
- **Recovery does NOT kill alive processes**: Only acts when PID is confirmed dead or reused (startInstant mismatch). Prevents killing slow-starting legitimate instances.
- **On Windows, `FileLock` is mandatory**: A lock from a dead process cannot persist — the kernel releases on exit. "Stale locks" = orphan processes, which Job Objects eliminate.

## Verification (2026-03-11)

| # | Experiment | Result | Evidence |
|---|-----------|--------|----------|
| 1 | Scoring pipeline | PASS | 123 sessions scored, 0 CONTEXT_PRESSURE flags |
| 2 | Lock file cleanup | PASS | `app.lock` and `*.index.lock` detected and removed by `none` mode |
| 3 | Orphan kill | PASS | Live process killed via PID from lock metadata before cleanup |
| 4 | Java dead-PID recovery | PASS | Both lock classes recover from locks left by recently-exited PIDs |
| 5 | Job Object kill | PASS | Force-killing Head without `/T` also killed Worker via Job Object |
| 6 | Crash-to-recovery round-trip | PASS | `--clean none` restart detected 4 stale artifacts, cleaned all, started in 3.8s |

---

## Data Gap Analysis (2026-03-11, updated with N=135 data)

The scoring model works but is constrained by data quality. All advanced scoring approaches are blocked by the same gap: **session type classification**.

| Metric | Value | Trend |
|--------|-------|-------|
| Session files on disk | 335 | |
| Scoreable (>=10 tool calls) | 135 | Growing |
| With type label | 60 (44%) | **Declining** (was 49% at N=123) |
| `unknown` type | 75 (56%) | Growing faster than labeled sessions |

Per-type populations:

| Type | N | Enough for per-type ceilings (N>=20)? | Enough for path convergence (~20 "good")? |
|------|---|---------------------------------------|-------------------------------------------|
| unknown | 75 | Yes (but meaningless) | N/A |
| investigation | 27 | Yes | No — no outcome labels |
| refactor | 14 | No | No |
| feature | 9 | No | No |
| implementation | 8 | No | No |
| docs | 1 | No | No |
| chore | 1 | No | No |

### What this means for future scoring improvements

| Approach | Prerequisite | Current state | Blocked by |
|----------|-------------|---------------|------------|
| Path convergence scoring | ~20 reference sessions per type | 0 outcome-labeled sessions | Type classification + outcome annotation |
| PCA-based weight tuning | N>=500 scoreable | N=135 (27%) | Volume — weeks away at current rate |
| Cost-of-pass metric | Token counts + outcomes | No token telemetry emitted | Telemetry instrumentation + outcome annotation |
| Turn-level credit assignment | Per-action reward instrumentation | Not started | Major instrumentation change |

### The binding constraint

The `unknown` bucket is growing proportionally (51% → 56%). New sessions are less likely to be type-classified than old ones, suggesting the classification is manual/ad-hoc rather than systematic. **Automated or semi-automated session type classification** would unblock:
1. Per-type ceiling effectiveness (A5) — currently only 2 types qualify
2. Path convergence scoring — requires typed reference trajectories
3. Cost-of-pass — requires outcome annotation, which depends on type classification

This is the single highest-leverage improvement to the scoring infrastructure. PCA weight tuning will resolve itself with volume growth; the other three require active work on classification.
