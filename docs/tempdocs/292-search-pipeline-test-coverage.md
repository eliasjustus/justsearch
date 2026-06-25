---
title: "Tempdoc 292 — Search Pipeline Test Coverage"
---

# Tempdoc 292 — Search Pipeline Test Coverage

**Status:** Complete (A1–A2 already existed; A3 implemented; A4–A5 deferred) — live-verified 2026-03-15
**Created:** 2026-03-15
**Updated:** 2026-03-15
**Goal:** Close critical test coverage gaps in the search pipeline's fusion, chunk merge, branch fusion, retry, and provenance paths — introduced or expanded across tempdocs 273–291.

## Context

The search pipeline has grown significantly through recent tempdocs (273: SPLADE integration, 278: chunk fusion, 280: branch fusion, 284: scoring calibration, 291: API response completeness). These changes added CC3 fusion, SPLADE parent-length modulation, branch fusion (CC/RRF), retry logic, per-leg timing, and extended provenance.

## Investigation Findings (2026-03-15)

### Existing test inventory

| Test class | Module | What it tests | Uses real Lucene? |
|------------|--------|---------------|-------------------|
| `HybridFusionUtilsTest` | adapters-lucene | `fuseWithRRF()`, `fuseWithCC()`, `fuseWithCC3()`, `fuseWithCCNamed()`: empty/single-leg/overlap/limit/debug, CC3 zero-exclusion, SPLADE parent-length modulation, branch CC debug keys, chunk_ prefixed keys | No (in-memory) |
| `HybridSearchOpsTest` | adapters-lucene | `shouldSkipVectorSearch()`, `computeLowSignalGating()`, vector weight scaling | No (config stubs) |
| `SearchOrchestratorCollapseTest` | indexer-worker | `collapseByParent()`, `collapseChunkHitsToParents()`: parent normalization, sibling evidence merge, SPLADE evidence preservation | No (in-memory hits) |
| `SearchOrchestratorComposablePathTest` | indexer-worker | `PipelineConfig` degradation: hybridFallback, vectorBlocked, spladeSkipReason | Yes (1 doc, BM25 only) |
| `SearchOrchestratorPipelineDispatchTest` | indexer-worker | `modeToDefaultPipeline()`, `deriveEffectiveMode()` round-trip | No (static methods) |
| `KnowledgeHttpApiAdapterProvenanceTest` | app-services | `assembleProvenance()` from debugScores: BM25/SPLADE routing, dense, fusion (RRF/CC), chunk merge (all legs including SPLADE + CC score), branch fusion (CC/RRF), cross-encoder | No (static method) |
| `KnowledgeHttpApiAdapterExpansionTest` | app-services | LLM expansion validation: null, blank, dedup, token rejection, limit | No |
| `KnowledgeHttpApiAdapterHarmfulCombinationsTest` | app-services | Reranker eligibility guards (256-F1) | No |
| `RrfFusionHarnessTest` | system-tests | RRF formula math in isolation via `RrfFusionHarness` | No (synthetic harness) |
| `GplStage3bBranchFusionReportTest` | app-services | GPL Stage 3B analysis report generation | No |
| `PassageRetrievalIntegrationTest` | system-tests (integrationTest) | End-to-end chunk-aware retrieval with RRF fusion against real Lucene + golden corpus | Yes (full) |

### Gaps assessed

#### G1: CC3 fusion — ALREADY COVERED

Initial investigation (via subagent) missed that `HybridFusionUtilsTest` already contains `FuseWithCC3Tests` (17 tests) and `FuseWithCCNamedTests` (2 tests), covering:
- Empty, single-leg, two-leg, three-leg scenarios with correct normalization math
- Zero-exclusion (renormalized weights, penalized missing legs)
- Weight edge cases (zero weight, asymmetric weights)
- SPLADE parent-length multiplier thresholds (Stage 3A)
- Chunk branch parent-length multiplier thresholds (Stage 3B)
- Namespaced score keys (`chunk_` prefix, `branch_merge_` prefix)
- Debug mode with effective weights and modifiers
- Branch CC with `zeroExclude` preserving exclusive hits

#### G2: Branch fusion — ALREADY COVERED

Same `FuseWithCCNamedTests` class tests named branch CC with debug keys, `minWeightMultiplier`, and `zeroExclude` behavior.

#### G3: Retry path — DEFERRED (low risk, high mock complexity)

Would require mocking `LuceneIndexRuntime` to return saturated results. Low regression risk since the retry is a quality-of-life optimization.

#### G4: Provenance new fields — WAS MISSING, NOW FIXED (A3)

`KnowledgeHttpApiAdapterProvenanceTest` had no tests for the tempdoc 291 extensions (SPLADE chunk, CC chunk score, branch fusion CC/RRF). Fixed by adding 11 tests.

#### G5: `pipelineExecution` branch_fusion component — DEFERRED

`buildPipelineExecution()` is `private`, requiring access changes for direct testing. The logic is 11 lines of trivial conditional plumbing. Accepted as a known gap.

## Action Items

### A1: CC3 fusion tests — ALREADY EXISTED

`HybridFusionUtilsTest.FuseWithCC3Tests` (17 tests at lines 849–1302) already covers all planned scenarios.

### A2: Branch fusion CC/RRF named tests — ALREADY EXISTED

`HybridFusionUtilsTest.FuseWithCCNamedTests` (2 tests at lines 1304–1378) already covers branch CC debug keys, parent-length multiplier, and zeroExclude.

### A3: Extend provenance test for new fields — DONE

Extended `KnowledgeHttpApiAdapterProvenanceTest` with 11 new tests:
- [x] `chunkMergeSpladeFieldsPopulated`: `chunk_splade_rank=1, chunk_splade=0.7` → `chunkMerge.spladeRank=1, spladeScore=0.7`
- [x] `chunkMergeSpladeOnlyTriggersChunkMerge`: SPLADE-only chunk rank > 0 (sparse/dense both 0) → chunkMerge still created
- [x] `chunkMergeCcScorePopulated`: `chunk_cc=0.85` → `chunkMerge.ccScore=0.85`
- [x] `chunkMergeCcScoreNullWhenAbsent`: no `chunk_cc` key → `chunkMerge.ccScore=null`
- [x] `branchFusionCcPopulated`: `whole_branch=0.4, chunk_branch=0.3, branch_merge_cc=0.6` → correct BranchFusionScore with method="cc"
- [x] `branchFusionRrfPopulated`: `branch_merge_rrf=0.45` → method="rrf"
- [x] `branchFusionRrfOverridesCc`: both CC and RRF keys present → RRF wins
- [x] `branchFusionNullWhenBothBranchScoresZero`: whole_branch=0, chunk_branch=0 → branchFusion=null
- [x] `branchFusionNullWhenChunkMergeNotApplied`: chunkMergeApplied=false → branchFusion=null regardless of keys
- [x] `branchFusionWithoutMergeKeyHasNullFusionScore`: branch scores present but no merge key → fusionScore=null, method=null

### A4: PipelineExecution branch_fusion component test — DEFERRED

`buildPipelineExecution()` is `private` and would need access-level changes for testing. The logic is trivial conditional plumbing (11 lines). Accepted gap.

### A5: Retry path — DEFERRED

Retry logic has low regression risk and high mocking complexity. Accepted as a known gap.

## Live Verification (2026-03-15)

After merging to main and clean-rebuilding, all implemented features from tempdocs 290/291/294 were verified against a live dev stack search for `"neural networks"` (47 hits, chunk merge applied, cross-encoder active).

### Tempdoc 291 — HitProvenance completeness

| Feature | Verified | Evidence |
|---------|----------|----------|
| `chunkMerge.spladeRank/spladeScore` | Yes | `spladeRank: 0, spladeScore: 0` (correct — no SPLADE chunks indexed in test corpus) |
| `chunkMerge.ccScore` | Yes | `ccScore: 0.9455` (CC3 fused chunk score) |
| `branchFusion` sub-record (chunk evidence hit) | Yes | `wholeBranchScore: 6.56, chunkBranchScore: 0.95, fusionScore: 0.30, method: "cc"` |
| `branchFusion` sub-record (whole-doc-only hit) | Yes | `wholeBranchScore: 9.47, chunkBranchScore: 0, fusionScore: 0.74, method: "cc"` |
| `branch_fusion` pipeline component | Yes | `status: "executed", reason: "cc"` |

### Tempdoc 294 — Per-leg timing

| Feature | Verified | Evidence |
|---------|----------|----------|
| `chunkBm25Ms` | Yes | `6` ms |
| `branchFusionMs` | Yes | `3` ms |
| `retrievalMs` / `chunkMergeMs` / `chunkCount` (existing) | Yes | `56` / `17` / `20` |

### Note on build cache

The first verification attempt ran against stale cached artifacts (Gradle `assemble` reported all tasks UP-TO-DATE despite the merge). A `clean assemble` + `installDist` was required to pick up the merged code. This is expected when switching between worktrees/branches without cleaning the build cache.
