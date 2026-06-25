---
title: "306: Pre-Retrieval Query Classification and Field Routing"
type: tempdoc
status: done
created: 2026-03-15
depends-on: [270, 280]
---

> NOTE: Noncanonical working tempdoc. Verify behavioral claims against canonical docs, code, and
> tests before promotion.

# 306: Pre-Retrieval Query Classification and Field Routing

## Purpose

Implement Stage 1 of tempdoc 270's consolidated 8-stage pipeline: rule-based pre-retrieval query
classification into NAVIGATIONAL / INFORMATIONAL / EXACT_MATCH / EXPLORATORY, with query-type-
driven BM25F field weight adjustment and reranker/expansion input.

This is the cheapest stage in 270's design (<1 ms, no model inference) and feeds into two
higher-value downstream decisions:
- Stage 6 (reranking): navigational queries skip CE (saves 60-100 ms)
- Stage 7 (post-reranking): exploratory queries get MMR diversity

## Scope

### In scope
- Rule-based query type classification (<1 ms, no ML)
- BM25F field weight adjustment by query type
- Passing query type to downstream stages (CE gating, expansion, diversity)
- GPL capture of query type for calibration

### Out of scope
- Per-document CE gating (Stage 6 — separate tempdoc)
- MMR diversity (Stage 7 — separate tempdoc)
- SimHash dedup (Stage 3 — separate tempdoc)
- Freshness/recency scoring (Stage 7 — separate tempdoc)
- Thompson Sampling / adaptive refinement (Stage 8)

## Investigation checklist

- [x] Audit current BM25 field configuration — which fields exist, current boost values
- [x] Audit how Lucene queries are constructed — where field weights would be applied
- [x] Audit the query parsing/analysis path — where classification logic should be inserted
- [x] Audit PipelineConfig — can it carry query type downstream?
- [x] Audit expansion path — how query type would gate expansion
- [ ] Research: validate 270's proposed classification rules against real desktop query patterns
- [ ] Research: validate 270's proposed BM25F weight table
- [x] Define concrete implementation plan with verification gates

## Investigation log

### 2026-03-15 — Codebase audit round 1

#### 1. Current BM25 query construction

Text queries go through `TextQueryOps.buildTextQuery()` which uses a single field (`content`)
with `QueryParser`. No multi-field BM25F query is constructed — there's no title boosting,
no path boosting, no metadata field querying in the default search path.

Existing boost constants:
- `EXACT_BOOST = 2.0f` — for exact match vs prefix expansion in `TextQueryOps`
- Autocomplete (`SuggestOps`) uses per-field boosts (title prefix: 4.0x, title wildcard: 2.0x,
  content prefix: 2.0x) — this shows the pattern exists but isn't applied to regular search

Fields available for BM25F but currently unused in search:
- `title` — text, ICU analyzer, stored
- `content` — text, ICU analyzer, stored (currently the ONLY searched field)
- `content_all` — text, ICU analyzer, not stored
- `path` — keyword (not text-analyzed; needs term-level matching)
- `filename` — keyword (same)

**Gap**: Regular search queries only hit `content`. Title, filename, and path are never queried
by BM25. A document whose title exactly matches the query gets no title-match boost.

#### 2. Query processing flow

```
KnowledgeHttpApiAdapter.search(req)           [Head, HTTP]
  → expandPreset(mode, rerankerConfig)         [mode → PipelineConfig]
  → isExpansionEligible(...)                   [gate expansion]
  → startExpansionAsync(queryText)             [async LLM]
  → build SearchRequest proto                  [query + PipelineConfig + filters]
  → client.search(baseReq)                     [gRPC to Worker]
      → GrpcSearchService.search(request)      [Worker, gRPC handler]
        → SearchOrchestrator.execute(request)  [Worker, orchestration]
          → computeQpp(queryString)            [QPP signals: maxIdf, avgIctf, queryScope]
          → route by PipelineConfig flags      [BM25 / Dense / SPLADE legs]
          → fuseWithCC3(...)                   [CC fusion]
          → mergeChunkResults(...)             [chunk CC + collapse]
  → isRerankerEligible(...)                    [Head, CE gating]
  → reranker.rerank(...)                       [Head, LambdaMART → CE cascade]
  → mergeExpansion(queryText, expansionText)   [Head, async expansion merge]
```

**Classification insertion point**: Between `expandPreset()` and `build SearchRequest`. This is
on the Head side, before the gRPC call. The classification result can:
- Modify the `PipelineConfig` (e.g., disable CE for NAVIGATIONAL)
- Adjust BM25F field weights (passed to Worker via a new proto field or PipelineConfig extension)
- Gate expansion (NAVIGATIONAL → skip expansion, saves 1500ms budget)

#### 3. PipelineConfig extensibility

Currently 8 fields (proto message, field numbers 1-8 + field 13 on SearchRequest):
```protobuf
message PipelineConfig {
  bool sparse_enabled = 1;
  bool dense_enabled = 2;
  bool splade_enabled = 3;
  string fusion_algorithm = 4;
  bool lambdamart_enabled = 5;
  bool cross_encoder_enabled = 6;
  int32 cross_encoder_window = 7;
  bool expansion_enabled = 8;
}
```

**Can be extended** with `QueryType query_type = 9` (enum) and/or
`map<string, float> field_boosts = 10`. Proto is additive — new fields are backwards-compatible.

#### 4. Cross-encoder gating

CE is gated by 4 conditions in `isRerankerEligible()`:
1. `pipeline.crossEncoderEnabled()` — pipeline flag
2. `config.enabled()` — global runtime config
3. `resultCount >= minHitsThreshold()` — minimum results
4. `avgContentLengthChars <= maxAvgDocLengthChars()` — corpus-level length check

**Missing**: no per-query gating by query type. A navigational query (user typing a filename)
still runs CE, wasting 60-100ms. Adding `queryType == NAVIGATIONAL → skip CE` is the
highest-value routing decision from tempdoc 270.

#### 5. Expansion gating

Expansion is gated by `isExpansionEligible()`:
- `config.expansionEnabled()` — pipeline flag
- not LUCENE syntax
- query not blank
- not paginated
- AI available

**Missing**: no query-type gating. Expansion on a NAVIGATIONAL query (user searching for
`report.pdf`) is counterproductive — it adds morphological variants of "report" that dilute
precision. Adding `queryType == NAVIGATIONAL → skip expansion` would save the 1500ms budget.

#### 6. BM25F field weight adjustment

No multi-field BM25 query exists. Implementing 270's field weight table requires changing
`TextQueryOps.buildTextQuery()` to construct a `BooleanQuery` across multiple fields with
per-field `BoostQuery` wrapping. The pattern already exists in `SuggestOps` (autocomplete).

270's proposed weights:

| Query type | title | path | metadata | body |
|------------|-------|------|----------|------|
| NAVIGATIONAL | 20 | 15 | 5 | 1 |
| EXACT_MATCH | 15 | 10 | 3 | 1 |
| INFORMATIONAL | 5 | 1 | 2 | 3 |
| EXPLORATORY | 3 | 1 | 2 | 5 |

**Important caveat**: `path` and `filename` are `keyword` fields (not text-analyzed). They can't
be searched with a standard `QueryParser`. They need `TermQuery` or `WildcardQuery` on the raw
keyword value. This means path/filename matching requires different query construction than
title/content matching.

### Working design decisions

1. **Classification runs on the Head, not the Worker.** The Head has the raw query text and
   makes the expansion/CE decisions. The Worker just executes what PipelineConfig says.

2. **Query type is computed once per search, before the gRPC call.** It feeds:
   - CE gating (Head-side `isRerankerEligible`)
   - Expansion gating (Head-side `isExpansionEligible`)
   - BM25F field weights (passed to Worker via proto)
   - Future: MMR lambda (post-reranking diversity)

3. **BM25F is a separate, additive change.** Query classification can ship without BM25F
   (just CE/expansion gating). BM25F requires changes to `TextQueryOps` on the Worker side
   plus a new proto field for field weights. These can be sequenced.

4. **The classification itself is simple.** 270's 8-rule priority list is ~20 lines of Java.
   The complexity is in the plumbing (proto extension, PipelineConfig changes, test updates),
   not in the classification logic.

## Proposed implementation

### Stage A: Query classifier + CE/expansion gating (Head-only)

This stage requires NO proto changes and NO Worker-side changes. It modifies only the Head's
`KnowledgeHttpApiAdapter`.

**Step A1: Add QueryType enum**

New file: `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/QueryType.java`

```java
public enum QueryType {
  NAVIGATIONAL,    // known-item: file extensions, path fragments, rare specific terms
  EXACT_MATCH,     // quoted phrases
  INFORMATIONAL,   // question words, specific multi-term queries (default)
  EXPLORATORY      // broad single-term, high queryScope
}
```

**Step A2: Add QueryClassifier**

New file: `modules/app-services/src/main/java/io/justsearch/app/services/worker/QueryClassifier.java`

Rule-based, <1ms, no model inference. Implements 270's priority rules:

```java
public static QueryType classify(String queryText) {
  if (hasFileExtension(queryText) || hasPathFragment(queryText)) return NAVIGATIONAL;
  if (isQuotedPhrase(queryText)) return EXACT_MATCH;
  if (hasQuestionWord(queryText) && termCount(queryText) > 3) return INFORMATIONAL;
  if (termCount(queryText) <= 2 && looksLikeFilename(queryText)) return NAVIGATIONAL;
  if (termCount(queryText) == 1) return EXPLORATORY;
  return INFORMATIONAL;
}
```

Note: Rules 4-7 from 270 use `maxIdf` and `queryScope` which are computed on the Worker side
(not available on Head). The Head-side classifier uses only syntactic signals. QPP-based
refinement can be added later on the Worker side.

**Step A3: Wire into KnowledgeHttpApiAdapter**

In `doSearch()`, after parsing the query and before `expandPreset()`:

```java
QueryType queryType = QueryClassifier.classify(queryText);
```

Then modify:
- `isExpansionEligible()`: add `&& queryType != NAVIGATIONAL && queryType != EXACT_MATCH`
- `isRerankerEligible()`: add `queryType != NAVIGATIONAL` check (skip CE for known-item)
- Include `queryType` in the search response execution report for diagnostics

**Step A4: Tests**

- Unit tests for `QueryClassifier`: file extensions, quoted phrases, question words, single
  terms, path fragments, edge cases
- Integration test: verify NAVIGATIONAL query skips CE and expansion
- Regression: verify INFORMATIONAL query still gets CE and expansion

### Stage B: BM25F multi-field search (Worker-side, proto extension)

This is the higher-effort change that requires proto modification and Worker-side query
construction changes.

**Step B1: Proto extension**

Add to `indexing.proto`:
```protobuf
message PipelineConfig {
  ...existing fields...
  string query_type = 9;  // "NAVIGATIONAL", "INFORMATIONAL", etc.
}
```

Use `string` not `enum` to avoid proto enum compatibility issues.

**Step B2: Multi-field query construction**

Modify `TextQueryOps.buildTextQuery()` to accept field weights and construct a multi-field
`BooleanQuery`:

```java
Query buildTextQuery(String queryText, RuntimeSearchFilters filters,
    QuerySyntax syntax, Map<String, Float> fieldBoosts) {
  // For each field with boost > 0:
  //   parse query against that field's analyzer
  //   wrap in BoostQuery(query, boost)
  //   add as SHOULD clause to BooleanQuery
  // Apply runtime filters
}
```

Fields to search:
- `content` — standard ICU analyzer (existing)
- `title` — standard ICU analyzer (existing, just not searched)
- `path` + `filename` — keyword fields, need TermQuery/WildcardQuery not QueryParser

**Step B3: Field weight resolution on Worker**

Worker reads `query_type` from PipelineConfig and looks up field weights from 270's table:

| Query type | title | content | path/filename |
|------------|-------|---------|---------------|
| NAVIGATIONAL | 20 | 1 | 15 |
| EXACT_MATCH | 15 | 1 | 10 |
| INFORMATIONAL | 5 | 3 | 1 |
| EXPLORATORY | 3 | 5 | 1 |

**Step B4: Tests**

- Unit tests for multi-field query construction
- Verify title-matching queries get boosted for NAVIGATIONAL
- Verify content-heavy queries still work for INFORMATIONAL
- BEIR regression: run SciFact with classification enabled, verify no regression

## Recommended implementation order

1. **Stage A** first — immediate value (CE/expansion gating), no proto changes, low risk
2. **Stage B** second — requires more plumbing but addresses the "search only queries content"
   gap which is arguably a bigger quality issue than the routing decisions

## Confidence assessment

| Component | Confidence | Rationale |
|-----------|------------|-----------|
| Classification rules | High | 270's rules are well-supported; misclassification cost is low |
| CE gating for NAVIGATIONAL | High | Saves 60-100ms with near-zero quality risk for known-item |
| Expansion gating | High | Expansion on filenames is counterproductive |
| BM25F title boosting | Medium-high | `SuggestOps` already uses the pattern; title matching is universally beneficial |
| BM25F path/filename matching | Medium | Keyword fields need different query construction; regex-like matching may be needed |
| 270's exact weight values | Low | The weights are intuited, not calibrated. Safe defaults exist but optimal values need eval |

## Stage A implementation status — DONE

### Files created
- `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/QueryType.java` — enum
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/QueryClassifier.java` — rule-based classifier
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/QueryClassifierTest.java` — 20 test cases

### Files modified
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeHttpApiAdapter.java` — classification in `doSearch()`, `isExpansionEligible()` and `isRerankerEligible()` gain QueryType parameter, skip reasons added, query type surfaced in `PipelineExecution.components`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeHttpApiAdapterHarmfulCombinationsTest.java` — all `isExpansionEligible` and `isRerankerEligible` calls updated with `QueryType.INFORMATIONAL`

### Live verification (2026-03-15)

| Query | Classification | CE | Expansion |
|-------|---------------|-----|-----------|
| `report.pdf` | NAVIGATIONAL | Skipped (NAVIGATIONAL_QUERY) | Skipped (QUERY_TYPE_NAVIGATIONAL) |
| `how to configure search ranking` | INFORMATIONAL | Executed | Skipped (AI_UNAVAILABLE) |
| `"exact phrase match"` | EXACT_MATCH | Executed | Skipped (QUERY_TYPE_EXACT_MATCH) |
| `optimization` | EXPLORATORY | Executed | Skipped (AI_UNAVAILABLE) |
| `src/main/java` | NAVIGATIONAL | Skipped (NAVIGATIONAL_QUERY) | Skipped |

All classification results visible in `pipelineExecution.components.query_classification` in the
search response. CE and expansion gating working correctly.

### Critical analysis and fixes (2026-03-15)

Four false-positive issues identified and fixed:

1. **File extension substring matching** — `"how to write a .py script"` was classified
   NAVIGATIONAL because `.py` appeared as a substring. Fixed: per-token matching with
   `lastIndexOf('.') > 0` check (requires chars before the dot).

2. **Path separator too broad** — `"pros/cons of SPLADE"` was classified NAVIGATIONAL because
   it contained `/`. Fixed: require 2+ forward slashes in a single token, OR backslash, OR
   `./`/`../` prefix. Single-slash natural language (`and/or`, `pros/cons`) no longer triggers.

3. **CE skip ignored explicit pipeline** — `isRerankerEligible()` skipped CE for NAVIGATIONAL
   queries even when the user provided an explicit `PipelineConfig` with `crossEncoderEnabled:
   true`. Fixed: track `explicitPipeline` flag in `doSearch()`, pass `QueryType.INFORMATIONAL`
   (no gating effect) when pipeline is explicit. Same for expansion gating.

4. **Single-term identifiers classified EXPLORATORY** — `"ResolvedConfigBuilder"` (CamelCase)
   was classified EXPLORATORY. Fixed: added `looksLikeIdentifier()` that detects CamelCase,
   snake_case, and kebab-case-with-digits. Single-term identifiers are now NAVIGATIONAL.

All tests pass (30+ test cases including false-positive regression tests).

## Remaining work

### Stage B: BM25F multi-field search (investigating)

#### Investigation round 2 (2026-03-15): indexing and field analysis

Deep codebase audit of the indexing path revealed:

- **`content` field contains ONLY extracted body text** — not title, not filename, not path.
  Verified at `IndexingDocumentOps.java:131`: `fields.put(SchemaFields.CONTENT, extraction.content())`.
  This confirms the quality gap: title matches produce zero BM25 signal.

- **`content_all` is identical to `content`** — NOT a multi-field concatenation. Both get the
  same `preview` value from `FileIngestor.java:72-73`. The name is misleading; it's a fallback
  for degraded intent queries, not a catch-all.

- **`title` comes from Tika metadata extraction** — not filename. Many file types (plain text,
  code, markdown) produce no Tika title, so the field is often null. Only PDFs, Office docs,
  and HTML with `<title>` tags reliably populate it.

- **`path` and `filename` are keyword fields** — stored as single tokens, not text-analyzed.
  A `QueryParser` query for `"quarterly report"` cannot match
  `path = "C:\Users\docs\quarterly-report.pdf"`. Matching these requires either text-analyzed
  copies at index time, or wildcard/ngram matching at query time.

#### Internet research: industry multi-field BM25 practices

- **Title boost ratios**: Industry standard is 1.5–3x, maximum 10–15x. Tempdoc 270's proposed
  20x is excessively aggressive and risks over-ranking short-titled documents.
- **`DisjunctionMaxQuery` (best_fields)** is the standard for multi-field search in Lucene/ES.
  Uses the maximum score from any field plus a tie-breaker, rather than summing all field scores.
  This prevents short-field bias.
- **Over-boosting risks**: documented failure mode where high boosts on rarely-populated fields
  dominate scoring when they do match. With `title` often null, a 20x boost would massively
  over-rank the minority of documents that have metadata titles.
- **Keyword field partial matching**: WildcardQuery or ngram analysis at index time. Neither
  is simple. Best practice is to create a separate text-analyzed copy of the path/filename.

#### Revised Stage B plan

**Phase B1 (low risk, immediate value)**: Add `title` field to BM25 query with modest 3x boost
using `DisjunctionMaxQuery`. Helps PDFs and Office docs immediately. Files without Tika titles
are unaffected (the title clause simply doesn't match). No proto changes needed — the title
boost is unconditional, not query-type-dependent.

**Phase B2 (medium effort, deferred)**: Index a text-analyzed copy of the filename (e.g.,
`filename_text`) that tokenizes `quarterly-report.pdf` into `[quarterly, report, pdf]`. Query
this with a modest boost (2x). This requires a schema addition + reindex.

**Phase B3 (higher effort, deferred)**: Query-type-dependent field weights via proto extension.
Navigational queries boost title/filename higher; informational queries boost content higher.
Requires `query_type` in `PipelineConfig` proto and Worker-side resolution.

Phases B2 and B3 are deferred.

### Phase B1 implementation status — DONE

**File modified**: `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/TextQueryOps.java`

Changes:
- `buildTextQuery()` now constructs a `DisjunctionMaxQuery` wrapping content (1.0x) + title
  (3.0x boost) queries. Uses best-field scoring with 0.1 tie-breaker (ES `best_fields` pattern).
- New `buildFieldQuery()` helper: parses query against a specific field with the same analyzer.
  Handles both SIMPLE (escaped) and LUCENE (raw) syntax.
- New `combineWithTitle()` helper: creates the `DisjunctionMaxQuery`. Returns content query
  unchanged if title query is null.
- Prefix expansion remains content-only — title gets exact matching to avoid over-matching.
- Constants: `TITLE_BOOST = 3.0f`, `TITLE_TIE_BREAKER = 0.1f`.

Verification:
- All existing `adapters-lucene` tests pass (no regression)
- Live verification on SciFact corpus (plain text, no Tika titles): `DisjunctionMaxQuery`
  gracefully degrades — title clause doesn't match, content score drives ranking unchanged
- Documents with Tika-extracted titles (PDFs, Office docs) will receive title-match boosting
  automatically — no config or reindex required

## Eval gap: current eval cannot validate these features

The BEIR/SciFact eval corpus exercises none of the features implemented in this tempdoc:

1. **No titled documents**: SciFact docs are plain-text files with no Tika-extracted titles.
   The B1 title boost (`DisjunctionMaxQuery` with 3x title weight) is completely invisible
   to SciFact — title clauses never match. We have zero evidence the boost helps or hurts
   on real documents.

2. **No navigational queries**: SciFact queries are scientific claims (e.g., "Vitamin D
   deficiency is associated with...") — all classify as INFORMATIONAL. The CE/expansion gating
   for NAVIGATIONAL and EXACT_MATCH queries is never exercised. We cannot measure latency
   savings or precision impact.

3. **No latency-by-query-type tracking**: `pipelineExecution` reports `crossEncoderMs` only
   when CE runs. There's no infrastructure to compare INFORMATIONAL latency (with CE) vs
   NAVIGATIONAL latency (without CE) across a query set.

### What's needed

- **Titled-document corpus**: Index a set of PDFs or Office docs with known titles. Measure
  whether title-matching queries rank titled documents higher than before.
- **Mixed query type eval set**: Include navigational queries (`report.pdf`, `src/main/java`,
  `ResolvedConfigBuilder`), exact-match queries (`"error message text"`), and informational
  queries alongside each other. Measure latency delta per query type.
- **A/B comparison**: Run the same query set with `TITLE_BOOST = 0` (disabled) and
  `TITLE_BOOST = 3.0` (current). Compare nDCG or MRR for titled documents.

Until this eval exists, the features are **safe** (verified no regression on SciFact) but
**unvalidated** (no positive evidence of improvement). The `TITLE_BOOST` constant and
classifier rules should not be tuned further without eval data.

### Runtime feature flags (implemented)

Both features are togglable at runtime for A/B eval without code changes:

```bash
# Disable classification (full pipeline for all queries):
-Djustsearch.search.query_classification.enabled=false

# Disable title boost (content-only BM25, pre-306 behavior):
-Djustsearch.search.title_boost=0.0
```

Config keys registered in `EnvRegistry`, resolved via `ResolvedConfig.Search`, read from
`ConfigStore` by `KnowledgeHttpApiAdapter` (classification) and `TextQueryOps` (title boost).
Visible in `/api/debug/effective-config`. Overridable via sysprop, env var, or settings.json.

## Deferred work (out of scope)

- **Phase B2**: text-analyzed `filename_text` field — requires schema addition + reindex
- **Phase B3**: query-type-dependent field weights via proto extension
- **Eval extension**: titled-document corpus, mixed query types, latency-by-type tracking, A/B
  comparison infrastructure. Assigned to a separate agent.

## Commits

| Hash | Description |
|------|-------------|
| `714f2b57` | tempdoc opened, investigation started |
| `8d0c4564` | Stage A: query classifier + CE/expansion gating |
| `94dca1ec` | tempdoc updated with Stage A results |
| `492b46d7` | classifier false-positive fixes + Phase B1 title boost + eval gap |
| `a7accd6e` | runtime feature flags for A/B eval |

### Tempdoc 270 remaining stages

| Stage | Status | Next step |
|-------|--------|-----------|
| 1. PRE-RETRIEVAL (query classification) | **Stage A + B1 done** | B2 (filename text field) |
| 2. RETRIEVAL (3-way parallel) | Done (274) | — |
| 3. DEDUP (SimHash) | Not started | New tempdoc |
| 4. FUSION (CC with per-doc weights) | Done (280) | — |
| 5. CHUNK MERGE (CC before MaxP) | Done (280) | — |
| 6. RERANKING (per-doc CE gating) | Not started | Highest-value next |
| 7. POST-RERANKING (freshness + MMR) | Not started | New tempdoc |
| 8. BACKGROUND (calibration) | Partial (GPL enriched) | Alpha sweep |
