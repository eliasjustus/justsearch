---
title: Search Pipeline Invariants
type: reference
status: stable
description: "Invariants and blocked feature combinations enforced by the search pipeline, with contract test references."
---

# Search Pipeline Invariants

Invariants and blocked combinations that the search pipeline enforces.
Contract tests live in `KnowledgeHttpApiAdapterHarmfulCombinationsTest.java`.

## Blocked Combinations

| Feature A | Feature B | Behaviour | Enforcement point |
|-----------|-----------|-----------|-------------------|
| HYBRID mode | Query expansion (AI translation) | Expansion silently skipped | `KnowledgeHttpApiAdapter.isExpansionEligible()` — returns false when mode ≠ TEXT |
| Stemming fallback | Fuzzy fallback | Applied sequentially, never simultaneously | Both are TEXT-mode SIMPLE-syntax fallbacks; fuzzy only fires when stemmed result set is empty |
| Explicit filters | QU-extracted boostFilters | QU boost not applied when caller provides filters | `KnowledgeHttpApiAdapter.doSearch()` checks `hasExplicitFilters` before QU dispatch (363) |
| Soft boost (SHOULD) | Zero results | Soft boost never produces zero results | `QueryFilterBuilder.applyBoostFilters()` uses `BooleanClause.Occur.SHOULD` — additive, always falls back to content-score-only (363) |
| FilterNormalization | Search vs answer path | Fires on both paths when enabled | `FilterNormalizationService` runs async on both `KnowledgeHttpApiAdapter` (search) and `RetrieveContextController` (answer) when `JUSTSEARCH_FILTER_NORM_ENABLED=true` (366) |
| Entity/metadata filters | Chunk documents | Never applied to chunks directly | Two-stage pre-filter: parent-doc ID lookup first, then chunk search scoped to matching parent IDs. `buildChunkFilterQuery()` excludes these; `buildFilterQueryOnly()` includes them (362) |
| Entity facet keys | MCP layer | `_raw` suffix stripped before agent response | Backend uses `_raw`-suffixed field names; MCP server strips `_raw` suffix before returning to agents (366) |

### Why HYBRID blocks expansion

Query expansion (AI translation) generates morphological variants for BM25.
In HYBRID mode, dense retrieval already provides semantic recall, making
expansion redundant and potentially harmful (expanded terms can dilute BM25
precision without improving fusion quality).

Note: Reranking (LambdaMART + cross-encoder cascade) is **not** blocked in
HYBRID mode — it fires for all presets when enabled (256-F1). The cross-encoder
operates on the fused candidate list regardless of which retrieval legs
produced it.

### Why stemming and fuzzy are sequential

Stemming is applied as a first-pass TEXT fallback (expand query to include stemmed variants).
Fuzzy is applied only when the stemmed query returns zero results.
Running both simultaneously would double-count variant terms and produce unpredictable recall.

### Why QU is bypassed when explicit filters are present

When a caller provides `filters` or `boostFilters` in the request, the caller
has already decided what to filter on. Applying QU-extracted boostFilters on
top would create unpredictable interactions (e.g., QU detects "The Verge" and
adds a soft boost, but the caller already has an explicit `metaSource` filter).
The `hasExplicitFilters` check in `doSearch()` prevents this overlap (363).

### Why soft boost (SHOULD) never produces zero results

Hard filters (`FILTER` clauses) caused 7/50 zero-result queries in the 362
agent eval when filter values didn't match the index vocabulary exactly. Soft
boosts use `BooleanClause.Occur.SHOULD` — they promote matching documents but
don't exclude non-matching ones. The system always falls back to content-score-
only results when no documents match the boost criteria (363).

### Why FilterNormalization fires on both search and answer paths

Filter value normalization (case folding + deterministic substring matching +
optional LLM fallback) must apply consistently regardless of whether the caller
uses `POST /api/knowledge/search` or `POST /api/knowledge/retrieve-context`.
Both paths accept the same filter fields and route through the same index.
Inconsistent normalization would cause the answer path to miss documents that
the search path finds (or vice versa). The service runs async with a deadline
to avoid blocking either path (366).

### Why metadata/entity filters are never applied to chunk docs directly

Entity and metadata fields exist on parent documents only (extracted from
frontmatter and content at index time). Chunk documents don't carry these
fields. Applying a `metaSource` filter directly on a chunk query would return
zero results. The two-stage pre-filter pattern solves this: first, find parent
document IDs matching the filter (`buildFilterQueryOnly`), then search chunks
scoped to those parent IDs. `buildChunkFilterQuery` intentionally excludes
entity and metadata filter fields to prevent accidental direct application (362).

### Why entity facet keys are stripped in the MCP layer

The Lucene index stores entity values in `_raw`-suffixed fields (e.g.,
`entity_persons_raw`) to preserve original casing for facet aggregation
(separate from the analyzed/lowercased fields used for search). Agents don't
need to know about this implementation detail. The MCP server strips the
`_raw` suffix so agents see clean field names (`entity_persons`) that match
the filter parameter names they use (366).

## Enforcement Pattern

Guard conditions are extracted as static package-private methods to enable unit testing:

```java
// KnowledgeHttpApiAdapter.java
static boolean isExpansionEligible(
    SearchMode mode, SearchQuerySyntax syntax, String query, String cursor, boolean aiAvailable)

static boolean isRerankerEligible(
    PipelineConfig pipeline, RerankerConfig config, int resultCount,
    long avgContentLengthChars, QueryType queryType)
```

`isRerankerEligible` checks `pipeline.crossEncoderEnabled()`, min hits
threshold, and average document length (258-B1: auto-disables cross-encoder
when documents exceed the reranker's 512-token input — active model is
`gte-multilingual-reranker-base` (343)). It does **not** gate on search mode.

Tests assert both the positive case (eligible config → feature applied) and
negative cases (expansion blocked in HYBRID). See
`KnowledgeHttpApiAdapterHarmfulCombinationsTest.java` for the full contract.
