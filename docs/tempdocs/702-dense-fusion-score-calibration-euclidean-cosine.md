---
title: "Dense fusion score-calibration: the EUCLIDEAN vector field vs COSINE-calibrated arbitration thresholds — fix + re-eval the +125% arbitration"
type: tempdoc
status: "planned — diagnosis code+bytecode-verified; a no-reindex fix exists; MUST be eval-gated (it moves the shipped tempdoc-636 arbitration)"
created: 2026-07-10
updated: 2026-07-10
related: [636, 268, 640]
---

# 702 — Dense fusion score-calibration (EUCLIDEAN vs COSINE)

## What this document is

A fix + eval plan for a confirmed dense-retrieval calibration defect: the dense
vector field is indexed with **EUCLIDEAN** similarity while the fusion arbitration's
confidence thresholds are calibrated for **COSINE** — and that miscalibrated threshold
**feeds the default-on tempdoc-636 leg-arbitration credited with a +125% nDCG needle
win**. Diagnosis (code + bytecode) below; all anchors are in this repo.

## Diagnosis (code + bytecode verified)

- `FieldMapper.java:250` builds the dense KNN field with the 2-arg
  `KnnFloatVectorField(id, vec)` → **EUCLIDEAN** (bytecode-confirmed against
  `lucene-core-10.4.0`, not the COSINE the surrounding code assumes).
- `HybridSearchOps.java:54-61` (`ARBITRATION_DENSE_CONFIDENT_MIN = 0.5`) and `:43`
  (`DEFAULT_VECTOR_LOW_SIGNAL_THRESHOLD = 0.40`) are commented/calibrated for
  **COSINE + L2-normalized** semantics, `score = (1+cos)/2`.
- Actual EUCLIDEAN score for L2-normalized vectors is `1/(3-2cos)`. So the intended
  gates are wrong: the `0.5` "dense-confident" gate actually requires cos ≈ 0.5
  (intended: cos ≈ 0, "not anti-correlated"); the `0.40` low-signal gate requires
  cos ≈ 0.25.
- Vectors are L2-normalized, so EUCLIDEAN and COSINE **rank identically** — the bug
  is not in ordering, it is in the **scalar thresholds** the arbitration and
  low-signal gates key on.
- That `0.5` gate feeds `tempdoc-636` per-query leg-arbitration (default ON,
  `ResolvedConfigBuilder.java:1508`), graded a +125% needle nDCG win — a shipped,
  quality-attributed mechanism running on a wrong signal.
- Collateral: `tempdoc 268` still asserts the 2-arg constructor "defaults to COSINE"
  (wrong per bytecode); `VectorFormatDetectorTest` fixtures use the 3-arg COSINE
  constructor while production uses EUCLIDEAN (test/prod mismatch).

## Fix options

- **Option A — recalibrate the thresholds for EUCLIDEAN (no reindex, preferred).**
  Convert the intended cosine gates to EUCLIDEAN-score space via `1/(3-2cos)`
  (intended cos≈0 → score ≈ 0.333; convert the intended low-signal cosine likewise),
  fix the constants + the misleading comment. Pure code change, no reindex, ranking
  unchanged.
- **Option B — switch the field to explicit COSINE similarity.** 3-arg
  `KnnFloatVectorField(id, vec, VectorSimilarityFunction.COSINE)`, making the
  `(1+cos)/2` comment literally true. Semantically cleaner but requires a **full
  reindex** (vector-format/similarity change) for no ranking benefit
  (L2-normalized → identical order).
- Recommendation: **Option A**. Also annotate/retire the stale `tempdoc 268` claim
  and align `VectorFormatDetectorTest` fixtures with production.

## Eval (mandatory — this moves a shipped mechanism)

Because the gate feeds the +125%-credited arbitration, recalibration **changes when
arbitration fires** and can improve or regress it. Do NOT ship on reasoning alone.

- A/B the fix on the tempdoc-636 grading corpora (the needle + enron sets that
  produced the +125% / −1.4% figures) plus the standard BEIR agent-utility set.
- Measure: arbitration firing-rate delta, and nDCG@10 delta per corpus.
- Falsifier: if firing-rate is materially unchanged → correctness-only cleanup
  (still ship — removes a latent trap). If firing-rate shifts → keep the variant
  only if nDCG is non-inferior on the 636 corpora.

## Downstream follow-up (not this doc)

`LambdaMART` (wired but inert — `justsearch.lambdamart.enabled=false`, no shipped
model) trains on the leg scores including the dense feature. Any investment in
training/enabling it is **contingent on this calibration** — train on a clean dense
signal, not a miscalibrated one.
