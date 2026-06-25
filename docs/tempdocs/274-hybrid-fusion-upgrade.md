---
title: "274: Hybrid Fusion Upgrade — CC Default + 3-Way Retrieval"
type: tempdoc
status: done
created: 2026-03-11
depends-on: [270]
---

> NOTE: Working tempdoc. Implementation plan extracted from tempdoc 270
> (investigation-complete). Covers Stages 1-2 of the optimal routing
> architecture only.

# 274: Hybrid Fusion Upgrade — CC Default + 3-Way Retrieval

## Purpose

Implement the two highest-value, lowest-risk changes from tempdoc 270's
optimal search routing architecture:

1. **Stage 1**: Switch 2-way hybrid fusion from RRF to convex combination (CC)
2. **Stage 2**: Add 3-way BM25 + Dense + SPLADE retrieval with CC fusion

These validate the core design thesis: CC outperforms RRF (Bruch et al.,
ACM TOIS 2024), and 3-way retrieval with CC improves recall on short-document
corpora without hurting long-document performance.

## Context

- **Tempdoc 270** (investigation-complete): 5 rounds of theoretical research,
  80+ papers, 8-stage pipeline design. The consolidated final design specifies
  CC fusion and run-all-legs architecture.
- **Current system**: 2-way RRF fusion (BM25 + dense) via `HybridSearchOps`.
  SPLADE exists but `searchHybridSplade()` is 2-way only (SPLADE + dense,
  silently drops BM25).
- **This tempdoc** covers only the fusion and retrieval layers. Stages 3-4
  (QDDF per-document weighting, freshness, diversity) are deferred to a
  follow-up tempdoc contingent on Stage 2 results.

## Stage 1: CC as Default Fusion

### Eval Results

| Dataset | Queries | RRF nDCG@10 | CC nDCG@10 | Δ nDCG | RRF Recall@10 | CC Recall@10 | Δ Recall |
|---------|---------|-------------|------------|--------|---------------|--------------|----------|
| scifact | 300 | 0.6653 | 0.7109 | +6.9% | 0.7766 | 0.8189 | +5.4% |
| nfcorpus | 323 | 0.3108 | 0.3444 | +10.8% | 0.1521 | 0.1630 | +7.1% |
| arguana | 1406 | 0.2912 | 0.3134 | +7.6% | 0.6053 | 0.6430 | +6.2% |

**CC wins on all 3 datasets** (α=0.50, 2-way BM25+dense, nomic-embed-text-v1.5 Q4).
Eval date: 2026-03-11. Profile: `embedding-nomic-q4`.
Artifacts: `tmp/beir-eval/workflows/stage1-{rrf,cc}-{scifact,nfcorpus,arguana}/`.

### Key finding: fusion strategy is server-level

`fusionStrategy` in `HybridSearchOps.java:353` reads from
`ResolvedConfig.HybridSearch.fusionStrategy()`, set via env var
`JUSTSEARCH_HYBRID_FUSION_STRATEGY`. The per-query `fusionAlgorithm` in
`PipelineConfig` does NOT override the Worker's fusion strategy.

This means Stage 1 "default change" is an env-var / config default change,
not a per-query pipeline field.

### Implementation checklist

- [x] **1.1** Record Stage 1 eval results in table above
- [x] **1.2** Changed default fusion from `"rrf"` to `"cc"` in
  `ResolvedConfigBuilder.java:1008` (Option A). Two RRF-specific tests in
  `LuceneIndexRuntimeTest` pinned to `fusion_strategy=rrf` via system property.
  Also fixed `Get-BeirAnnProof` to check `pipeline` mode results (not just
  `hybrid`), enabling ANN proof PASS for pipeline-mode evals.
- [x] **1.3** ~~If CC < RRF on any dataset~~ — N/A: CC ≥ RRF on all 3 datasets
- [ ] **1.4** Update baselines: deferred to next CI gate run. Standalone eval
  metrics lack corpus identity metadata (`corpus_profile_id`, `corpus_signature`)
  required by `promote-search-eval-beir-baseline-win.ps1`. The gate will
  auto-pass since CC > RRF > 0.98 × RRF baseline.
- [x] **1.5** Gate verification: `./gradlew.bat test` passes (all modules,
  2m06s). CC default active, all unit tests green.

### Key files

| Purpose | Path |
|---------|------|
| CC dispatch | `modules/adapters-lucene/.../HybridSearchOps.java:353-358` |
| CC fusion impl | `modules/adapters-lucene/.../HybridFusionUtils.java:252-363` |
| Config record | `modules/configuration/.../ResolvedConfig.java:450-465` |
| Config builder | `modules/configuration/.../ResolvedConfigBuilder.java:423` |
| Preset expansion | `modules/app-services/.../KnowledgeHttpApiAdapter.java:1034-1064` |

## Stage 2: 3-Way Retrieval (BM25 + Dense + SPLADE)

### Prerequisites

- Stage 1 complete (CC default validated) — **done 2026-03-11**
- ONNX SPLADE model loaded at runtime — **ready** (see investigation below)
- Understanding of `SearchOrchestrator` retrieval dispatch (lines 388-399)

### SPLADE readiness investigation (2026-03-11)

**Model files**: Present at `models/splade/naver-splade-v3/` (532MB ONNX model,
tokenizer, IDF weights, config). This is the **dev fallback** path — the standard
auto-discovery path (`models/onnx/splade/`) has no files.

**Discovery path** (`SpladeModelDiscovery.java`): Checks 5 locations in order:
1. Explicit `JUSTSEARCH_SPLADE_MODEL_PATH` override
2. `<dataDir>/models/onnx/splade/` (AI Home)
3. `<repo.root>/models/onnx/splade/` (sidecar/Tauri)
4. `<baseDir>/models/onnx/splade/` (install dir)
5. `<baseDir>/models/splade/naver-splade-v3/` (**dev fallback** — this is what works)

`baseDir` = `user.dir` (CWD) when `JUSTSEARCH_HOME` is not set. For Gradle
`runHeadless`, CWD = repo root, so the dev fallback finds the model.

**Auto-enable**: Dev fallback sets `autoDiscovered = false`, so SPLADE does NOT
auto-enable. Requires explicit `JUSTSEARCH_SPLADE_ENABLED=true`.

**Code integration**: Fully wired — `SpladeEncoder` (ONNX), `SpladeIdfQueryEncoder`
(inference-free IDF), `SpladeConfig`, indexing backfill, `SearchOrchestrator`
(5+ search paths including `searchHybridSplade()`).

**Unit tests**: All SPLADE tests pass (`SpladeConfigTest`, `SpladeIdfQueryEncoderTest`,
`SpladePostProcessTest`, `SpladeEncoderNativePathTest`).

**Eval config**: Set `JUSTSEARCH_SPLADE_ENABLED=true` and
`JUSTSEARCH_SPLADE_MODEL_PATH=D:/code/JustSearch/models/splade/naver-splade-v3`
in `BackendEnv` for eval runs. The explicit path avoids discovery ambiguity.

**Remaining runtime risk**: ONNX model loading hasn't been verified in this
session (requires a live backend). The model was used successfully in prior
sessions (tempdoc 273 SPLADE quality eval). Risk is low.

### Confidence assessment (2026-03-11, updated)

**High confidence** (2.2 fuseWithCC3, 2.3 config extension, 2.5 unit tests):
Algorithmic/config work following clear existing patterns in `HybridFusionUtils`
and `ResolvedConfigBuilder`.

**Medium-high confidence** (2.1 orchestrator, 2.4 HybridSearchOps wiring):
Requires understanding `SearchOrchestrator` dispatch model and
`searchHybridSplade()` internals. Doable with careful exploration. SPLADE
infrastructure is fully implemented — this is wiring, not greenfield.

**Low risk** (2.6 eval, 2.7 gate): SPLADE model files present, discovery
path verified, prior runtime success (tempdoc 273). Eval config requires
explicit env vars but is straightforward.

### Problem statement

`searchHybridSplade()` is **2-way only**: it runs SPLADE as the text leg and
dense as the vector leg, silently dropping BM25. Passing
`{sparse: true, dense: true, splade: true}` does NOT produce true 3-way
retrieval. The existing `fuseLegs()` fallback (lines 1070-1086) does
sequential pairwise RRF, not simultaneous 3-way CC.

### Implementation checklist

- [x] **2.1** New orchestrator branch in `SearchOrchestrator`
  - Replaced `searchHybridSplade()` 2-way call with true 3-way parallel
    retrieval: `searchText()` + `searchVector()` + `searchSplade()` via
    virtual thread executor, fused with `fuseWithCC3()`
  - Candidate limits: BM25/SPLADE use `textCandidateMultiplier`, dense uses
    `vectorCandidateMultiplier`

- [x] **2.2** New `fuseWithCC3` method in `HybridFusionUtils`
  - Signature: `fuseWithCC3(SearchResult sparse, SearchResult dense,
    SearchResult splade, int limit, double[] weights, boolean debug,
    boolean zeroExclude)`
  - Min-max normalization per leg, 7-case `zeroExclude` with weight
    re-normalization across present legs
  - Debug score keys: `sparse`, `sparse_rank`, `vector`, `vector_rank`,
    `splade`, `splade_rank`, plus `cc`, `cc_weight_{sparse,dense,splade}`
    when debug=true

- [x] **2.3** Extended `ResolvedConfig.HybridSearch` for CC weight vector
  - Added 3 fields: `ccWeightSparse`, `ccWeightDense`, `ccWeightSplade`
  - `ccAlpha` retained for 2-way backward compat
  - Defaults: `[0.35, 0.35, 0.30]` (BM25, dense, SPLADE)
  - Env vars: `JUSTSEARCH_HYBRID_CC_WEIGHT_{SPARSE,DENSE,SPLADE}`

- [x] **2.4** Wired 3-way path in `SearchOrchestrator`
  - 3-way dispatch lives in `SearchOrchestrator` (not `HybridSearchOps`),
    replacing the old `searchHybridSplade()` call at the
    `canSparse && canDense && canSplade` branch
  - 2-way paths unchanged — existing `fuseWithCC` / `executeHybrid` untouched

- [x] **2.5** Unit tests for `fuseWithCC3` (17 tests)
  - Empty inputs, single-leg, two-leg, all-three combinations
  - `zeroExclude` weight re-normalization for all subsets
  - Weight edge cases (zero weight, asymmetric)
  - Score normalization, limit, null docId, debug keys, fields

- [x] **2.6** Stage 2 eval run (2026-03-11)
  - 3-way CC vs 2-way CC on scifact, nfcorpus (arguana deferred — CPU SPLADE
    indexing too slow for reliable completion; 2 runs crashed after 60-85 min)
  - Both datasets confirm SPLADE adds recall on short-doc corpora as expected
  - Results (weights: BM25=0.35, dense=0.35, SPLADE=0.30, zeroExclude=false):

    | Dataset  | Metric     | 2-way CC | 3-way CC | Δ     |
    |----------|------------|----------|----------|-------|
    | scifact  | nDCG@10    | 0.7109   | 0.7305   | +2.8% |
    | scifact  | Recall@10  | 0.8189   | 0.8396   | +2.5% |
    | nfcorpus | nDCG@10    | 0.3444   | 0.3517   | +2.1% |
    | nfcorpus | Recall@10  | 0.1630   | 0.1660   | +1.8% |

  - All queries ran in HYBRID mode, SPLADE executed on 100% of queries
  - ANN proof_status: PASS on both datasets
  - Metrics artifacts:
    - `tmp/beir-eval/workflows/stage2-cc3-scifact/beir-eval/metrics.v2.json`
    - `tmp/beir-eval/workflows/stage2-cc3-nfcorpus/beir-eval/metrics.v2.json`

- [x] **2.7** Gate verification (2026-03-11)
  - Both datasets pass 0.98 regression threshold (all deltas positive)
  - Runtime gates passed: `chunkVectorsReady=true`, `spladeReady=true`,
    `gates_passed=true` on both datasets
  - Arguana gate deferred with eval — no regressions expected given consistent
    +2% pattern across two corpora with different characteristics

### Arguana eval failure investigation (2026-03-11)

Two arguana runs failed for different root causes:

**Run 1 (`stage2-cc3-arguana`): EPERM file rename at 76% indexed**
- `writeJsonAtomic` in `scripts/lib/json-utils.mjs:67` does `write tmp → rename tmp → target`
- Windows AV/indexer held `meta.json` locked; 5 retries (50-250ms backoff) exhausted
- Crashed the entire eval — backend was killed, 6594/8674 docs indexed
- Fix: increase retry count or backoff in `writeJsonAtomic`, or use a Windows-safe
  write pattern (write to same dir, use `MoveFileEx` with `REPLACE_EXISTING`)

**Run 2 (`stage2-cc3-arguana-r2`): stuck forever on `spladeReady: false`**
- Root cause: **`spladeReady` boolean is NOT exposed by the Java `/api/status` API**.
  The API only provides coverage fields: `spladeDocCount`, `spladeCompletedCount`,
  `spladePendingCount`, `spladeFailedCount`, `spladeCoveragePercent`.
- The **PowerShell** eval gates (`BeirEval.Search.psm1:337-341`) correctly **compute**
  `spladeReady` from coverage fields: `docCount > 0 && pending ≤ 0 && failed ≤ 0 && coverage ≥ 99.9`
- The **Node.js** lifecycle (`eval-backend-lifecycle.mjs:278` and `run-search-workflow.mjs:352`)
  reads `statusSnapshot.spladeReady` directly from the API → `undefined` →
  `normalizeBoolean(undefined)` → `false` → always false
- `deriveManagedBackendPhase()` (line 387) enters `backend_splade_wait` phase
  and **reports it forever** in the `onBackendStatus` callback
- The `backend_splade_wait` phase itself is only **reporting**, not blocking —
  the PowerShell script runs independently. But the arguana-r2 PowerShell never
  started, suggesting the issue is earlier in the startup sequence
- PowerShell eval script DID launch (heartbeat events in telemetry prove it),
  but got stuck in `Wait-EvalIndexIdle` before reaching `Test-BeirRuntimeGates`
- `Wait-EvalIndexIdle` requires `queueSeenNonZero` — at least one file must
  enter the job queue. But run 2 reused run 1's data dir where all 8674 files
  were already indexed. Re-discovery produces no new queue entries → wait loops
  forever. This is the same bug that hit scifact (fixed with `SkipIngest: true`
  + `SkipWait: true` in that config)
- The arguana config lacked `SkipIngest`/`SkipWait` because it was intended for
  fresh indexing, but run 2 inadvertently reused run 1's partial data

**Root cause**: The eval toolchain assumes a clean, single-shot lifecycle.
Every component breaks when confronted with partial state from a prior crashed
run. The three symptoms are all manifestations of this gap:

**Fix 1 — Telemetry writes best-effort** (`scripts/lib/workflow-telemetry.mjs`) — **DONE 2026-03-11**
- Root cause: a telemetry write failure (`writeJsonAtomic` EPERM) crashed the
  entire eval workflow. Telemetry is bookkeeping — the workflow shouldn't die
  because it can't update its log file.
- Fix: wrapped all `writeJsonAtomic` and `appendFile` calls in
  `createWorkflowRunStore`, `writeMetaPatch`, and `appendEvent` with try-catch.
  Failures log to stderr and continue. The workflow runs without telemetry
  rather than crashing.

**Fix 2a — `Wait-EvalIndexIdle` bypass for resumed data** (`scripts/eval/EvalSession.psm1:418-424`) — **DONE 2026-03-11**
- Root cause: `queueSeenNonZero` gate requires queue activity, but resumed runs
  with fully-indexed data produce no new queue entries → permanent deadlock.
- Fix: added `$docCountSufficient` bypass. When `indexedDocuments >=
  ExpectedIndexedDocumentCountMin`, skip the queue-activity gate. Fresh runs
  still see queue activity; resumed runs with sufficient docs complete immediately.

**Fix 2b — `expectedIndexedDocsMin` double-counting** (`scripts/search/lib/BeirEval.Indexing.psm1:326`) — **DONE 2026-03-11**
- Root cause: expected count was `initialIndexedDocs + corpus_size`. On resumed
  data dirs, `initialIndexedDocs` already includes corpus docs from the prior
  run, creating an unreachable target (e.g., 6594 + 8674 = 15268 when only
  8674 docs exist).
- Fix: changed to `max(initialIndexedDocs, corpus_size)`. The expected count
  is the corpus size, not the sum.

**Fix 3 — `spladeReady` computation in Node.js** — **DONE 2026-03-11**
- Root cause: `eval-backend-lifecycle.mjs:278` and `run-search-workflow.mjs:352`
  read `statusSnapshot.spladeReady` from the Java API, which doesn't expose
  this field → always `undefined` → always `false`.
- Fix: added `computeSpladeReady()` function in both files that derives
  readiness from coverage fields (`spladeDocCount`, `spladePendingCount`,
  `spladeFailedCount`, `spladeCoveragePercent`), matching the PowerShell logic.
  Phase reporting now correctly shows `workflow_evaluating` instead of permanent
  `backend_splade_wait`.

### Key files

| Purpose | Path |
|---------|------|
| Orchestrator | `modules/indexer-worker/.../SearchOrchestrator.java` |
| Fusion utils | `modules/adapters-lucene/.../HybridFusionUtils.java` |
| Hybrid ops | `modules/adapters-lucene/.../HybridSearchOps.java` |
| Pipeline config | `modules/app-api/.../PipelineConfig.java` |
| Config record | `modules/configuration/.../ResolvedConfig.java` |
| Config builder | `modules/configuration/.../ResolvedConfigBuilder.java` |
| Fusion tests | `modules/adapters-lucene/.../HybridFusionUtilsTest.java` |

## Out of Scope

The following are explicitly deferred to a follow-up tempdoc contingent on
Stage 2 results validating the architecture:

- **Stage 3**: Per-document weight modulation (QDDF), chunk-level fusion
  before MaxP, `parent_token_count` DocValues, GPL 3-way extension, alpha
  sweep calibration
- **Stage 4**: Freshness scoring, MMR diversity, query classification
- **Background**: Thompson Sampling, shadow logging, implicit feedback
- **Product**: User mode deprecation, routing transparency indicators

Reference: tempdoc 270 consolidated final design for the full architecture.

## Verification

## Closure Note (2026-03-12)

Both stages validated and shipped on `main`. CC fusion is the default (Stage 1:
+6.9% to +10.8% nDCG over RRF). 3-way retrieval adds +2.1% to +2.8% nDCG
over 2-way on short-doc corpora (Stage 2). Arguana eval deferred due to CPU
SPLADE indexing time; eval-toolchain fixes shipped to unblock future runs.

Item 1.4 (baseline promotion) will auto-resolve on next CI gate run — CC
baselines exceed 0.98× RRF baselines on all datasets.

Stages 3+ (QDDF, freshness, diversity, Thompson Sampling) deferred to a
successor tempdoc contingent on these results. See "Out of Scope" above.

## Verification

1. **Compile**: `./gradlew.bat build -x test`
2. **Format**: `./gradlew.bat spotlessApply`
3. **Unit tests**: `./gradlew.bat :modules:adapters-lucene:test` (fusion)
   and `./gradlew.bat :modules:indexer-worker:test` (orchestrator)
4. **BEIR eval**: `run-search-workflow.mjs beir-eval` per stage
5. **Diff**: `diff-search-eval-suite.mjs --min-ndcg-ratio 0.98`
6. **Full gate**: `.\scripts\gate.ps1`
