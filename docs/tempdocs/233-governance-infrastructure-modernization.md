---
title: Governance Infrastructure Modernization
status: done
created: 2026-02-22
updated: 2026-02-25
---

# 233: Governance Infrastructure Modernization

> Replaced 9 imperative PS1 orchestrators (~5,600 lines) with declarative Node.js DAG
> runners (~8,800 lines including tests). See [ADR-0009](../decisions/0009-custom-dag-engine-ci-orchestration.md)
> for the architectural decision rationale.

## Current Status Summary

### What's Done

| Phase | Tier | Scope | Runner | PS1 Replaced | New Code | Tests |
|-------|------|-------|--------|-------------|----------|-------|
| 0 | A | Engine core (T1–T5) | — | — | 775 | — |
| 0 | B | Governance DAG | `dag-runner.mjs` | 931 → 33 | 877+310 | 324 checks |
| 1–4 | C | Strangler Fig wiring | — | 931 deleted | +3 | — |
| 6 | D | gRPC soak + smoke | 2 runners | 617 → 67 | 960 | 229 checks |
| 6 | E | Track G + shared lib | `dag-runner-track-g.mjs` | 310 → 35 | 1,275 | 159 checks |
| 6 | F | Perf regression | `dag-runner-perf-regression.mjs` | 583 → 52 | 1,090 | 91 checks |
| 7 | G1–G4 | 4 Tier 2 ports | 4 runners + 2 retry helpers | 2,804 → ~220 | 3,100 | 320 checks |
| 7 | S3 | dev-runner-lifecycle.mjs | — | — | 195 | — |
| 9 | R1 | `--resume` idempotency | all 9 runners | — | ~200 | 25 checks |
| 10 | — | perf-suite DAG migration | `dag-runner-perf-suite.mjs` | 949 → step 02 | ~480 | 185 checks |
| 5 | S1–S2 | json-utils + process-utils | — | — | 83 | — |
| — | — | Audit #1: Tier A–F fixes | — | — | — | — |
| — | — | Audit #2: Phase 7+R1 fixes | — | — | — | — |
| — | — | Live testing: cleanup step + allowNegative | dag-scheduler + 8 runners | — | ~40 | 3 assertions |
| — | — | Critical analysis hardening (6 issues) | 11 runners + shared lib | — | ~120 | +41 checks |
| 8a | — | Autopilot framework port | `overnight-benchmark-autopilot.mjs` | 385 → PS1 retained | 551+772 | 111 checks |
| 8b | — | RAG-AI-Queue framework port | `overnight-rag-ai-queue.mjs` | 1,672 → PS1 retained | 855+650 | 222 checks |
| — | — | Cross-cutting: `--no-daemon` + `-WindowStyle Hidden` fleet | 3 runners (9 Gradle steps) + 5 runners (6 PS1 steps); 7 test suites | — | — | +20 checks |
| — | — | Post-close: `allowNegative` + test suite for agent-battery | `governance/dag-runner.mjs`, `dag-runner-agent-battery.mjs`; new test file | — | ~83 | +83 checks |

**Aggregate metrics:**

| Metric | Value |
|--------|------:|
| DAG runners implemented | 13 (9 pipeline + 1 inner perf-suite + 1 autopilot + 1 RAG-AI-queue + 1 agent-battery) |
| Test suites | 13 (1,816 checks) |
| PS1 lines replaced | ~8,220 → ~280 (thin wrappers) |
| Net PS1 reduction | ~7,940 lines |
| New Node.js code | ~12,300 lines (including ~4,650 tests) |
| Shared library modules | 11 (`scripts/lib/orchestration/` + `scripts/lib/bench/`) |
| npm dependencies added | 3 (`koffi`, `p-limit`, `toposort`) |

### What's Done (Live Integration Testing — 2026-02-23)

All 4 Tier 2 runners tested against live dev stacks. Two runtime bugs found and fixed:

| Runner | Result | Duration | Notes |
|--------|--------|----------|-------|
| G2 (search-eval-rank) | PASSED | ~3.5 min | **Bug #1 found:** `stop-backend` was skipped on upstream failure. Fixed via `cleanup: true` step property in dag-scheduler. |
| G1 (beir-gate) | PASSED | ~2 min | Clean pass (single dataset, scifact). Cleanup fix verified. |
| G3 (local-agent-gate) | PASSED | ~5 min | **Bug #2 found:** `--no-*` flags crash with `strict: true` in Node.js 24 parseArgs. Fixed via `allowNegative: true` in all 8 runners. 7/10 probes passed, 3 failed (pre-existing env issues). |
| G4 (backfill) | PASSED | ~25 min | Track-g lane, 1 iteration. All 4 inner steps ran correctly. `diff` failed on stale baseline (expected). |

**Bugs fixed during live testing:**

1. **Cleanup step support** (`dag-scheduler.mjs`): Added `cleanup: true` step property
   that bypasses `shutdownRequested` and `hasFailedUpstream()` checks. Changed failure
   handler to always call `scheduleReadyDependents` (removed early `return`). Applied
   `cleanup: true` to `stop-backend` steps in G1, G2, G3 runners.

2. **`allowNegative: true`** for `parseArgs`: Node.js 24's `parseArgs` with `strict: true`
   does not support `--no-*` boolean negation without `allowNegative: true`. Added to all
   8 production runners in scope at the time. (P7-FP1 audit finding was incorrectly marked
   as "false positive" — see post-close fix below.) Governance runner and `dag-runner-agent-battery.mjs`
   were later found to still be missing this; corrected 2026-02-25 and runtime-verified.

**Design observation (not fixed, low priority):** With `continueOnError: true`, fan-in
steps still get skipped when any upstream probe fails because `hasFailedUpstream()` doesn't
consider the `continueOnError` flag. This affects G3's capture-evidence/validate-eb chain
but doesn't impact correctness (runner still reports correct `ok`/`gatePassed` status).

### Soak Test Results (3×each — 2026-02-23)

Ran each runner 3 times sequentially to test stability under repeated dev-runner cycling:

| Run | Runner | Result | Duration | Notes |
|-----|--------|--------|----------|-------|
| 1 | G1 beir-gate | PASSED | 3.21 min | Cold start (24.9s backend, 166.4s eval) |
| 2 | G1 beir-gate | PASSED | 1.97 min | Warm (14.7s backend, 102.0s eval) |
| 3 | G1 beir-gate | PASSED | 1.93 min | Warm (14.7s backend, 100.0s eval) |
| 4 | G2 search-eval-rank | FAILED (diff) | 1.87 min | All infra steps pass, diff=stale baseline |
| 5 | G2 search-eval-rank | FAILED (diff) | 1.83 min | Identical regression ratios — deterministic |
| 6 | G2 search-eval-rank | FAILED (diff) | 1.84 min | Identical regression ratios — deterministic |
| 7 | G3 local-agent-gate | **CRASHED** | 5.0 min | Bug #3: start-backend timeout + Bug #4: ENOENT crash |
| 8 | G3 local-agent-gate | **CRASHED** | 5.0 min | Same: start-backend timeout + ENOENT crash |
| 9 | G3 local-agent-gate | PASSED | 5.79 min | start-backend barely made it (300.9s / 300s limit) |
| 10 | G4 backfill (3 iter) | FAILED | 23.7 min | Bug #5: iter-1 diff failed → iter 2,3 skipped |

**Bugs found during soak testing:**

3. **start-backend timeout after repeated dev-runner cycling** (HIGH): After 6 prior
   start/stop cycles (3×G1 + 3×G2), the dev-runner takes 300+ seconds to become ready
   (was 14s in isolation). 2 of 3 G3 runs exceeded the 300s lifecycle readiness timeout.
   Root cause: likely stale Gradle daemons, port contention, or dev-runner resource leak.

4. **ENOENT crash in `writeJsonAtomic` on run-state persist** (HIGH): When `start-backend`
   times out, the `onStepComplete` callback writes run state to
   `tmp/dag-runner/runs/<runId>/run-state.json`, but the directory doesn't exist. The atomic
   rename fails with ENOENT, crashing the runner with an unhandled exception. Stack:
   `dag-scheduler.mjs:154` → `run-state.mjs:67` → `json-utils.mjs:57`.

5. **Backfill skips remaining iterations on diff failure** (MEDIUM): The backfill runner
   uses sequential edges without `continueOnError: true`. When iter-001's diff detects a
   regression, the chain stops — defeating the purpose of multi-iteration backfill.

### What's NOT Done

| Item | Priority | Effort | Notes |
|------|----------|--------|-------|
| ~~**Live integration testing**~~ | ~~HIGH~~ | ~~~1 day~~ | Done (2026-02-23). 2 bugs found and fixed, 3 more found in soak testing. |
| ~~**Fix Bug #3: start-backend timeout after cycling**~~ | ~~HIGH~~ | ~~30 min~~ | FIXED. Added crash detection: 30 consecutive NO_ACTIVE_RUN polls (60s) → `SUPERVISOR_CRASH` error + early abort (was 300s blind timeout). `dev-runner-lifecycle.mjs`. |
| ~~**Fix Bug #4: ENOENT crash on run-state persist**~~ | ~~HIGH~~ | ~~15 min~~ | FIXED. Root cause: `writeJsonAtomic` used deterministic `.tmp` path → concurrent rename race. Fix: unique tmp suffix (`randomUUID`) in `json-utils.mjs` + deduped `ensureDirs()` promise in `run-state.mjs`. |
| ~~**Fix Bug #5: backfill skips iterations on diff failure**~~ | ~~MEDIUM~~ | ~~30 min~~ | FIXED. Root cause: `hasFailedUpstream()` ignores `continueOnError`. Fix: added `ignoreUpstreamFailure` step property to `dag-scheduler.mjs`, applied to all backfill iteration/cooldown/post steps. |
| ~~**Workflow YAML updates for Tier 2**~~ | ~~HIGH~~ | ~~2h~~ | DONE. Both `beir-eval-gate-win.yml` and `search-eval-rank-report-win.yml` now call `node dag-runner-*.mjs` directly, bypassing PS1 wrappers. |
| ~~**R1 resume backport to 5 existing runners**~~ | ~~MEDIUM~~ | ~~2h~~ | DONE. `--resume <dir>` added to all 9 runners. Path-based convention (not runId). 25 resume tests added across 5 test files. |
| ~~**`run-perf-suite-win.ps1` migration**~~ | ~~LOW~~ | ~~1 day~~ | DONE. New `dag-runner-perf-suite.mjs` (~480 lines) replaces PS1 (305+644 lines). `dag-runner-perf-regression.mjs` step 02 updated to call node. 185 test checks. |
| ~~**Critical analysis hardening (6 issues)**~~ | ~~MEDIUM~~ | ~~2h~~ | DONE. Fixed: (1) resume hash bug via build-time `computeStepHashMap`, (2) governance `makeOnStepComplete` rewritten to use `runCtx.writeState()` + spread, (3) perf-suite `onStepComplete` pure functions extracted + 25 tests, (4) resume warning on missing state in all 11 runners, (5) shared `resume-test-helpers.mjs` deduplicates 6 test files + adds coverage to 4 more. 1,380 checks, 0 failures. |
| ~~**Phase 8a: Autopilot framework port**~~ | ~~LOW~~ | ~~1 day~~ | DONE. `overnight-benchmark-autopilot.mjs` (551 lines) replaces PS1 (385 lines). 9 exported pure functions, per-cycle 3-step DAG via `executeDAG`, adaptive cadence, resume support. 111 test checks (67 unit + 44 integration). |
| ~~**Phase 8b: RAG-AI-Queue framework port**~~ | ~~LOW~~ | ~~1 day~~ | DONE + live tested (2026-02-25). `overnight-rag-ai-queue.mjs` (~855 lines) replaces PS1 (1,672 lines). 9 exported pure functions, linear-spine DAG, `onStepComplete` placeholder patching, directory-based resume, dry-run. Live run found 4 bugs — all fixed: (1) `./gradlew.bat` → `.\\gradlew.bat` for Windows cmd.exe; (2) `JUSTSEARCH_NATIVE_PATH`/`MODEL_PATH` defaults matching PS1; (3) `ignoreUpstreamFailure: true` on claim/track/docs/scorecard to match PS1 unconditional execution; (4) `--no-daemon` + `-WindowStyle Hidden` to suppress Windows console pop-ups. 222 test checks, 0 failures. All 15 steps confirmed running end-to-end. |
| ~~**R2: Input-hash caching**~~ | ~~LOW~~ | ~~~3 days~~ | **Permanently deferred.** Engine infra is straightforward (~300 lines), but per-step input declarations have correctness risks: Gradle steps already cache internally (a parallel hash layer would diverge); PS1 and MJS transitive inputs are hard to enumerate without missing something (→ silent stale hits). The `--resume` mechanism already covers the primary use case. Not a bottleneck. |

---

## Next Directions

The tempdoc's core mission — replacing imperative PS1 orchestrators with declarative
Node.js DAG runners — is achieved. Remaining work:

### ~~1. Live Validation~~ — DONE (2026-02-23)

All 4 Tier 2 runners tested live. 2 runtime bugs found and fixed (cleanup step support,
`allowNegative` for parseArgs). 3×each soak test found 3 more bugs (all fixed):
crash detection, writeJsonAtomic race, backfill iteration skip.

### ~~1. Production Readiness~~ — DONE (2026-02-24)

Both workflow YAMLs updated to call DAG runners directly. Also fixed:
`createRunContext` API mismatch, unawaited `writeState` calls, unified test harness.

### ~~2. Hardening~~ — DONE (2026-02-24)

- R1 resume backport completed: `--resume <dir>` in all 9 runners (path-based convention)
- `run-perf-suite-win.ps1` migrated to `dag-runner-perf-suite.mjs` (10th runner, 185 test checks)
- `dag-runner-perf-regression.mjs` step 02 updated: `powershell` → `node`

### ~~3. Critical Analysis Hardening~~ — DONE (2026-02-24)

Critical analysis of all DAG runner changes identified 6 issues (2 medium, 4 low). All fixed:

1. **Resume hash bug** (Medium): `computeStepHash` was called on mutated steps in `onStepComplete`,
   so hashes never matched on resume. Fix: `computeStepHashMap()` pre-computes all hashes at
   build time before any placeholder patching. Applied to all 11 runners.

2. **`onStepComplete` untested runtime logic** (Medium): 100-line closure in perf-suite with
   JSON parsing, placeholder patching, result assembly — zero coverage. Fix: extracted 4 pure
   functions (`parseLifecycleStartOutput`, `parseBundleDir`, `patchCaptureStepFromStart`,
   `patchValidateStepFromCapture`) with 25 new tests.

3. **No resume warning on bad path** (Low): Silent skip when `--resume` path has no
   `run-state.json`. Fix: stderr WARNING in all 11 runners.

4. **Governance `makeOnStepComplete` inconsistent** (Low): Used `fs.writeFileSync` + manual
   field enumeration instead of `runCtx.writeState()` + spread. Rewritten to async + spread.

5. **~500 lines duplicated resume tests** (Low): Created shared `resume-test-helpers.mjs`,
   deduplicated 6 test files, added resume coverage to 4 files that lacked it.

Verification: 1,380 checks across 10 test suites, 0 failures.

### 4. Phase 8a: Autopilot Framework Port — DONE (2026-02-24)

Ported `overnight-benchmark-autopilot-win.ps1` (385 lines) to `overnight-benchmark-autopilot.mjs`
(551 lines). The outer while-loop stays imperative; each cycle dispatches a 3-step linear DAG
(backfill→validate-history→build-scorecard) via `executeDAG`.

**Key design decisions:**
- 9 exported pure functions for testability (`decideLanes`, `parseRatchet`, `adaptCadence`,
  `buildCycleArtifactPaths`, `buildCycleSteps`, `buildCycleEdges`, `getManifestCounts`,
  `buildSessionState`, `buildMarkdown`)
- All 3 steps use `ignoreUpstreamFailure: true` — scorecard runs even when backfill fails
- Resume via `--resume <session-json-path>` — carries forward cycles/cadence/hadFailures
- Session JSON schema verified identical to PS1 output (13 real sessions compared)

**Test coverage (111 checks):**
- Sections A–J (67 checks): Pure function unit tests
- Section K (5): `getManifestCounts` with real filesystem
- Section L (4): `--dry-run` subprocess output validation
- Section M (6): `executeDAG` with synthetic `node -e` steps
- Section N (5): Scorecard → `parseRatchet` → `adaptCadence` pipeline
- Section O (4): Failure propagation via `ignoreUpstreamFailure`
- Section P (9): Multi-cycle loop (6 cycles) with adaptive cadence firing
- Section Q (6): Resume checkpoint write/read round-trip
- Section R (5): PS1 schema compatibility with real production data

### 5. Future Work (LOW priority, deferred)

#### ~~Phase 8b: RAG-AI-Queue Framework Port~~ — DONE + live tested (2026-02-25)

`overnight-rag-ai-queue.mjs` implements the full port. Steps: load-corpus-selection, preflight,
N × (rag-run + rag-diff), claim-d/a, track-g, M × agent-attempt (with `ignoreUpstreamFailure`),
agent-gate-scorecard/eval, docs checks, consolidated-scorecard. `onStepComplete` patches
`--candidate` and `--current-manifest` args dynamically. Directory-based resume, dry-run, session
JSON/MD output to `tmp/agent-evidence/_summaries/`. 222 test checks, 0 failures.

**Live test run (2026-02-25):** All 15 steps executed end-to-end against dev stack with AI
runtime activated. 4 bugs found and fixed during live testing:

1. **`./gradlew.bat` fails on Windows** (`rag-run-N`): `shell: true` spawns cmd.exe, which
   doesn't support `./` prefix. Fixed: `'.\\gradlew.bat'` (matches all other DAG runners).

2. **Missing native/model path defaults** (`rag-run-N`): `JUSTSEARCH_NATIVE_PATH` and
   `JUSTSEARCH_MODEL_PATH` were only set when explicitly passed via CLI. RAG test refused to
   run in real-embedding mode. Fixed: `buildRagRunEnv` now defaults to repo root and
   `models/nomic-embed-text-v1.5.Q4_K_M.gguf` — identical to PS1 defaults.

3. **`ignoreUpstreamFailure` missing on claim/track/docs/scorecard** : `rag-diff-N` gate
   failure (expected on first-run with no baseline) was cascading to skip claim-d/a, track-g,
   and all downstream steps. PS1 runs all phases unconditionally. Fixed: added
   `ignoreUpstreamFailure: true` to claim-d, claim-a, track-g, agent-gate-scorecard,
   docs-check-*, and consolidated-scorecard.

4. **Two console windows appearing during run**: Gradle daemon fork and PowerShell steps each
   created a visible cmd.exe window. `windowsHide: true` only suppresses the direct child.
   Fixed: `--no-daemon` added to `buildRagRunStep` Gradle args; `-WindowStyle Hidden` prepended
   to all three PowerShell step arg arrays (claim-d, claim-a, track-g).

#### ~~R2: Input-hash caching~~ — Permanently deferred

Engine infrastructure is tractable (~300 lines, straightforward extension of `computeStepHashMap`).
Input declarations are not: Gradle steps already cache internally and a parallel hash layer would
silently diverge; MJS transitive imports are invisible without tracing; PS1 inputs require manual
audit of every script. A false cache hit (missing input → stale result served as correct) is
worse than no caching. The `--resume` mechanism covers the primary real-world use case
(re-running a failed overnight queue from the last successful step). Not a bottleneck; if it
ever becomes one, the correct fix for Gradle steps is `--build-cache`, not a parallel layer.

#### Closure criteria

Tempdoc 233 is complete. All 13 runners implemented and live-tested (12 original + 1
post-scope: `dag-runner-agent-battery.mjs`). Window suppression (`--no-daemon` +
`-WindowStyle Hidden`) applied fleet-wide to all affected runners. R2 (input-hash caching)
is permanently deferred: correctness risks outweigh the benefit, `--resume` covers the
primary use case, and it is not a bottleneck. `allowNegative: true` added to all runners
with boolean flags. No remaining work items.

---

## Plan vs. Reality

| Prediction | Actual | Lesson |
|-----------|--------|--------|
| Engine core: 550–720 lines | 775 lines (+8–40%) | Graceful shutdown, CLI entry point, and skip-outcome handling were underestimated |
| Governance runner: 350–500 lines | 877 lines (+75%) | `resolveInputs`, `resolvePreferredSummaryDir` scoring, and 24-key artifact map were missed |
| "4 engine primitives needed" for Tier 2 | 0 primitives needed | `onStepComplete` + `continueOnError` + subprocess model covered all cases. Pre-implementation analysis overestimated engine gaps. |
| Parallelism: "single largest performance waste" | Never measured | The parallelism benefit was the original motivation but was never quantified post-implementation |
| ~2,000–3,000 lines total scope | ~10,800 lines (including ~4,000 tests) | The 11-runner cross-codebase expansion was not in the original estimate, which covered governance only |
| Maintenance burden: 5–10 days/year | Too early to measure | Track over next 6 months |

**Key takeaway:** The engine was smaller and more capable than predicted (no extensions
needed), but the total investment was much larger than originally scoped because the
project expanded from 1 runner to 9. The per-runner cost (~400–600 lines + ~200 lines
tests) was consistent and predictable after the first 2–3 were built.

---

## Quantified Blast Radius

The "PowerShell + JSON DAG" pattern is the load-bearing foundation for almost all automated quality gating in this repository. A codebase inventory across 8 script directories reveals the full scope:

### Aggregate Script Inventory

| Extension | Files | Lines | Role |
|-----------|------:|------:|------|
| `.ps1` | 71 | ~20,600 | Orchestrators, CI wrappers, suite runners |
| `.mjs` | 103 | ~31,500 | Builders, validators, diff reporters, test harnesses |
| `.cjs` | 2 | ~985 | Dev-runner process orchestrator, MCP server |
| **Total** | **176** | **~53,100** | |

### By Domain

| Domain | PS1 | MJS | Lines | Key Files |
|--------|----:|----:|------:|-----------|
| Resilience governance (219) | 1 | 27 | ~8,800 | `run-rr219-regression-pack-win.ps1`, `build-*.mjs` family |
| CI pipeline | 30 | 25 | ~16,600 | `run-beir-gate-win.ps1`, `run-perf-regression-win.ps1`, `run-agent-live-battery.mjs` |
| Benchmarks | 26 | 40 | ~18,900 | `build-benchmark-scorecard.mjs` (2,101 lines), overnight autopilots |
| Performance | 6 | 3 | ~3,500 | `run-perf-suite-win.ps1`, `diff-perf-suite.mjs` |
| Resilience faults | 1 | 3 | ~850 | `run-grpc-retry-soak-win.ps1` |
| Resilience calibration | 1 | 1 | ~550 | `run-grpc-resilience-smoke-win.ps1` |
| Search eval | 3 | 2 | ~2,200 | `beir-eval-win.ps1` (1,163 lines) |
| Dev tooling | 3 | 2 | ~1,700 | `dev-runner.cjs` (951 lines) |

### The Governance DAG (Primary Migration Target)

The primary target — `run-rr219-regression-pack-win.ps1` — executes 36 numbered steps:

| Executor | Steps | Step IDs |
|----------|------:|----------|
| `node` | 28 | 01–09, 14, 16–16d, 17–18b |
| `gradlew` | 3 | 10, 11, 12 |
| `powershell` (nested) | 2 | 05b, 15 |
| `npm` | 1 | 13 |
| internal (no subprocess) | 2 | 16e, 18c |

**Key finding:** 28 of 36 steps are Node.js invocations. The pipeline is overwhelmingly a Node.js DAG with 3 Gradle test calls and 2 PowerShell wrappers.

### Parallelism Analysis

21 steps (Phase A: all self-contained tests 01–13) have zero inter-step file dependencies and can run fully in parallel. Critical path (longest sequential chain): 7 hops.

## Cross-Codebase Orchestrator Analysis

12 scripts share the same structural pattern. Three tiers of DAG engine compatibility:

### Tier 1 — Direct DAG Fit (all migrated)

| Script | Lines | Domain | Status |
|--------|------:|--------|--------|
| ~~`run-rr219-regression-pack-win.ps1`~~ | ~~931~~ | Governance | Tier B/C — `dag-runner.mjs` |
| ~~`run-perf-regression-win.ps1`~~ | ~~582~~ | Performance | Tier F — `dag-runner-perf-regression.mjs` |
| ~~`run-track-g-report-win.ps1`~~ | ~~310~~ | CI | Tier E — `dag-runner-track-g.mjs` |
| ~~`run-grpc-retry-soak-win.ps1`~~ | ~~282~~ | Resilience | Tier D — `dag-runner-grpc-soak.mjs` |
| ~~`run-grpc-resilience-smoke-win.ps1`~~ | ~~335~~ | Resilience | Tier D — `dag-runner-grpc-smoke.mjs` |
| ~~`run-perf-suite-win.ps1`~~ | ~~304~~ | Performance | Migrated → `dag-runner-perf-suite.mjs` (invoked by perf-regression step 02) |

### Tier 2 — ~~DAG With Extensions~~ DAG Via Topology (all migrated)

~~These have a static DAG structure but use patterns the Phase 0 engine doesn't cover.
Four new primitives are needed.~~

**Post-implementation correction:** Zero engine extensions were needed. All 4 scripts
are expressible via existing `onStepComplete`, `continueOnError`, and subprocess model:
- "Dynamic fan-out" → plan-time parameterized step instantiation
- "Dev-runner lifecycle nodes" → subprocess steps calling S3's CLI
- "Conditional node activation" → probe steps + DAG branching + `continueOnError`
- "Loop/fan-out" → plan-time loop creating N×M step definitions

| Script | Lines | DAG Pattern | Status |
|--------|------:|-------------|--------|
| ~~`run-beir-gate-win.ps1`~~ | ~~509~~ | Per-dataset serial branches | G1 — `dag-runner-beir-gate.mjs` |
| ~~`run-search-eval-rank-report-win.ps1`~~ | ~~811~~ | Linear chain, retry-inside-step | G2 — `dag-runner-search-eval-rank-report.mjs` |
| ~~`local-agent-gate-win.ps1`~~ | ~~1,130~~ | Probe-and-branch, ~20 steps | G3 — `dag-runner-local-agent-gate.mjs` |
| ~~`backfill-report-history-win.ps1`~~ | ~~354~~ | Iterations × lanes sequential chain | G4 — `dag-runner-backfill-report-history.mjs` |
| _(no PS1 predecessor)_ | — | Linear chain (6 steps), dynamic port, MCP-driven | G5 — `dag-runner-agent-battery.mjs` _(added post-233; not in original scope)_ |

### Tier 3 — ~~Not a Static DAG~~ Framework Ports (both migrated)

| Script | Lines | Structure | Migration Path |
|--------|------:|-----------|---------------|
| `overnight-benchmark-autopilot-win.ps1` | 385 | Deadline-driven loop, per-cycle 3-step DAG, adaptive cadence between cycles | ~~Phase 8a~~: DONE — 551 lines + 772 test lines, 111 checks |
| `overnight-rag-ai-queue-win.ps1` | 1,672 | Linear 13-phase sequence with conditional skipping and agent retry loop | ~~Phase 8b~~: DONE — 855 lines + 650 test lines, 222 checks |

~~These benefit from the shared library but should remain imperative scripts.~~

**Post-implementation reassessment (2026-02-24):** Original Tier 3 assessment was wrong.
Neither script's complexity comes from dynamic graph topology — it comes from loop control
(autopilot) and conditional phase skipping (RAG-AI-Queue), both of which the framework
handles. The per-cycle/per-phase work IS a static DAG. The outer loops stay as loops.
See §4 (Future Work) for detailed analysis.

## Evidence-Based Tool Evaluation

Tools were evaluated against JustSearch's hard constraints: **Windows-native (no WSL, no Docker), local-first, and able to orchestrate predominantly Node.js scripts with occasional Gradle/PowerShell calls.** Each evaluation cites specific evidence (GitHub issues, documentation, version data).

### Eliminated Tools

| Tool | Constraint Violated | Evidence |
|------|-------------------|----------|
| **Turborepo** | Requires `package.json` scripts for all tasks | Issue [vercel/turborepo#4549](https://github.com/vercel/turborepo/issues/4549) (arbitrary commands — closed Feb 2024 with "we have plans" but not shipped). Issue [#1495](https://github.com/vercel/turborepo/issues/1495) (decouple from package.json — closed without implementing). No conditional pipeline support. Windows signal forwarding broken: [#9694](https://github.com/vercel/turborepo/issues/9694) (SIGINT not forwarded — open), [#9730](https://github.com/vercel/turborepo/issues/9730) (Windows-specific — open). |
| **Wireit** (Google) | Every task must be in `package.json` | Confirmed by design. Issues [google/wireit#265](https://github.com/google/wireit/issues/265), [#648](https://github.com/google/wireit/issues/648) confirm this is intentional, not a gap. |
| **Dagger** | Requires Docker/OCI container runtime | Docs: "The only dependency is a container runtime like Docker." No bare-metal execution mode exists. The TypeScript SDK generates code that runs inside containers. |
| **Pants / Buck2** | Require WSL2 on Windows | Pants has no native Windows support. Buck2 has experimental Windows support but requires Rust toolchain. |
| **Bazel** | Overhead too high for script orchestration | Migrating `.mjs` scripts into `BUILD` files via `genrule` is architecturally awkward. Windows symlink handling remains brittle without Admin privileges. |
| **Temporal / Dagster / Prefect** | Daemon overhead for a 5-minute pipeline | Require background scheduling daemons, SQLite databases, and local webservers. Overkill for a governance check that should take minutes. |

### Detailed Evaluations

#### Go-Task (task.taskfile.dev)

*Version: v3.48.0 (Jan 2026). Stars: 14,900+. Contributors: 231. Active maintenance.*

**Strengths:**
- Runs arbitrary commands directly (any POSIX-compatible syntax)
- MD5 checksum caching via `sources:`/`generates:` — works with JSON artifacts ([issue #238](https://github.com/go-task/task/issues/238) resolved: re-runs if generated files deleted)
- Parallel execution via `deps:` — no concurrency cap; 21+ parallel tasks are architecturally fine (Go goroutines)
- 15 built-in Windows coreutils (`cp`, `mv`, `mkdir`, `rm`, etc.) — [announced in blog post](https://taskfile.dev/blog/windows-core-utils)
- `if:` conditional execution, `status:` for caching predicates, `preconditions:` for gates
- `sh:` variable capture for passing data between tasks (via `vars`)
- `fromJson` templating for parsing JSON output from scripts

**Weaknesses:**
- **No Windows process tree cleanup.** Issue [go-task/task#458](https://github.com/go-task/task/issues/458): the Unix fix (PR #479) uses TTY signal propagation that doesn't apply on Windows. The contributor explicitly stated: "Still, I don't have a solution for Windows." If a task is killed mid-Gradle-run, orphaned JVM/daemon processes are plausible. Related Gradle issues: [gradle/gradle#3987](https://github.com/gradle/gradle/issues/3987), [#18716](https://github.com/gradle/gradle/issues/18716).
- **POSIX-only shell** via `mvdan/sh` (a Go-based bash interpreter). Cannot write native PowerShell syntax inline ([issue #1723](https://github.com/go-task/task/issues/1723)). Must invoke PowerShell explicitly: `powershell -File script.ps1`.
- **No concurrency limit.** All `deps:` run in parallel with no `--jobs` flag. Issue [#703](https://github.com/go-task/task/issues/703) (v4 roadmap) plans `serialGroups` but v4 is not yet released.
- **v4 breaking changes incoming.** Syntax changes planned (e.g., `deps:` → `needs:`). Adopting v3 now means a migration when v4 ships.
- **No native "skip if upstream failed" primitive.** Must be implemented via sentinel files or env vars.

#### Nx (nx.dev)

*Version: Nx 21 (2025). Stars: 25,000+. Backed by Nrwl (Vercel). Major releases every 6 months.*

**Strengths:**
- `nx:run-commands` executor runs any shell command — `node scripts/foo.mjs`, `./gradlew.bat build`, `powershell -File bar.ps1`. No `package.json` wrapping required.
- Standalone (non-monorepo) project mode is fully supported since Nx 18+.
- Local-only caching is the default (no Nx Cloud required). Cache stored in `node_modules/.cache/nx`.
- Gradle plugin (`@nx/gradle`) invokes Gradle transparently with batch mode (reduced CI time by 59.6% on Spring Boot repo per Nx's benchmarks).
- Startup overhead: 2–5 seconds on cold start; daemon eliminates overhead on subsequent runs.

**Weaknesses:**
- **No native `onlyIf` predicate.** Conditional task skipping must be encoded in the scripts themselves (check preconditions, exit 0 to signal skip). Nx does skip downstream tasks when upstream fails, but there's no declarative condition.
- **2024 pricing controversy.** Nx deprecated custom task runners and made self-hosted remote caching paid (Sep 2024). Reversed under community backlash (Mar 2025). Demonstrates willingness to change commercial terms. For local-only use, the lock-in risk is low — `nx.json` format is not harder to migrate from than any other tool.
- **Adds npm dependency.** Nx itself is an npm package. Adds `nx`, `@nx/workspace`, potentially `@nx/gradle` to `devDependencies`.
- **Windows edge cases.** Shell quoting in `run-commands` defaults to `cmd.exe` (single quotes don't work). Project graph bugs on Windows paths exist ([nrwl/nx#19520](https://github.com/nrwl/nx/issues/19520)).

#### Gradle as Orchestrator

*Already in codebase. Custom `NodeScriptTask` exists in `build-logic`. 29 Java modules.*

**Critical Blocker:** `--parallel` only parallelizes across subprojects, not within a single project. The built-in `Exec` task doesn't use the Worker API. Confirmed by Gradle maintainer Sterling Greene on [gradle/gradle#9215](https://github.com/gradle/gradle/issues/9215), **closed "not planned" in September 2022**. Worker API workaround requires ~600–850 lines Kotlin and provides no per-step caching.

#### Custom TypeScript DAG Engine (chosen)

*Builds on existing `dev-runner.cjs` (951 lines). Libraries: `toposort`, `p-limit`, `koffi`.*

See [ADR-0009](../decisions/0009-custom-dag-engine-ci-orchestration.md) for the decision rationale. Key finding: koffi + Win32 Job Objects provide crash-safe process tree containment. libuv's `CREATE_BREAKAWAY_FROM_JOB` does NOT prevent job membership inheritance when the parent is already a job member. Validated via PoC.

## DAG Engine Design Patterns (from Prior Art)

### Reference Implementations

**hereby** (TypeScript compiler's build runner, ~60 lines core): Tasks are ES module exports wrapping `task({ name, dependencies, run })`. Dependencies resolved recursively; `Map<Task, Promise>` deduplicates execution. No concurrency limit, no caching. Demonstrates that the core scheduling logic can be extremely small.

**p-graph** (Microsoft, ~150 lines core): Separates graph structure (edge-list of `[prerequisite, dependent]` tuples) from node definitions (`Map<string, { run }>`) — enabling JSON-serializable task manifests. Uses a priority queue with cumulative priority (node priority + max descendant priority) for greedy scheduling. Supports concurrency limiting and continue-on-error mode with downstream failure propagation.

**Eppo Paddle** (custom TS DAG replacing Airflow): Key architectural decisions — workers report their own status (don't bottleneck the scheduler), 3-table data model vs. Airflow's 12+ (simplicity wins), SIGTERM handling for graceful cleanup during deploys, `AsyncLocalStorage` for per-task log attribution in concurrent execution.

### Patterns Adopted

| Decision | Pattern | Source |
|----------|---------|--------|
| Graph definition | Edge-list + node map (JSON-serializable) | p-graph |
| Task identity | String IDs (not object references) | p-graph |
| Scheduling | Priority queue + running counter (greedy) | p-graph |
| Error handling | Continue mode with downstream failure propagation | p-graph |
| Process spawning | Workers/tasks report own status and artifacts | Eppo Paddle |
| Input-hash caching | SHA-256 of declared inputs → skip on match | Google Bazel principles |

## Recommendation

**Decision: Custom TypeScript DAG Engine** (see [ADR-0009](../decisions/0009-custom-dag-engine-ci-orchestration.md))

The evidence converges on a custom TypeScript DAG engine as the strongest long-term solution:

1. **The "polyglot" framing was misleading.** 28 of 36 steps are Node.js. A TS engine is the natural fit.
2. **Foundational patterns already exist.** `dev-runner.cjs` provides transferable patterns (~20% of needed infrastructure).
3. **Off-the-shelf tools all have critical gaps:** Go-Task (no Windows process tree cleanup), Gradle (can't parallelize within project), Nx (heavyweight), Turborepo (structurally incompatible).
4. **The 28 existing `.mjs` builder scripts are invocable as subprocess tasks.** No configuration translation needed.

## Implementation Risks and Resolutions

| Risk | Original Assessment | Resolution |
|------|-------------------|------------|
| **Scope underestimate** | Engine core est. 550–720 lines | **Actual: 775 lines.** +8–40% over. T6 governance runner was 877 vs. 350–500 est. (+75%). The `resolveInputs` and `resolvePreferredSummaryDir` complexity was missed. |
| **koffi Job Objects** | PoC needed | **RESOLVED.** Validated on Windows 11 x64, Node 24. `sizeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION)` = 144 bytes. All struct definitions and function bindings work. koffi ≥ 2.15.1 required. |
| **"Import Directly" refactoring** | Open-ended task | **DEFERRED.** Validated as ~2 hours total for all 12 governance builders. Phase 0 used subprocess spawning (safe default). Not yet implemented — subprocess model works fine. |
| **Input-hash caching correctness** | Completeness of input declarations | **DEFERRED.** Opt-in per step with output-hash verification as correctness guard. Not yet implemented — not a bottleneck. |
| **Shared library is design work** | PS1 patterns don't translate directly | **RESOLVED.** 10 modules across `scripts/lib/orchestration/` and `scripts/lib/bench/`. Async/await patterns, error handling conventions designed and proven across 9 runners. |
| **`dev-runner.cjs` reuse** | ~20% directly reusable | **CONFIRMED.** `spawnLogged`, `writeJsonAtomic`, `readJsonIfExists`, health polling patterns all extracted. Step-runner (144 lines) uses the same DEP0190 shell quoting workaround. |
| **Maintenance burden** | 5–10 days/year | **Unknown (too early).** Track over next 6 months. |
| **Tier 2 "4 engine primitives"** | ~300–400 lines of extensions | **WRONG.** Zero engine extensions needed. All 4 Tier 2 scripts expressed via existing `onStepComplete`, `continueOnError`, and subprocess model. |
| **In-memory policy evaluation** | ~10–18s saved | **DROPPED.** The original concern (Flaw #2: disk-IPC fragility) was about race conditions and implicit ordering, not speed. The DAG engine addresses both: dependency-ordered execution eliminates race conditions, atomic writes eliminate partial-read hazards. Process isolation provides crash safety. |

## Critical Analysis — Post-Implementation Audits

### Audit #1: Tier A–F (after 5 runners)

Comprehensive audit of 6 engine modules, 5 DAG runners, 5 test suites, 5 PS1 wrappers,
and 4 workflow YAML files.

#### CRITICAL (3 bugs — would crash at runtime)

| ID | File | Issue |
|----|------|-------|
| C1 | `dag-runner-perf-regression.mjs` | `executeDAG({...})` — single-object form instead of 4 positional params |
| C2 | `dag-runner-track-g.mjs` | `logDir`/`jobHandle` passed in opts (3rd param) instead of ctx (4th param) |
| C3 | `dag-runner-track-g.mjs`, `dag-runner-perf-regression.mjs` | Missing `await` on `createJobObject()` — handle is a Promise |

**Pattern:** All 3 bugs in the 2 newest runners (Tiers E/F). Older runners have correct patterns.
**Root cause:** Tests validate static structure but never exercise the `executeDAG` call site.

#### HIGH (1 race condition)

| ID | File | Issue |
|----|------|-------|
| H1 | `dag-scheduler.mjs` | `onStepComplete(id, outcome)` not awaited — dependents may start before arg patching completes |

#### MEDIUM / LOW

- **M1:** `job-object.mjs` double-close passes invalid handle. Fixed with `closed` guard flag.
- **L1/L2:** `formatTimestamp` and `createProgressTracker` duplicated 5×. Extracted to `format-utils.mjs`.
- **L3/L4:** Placeholder replacement inconsistency; PS1 wrapper default coupling.

**All fixed.** 5 test suites pass (803 checks).

### Audit #2: Phase 7 + R1 (after 9 runners)

Focused on 4 new Tier 2 runners, 2 retry helpers, S3 lifecycle, R1 resume, PS1 wrappers.

#### CRITICAL (2 bugs)

| ID | Files | Issue |
|----|-------|-------|
| P7-C1 | `beir-index-with-retry.mjs`, `rank-eval-with-retry.mjs` | Retry helpers call `dev-runner-lifecycle.mjs restart` without forwarding `--api-port` or `--data-dir`. Restart would bind to random port. |
| P7-C2 | `dag-runner-local-agent-gate.mjs` | `__DATA_DIR__` as literal string; evidence scripts at wrong paths; unhandled `__EVIDENCE_BUNDLE_DIR__` placeholder; wrong log path suffix. |

#### HIGH (2 bugs)

| ID | Files | Issue |
|----|-------|-------|
| P7-H1 | All 4 new + `dag-runner-track-g.mjs` | `onStepComplete` checks `status === 'passed'` but R1 resume introduces `'passed-cached'`. Resumed runs fail to resolve candidates. |
| P7-H2 | `dag-runner-search-eval-rank-report.mjs`, PS1 wrapper | Three PS1 params not forwarded: `$BeirJudgedBaselineMetricsJson`, `$MinBeirRecallRatio`, `$MinBeirNdcgRatio`. |

#### MEDIUM

- **P7-M1:** `dev-runner-lifecycle.mjs` restart with no port-release verification. Fixed with polling.

#### FALSE POSITIVE (incorrect — retroactively reclassified)

- **P7-FP1:** `--no-*` flags with `strict: true` in `parseArgs` — originally marked "natively supported
  by Node.js", but this was wrong. Node.js 24 `parseArgs` with `strict: true` DOES crash on `--no-X`
  flags without `allowNegative: true`. The 8 runners fixed during live testing were correct. However,
  two runners were missed: `scripts/governance/resilience/dag-runner.mjs` and `scripts/ci/dag-runner-agent-battery.mjs`.
  Both were fixed and runtime-verified on 2026-02-25 (`--no-continue` and `--no-dry-run` no longer crash).

**All fixed.** 9 test suites pass (1,123 checks).

---

## Appendix: Sources and Evidence

### Go-Task
- [Issue #458: SIGINT handling — Unix only, no Windows solution](https://github.com/go-task/task/issues/458)
- [Issue #1723: POSIX-only shell on Windows via mvdan/sh](https://github.com/go-task/task/issues/1723)
- [Issue #238: Checksum should consider generated files (resolved)](https://github.com/go-task/task/issues/238)
- [Issue #703: Roadmap to v4 — breaking changes planned](https://github.com/go-task/task/issues/703)
- [Blog: Windows Core Utils announcement](https://taskfile.dev/blog/windows-core-utils)
- [v3.48.0 release (Jan 2026)](https://github.com/go-task/task/releases)

### Turborepo
- [Issue #4549: Arbitrary command execution — closed Feb 2024, not shipped](https://github.com/vercel/turborepo/issues/4549)
- [Issue #1495: Decouple from package.json — closed without implementing](https://github.com/vercel/turborepo/issues/1495)
- [Issue #9694: SIGINT not forwarded to tasks — open](https://github.com/vercel/turborepo/issues/9694)
- [Issue #9730: Windows-specific signal handling — open](https://github.com/vercel/turborepo/issues/9730)

### Nx
- [Issue #19520: Windows project graph path bugs](https://github.com/nrwl/nx/issues/19520)
- [Blog: Self-hosted cache pricing changes (reversed Mar 2025)](https://nx.dev/blog/custom-runners-and-self-hosted-caching)
- [Docs: run-commands executor](https://nx.dev/nx-api/nx/executors/run-commands)

### Gradle
- [Issue #9215: Exec tasks not parallelizable within single project — closed "not planned"](https://github.com/gradle/gradle/issues/9215)
- [Issue #18716: Gradle doesn't kill subprocesses on interrupt on Windows](https://github.com/gradle/gradle/issues/18716)
- [Issue #21260: Orphan processes with processIsolation on Windows — open](https://github.com/gradle/gradle/issues/21260)
- [Docs: Worker API](https://docs.gradle.org/current/userguide/worker_api.html)
- [Palantir auto-parallelizable: Worker API + ExecOperations for external processes](https://github.com/palantir/auto-parallelizable)

### Custom TS DAG Engine — Libraries and Precedents
- [toposort (npm): 4.87M weekly downloads](https://www.npmjs.com/package/toposort)
- [p-limit (npm): 100M weekly downloads](https://www.npmjs.com/package/p-limit)
- [p-graph (Microsoft): DAG promise executor with priority queue scheduling](https://github.com/microsoft/p-graph)
- [hereby: TypeScript compiler's own build runner (~60 lines core)](https://github.com/jakebailey/hereby)
- [Eppo Paddle: Custom TS DAG replacing Airflow](https://www.geteppo.com/blog/building-a-custom-dag-orchestration-system-for-experimentation)
- [koffi: Pure-JS FFI for Node.js (prebuilt binaries, no compilation)](https://koffi.dev/)

### Windows Process Management
- [Node.js issue #40438: Descendant process killing](https://github.com/nodejs/node/issues/40438)
- [libuv issue #3179: JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK — grandchildren escape job](https://github.com/libuv/libuv/issues/3179)
- [Raymond Chen: Destroying all child processes when parent exits (Job Objects)](https://devblogs.microsoft.com/oldnewthing/20131209-00/?p=2433)
- [Meziantou: Killing child processes with Job Objects (C#)](https://www.meziantou.net/killing-all-child-processes-when-the-parent-exits-job-object.htm)

### Migration Patterns
- [Martin Fowler: Strangler Fig Application](https://martinfowler.com/bliki/StranglerFigApplication.html)
- [Software Engineering at Google, Ch.18: Build Systems and Build Philosophy](https://abseil.io/resources/swe-book/html/ch18.html)
