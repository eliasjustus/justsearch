---
title: "281: Workflow Quality Baseline Refresh And Stale Run Reconciliation Follow-Up"
type: tempdoc
status: done
created: 2026-03-12
updated: 2026-03-12
---

> NOTE: Noncanonical follow-up doc. May drift.

# 281: Workflow Quality Baseline Refresh And Stale Run Reconciliation Follow-Up

## Purpose

Refresh the workflow-quality baseline a few days after the March 7, 2026
implementation close, verify the canonical workflow-quality surface still holds
on current `main`, and capture the next narrow follow-up exposed by that
refresh.

This doc does **not** reopen tempdocs 262-265. It records a fresh evidence pass
against the canonical workflow-quality contract and the resulting next-action
candidate.

## Canonical Context

This tempdoc is grounded in the current canonical workflow-quality surfaces:

- `docs/how-to/validate-workflow-quality.md`
- `docs/reference/contracts/workflow-telemetry-contract.v1.md`
- `docs/decisions/0010-local-first-workflow-quality-observability.md`
- `docs/reference/contributing/dag-runner-operations.md`

The continuation model remains:

1. refresh the baseline through the repo-owned gate and closure flow
2. compare direct vs legacy runs through local workflow telemetry
3. judge regressions using workflow-run metrics, not PHI/session scores
4. keep local NDJSON canonical; optional overlays remain optional

## Work Performed On 2026-03-12

### 1. Baseline refresh

Executed:

```text
node scripts/ci/dag-runner-workflow-quality-gate.mjs --dry-run
node scripts/ci/dag-runner-workflow-quality-gate.mjs
```

Dry-run result: expected 8-step DAG emitted.

Initial full-gate result: failed at `search-workflow-runner`.

Failure run:

- gate run id: `cbd0e737-1f8a-41cc-854b-dc55b49ba098`
- failing log:
  `tmp/workflow-quality-gate/runs/cbd0e737-1f8a-41cc-854b-dc55b49ba098/logs/search-workflow-runner.log`

### 2. Root cause of the failed gate

The breakage was in the runner test, not in the workflow wrapper itself.

Observed mismatch:

1. `scripts/search/run-search-workflow.mjs` now derives managed SPLADE
   readiness from coverage-oriented status fields
   (`spladeDocCount`, `spladePendingCount`, `spladeFailedCount`,
   `spladeCoveragePercent`) rather than a simple `spladeReady` boolean
2. `scripts/search/test-search-workflow-runner.mjs` still mocked the older
   `spladeReady`-only shape
3. the SPLADE phase integration assertion also assumed
   `workflow_evaluating` must always be observed, which is stricter than the
   actual poll-visible workflow contract on fast successful runs

Relevant prior context: tempdoc 274 already documents that the Java
`/api/status` shape does not expose a simple `spladeReady` field and that the
wrapper now derives SPLADE readiness from coverage metrics.

### 3. Fix applied during the refresh

Only the workflow-runner test was changed.

Updated `scripts/search/test-search-workflow-runner.mjs` to:

1. emit SPLADE coverage fields in mock snapshots instead of relying on
   `spladeReady`
2. make the SPLADE mock progression expose `backend_splade_wait`
   deterministically
3. improve hybrid/SPLADE assertion failures by printing the actual phase
   sequence
4. relax the SPLADE phase assertion to the real observable contract:
   - `backend_ingesting` must appear
   - `backend_splade_wait` must appear
   - `workflow_complete` must appear after `backend_splade_wait`
   - `workflow_evaluating` is valid when observed, but not guaranteed on a
     fast successful run

No workflow wrapper behavior was changed during this slice.

### 4. Verification after the fix

Executed:

```text
node scripts/search/test-search-workflow-runner.mjs
node scripts/ci/dag-runner-workflow-quality-gate.mjs
```

Result: both passed.

Successful refresh artifacts:

- gate run id: `2710ae82-b150-432a-8043-49080e67e7bc`
- gate manifest:
  `tmp/workflow-quality-gate/workflow-quality-gate-manifest-2026-03-12T18-34-03-680Z.json`
- closure root:
  `tmp/workflow-quality-gate/closure-2026-03-12T18-34-03-680Z/`
- closure summary:
  `tmp/workflow-quality-gate/closure-2026-03-12T18-34-03-680Z/workflow-quality-closure-summary.json`

The refreshed gate passed all 8 steps:

1. `doc-validation`
2. `lifecycle-ownership`
3. `workflow-telemetry`
4. `search-workflow-runner`
5. `ranking-contract`
6. `compare-workflow-runs`
7. `telemetry-java`
8. `closure-run`

## Fresh March 12 Baseline

Closure result:

- `slo.pass: true`
- `violationCount: 0`

### Direct engine

| Run | readyHttpMs | indexServingMs | fallbackReadinessUsed | staleArtifactCount | manualForensicsCount |
|------|-------------|----------------|------------------------|--------------------|----------------------|
| run A | 5407 | 5464 | false | 0 | 0 |
| run B | 4346 | 4404 | false | 0 | 0 |

Additional notes:

- `portsClosed: true` on both runs
- `orphanProcessReaped: false` on both runs
- `parameterBindingErrorCount: 0`
- `circuitBreakerDuringBaseline: false`
- `silentWindowMaxMs: 0`

### Legacy engine

| Run | readyHttpMs | indexServingMs | fallbackReadinessUsed | staleArtifactCount | manualForensicsCount |
|------|-------------|----------------|------------------------|--------------------|----------------------|
| run A | 41844 | 41921 | true | 0 | 0 |
| run B | 10939 | 11034 | true | 0 | 0 |

Additional notes:

- `portsClosed: true` on both runs
- `orphanProcessReaped: false` on both runs
- `parameterBindingErrorCount: 0`
- `circuitBreakerDuringBaseline: false`
- `silentWindowMaxMs: 0`

## March 7 vs March 12 Snapshot Delta

Reference baseline:

- March 7 closure summary:
  `tmp/workflow-quality-closure/2026-03-07T02-06-23-016Z/workflow-quality-closure-summary.json`

Observed delta:

1. direct readiness improved modestly
   - March 7 direct `readyHttpMs`: `6117 / 5032`
   - March 12 direct `readyHttpMs`: `5407 / 4346`
   - March 7 direct `indexServingMs`: `6175 / 5131`
   - March 12 direct `indexServingMs`: `5464 / 4404`
2. direct stale-artifact signal improved
   - March 7 direct `staleArtifactCount`: `1 / 1`
   - March 12 direct `staleArtifactCount`: `0 / 0`
3. legacy remains much slower and much more variable than direct
   - March 7 legacy `readyHttpMs`: `38187 / 21398`
   - March 12 legacy `readyHttpMs`: `41844 / 10939`
   - fallback readiness remains legacy-only in both snapshots
4. March 7 `manualForensicsCount` was effectively unknown (`null`)
   because those runs predated usable workflow/session attribution
5. March 12 `manualForensicsCount` is now a real measured `0`, not a null
   collapse

Interpretation:

- the direct workflow family remains the stable baseline to trust
- the legacy family still clears SLOs but is materially noisier
- the baseline refresh validated the current workflow-quality architecture
  without reopening the canonical design

## Attribution Validation

Attribution report artifact:

- `tmp/workflow-attribution-2026-03-12.json`

Executed:

```text
node scripts/bench/report-workflow-attribution.mjs
node scripts/bench/report-workflow-attribution.mjs --out tmp/workflow-attribution-2026-03-12.json
```

The current gate and closure runs are attributed to session:

- `session_id = 542059f4-2a2a-4e8b-a116-06769ffb62a3`

Attributed examples from the fresh closure:

- `workflow-quality.direct.integration-run-a-0fb45bac-3aee-4946-9db3-e38aa9fab95c`
- `workflow-quality.direct.integration-run-b-5ef04f83-3274-40f0-82eb-17a0d1852d17`
- `workflow-quality.legacy.integration-run-a-69f1f8bc-79c4-409f-a508-9ab420b4c4b7`
- `workflow-quality.legacy.integration-run-b-e9c58156-f322-4d58-b339-850ff3cca439`
- gate run `2710ae82-b150-432a-8043-49080e67e7bc`

This matters because the March 12 closure now demonstrates a genuine measured
`manualForensicsCount = 0` rather than the earlier "no session telemetry, so
value is null" state.

## New Finding: Historical Running Meta Records Need Reconciliation

The most interesting follow-up exposed by the refresh is not an SLO failure. It
is stale workflow metadata.

### What was observed

As of the March 12 attribution report timestamp, the local workflow store still
contained 26 runs with `status: "running"`:

- 25 were older than 24 hours
- 17 were older than 96 hours

Breakdown by workflow family:

- `eval-backend-lifecycle.direct`: 10
- `search-workflow.beir-eval`: 6
- `search-workflow.ranking-experiments`: 3
- `workflow-otlp-pilot`: 6
- `workflow-quality.gate`: 1

Representative stale examples:

1. `tmp/workflow-telemetry/runs/9446e269-af31-4ec7-8787-f66a56167171/meta.json`
   - `workflowFamily: "search-workflow.beir-eval"`
   - `source: "run-search-workflow"`
   - `startedAt: 2026-03-06T22:36:51.285Z`
   - `updatedAt: 2026-03-06T22:36:51.285Z`
   - `status: "running"`
   - `finishedAt: null`
2. `tmp/workflow-telemetry/runs/workflow-otlp-pilot-1772847380208/meta.json`
   - `workflowFamily: "workflow-otlp-pilot"`
   - `source: "eval-backend-lifecycle"`
   - `startedAt: 2026-03-07T01:36:20.278Z`
   - `updatedAt: 2026-03-07T01:37:22.898Z`
   - `status: "running"`
   - `finishedAt: null`
   - `stats.lastEvent: "stale_artifact_detected"`

### Why this matters

`scripts/bench/report-workflow-attribution.mjs` rolls up every discovered
`meta.json` as-is. That means old interrupted or abandoned runs remain visible
forever as active runs, which pollutes:

1. attribution totals
2. workflow-family rollups
3. operator understanding of what is actually live vs. what is just leftover
   bookkeeping

### What this is not

This is **not** the same issue as stale lock recovery.

The repo already has stale lock recovery coverage in
`scripts/search/test-search-workflow-runner.mjs`
(`beir-stale-lock`, `beir-managed-stale-lock`), and
`scripts/search/run-search-workflow.mjs` can recover stale lockfiles and stop a
recovered managed backend.

The gap is different:

- lock recovery handles ownership of a currently requested run
- it does not reconcile old `tmp/workflow-telemetry/runs/*/meta.json` records
  that never reached a terminal `store.finish(...)`

### Current code seams

The relevant implementation edges are now clear:

1. `scripts/search/run-search-workflow.mjs`
   - correctly calls `store.finish(...)` for normal success/failure paths
2. `scripts/lib/workflow-telemetry.mjs`
   - persists `meta.json`
   - exposes `discoverWorkflowRuns(...)`
   - currently has no stale-run reconciliation step
3. `scripts/bench/report-workflow-attribution.mjs`
   - reads discovered workflow metas and reports them exactly as stored
   - currently does not downgrade or repair very old `status: "running"` docs

## Implementation Update (2026-03-12)

The stale-run reconciliation slice is now implemented.

### Chosen repair policy

Implemented policy:

1. stale-run reconciliation lives in `scripts/lib/workflow-telemetry.mjs`
2. `discoverWorkflowRuns(...)` remains read-only
3. `scripts/bench/report-workflow-attribution.mjs` explicitly runs
   reconciliation before classification
4. repair is persistent and writes back to the canonical local workflow store
5. repaired stale runs are terminalized as `failed`

Implemented stale candidate rule:

1. `meta.status === "running"`
2. last activity is older than 24 hours
3. no strong live ownership signal exists

Implemented last-activity rule:

1. latest event timestamp from `events.ndjson`
2. else `meta.updatedAt`
3. else `meta.startedAt`

Implemented live-signal exemptions:

1. wrapper-owned runs (`source = "run-search-workflow"`)
   - live workflow lock for the same `workflowRunId`, or
   - fresh `progressFile` heartbeat plus live wrapper PID
2. lifecycle runs (`source = "eval-backend-lifecycle"`)
   - registry entry not stopped and stored API port still responds
3. all other sources
   - age-only gating

Implemented repair shape:

1. append `run_reconciled`
2. set `meta.status = "failed"`
3. set `finishedAt` from last trustworthy activity
4. recompute `durationMs`
5. add reconciliation tags:
   - `reconciled`
   - `reconciledBy`
   - `reconciledReason`
   - `reconciledFromStatus`

Implemented additive report fields:

1. `summary.reconciledRuns`
2. top-level `reconciledRuns[]`

### Verification

Targeted verification passed:

```text
node scripts/lib/test-workflow-telemetry.mjs
node scripts/bench/test-report-workflow-attribution.mjs
node scripts/ci/test-workflow-quality-docs.mjs
```

Maintained gate verification also passed:

```text
node scripts/ci/dag-runner-workflow-quality-gate.mjs
```

Gate artifact:

- run id: `efa05549-712d-40d1-a24f-9aa2ed3cd451`
- manifest:
  `tmp/workflow-quality-gate/workflow-quality-gate-manifest-2026-03-12T19-18-55-986Z.json`
- closure summary:
  `tmp/workflow-quality-gate/closure-2026-03-12T19-18-55-986Z/workflow-quality-closure-summary.json`

Gate result:

- all 8 steps passed
- closure `slo.pass: true`
- closure `violationCount: 0`

### Real store repair result

Repair report artifact:

- `tmp/workflow-attribution-2026-03-12.reconciled.json`

Before report-driven repair:

- `totalRunning = 26`
- `olderThan24h = 25`

After report-driven repair:

- `totalRunning = 1`
- `olderThan24h = 0`
- `summary.reconciledRuns = 25`

The single remaining `running` record after repair was:

- `stage2-cc3-arguana-r2`
  - `workflowFamily = eval-backend-lifecycle.direct`
  - preserved because it still had a live ownership signal

Repaired workflow families:

1. `eval-backend-lifecycle.direct`: 9
2. `workflow-quality.gate`: 1
3. `workflow-otlp-pilot`: 6
4. `search-workflow.ranking-experiments`: 3
5. `search-workflow.beir-eval`: 6

Repaired sources:

1. `eval-backend-lifecycle`: 15
2. `run-search-workflow`: 9
3. `dag-runner-workflow-quality-gate`: 1

Representative repaired runs:

1. `stage2-cc3-scifact-v2`
2. `00000000-0000-0000-0000-000000000000`
3. `e44f798c-ee9d-4e07-9a27-b1b05c210d55`
4. `9446e269-af31-4ec7-8787-f66a56167171`
5. `workflow-otlp-pilot-1772847380208`

### Residual risk

This closes the rollup-pollution problem at the reporting layer, but it does
not yet prevent interrupted producers from leaving `status: "running"` records
behind in the first place.

Residual follow-up candidate:

1. producer-side terminalization on interrupted wrapper/lifecycle paths so
   stale metas are rarer even before a report or maintenance pass runs

## Status After This Refresh

As of 2026-03-12:

1. the workflow-quality gate is green again on current `main`
2. the fresh closure baseline passes all SLOs
3. the direct engine remains the trustworthy baseline
4. attribution is now good enough to interpret `manualForensicsCount`
   meaningfully
5. stale workflow-run reconciliation is now implemented for the canonical
   report path
6. the next remaining gap is producer-side terminalization on interrupted
   paths, not another readiness or lifecycle-control rewrite

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Workflow-quality followup with explicit numbered closure conclusions (fresh baseline passes SLOs, direct engine trustworthy, attribution sufficient, stale-run reconciliation implemented). Self-declared closure.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

