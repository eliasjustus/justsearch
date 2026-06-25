---
title: "Tempdoc 291 — Search API Response Completeness"
---

# Tempdoc 291 — Search API Response Completeness

**Status:** Complete (A1–A4 implemented; A5 deferred — frontend)
**Created:** 2026-03-14
**Updated:** 2026-03-14
**Goal:** Ensure that search API responses accurately and completely reflect all pipeline stages that contributed to ranking, so that consumers (UI, eval harness, MCP agents, debug tools) have full provenance.

## Context

The search API returns structured provenance per hit (`HitProvenance`) and a `pipelineExecution` report with per-component status and timing. As the pipeline has grown (SPLADE retrieval, chunk fusion, branch fusion), some new data flows through the raw `debugScores` map but is not reflected in the structured provenance objects.

## Investigation Findings (2026-03-14)

### Current state of HitProvenance

`HitProvenance` is a structured per-hit breakdown of which retrieval legs contributed to ranking. It contains typed sub-records: `RetrieverScore` (rank + raw score per leg), `FusionScore` (fused score + method), `ChunkMergeScore` (chunk leg ranks/scores), and `RerankerScore` (cross-encoder score).

It is assembled by `KnowledgeHttpApiAdapter.assembleProvenance()` (lines 1184–1250) from the raw `debugScores` map. Currently reads: `sparse`, `sparse_rank`, `vector`, `vector_rank`, `rrf`, `cc`, `chunk_sparse`, `chunk_sparse_rank`, `chunk_vector`, `chunk_vector_rank`. Does NOT read: `chunk_splade`, `chunk_splade_rank`, `chunk_cc`, `whole_branch`, `chunk_branch`, `branch_merge_*`.

### Consumer analysis

| Consumer | Reads `HitProvenance`? | Reads `debugScores`? | Reads `pipelineExecution`? |
|----------|----------------------|---------------------|---------------------------|
| **Frontend** (ui-web) | No — mapper drops it, `SearchHit` has no `provenance` field | No | No — type exists but mapper drops it |
| **BEIR eval harness** | No — bypasses, reads `debugScores` directly | Yes — reads `vector`, `sparse`, `chunk_vector`, `chunk_sparse`, `rrf` + rank keys | Yes — `Get-BeirPipelineExecutionComponentStatus` reads component status/reason |
| **MCP production server** | No in slim mode (stripped); yes in verbose mode (passthrough via `z.any()`) | Same as provenance | No |
| **GPL analysis tools** | No — reads JSONL triple store directly via gRPC, never the HTTP API | N/A (gRPC path) | N/A |

**Key finding:** `HitProvenance` has zero active consumers today. The eval harness reads `debugScores` directly because it predates `HitProvenance`. The frontend has TypeScript types defined but the mapper silently drops provenance.

### Why HitProvenance matters despite zero current consumers

`HitProvenance` provides a typed, structured API contract for per-hit ranking explanation. Without it, consumers must know internal debug score key names (`"sparse_rank"`, `"chunk_vector"`, `"branch_merge_cc"`) which are implementation details. Concrete future consumers:

1. **Frontend search quality inspector** — A debug panel showing "why this result ranked here" with per-leg contribution breakdown. The TypeScript types already exist; only the mapper needs to pass data through. (Not implementing now — frontend work is out of scope.)

2. **Eval harness migration** — The BEIR eval harness hard-codes debug key names across ~100 lines. Migrating to `provenance.*` would decouple eval scripts from internal fusion key names and make the eval harness robust to future pipeline changes (e.g., renaming `rrf` → `cc`, adding new legs).

3. **MCP agent explainability** — External AI agents calling the production MCP server's search tool could use structured provenance to reason about result quality without parsing an opaque flat map. Currently only available in `verbose=true` mode with `z.any()` passthrough.

4. **Search quality regression detection** — Automated comparison of provenance across builds ("in build A, 80% of hits had chunk branch contribution; in build B only 40%") is more robust with typed fields than string-key-dependent flat maps.

5. **GPL tools** — No benefit. They read the gRPC-level triple store with richer data (branch weights, modifiers, QPP) than HitProvenance could provide.

## Revised Gaps

### G1: `HitProvenance` is incomplete for the current pipeline

`ChunkMergeScore` only has `sparseRank/Score` and `denseRank/Score`. Missing:
- `spladeRank` / `spladeScore` (SPLADE chunk evidence)
- `ccScore` (fused CC3 score)

No branch fusion sub-record exists. Missing:
- `wholeBranch` / `chunkBranch` (branch-level inputs)
- `branchFusionScore` / `branchFusionMethod` (merged result)

### G2: `pipelineExecution` missing branch fusion component

The eval harness reads `pipelineExecution.components` for component status. It checks `splade`, `lambdamart`, `cross_encoder`, `expansion`. There is a `chunk_merge` component but no `branch_fusion` component. Adding one would let the eval harness track branch fusion execution rates.

### G3: Eval harness coupled to debug key names

The eval harness reads ~10 debug score keys by name (`"vector"`, `"sparse"`, `"chunk_sparse"`, etc.). Migrating to structured `provenance.*` fields would decouple eval scripts from internal naming.

### G4: MCP server doesn't expose provenance in slim mode

The MCP production server strips provenance in non-verbose mode. An `includeProvenance` flag would let agents request structured ranking explanations without the full verbose payload.

## Action Items

### A1: Complete `HitProvenance` structure — DONE

- [x] Extended `ChunkMergeScore` with `spladeRank`, `spladeScore`, `ccScore`
- [x] Added `BranchFusionScore` record (`wholeBranchScore`, `chunkBranchScore`, `fusionScore`, `method`)
- [x] Updated `assembleProvenance()` to read `chunk_splade`, `chunk_splade_rank`, `chunk_cc`, `whole_branch`, `chunk_branch`, `branch_merge_cc`/`branch_merge_rrf`

### A2: Add `branch_fusion` component to `pipelineExecution` — DONE

- [x] Added `branch_fusion_strategy` (field 23) and `branch_fusion_contributed` (field 24) to proto
- [x] Added `branchContributed` + `branchFusionStrategy` to `ChunkMergeResult` record
- [x] Wired from `SearchOrchestrator` → proto → `buildPipelineExecution()` → `branch_fusion` component
- [x] Component shows `executed` (with strategy as reason) or `skipped` (`CHUNK_BRANCH_EMPTY`)

### A3: Migrate eval harness to structured provenance — DONE

- [x] Migrated `BeirEval.Search.psm1` to read from `provenance.*` first, falling back to `debugScores` for backward compat
- [x] Top-hit telemetry: reads `provenance.dense.rawScore`, `provenance.bm25.rawScore` (or `.splade`), `provenance.chunkMerge.denseScore/sparseScore`, `provenance.fusion.score`
- [x] Per-hit evidence loop: reads `provenance.*.rank` for participation detection, falls back to `debugScores.*_rank`
- [x] Updated test mocks with `provenance` sub-objects (hybrid doc-level + chunk-level mocks)
- [x] Test passes: `powershell -File scripts/search/test-beir-eval-search-lib.ps1` → PASS

### A4: Add `includeProvenance` flag to MCP production server — DONE

- [x] Added `includeProvenance` boolean to search tool Zod input schema (`schemas.mjs`)
- [x] Modified `slimSearchResult()` to accept `includeProvenance` flag and conditionally include `r.provenance`
- [x] Updated result mapper to pass `input.includeProvenance` to `slimSearchResult()`

### A5: Frontend mapper wiring (not implementing — noted for future)

The frontend TypeScript types (`HitProvenance`, `PipelineExecution`) already exist in `search.ts`. The `mapKnowledgeSearchResponse()` mapper in ui-web needs to pass `provenance` through to `SearchHit` and `pipelineExecution` through to `SearchResponse`. A UI debug panel could then display per-hit ranking explanations. This is frontend work — out of scope for this tempdoc but documented here for future reference.
