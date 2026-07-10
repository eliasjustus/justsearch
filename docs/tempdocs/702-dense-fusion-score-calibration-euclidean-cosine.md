---
title: "Dense fusion score-calibration: the EUCLIDEAN vector field vs COSINE-calibrated arbitration thresholds — fix + re-eval the +125% arbitration"
type: tempdoc
status: "planned — diagnosis code+bytecode-verified; a no-reindex fix exists; MUST be eval-gated (it moves the shipped tempdoc-636 arbitration). 2026-07-10 (second pass, §B): pre-implementation verification pass re-confirmed every anchor at source AND found the fix plan incomplete — the low-signal 0.40 default has a SECOND, production-authoritative site (ResolvedConfigBuilder.java:1481) the original plan missed; corrected constants derived (0.5→1/3, 0.40→0.294 at both sites); bounded implementation plan §B.4 ready for a Sonnet implementer; eval gate concretized §B.5; pillar-5 scope boundary recorded §B.6 (recalibration cannot move raw leg recall — pre-gate vs post-fusion must be measured separately)"
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

## §B — Pre-implementation verification pass (2026-07-10, Fable orchestration)

Every diagnosis anchor re-verified against source this session, plus **one gap in the
fix plan as written above** found and closed here.

### B.1 Verified anchors

- `FieldMapper.java:250` — 2-arg `KnnFloatVectorField(def.id, vec)` confirmed (Lucene
  default similarity = EUCLIDEAN). The dense field is the only 2-arg production use;
  `VectorFormatDetectorTest` fixtures use the 3-arg COSINE form (`:43/:72/:136/:166/:197/:203`)
  — test/prod mismatch confirmed.
- `HybridSearchOps.java:61` `ARBITRATION_DENSE_CONFIDENT_MIN = 0.5`, applied at `:253`
  to the raw Lucene KNN score; javadoc `:223` says "top cosine ≥ …" — wrong for the
  actual EUCLIDEAN score. Private constant, single site, not config-exposed.
- `ResolvedConfigBuilder.java:1508` — `index.hybrid.leg_arbitration_enabled` default
  **true** (comment `:1503` "DEFAULT ON"): the miscalibrated gate feeds a default-on
  shipped mechanism, as diagnosed.
- `OnnxEmbeddingEncoder` (worker-core) — production embeddings are **L2-normalized**
  (class javadoc `:32/:37`, `l2Normalize` at `:350/:507/:589/:673`). The conversion
  `score_euc = 1/(3 − 2·cos)` is therefore exact, and EUCLIDEAN/COSINE rank
  identically — the defect is scalar-threshold-only, as diagnosed.

### B.2 New finding — the low-signal default has TWO sites, not one

The doc above cites only `HybridSearchOps.java:43` (`DEFAULT_VECTOR_LOW_SIGNAL_THRESHOLD
= 0.40`). That constant is the `hs == null` **fallback** (`HybridSearchOps.java:160-163`).
The value production actually uses resolves at
**`ResolvedConfigBuilder.java:1481`** — `resolveDouble("index.hybrid.vector_low_signal_top_score_threshold", 0.40)`
(env-registered at `EnvRegistry.java:997`). A fix touching only `HybridSearchOps`
would leave the shipped default at 0.40 — the wrong-gate failure mode. **Option A must
change both sites**, and the `ResolvedConfig.java:657` param doc + any cosine-semantics
comments near these sites must be updated. A user-set explicit override keeps their
value; the semantics change is worth one line in the key's comment.

### B.3 Corrected constants (derivation, not to be re-derived by the implementer)

For unit vectors, Lucene EUCLIDEAN score = `1/(1+d²)` with `d² = 2−2·cos`, i.e.
`score_euc = 1/(3−2·cos)`; intended semantics were cosine-score `(1+cos)/2`.

| Gate | Intended (cosine terms) | Current effective (EUCLIDEAN) | Corrected constant |
|---|---|---|---|
| `ARBITRATION_DENSE_CONFIDENT_MIN` | cos ≥ 0 ("not anti-correlated") | requires cos ≥ 0.5 | `1.0 / 3.0` (≈0.3333) |
| low-signal threshold (both sites) | cosine-score 0.40 ⟺ cos ≥ −0.2 | requires cos ≥ 0.25 | `0.294` (= 1/3.4) |

Both corrections **loosen** the effective gates: arbitration will fire more often, and
fewer queries will classify low-signal. That is precisely why the eval gate above is
mandatory — this moves shipped behavior in the direction that was intended but never
shipped.

### B.4 Implementation plan (bounded; delegate to a Sonnet implementer)

1. `HybridSearchOps.java`: `ARBITRATION_DENSE_CONFIDENT_MIN` 0.5 → `1.0 / 3.0`;
   `DEFAULT_VECTOR_LOW_SIGNAL_THRESHOLD` 0.40 → 0.294; fix the `:223` "top cosine"
   javadoc and any comment asserting `(1+cos)/2` semantics; document the EUCLIDEAN
   score space + conversion at the constants.
2. `ResolvedConfigBuilder.java:1481`: default 0.40 → 0.294; note the score-space
   semantics at the resolve site. Update `ResolvedConfig.java:657` param doc.
3. `VectorFormatDetectorTest`: align fixtures with production — use the 2-arg
   constructor (EUCLIDEAN) so the detector is tested against what production writes.
4. New unit test pinning the calibration: assert the constants equal the documented
   conversion (`1/(3-2·cos_intended)`) so a future "tidy the magic number" edit fails
   loudly with the derivation in the assertion message.
5. Check `ResolvedConfigBuilderTest` (default-assertion style, cf. `:639` rrfK) for
   pinned 0.40/0.5 expectations tied to these keys and update them with the same
   derivation comment.
6. Annotate tempdoc 268's stale "2-arg defaults to COSINE" claim (one-line correction
   note, not a rewrite).
7. `spotlessApply` → `build -x test` → `:modules:adapters-lucene:test` +
   `:modules:configuration:test`.

NOT in scope for the implementer: the eval A/B (main-loop judgment, dev-stack-gated),
any reindex (none needed), any change to arbitration logic itself.

### B.5 Eval gate (unchanged from above, made concrete)

A/B this branch vs `main` on the 636 grading corpora (needle + enron) + the standard
BEIR set: (i) arbitration firing-rate delta, (ii) nDCG@10 per corpus, (iii) low-signal
classification-rate delta. Ship criteria per the falsifier above (firing-rate unchanged
→ correctness cleanup, ship; firing-rate shifts → ship only if non-inferior on the 636
corpora). Interpretation stays with the orchestrating session, not the implementer.

### B.6 Interaction with pillar-5 attribution (704) — scope boundary

Threshold recalibration **cannot move raw dense-leg R@10** (identical ranking under
both metrics for unit vectors). It can only move **post-gate/post-fusion** numbers
(low-signal classification caps the vector leg's contribution; arbitration shifts
alpha). Therefore F-029's raw dense-death on CLERC is NOT explained by this bug alone —
the pillar-5 experiment (designed in tempdoc 678 this session) must measure **pre-gate
leg recall and post-fusion recall separately**, and its post-fusion measurements should
run on the post-702 engine to avoid measuring a known-miscalibrated gate.
