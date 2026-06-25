---
evolution-rule: remove
---
Tempdoc 549 (unified search trace) retires the legacy search-explainability representations
from `knowledge.proto` in favor of the single canonical stage-keyed `SearchTrace`
(carried on `ipc-common`'s `SearchResponse.search_trace` / `SearchResult.trace`):

- `Hit.debug_scores` (field 4) — reserved; per-hit numeric detail is now `HitStage.detail` (E1).
- `Hit.provenance` (field 5) + the `HitProvenance` message and its `RetrieverScore` / `FusionScore`
  / `ChunkMergeScore` / `BranchFusionScore` / `RerankerScore` sub-messages — removed; per-hit
  ranking provenance is the per-doc slice of the stage vocabulary (E2).
- `KnowledgeSearchResponse.pipeline_execution` (field 24) + the `PipelineExecution` /
  `ComponentStatus` messages — removed; per-stage timing + component status live on the trace's
  `TraceStage.ms` / `status` (E3).
- The flat `KnowledgeSearchResponse` query-trace fields (7–21: `effective_mode`, `vector_blocked*`,
  `hybrid_fallback*`, `chunk_merge_applied/reason`, `correction_applied/corrected_query`,
  `expansion_applied`, `splade_executed/skip_reason`, `lambda_mart_applied`,
  `cross_encoder_applied/skip_reason`) — reserved; subsumed by the trace's scalars
  (`effective_mode` / `decision_kind` / `qpp` / `degradation`) + per-stage nodes (E5).

Breaking by design: this is the deliberate collapse of four+ overlapping representations into one
source of truth on the wire (549 principles 3 & 5). The Head and Worker are rebuilt together;
proto3 readers tolerate the absent fields. Field numbers are reserved to prevent reuse.
