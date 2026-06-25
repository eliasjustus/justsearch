---
title: Recalibrate the Phase 3 Nightly Observability Baseline
type: how-to
status: stable
description: "Procedure for accepting genuine infrastructure drift by rebasing the nightly workflow's σ(nDCG@10) guard."
---

# Recalibrate the Phase 3 Nightly Observability Baseline

The `.github/workflows/phase-3-observability-nightly.yml` workflow gates
on `σ(nDCG@10)` being within ±10% of a hardcoded baseline
(`PHASE3_BASELINE_NDCG10_STDEV`). The Q1 probe during Phase 2 measured
0.00108 on scifact/50queries. This is fine until infrastructure drift
(GPU driver update, ONNX runtime upgrade, model reload) shifts the
natural variance. At that point the nightly starts filing false-alarm
issues — the gate is wrong, not the system.

This document records the procedure for operators who have accepted
that drift is genuine and want to rebase the gate.

**Do not auto-update** the baseline from a nightly run. Auto-healing
masks real degradation. Every rebase must be an explicit, reviewed
operator action.

## When to rebase

- Nightly workflow has filed >1 drift issue in a week.
- The auto-opened issue's gate report shows measured σ consistently
  outside the ±10% band but stable at a new value.
- You have traced the drift to a specific infrastructure change
  (driver update, ORT version bump, scifact corpus refresh, etc.) —
  NOT to a regression in search quality.

## When NOT to rebase

- A single night's gate failure with no identified infrastructure
  cause. Wait for confirmation across multiple nights.
- Any failure where `sigma(nDCG@10)` increases beyond ~2× the baseline.
  That's genuine instability; investigate before rebasing.
- Any failure coincident with a code change to the search pipeline,
  model, or eval harness — that's a regression signal, not drift.

## Procedure

### 1. Reproduce the current σ

Trigger a manual calibration with the same parameters the nightly
uses:

```bash
export JUSTSEARCH_INDEX_TRACING_LEVEL=detailed
export JUSTSEARCH_DATA_DIR=tmp/recalibration-data
rm -rf "$JUSTSEARCH_DATA_DIR"  # clean slate, cold-start cost captured

cd scripts/jseval
python -m jseval calibrate \
    --dataset scifact --modes full --runs 5 --max-queries 50 \
    --data-dir "$JUSTSEARCH_DATA_DIR"
```

Estimated wall time: ~25 min (5 runs × ~5 min).

### 2. Extract the measured σ

Use the recalibration helper to read `σ(nDCG@10)` from the produced
envelope:

```bash
python -m jseval recalibrate-nightly-baseline \
    --data-dir "$JUSTSEARCH_DATA_DIR" \
    --cohort-hash <HASH> \
    --output /tmp/nightly-baseline.env
```

The `<HASH>` comes from the calibration log (`cohort identified:
<hash>...`) or from the filename under
`$JUSTSEARCH_DATA_DIR/cohort_baselines/`.

The helper writes a line like:

```text
PHASE3_BASELINE_NDCG10_STDEV=0.00142
```

### 3. Sanity-check against the current baseline

Compare the new value to the current workflow env value:

```bash
grep PHASE3_BASELINE_NDCG10_STDEV \
    .github/workflows/phase-3-observability-nightly.yml
```

A ≤20% shift is reasonable for driver / ORT changes. A >50% shift
typically indicates genuine instability — investigate before rebasing.

### 4. Update the workflow

Edit `.github/workflows/phase-3-observability-nightly.yml`, replace the
`PHASE3_BASELINE_NDCG10_STDEV` value. Commit message template:

```text
ci(400): recalibrate Phase 3 nightly σ(nDCG@10) baseline 0.00108 → 0.00142

Trigger: driver update to CUDA 13.5 on 2026-04-22. Natural σ shifted
from 0.00108 to 0.00142 across 5 calibrations. No quality regression
(mean nDCG@10 unchanged at 0.7411). New band: ±10% of 0.00142.
Attached calibration evidence: <path to /tmp/nightly-baseline.env> +
cohort_baselines/<hash>/envelope.json.
```

### 5. Close any outstanding drift issues

Auto-opened issues for the old baseline should be closed with a
reference to the rebase commit.

## Emergency: disable the nightly gate

If the gate is genuinely broken AND an immediate rebase isn't feasible
(e.g. weekend, limited debugging bandwidth), set the tolerance to a
wide value temporarily:

```yaml
PHASE3_STDEV_TOLERANCE_PCT: '100'
```

…and open an issue to investigate + revert within a working day.
Disabling the gate entirely (`if: false`) is never appropriate — it
removes the only automated signal for cross-run variance regressions.

## Related

- Tempdoc 400 §26.6 Decision 4 — the decision that introduced the
  nightly with hardcoded σ.
- Tempdoc 400 post-implementation critique C-1.10.1 — the recalibration-
  path gap that motivated this how-to.
- `docs/how-to/envelope-staleness-policy.md` — triggers for envelope
  rotation (this doc is one output of that policy).
- `docs/how-to/calibrate-drift-baseline.md` — the sibling artifact
  (`span_distributions.json`) that should be rotated alongside the
  envelope when infrastructure drifts.
- `docs/how-to/triage-psi-drift.md` — what to do when the nightly's
  encoder_drift signal fires (often the first symptom of drift
  before the envelope-gate bands widen).
- `docs/how-to/interpret-bisect-output.md` — when the drift is a
  real regression (metric + latency), bisection attributes it to an
  axis.
- `scripts/jseval/jseval/cli.py::cmd_recalibrate_nightly_baseline` —
  the tool.
- `.github/workflows/phase-3-observability-nightly.yml` — the workflow
  env block holds the baseline.
