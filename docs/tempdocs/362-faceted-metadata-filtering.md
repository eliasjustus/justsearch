---
title: "362: Faceted Metadata Filtering"
status: done
created: 2026-03-27
---

# 362: Faceted Metadata Filtering

## Problem

Documents have structured metadata (YAML frontmatter, PDF
properties, file path) but JustSearch treats it all as body text.
Agents and users cannot filter search results by metadata fields
like source, author, date, or category.

In the agent retrieval eval (tempdoc 346), 14 out of 20 agent
reflections requested metadata filtering as their primary pain
point. Agents had to keyword-search for publication names like
"The Verge" and manually inspect each result's frontmatter,
wasting turns and tokens. A structured filter like
`source:"The Verge"` would have been trivial.

## Implementation summary

Quick reference. See full sections below for details and rationale.

| Step | Description | Key files | Status |
|------|-------------|-----------|--------|
| 1 | SSOT field catalog + SchemaFields constants | `fields.v1.json`, `SchemaFields.java` | **Done** |
| 2 | ExtractionResult expansion + frontmatter parsing | `StructuredContentExtractor.java`, `ContentExtractor.java`, `LanguageUtils.java` | **Done** |
| 3 | Index metadata fields + FieldMapper LongPoint + normalization | `IndexingDocumentOps.java`, `FieldMapper.java` | **Done** |
| 4 | Proto fields + RuntimeSearchFilters + QueryFilterBuilder | `indexing.proto`, `LuceneRuntimeTypes.java`, `ProtoConverters.java`, `QueryFilterBuilder.java` | **Done** |
| 5 | Fix RagContextOps chunk filtering (pre-existing bug) | `RagContextOps.java`, `ChunkSearchOps.java` | **Done** |
| 6 | REST API plumbing + RetrieveContextRequest proto | `KnowledgeSearchRequest.java`, `KnowledgeHttpApiAdapter.java`, `RetrieveContextController.java`, `RetrieveContextParams.java`, `SearchRpcOps.java`, `RemoteDocumentService.java`, `KnowledgeSearchController.java`, `SearchTool.java`, `indexing.proto`, `RagContextOps.java` | **Done** |
| 7 | MCP tool schemas + tool descriptions | `schemas.mjs`, `server.mjs` | **Done** |
| 8 | UI facet rendering | `SearchFiltersBar.tsx` | Deferred |
| 9 | Eval | `metadata_eval.py` | **Done** — all 4 phases complete |

**38 files, ~1400 lines changed.** All unit tests pass. Merged to `main`.

### Post-implementation fixes applied
- Timezone fix: `parsePublishedAt()` uses `ZoneOffset.UTC` (not system default)
- Circular dependency: `extractFrontmatterMetadata()` moved from `StructuredContentExtractor` to `LanguageUtils`
- Empty filter response: `buildEmptyFilterResponse()` routes through `ChunkContextResult` + `buildChunkResponse()`
- SSOT manifest: `repro.v1.json` regenerated with updated field catalog hash
- MCP tool descriptions: both `justsearch_search` and `justsearch_retrieve_context` descriptions updated to mention metadata/entity filtering
- Hybrid chunk-safe filters: both `searchChunksHybrid` overloads now accept `Query additionalFilter`, threaded through all BM25 and kNN sub-calls
- Integration tests: 2 new tests in `GrpcSearchServiceRetrieveContextTest` (metadata filter scopes to parents, empty filter returns empty)
- Test catalog: `FieldCatalogDef.forChunkTesting()` updated with 4 metadata field definitions
- Unit tests: `LanguageUtilsTest` (5), `IndexingDocumentOpsTest` (5), `QueryFilterBuilderTest` (3) — 15 new tests total

### Live eval fix
During live eval, discovered that `RemoteDocumentService.preSearchForDocIds()`
was not forwarding metadata filter fields to the search proto. This caused
retrieve-context with metadata filters to return FILTERED_EMPTY when
no doc_ids were pre-supplied. Fixed in commit `000ed4334`.

### Eval results

| Phase | Result | Key finding |
|-------|--------|-------------|
| 1: Ground truth | **PASSED** | All facet counts match ground truth. Filter precision 100% for all tested sources. Case insensitivity verified. Cross-filter intersection correct. |
| 2: Entity regression | **PASSED** | Entity + metadata filters work in retrieve-context. Two-stage pre-filter routes correctly. Combined filters (Taylor Swift + entertainment) work. |
| 3: MCP-style | **PASSED** | Snake_case keys work on both paths. Date range has boundary semantics note (before = start of day). |
| 4: Agent eval (Haiku) | **+3.9pp** | 78.9% accuracy (v3 descriptions) vs 75.0% baseline. Filter adoption inconsistent. |
| 4b: Agent eval (Opus) | **Filters adopted** | 80% accuracy, 5.6 avg turns. 3/5 queries used meta_source. Q5 failed on result verification (now fixed). |
| Manual proof of concept | **100%** | 5/5 queries correct in 1-2 API calls with metadata filters. Infrastructure ceiling confirmed. |

### Remaining items

| Item | Severity | Notes |
|------|----------|-------|
| `server.mjs` ISO date parsing | Low | Backend controllers handle ISO-to-epoch conversion. Functionally correct, tech debt only. |
| Date range boundary semantics | Low | `before: "2023-10-31"` means start of day, excluding Oct 31 articles (234 vs 244). Correct behavior, but could confuse users. Documented, not a bug. |
| UI (Step 8) | Deferred | Frontend facet rendering. |
| Query intent decomposition | Forward ref | See tempdoc 363 — LLM-based query decomposition to auto-extract filters from natural language. |

### Agent filter adoption analysis

The Phase 4 agent eval showed that **the infrastructure works
but agent adoption depends on tool description quality and model
capability.**

**Final state (v3 descriptions, Haiku 20q):** 78.9% accuracy
(+3.9pp over 75.0% baseline), $0.081 avg cost, 13.6 avg turns.
Filter adoption inconsistent — some queries used `meta_source`,
most did not. Multiple reflections still complained "No metadata
filtering" despite the feature being available.

**Progression across tool description iterations:**

| Version | Tool description | Accuracy |
|---------|-----------------|----------|
| v1 | Generic mention of metadata filtering | 75.0% (20q, = baseline) |
| v2 | Concrete example in tool description | 100% (5q sample) |
| v3 | Example + IMPORTANT directive in parameter description | **78.9%** (20q) |

The improvement is real but concentrated on source-specific
queries. Multi-hop reasoning queries don't benefit from filters.
Haiku's filter adoption is non-deterministic across runs.

### Opus vs Haiku: isolating intelligence from system design

Ran 5-query eval with Opus (Condition C, v3 tool descriptions)
to test whether filter adoption is a model capability issue:

| Metric | Haiku v3 (5q) | **Opus v3 (5q)** |
|--------|---------------|-------------------|
| Accuracy | 100% | **80%** |
| Avg turns | 8.8 | **5.6** |
| Avg cost | $0.062 | $0.220 |
| Filter adoption | inconsistent | **3/5 queries used meta_source** |

**Finding: Opus discovers and uses metadata filters.** Q1
reflection: *"I could filter by `meta_source`"*. Q3: 3 turns
with `retrieve_context` + filter. Q4: just 2 turns.

Opus's Q5 failure is instructive: it used `meta_source:
["cbssports.com"]` correctly but then believed it "got back
articles tagged as sporting news." The filter was applied
correctly (verified: `meta_source: ["cbssports.com"]` returns
exactly 16 CBSSports docs). But the agent had no way to confirm
this — **the search response doesn't include `meta_source` in
per-result fields**. The agent sees `path: article_072.md` and
a content preview about betting, but nothing confirming "this
result is from cbssports.com." It can't distinguish cbssports.com
from sporting news results without reading each article's
frontmatter, which defeats the purpose of filtering.

**Finding: metadata fields were missing from MCP search response.**
The Java stack passes all stored fields through transparently
(no allowlist). The blocker was `slimSearchResult()` in
`server.mjs` — an explicit allowlist that only included
`filename`, `path`, `file_kind`, `size_bytes`, `language`,
`content_preview`. **Fixed in `fceffdda5`**: added
`meta_source`, `meta_author`, `meta_category` to the allowlist.
Agents now see document provenance per result without reading
full documents.

**Conclusion:** The filter adoption problem is primarily
**model capability**, not system design. Opus discovers filters
from the tool description; Haiku doesn't reliably do so. The
v3 tool description (with examples + IMPORTANT directive) is
sufficient for Opus but insufficient for Haiku. For Haiku
adoption, proactive facet surfacing or system prompt injection
would be needed.

### Manual filter usage: proof of concept

To quantify the infrastructure ceiling, the same 5 eval queries
were answered manually using metadata filters via the REST API:

| Query | Sources | Answer | API calls | Agent turns |
|-------|---------|--------|-----------|-------------|
| SBF fraud (Verge+TC) | `meta_source:["the verge","techcrunch"]` | Sam Bankman-Fried | **1** | 9-13 |
| Trump apartment (Fortune+Age) | `meta_source:["fortune","the age"]` | Donald Trump | **1** | 6-8 |
| Altman OpenAI (Fortune+TC) | `meta_source:["fortune","techcrunch"]` | Sam Altman | **1** | 4-7 |
| Revenue increase (TC+HN) | `meta_source:["techcrunch"]` then `["hacker news"]` | Yes | **2** | 6-10 |
| Caesars betting (CBS+SN) | `meta_source:["cbssports.com","sporting news"]` | Caesars Sportsbook | **1** | **FAILED** (15-23) |

Every query answered correctly in 1-2 API calls with metadata
filters. The agent failed Q5 (Caesars) because it didn't use
`meta_source` and spent 15-23 turns keyword-searching for
"CBSSports" in article content. The infrastructure ceiling is
100% accuracy with ~1 call per query. The gap is entirely in
agent tool discovery.

**Improvement levers (status):**
1. **Dynamic enum** — populate available filter values from facet
   counts in the tool schema or first search response. *Open.*
2. **Stronger model** — *Tested.* Opus discovers filters from the
   same descriptions Haiku ignores (3/5 queries used meta_source).
3. **Metadata in response** — *Done (`fceffdda5`).* MCP search
   results now include `meta_source`/`meta_author`/`meta_category`
   per hit. Agents can verify filters without reading full docs.
4. **Query intent decomposition** — *Scoped in tempdoc 363.* LLM
   preprocessing to auto-extract filters from natural language.
   Removes need for agents to learn filter syntax.

### Additional files touched during implementation
- `SearchOrchestrator.java` — entity filter expansion preserves new metadata fields
- `RemoteDocumentService.java` — forwards metadata fields in doc-ID pre-search
- `KnowledgeSearchController.java` — parses entity + metadata from REST body (supports both camelCase and snake_case for MCP compatibility)
- `SearchTool.java` — agent tool Filters construction updated
- `FieldCatalogDef.java` — test catalog (`forChunkTesting`) includes metadata fields
- `ChunkSearchIntegrationTest.java` — `searchChunksHybrid` call updated with new filter param
- `FacetingIntegrationTest.java`, `HybridSearchIntegrationTest.java`, `TextSearchIntegrationTest.java`, `QueryFilterBuilderTest.java` — RuntimeSearchFilters constructor updated
- `GrpcSearchServiceRetrieveContextTest.java` — 2 new metadata filter integration tests
- `LanguageUtilsTest.java`, `IndexingDocumentOpsTest.java` (new) — unit tests for extraction and date parsing
- `metadata_eval.py` (new) — Phase 1 ground truth + verification script for metadata filtering eval

Post-merge fixes:
- `RemoteDocumentService.java` — `preSearchForDocIds()` now forwards metadata filter fields to search proto (commit `000ed4334`)
- `server.mjs` — `slimSearchResult()` now includes `meta_source`/`meta_author`/`meta_category` in MCP response (commit `fceffdda5`)
- `schemas.mjs` — filter parameter descriptions updated with concrete examples and IMPORTANT directive for agent adoption

### Dependency graph and parallelization

```
Track A (extraction):    1 → 2 → 3 ─────┐
                                         ├→ 6 → 7 → 9
Track B (filter plumb):  1 → 4 ──────┐  │
                                      ├──┘
Track C (bug fix):       5 ───────────┘

Step 8 (UI) branches off Step 4 independently.
```

Steps 1-3 and Steps 4-5 can proceed in parallel (merging at
Step 6). Step 5 is independent of all other steps — it fixes
pre-existing entity filter behavior in retrieve-context.

### Verification checkpoints

| After | Verify | Result |
|-------|--------|--------|
| Steps 1-3 | Compilation + module unit tests | **Pass** |
| Step 4 | All affected module tests. QueryFilterBuilder: metadata TermQuery + IndexOrDocValuesQuery. | **Pass** |
| Step 5 | Compilation + unit tests. Two-stage pre-filter in `executeRetrieval()`. | **Pass** |
| Steps 6-7 | Full compilation. All unit tests pass. | **Pass** |
| Fixes | Timezone (UTC), circular dep (LanguageUtils), empty response, SSOT manifest | **Pass** |
| Gap fixes | Tool descriptions, hybrid chunk filters, integration tests, test catalog | **Pass** |
| Step 9 | End-to-end eval with running backend + MultiHop-RAG corpus | **Passed** — all 4 phases |

## Existing infrastructure

The entity facet pipeline already solves this problem for
NER-extracted entities. The same pattern applies here:

```
NER extracts entities
  → stored as entity_*_raw keyword fields (FieldMapper)
  → FacetingEngine computes facet counts (DocValues)
  → SearchOrchestrator applies filter clauses (TermQuery)
  → SearchFiltersBar renders in UI
```

For metadata filtering, replace "NER extracts entities" with
"frontmatter parser extracts metadata fields" and the rest of
the pipeline is identical.

This architecture matches what the literature identifies as
best-in-class: dual-representation storage per filterable field
(inverted index for selective lookups + column-oriented DocValues
for faceting/sorting), with filter clauses pushed down into the
query plan as non-scoring `FILTER` clauses. This is the same
pattern Elasticsearch, Solr, and Tantivy use. Hearst's foundational
work on faceted search (Flamenco project, UC Berkeley) established
that faceted navigation turns search from a *recall* task (users
must remember filter values) into a *recognition* task (users see
and select values) — which is exactly what FacetingEngine + the
UI pill buttons provide.

Key existing components:
- `FacetingEngine` — DocValues-based facet counting (single-valued
  via `SortedDocValues`, multi-valued via `SortedSetDocValues`)
- `FieldMapper` — keyword fields with `roles: ["filter"]` get a
  `StringField` (inverted index); `docValues: true` gets DocValues.
  Multi-valued keywords use `SortedSetDocValuesField`; single-valued
  use `SortedDocValuesField`.
- `QueryFilterBuilder` — `addTermOrFilter()` builds OR-disjunctions
  of `TermQuery`, added as `FILTER` clauses (no scoring)
- `SearchOrchestrator` — routes `RuntimeSearchFilters` to
  `QueryFilterBuilder` for all retrieval modes. For VECTOR and
  HYBRID modes, `buildFilterQueryOnly()` is passed directly as
  the 4th argument to `KnnFloatVectorQuery(field, vec, k, filter)`
  — Lucene's native pre-filter API that restricts candidates
  during HNSW graph traversal. There is no post-filtering.
- `SearchFiltersBar` — renders facet pill buttons in the UI,
  with coverage gating (NER ≥ 10% before showing entity facets)
- SSOT field catalog (`fields.v1.json`) — declares all indexed fields

Important: the existing `author` field (`SchemaFields.AUTHOR`) is
type `text` with ICU analyzer, no DocValues, and no roles. It
cannot be used for filtering or faceting. This is the same pattern
as entities: `entity_persons_text` (text, ICU) exists alongside
`entity_persons_raw` (keyword, DocValues, filter+facet). Metadata
needs the parallel keyword field `meta_author`.

Unlike entity filters, metadata fields do not need cluster
expansion — there are no synonym variants to canonicalize.
However, metadata values DO need case normalization (see below).

## Metadata sources

Confirmed fields from the MultiHop-RAG corpus (609 articles,
`tmp-multihop-corpus/`):

```yaml
---
title: "200+ of the best deals from Amazon's Cyber Monday sale"
author: "Stan Choe"
source: "Mashable"
category: "entertainment"
published_at: "2023-11-27 08:45:59"
---
```

All 609 articles have identical frontmatter structure. ~40 distinct
sources, 6 categories (sports, technology, entertainment, business,
science, health). Date format is consistent: `"YYYY-MM-DD HH:MM:SS"`.
Author is sometimes `"None"` (treat as absent).

Future file types (PDF properties via Tika `dc:creator`/
`dcterms:created`, email headers) can reuse the same downstream
pipeline but are out of scope for Phase 1.

## Extraction boundary: ExtractionResult

`ExtractionResult` is the handoff between Tika extraction and
document indexing. It is currently a 4-field record:

```java
// ContentExtractor.java:209
public record ExtractionResult(
    String content, String title, String mimeType, String author) {}
```

The Tika `Metadata` object is populated in `extract()` (line 109)
but discarded after extracting title/author/mime (lines 114–122).
There is no mechanism to carry `source`, `category`, or
`published_at` downstream to `IndexingDocumentOps.buildDocument()`.

### Solution

Add a `Map<String, String> frontmatterMetadata` field:

```java
public record ExtractionResult(
    String content, String title, String mimeType, String author,
    Map<String, String> frontmatterMetadata) {

    // Backward-compatible constructors
    public ExtractionResult(String content, String title, String mimeType, String author) {
        this(content, title, mimeType, author, Map.of());
    }
    public ExtractionResult(String content, String title, String mimeType) {
        this(content, title, mimeType, null, Map.of());
    }
}
```

Only the markdown extraction path populates this map. PDF/email
metadata can be added later using the same field.

**File:** `modules/worker-services/.../extract/ContentExtractor.java`

## Extraction: frontmatter parsing

Tika does NOT parse YAML frontmatter in markdown files — it
includes the frontmatter verbatim as body text. JustSearch already
has frontmatter parsing infrastructure from tempdoc 146:

1. `LanguageUtils.stripFrontmatter(String content)` (line 123) →
   `FrontmatterResult(String body, String rawFrontmatter)`.
   Currently called only from `contentPreview()`.

2. `LanguageUtils.extractYamlValue(String[] lines, String key)`
   (line 334, private) — general-purpose YAML value extractor.
   Handles inline values, quoted values (`"..."`, `'...'`), and
   block scalars (`|`, `>`, `|-`, `>-`). Guards against prefix
   matches on longer keys and rejects indented (nested) keys.
   Works for any top-level YAML key — just needs to be exposed.

3. `ContentExtractor.extractFrontmatterTitle(String content)`
   (line 177) — simpler inline title extractor, less robust than
   `extractYamlValue` (no `\r\n` handling, no `...` close, no
   block scalars). Not recommended for extension.

### Extraction plan

**Important:** The active production extractor is
`StructuredContentExtractor`, not `ContentExtractor`. Markdown
files go through `extractStructured()` unconditionally — there is
no MIME-type gate. `ContentExtractor.extract()` only runs on
fallback failure. Both extractors already have a frontmatter title
fallback with the `content.startsWith("---")` guard.

The frontmatter metadata parsing must go in **both** extractors
(for consistency and fallback correctness). The primary location
is `StructuredContentExtractor.extractStructured()`, after line 134
where `content` is available:

```java
// After existing title extraction (StructuredContentExtractor line 141)...
Map<String, String> frontmatterMeta = Map.of();
if (content != null && content.startsWith("---")) {
    FrontmatterResult fm = LanguageUtils.stripFrontmatter(content);
    if (fm.rawFrontmatter() != null) {
        String[] lines = fm.rawFrontmatter().split("\\R");
        Map<String, String> meta = new HashMap<>();
        putIfPresent(meta, "source",       LanguageUtils.extractYamlValue(lines, "source"));
        putIfPresent(meta, "category",     LanguageUtils.extractYamlValue(lines, "category"));
        putIfPresent(meta, "published_at", LanguageUtils.extractYamlValue(lines, "published_at"));
        // Frontmatter author as fallback (skip "None")
        String fmAuthor = LanguageUtils.extractYamlValue(lines, "author");
        if (fmAuthor != null && !"None".equalsIgnoreCase(fmAuthor)) {
            putIfPresent(meta, "author", fmAuthor);
        }
        frontmatterMeta = Map.copyOf(meta);
    }
}
return new ExtractionResult(content, title, mimeType, author, frontmatterMeta);
```

The same logic goes into `ContentExtractor.extract()` (line 119)
as the fallback path.

Required change: make `LanguageUtils.extractYamlValue()` package-
private (both classes are in the same package under `worker-services`),
or add a public wrapper method. No YAML parsing library needed —
`extractYamlValue` already handles all formats in the corpus.

Alternative considered: CommonMark's `YamlFrontMatterExtension`
(`org.commonmark:commonmark-ext-yaml-front-matter`) — JustSearch
already depends on `commonmark-java` for markdown stripping. The
extension provides structured frontmatter parsing via
`YamlFrontMatterVisitor`. However, `extractYamlValue` handles all
corpus formats and avoids adding a dependency. Revisit if
frontmatter complexity exceeds what `extractYamlValue` handles
(e.g., nested YAML objects, flow sequences).

Author precedence: Tika `dc:creator` (set for emails/Office docs)
wins over frontmatter `author`. Both populate `ExtractionResult.author`.
Additionally, `frontmatterMetadata.get("author")` feeds `meta_author`
(keyword field) for exact-match filtering, regardless of which source
provided the text `author` field.

**Files:**
- `modules/worker-services/.../extract/StructuredContentExtractor.java` (primary path)
- `modules/worker-services/.../extract/ContentExtractor.java` (fallback path)
- `modules/worker-services/.../services/LanguageUtils.java`

## Schema design

New SSOT field catalog entries in `SSOT/catalogs/fields.v1.json`:

```json
{ "id": "meta_source",       "type": "keyword", "stored": true, "docValues": true, "roles": ["filter", "facet"] },
{ "id": "meta_author",       "type": "keyword", "stored": true, "docValues": true, "roles": ["filter", "facet"] },
{ "id": "meta_category",     "type": "keyword", "stored": true, "docValues": true, "roles": ["filter", "facet"] },
{ "id": "meta_published_at", "type": "long",    "stored": true, "docValues": true, "roles": ["filter", "sort"] }
```

Design decisions:
- `meta_` prefix distinguishes from content fields (`title`,
  `author`) and entity fields (`entity_*_raw`)
- `meta_source`, `meta_author`, `meta_category`: single-valued
  keyword with DocValues → `SortedDocValuesField` + `StringField`
  (via FieldMapper keyword branch, `filter` role)
- `meta_published_at`: long (epoch millis) → dual-indexed as
  `LongPoint` (BKD-tree range queries) + `NumericDocValuesField`
  (sorting/faceting). See "Range query performance" below.
  Parsing `"2023-11-27 08:45:59"` → epoch millis requires a
  `DateTimeFormatter` in `buildDocument()`.
- No `meta_tags` field — the MultiHop-RAG corpus has no tags

### Range query performance

The existing `modified_at` range filter uses
`NumericDocValuesField.newSlowRangeQuery()` — a linear scan over
all matching documents. Lucene's BKD-tree (`LongPoint`) is orders
of magnitude faster for range filtering (3.5x speedup in Lucene
10.2 via SIMD vectorization). Best practice is dual indexing:

```java
// At index time: both LongPoint and NumericDocValuesField
doc.add(new LongPoint(field, epochMs));
doc.add(new NumericDocValuesField(field, epochMs));

// At query time: IndexOrDocValuesQuery picks the faster path
Query q = new IndexOrDocValuesQuery(
    LongPoint.newRangeQuery(field, from, to),
    NumericDocValuesField.newSlowRangeQuery(field, from, to));
```

`IndexOrDocValuesQuery` dynamically chooses at query time: BKD-tree
when the range query drives iteration (lead), DocValues when used
for random-access verification inside a conjunction.

This affects `meta_published_at` (new) and should also be applied
to the existing `modified_at` filter as a drive-by fix. Requires
a FieldMapper change: the `long` branch must add `LongPoint`
alongside `NumericDocValuesField` for fields with `filter` role.
`QueryFilterBuilder` should wrap range filters in
`IndexOrDocValuesQuery` instead of using `newSlowRangeQuery` alone.

### Date parsing

The MultiHop-RAG corpus uses a consistent format:
`"2023-11-27 08:45:59"`. Use a single `DateTimeFormatter`:

```java
private static final DateTimeFormatter PUBLISHED_AT_FMT =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
```

For future robustness (user-authored frontmatter with varied
formats), stack optional patterns via `DateTimeFormatterBuilder`:

```java
private static final DateTimeFormatter FLEXIBLE_DATE_FMT =
    new DateTimeFormatterBuilder()
        .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        .appendOptional(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .appendOptional(DateTimeFormatter.ISO_LOCAL_DATE)
        .toFormatter();
```

No external library needed — `java.time.format` handles all cases.
Use `parseBest(input, LocalDateTime::from, LocalDate::from)` when
the input may or may not include a time component.

### Value normalization

Metadata keyword values are **lowercased at index time** using
`toLowerCase(Locale.ROOT)`. Filter values are lowercased at query
time (in `ProtoConverters` or `QueryFilterBuilder`).

Rationale:
- `StringField` is case-sensitive. Without normalization, a filter
  for `"the verge"` won't match indexed `"The Verge"`.
- Entity fields deliberately preserve case because NER surface
  forms are semantically significant and `EntityClusterSnapshot`
  handles variant expansion. Metadata has no equivalent —
  "Technology" and "technology" are the same category.
- `mime_base` already sets precedent: it is the only existing
  keyword field with explicit `toLowerCase(Locale.ROOT)` at index
  time (`IndexingDocumentOps.normalizeMimeBase()`).
- Most other keyword fields (`file_kind`, `language`) are lowercase
  by construction (hardcoded literal strings). `collection` and
  entity fields have no normalization and are case-sensitive.

Implementation:
- **Index time:** In `buildDocument()`, apply
  `value.toLowerCase(Locale.ROOT)` to `META_SOURCE`,
  `META_AUTHOR`, `META_CATEGORY` before `fields.put()`.
- **Query time:** In `ProtoConverters.toRuntimeFilters()`, apply
  `.toLowerCase(Locale.ROOT)` to each value in the `metaSource`,
  `metaAuthor`, `metaCategory` lists. This ensures filter values
  from REST/gRPC/MCP are normalized before becoming `TermQuery`
  terms.
- **Facet display:** Facet counts will show lowercased values
  (e.g., "the verge" not "The Verge"). For display-quality labels,
  the UI or MCP response can capitalize the first letter of each
  word — but the indexed value is canonical lowercase.

This does NOT solve deeper normalization (name variants like
"S. Choe" vs "Stan Choe", or aliases like "theverge.com" vs
"The Verge"). That requires a canonicalization layer similar to
`EntityClusterSnapshot`, which is out of scope for Phase 1.

Add constants to `SchemaFields.java`:
```java
public static final String META_SOURCE       = "meta_source";
public static final String META_AUTHOR       = "meta_author";
public static final String META_CATEGORY     = "meta_category";
public static final String META_PUBLISHED_AT = "meta_published_at";
```

Add all four to `INDEXABLE_FIELDS`. Regenerate fingerprints.
Update pinned-hash tests in `modules/ssot-tools`.

**Files:**
- `SSOT/catalogs/fields.v1.json`
- `modules/indexing/.../SchemaFields.java`

## Indexing

In `IndexingDocumentOps.buildDocument()` (line 107), after the
existing author/title writes (~line 147), read from
`extraction.frontmatterMetadata()`:

```java
Map<String, String> fmMeta = extraction.frontmatterMetadata();
if (!fmMeta.isEmpty()) {
    putIfNonBlank(fields, SchemaFields.META_SOURCE,   fmMeta.get("source"));
    putIfNonBlank(fields, SchemaFields.META_CATEGORY,  fmMeta.get("category"));
    // meta_author: use frontmatter author for keyword field, even if
    // ExtractionResult.author came from Tika dc:creator
    putIfNonBlank(fields, SchemaFields.META_AUTHOR,    fmMeta.get("author"));
    String publishedAt = fmMeta.get("published_at");
    if (publishedAt != null) {
        Long epochMs = parsePublishedAt(publishedAt); // "YYYY-MM-DD HH:MM:SS" → epoch ms
        if (epochMs != null) {
            fields.put(SchemaFields.META_PUBLISHED_AT, epochMs);
        }
    }
}
```

Keyword fields need no FieldMapper changes — the keyword branch
(line 194) already adds `StringField` + `SortedDocValuesField` for
single-valued keywords with `filter` role and `docValues: true`.

The `long` branch in FieldMapper needs a change: add `LongPoint`
alongside `NumericDocValuesField` for fields with `filter` role,
enabling fast BKD-tree range queries. This benefits both the new
`meta_published_at` and the existing `modified_at` field.

**File:** `modules/worker-services/.../loop/ops/IndexingDocumentOps.java`

## Proto design: typed fields

Add to `SearchFilters` in `indexing.proto` (following the entity
filter pattern of typed repeated string fields):

```protobuf
message SearchFilters {
  // ... existing fields 1-10 ...
  repeated string meta_source = 11;
  repeated string meta_author = 12;
  repeated string meta_category = 13;
  TimeRangeMs meta_published_at = 14;
}
```

Rationale for typed fields over generic `MetadataFilter { field, value }`:
the entire existing filter pipeline uses typed fields.
`RuntimeSearchFilters` is a Java record with named fields.
`ProtoConverters.toRuntimeFilters()` does field-by-field mapping.
`QueryFilterBuilder.applyRuntimeFilters()` calls `addTermOrFilter()`
with specific `SchemaFields` constants. Going generic would require
reworking all three components plus the UI for no benefit at 4 fields.

Extend `RuntimeSearchFilters` (`LuceneRuntimeTypes.java:85`):

```java
public record RuntimeSearchFilters(
    // ... existing 11 fields ...
    List<String> metaSource,
    List<String> metaAuthor,
    List<String> metaCategory,
    Long metaPublishedFromMs,
    Long metaPublishedToMs) {}
```

Extend `ProtoConverters.toRuntimeFilters()` — add 4 field mappings
(3 lists + 1 TimeRangeMs → 2 longs), identical to existing pattern.

Extend `QueryFilterBuilder.applyRuntimeFilters()` — add:
```java
addTermOrFilter(qb, filters.metaSource(),   SchemaFields.META_SOURCE);
addTermOrFilter(qb, filters.metaAuthor(),   SchemaFields.META_AUTHOR);
addTermOrFilter(qb, filters.metaCategory(), SchemaFields.META_CATEGORY);
// Range filter for meta_published_at — IndexOrDocValuesQuery for optimal performance
if (filters.metaPublishedFromMs() != null || filters.metaPublishedToMs() != null) {
    long from = filters.metaPublishedFromMs() != null ? filters.metaPublishedFromMs() : Long.MIN_VALUE;
    long to   = filters.metaPublishedToMs()   != null ? filters.metaPublishedToMs()   : Long.MAX_VALUE;
    qb.add(new IndexOrDocValuesQuery(
        LongPoint.newRangeQuery(SchemaFields.META_PUBLISHED_AT, from, to),
        NumericDocValuesField.newSlowRangeQuery(SchemaFields.META_PUBLISHED_AT, from, to)), FILTER);
}
```

Also add to `buildFilterQueryOnly()` (vector/hybrid path) and
skip in `buildChunkFilterQuery()` (metadata not stored on chunks).

**Files:**
- `modules/ipc-common/.../proto/indexing.proto`
- `modules/adapters-lucene/.../runtime/LuceneRuntimeTypes.java`
- `modules/worker-services/.../util/ProtoConverters.java`
- `modules/adapters-lucene/.../runtime/QueryFilterBuilder.java`

## Chunk filtering: pre-existing bug and design decision

### Pre-existing bug in RagContextOps

Investigation revealed that entity filters in the retrieve-context
path are **silently broken today**. The failure chain:

1. `RagContextOps.buildRagFilters()` (line 626) builds
   `RuntimeSearchFilters` with entity lists and `includeChunks=true`.
2. `searchChunksWithMeta()` (line 446) passes filters to
   `QueryFilterBuilder.buildFilterQueryOnly(ragFilters)`.
3. `buildFilterQueryOnly()` emits entity FILTER clauses
   (`entity_persons_raw`, etc.) targeting chunk documents.
4. Chunk documents don't have entity fields → zero matches.
5. Fallback fires → `buildFallbackWithVirtualChunks()` returns
   unfiltered full-text context.

Entity filters in retrieve-context silently degrade to unfiltered
retrieval. The correct method `buildChunkFilterQuery()` (which
skips entity/path/date filters) exists in `QueryFilterBuilder`
but is never called from `RagContextOps`.

Additionally, the hybrid branch in `searchChunksWithMeta()` (lines
431-445) never passes `ragFilters` to `searchChunksHybrid()` at
all — filters are silently dropped in hybrid mode.

### Design decision: two-stage retrieval for document-level filters

Metadata filters (like entity filters) are indexed on parent
documents, not chunks. They cannot be applied directly to chunk
queries. The correct architecture is two-stage:

**Stage 1 — Document-level pre-filter:** Query parent documents
using entity + metadata filters (via `buildFilterQueryOnly()` with
`includeChunks=false`). Collect matching parent doc IDs.

**Stage 2 — Chunk retrieval:** Search chunks scoped to the
matching parent doc IDs. Use `buildChunkFilterQuery()` for
chunk-safe filters only (mime, fileKind, mimeBase, language).
Entity and metadata filters are NOT applied to chunks.

This fixes the pre-existing entity filter bug AND correctly
handles metadata filters. The implementation:

- `RagContextOps.searchChunksWithMeta()` — before the chunk
  search, run a parent-doc ID query if entity or metadata filters
  are present. Add the resulting doc IDs to the `docIds` set.
- `buildChunkFilterQuery()` — confirm metadata filters are
  excluded (same as entity filters).
- Hybrid branch — pass chunk-safe filters to
  `searchChunksHybrid()` or adopt the same two-stage pattern.

This is a bigger change than originally scoped but it fixes a
real bug in entity filtering and prevents the same bug for
metadata filtering. Without it, metadata filters in retrieve-
context would silently return unfiltered results.

**Files:**
- `modules/worker-services/.../services/RagContextOps.java`
- `modules/adapters-lucene/.../runtime/QueryFilterBuilder.java`

## API surface: fixing the filter asymmetry

### Current state

The search and retrieve-context REST paths have asymmetric filter
coverage:

| Filter field | Search path | Retrieve-context path |
|---|---|---|
| mime, language, mimeBase | yes | **no** |
| fileKind, pathPrefix | yes | yes |
| modifiedAt | yes | yes |
| entity_persons/orgs/locs | **no** | yes |
| includeChunks | yes | n/a |
| metadata (new) | no | no |

Entity filters exist in the proto `SearchFilters` (fields 8-10)
and in `RuntimeSearchFilters`, but `KnowledgeSearchRequest.Filters`
does not have entity fields, so they cannot be passed through the
search REST API. The `justsearch_search` MCP tool schema is
`.strict()` and also lacks entity filter fields.

### Fix: converge both paths

Add to **both** paths: entity filters + metadata filters.

**Search path** (`POST /api/knowledge/search`):

1. `KnowledgeSearchRequest.Filters` (in `KnowledgeSearchRequest.java`)
   — add `entityPersons`, `entityOrganizations`, `entityLocations`,
   `metaSource`, `metaAuthor`, `metaCategory`, `metaPublishedAt`
   (as `TimeRangeMs`).
2. `KnowledgeHttpApiAdapter` (line 424) — wire new fields to proto
   `SearchFilters` builder (fields 8-14).

**Retrieve-context path** (`POST /api/knowledge/retrieve-context`):

1. `RetrieveContextController` (line 63) — add parsing for
   `meta_source`, `meta_author`, `meta_category`,
   `meta_published_after`, `meta_published_before`.
2. `RetrieveContextParams` — add metadata filter fields.
3. `SearchRpcOps.retrieveContext()` (line 235) — wire metadata
   fields to `RetrieveContextRequest` proto builder.

**Files:**
- `modules/app-api/.../knowledge/KnowledgeSearchRequest.java`
- `modules/app-services/.../worker/KnowledgeHttpApiAdapter.java`
- `modules/ui/.../api/RetrieveContextController.java`
- `modules/app-api/.../RetrieveContextParams.java`
- `modules/app-services/.../worker/SearchRpcOps.java`

## Agent discoverability

### Design principles

Anthropic's guidance on writing effective tools for AI agents
establishes that tool definitions are effectively prompts — the
same effort invested in HCI should go into ACI (agent-computer
interfaces). Key principles applied here:

- **Use `enum` for categorical filters** when the value set is
  known and bounded. For `meta_category` (6 values), enumeration
  in the schema is practical. For `meta_source` (~40 values) and
  `meta_author` (hundreds), `string[]` with descriptive examples
  in the parameter description is better.
- **Rich parameter descriptions** — a field described as `"Filter
  by source publication (e.g., 'The Verge', 'AP News'). Omit to
  search all sources."` performs materially better than `"source
  filter"`. Research confirms description quality directly affects
  parameter generation accuracy.
- **Keep tool surface area small** — a single `search` tool with
  optional filter parameters, not separate tools per filter type.
  Setups with <20 arguments per tool are well within model
  capabilities.
- **Include examples** in tool descriptions for format-sensitive
  parameters (ISO dates, array syntax).

### MCP tool schemas

`scripts/prod/justsearch-mcp/schemas.mjs`:

**SearchInputSchema** — add to the `filters` object (currently
`.strict()` with only pathPrefix, mime, fileKind, language):
```
entity_persons:        string[] (optional)
entity_organizations:  string[] (optional)
entity_locations:      string[] (optional)
meta_source:           string[] (optional)
meta_author:           string[] (optional)
meta_category:         string[] (optional)  // enum if corpus is fixed
meta_published_after:  string   (optional, ISO 8601)
meta_published_before: string   (optional, ISO 8601)
```

**RetrieveContextInputSchema** — already has entity filters. Add:
```
meta_source:           string[] (optional)
meta_author:           string[] (optional)
meta_category:         string[] (optional)
meta_published_after:  string   (optional, ISO 8601)
meta_published_before: string   (optional, ISO 8601)
```

**server.mjs** — passes `input.filters` verbatim to the backend.
ISO 8601 date parsing for `meta_published_after`/
`meta_published_before` is handled by the backend controllers
(`KnowledgeSearchController.parseMetaPublishedAt()` and
`RetrieveContextController`) rather than in server.mjs. This
works but splits the conversion responsibility across layers.

### Tool descriptions

**Done.** Both tool-level descriptions in `server.mjs` updated:
- `justsearch_search`: mentions entity names, document metadata
  (source, author, category, publication date) as filter options
- `justsearch_retrieve_context`: mentions entity names, metadata,
  path prefix, file kind, modification date as filter options
Parameter-level descriptions in `schemas.mjs` include examples
(e.g., `"Filter by source publication (e.g. ['the verge',
'techcrunch']). Values are case-insensitive."`).

### Facet discovery

FacetingEngine automatically computes facets for any field with
`roles: ["facet"]` when requested. Agents discover available filter
values by including `facets: { include: true, fields: [{ field:
"meta_source", size: 50 }] }` in their search request. The facet
counts in the response tell the agent what values exist and how
common they are. This follows the universal pattern across
Meilisearch (`facetDistribution`), Typesense (`facet_by`),
Elasticsearch (`terms` aggregation), and Qdrant (`/facet` endpoint)
— all provide facet count discovery alongside search results.

**Files:**
- `scripts/prod/justsearch-mcp/schemas.mjs` — **Done** (entity + metadata filters added to both schemas)
- `scripts/prod/justsearch-mcp/server.mjs` — **Done** (tool descriptions updated)

## UI integration (deferred)

Add metadata facets to `SearchFiltersBar.tsx` following the
`ENTITY_FACETS` pattern (line 13):

```ts
const META_FACETS = [
  { field: "meta_source",   label: "Source",   filterKey: "metaSource" },
  { field: "meta_author",   label: "Author",   filterKey: "metaAuthor" },
  { field: "meta_category", label: "Category", filterKey: "metaCategory" },
] as const;
```

Render as pill buttons with facet counts. Date range filter
(`meta_published_at`) needs a date picker component — defer to
a follow-up if needed.

**File:** `modules/ui-web/src/components/search/SearchFiltersBar.tsx`

## Implementation order

See the implementation summary table and dependency graph at the
top of this document for the quick reference. Steps 1-3 (Track A:
extraction pipeline) and Steps 4-5 (Track B+C: filter plumbing +
bug fix) can proceed in parallel, merging at Step 6. Verify at
each checkpoint before proceeding to dependent steps.

### Step 1: SSOT + schema constants
- `SSOT/catalogs/fields.v1.json` — add 4 field entries
- `modules/indexing/.../SchemaFields.java` — add 4 constants, add to `INDEXABLE_FIELDS`
- Regenerate fingerprints, update pinned-hash tests

### Step 2: Extraction boundary
- `ContentExtractor.java` — add `Map<String, String> frontmatterMetadata` to `ExtractionResult`, backward-compatible constructors
- `LanguageUtils.java` — make `extractYamlValue()` package-private
- `StructuredContentExtractor.extractStructured()` — add frontmatter metadata parsing after line 134 (primary extraction path for all file types including markdown)
- `ContentExtractor.extract()` — same frontmatter parsing after line 119 (fallback path)
- Tests: verify frontmatter metadata extraction for source, category, published_at, author

### Step 3: Indexing + FieldMapper + normalization
- `IndexingDocumentOps.buildDocument()` — read `frontmatterMetadata`, write to `META_SOURCE`, `META_AUTHOR`, `META_CATEGORY`, `META_PUBLISHED_AT` fields. Apply `toLowerCase(Locale.ROOT)` to keyword metadata values before `fields.put()`.
- Add `parsePublishedAt()` helper for date string → epoch millis using `DateTimeFormatter`
- `FieldMapper.java` — the `long` branch currently adds only `NumericDocValuesField` + optional `StoredField` with NO role-based branching. Add `LongPoint` for fields with `filter` role. This affects 17 existing long fields (including `modified_at`, `size_bytes`, `indexed_at`, `chunk_*` fields). All benefit from BKD-tree range queries; index size increase is minor (one BKD tree entry per long value per doc).

### Step 4: Proto + filter plumbing
- `indexing.proto` — add fields 11-14 to `SearchFilters`
- `LuceneRuntimeTypes.java` — extend `RuntimeSearchFilters` (5 new fields)
- `ProtoConverters.java` — map new proto fields. Apply `toLowerCase(Locale.ROOT)` to `metaSource`, `metaAuthor`, `metaCategory` values for case-insensitive matching.
- `QueryFilterBuilder.java` — add `addTermOrFilter` calls for keyword fields + `IndexOrDocValuesQuery` for date range. Also migrate existing `modified_at` range filter from `newSlowRangeQuery` to `IndexOrDocValuesQuery`. Confirm metadata filters are excluded from `buildChunkFilterQuery()`.
- Tests: ProtoConverters, QueryFilterBuilder

### Step 5: Fix RagContextOps chunk filtering (pre-existing bug)
- **Done:** `RagContextOps.executeRetrieval()` — two-stage pre-filter: `hasDocLevelFilters()` check → `findMatchingParentDocIds()` → intersect with caller docIds → pass `effectiveDocIds` to all branches (BM25, hybrid, fallback).
- **Done:** `RagContextOps.searchChunksWithMeta()` BM25 branch — replaced `buildFilterQueryOnly(ragFilters)` with `buildChunkFilterQuery(ragFilters)` for chunk-safe filters only.
- **Done:** `RagContextOps.buildRagFilters()` — metadata filter fields wired from proto with lowercasing.
- **Done:** `ChunkSearchOps.findMatchingParentDocIds()` — new method for Stage 1 parent-doc ID query.
- **Done:** Hybrid branch — both `searchChunksHybrid` overloads now accept `Query additionalFilter`. Chunk-safe filters are threaded through all BM25 and kNN sub-calls. `RagContextOps` builds `chunkFilter` once and passes to all three branches.
- **Done:** Integration tests — 2 new tests in `GrpcSearchServiceRetrieveContextTest` (metadata filter scopes to parents, empty filter returns empty). `FieldCatalogDef.forChunkTesting()` updated with metadata field definitions.

### Step 6: REST API plumbing + retrieve-context proto
- `KnowledgeSearchRequest.java` — add entity + metadata fields to `Filters`
- `KnowledgeHttpApiAdapter.java` — wire to proto builder
- `RetrieveContextController.java` — add metadata filter parsing
- `RetrieveContextParams.java` — add metadata fields
- `SearchRpcOps.java` — wire metadata to `RetrieveContextRequest`
- `indexing.proto` — add metadata fields to `RetrieveContextRequest` (fields 19-22: `repeated string meta_source = 19`, `repeated string meta_author = 20`, `repeated string meta_category = 21`, `TimeRangeMs meta_published_at = 22`). Note: this is a SEPARATE proto message from `SearchFilters` — entity filters are flat fields 10-12, not a nested message.
- `RagContextOps.buildRagFilters()` — add metadata filter fields to `RuntimeSearchFilters` construction (this is the worker-side converter for retrieve-context, distinct from `ProtoConverters.toRuntimeFilters()`)

### Step 7: MCP tool schemas + descriptions
- **Done:** `schemas.mjs` — entity + metadata filters added to `SearchInputSchema` and `RetrieveContextInputSchema` with descriptive parameter descriptions and examples
- **Done:** `server.mjs` — both tool descriptions updated to mention metadata/entity filtering

### Step 8: UI (deferred)
- `SearchFiltersBar.tsx` — metadata facet pills, date picker. Deferred.

### Step 9: Eval
- See eval plan below. Requires running backend.

## Eval plan

### Phase 1: Ground truth verification (automated, no agent)

Ingest MultiHop-RAG corpus (609 articles) and verify metadata
extraction correctness against ground truth.

**Ingestion:** Start the eval backend, then add the corpus as a
watched folder via REST:
```bash
# Terminal 1: start backend
./gradlew.bat :modules:ui:runHeadlessEval

# Terminal 2: add corpus folder + wait for ingestion
curl -s -X POST http://127.0.0.1:33221/api/indexing/roots \
  -H "Content-Type: application/json" \
  -d '{"path": "D:/code/JustSearch/tmp-multihop-corpus"}'
# Monitor /api/status until all enrichments complete
```

Note: jseval's main pipeline only supports BEIR datasets.
MultiHop-RAG uses `agent_retrieval_eval.py` (Phase 4).

**Facet count verification:**
```json
POST /api/knowledge/search
{
  "query": "*",
  "limit": 1,
  "facets": {
    "include": true,
    "fields": [
      { "field": "meta_source", "size": 50 },
      { "field": "meta_category", "size": 10 },
      { "field": "meta_author", "size": 200 }
    ]
  }
}
```
Build ground truth by parsing all 609 articles' frontmatter
(grep/script). Compare facet counts against ground truth:
- meta_source: ~40 distinct values, counts sum to 609
- meta_category: exactly 6 values, counts sum to 609
- meta_author: verify articles with `author: "None"` are excluded

**Filter precision and recall (per field):**
For each distinct meta_source value, query with that filter and
verify: (a) every returned doc has that source (precision = 1.0),
(b) result count matches ground truth count (recall = 1.0).

**Case normalization:**
Verify `meta_source: ["The Verge"]` and `meta_source: ["the verge"]`
return identical result sets.

**Cross-filter intersection:**
Query `meta_source: ["the verge"], meta_category: ["technology"]`.
Verify result count equals the intersection of the two individual
filter counts from ground truth.

**Date range accuracy:**
Query `meta_published_after: "2023-10-01"`,
`meta_published_before: "2023-10-31"`. Verify result count matches
the ground truth count of articles with published_at in October.

**Automated verification script** (`scripts/jseval/jseval/metadata_eval.py`):
```bash
# Build ground truth (no backend needed):
cd scripts/jseval
python -m jseval.metadata_eval ground-truth \
  --corpus-dir D:/code/JustSearch/tmp-multihop-corpus

# Verify against live backend (requires running eval backend):
python -m jseval.metadata_eval verify \
  --corpus-dir D:/code/JustSearch/tmp-multihop-corpus \
  --base-url http://127.0.0.1:33221
```

Ground truth (verified): 609 articles, 49 sources, 298 authors,
6 categories, 64 "None" authors skipped, 244 October 2023 articles.

### Phase 2: Entity filter bug fix regression

Verify the pre-existing entity filter bug is fixed:

```json
POST /api/knowledge/retrieve-context
{
  "query": "news articles",
  "top_k": 5,
  "filters": {
    "entity_persons": ["Stan Choe"]
  }
}
```

Verify the response contains context ONLY from documents where
Stan Choe is an entity — not unfiltered fallback content. Before
the fix, entity filters in retrieve-context silently returned
unfiltered results.

Also test metadata filter on retrieve-context:
```json
POST /api/knowledge/retrieve-context
{
  "query": "technology news",
  "top_k": 5,
  "filters": {
    "meta_source": ["the verge"]
  }
}
```
Verify context comes exclusively from The Verge articles.

### Phase 3: MCP verification

Test that `.strict()` schema validation accepts new filter fields
and filters reach the backend correctly:
- `justsearch_search` with `filters: { meta_source: ["techcrunch"] }`
- `justsearch_retrieve_context` with `filters: { meta_category: ["technology"] }`
- `justsearch_search` with combined filters:
  `filters: { meta_source: ["the verge"], meta_category: ["technology"] }`

### Phase 4: Agent eval re-run

Re-run tempdoc 346 agent retrieval eval with metadata filters
available in the MCP tool schema. Use `agent_retrieval_eval.py`:

```bash
cd scripts/jseval
python -m jseval agent-eval --queries tmp-multihop-corpus/queries.json \
  --condition C --model haiku
```

**Baseline** (Condition C, no metadata filters):
- 75.0% accuracy, 12.4 avg turns, $0.082 avg cost

**Target** (Condition C', with metadata filters):
- Accuracy > 85% (the 14/20 source-specific queries should improve)
- Avg turns < 8 (agents skip manual frontmatter inspection)
- Avg cost reduction proportional to turn reduction

**Additional metrics to capture:**
- *Filter adoption rate:* for each of the 20 queries, did the
  agent construct a structured filter? Expected: 14+ queries.
- *Filter correctness:* when the agent uses a filter, did it use
  the right field name and value format?
- *Source-specific accuracy delta:* accuracy measured only on the
  14/20 source-specific queries (the motivating use case).
- *Temporal query handling:* for the 2/20 date-scoped queries,
  did the agent use `meta_published_after`/`meta_published_before`?

### Future eval dimensions (not in initial run)

- *Facet-driven reformulation:* does an agent use facet counts
  from a broad search to construct targeted filters on follow-up?
- *Multi-filter composition:* queries requiring 3+ simultaneous
  filters (author + source + category + date).
- *Retrieve-context vs search:* does RAG with metadata filters
  produce more focused context than document-level search?
- *Performance:* IndexOrDocValuesQuery speedup, two-stage
  pre-filter overhead, facet computation with 10 fields.

## Scope boundary

This is structured filters on top of full-text + semantic search.
It is NOT:
- A knowledge graph or relational database
- A custom metadata schema per user
- Free-form key-value indexing of arbitrary frontmatter

The initial implementation indexes a fixed set of well-known
metadata fields from markdown frontmatter. Extension to PDF
properties (via Tika metadata keys), email headers, or user-defined
fields is future work using the same `frontmatterMetadata` map on
`ExtractionResult`.

Phase 2 priority: **`tags`** (multi-valued keyword). Cross-
framework research shows `tags` is the most universal frontmatter
field after `title` — present in Hugo, Jekyll, Obsidian,
Docusaurus, and Astro. The MultiHop-RAG corpus lacks it, but
real-world user corpora almost certainly have it. Uses the multi-
valued keyword path (`SortedSetDocValuesField`) already proven by
`entity_*_raw` fields.

### Future opportunities

See "Theoretical best: target architecture" for the full analysis.
The highest-impact items in priority order:

1. **ACORN filtered kNN** — Lucene 10.2 version upgrade, no code
   change. Single biggest performance improvement for filtered
   hybrid search.
2. **`tags` field** — multi-valued keyword, proven infrastructure.
3. **Dynamic MCP enum** — populate tool schema from facet counts.
4. **Disjunctive faceting** — important when UI interactive use
   grows.
5. **Unified filter algebra** — eliminates dual-converter tech
   debt.
6. **Concurrent faceting** — matters when index exceeds 50K docs.
7. **Dynamic field patterns** — for arbitrary frontmatter keys.

## Theoretical best: target architecture

This section describes the theoretically optimal metadata
filtering architecture, derived from deep research across
academic papers, production vector databases, and enterprise
search systems. It is not a proposal for immediate
implementation — it is a north star that informs incremental
design decisions. Each subsection describes the gap between our
current architecture and the theoretical ideal.

### 1. Triple-representation index structure

**Current:** Each filterable field gets two representations —
inverted index (`StringField`/`LongPoint`) for lookups/ranges,
and column-oriented `DocValues` for faceting/sorting.

**Ideal:** Three representations per filterable field:
- **Inverted index** — for selective point lookups (term queries)
- **Column-oriented DocValues** — for faceting, sorting, aggregation
- **Bitmap index** — for fast boolean combination of filters

The bitmap layer is the key gap. When combining multiple filters
(`source:"The Verge" AND category:"technology"`), our system
intersects posting lists via Lucene's `BooleanQuery`. Best-in-class
systems (Weaviate, Pinecone, Qdrant) build roaring bitmap
AllowLists per filter predicate, then combine with native bitwise
AND/OR — O(n/64) regardless of predicate complexity. This matters
most when combining many filters or when filters feed into vector
search (a single bitmap AllowList is cheaper to evaluate during
graph traversal than a nested BooleanQuery).

Lucene already uses roaring bitmaps internally in the query cache
(`RoaringDocIdSet`), so we benefit indirectly. Making bitmap
indexes a first-class citizen — with the query planner choosing
between posting list intersection and bitmap AND based on estimated
cost — is the theoretical improvement.

### 2. Adaptive query planning

**Current:** `QueryFilterBuilder` applies all filters
unconditionally as `FILTER` clauses. Every filter is always a
pre-filter. No selectivity estimation, no strategy selection.

**Ideal:** A query planner that estimates selectivity per filter
before execution, then selects the optimal strategy:

```
Filter selectivity estimation (O(1) via term dictionary docFreq)
  → Strategy selection per filter:
    - <5% pass:  pre-filter + brute-force (vector path)
    - 5-80%:     integrated ACORN (vector), normal FILTER (BM25)
    - >80%:      post-filter or skip
  → Cost-based filter ordering:
    - Most selective filter executes first
    - Conjunction A ∧ B ∧ C ordered by ascending cardinality
  → Adaptive mid-execution correction:
    - Switch from HNSW to brute-force if neighborhood too sparse
```

Vespa implements the most complete version: hit count estimation
from index B-tree dictionaries, per-node strategy selection,
adaptive threshold-based fallback. Elasticsearch relies on
Lucene's scorer-ordering heuristics plus automatic filter caching
(tracks 256 most recent filters, caches those appearing 5+ times,
skips caching on small segments).

### 3. ACORN-integrated vector search

**Current:** Filters are passed to `KnnFloatVectorQuery(field,
vec, k, filter)` — Lucene's native pre-filter. Each candidate
node is checked against the filter during HNSW traversal. Fails
when the filter is highly selective: the graph fragments, the
traversal gets stuck, and recall degrades.

**Ideal (two levels):**

*Level 1 (ACORN):* Two-hop expansion when a neighbor fails the
filter — expand to the neighbor's neighbors, using filtered-out
nodes as bridges. Plus unpruned edge retention during graph
construction (M × gamma candidates instead of M) for denser,
more navigable graphs under filtering. Lucene 10.2 implements
ACORN-1 (PR #14160); JustSearch gets this with a version upgrade.

*Level 2 (per-predicate subgraphs):* During index building,
construct and merge subgraphs for common filter values. Filtering
`source = "The Verge"` traverses a pre-connected subgraph with
its own entry points. Eliminates graph fragmentation entirely for
known predicates. Higher index build time and storage, but
O(1)-quality filtered recall. This is what Qdrant does.

### 4. Concurrent, entropy-ranked, disjunctive faceting

**Current:** `FacetingEngine` iterates matching documents
sequentially per segment per field. 50K scan cap. Facets returned
in descending count order. No concurrency, no entropy ranking, no
disjunctive semantics.

**Ideal (four improvements):**

*Concurrent segment processing.* Process segments in parallel
using a thread pool. Lucene's
`ConcurrentSortedSetDocValuesFacetCounts` does this. Near-linear
speedup for multi-segment indexes.

*Global ordinal acceleration.* Convert keyword values to integer
ordinals at reader-open time. Facet counting operates on integer
arrays — no string comparisons during aggregation. Only convert
back to strings for the response. This is what Elasticsearch does
for all keyword aggregations.

*Entropy-based facet ranking.* Rank facets by discrimination
power for the current result set using Shannon entropy. A facet
where one value has 95% of results is less useful than one where
values are evenly distributed. Surface the most discriminative
facets first — to the UI (most useful filter first) and to agents
(saves context tokens).

*Disjunctive faceting.* When the user selects `source: "The
Verge"`, other source values' counts reflect OR semantics — what
*would* match if that value were also selected. Requires computing
each facet group's counts on the result set excluding its own
filter. Implementation: evaluate all K filter groups as cached
bitsets, for each group AND together all bitsets except its own.
Cost: O(K × N/64). Elasticsearch uses `post_filter` +
aggregations; Solr uses tag/exclude.

### 5. Adaptive agent interface

**Current:** Static MCP tool schema with optional string arrays
and fixed descriptions. Agent must make explicit facet queries to
discover available filter values.

**Ideal (three levels):**

*Level 1 — Dynamic enum:* After indexing, populate the tool
schema's categorical fields with actual values from the index.
`meta_category` gets `enum: ["sports", "technology", ...]`
generated from facet counts. Eliminates agent guessing.

*Level 2 — Query-to-filter decomposition:* A preprocessing layer
parses "articles by Stan Choe from The Verge" into
`{meta_author: "Stan Choe", meta_source: "The Verge"}` + semantic
query "articles". For agents this happens naturally via tool
parameters. For humans, match query tokens against known facet
values (the Algolia approach — facet value index as entity
dictionary). No ML model needed.

*Level 3 — Proactive facet surfacing:* Include the most useful
facets in every search response by default, ranked by entropy.
Saves the agent a round-trip. The response always includes
`available_filters: {meta_source: {"The Verge": 30, ...}}` without
the agent asking.

### 6. Dynamic schema evolution

**Current:** Adding a new filterable field requires changes to 8+
files: SSOT catalog → SchemaFields → proto → RuntimeSearchFilters
→ ProtoConverters → QueryFilterBuilder → REST DTO → MCP schema.

**Ideal:** Combine fixed catalog for known fields with dynamic
field patterns for arbitrary frontmatter keys:

*Fixed catalog:* `meta_source`, `meta_author`, `meta_category`,
`meta_published_at` — fully typed, validated, optimized.

*Dynamic field patterns:* `meta_kw_*` (auto-keyword),
`meta_dt_*` (auto-date). Any frontmatter key matching the pattern
is indexed with the correct type, DocValues, and roles. Adding
`tags`, `description`, or user-defined keys requires no code
changes — only naming convention.

Solr implements this with dynamic field declarations.
Elasticsearch infers types automatically. The hybrid approach
provides type safety for known fields and extensibility for
unknown ones.

### 7. Unified filter algebra

**Current:** Two separate filter paths: `ProtoConverters` for
search, `RagContextOps.buildRagFilters()` for retrieve-context.
Same `RuntimeSearchFilters` record, different proto messages,
different field subsets, different hardcoded defaults. Adding a
filter field requires touching both.

**Ideal:** A single canonical `FilterExpression` tree:

```
FilterExpression = And(left, right)
                 | Or(left, right)
                 | Not(inner)
                 | Term(field, value)
                 | Terms(field, values)
                 | Range(field, from, to)
                 | Exists(field)
                 | MatchAll
```

All entry points (REST, gRPC, MCP) parse their surface syntax
into this tree. A single translator converts the tree to Lucene
queries. Adding a filter field means adding it to the parser and
schema — the Lucene translation is generic. This also enables
filter composition: agent-specified filters combined with system
constraints (freshness, chunk inclusion) in one expression tree.

### Target code structure

```
                    ┌─────────────┐
                    │   Callers   │
                    │ REST, gRPC, │
                    │  MCP, UI    │
                    └──────┬──────┘
                           │ surface-level filter syntax
                           ▼
                    ┌─────────────┐
                    │   Filter    │
                    │   Parser    │  → canonical FilterExpression
                    └──────┬──────┘
                           │
                           ▼
                    ┌─────────────┐
                    │   Query     │
                    │  Planner    │  selectivity estimation,
                    │             │  strategy + ordering
                    └──────┬──────┘
                           │ optimized execution plan
                           ▼
              ┌────────────┼────────────┐
              │            │            │
              ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │   BM25   │ │  Vector  │ │  SPLADE  │
        │  Filter  │ │  Filter  │ │  Filter  │
        │ Executor │ │ Executor │ │ Executor │
        └──────────┘ └──────────┘ └──────────┘
              │       AllowList      │
              │       or ACORN       │
              ▼            ▼         ▼
        ┌──────────────────────────────────┐
        │       Fusion + Reranking         │
        └───────────────┬──────────────────┘
                        ▼
                 ┌─────────────┐
                 │   Facet     │  concurrent, entropy-
                 │  Computer   │  ranked, disjunctive
                 └──────┬──────┘
                        ▼
                 ┌─────────────┐
                 │  Response   │  proactive facets,
                 │  Assembler  │  dynamic MCP enum
                 └─────────────┘
```

### Relationship to this tempdoc

This tempdoc implements the correct simplified version of the
target architecture:

| Target component | This tempdoc's implementation |
|---|---|
| Triple-representation | Dual (inverted + DocValues); LongPoint added for longs |
| Adaptive query planner | Static pre-filter via FILTER clauses |
| ACORN vector search | Lucene's native KnnFloatVectorQuery pre-filter |
| Concurrent faceting | Sequential FacetingEngine (adequate at current scale) |
| Entropy-ranked facets | Count-ordered (adequate with 4 facet fields) |
| Disjunctive faceting | Conjunctive only (agents don't need disjunctive) |
| Dynamic enum | Static tool descriptions with examples |
| Query decomposition | Agent constructs filters directly |
| Dynamic schema | Fixed SSOT catalog (adequate for known fields) |
| Unified filter algebra | RuntimeSearchFilters flat record + two converters |

Each simplification is appropriate for current scale (hundreds to
low thousands of documents, 4 metadata fields, agent-primary use).
The target architecture becomes necessary as document count,
field count, filter complexity, or interactive UI usage grows.
The migration path is incremental — each component can evolve
independently.

### Open design questions

The target architecture above has blind spots — semantic and
system-level questions it doesn't address. These are documented
here so they inform future work rather than being discovered
during implementation.

#### Semantic gaps (affect Phase 1 correctness)

**Filter value normalization.** *Resolved for Phase 1* — see
"Value normalization" in the Schema design section. Metadata
keyword values are lowercased at index time and query time using
`toLowerCase(Locale.ROOT)`. This prevents case-mismatch bugs.
The deeper question remains for the target architecture: deeper
normalization (name variants "S. Choe" vs "Stan Choe", aliases
"theverge.com" vs "The Verge") would require a canonicalization
layer similar to `EntityClusterSnapshot`. The entity pipeline's
case-sensitivity is a related pre-existing issue — entity filter
values are not normalized either, and `expandCanonical()` lookups
are case-sensitive.

**Chunk vs. document filtering semantics.** *Resolved for Phase 1*
— see "Chunk filtering: pre-existing bug and design decision"
section. Two-stage retrieval (parent-doc pre-filter → chunk
search) fixes both the pre-existing entity filter bug and metadata
filtering. The deeper question for the target architecture remains:
the `FilterExpression` tree has no concept of parent-child joins.
A theoretically complete algebra would distinguish document-level
predicates from chunk-level predicates and handle the join
automatically.

**Filter vs. boost — the binary boundary.** The entire filter
algebra is boolean: a document either passes or doesn't. But real
retrieval needs sit on a spectrum: "prefer articles from The
Verge" (soft boost), "recent articles are more relevant" (temporal
decay), "authoritative sources should rank higher" (source-based
scoring). `freshness_enabled` already exists as a scoring signal
outside the filter system. The theoretically complete query algebra
would unify filters and boosts: each predicate has a mode —
`FILTER` (binary), `BOOST` (scoring influence), or `REQUIRE` (must
match, also scores). Elasticsearch's `bool.must` vs `bool.filter`
vs `bool.should` implements this distinction.

#### Expressiveness gaps

**Cross-field filter logic.** The MCP/REST API only exposes flat
AND-of-ORs: multiple values within a field are OR'd, different
fields are AND'd. No negation (`source != "The Verge"`), no
cross-field OR (`source = "The Verge" OR category = "technology"`).
The `FilterExpression` tree supports AND/OR/NOT syntactically, but
no surface API exposes the full tree. The design should make an
explicit choice: is flat AND-of-ORs sufficient (covers most agent
use cases), or should the API expose a richer expression language
(covers power users but complicates the schema)?

**Temporal semantics beyond range filtering.** `meta_published_at`
is a flat epoch-millis range filter, but real temporal needs are
richer:
- *Which date?* Publication date vs file modification vs indexing
  date. "Recent articles" is ambiguous.
- *Relative expressions:* "last week", "this month" — where is
  this resolved? The MCP tool accepts ISO dates, but agents often
  think in relative terms.
- *Date histogram faceting:* "When were these articles published?"
  requires bucketed counts by month/year. FacetingEngine computes
  value counts for keyword fields but has no histogram capability
  for long fields. This is a missing facet type.
- *Temporal decay scoring:* Gradual recency preference, not a hard
  date cutoff. Ties back to the filter-vs-boost gap.

#### Scale and correctness

**Cardinality management.** `meta_author` could have thousands of
unique values in a large corpus. High-cardinality facets are
expensive to compute (FacetingEngine iterates all matching docs),
overwhelm the UI (can't render 1000 pills), and break the dynamic
enum pattern (agent can't use a 1000-value enum). Production
systems handle this with: top-K facet values (already supported
via `size` parameter), facet value search/autocomplete
(Meilisearch's `/facet-search`), approximate counting (Qdrant's
`exact: false`), and cardinality-based rendering (dropdown for
<10 values, search box for >10). The design should specify
behavior at high cardinality.

**Facet-filter consistency.** If the `meta_source` facet says "The
Verge: 45", then applying filter `meta_source: ["The Verge"]` must
return exactly 45 results (same base query). This can break when:
facets are computed on a truncated scan (50K cap) but the filter
runs on the full index; facets use a stale reader snapshot; or
disjunctive faceting changes the computation base. The current
`FacetingEngine` receives the same `Query` used for search (which
is correct), but the 50K scan cap could cause divergence on large
indexes.

**Storage and memory budget.** Triple-representation (inverted +
DocValues + bitmap) multiplies storage per field. Combined with
dynamic field patterns allowing arbitrary frontmatter keys, a
local-first system with limited SSD/RAM faces constraints cloud
systems don't. Each new indexed field adds inverted index entries,
DocValues columns, and BKD trees. The design should specify field
count limits for dynamic patterns and storage-budget awareness.

#### System-level concerns

**Access control as filtering.** In multi-user or multi-context
scenarios, access control is metadata filtering: "user A sees only
collection X", "agent cannot retrieve confidential documents."
These are system-imposed filters that compose with user-specified
filters but have different trust requirements — the user/agent
must not be able to remove them. The `FilterExpression` tree treats
all predicates equally. The architecture should distinguish
user-specified filters (modifiable) from system-imposed filters
(injected after input parsing, tamper-resistant).

**Filter observability.** When a filter produces unexpected
results, there is no debugging path. Useful diagnostics would
include: why a specific document didn't match (metadata not
extracted? case mismatch? applied to chunks instead of parents?),
actual selectivity of a filter combination, vector recall
degradation from filter-induced graph fragmentation, and filter
cache hit rates. The Query Planner is the natural owner of a
filter execution plan, surfaced via the existing `debug` flag on
`SearchRequest`.

## Research findings

Findings from external research that informed design decisions.
Organized by topic, most impactful first.

### Filtered vector search and adaptive query planning

No single filtering strategy is optimal across all selectivities.
State-of-the-art systems (Qdrant, Weaviate, Vespa, Pinecone) use
adaptive query planning that selects strategy at query time:

| Filter selectivity | Strategy | Rationale |
|---|---|---|
| >80% pass | Post-filter | Graph traverses efficiently |
| 5-80% pass | Integrated (ACORN) | Both naive approaches degrade |
| <5% pass | Pre-filter + brute-force | Subset small enough for exact search |

For JustSearch's three retrieval modes:
- **BM25/SPLADE** (inverted index): metadata filters become
  `FILTER` clauses in `BooleanQuery` — inherently pre-filtered.
  This is the correct approach and needs no change.
- **Dense vectors (HNSW)**: Lucene 10.2 implements ACORN-1 (PR
  #14160) for integrated filtered kNN. Until JustSearch upgrades,
  filters are applied post-retrieval, which works but degrades
  with highly selective filters.
- **Hybrid fusion**: each retrieval mode applies filters
  independently; the fused result set naturally contains only
  documents passing the filter.

Key papers: ACORN (SIGMOD 2024), Pinecone single-stage filtering
(ICML 2025), Window Filters (ICML 2024), Dynamic Range-Filtering
ANN (VLDB 2025), Filtered FANNS benchmark (arXiv 2509.07789).

### Index structure validation

Best-in-class systems use dual-representation storage per field:
- **Inverted index** (term → doc list) for selective point lookups
- **Column-oriented DocValues** (doc → value) for faceting/sorting
- **BKD-tree** (LongPoint) additionally for numeric range queries

This is exactly what JustSearch's FieldMapper produces for keyword
fields (StringField + SortedDocValuesField). The gap was the `long`
branch missing LongPoint — now addressed in this tempdoc.

Roaring bitmaps are the state of the art for filter combination
(AND/OR of multiple filters). Lucene already uses them internally
in the query cache (`RoaringDocIdSet`). Weaviate, Pinecone, and
pgfaceting all use bitmap-based filter evaluation. JustSearch
benefits from Lucene's internal use without explicit changes.

### Faceted search theory (Hearst, Ranganathan)

Faceted navigation (Hearst, Flamenco project, UC Berkeley) turns
search from a recall task (remember filter values) into a
recognition task (see and select values). Facets should be:
- **Orthogonal** — each describes an independent aspect (source,
  category, date are orthogonal; source and author may correlate)
- **Collectively exhaustive** — together they cover the useful
  filter dimensions
- **Ranked by discrimination power** — Shannon entropy of the
  value distribution across the result set (CIKM 2013)
- **Coverage-gated** — only show facets with values for a
  meaningful fraction of results

JustSearch's entity facet coverage gate (NER ≥ 10% in
`SearchFiltersBar`) already implements coverage gating.

### Agent tool design

Anthropic's tool design guidance: tool definitions are prompts.
Key findings applied to MCP schema design:
- `enum` for bounded categorical fields improves accuracy
- Rich parameter descriptions with examples outperform terse labels
- Single tool with optional filters beats separate tools per filter
- BFCL V4 (Berkeley, 2025) shows returning structured metadata
  with results significantly improves agent performance vs raw text

### Tika cannot parse markdown frontmatter (confirmed)
Apache Tika delegates `.md` files to `TXTParser`, which treats
content as raw text. No configuration, plugin, or recent version
(through 3.x) adds frontmatter extraction. Confirmed via Tika
JIRA, mailing list, and GitHub. The custom extraction path using
`LanguageUtils.extractYamlValue()` is the correct approach.
CommonMark's `YamlFrontMatterExtension` is available as an
alternative if frontmatter complexity grows.

### Lucene range query performance
`NumericDocValuesField.newSlowRangeQuery()` is a linear scan.
`LongPoint.newRangeQuery()` uses BKD-tree with SIMD vectorization
(3.5x faster in Lucene 10.2). Best practice is dual indexing
(`LongPoint` + `NumericDocValuesField`) wrapped in
`IndexOrDocValuesQuery` which picks the optimal path at runtime.
Elasticsearch uses this pattern for all numeric fields. This
informed the FieldMapper change and QueryFilterBuilder update.

### Lucene native faceting
`SortedSetDocValuesFacetCounts` is recommended over custom facet
iteration. Has a concurrent variant and handles deleted-doc
skipping. However, requires `FacetsConfig.build()` at index time
and costly `SortedSetDocValuesReaderState`. For our low-cardinality
fields (6-40 values), the existing `FacetingEngine` works correctly
and the performance difference is negligible. Migrating to the
native module is a separate future optimization.

### Filter API design patterns
All major search systems (Meilisearch, Typesense, Elasticsearch,
Qdrant) use typed/structured filter parameters, not generic key-
value pairs. All provide facet count discovery APIs. Our typed-
field proto design and facet response pattern are consistent with
industry practice. Cross-system comparison confirms: Meilisearch
uses `filterableAttributes` + string expressions, Typesense uses
schema `facet: true` + `filter_by` strings, Elasticsearch uses
index mappings + structured JSON DSL, Qdrant uses payload indexes
+ structured JSON. All four provide facet count endpoints.

### Frontmatter field conventions
Cross-framework research (Hugo, Jekyll, Obsidian, Docusaurus,
Astro) shows `title`, `tags`, and `date` are near-universal. Our
Phase 1 field set (source, author, category, published_at) is
corpus-specific (MultiHop-RAG). `tags` is the top priority for
Phase 2 extension.

### Java date parsing
`DateTimeFormatterBuilder` with `appendOptional()` handles multi-
format parsing natively in Java 17+. No external library needed.
`parseBest()` supports type-varying results (date vs datetime).

### Disjunctive faceting
The "checkbox problem": when a user selects a facet value, other
values' counts should reflect OR semantics (what *would* match if
that value were also selected). Elasticsearch uses `post_filter` +
aggregations; Solr uses tag/exclude. Algorithmically O(K * N/64)
using cached bitsets. Not needed for Phase 1 (agents construct
explicit filters) but important for interactive UI use.

## Related

- **146**: Frontmatter stripping — `LanguageUtils.stripFrontmatter()`,
  `extractYamlValue()` infrastructure (completed)
- **326**: Entity retrieval activation — entity facet pattern this
  tempdoc follows
- **345**: RAG considerations — noted metadata filter gap
- **346**: Agent retrieval eval — 14/20 reflections requesting this;
  provides baseline metrics for eval comparison
- **256**: Component activation model — facet computation constraints
- Pre-existing gap: entity filters missing from search REST path
  (`KnowledgeSearchRequest.Filters`) — fixed as part of Step 5

## References

### Academic papers
- Patel, Kraft et al. "ACORN: Performant and Predicate-Agnostic
  Search Over Vector Embeddings and Structured Data" (SIGMOD 2024)
- Ingber & Liberty. "Accurate and Efficient Metadata Filtering in
  Pinecone's Serverless Vector Database" (ICML 2025)
- Engels et al. "Approximate Nearest Neighbor Search with Window
  Filters" (ICML 2024)
- "Dynamic Range-Filtering ANN Search" (VLDB 2025)
- "Filtered FANNS: A Unified Benchmark" (arXiv 2509.07789, 2025)
- "Filtered ANN in Vector Databases: System Design" (arXiv
  2602.11443, 2026)
- Hearst, M.A. *Search User Interfaces* (Cambridge, 2009), Ch. 8
- Hearst, M.A. "Design Recommendations for Hierarchical Faceted
  Search Interfaces" (SIGIR 2006 Workshop)
- Frasincar et al. "Facet Selection Algorithms for Web Product
  Search" (CIKM 2013)
- Chambi, Lemire et al. "Better Bitmap Performance with Roaring
  Bitmaps" (Software: Practice and Experience, 2016)
- Ranganathan, S.R. *Colon Classification* (1933) — origin of
  faceted classification

### Industry sources
- Anthropic: "Writing effective tools for AI agents" (2025)
- BFCL V4 Agentic benchmark (Berkeley, 2025)
- QAM: Query Attribute Modeling (arXiv 2508.04683, 2025)
- HyST: LLM-Powered Hybrid Retrieval (arXiv 2508.18048, 2025)
- Lucene ACORN-1 variant: PR #14160, Issue #13940
- Weaviate ACORN blog: "How we speed up filtered vector search"
- Qdrant: "A Complete Guide to Filtering in Vector Search"
- Vespa: "Constrained Approximate Nearest Neighbor Search"
- Algolia: auto-selected facets, query categorization
- Tunkelang, D.: "Facets, But Which Ones?" (Medium, 2018)
