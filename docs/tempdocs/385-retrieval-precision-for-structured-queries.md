---
title: "385: Retrieval Precision for Structured Queries"
status: done
created: 2026-04-09
depends_on: [366]
---

# 385: Retrieval Precision for Structured Queries

## Problem

When a query asks about specific articles from specific sources
("Does the TechCrunch article on Twitch's subscription split
suggest..."), the retrieval pipeline returns topically related
content from the right source but often the wrong article. The
consumer (LLM or agent) then answers correctly based on wrong
context — producing a confident, logical, wrong answer.

This is not a model reasoning problem. The model is doing exactly
what it should with the context it receives. The retrieval system
is not precise enough at the article level.

## Evidence

From 2,556-query MultiHop-RAG benchmark (tempdoc 366):

- 86% of failures: model received wrong context and answered
  logically based on it
- 14% of failures: genuinely missing evidence
- Source-level filtering (meta_source) narrows from 609 to
  ~50-100 articles per source — insufficient for top-6 to
  find the right one
- The gap is article-level precision within a source

## Implementation status

### Phase 1: Foundations — DONE

- [x] **#10 — StructuredQueryAnalyzer** (`app-services/.../worker/StructuredQueryAnalyzer.java`)
  - Deterministic source extraction: multi-word substring match + single-word
    context regex (4-branch pattern including "the [Source] article on")
  - `cachedSourceVocabulary` on `KnowledgeHttpApiAdapter` (volatile `Set<String>`,
    populated from `meta_source` facet during `refreshFacetSnapshotIfStale()`)
  - Returns `StructuredQueryAnalysis(detectedSources, topicRemainder)`
  - Integrated in `doSearch()` after `QueryClassifier.classify()`
  - 24 unit tests covering multi-word, single-word context branches,
    false positive rejection, topic remainder stripping

- [x] **#9 — TemporalQueryExtractor** (`app-services/.../worker/TemporalQueryExtractor.java`)
  - Lightweight date regex: `Month DD, YYYY` / `DD Month YYYY` / `YYYY-MM-DD` / `Month YYYY`
  - Returns `TemporalExtraction(dates, suggestedRange)` with ±1 day tolerance
  - Injected as `metaPublishedAt` boost filter when QU doesn't set one;
    standalone boost when no QU boost is active
  - Debug logging when QU temporal range takes precedence
  - 18 unit tests including May disambiguation and invalid date rejection

- [x] **#8 — AnswerTypeClassifier** (`app-services/.../worker/AnswerTypeClassifier.java`)
  - Rule-based `COMPARISON | TEMPORAL | INFERENCE` classification
  - 9 comparison patterns (greedy `does...while` removed after critical analysis)
  - TEMPORAL: 2+ dates from #9; INFERENCE: default fallback
  - Surfaced as `expectedAnswerType` on `KnowledgeSearchResponse.QueryUnderstanding`
  - Schema fixture updated via `updateSchemas`
  - 12 unit tests

- [x] **#3 — Source-aware context attribution** (`worker-services/.../RagContextOps.java`)
  - `DocumentFieldOps` added as constructor dependency (6-param constructor,
    5-param backward-compat overload delegates with null)
  - `fetchParentMetadata()` batch-fetches `meta_source` + `title` via
    `DocumentFieldOps.getDocumentFieldsBatch()` — one call per retrieval
  - `buildContextLabel()`: `"Source — \"Title\""` / `"Source — filename"` / `"filename"`
  - Applied to: chunk budgeting (both token-aware and char-based paths),
    fallback virtual-chunk path
  - `GrpcSearchService` passes `searchLifecycle.documentFieldOps()` to constructor

### Phase 2: Core retrieval precision — DONE

- [x] **#7 — Article-level dedup** (`worker-services/.../RagContextOps.java`, `configuration/`)
  - Per-parent chunk cap in both budgeting loops: `chunksPerParent` counter,
    skip when `>= maxChunksPerArticle`, overflow backfill after primary pass
  - Overflow backfill intentionally bypasses cap (diversity first, then fill)
  - Config: `rag.max_chunks_per_article` (default 2, clamped 1-10) via
    `ConfigKey.RAG_MAX_CHUNKS_PER_ARTICLE`

- [x] **#6 + #1 — Per-source retrieval budget + article-specific retrieval**
  (`app-services/.../worker/KnowledgeHttpApiAdapter.java`)
  - Gate: `answerType == COMPARISON && detectedSources.size() >= 2`
  - `executePerSourceRetrieval()`: N parallel gRPC calls via virtual-thread
    executor (`Executors.newVirtualThreadPerTaskExecutor()`), each with
    `meta_source` hard filter + `topicRemainder` as query text
  - `mergeSearchResponses()`: round-robin interleave, dedup by hit ID,
    backfill from unfiltered query if insufficient, clears stale facets
    and correctedQuery from template
  - Single-source: inject detected source as `meta_source` boost (no decomposition)

### Phase 3: Quality signals — NOT STARTED

- [ ] **#4 — Completeness signal**
- [ ] **#5 — Null query detection (source-conditional)**

### Phase 4: Completeness — NOT STARTED

- [ ] **#2 — Multi-article comparison retrieval** (integration testing)
- [ ] **#11 — Chunk boundary awareness** (adjacent expansion)

## Known issues and deferred items

### Open: `doSearch()` god method

The adapter's `doSearch()` is ~350 lines of sequential orchestration
with 8+ local variables set in one place and consumed 100+ lines later.
The 3 new pre-retrieval calls (#10, #9, #8) made it worse. Needs
decomposition into a pre-retrieval pipeline object.

**Theoretical solution:** Extract a `PreRetrievalContext` value object
with a factory `PreRetrievalContext.analyze(queryText, vocabulary)` that
runs all extractors internally and returns `sources()`, `dates()`,
`answerType()`, `topicRemainder()`. Decompose `doSearch()` into 4
explicit phases: `analyze()` → `buildRequest(ctx)` →
`executeRetrieval(ctx)` → `assembleResponse()`, each 50-80 lines with
a clear contract. The phases are private methods sharing the context
object. Future Phase 3/4 signals add fields to `PreRetrievalContext`
without growing `doSearch()`.

**Confidence: 85%.** Data dependencies map cleanly to 4 phases. The
hard coupling is `resp` mutation in the expansion+reranking phase
(conditionally replaced, then results mutated in-place by LambdaMART
and cross-encoder). Requires a `RerankedResult` record to decouple.
~15 tracking booleans flow into the response builder — fiddly plumbing.

### Resolved: `topicRemainder` stripping — eliminated per research

Per-source retrieval now uses the **full original query** with
`meta_source` hard filter, not the stripped topic remainder. Validated
by FeB4RAG (SIGIR 2024) — federated search works best with the full
query. BM25 is robust to source name tokens; the filter does the work.
The `topicRemainder` field still exists on `StructuredQueryAnalysis`
but is no longer consumed by per-source retrieval.

### Resolved: per-source interleave confirmed end-to-end

Diagnostic validation (April 2026) confirmed the full pipeline works:
- Source detection: `[techcrunch, fortune]` from vocabulary (49 entries)
- Per-source gate: fires on `detectedSources.size() >= 2` (relaxed
  from COMPARISON-only per FeB4RAG research)
- Parallel retrieval: 2 gRPC calls, 10 hits each (balanced budget)
- Round-robin merge: `techcrunch: 3, fortune: 2` in top-6 results

Earlier test failures (5+1 distribution) were caused by running a
stale JVM distribution that didn't include the per-source code.
After `installDist` rebuild, the feature works correctly.

Added 5th regex branch for `[CapWord] reported/said/noted that` to
catch "Reuters reported that..." patterns.

### Future improvement: embedding-based re-ranking for merge

FeB4RAG (SIGIR 2024) and RAGRoute (2025) show that per-source result
merging works best with **embedding-based re-ranking** across all
per-source results, not round-robin interleaving. Re-ranking by
embedding similarity to the query produces a globally optimal ranking.
Round-robin guarantees representation but doesn't optimize relevance.
The query embedding is already computed for hybrid search. Fall back
to round-robin when embeddings are unavailable.

### Open: `executePerSourceRetrieval` is untested

The `mergeSearchResponses` static method has 14 integration tests.
The calling method `executePerSourceRetrieval` (parallel gRPC,
timeout, fallback-on-all-failures) is private and untested beyond
the dev server smoke test.

**Theoretical solution:** Extract the gRPC interaction behind
`Function<SearchRequest, SearchResponse>` (same pattern as
`mergeSearchResponses` already uses for backfill). The method becomes
a static or package-private function that takes a search function, a
list of sources, topic remainder, and limit. Test suite covers:
successful 2-source parallel execution, one source empty, one source
timeout, all sources fail (fallback fires), topic remainder used as
sub-query text.

**Confidence: 90%.** Pattern already proven in `mergeSearchResponses`.
Direct extension. Only uncertainty: testing parallel execution
correctness (thread starvation) requires load testing, not unit tests.

### Open: fallback path lacks per-parent cap

`buildFallbackWithVirtualChunks` (L824+) uses `ContextBudgeter`
directly without `runBudgetLoop`. It has source-aware labels but
NOT the article diversity cap. The inconsistency is minor (fallback
is rare, full-doc search returns 1 entry per doc by definition), but
the code paths diverge.

**Theoretical solution:** Make `runBudgetLoop` generic over the hit
type via a functional interface for field extraction (`getContent`,
`getParentDocId`). It becomes a reusable algorithm that doesn't know
about `SearchHit` vs `VirtualChunk` — it just needs a content string,
a parent ID, and a label. The fallback path uses the same loop with
a `VirtualChunk` adapter.

**Confidence: 80%.** Generic `<T>` with two extractor lambdas is
straightforward. Limitation: the fallback path has post-loop proto
construction that differs from the chunk path — the loop extraction
helps but doesn't unify everything.

### Partially resolved: `EXTRA_KNOWN_SOURCES` single-word entries

Branch 5 (`[CapWord] reported/said/noted/claimed/found/stated that`)
now catches "Reuters reported that...", "Bloomberg noted that...", etc.
Remaining gap: bare mentions without a reporting verb ("Reuters and
Bloomberg both covered...") still require the existing context regex
branches (1-4), which may not match all phrasings.

**Future improvement:** Aho-Corasick trie-based multi-pattern matching
(Java library: robert-bor/aho-corasick) would replace the two-phase
substring+regex approach with a single O(n) scan handling both
multi-word and single-word sources uniformly. Context validation
remains a post-filter on matched candidates.

### Deferred: retrieve-context doesn't surface expectedAnswerType (#14)

The answer type is only available on the search endpoint. The
retrieve-context path builds a raw `Map<String, Object>` response
and doesn't use `QueryUnderstanding`. Low value (eval uses search
endpoint), high plumbing cost (proto + ContextResult + controller).

### Accepted: single-word context regex requires initial capital (#8)

The `[A-Z][A-Za-z]+` pattern in the single-word context regex won't
match all-lowercase queries ("article from techcrunch"). This is
intentional — the capital requirement prevents common words from
matching as source names. Multi-word sources are case-insensitive.

### Accepted: sourceInKnownSet substring match (#1)

Substring/prefix matching (`ks.contains(src)`) could theoretically
false-positive on very short source names. Mitigated by a `src.length()
< 3` guard. Real publication names are 4+ chars.

## Files changed

### New files
| File | Purpose |
|------|---------|
| `app-services/.../worker/StructuredQueryAnalyzer.java` | Source extraction (#10) |
| `app-services/.../worker/TemporalQueryExtractor.java` | Date extraction (#9) |
| `app-services/.../worker/AnswerTypeClassifier.java` | Answer type classification (#8) |
| `app-services/...test.../StructuredQueryAnalyzerTest.java` | 24 tests |
| `app-services/...test.../TemporalQueryExtractorTest.java` | 18 tests |
| `app-services/...test.../AnswerTypeClassifierTest.java` | 12 tests |

### Modified files
| File | Changes |
|------|---------|
| `KnowledgeHttpApiAdapter.java` | Vocabulary cache, 3 pre-retrieval calls, temporal boost, per-source retrieval, merge, virtual-thread executor |
| `KnowledgeSearchResponse.java` | `expectedAnswerType` on `QueryUnderstanding` |
| `RagContextOps.java` | `DocumentFieldOps` dep, batch metadata lookup, `buildContextLabel()`, per-parent cap + overflow |
| `GrpcSearchService.java` | Pass `documentFieldOps` to `RagContextOps` |
| `ResolvedConfig.java` | `maxChunksPerArticle` on `Rag` record |
| `ResolvedConfigBuilder.java` | Wire `rag.max_chunks_per_article` |
| `ConfigKey.java` | `RAG_MAX_CHUNKS_PER_ARTICLE` |
| `StatusRecordSchemaTest.java` | `queryUnderstanding` sample with `expectedAnswerType` |
| `search-response-live.json` | Regenerated fixture |

## Eval verification

```bash
cd scripts/jseval
python -m jseval tier2-eval --dataset multihop-rag --source-check --json
```

| Metric | Baseline (Llama 8B) | Baseline (Qwen 9B) |
|--------|---------------------|---------------------|
| Overall accuracy | 61% | 77% |
| Comparison accuracy | 42% | 67% |
| Null accuracy | 48% | 100% |
| Temporal accuracy | 50% | 73% |
| Inference accuracy | 94% | 87% |

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 39 days at audit time.

