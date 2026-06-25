---
title: "354: Metrics Pipeline Consistency"
type: tempdoc
status: done
created: 2026-03-24
updated: 2026-03-24
---

> NOTE: Noncanonical doc (architecture + investigation). May drift.

# 354: Metrics Pipeline Consistency

## Goal

Eliminate per-field metric proliferation across the OperationalMetrics →
proto → API → jseval pipeline. Adding or removing an enrichment stage
should not require synchronized changes across 8+ files.

## Background: Phase 1 (batch timing) — complete

Phase 1 replaced 6 individual `LongAdder` timing fields with map-based
`ConcurrentHashMap<String, LongAdder>` for batch timing. This made
adding a new timing phase (fetch, write, total) a two-line change
(one constant + one call site). Implemented 2026-03-24.

**Files created:** `BatchTimingKeys.java` (worker-core),
`BatchTimingView.java` (app-api).

**Pattern established:** String-keyed maps flow through
`map<string, int64>` proto fields → `Map<String, Long>` API records →
dict-based jseval extraction. Schema auto-generated. Adding a key
touches only the constant definition and call site.

## Current State: Remaining Inconsistencies

### I-1: Enrichment doc-count fields use the per-field pattern

`enrichmentEmbeddingCompleted`, `enrichmentSpladeCompleted`,
`enrichmentNerCompleted` are 3 individual `LongAdder` fields in
`OperationalMetrics`, 3 individual `int64` fields in
`EnrichmentCoverage` proto, 3 individual parameters in
`EnrichmentProgressView`, and 3 individual `.get()` calls in
`timeline.py`. Exactly the pattern Phase 1 eliminated for timing.

The recording method `recordEnrichmentBatch(int embedDone, int
spladeDone, int nerDone)` takes 3 positional parameters — adding a
fourth enrichment stage means changing this signature and every caller.

**Current shape:**
```
OperationalMetrics:  3 LongAdder fields + 3 getters + 1 method (3 params)
indexing.proto:      3 individual int64 fields in EnrichmentCoverage
IndexStatusOps:      3 individual setter calls
EnrichmentProgressView: 3 individual long parameters
WorkerStatusMapper:  3 individual proto getter calls
timeline.py:         3 individual snapshot.get() calls
```

### I-2: NER coverage representation is inconsistent with embed/SPLADE

Embedding and SPLADE coverage use structured `FeatureCoverage`
sub-messages (doc_count, completed_count, pending_count, failed_count,
coverage_percent). NER uses two bare scalars (`pending_ner_count`,
`completed_ner_count`) — no doc_count, no failed_count, no
coverage_percent. This means:

- The API has `embeddingCoveragePercent` and `spladeCoveragePercent`
  but no `nerCoveragePercent`
- `EnrichmentProgressView` has 5 fields per embed/SPLADE stage but
  only 2 for NER
- jseval can compute embed/SPLADE rates from coverage percent deltas
  but must use raw count deltas for NER

This is a data-model inconsistency, not a metrics-pipeline issue.
It predates this tempdoc and has a different root cause (NER was added
later with a simpler representation). Fixing it would mean giving NER
its own `FeatureCoverage` in the proto — a separate concern from
map-based accumulation.

### I-3: 19 of 30 OperationalMetrics getters are not in the gRPC pipeline

Only 11 getters (+ throughputMonitor) are read by `IndexStatusOps`
and sent through gRPC to the Head. The other 19 are consumed only by
OTel callbacks in `KnowledgeServer` for NDJSON export. The unsurfaced
metrics include search counters, RAG retrieval counters, search
pipeline fallback counters, latency gauges, and batch submission
counters.

This is by design — the gRPC status path exposes what `/api/status`
and jseval need for polling-based observability. OTel export is the
correct channel for the rest. No action needed unless a specific
unsurfaced metric is needed for jseval profiling or frontend display.

## Analysis

### What should change (I-1)

The enrichment doc-count fields are the same pattern Phase 1 fixed.
The fix is the same: replace 3 individual fields with a single
`Map<String, Long>` keyed by stage name.

**Metrics layer change:**
```java
// Replace 3 LongAdder fields with one map
private final ConcurrentHashMap<String, LongAdder> enrichmentCompleted =
    new ConcurrentHashMap<>();

// Replace recordEnrichmentBatch(int, int, int) with:
public void recordEnrichmentCompleted(String stage, int count) {
    if (count > 0) {
        enrichmentCompleted.computeIfAbsent(stage, k -> new LongAdder()).add(count);
    }
}

public Map<String, Long> getEnrichmentCompleted() { /* snapshot */ }
```

**Call site change** (CombinedEnrichmentBackfillOps):
```java
// Before:
metrics.recordEnrichmentBatch(embedProcessed, spladeProcessed, nerProcessed);

// After:
metrics.recordEnrichmentCompleted(EMBED, embedProcessed);
metrics.recordEnrichmentCompleted(SPLADE, spladeProcessed);
metrics.recordEnrichmentCompleted(NER, nerProcessed);
```

**Proto change** (EnrichmentCoverage):
```protobuf
// Remove fields 6, 7, 8 and reserve them. Use next available (10).
reserved 6, 7, 8;
map<string, int64> enrichment_completed = 10;
```

**API change** (EnrichmentProgressView):
```java
// Replace 3 individual long parameters with:
Map<String, Long> enrichmentCompleted
```

**jseval change** (timeline.py):
```python
# Replace 3 individual snapshot.get() calls with:
enrich = snapshot.get("enrichmentCompleted", {})
row["enrich_embed"] = enrich.get("embed", 0)
row["enrich_splade"] = enrich.get("splade", 0)
row["enrich_ner"] = enrich.get("ner", 0)
```

This reuses the exact Phase 1 pattern. The key vocabulary is the same
(`BatchTimingKeys.EMBED`, etc.) — doc counts and batch timing share
stage identity.

### What should NOT change (I-2, I-3)

**I-2 (NER coverage representation):** This is a data-model design
question, not a metrics accumulation issue. Giving NER a
`FeatureCoverage` sub-message would require changes to how NER status
is queried from Lucene (currently two `countByField` queries rather
than the `queryEmbeddingCounts()`/`querySpladeFeatureCounts()` pattern
used by embedding and SPLADE). That's a different kind of work with
different risks. Out of scope.

**I-3 (unsurfaced metrics):** The OTel export path is the correct
channel for search/RAG/indexing counters. No evidence that jseval or
the frontend needs them via the polling API. No action.

## Work Items

### Phase 1: Batch timing (complete)

1. [x] `BatchTimingKeys` constants class
2. [x] Map-based accumulation in `OperationalMetrics`
3. [x] Update `CombinedEnrichmentBackfillOps` call site
4. [x] Update `PipelineBatchTiming` proto
5. [x] Update `IndexStatusOps`
6. [x] `BatchTimingView` record + update `EnrichmentProgressView`
7. [x] Update `WorkerStatusMapper`
8. [x] Update jseval `timeline.py`
9. [x] Update tests + regenerate schema
10. [x] Update `08-observability.md`
11. [x] Live verification

### Phase 2: Enrichment doc-count map-ification

12. [x] **Map-based enrichment completed in `OperationalMetrics`.**
    Add `enrichmentCompleted` map + `recordEnrichmentCompleted(stage,
    count)` method + snapshot getter. Remove 3 individual LongAdder
    fields, 3 individual getters, and `recordEnrichmentBatch()`.
13. [x] **Update `CombinedEnrichmentBackfillOps` call site.** Replace
    `recordEnrichmentBatch(embed, splade, ner)` with 3
    `recordEnrichmentCompleted(STAGE, count)` calls.
14. [x] **Update `EnrichmentCoverage` proto.** Remove individual
    `int64` fields 6, 7, 8 (reserve them). Add `map<string, int64>
    enrichment_completed = 10`.
15. [x] **Update `IndexStatusOps`.** Replace 3 individual setter
    calls with `putAllEnrichmentCompleted()` from metrics snapshot.
16. [x] **Update `EnrichmentProgressView`.** Replace 3 individual
    `long` parameters with `Map<String, Long> enrichmentCompleted`.
17. [x] **Update `WorkerStatusMapper`.** Map from proto map to view.
18. [x] **Update jseval `timeline.py`.** Read from nested
    `enrichmentCompleted` map instead of 3 flat fields.
19. [x] **Update tests.** `StatusRecordSchemaTest` constructor calls,
    regenerate schema. `test_timeline.py` snapshot format.
20. [x] **Update `08-observability.md`.**
21. [x] **Live verification.**

## Migration Path

Same as Phase 1: single coordinated commit. Head+Worker co-deployed,
jseval controlled. No external consumers need migration time.

## Verification

Full pipeline run (SciFact 5184 docs, GPU, 2026-03-25) confirms both
phases work end-to-end.

**Phase 1 — overhead timing captured (previously invisible):**

| Phase | Batches | Total ms | Per batch | % of batch time |
|-------|---------|----------|-----------|-----------------|
| Fetch | 77 | 1,049 | 13.6ms | 0.7% |
| Write | 77 | 9,989 | 129.7ms | 6.4% |
| Total | 77 | 155,908 | 2,024.8ms | 100% |

**Phase 1 — inference timing:**

| Stage | Batches | Total ms | Per batch |
|-------|---------|----------|-----------|
| Embedding | 53 | 81,396 | 1,535.8ms |
| SPLADE | 53 | 29,730 | 560.9ms |
| NER | 52 | 31,274 | 601.4ms |

**Phase 2 — enrichment doc counts from `enrichmentCompleted` map:**
- `enrich_embed=7362`, `enrich_splade=5184`, `enrich_ner=5184`
- Timeline TSV: all 27 columns present, all populated.

Timeline written to `scripts/jseval/tmp/timeline-354.tsv` (92 rows).

## Non-Goals

- OTel histogram/trace integration
- Per-batch granularity in the API
- NER coverage parity with embed/SPLADE (I-2 — different concern)
- Surfacing unsurfaced OTel-only metrics via gRPC (I-3 — no demand)
