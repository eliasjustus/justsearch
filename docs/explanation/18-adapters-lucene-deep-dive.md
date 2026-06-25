---
title: Adapters-Lucene Deep Dive
type: explanation
status: stable
description: "HNSW search, SPLADE learned sparse retrieval, hybrid fusion, commit metadata system."
---

# 18. Adapters-Lucene Deep Dive (The "Search Engine" Internals)

The `modules/adapters-lucene` module provides the core search engine integration for JustSearch. It wraps Apache Lucene 10 with HNSW vector search, hybrid BM25+KNN fusion, and a sophisticated commit metadata system for schema evolution.

This document provides a deep architectural walkthrough for developers who need to understand, extend, or tune the search subsystem.

> **Decision register:** For settled findings, canonical baselines, and open questions about search quality, see `docs/reference/search-quality-register.md`.

## 1. Module Overview

### 1.1 Package Structure

```text
io.justsearch.adapters.lucene/
├── analyzers/          # Analyzer configuration, ICU tokenization, and synonym loading from SSOT catalogs
├── commit/             # Commit metadata fingerprinting (SHA-256) and schema parity validation on open
├── runtime/            # Core search engine runtime — facade, read/write paths, hybrid fusion, chunked RAG retrieval,
│                       #   query building, faceting, autocomplete, field mapping, NRT commit coordination, and pruning
└── (interfaces)        # Shard coordination contracts (single-shard implementation)
```

The `runtime/` package is the largest. It is organized around focused ops classes — `ReadPathOps`,
`WritePathOps`, `CommitOps`, `TextQueryOps`, `HybridSearchOps`, `ChunkSearchOps`, `DocumentQueryOps`,
`SuggestOps`, `PruneOps`, `FacetingEngine` — with `RunningRuntime`/`RuntimeSession` holding lifecycle.
(The former `LuceneIndexRuntime` facade was dissolved in tempdoc 320; the ops classes are now the API.)

### 1.3 Dependencies

- **Lucene 10.x**: Core search library (IndexWriter, IndexSearcher, HNSW)
- **configuration**: Field catalog definitions, `ResolvedConfig`/`ConfigStore` (config moved here in tempdoc 239; `RuntimeConfig` later deleted in 314)
- **indexing**: Schema fields, IndexApi contracts
- **infra-core**: Telemetry, utilities

## 2. Lucene 10 Integration

### 2.1 Directory and IndexWriter

```java
// Default: MMapDirectory for memory-mapped I/O
Directory directory = new MMapDirectory(indexPath);

// IndexWriter with custom codec
IndexWriterConfig config = new IndexWriterConfig(indexAnalyzer);
config.setCodec(new JustSearchCodec(knnVectorsFormat));
config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

// Soft deletes merge policy
config.setMergePolicy(new SoftDeletesRetentionMergePolicy(
    softDeleteField,
    retentionQuery,
    new TieredMergePolicy()
));
```

### 2.2 SearcherManager and CRTRT

Near Real-Time (NRT) search uses `ControlledRealTimeReopenThread`:

```java
SearcherManager searcherManager = new SearcherManager(writer, new SearcherFactory());

// CRTRT: background thread that refreshes searchers
ControlledRealTimeReopenThread<IndexSearcher> crtrt =
    new ControlledRealTimeReopenThread<>(writer, searcherManager,
        nrtHardMaxStaleMs / 1000.0,   // max stale (seconds)
        nrtTargetMaxStaleMs / 1000.0  // target stale (seconds)
    );
```

**Configuration**:
- `index.nrt.target_max_stale_ms`: 500ms (default)
- `index.nrt.hard_max_stale_ms`: Long.MAX_VALUE (default)

### 2.3 Read-After-Write Consistency

For APIs that need immediate visibility after commit:

```java
void maybeRefreshBlockingIfCommittedSinceRefresh() {
    if (lastCommitNanos.get() > lastRefreshNanos.get()) {
        searcherManager.maybeRefresh();
        lastRefreshNanos.set(System.nanoTime());
    }
}
```

## 3. HNSW Vector Search

### 3.1 JustSearchCodec

Custom codec wrapping `Lucene104Codec` with pluggable vector format:

```java
public final class JustSearchCodec extends FilterCodec {
    // HNSW Parameters
    private static final int DEFAULT_M = 16;              // max connections per node
    private static final int DEFAULT_EF_CONSTRUCTION = 200; // build-time beam width

    // Float32 format (current default)
    public static KnnVectorsFormat float32Format() {
        return new Lucene99HnswVectorsFormat(DEFAULT_M, DEFAULT_EF_CONSTRUCTION);
    }

    // Int8 scalar-quantized format (~75% memory reduction)
    // Tested with Lucene 10.3.1 - enable via index.vector.quantization.enabled=true
    public static KnnVectorsFormat quantizedFormat() {
        return new Lucene104HnswScalarQuantizedVectorsFormat(DEFAULT_M, DEFAULT_EF_CONSTRUCTION);
    }
}
```

**Memory Impact**:
| Format | 768 dims | Savings |
|--------|----------|---------|
| Float32 | 3,072 bytes/doc | Baseline |
| Int8 | 768 bytes/doc | ~75% reduction |

### 3.2 KnnFloatVectorQuery Variants

**Unfiltered KNN**:

```java
KnnFloatVectorQuery query = new KnnFloatVectorQuery(
    SchemaFields.VECTOR, queryVector, limit
);
```

**Filtered KNN** (pre-filter reduces candidate set):

```java
Query filter = buildFilterQueryOnly(runtimeFilters);
KnnFloatVectorQuery query = new KnnFloatVectorQuery(
    SchemaFields.VECTOR, queryVector, limit, filter
);
```

**With `ef_search` oversampling** (improves recall):

```java
// Lucene 10.3.1 sizes the HNSW candidate queue to `k` (no separate efSearch knob).
// When index.vector.ef_search is set (>0), JustSearch runs the query with k=max(limit, ef_search)
// and then returns only `limit` hits.
int queryK = Math.max(limit, configuredEfSearch);
KnnFloatVectorQuery query = new KnnFloatVectorQuery(
    SchemaFields.VECTOR, queryVector, queryK, filter
);
```

### 3.3 Field Separation Strategy

JustSearch uses separate vector fields to avoid filter overhead:

| Field | Documents | Purpose |
|-------|-----------|---------|
| `vector` | All documents | Parent-level embeddings |
| `chunk_vector` | Chunk documents only | Chunk-level embeddings |

**Why**: Querying `chunk_vector` avoids the `is_chunk=true` filter needed when searching `vector` for chunks. This saves ~17ms p95 overhead.

### 3.4 Configuration

For current HNSW tuning parameters (M, efConstruction, efSearch), see [`docs/explanation/23-search-pipeline-overview.md` §Stage 8](23-search-pipeline-overview.md).

| Parameter | Purpose |
|-----------|---------|
| `index.vector.ef_search` | Query-time search breadth (oversampling k) |
| `index.vector.hnsw.m` | Max connections per HNSW node |
| `index.vector.hnsw.ef_construction` | Build-time beam width |
| `index.vector.quantization.enabled` | Enable Int8 quantization |

## 4. Hybrid Search Architecture

### 4.1 Parallel Execution

Hybrid search runs BM25 and KNN in parallel using virtual threads:

```java
var executor = Executors.newVirtualThreadPerTaskExecutor();
try {
    var textFuture = CompletableFuture.supplyAsync(
        () -> searchText(query, textLimit, filters), executor);
    var vectorFuture = CompletableFuture.supplyAsync(
        () -> searchVector(vector, vectorLimit, filters), executor);

    textResult = textFuture.join();
    vectorResult = vectorFuture.join();
} finally {
    executor.close();
}
```

### 4.2 Over-Retrieval Strategy

To improve fusion quality, both paths over-retrieve candidates beyond the requested limit. Final results are trimmed to the requested limit after fusion. For current over-retrieval multipliers per path, see [`docs/explanation/23-search-pipeline-overview.md` §Stages 6-7](23-search-pipeline-overview.md).

### 4.3 RRF Fusion Algorithm

Reciprocal Rank Fusion combines rankings:

```text
score(doc) = Σ(weight / (K + rank))
           + bm25_boost_weight × raw_bm25_score
```

**Implementation** (`fuseWithRRF`):
1. Process BM25 results: `rrfScore = 1.0 / (K + rank)`
2. Process Vector results: `rrfScore = vectorWeight / (K + rank)`
3. Add BM25 boost: `finalScore += bm25BoostWeight × rawBm25Score`
4. Sort by fused score with tie-breakers (BM25 > vector > docId)

For current RRF constants (K, vectorWeight, bm25BoostWeight), see [`docs/explanation/23-search-pipeline-overview.md` §Stage 9](23-search-pipeline-overview.md).

### 4.4 Low-Signal Gating

Detects weak signal from either ranking and adjusts fusion. When low signal is detected, vector-only docs are capped and vector contribution is reduced. This prevents "semantic hijack" where weak vector matches dominate results.

For current low-signal detection thresholds and cap values, see [`docs/explanation/23-search-pipeline-overview.md` §Stage 10](23-search-pipeline-overview.md).

### 4.5 Stop-Word Short-Circuit

Skips expensive vector search for trivial queries (very short queries or single-word common stop words). For current thresholds, see [`docs/explanation/23-search-pipeline-overview.md` §Stage 11](23-search-pipeline-overview.md).

## 4B. SPLADE (Learned Sparse Retrieval)

### 4B.1 Indexing

SPLADE-v3 encodes documents as sparse term-weight vectors. `FieldMapper`
stores these as Lucene `FeatureField` entries — one per non-zero term:

```java
// FieldMapper.toDocument() — "splade" field type
for (var entry : sparseVec.entrySet()) {
    float weight = Math.min(entry.getValue(), 64.0f); // clamp outliers
    doc.add(new FeatureField(fieldId, entry.getKey(), weight));
}
```

Each document gets a variable number of `FeatureField` entries under the
`splade` field name. Weights are clamped to 64.0 to prevent single-token
dominance.

### 4B.2 Query-Time Search

SPLADE queries are also sparse weight vectors. Each token becomes a
`FeatureField.newLinearQuery` SHOULD clause in a `BooleanQuery`:

```java
// ChunkSearchOps.searchChunksSplade / LuceneIndexRuntime.searchSplade
BooleanQuery.Builder builder = new BooleanQuery.Builder();
for (var entry : queryWeights.entrySet()) {
    builder.add(
        FeatureField.newLinearQuery("splade", entry.getKey(), entry.getValue()),
        BooleanClause.Occur.SHOULD
    );
}
// + IS_CHUNK filter (true for chunk search, false/MUST_NOT for whole-doc)
```

Lucene's `FeatureField` scoring uses the stored weight directly — the
dot product of query weights and document weights produces the relevance
score, which is what makes SPLADE a *learned* sparse model (weights
come from the neural encoder, not BM25 TF-IDF).

### 4B.3 Whole-Doc vs Chunk SPLADE

| Search Path | IS_CHUNK Filter | Owner |
|-------------|-----------------|-------|
| Whole-doc SPLADE | `MUST_NOT is_chunk=true` | `LuceneIndexRuntime` |
| Chunk SPLADE | `FILTER is_chunk=true` | `ChunkSearchOps` |

Both paths share the same `FeatureField` query building logic. The chunk
path additionally includes `PARENT_TOKEN_COUNT` in its stored-field
allowlist so downstream fusion can modulate SPLADE weight by parent
document length (see [23-search-pipeline-overview.md § Stage 13b](23-search-pipeline-overview.md)).

### 4B.4 Known Limitation

SPLADE-v3 uses a BERT-base encoder with a 256-token max sequence length.
Documents longer than ~256 tokens lose body terms from the SPLADE
representation. The search pipeline compensates via parent-length
modulation in `HybridFusionUtils.spladeParentLengthMultiplier()`, which
tapers SPLADE weight linearly based on parent token count. For current
token-count thresholds, see [`docs/explanation/23-search-pipeline-overview.md` §Stage 13b](23-search-pipeline-overview.md).

## 5. Query Building

### 5.1 Filter Construction

Filters use `BooleanClause.Occur.FILTER` for non-scoring clauses:

```java
BooleanQuery.Builder builder = new BooleanQuery.Builder();

// Content query (scoring)
builder.add(contentQuery, BooleanClause.Occur.MUST);

// Chunk exclusion (non-scoring filter)
builder.add(new TermQuery(new Term("is_chunk", "true")), BooleanClause.Occur.MUST_NOT);

// MIME filter (non-scoring)
BooleanQuery.Builder mimeFilter = new BooleanQuery.Builder();
for (String mime : mimeTypes) {
    mimeFilter.add(new TermQuery(new Term("mime", mime)), BooleanClause.Occur.SHOULD);
}
builder.add(mimeFilter.build(), BooleanClause.Occur.FILTER);
```

### 5.2 Supported Filters

| Filter | Query Type | Purpose |
|--------|------------|---------|
| `mime` | TermQuery (OR) | MIME type filtering |
| `mimeBase` | TermQuery (OR) | MIME base category (e.g., "text") |
| `fileKind` | TermQuery (OR) | Document type bucket |
| `language` | TermQuery (OR) | Language codes |
| `pathPrefix` | PrefixQuery | Directory path prefix |
| `modifiedFromMs/ToMs` | NumericDocValuesField.newSlowRangeQuery | Date range |
| `includeChunks` | TermQuery (MUST_NOT) | Chunk doc visibility |

### 5.3 Query Syntax Modes

| Mode | Behavior |
|------|----------|
| `SIMPLE` | User input escaped (operators are literal text). Last term gets prefix expansion via `PrefixQuery` (min 3 chars, exact match boosted 2x). Uses `SCORING_BOOLEAN_REWRITE` for BM25 relevance ranking. |
| `LUCENE` | Full Lucene syntax (phrases, boolean, field qualifiers). No prefix expansion. |

Query building is centralized in `buildSimpleContentQuery()`, shared by both direct text search and filtered hybrid search paths. The method pipeline: escape → parse → `withPrefixExpansion()` → return query.

### 5.4 Search Correction Pipeline

When a SIMPLE-mode query returns zero hits, `GrpcSearchService` applies a two-stage correction pipeline:

1. **Zero-hit retry:** `buildFuzzyTextQuery()` resolves each query token to the closest indexed term via `resolveClosestTerm()` (Levenshtein distance + docFreq tiebreaker), then pipes the resolved terms through `buildSimpleContentQuery()` for score parity with normal queries.
2. **Per-term correction:** When total hits > 0 but some individual terms have zero `docFreq`, `buildPerTermFuzzyQuery()` replaces only the missing terms with their closest resolved equivalents, preserving exact terms.

Both paths set `correctionApplied = true` on the gRPC response and produce scores identical to equivalent exact queries (score parity via shared `buildSimpleContentQuery()` pipeline).

**Key methods in `TextQueryOps`:** `resolveClosestTerm()`, `levenshteinDistance()`, `buildFuzzyTextQuery()`, `buildPerTermFuzzyQuery()`. The facade retains thin delegation stubs for `buildFuzzyTextQuery` and `buildPerTermFuzzyQuery`.

## 6. Pagination and Cursors

### 6.1 Search-After Pattern

Cursor-based pagination using Lucene's `searchAfter`:

```java
// Cursor format: "safter-v1:" + sortKey + ":" + docIdB64 + ":" + score + ":" + modified + ":" + size
String cursor = SEARCH_AFTER_CURSOR_PREFIX +
    sortKey + ":" +
    SEARCH_AFTER_B64.encodeToString(docId.getBytes()) + ":" +
    score + ":" + modifiedAt + ":" + sizeBytes;
```

### 6.2 Sort Modes

| Mode | Sort Fields | Tie-breaker |
|------|-------------|-------------|
| `RELEVANCE` | score DESC | docId |
| `MODIFIED_DESC` | modified_at DESC | docId |
| `MODIFIED_ASC` | modified_at ASC | docId |
| `SIZE_DESC` | size_bytes DESC | docId |
| `SIZE_ASC` | size_bytes ASC | docId |
| `PATH_ASC` | docId ASC | - |
| `PATH_DESC` | docId DESC | - |

### 6.3 Lookahead Strategy

Request `limit + 1` documents to determine `hasMore` without extra query:

```java
TopDocs topDocs = searcher.searchAfter(after, query, limit + 1, sort);
boolean hasMore = topDocs.scoreDocs.length > limit;
```

## 7. Field Mapping

### 7.1 Type Conversion

`FieldMapper.toDocument()` converts IndexDocument fields to Lucene fields:

| Type | Lucene Field | DocValues |
|------|--------------|-----------|
| `text` | TextField | - |
| `keyword` | StringField | SortedDocValuesField |
| `long` | StoredField | NumericDocValuesField |
| `boolean` | StoredField (0/1) | NumericDocValuesField |
| `vector` | KnnFloatVectorField | - |

### 7.2 Field Roles

| Role | Behavior |
|------|----------|
| `id` | Primary key, must have DocValues |
| `filter` | Enables inverted index for O(log n) TermQuery |

### 7.3 Key Schema Fields

| Field | Type | Purpose |
|-------|------|---------|
| `doc_id` / `_id` | keyword | Primary key |
| `doc_uid` | keyword | Tiebreaker for search-after |
| `vector` | vector | Document embeddings |
| `chunk_vector` | vector | Chunk-level embeddings |
| `parent_doc_id` | keyword | Links chunks to parent |
| `is_chunk` | keyword | Marks chunk vs full doc |
| `_soft_delete` | keyword | Soft deletion marker |

## 8. Analyzer System

### 8.1 SsotAnalyzerRegistry

Loads analyzers from SSOT catalogs with fingerprinting:

```java
// Analyzer providers
Map<String, Function<Locale, Analyzer>> providers = Map.of(
    "icu+synonyms", locale -> buildIcuWithSynonyms(locale),
    "icu", locale -> buildIcu(locale),
    "keyword", locale -> new KeywordAnalyzer()
);
```

### 8.2 ICU Tokenizer Pipeline

```text
Input Text
    ↓
ICUTokenizer (Unicode-aware word breaking)
    ↓
ICUNormalizer2Filter (Unicode normalization: NFC)
    ↓
LowerCaseFilter
    ↓
SynonymGraphFilter (optional, locale-specific)
    ↓
Token Stream
```

### 8.3 Synonym Loading

Synonyms loaded from SSOT catalogs:
- `SSOT/catalogs/synonyms.en.v1.txt`
- `SSOT/catalogs/synonyms.de.v1.txt`

Format: comma-separated bidirectional expansion

```text
car,automobile,vehicle
quick,fast,rapid
```

### 8.4 Per-Field Analyzers

```java
Map<String, String> fieldToAnalyzerKey = Map.of(
    "content", "icu+synonyms",
    "title", "icu",
    "tags", "keyword"
);
Analyzer perField = analyzerRegistry.buildPerFieldAnalyzer(fieldToAnalyzerKey);
```

## 9. Commit Metadata

### 9.1 Fingerprinting Strategy

`SsotCommitMetadataSource` generates deterministic fingerprints:

```java
Map<String, String> metadata = Map.of(
    "schema_ver", schemaVersion,
    "schema_fp", sha256(canonicalJson(fieldsCatalog)),
    "analyzer_fp", sha256(sortedAnalyzerDefs),
    "synonyms_hash", sha256(concat(synonymFiles)),
    "boosts_fp", sha256(boostsConfig),
    "similarity_fp", sha256(bm25Descriptor)
);
```

### 9.2 Parity Guards

`IndexMetadataParityGuard` validates consistency on open:

```java
void checkOnOpen(Path indexPath, Map<String, String> expected) {
    Map<String, String> stored = readCommitMetadata(indexPath);
    if (!expected.equals(stored)) {
        throw new SchemaMismatchException(diff(expected, stored));
    }
}
```

### 9.3 Schema Mismatch Policies

| Policy | Behavior |
|--------|----------|
| `FAIL_CLOSED` | Refuse startup (production default) |
| `REBUILD_BACKUP_FIRST` | Backup then rebuild (dev default) |
| `BLUE_GREEN_MIGRATE` | Read-only + background rebuild |

## 10. Configuration Reference

### 10.1 Hybrid Search Tuning

JustSearch supports two fusion strategies: CC (Convex Combination, default) and RRF (Reciprocal Rank Fusion). CC fusion operates at two levels: 3-way within-branch fusion (BM25 + KNN + SPLADE) and 2-way branch fusion (whole-doc vs chunk branch). RRF provides an alternative rank-based fusion with configurable K constant, vector weight, and BM25 boost factor.

For current CC fusion weights and branch fusion parameters, see [`docs/reference/configuration/environment-variables.md`](../reference/configuration/environment-variables.md). For RRF constants, see [`docs/explanation/23-search-pipeline-overview.md` §Stage 9](23-search-pipeline-overview.md).

**Low-signal gating:** For current threshold and cap values, see [`docs/explanation/23-search-pipeline-overview.md` §Stage 10](23-search-pipeline-overview.md).

### 10.2 Vector Search Tuning

For current HNSW parameter values and ranges, see [`docs/explanation/23-search-pipeline-overview.md` §Stage 8](23-search-pipeline-overview.md).

### 10.3 BM25 Tuning

| Parameter | Default | Purpose |
|-----------|---------|---------|
| `index.similarity.text.k1` | 0.9 | Term frequency saturation |
| `index.similarity.text.b` | 0.4 | Length normalization |

## 11. Performance Optimizations

### 11.1 Virtual Thread Parallelization

BM25 and KNN execute in parallel using `Executors.newVirtualThreadPerTaskExecutor()`:
- No thread pool overhead
- True parallelism for I/O-bound operations
- Automatic cleanup via try-with-resources

### 11.2 Field Separation

Chunk vectors in separate field (`chunk_vector`) avoids `is_chunk` filter:
- Saves ~17ms p95 overhead
- No false positives from parent docs

### 11.3 FILTER vs MUST Clauses

Non-scoring filters use `BooleanClause.Occur.FILTER`:
- Lucene skips scoring calculation
- Significant speedup for complex filter trees

### 11.4 Prefetching

Stored fields are pre-loaded for I/O batching:

```java
// Group disk accesses for multiple hits
searcher.storedFields().prefetch(docIds);
```

### 11.5 Two-Phase Iterator Handling

Facet computation correctly handles query approximations:

```java
TwoPhaseIterator twoPhase = scorer.twoPhaseIterator();
if (twoPhase != null) {
    // Use approximation, then confirm matches
}
```

## 12. Runtime Concurrency Model

`LuceneIndexRuntime` is accessed from multiple threads (gRPC handlers, commit scheduler, close). The following patterns ensure thread safety without heavy locking:

### 12.1 Volatile Snapshot Accessors

The `facetingEngine` and `folderBrowseEngine` fields are declared `volatile`. Public methods access them through null-guard accessors (`facetingOps()`, `folderBrowseOps()`) that take a local snapshot before use:

```java
private FacetingEngine facetingOps() {
  FacetingEngine ops = this.facetingEngine; // local snapshot of volatile
  if (ops == null) throw new IllegalStateException("FacetingEngine not available");
  return ops;
}
```

The same pattern applies to `readOps()`, `writeOps()`, and `indexingCoordinator()`. `close()` nulls all volatile fields after shutting down components.

### 12.2 Lambda Capture Safety

`FacetingEngine` and `FolderBrowseEngine` receive `Supplier<IndexSearcher>` and `Consumer<IndexSearcher>` lambdas for searcher lifecycle. These lambdas capture `searcherManager` via a local snapshot (not a direct field reference) to prevent NPE if `close()` nulls the field concurrently:

```java
() -> {
  SearcherManager mgr = searcherManager; // local snapshot
  if (mgr == null) throw new IllegalStateException("SearcherManager not available");
  return mgr.acquire();
}
```

### 12.3 WritePathOps Null-Guard

`WritePathOps` receives a `Supplier<IndexWriter>` that captures the runtime's volatile writer field. Both `indexDocument()` and `applyBatch()` null-check the supplier result before use, throwing `IllegalStateException` if the runtime has been closed.

### 12.4 Thread-Safety Annotations

`LuceneIndexRuntime`, `ReadPathOps`, `WritePathOps`, and `FacetingEngine` are annotated with `@ThreadSafe` from `net.jcip.annotations`. `InferenceLifecycleManager` additionally uses `@GuardedBy("lock")` on its `currentMode` field.

## 13. Related Documentation

- `docs/explanation/04-storage-engine.md` - Storage layer overview
- `docs/explanation/17-ai-bridge-deep-dive.md` - AI/embedding integration
- `docs/explanation/11-index-schema-migration.md` - Blue/green migration
- `docs/future-features/rust-worker-rewrite.md` - Lucene JNI bridge analysis
