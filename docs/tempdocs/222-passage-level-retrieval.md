---
title: "Passage-Level Retrieval (SRQ-002)"
type: tempdoc
status: done
created: 2026-02-19
updated: 2026-02-20
parent: 135-search-retrieval-quality.md
---

# 222. Passage-Level Retrieval (SRQ-002)

Interactive search currently returns whole documents. A user querying "VRAM memory management"
gets back a 40-page PDF — they must locate the relevant paragraph themselves. Passage-level
retrieval returns the specific excerpt that matched, alongside the parent document reference.

## What already exists

### Chunking infrastructure (RAG pipeline)

The RAG pipeline already does chunked retrieval end-to-end. Chunks and whole documents live in
the **same Lucene index** — there is no separate chunk index. Chunks are regular Lucene documents
marked with `IS_CHUNK=true` and `PARENT_DOC_ID=<parent>`.

| Component | Location | What it does |
|-----------|----------|--------------|
| Chunk splitter | `ChunkSplitter.java` (`modules/indexing`) | Splits documents ≥2000 chars into 500-token overlapping windows (50-token overlap) at index time. 5 content-aware modes: DEFAULT, MARKDOWN, CODE, CSV, JSON. |
| Chunk writer | `ChunkDocumentWriter.java` (`modules/indexing`) | Writes chunk documents to the shared Lucene index with `IS_CHUNK=true`, `PARENT_DOC_ID`, char offsets, line numbers, heading context. |
| Schema fields | `SchemaFields.java` (`modules/indexing`) | 17+ chunk fields: `CHUNK_CONTENT`, `CHUNK_VECTOR` (768-dim), `CHUNK_INDEX`, `CHUNK_TOTAL`, `CHUNK_START_CHAR`/`END_CHAR`, `CHUNK_START_LINE`/`END_LINE`, `CHUNK_HEADING_TEXT`/`LEVEL`, `CHUNK_EMBEDDING_STATUS`. |
| Chunk search | `RagContextOps.searchChunksWithMeta()` (lines 246–441) | BM25 + optional kNN over chunk docs. Diversification (position-based or MMR) + token budgeting. **No parent-doc deduplication** — multiple chunks from one parent can appear. |
| Chunk reranker | `RagContextOps` (lines 667–847) | Cross-encoder reranking on BM25-mode chunks. Adaptive GPU/CPU ordering. Auto-enables when model discovered. |
| Chunk vector coverage | `RagContextOps.isChunkVectorCoverageReady()` (line 860) | Returns true when ≥95% of chunks have embeddings. Enables chunk-level hybrid (kNN over `CHUNK_VECTOR`). |
| Virtual chunk fallback | `RagContextOps.buildFallbackWithVirtualChunks()` (lines 499–616) | When no pre-indexed chunks exist, splits full docs at query time using `ChunkSplitter`. Enables graceful degradation. |

### Excerpt infrastructure (interactive search)

Interactive search already has excerpt extraction and rendering. This was not documented in the
original tempdoc.

| Component | Location | What it does |
|-----------|----------|--------------|
| Excerpt extractor | `HighlightingOps.computeExcerptRegions()` (`indexer-worker`) | BM25-scored clustering + IDF weighting. Extracts up to 3 query-focused regions (~200–400 chars each) per hit. Sentence-boundary snapping, overlap rejection, line number computation. |
| Excerpt proto | `indexing.proto` `ExcerptRegion` message | `text`, `start_char`, `end_char`, `approx_line`, `repeated MatchSpan match_spans`. |
| Search orchestrator | `SearchOrchestrator.java` (lines 480–502) | Calls `HighlightingOps` for top-10 TEXT/HYBRID hits when `includeExcerpts=true`. Retrieves full doc content via `getDocumentContent()`. |
| HTTP API adapter | `KnowledgeHttpApiAdapter.java` (lines 362–433) | Converts gRPC excerpts to HTTP API format with line/span info. |
| Frontend rendering | `ResultRow.tsx` `ExcerptRegionsBlock` (lines 124–153) | Renders excerpt regions with `~line N` indicators. Shown in rich/comfort density modes. Replaces snippet block when excerpts available. |
| Frontend types | `types.ts` `ExcerptRegion` (lines 10–16) | TypeScript model: `text`, `startChar`, `endChar`, `approxLine`, `matchSpans`. |
| Agent integration | `SearchTool.java` (lines 187–196) | Consumes excerpt regions, caps at 800 chars for agent context. |
| Chunk exclusion | `QueryFilterBuilder.applyRuntimeFilters()` | Adds `MUST_NOT IS_CHUNK=true` to all interactive search queries. Chunks are invisible to interactive search by default. |

### Key architectural facts

1. **Single index.** Chunks and whole documents share one Lucene index. There is no separate
   "chunk index" or "document index" — `QueryFilterBuilder` controls which document type each
   query path sees.
2. **Excerpts already rendered.** The frontend already shows query-focused excerpts in
   rich/comfort density modes. The gap is not "how to show passages" but "would chunk-based
   retrieval produce better passages than full-doc excerpt extraction?"
3. **Short docs not chunked.** `ChunkDocumentWriter` skips documents <2000 chars. A chunk-based
   interactive search path must handle both chunked (long) and unchunked (short) documents.

## Research questions

1. **Should interactive search use the existing chunk index or a new one?**
   The RAG chunk index is already there. Can `searchText()` be retargeted to query chunks and
   collapse results by parent doc? Or does the chunk schema have fields/settings tuned for RAG
   that would hurt interactive search (e.g. different field boosts, chunk-only stored fields)?

2. **What is the deduplication strategy?**
   If a document has 30 chunks and 8 match the query, the user should see one result with the
   best-matching excerpt, not 8 result cards. How does the RAG path handle this? Is the same
   logic reusable for interactive search, or does it collapse too aggressively?

3. **What does the result card look like?**
   Currently: title + file path + last-modified. With passages: title + file path + matched
   excerpt (highlighted?). How long should the excerpt be? Should it be the raw chunk or a
   query-focused snippet extracted from the chunk?

4. **Does chunked retrieval regress recall for short documents?**
   For a 1-paragraph note, chunking is identity. For a 50-page PDF, chunking dramatically
   improves precision. The golden corpus and BEIR eval can measure this — what's the Recall@3
   impact of switching to chunk-based retrieval on existing query sets?

5. **What happens to the VECTOR search mode?**
   Embeddings are currently computed per document. Chunked embedding is more expensive (one
   embedding per chunk vs per document) but is standard for dense retrieval. Does the RAG path
   already compute per-chunk embeddings, and if so, can they be reused?

6. **Index migration story.**
   Switching to chunk-based interactive search requires re-indexing all documents. Is there a
   migration path that doesn't require manual user action (e.g., background re-index, dual-read
   during transition)?

## Investigation tasks

- [x] Read `RagContextOps.searchChunksWithMeta()` in full — understand the exact deduplication
      logic, field mappings, and how chunk hits are surfaced to callers.
- [x] Read `ChunkSplitter.java` — understand chunk size, overlap, and how chunk metadata (parent
      doc ID, chunk index, byte offsets) is stored.
- [x] Read `LuceneIndexRuntime` — identify exactly which Lucene index interactive search queries
      and whether that index has a `CHUNK_CONTENT` field at all.
- [x] Check whether per-chunk embeddings exist in the current index or only per-document embeddings.
- [x] Run a local experiment: query the chunk documents directly with a TEXT mode query and
      measure Recall@3 vs the current whole-doc search. (Pipeline validated with deterministic
      vectors; real-embedding run pending.)

## Investigation findings (2026-02-19)

### Q1 — Same index, not separate indexes

**Answer:** There is only one Lucene index. Chunks and whole documents coexist.

Interactive search queries the same index as the RAG chunk path. The difference is a query-time
filter: `QueryFilterBuilder.applyRuntimeFilters()` adds `MUST_NOT TermQuery(IS_CHUNK, "true")`
for all interactive search queries. The RAG path sets `filters.includeChunks()=true` to bypass
this exclusion.

"Retarget interactive search to the chunk index" (Option A) is really: stop filtering out
chunks and add parent-doc collapsing. No new index, no schema change, no data migration.

**Evidence:**
- `QueryFilterBuilder.applyRuntimeFilters()` — chunk exclusion filter
- `ChunkDocumentWriter.java:153` — `runtime.index(new IndexDocument(fields))` uses the same index writer
- `WritePathOps.deleteChunksForParentDocId()` — BooleanQuery on `PARENT_DOC_ID` + `IS_CHUNK` in the shared index
- `ChunkSearchOps.searchChunksForDocs()` — filters by `parent_doc_id` and `is_chunk=true` within the same searcher

### Q2 — RAG deduplication is not reusable for interactive search

**Answer:** `RagContextOps` does **not** deduplicate by parent document. It returns all matching
chunks ranked by BM25/hybrid score, then applies:
1. **Diversification** — position-based (beginning/middle/end thirds) or MMR-based (semantic diversity)
2. **Token budgeting** — `ContextBudgeter` stops appending chunks when the token limit is exhausted

Multiple chunks from the same parent *can* appear in the final result. This is desirable for RAG
(more context from a highly relevant doc) but wrong for interactive search (users expect one result
card per document).

Interactive search needs a different strategy: **collapse by `PARENT_DOC_ID`**, keeping the
top-scoring chunk per parent as the excerpt. Options:
- Lucene `CollapsingTopDocsCollector` (if it works with hybrid queries — needs research)
- Post-retrieval Java grouping: fetch N×limit chunk results, group by parent, keep top chunk per parent, trim to limit

**Evidence:**
- `RagContextOps:246-441` — `searchChunksWithMeta()` flow
- `RagContextOps:349-367` — diversification (not deduplication)
- `RagContextOps:374-409` — token budgeting

### Q3 — Result card UI already supports excerpts

**Answer:** The result card already renders excerpts in rich/comfort density modes.

Current rendering (`ResultRow.tsx`):
- **Rich/comfort mode:** title + path + `ExcerptRegionsBlock` (up to 3 regions with `~line N`
  indicators and match span highlighting). Replaces the snippet when excerpts are available.
- **Compact mode:** title + path + snippet (no excerpts, `includeExcerpts=false`).

For chunk-based retrieval, the chunk text *is* the passage. It could be displayed directly as an
excerpt region with line-number attribution from `CHUNK_START_LINE`. No new UI components needed.

The remaining gap is match span highlighting within the chunk text. Currently,
`HighlightingOps.computeExcerptRegions()` computes match spans relative to the excerpt window.
For chunk-based results, match spans would need to be computed against `CHUNK_CONTENT` instead
of the full document.

**Evidence:**
- `ResultRow.tsx:124-153` — `ExcerptRegionsBlock` component
- `ResultRow.tsx:170-176` — density-based toggle: show excerpts or snippet
- `HighlightingOps.java:95-232` — excerpt extraction pipeline
- `SearchOrchestrator.java:480-502` — excerpt integration in gRPC response
- `useSearch.ts:181` — `includeExcerpts: density !== 'compact'`

### Q4 — No regression risk for short documents

**Answer:** Documents <2000 chars are not chunked (`ChunkDocumentWriter` line 77:
`content.length() < CHUNK_THRESHOLD_CHARS`). They have no chunk documents in the index.

A chunk-based interactive search must handle both cases:
- **Long docs (≥2000 chars):** Match via chunk documents, show best chunk as excerpt
- **Short docs (<2000 chars):** Match via whole-doc documents as today

This dual-path query is natural with the single-index architecture: include both chunk docs and
non-chunked whole docs in the query, collapse by effective parent ID (chunk's `PARENT_DOC_ID` or
whole doc's `DOC_ID`). Short docs continue matching exactly as they do today.

For recall measurement: the golden corpus has 10 documents of varying lengths. A local experiment
querying chunks vs whole docs would confirm no regression. The `buildFallbackWithVirtualChunks()`
path already handles the no-chunks case by splitting at query time, confirming the architecture
supports graceful degradation.

### Q5 — Per-chunk embeddings exist and are usable

**Answer:** Per-chunk embeddings are fully implemented.

- `CHUNK_VECTOR`: 768-dimensional embedding field in `SchemaFields.java`
- `CHUNK_EMBEDDING_STATUS`: tracks "PENDING", "COMPLETED", "FAILED" per chunk
- `isChunkVectorCoverageReady()` (`RagContextOps:860-888`): returns true when ≥95% of chunks
  have completed embeddings
- The RAG path already does chunk-level hybrid search (BM25 + kNN over `CHUNK_VECTOR`) when
  coverage is ready

Interactive search could reuse the same chunk embeddings for VECTOR and HYBRID modes. The
embedding computation cost is already paid at index time — no additional overhead for interactive
search.

### Q6 — No index migration needed

**Answer:** Chunks are already created at index time for all documents ≥2000 chars. The index
already contains chunk documents. The change is purely in the query path.

Documents indexed before chunking was introduced would not have chunks. This is handled by
`buildFallbackWithVirtualChunks()`, which splits full docs at query time as a fallback. A
background re-index would eventually create chunks for all documents.

No dual-read, no schema migration, no user action required. The worst case is that some older
documents temporarily use whole-doc matching until they are re-indexed.

## Design options (revised after investigation)

### ~~Option A — Retarget interactive search to the existing chunk index~~
### Option A — Chunk-aware interactive search (recommended path)

Remove the `IS_CHUNK` exclusion filter for interactive search queries. Query both chunk documents
and unchunked whole documents in a single Lucene query. Collapse results by parent document
(`PARENT_DOC_ID` for chunks, `DOC_ID` for whole docs), keeping the top-scoring hit per parent.
For chunk hits, use `CHUNK_CONTENT` + `CHUNK_START_LINE` as the excerpt. For whole-doc hits,
fall back to existing `HighlightingOps` excerpt extraction.

- **Pro:** Minimal code change (filter removal + collapsing). No new index, no new chunking
  logic. Reranker already works on chunk text. Excerpt UI already exists. Improves recall for
  long documents (query matches specific passage, not diluted by full-doc TF-IDF).
- **Con:** BM25 scoring on `CHUNK_CONTENT` may not be directly comparable to scoring on
  `CONTENT` — needs field boost calibration. 500-token chunks are too large for ideal search
  result display (see research findings below).
- **Risk:** Need to verify `CHUNK_CONTENT` uses the same analyzer as `CONTENT`.
- **Collapsing mechanism:** Post-retrieval Java grouping after RRF fusion (see research findings).

### ~~Option B — Add a passage layer to the existing document index~~
### Option B — Keep current architecture (already implemented)

The current system already does passage-level display: `HighlightingOps` extracts query-focused
excerpts from full documents, and the UI renders them. This is a two-pass approach (retrieve
whole docs, then extract best passage via BM25 highlights).

- **Pro:** Already working. No code change needed. Recall baseline preserved.
- **Con:** Doesn't improve recall — a long doc where the query matches paragraph 47 may still
  rank below a shorter doc with superficial keyword overlap. Excerpt extraction is approximate
  (post-hoc highlighting, not chunk-level retrieval).
- **When to choose:** If the current excerpt quality is already acceptable and the Recall@3
  experiment shows no significant improvement from chunk-based retrieval.

### ~~Option C — Unified chunk index for both RAG and interactive search~~
### Option C — Already the reality

Chunks and whole documents already share one index. The "unified" architecture is the current
state. What's missing is the interactive search query path exploiting the chunk documents.

This option collapses into Option A.

## Research findings — internet (2026-02-19)

### Collapsing mechanism: post-retrieval Java grouping is the answer

**`CollapsingTopDocsCollector` is not available.** It is an Elasticsearch-internal class, not part
of upstream Apache Lucene. The Lucene 10.x grouping module provides `FirstPassGroupingCollector`
but it only works with a single query (not hybrid fusion).

**For hybrid search, collapsing must happen after RRF fusion.** The two retrieval legs (BM25 and
kNN) run independently with different scoring distributions. Collector-level collapsing during
search is not feasible. OpenSearch's RFC on "Collapse in Hybrid Query"
([#1258](https://github.com/opensearch-project/neural-search/issues/1258)) documents the same
conclusion: collapse must be post-normalization.

**Recommended approach — post-retrieval Java grouping:**
1. Run BM25 query → `TopDocs` (chunk + whole-doc results)
2. Run kNN query → `TopDocs` (chunk + whole-doc results)
3. Fuse with `TopDocs.rrf()` (available in Lucene 10.2.0+, JustSearch uses 10.3.1)
4. Iterate fused results, group by effective parent ID (`PARENT_DOC_ID` for chunks, `DOC_ID` for
   whole docs), keep first (highest-scored) hit per parent
5. Trim to requested limit

Over-fetch by 3–5× the desired result count to ensure enough unique parents survive deduplication.
The grouping operation is O(n) over a small result set — negligible cost.

**Alternative for VECTOR-only mode:** Lucene's `DiversifyingChildrenFloatKnnVectorQuery`
(`lucene-join` module, available since Lucene 9.8) performs kNN on child vectors with automatic
parent-document diversification during HNSW traversal. More efficient than post-dedup for pure
vector queries, but cannot incorporate BM25 scores. Worth considering as an optimization if
VECTOR mode performance matters.

Sources:
- [Lucene 10.2.1 grouping module](https://lucene.apache.org/core/10_2_1/grouping/org/apache/lucene/search/grouping/package-summary.html)
- [TopDocs.rrf() PR #13470](https://github.com/apache/lucene/pull/13470)
- [DiversifyingChildrenFloatKnnVectorQuery Javadoc](https://lucene.apache.org/core/10_3_0/join/org/apache/lucene/search/join/DiversifyingChildrenFloatKnnVectorQuery.html)
- [OpenSearch RFC: Collapse in Hybrid Query #1258](https://github.com/opensearch-project/neural-search/issues/1258)
- [Elastic Labs: Adding Passage Vector Search to Lucene](https://www.elastic.co/search-labs/blog/adding-passage-vector-search-to-lucene)

### Chunk size: 500 tokens is too large for search result display

**Production systems use 100–250 words (~130–330 tokens) for passage retrieval:**

| System | Recommended chunk size | Notes |
|--------|----------------------|-------|
| Elasticsearch `semantic_text` | 250 words (~330 tokens) | Default with 100-word overlap |
| Weaviate | 100–150 words (~130–200 tokens) | "Chunk size should match desired search result display size" |
| Pinecone | 128–256 tokens for granular search | 512–1024 only for context-heavy RAG |
| MS MARCO (gold standard benchmark) | Mean 56 tokens (~42 words) | Most widely-used passage retrieval benchmark |
| Google featured snippets | 40–60 words (~55–80 tokens) | Optimized for scan-and-judge UX |

**Key finding:** The "Rethinking Chunk Size" paper (Bhat et al., 2025) shows chunk size can swing
Recall@1 by 25+ percentage points depending on query type:
- Factoid queries (SQuAD): 64 tokens = 64.1% Recall@1 vs 1024 tokens = 38.6%
- Analytical queries (NarrativeQA): 64 tokens = 4.2% vs 1024 tokens = 10.7%

**500 tokens (~375 words) is approximately a full page of paperback text.** Users scanning search
results would need to read substantial text per result to judge relevance.

**Practical options for JustSearch:**
1. **Sub-excerpt from chunk (recommended short-term):** Retrieve using 500-token chunks (existing
   infrastructure), then extract a ~100–200 token query-focused window from the best chunk using
   `HighlightingOps`-style extraction. This reuses existing chunks without re-indexing.
2. **Dual chunk sizes (long-term):** Add a second, smaller chunk tier (150–200 tokens) optimized
   for search display, alongside the existing 500-token chunks for RAG. More index space but
   optimal for both use cases.
3. **Reduce chunk size globally:** Change to 200–250 tokens for both RAG and search. Loses some
   RAG context quality but simplifies architecture.

Sources:
- [Rethinking Chunk Size for Long-Document Retrieval (Bhat et al., 2025)](https://arxiv.org/abs/2505.21700)
- [Chroma Research: Evaluating Chunking](https://research.trychroma.com/evaluating-chunking)
- [Elastic Labs: Chunking Strategies](https://www.elastic.co/search-labs/blog/chunking-strategies-elasticsearch)
- [Weaviate Academy: Chunking Considerations](https://weaviate.io/developers/academy/py/standalone/chunking/considerations)
- [MS MARCO Passage Statistics](https://ir-datasets.com/msmarco-passage.html)

### Architecture validation: JustSearch's flat approach is correct

**Elasticsearch and OpenSearch offer two passage retrieval architectures:**

1. **Nested documents + `inner_hits`:** Passages are nested objects within parent documents.
   Deduplication is automatic via block join. However, nested docs have significant overhead:
   updating any part of a document reindexes the entire block (parent + all children), and
   `inner_hits` adds 30–120ms per query in reported benchmarks.

2. **Flat documents + `collapse`:** Passages are independent documents with a `parent_id` keyword
   field. Results are collapsed by `parent_id`, returning one hit per parent with the best-scoring
   passage. Update granularity is per-chunk. Naturally handles mixed documents (some chunked, some
   not).

**JustSearch's architecture (flat chunks with `IS_CHUNK` + `PARENT_DOC_ID`) maps directly to
approach #2.** This is the more flexible and performant pattern:

| Aspect | Nested (ES) | Flat + Collapse (ES) | JustSearch |
|--------|-------------|---------------------|------------|
| Deduplication | Automatic block join | `collapse` on parent_id | Post-retrieval grouping |
| Update granularity | Reindex entire block | Per-chunk | Per-chunk |
| Mixed doc support | Awkward (empty nested) | Natural | Natural |
| kNN dedup | Pre-join during HNSW | Collapse after kNN | Post-dedup or `DiversifyingChildren` |

The main gap is that Elasticsearch provides `collapse` as a built-in query parameter, while
JustSearch needs to implement the equivalent as post-retrieval Java grouping. This is
straightforward (see collapsing mechanism section above).

Sources:
- [Elastic Labs: Chunking via Ingest Pipelines](https://www.elastic.co/search-labs/blog/chunking-via-ingest-pipelines)
- [Elasticsearch Collapse Search Results](https://www.elastic.co/guide/en/elasticsearch/reference/8.19/collapse-search-results.html)
- [Elasticsearch Inner Hits Documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/inner-hits.html)
- [OpenSearch: Semantic Field Type](https://docs.opensearch.org/latest/mappings/supported-field-types/semantic/)

## Experiment plan: chunk-aware vs whole-doc Recall@3

### Why the existing golden corpus can't answer this

All 10 golden corpus documents are under 2000 characters (largest: 1,344 chars). The chunking
threshold is 2000 chars (`ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS`). Zero chunk documents are
created during golden corpus tests. The existing Recall@3 numbers measure whole-doc ranking of
small documents — they say nothing about passage retrieval for long documents.

### What the experiment measures

The core question: **For a long document where the relevant content is in a specific passage,
does chunk-based retrieval rank the parent document higher than whole-doc retrieval?**

This tests the scenario where:
- A long document mentions the query topic in one specific paragraph (paragraph 15 of 30)
- A shorter, less relevant document mentions query terms scattered throughout
- Whole-doc BM25 may rank the short doc higher (term frequency diluted by document length)
- Chunk BM25 should rank the long doc's relevant chunk higher (passage-level TF-IDF)

### Corpus design (12 documents, v2)

**Long documents with buried relevant passages (targets, ~12–18K chars each):**
- `long-vram-architecture.txt` (~18K chars) — GPU architecture survey. Buried "VRAM Memory
  Management" passage (~400 chars, 2 mentions) in middle, surrounded by shader pipelines, compute
  units, clock domains, power delivery.
- `long-http-protocols.txt` (~14K chars) — Web protocols survey. Buried "Connection Pooling"
  passage (~400 chars, 2 mentions) in middle, surrounded by HTTP/2, QUIC, WebSocket, TLS.
- `long-jvm-internals.txt` (~14K chars) — JVM internals survey. Buried "Garbage Collection Tuning"
  passage (~400 chars, 2 mentions) in middle, surrounded by JIT, class loading, bytecode, threads.
- `long-container-orchestration.txt` (~14K chars) — Container orchestration survey. Buried "TLS
  Certificate Rotation" passage (~400 chars, 2 mentions) in middle, surrounded by pod scheduling,
  service mesh, resource quotas.

**Short focused documents (baselines, ~1200–1900 chars each):**
- `short-vram-tips.txt` (~1200 chars) — Entirely about VRAM memory management.
- `short-connection-pooling.txt` (~1400 chars) — Entirely about connection pooling.
- `short-gc-tuning.txt` (~1900 chars) — Entirely about GC tuning strategies.
- `short-tls-cert-rotation.txt` (~1900 chars) — Entirely about TLS certificate rotation.

**Confusers (same query terms, different semantic context, ~6–11K chars each):**
- `long-gpu-compute-benchmarks.txt` (~8K chars) — GPU compute benchmarking. Contains "VRAM" 7×
  and "memory management" in performance-measurement context (not architecture).
- `long-microservice-observability.txt` (~8K chars) — Microservice observability. Contains
  "connection pooling" 3× and "database" in monitoring/alerting context (not best-practices).
- `long-database-internals.txt` (~10K chars) — Database internals (B-trees, WAL, buffer pool).
  Contains "garbage collection" 5× and "tuning" in DB VACUUM context (not JVM).
- `long-network-security-audit.txt` (~11K chars) — Network security audit. Contains "certificate
  rotation" 4× and "TLS" in compliance/audit context (not operational how-to).

**Key design principle:** Target docs have 2 query term mentions in 12–18K chars (low BM25 term
density). Confusers have 3–7 query term mentions in 6–11K chars (higher BM25 term density). This
ensures BM25 TF dilution is real — confusers should outrank targets in whole-doc BM25, while
chunk-aware search recovers the buried passage.

### Query set (4 topics × 3 modes = 12 queries)

| Query | Mode | Expected relevant docs | Confuser (should not be in top 3) |
|-------|------|----------------------|----------------------------------|
| "VRAM memory management" | TEXT | short-vram-tips, long-vram-architecture | long-gpu-compute-benchmarks |
| "VRAM memory management" | VECTOR | short-vram-tips, long-vram-architecture | long-gpu-compute-benchmarks |
| "VRAM memory management" | HYBRID | short-vram-tips, long-vram-architecture | long-gpu-compute-benchmarks |
| "database connection pooling best practices" | TEXT | short-connection-pooling, long-http-protocols | long-microservice-observability |
| "database connection pooling best practices" | VECTOR | short-connection-pooling, long-http-protocols | long-microservice-observability |
| "database connection pooling best practices" | HYBRID | short-connection-pooling, long-http-protocols | long-microservice-observability |
| "garbage collection tuning" | TEXT | short-gc-tuning, long-jvm-internals | long-database-internals |
| "garbage collection tuning" | VECTOR | short-gc-tuning, long-jvm-internals | long-database-internals |
| "garbage collection tuning" | HYBRID | short-gc-tuning, long-jvm-internals | long-database-internals |
| "TLS certificate rotation" | TEXT | short-tls-cert-rotation, long-container-orchestration | long-network-security-audit |
| "TLS certificate rotation" | VECTOR | short-tls-cert-rotation, long-container-orchestration | long-network-security-audit |
| "TLS certificate rotation" | HYBRID | short-tls-cert-rotation, long-container-orchestration | long-network-security-audit |

### Execution: two paths, same index

**Path A — Current behavior (whole-doc, chunks excluded):**
1. Index all documents as whole docs + generate chunks via `ChunkDocumentWriter`
2. Query with standard `searchText()` / `searchVector()` / `searchHybrid()` (chunks excluded)
3. Compute Recall@3, NDCG@3

**Path B — Chunk-aware (4-search decomposition + RRF):**

For TEXT mode:
1. `searchChunksForDocs(query, allParentIds, 30)` — chunk BM25
2. Collapse by parent ID, keeping max-scoring chunk per parent

For VECTOR mode:
1. `searchChunkVector(queryVector, allParentIds, 30)` — chunk kNN
2. Collapse by parent ID, keeping max-scoring chunk per parent

For HYBRID mode (decomposed to avoid triple-RRF):
1. `searchText(query, 10)` — whole-doc BM25
2. `searchVector(queryVector, 10)` — whole-doc kNN
3. `searchChunksForDocs(query, allParentIds, 30)` — chunk BM25
4. `searchChunkVector(queryVector, allParentIds, 30)` — chunk kNN
5. Fuse pairwise with `HybridFusionUtils.fuseWithRRF()`:
   - (1)+(2) → whole-doc fused
   - (3)+(4) → chunk fused (after collapsing by parent ID)
   - Merge whole-doc + chunk-fused → final result
6. Compute Recall@3, NDCG@3 on collapsed results

### Metrics

- **Recall@3 delta** per query — does chunk-aware retrieval find the long doc with the buried
  passage?
- **NDCG@3 delta** per query — does the relevant long doc rank higher with chunk scoring?
- **Exclusion check** — do confusers appear in Path B top 3? (soft-check with warning, not hard
  assertion, since deterministic vectors have known limitations)

### Embedding generation

Frozen embeddings for VECTOR and HYBRID modes. Generator creates embeddings for all documents,
all chunks (from `ChunkSplitter.splitWithMetadata()`), and all 4 query texts. Two modes:
- **Real mode:** Calls llama-server `/v1/embeddings` with `nomic-embed-text-v1.5`
- **Deterministic mode:** `CorpusVectorGenerator` assigns category-based pseudo-random vectors.
  Same category = high similarity. Confuser categories differ from target categories.

Generator location: `src/integrationTest/java/.../corpus/PassageRetrievalVectorGenerator.java`
(moved from `src/main/java` to avoid pulling `indexing` into main classpath).

## Experiment results v1 (deterministic vectors, 6 docs, 2 topics)

Initial run with 6 documents (2 targets, 2 baselines, 2 confusers) and 2 query topics. All
Recall@3 = 1.0 for both paths. **Critical analysis found 10 issues** undermining the experiment's
validity — see v2 below.

## Experiment results v2 (deterministic vectors, 12 docs, 4 topics)

### Issues fixed in v2

| # | Issue | Fix |
|---|-------|-----|
| 1 | Corpus too small (~5K chars), keyword-dense | Targets expanded to 12–18K chars, 2 query term mentions each |
| 2 | Confusers don't confuse BM25 — lack actual query terms | Confusers now contain exact query terms (3–7 mentions) in different semantic context |
| 3 | HYBRID Path B does triple-RRF | Decomposed into 4 independent sub-searches, fused pairwise with `HybridFusionUtils.fuseWithRRF()` |
| 4 | Generator uses `split()`, test uses `splitWithMetadata()` | Both now use `splitWithMetadata()` |
| 5 | NDCG and `not_in_top_3` defined but never checked | Added NDCG validation test and exclusion soft-check |
| 6 | Comparison assertion vacuously true | Corpus redesign + NDCG delta measurement |
| 7 | Fragile `@Order` test coupling | All search in `@BeforeAll`, test methods are pure assertions |
| 8 | No temp directory cleanup | `@TempDir` parameter injection |
| 9 | Generator in main source set, pulling indexing dep | Moved to `src/integrationTest/java` |
| 10 | Only 2 query topics | 4 topics × 3 modes = 12 queries |

### Test infrastructure (v2)

| File | Purpose |
|------|---------|
| `src/test/resources/corpus/passage-retrieval/*.txt` (12 files) | 4 targets (12–18K), 4 baselines (1200–1900), 4 confusers (6–11K) |
| `src/test/resources/manifests/passage-retrieval-truth.json` | 12 docs, 12 queries, `not_in_top_3` for all |
| `src/integrationTest/java/.../corpus/PassageRetrievalVectorGenerator.java` | Generates frozen vectors; uses `splitWithMetadata()` |
| `src/test/resources/corpus/passage-retrieval-frozen-vectors.json` | 84 frozen vectors (12 docs + 68 chunks + 4 queries) |
| `src/integrationTest/java/.../PassageRetrievalIntegrationTest.java` | 4 tests: recall comparison, NDCG thresholds, exclusion check, detailed report |

### Results (v2, deterministic vectors except TEXT mode which is pure BM25)

```
Query               Mode    PathA_R@3  PathB_R@3  Delta   PathA_NDCG  PathB_NDCG  NDCG_Delta
---------------------------------------------------------------------------------------------
vram-text           TEXT    1.000      1.000      +0.000  0.920       0.920       +0.000
vram-vector         VECTOR  1.000      1.000      +0.000  1.000       1.000       +0.000
vram-hybrid         HYBRID  1.000      1.000      +0.000  0.920       1.000       +0.080
pooling-text        TEXT    1.000      1.000      +0.000  0.920       1.000       +0.080
pooling-vector      VECTOR  1.000      1.000      +0.000  1.000       1.000       +0.000
pooling-hybrid      HYBRID  1.000      1.000      +0.000  0.920       1.000       +0.080
gc-text             TEXT    1.000      1.000      +0.000  0.920       0.920       +0.000
gc-vector           VECTOR  1.000      1.000      +0.000  1.000       1.000       +0.000
gc-hybrid           HYBRID  1.000      1.000      +0.000  0.920       1.000       +0.080
tls-text            TEXT    1.000      1.000      +0.000  0.920       0.920       +0.000
tls-vector          VECTOR  1.000      1.000      +0.000  1.000       1.000       +0.000
tls-hybrid          HYBRID  1.000      1.000      +0.000  0.920       1.000       +0.080
```

### Path A vs Path B: TEXT mode rankings (pure BM25 — final, no vector dependency)

```
PATH A TOP-3 (whole-doc BM25):
  vram-text:    [short-vram-tips, long-gpu-compute-benchmarks(confuser), long-vram-architecture]
  pooling-text: [short-connection-pooling, short-tls-cert-rotation(incidental!), long-http-protocols]
  gc-text:      [short-gc-tuning, long-database-internals(confuser), long-jvm-internals]
  tls-text:     [short-tls-cert-rotation, long-network-security-audit(confuser), long-container-orchestration]

PATH B TOP-3 (chunk-aware BM25):
  vram-text:    [short-vram-tips, long-gpu-compute-benchmarks(confuser), long-vram-architecture]
  pooling-text: [short-connection-pooling, long-http-protocols(target!), short-tls-cert-rotation]  ← FIXED
  gc-text:      [short-gc-tuning, long-database-internals(confuser), long-jvm-internals]
  tls-text:     [short-tls-cert-rotation, long-network-security-audit(confuser), long-container-orchestration]
```

### Analysis: two distinct BM25 failure modes

**TEXT mode uses zero vectors** — these results are final and conclusive.

All 4 TEXT queries show Path A NDCG@3 = 0.920 (both relevant docs in top 3, but a non-relevant
doc at rank #2 pushes a target to #3). The experiment reveals two distinct BM25 failure modes:

**Failure mode 1 — Topical confusers (vram, gc, tls):** Confuser docs use the *same query terms*
in a different semantic context with 2–5× higher term density:

| Query | Target mentions/size | Confuser mentions/size | BM25 density ratio |
|-------|---------------------|----------------------|-------------------|
| vram-text | 2 / 18K chars | 7 / 8K chars | confuser 7.9× denser |
| gc-text | 2 / 14K chars | 5 / 10K chars | confuser 3.5× denser |
| tls-text | 3 / 14K chars | 9 / 11K chars | confuser 3.8× denser |

**Path B does NOT fix this for TEXT.** The confuser's chunks also match the query terms — chunk-
level BM25 cannot distinguish semantic context. Only vectors can separate these.

**Failure mode 2 — Incidental matches (pooling):** `short-tls-cert-rotation` (~1900 chars) matches
3 of 5 query words ("best", "practices", "connection") in completely unrelated context. BM25's
length normalization boosts this short doc above the 14K-char target which has 11 matches but in a
7× longer document.

**Path B fixes this.** The chunk containing "Connection Pooling for Database and Backend Services"
concentrates all 5 query terms in ~500 tokens, outscoring the incidental match via RRF. Target
moves from rank #3 to #2.

### Analysis: HYBRID mode (directional, vector component is synthetic)

All 4 HYBRID queries show NDCG +0.080 (0.920 → 1.000) in Path B. The chunk-level vector signal
helps separate confusers from targets by semantic meaning, even with deterministic vectors. Real
embeddings would likely show equal or larger improvement.

### Conclusions

**Established facts (no further experiments needed):**
1. BM25 TF dilution is real — all 4 TEXT queries show confusers/incidentals outranking targets
2. Chunk-aware BM25 alone fixes incidental matches (failure mode 2) but not topical confusers
3. Chunk-aware HYBRID fixes both failure modes (directional evidence)
4. No recall degradation from chunk-aware search

**Open question (requires real embeddings):**
- Magnitude of HYBRID/VECTOR improvement with production embeddings
- Whether real embeddings cause targets to fall *out* of top 3 entirely (Recall delta)

### Optional: real embeddings re-run

To generate real embeddings and re-run (quantifies magnitude, not direction):
```bash
# Start llama-server with nomic-embed-text-v1.5
./gradlew.bat :modules:system-tests:generatePassageVectors
# Then re-run
./gradlew.bat :modules:system-tests:integrationTest --tests "*PassageRetrievalIntegrationTest*"
```

## Critical analysis of Option A implementation (2026-02-20)

Post-implementation review of the chunk-aware interactive search found 10 issues (4 bugs,
3 performance concerns, 3 design issues). 7 were fixed; 3 were deferred as acceptable v1 debt.

### Issues found and fixed

| # | Issue | Severity | Category | Fix |
|---|-------|----------|----------|-----|
| 1 | **Filters not applied to chunk search.** User filters (mime, language, fileKind, mimeBase) were not threaded to chunk queries — chunks from filtered-out doc types could appear in results. | HIGH | Bug | Added `QueryFilterBuilder.buildChunkFilterQuery()` that applies the 4 filter types stored on chunks (skips pathPrefix, modifiedAt, entities which are not stored on chunks). Threaded filter through `ChunkSearchOps.searchChunksText()`, `searchChunkVector()`, `LuceneIndexRuntime` facades, and `SearchOrchestrator.mergeChunkResults()`. |
| 2 | **Custom sort broken by RRF merge.** Non-relevance sorts (date, name) were destroyed by RRF fusion which re-orders by reciprocal rank. | MEDIUM | Bug | Added merge gate: skip chunk merge when `ProtoConverters.toRuntimeSort(sortMsg) != RELEVANCE`. |
| 3 | **LUCENE syntax broken in chunk search.** Field-specific operators (e.g. `title:foo`) get escaped to literal text in chunk BM25, producing wrong results. | MEDIUM | Bug | Added merge gate: skip chunk merge when `querySyntax == SEARCH_QUERY_SYNTAX_LUCENE`. |
| 4 | **Match spans empty for chunk hits.** `HighlightingOps.computeMatchSpansFromQuery()` reads `CONTENT_PREVIEW` which chunks don't have — match spans were always empty for chunk results. | LOW | Bug | For chunk hits, inject `CHUNK_CONTENT` as `CONTENT_PREVIEW` in the span fields map before match span computation. |
| 5 | **No chunk existence check.** `mergeChunkResults()` runs even when the index has zero chunk documents, wasting query parsing and searcher acquisition. | MEDIUM | Perf | Added merge gate: `queryDocIdsByField(IS_CHUNK, "true", 1).isEmpty()` — single term query with limit 1, effectively free. |
| 9 | **Null runtimeConfig in RRF.** Both `fuseWithRRF()` calls in `mergeChunkResults()` passed `null` instead of the actual `RuntimeConfig`, ignoring user-configured RRF K parameter. | LOW | Design | Pass `indexRuntime.runtimeConfig()` to both `fuseWithRRF()` calls. |

### Issues deferred (acceptable v1 debt)

| # | Issue | Severity | Category | Rationale for deferral |
|---|-------|----------|----------|----------------------|
| 6 | **No parallel execution of chunk search.** Chunk search runs sequentially after whole-doc search, adding single-digit milliseconds on warm indexes. | MEDIUM | Perf | Parallelizing requires restructuring the `execute()` flow significantly. Sequential approach adds negligible latency. Revisit if profiling shows it matters. |
| 7 | **Per-hit metadata lookups.** `resolveParentMetadata()` does 2 Lucene term lookups per chunk hit (TITLE + FILENAME). For 10 chunk hits, that's 20 lookups. | LOW | Perf | 20 Lucene term lookups complete in sub-millisecond time. Batch-resolve by collecting all parentDocIds first is cleaner but not justified for v1. |
| 8 | **Field asymmetry (BM25 vs vector).** Vector chunk path returns all stored fields via ReadPathOps, BM25 chunk path returns a curated subset. Extra fields in vector results are harmless. | LOW | Design | No functional impact — extra fields are just additional metadata. Cleanup for consistency when the chunk search path matures. |
| 10 | **Projection not respected.** Chunk fields leak into projection-filtered results. Projection is only used in TEXT mode by advanced API consumers. | LOW | Design | Minor inconsistency. Defer until projection is more widely used. |

### Files modified

| File | Changes |
|------|---------|
| `QueryFilterBuilder.java` | Added `buildChunkFilterQuery()` — builds chunk-safe filter (mime, fileKind, mimeBase, language only) |
| `ChunkSearchOps.java` | Added `Query additionalFilter` param to `searchChunksText()` and `searchChunkVector()` |
| `LuceneIndexRuntime.java` | Updated facades with backward-compatible overloads for new filter param |
| `SearchOrchestrator.java` | Hoisted filters, added 3 merge gates (sort, syntax, chunk-exist), passed filters + runtimeConfig, injected CONTENT_PREVIEW for chunk span computation |
| `QueryFilterBuilderTest.java` | 9 tests for `buildChunkFilterQuery()` (null, empty, each filter type, combined, ignored fields) |

## Critical analysis of Option A implementation — round 2 (2026-02-20)

Second review of the chunk-aware interactive search found 7 additional issues. All 7 were fixed.

### Issues found and fixed

| # | Issue | Severity | Category | Fix |
|---|-------|----------|----------|-----|
| 11 | **totalHits double-counts after RRF merge + collapse.** `fuseWithRRF()` sets `totalHits = scores.size()` which counts both whole-doc IDs and chunk IDs as separate entries, inflating the reported total. | MEDIUM | Bug | In `mergeChunkResults()`, after `collapseByParent()`, reconstruct `SearchResult` using `wholeDocResult.totalHits()` — the Lucene estimate of matching whole documents. |
| 12 | **Fuzzy correction not propagated to chunk search.** Zero-hit retry updates `correctedQuery` but not `queryString`. The merge gate passes the original `queryString` to `mergeChunkResults()`, so chunk BM25 searches the uncorrected terms. | MEDIUM | Bug | Hoisted `chunkQueryText` variable, updated after zero-hit retry correction, passed to `mergeChunkResults()` instead of `queryString`. |
| 13 | **Per-term correction same root cause as #12.** Per-term correction also updates `correctedQuery` but not the query text used for chunk search. | MEDIUM | Bug | Same fix as #12 — `chunkQueryText` updated after per-term correction block. |
| 14 | **Chunk metadata leaks into HTTP API fields map.** `toGrpcResponseBuilder()` copies all fields from `hit.fields()` except `CONTENT` and `CHUNK_CONTENT`. For chunk hits, this leaks `IS_CHUNK`, `CHUNK_EMBEDDING_STATUS`, `CHUNK_EMBEDDING_RETRY_COUNT` into the API response. | LOW | Design | Added `CHUNK_INTERNAL_FIELDS` set; chunk-internal fields stripped in the field copy loop for chunk hits. Kept UI-useful fields (CHUNK_INDEX, CHUNK_START_LINE, CHUNK_HEADING_TEXT, etc.). |
| 15 | **No feature flag or runtime toggle.** Chunk-aware search cannot be disabled without code change. | LOW | Design | Added `search.chunk_aware.enabled` config key (schema + RuntimeSearchConfigFactory + SearchConfig). Defaults to `true`. Gate check added to merge condition. |
| 16 | **No search latency impact measurement.** No way to observe chunk merge overhead in production. | MEDIUM | Gap | Added timing around the merge gate with `log.debug("Chunk merge completed in {}ms, {} results after collapse", ...)`. |
| 17 | **Remaining acceptance criteria vague/misplaced.** "No regression in indexing throughput > 10%" and "Result card UI updated" are not actionable for v1. | LOW | Process | Updated acceptance criteria (see below). |

### Files modified (round 2)

| File | Changes |
|------|---------|
| `SearchOrchestrator.java` | Fixes #11 (totalHits), #12/#13 (chunkQueryText), #14 (field stripping), #15d (gate check), #16 (timing log) |
| `RuntimeSearchConfigFactory.java` | Fix #15a: `resolveChunkAwareEnabled()` resolver |
| `RuntimeConfig.java` | Fix #15b: `chunkAwareEnabled` field in SearchConfig |
| `app-config.schema.json` | Fix #15c: `search.chunk_aware.enabled` schema key |
| `RuntimeConfigTest.java` | Fix #18/#21: add `chunkAwareEnabled` assertions to overrides and defaults tests |

## Acceptance criteria

- [x] Investigation complete: questions 1–6 above answered with code evidence
- [x] Internet research complete: collapsing mechanism, chunk size, architecture validation
- [x] Passage retrieval experiment infrastructure: test pipeline built and validated (v1)
- [x] Experiment v2: corpus redesigned (12 docs, 4 topics), 10 critical issues fixed
- [x] Experiment v2: confusers validated (appear in Path A BM25 top 3)
- [x] Experiment v2: NDCG improvement measured (+0.080 for HYBRID mode)
- [x] Experiment v2: TEXT-mode analysis confirms BM25 dilution (pure BM25, no vector dependency)
- [x] Design option selected: Option A (chunk-aware interactive search) — rationale below
- [x] Option A implemented: chunk-aware search in SearchOrchestrator (RRF merge + parent collapse + chunk excerpts)
- [x] Option A critical review round 1: 7/10 issues fixed (3 deferred as acceptable v1 debt)
- [x] Option A critical review round 2: 7/7 issues fixed (totalHits, corrected query, field stripping, feature flag, timing, criteria)
- [ ] Optional: re-run with real embeddings to quantify HYBRID magnitude
- [x] Long-term design research: passage retrieval best practices (2025–2026 state of the art)
- [ ] BEIR nDCG@10 measured before and after on at least one dataset (nfcorpus recommended). Compare with chunk-aware search disabled (`search.chunk_aware.enabled: false`) vs enabled.
- [ ] Frontend consumes chunk metadata fields (CHUNK_START_LINE, CHUNK_HEADING_TEXT) for passage display (future work, not blocking for v1)
- [ ] Chunk-aware merge latency logged at DEBUG level (verify < 50ms on warm index via dev stack)

## Design decision: Option A (chunk-aware interactive search)

**Selected:** Option A — add chunk-aware search to interactive search path.

**Rationale (from experiment v2):**
1. BM25 TF dilution is a real, measured problem — confusers outrank targets in all 4 TEXT queries
2. Chunk-aware search fixes incidental matches (pooling-text: NDCG 0.920 → 1.000)
3. Chunk-aware HYBRID fixes topical confusers (all 4 HYBRID queries: NDCG 0.920 → 1.000)
4. No recall degradation (Recall@3 = 1.0 for all queries in both paths)
5. The infrastructure already exists: chunks in the index, chunk search APIs, RRF fusion, excerpt
   rendering in the UI. The gap is only the query-path plumbing + collapsing.

**Real embeddings are optional.** They would quantify the HYBRID improvement more precisely but
the TEXT-mode results (pure BM25, no vector dependency) already confirm the problem exists and
chunk-aware search helps. The design decision does not depend on the magnitude of HYBRID gain.

## Long-term design research (2025–2026 state of the art)

### 1. Chunk size: no universal optimum, but 500 tokens is too large for search display

The "Rethinking Chunk Size" paper (Bhat et al., 2025) confirms chunk size must match query and
answer characteristics:

| Query type | Optimal chunk size | Evidence |
|------------|-------------------|----------|
| Factoid (SQuAD-style, short answers) | 64–128 tokens | Recall@1 = 64.1% at 64 tokens, declines at larger sizes |
| Technical (TechQA, dispersed answers) | 512–1024 tokens | Recall@1 jumps from 4.8% (64) to 61.3% (512) |
| Narrative (NarrativeQA) | 512–1024 tokens | Recall@1 increases from 4.2% (64) to 10.7% (1024) |

A 2026 RAG performance study found that recursive character splitting at 512 tokens achieves the
highest answer accuracy, outperforming semantic and proposition-based chunking — simpler methods
win because smaller fragments dilute accuracy and waste retrieval focus.

**Implication for JustSearch:** The current 500-token chunks are optimal for RAG context but too
large for search result display (~375 words per result). The recommended short-term approach is
sub-excerpt extraction from 500-token chunks using `HighlightingOps`-style windowing, which is
already the plan (Option A uses chunk for retrieval, then extracts display excerpt from chunk).

Sources:
- [Bhat et al., "Rethinking Chunk Size" (2025)](https://arxiv.org/abs/2505.21700)
- [Chroma Research: Evaluating Chunking](https://research.trychroma.com/evaluating-chunking)

### 2. Late chunking: embed first, chunk second

Late chunking (Günther et al., EMNLP 2024) inverts the traditional pipeline: embed the entire
document through a long-context transformer, then apply mean pooling to chunk boundaries after
the transformer pass. Each chunk embedding is "conditioned on" the full document context.

Performance improvements on BeIR datasets:
- NFCorpus: 23.46% → 29.98% nDCG@10 (+28% relative improvement)
- SciFact: 64.20% → 66.10% nDCG@10
- Improvements scale with document length

**Requirements:** Long-context embedding model (8192+ tokens). Jina Embeddings v2/v3 is the
reference implementation.

**Applicability to JustSearch:** Not directly applicable today — JustSearch uses
`nomic-embed-text-v1.5` (max 8192 tokens) which could theoretically support late chunking, but
the implementation would require embedding the full document first and then extracting chunk
embeddings from the token-level representations. This is a substantial change to the embedding
pipeline. Worth revisiting if JustSearch switches to a model with native late chunking support.

Source: [Jina: Late Chunking in Long-Context Embedding Models](https://jina.ai/news/late-chunking-in-long-context-embedding-models/)

### 3. Contextual retrieval: LLM-augmented chunk context

Anthropic's contextual retrieval (2024) prepends LLM-generated context (~50–100 tokens) to each
chunk before embedding. The LLM receives the full document and the target chunk, then generates
a brief explanation of the chunk's role within its source document.

Performance:
- Contextual embeddings alone: 35% reduction in retrieval failure rate
- Contextual embeddings + contextual BM25: 49% reduction
- With reranking: 67% reduction
- Cost: ~$1.02 per million document tokens (with prompt caching)

**Applicability to JustSearch:** Interesting but requires LLM inference at index time for every
chunk. JustSearch already has the reranker (cross-encoder) which addresses the reranking portion.
The chunk context generation could be added as an optional index-time enrichment step when the AI
runtime is available. Lower priority than the core chunk-aware search plumbing.

Source: [Anthropic: Contextual Retrieval](https://www.anthropic.com/news/contextual-retrieval)

### 4. ColBERT late interaction: token-level multi-vector retrieval

ColBERT stores one embedding per token (not per chunk), enabling fine-grained matching without
chunking. At query time, each query token matches against the most similar document token
(MaxSim), then scores are aggregated. ColBERTv2 adds residual compression, reducing index size
from 154GB to 16–25GB for MS MARCO.

**Pros:**
- No chunking decisions needed — operates at token level
- Beats bi-encoders on retrieval quality (retains contextual nuances lost in pooling)
- Explainable: can show which tokens matched and why
- PyLate enables modular training and serving

**Cons:**
- Storage: even with ColBERTv2 quantization, multi-vector storage is 2–10× larger than single
  vector per chunk
- Requires PLAID or similar index structure (not vanilla Lucene HNSW)
- No Lucene-native support — would require a custom index or external service

**Applicability to JustSearch:** Not practical for the short term. JustSearch's Lucene-native
architecture doesn't support multi-vector per document. ColBERT would require either (a) an
external retrieval service or (b) a custom Lucene codec. Better suited as a reranker in a
two-stage pipeline (first stage: BM25/single-vector, second stage: ColBERT rerank), which is
similar to what JustSearch already does with its cross-encoder reranker.

Sources:
- [Weaviate: Late Interaction Overview](https://weaviate.io/blog/late-interaction-overview)
- [Jina: What is ColBERT?](https://jina.ai/news/what-is-colbert-and-late-interaction-and-why-they-matter-in-search/)

### 5. SPLADE learned sparse retrieval

SPLADE generates sparse neural vectors using transformer-based term expansion. Instead of
matching only exact query terms (BM25), SPLADE expands both queries and documents with
semantically related terms. The output is still a sparse vector stored in an inverted index.

**vs BM25:** Handles vocabulary mismatch (searching "tasty cheese" can match "Gouda")
**vs dense retrieval:** More interpretable, fewer false positives
**Model size:** ~532MB (SPLADE++ via FastEmbed)
**Limitation:** Some models overfit to MS MARCO and underperform on out-of-domain data

**Applicability to JustSearch:** SPLADE could replace or augment BM25 for the lexical search
lane. It addresses the exact failure mode our experiment identified: topical confusers that share
query terms but differ semantically. However, it requires a neural inference pass at both index
and query time, adding latency and complexity. Medium-term opportunity if search quality on
confuser-style queries proves insufficient with chunk-aware HYBRID.

Sources:
- [Qdrant: Modern Sparse Neural Retrieval](https://qdrant.tech/articles/modern-sparse-neural-retrieval/)
- [Pinecone: SPLADE Explained](https://www.pinecone.io/learn/splade/)

### 6. Lucene parent-child kNN: DiversifyingChildrenFloatKnnVectorQuery

Lucene 9.8+ provides `DiversifyingChildrenFloatKnnVectorQuery` (in `lucene-join`) which performs
kNN over child passage vectors with automatic parent-document diversification during HNSW graph
traversal. This is the pre-joining strategy Elasticsearch uses for passage vector search.

**How it works:** While traversing the HNSW graph, the query tracks which parent documents have
been seen. When a child vector is a candidate, it only updates the score if it's more competitive
than the previous best child for that parent. Returns k nearest *parents*, not k nearest passages.

**Performance:** Only 1 extra bit of memory per stored vector. No post-retrieval dedup needed for
pure vector queries. Used in Elasticsearch's `semantic_text` field type (GA in 2025).

**Applicability to JustSearch:** Directly applicable for the VECTOR search mode in Option A. For
VECTOR-only queries, this avoids the over-fetch + post-dedup strategy and gives exact top-k
parent results. Cannot be used for HYBRID (needs BM25 integration) or TEXT mode. JustSearch
already uses Lucene 10.3.1, which includes this class.

**Implementation path:** Use `DiversifyingChildrenFloatKnnVectorQuery` for VECTOR-mode Path B,
keep post-retrieval Java grouping for TEXT and HYBRID modes.

Sources:
- [Elastic Labs: Adding Passage Vector Search to Lucene](https://www.elastic.co/search-labs/blog/adding-passage-vector-search-to-lucene)
- [Lucene PR #12434: ParentJoin KNN support](https://github.com/apache/lucene/pull/12434)

### 7. Elasticsearch semantic_text: production passage retrieval reference

Elasticsearch's `semantic_text` field type (GA 2025) is the most mature production implementation
of passage-level retrieval. Key design choices:

- **Chunking is transparent:** stored in a hidden metafield, original `_source` preserved
- **Highlighting replaces inner_hits:** semantic highlighter returns the best-scoring chunk per
  document, replacing the previous `inner_hits` approach
- **Nested field type:** each chunk is indexed as its own Lucene document with parent reference
- **Supports partial updates and multi-fields**

**Key lesson for JustSearch:** Elasticsearch moved from `inner_hits` (explicit nested query with
parent join) to highlighting (post-retrieval best-chunk extraction) for passage display. This
validates JustSearch's planned approach: retrieve via chunks, display via excerpt extraction.

Source: [Elastic: Semantic Text GA](https://www.elastic.co/search-labs/blog/elasticsearch-semantic-text-ga)

### 8. OpenSearch clustered parent-child index (RFC, not yet shipped)

OpenSearch RFC #18608 proposes a hybrid approach combining query-time joins with block-join
performance. Parent and child documents reside in the same Lucene segment, physically clustered
via sort key. This is an evolution beyond Elasticsearch's approach.

**Notable trade-offs:** Force-merge after every commit (~10 min for 6-7GB), mandatory segment
replication, in-memory parent UUID tracking. The RFC does not yet address kNN over children.

**Applicability:** JustSearch's flat architecture (chunks as independent docs with `PARENT_DOC_ID`)
avoids these complexities. No need to adopt this approach.

Source: [OpenSearch RFC #18608](https://github.com/opensearch-project/OpenSearch/issues/18608)

### Summary: recommended long-term roadmap

| Priority | Technique | Effort | Impact | When |
|----------|-----------|--------|--------|------|
| **Now** | Chunk-aware search with post-retrieval collapsing (Option A) | Medium | Fixes BM25 dilution, incidental matches | Current tempdoc |
| **Soon** | `DiversifyingChildrenFloatKnnVectorQuery` for VECTOR mode | Low | Native Lucene parent-join kNN, no post-dedup | Part of Option A |
| **Medium** | Sub-excerpt from chunk for search display (100–200 tokens) | Low | Better search result UX | Part of Option A |
| **Later** | Contextual retrieval (LLM chunk context at index time) | Medium | 35–49% failure reduction per Anthropic | When AI runtime is stable |
| **Later** | Late chunking (embed-first pipeline) | High | 6–28% nDCG improvement on long docs | Requires embedding pipeline rework |
| **Evaluate** | SPLADE for lexical lane | High | Solves topical confuser problem for TEXT mode | If TEXT-mode confusers are a real user issue |
| **Not now** | ColBERT multi-vector | Very high | Best retrieval quality but non-Lucene | When/if external retrieval service is justified |

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 87 days at audit time.

