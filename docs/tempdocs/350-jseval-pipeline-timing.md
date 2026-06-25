---
title: "350: Backend Pipeline Timing Accumulation"
type: tempdoc
status: done
created: 2026-03-23
updated: 2026-03-24
verified: true
---

> NOTE: Noncanonical doc (architecture + investigation). May drift.

# 350: Backend Pipeline Timing Accumulation

## Goal

The backend accumulates per-batch enrichment timing (embed ms, SPLADE ms,
NER ms) that it already computes but discards after logging, and exposes
it through the `/api/status` response. This gives clients data that
polling cannot provide — per-ORT-call latency totals, batch counts, and
total inference wall time per stage.

## Root Cause

`CombinedEnrichmentBackfillOps.processCombinedBackfill()` computes
`embedMs`, `spladeMs`, `nerMs`, `fetchMs`, `writeMs` on every batch
and logs them at INFO. Then discards them. The only surviving data is
doc-count accumulators in `OperationalMetrics`.

## What Already Works (no changes needed)

- **Stage completion timing**: jseval `timeline.py:compute_pipeline_summary()`
  reconstructs per-stage completion times from 2s polling snapshots. ±2s
  accuracy is sufficient for stages that take 30–160s.
- **GPU/VRAM tracking**: timeline rows already capture GPU%, VRAM from
  `/api/status` at 2s intervals.
- **`--pipeline` mode**: jseval already waits for all enrichment stages.

## Work Items

### 1. Accumulate per-batch timing in `OperationalMetrics`

- [x] Add per-stage `LongAdder` pairs: `enrichmentEmbedBatchCount` +
  `enrichmentEmbedTotalMs`, same for SPLADE and NER
- [x] Extend `recordEnrichmentBatch()` to accept ms values
- [x] Only accumulate timing when that stage processed > 0 docs (so
  `totalMs / batchCount` gives meaningful per-batch averages)
- [x] Add corresponding getters

### 2. Pass timing from `CombinedEnrichmentBackfillOps`

- [x] Hoist timing variables (`embedMs`, `spladeMs`, `nerMs`) and
  processed counts to before the `try` block so they survive exceptions
- [x] Move `recordEnrichmentBatch` to a `finally` block guarded by a
  `recordTiming` flag (set after early-return checks pass)
- [x] If embedding throws mid-phase, `embedMs` stays 0 and
  `embedProcessed` stays 0, so the `if (done > 0)` guard in
  `OperationalMetrics` correctly skips it — but completed stages
  still get recorded

### 3. Proto message for pipeline timing

- [x] Add `PipelineBatchTiming` message to `indexing.proto`
- [x] Embed as field 9 in `EnrichmentCoverage`

### 4. Worker-side: populate proto from metrics

- [x] `IndexStatusOps.buildEnrichment()` — read new getters, populate
  the proto builder

### 5. Head-side: map through to status response

- [x] `WorkerStatusMapper` — extract new proto fields
- [x] `EnrichmentProgressView` — add timing record components
- [x] Verify it appears in `/api/status` JSON response

### 6. jseval consumer: timeline capture and pipeline summary

- [x] `snapshot_to_row()` captures the 6 new cumulative fields from
  `/api/status` (`embed_batches`, `embed_ms`, `splade_batches`, etc.)
- [x] `compute_pipeline_summary()` computes run-scoped deltas (last row
  minus first row) to produce per-stage inference timing: `batches`,
  `total_ms`, `avg_ms_per_batch`
- [x] `format_pipeline_summary()` renders inference timing in console output
- [x] Backward-compatible: old timeline rows without timing fields produce
  no `inference` section (tested)

## Items Not Pursued (and why)

- **Resource snapshots (GPU sampling)**: Already captured by timeline
  polling at 2s intervals. Backend-side sampling for sub-2s granularity
  is not justified.
- **`compare-pipeline` command**: jseval already has `compare` and `diff`.
- **`PipelineRun` lifecycle tracking** (startedAtMs, completedAtMs per
  stage): Stage transitions are already detected by timeline polling with
  sufficient accuracy. The new value here is accumulated inference time,
  not timestamps.

## Verification

- All Java compiles (main + test + integrationTest sources)
- Unit tests pass for all 6 affected modules: worker-core, worker-services,
  app-api, app-services, ipc-common, ui
- Schema baseline regenerated (`status-response.schema.json`) — new fields
  `enrichmentEmbedBatchCount`, `enrichmentEmbedTotalMs`,
  `enrichmentSpladeBatchCount`, `enrichmentSpladeTotalMs`,
  `enrichmentNerBatchCount`, `enrichmentNerTotalMs`
  appear in the `/api/status` JSON contract
- Spotless clean
- 325/325 jseval tests pass (4 new timeline tests for inference timing)

## Dependencies

- **334 (Single-Pass Enrichment):** `CombinedEnrichmentBackfillOps` is
  the accumulation site.
- **351 (CWD-Independent Paths):** jseval path resolution — done.
