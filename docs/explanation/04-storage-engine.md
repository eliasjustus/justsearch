---
title: Storage Engine
type: explanation
status: stable
description: "Lucene internals, schema (SSOT), and locking strategies."
---

# 04. Storage Engine (Lucene)

JustSearch wraps **Apache Lucene 10** as its core storage engine. We do not use higher-level wrappers like Elasticsearch or Solr; we interact directly with the Lucene API (`IndexWriter`, `IndexSearcher`) for maximum performance and minimal footprint.

## Directory Structure

### Index root (`indexBasePath`)

The effective on-disk index root is `RuntimeConfig.indexBasePath()`:

- **Default**: `<dataDir>/index/<collection>` (collection defaults to `default`)
- **Override**: `JUSTSEARCH_INDEX_BASE_PATH` or `-Djustsearch.index.base_path=<path>`

In dev (`npm --prefix ./modules/ui-web run dev:all`) this commonly points at `modules/ui-web/.dev-data/index/default`, but the override mechanism is the source of truth.

### Generation layout (blue/green ready)

The index root is **generation-scoped** (managed by `IndexGenerationManager`):

```text
<indexBasePath>/
  state.json                       # pointers + migration state (format_version=2)
  migration_progress.json           # best-effort enumerator progress snapshot
  indices/
    <generationId>/
      .justsearch-generation.sentinel
      .justsearch-index-generation.json
      segments_N / _*.cfs / ...     # Lucene files
```

This layout enables safe schema migration (build a new generation alongside the active one) and crash-safe pointer updates.

*   **MMapDirectory:** We use memory-mapped files for the index.
    *   *Pros:* Extremely fast read access. The OS handles caching.
    *   *Cons:* On Windows, MMap files are **locked** by the OS. This is the primary reason for our 3-process architecture: only the Worker process ever opens these files, preventing `AccessDeniedException` when the UI process tries to delete them.

## Schema Management

Data in Lucene is schema-less by default, but JustSearch enforces a strict schema via the **SSOT** field catalog (`FieldCatalogDef`), injected via `FieldMapper`.

### Core Fields (`SchemaFields.java`)
| Field | Type | Purpose |
| :--- | :--- | :--- |
| `doc_id` | keyword | Primary Key (Normalized file path). |
| `doc_uid` | keyword | Unique ID (UUID) assigned at ingest/reindex (useful tie-breaker in some pipelines; not stable across full reindex). |
| `content` | text | Main searchable text (tokenized). |
| `content_preview` | text | Small stored snippet source (first few KB) for fast results list rendering. |
| `title` | text | Optional extracted title (stored). |
| `path` | keyword | Normalized path (stored). |
| `filename` | keyword | Filename (stored). |
| `mime` | keyword | Raw MIME (often includes parameters like `; charset=`). |
| `mime_base` | keyword | Normalized base MIME without parameters (DocValues; filter/facet friendly). |
| `file_kind` | keyword | Canonical UX-oriented type bucket (DocValues; filter/facet friendly). |
| `language` | keyword | Heuristic language/script tag (DocValues; filter/facet friendly). |
| `vector` | floats | 768-dim embeddings (HNSW). |
| `modified_at` | long | Timestamp for change detection. |
| `size_bytes` | long | File size (DocValues; sortable). |
| `parent_doc_id` | keyword | For Chunk documents (points to File). |
| `is_chunk` | boolean | "true" if this is a chunk, absent otherwise. |
| `chunk_index` | int | Sequential index of the chunk (0, 1, 2...). |
| `chunk_total` | int | Total number of chunks for the parent document. |
| `chunk_content` | text | Searchable chunk text used for BM25 chunk retrieval. |
| `chunk_start_char` | int | Start character offset (0-based) into the parent document’s extracted text. |
| `chunk_end_char` | int | End character offset (exclusive, 0-based) into the parent document’s extracted text. |
| `chunk_start_line` | int | Optional start line number (1-based) for citation/navigation UX. |
| `chunk_end_line` | int | Optional end line number (1-based) for citation/navigation UX. |
| `chunk_heading_text` | keyword | Optional nearest preceding Markdown heading text (empty when N/A). |
| `chunk_heading_level` | int | Optional Markdown heading level (1–6; 0 when N/A). |
| `chunk_vector` | floats | 768-dim chunk embeddings (HNSW) used for chunk-level hybrid retrieval. |
| `chunk_embedding_status` | keyword | Chunk embedding generation status (`PENDING|COMPLETED|FAILED`). |
| `chunk_embedding_retry_count` | long | Retry count for chunk embedding poison-pill protection. |
| `entity_persons_raw` | keyword (SortedSetDocValues) | Person entity facet values (filter/facet). |
| `entity_organizations_raw` | keyword (SortedSetDocValues) | Organization entity facet values (filter/facet). |
| `entity_locations_raw` | keyword (SortedSetDocValues) | Location entity facet values (filter/facet). |
| `entity_persons_text` | text (ICU-analyzed, stored) | Person entities for BM25 scoring. |
| `entity_organizations_text` | text (ICU-analyzed, stored) | Organization entities for BM25 scoring. |
| `entity_locations_text` | text (ICU-analyzed, stored) | Location entities for BM25 scoring. |
| `meta_source` | keyword (stored, DocValues) | Document source for filter/facet. |
| `meta_author` | keyword (stored, DocValues) | Document author for filter/facet. |
| `meta_category` | keyword (stored, DocValues) | Document category for filter/facet. |
| `meta_published_at` | long (stored, DocValues) | Publication timestamp for filter/sort. |
| `extraction_method` | keyword | Extraction tier used (e.g., STRUCTURED_TIKA, FLAT_TIKA). |
| `extraction_quality_score` | double | Numeric quality score 0.0–1.0 for provenance. |

**Notes on new field groups:**

- `meta_*` keyword fields are lowercased at both index time and query time (same pattern as `mime_base`). See [ADR-0020](../decisions/0020-structured-metadata-filterable-facets.md) for the full design rationale.
- `long` fields with `filter` role are dual-indexed as `LongPoint` (BKD-tree) + `NumericDocValuesField`, and `QueryFilterBuilder` wraps range queries in `IndexOrDocValuesQuery`. This benefits both `meta_published_at` and `modified_at`. (362)
- **SSOT catalog drift caveat:** the root-level `SSOT/catalogs/fields.v1.json` and `modules/adapters-lucene/src/main/resources/SSOT/catalogs/fields.v1.json` are separate copies. Adding fields to the root catalog does not update the classpath copy (used in production). Both must be synced. (326)

### Chunking Strategy
Large documents are split into overlapping chunks (default 500 tokens) to support RAG.
*   **Storage:** Chunks are stored as separate Lucene documents.
*   **Linkage:** They are linked to the original file via `parent_doc_id`.
*   **Retrieval:** Searches can target `is_chunk:true` to find specific relevant passages rather than whole files.

Chunk-level vector retrieval uses **field separation**:
- Full documents embed into `vector`
- Chunk documents embed into `chunk_vector`

This prevents doc/chunk mixing and keeps filter parity safe across TEXT/VECTOR/HYBRID query paths.

### Large docId-set filters (scale guardrail)
Some query paths (especially RAG retrieval) need to search within a **set of specific documents** (e.g., “only these docIds selected in the UI”).

Naively implementing that as a `BooleanQuery` with one `TermQuery` clause per docId can hit Lucene’s `maxClauseCount` (default 1024) and throw `TooManyClauses`.

Current implementation uses `TermInSetQuery` for these "ID set" filters to avoid clause explosion:

- Worker search runtime ops: `ReadPathOps` (read), `WritePathOps`+`CommitOps` (write/commit), `RunningRuntime`+`RuntimeSession` (lifecycle) in `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/`

Regression coverage:

- `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkSearchIntegrationTest.java` asserts `searchFullDocsForDocs` / `searchChunksForDocs` handle docId selections larger than `IndexSearcher.getMaxClauseCount()` without throwing; `TextSearchIntegrationTest` covers the prefix-expansion high-fanout variant.

## Interactive Search Runtime (current behavior)

Interactive file search is latency-sensitive (called on every keystroke), so the Worker enforces:

- **Projection**: callers can request only the fields they need for the list UI.
- **No `content` in hits**: full extracted `content` is never returned as part of search hits.
- **No `content` materialization for interactive search**: the Worker avoids decoding large stored `content` when building hit payloads.
- **Snippets**: the UI uses `content_preview` (small stored field) plus client-side highlighting.

## Sorting + cursor pagination (TEXT mode)

For large result sets, TEXT mode supports:

- **Query-time sorting** (e.g., relevance vs modified time).
- **Cursor-based pagination** using Lucene `searchAfter`.

The cursor token is intentionally opaque and short-lived: index mutations (especially reindex) can invalidate it.

### Validation Strategies
1.  **Startup Check:** `LuceneIndexRuntime.validateIndexableFields(...)` ensures the loaded catalog matches the constant `SchemaFields.INDEXABLE_FIELDS`. This prevents "silent failures" where code writes to a field that doesn't exist in the schema.
2.  **Runtime Check:** `FieldMapper` logs a warning (once per field) if an unknown field is encountered.

## Reliability Strategies

### 1. Corruption Recovery
`IndexRuntimeIOException` classifies errors. If `Reason.CORRUPT_INDEX` is detected at startup (e.g., missing segment files or `CorruptIndexException`):

- **Auto-recovery (guarded, backup-first)**: if `index.auto_recovery=true`, the runtime will **rename the broken index directory to a timestamped backup** and rebuild an empty index. This avoids destructive deletes and is more Windows-friendly.
- **Manual**: if auto-recovery is disabled, startup fails with a typed error; operators can safely rename/remove the affected generation directory.

### 2. Schema mismatch (distinct from corruption)

Schema mismatches are **not** treated as “corruption”.

- **Typed reason**: `IndexRuntimeIOException.Reason.SCHEMA_MISMATCH`
- **Policy-controlled** via `index.schema_mismatch.policy` (also overridable via `JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY` / `-Dindex.schema_mismatch.policy=...`):
  - `FAIL_CLOSED`: refuse to rebuild; require operator action (recommended prod default)
  - `REBUILD_BACKUP_FIRST`: rename-to-backup and rebuild empty (dev convenience)
  - `BLUE_GREEN_MIGRATE`: orchestrate a blue/green migration (serve read-only Blue while building Green)

Stable migration architecture is described in `docs/explanation/11-index-schema-migration.md`.

### 3. Commit Strategy
Writing to disk is expensive. `IndexingLoop` controls commits, but `LuceneIndexRuntime` enforces the physical write.
*   **Trigger:** We commit to disk (fsync) when:
    1.  **Time:** > 10 seconds since last commit.
    2.  **Size:** > 1000 documents in buffer.
    3.  **Event:** Shutdown signal received (Safe close).

### 4. Backpressure
The `queueDepth` counter guards against overloading the writer.
*   If `queueDepth > maxQueueDepth` (default 10,000), `indexBatch` throws `BACKPRESSURE` exception to slow down the ingest loop.

### 5. Vector Search (HNSW)
We use `Lucene99HnswVectorsFormat` (Float32) in the current default codec (`JustSearchCodec`, which extends `Lucene104Codec`).
*   **Quantization:** Available behind a flag (default off): `index.vector.quantization.enabled`, `JUSTSEARCH_INDEX_VECTOR_QUANTIZATION_ENABLED`, or `-Djustsearch.index.vector.quantization.enabled=true`. Uses `Lucene104HnswScalarQuantizedVectorsFormat`. Tested with Lucene 10.3.1 (5K/20K/50K docs, all modes pass). Provides ~75% vector storage reduction. Float32 remains default for backwards compatibility with existing indexes.
*   **Dimension:** Validated against the SSOT catalog (768).
*   **Validation:** `FieldMapper` strictly checks that `vector` field arrays match the expected dimension, throwing errors if they drift.

### 6. Performance Configuration

JustSearch uses tuned Lucene defaults optimized for desktop workloads:

| Setting | Default | Purpose |
|---------|---------|---------|
| Directory type | MMapDirectory | Memory-mapped files for fast reads (explicitly set at `ComponentsFactory.java:83`) |
| RAM buffer | 64 MB | Larger buffer reduces flush frequency (+20-30% indexing throughput vs Lucene's ~16MB default) |
| Commit interval | 10s / 1000 docs | Balance between durability and performance |

These defaults are optimized for desktop systems with sufficient RAM. The RAM buffer setting can be overridden via `index.writer.ram_buffer_mb` in configuration YAML if needed.
