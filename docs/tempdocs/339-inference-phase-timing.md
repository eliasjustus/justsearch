---
title: "339: Built-In Inference Phase Timing"
type: tempdoc
status: done
created: 2026-03-22
---

> NOTE: Noncanonical doc (architecture). May drift.

# 339: Built-In Inference Phase Timing

## Problem

Inference paths lack built-in per-phase timing. When SPLADE
post-processing consumed 44% of total SPLADE time (94M per-element
`FloatBuffer.get()` JNI calls), this was invisible without adding
ad-hoc instrumentation. The fix was a one-line bulk read — but
discovering the bottleneck required adding timing code, running
a profiling session, extracting data from logs, and removing the
timing code afterward.

Similarly, NER per-call overhead profiling (467us tensor creation
vs 1,520us ORT inference) required temporary instrumentation to
determine that batching was not viable.

## Proposed Solution

Every encoder should have built-in per-phase timing at DEBUG level:

```
SPLADE timing: batch=8, seqLen=384, ort=65ms, postProcess=37ms, total=102ms
NER timing: tokenize=420us, tensor=34us, ort=1520us, extract=12us, total=1987us
Embed timing: tokenize=Xms, ort=Yms, pool=Zms, total=Wms
```

This already exists in SPLADE (`SpladeEncoder` line 555) and was
added temporarily for NER (`BertNerInference` profiling counters).
The proposal: make these permanent at DEBUG level in all encoders.

Additionally, expose aggregate batch timing via `/api/status` so
jseval can read it without parsing worker logs (tempdoc 335 item 12).

## Related

- **335** item 12 (Combined batch timing from backend): deferred,
  proposes either `/api/status` batch timing or `analyze-log`.
- **312** Phase 0 (OTel tracing): implemented switchable tracing
  for primary indexing spans. Could extend to backfill.
- **334** items 19, 22: SPLADE and NER timing added during
  investigation, partially kept.

## Scope

- Standardize DEBUG-level timing in all 3 encoders
- Add aggregate counters (total ORT ms, total post-process ms,
  call count) to each encoder
- Expose via `/api/status` enrichment group

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Small enhancement (57 lines) standardizing DEBUG-level timing in 3 encoders + aggregate counters. Subsequent inference observability work (tempdoc 356, ADR-0027 MetricCatalog) covered the broader observability surface.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

