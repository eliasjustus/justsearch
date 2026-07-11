---
title: Rebase the Phase 3 Drift-Gate Baseline
type: how-to
status: stable
description: "Procedure for accepting genuine infrastructure drift by rebasing the manual jseval gate's σ(nDCG@10) baseline."
---

# Rebase the Phase 3 Drift-Gate Baseline

`jseval gate` checks `σ(nDCG@10)` against a baseline
(`--baseline-stdev`, current recorded value: **0.00108**, measured on
scifact/50queries during the Phase 2 Q1 probe). This is fine until
infrastructure drift (GPU driver update, ONNX runtime upgrade, model
reload) shifts the natural variance. At that point manual gate runs
start reporting false-alarm drift — the baseline is wrong, not the
system.

> This procedure previously rebased a value hardcoded in
> `.github/workflows/phase-3-observability-nightly.yml`'s env block.
> That workflow was deleted 2026-07-07 — it never ran automatically in
> its history (see ADR-0026's 2026-07-07 amendment) — so `jseval
> gate`/`jseval calibrate` are now invoked manually, and **this
> document is the sole recorded source of truth for the current
> baseline value.** Update the bolded value above whenever you rebase.

This document records the procedure for operators who have accepted
that drift is genuine and want to rebase the gate.

**Do not auto-update** the baseline from a routine run. Auto-healing
masks real degradation. Every rebase must be an explicit, reviewed
operator action.

## When to rebase

- A manual gate run has filed >1 drift finding in a week.
- The gate report shows measured σ consistently outside the ±10% band
  but stable at a new value.
- You have traced the drift to a specific infrastructure change
  (driver update, ORT version bump, scifact corpus refresh, etc.) —
  NOT to a regression in search quality.

## When NOT to rebase

- A single gate run's failure with no identified infrastructure cause.
  Wait for confirmation across multiple runs.
- Any failure where `sigma(nDCG@10)` increases beyond ~2× the baseline.
  That's genuine instability; investigate before rebasing.
- Any failure coincident with a code change to the search pipeline,
  model, or eval harness — that's a regression signal, not drift.

## Procedure

### 1. Reproduce the current σ

Trigger a manual calibration with the same parameters the gate
uses:

```bash
export JUSTSEARCH_INDEX_TRACING_LEVEL=detailed
# Tempdoc 716: --data-dir is where the envelope is FILED (the jseval-owned
# root); --backend-data-dir is the isolated backend dir the calibration
# sub-runs execute against (their fail-closed --clean wipes it each run).
CALIB_DIR=tmp/recalibration-calib
BACKEND_DIR=tmp/recalibration-data

cd scripts/jseval
python -m jseval calibrate \
    --dataset scifact --modes full --runs 5 --max-queries 50 \
    --data-dir "$CALIB_DIR" \
    --backend-data-dir "$BACKEND_DIR"
```

Estimated wall time: ~25 min (5 runs × ~5 min).

### 2. Extract the measured σ

Use the recalibration helper to read `σ(nDCG@10)` from the produced
envelope:

```bash
python -m jseval recalibrate-nightly-baseline \
    --data-dir "$CALIB_DIR" \
    --cohort-hash <HASH> \
    --output /tmp/nightly-baseline.env
```

The `<HASH>` comes from the calibration log (`cohort identified:
<hash>...`) or from the directory name under
`$CALIB_DIR/cohort_baselines/`. (When calibration ran at defaults,
omit `--data-dir` — it defaults to the jseval data root,
`scripts/jseval/tmp/`.)

The helper writes a line like:

```text
PHASE3_BASELINE_NDCG10_STDEV=0.00142
```

### 3. Sanity-check against the current baseline

Compare the new value to the baseline recorded at the top of this
document (currently 0.00108).

A ≤20% shift is reasonable for driver / ORT changes. A >50% shift
typically indicates genuine instability — investigate before rebasing.

### 4. Update the recorded baseline

Update the bolded baseline value at the top of this document, and pass
the new value explicitly on future manual invocations:

```bash
python -m jseval gate \
    --data-dir "$CALIB_DIR" \
    --baseline-stdev 0.00142 \
    --tolerance-pct 10 \
    --report-out "$CALIB_DIR/gate-report.json"
```

Commit message template for the doc update:

```text
docs(400): rebase Phase 3 drift-gate baseline 0.00108 → 0.00142

Trigger: driver update to CUDA 13.5 on 2026-04-22. Natural σ shifted
from 0.00108 to 0.00142 across 5 calibrations. No quality regression
(mean nDCG@10 unchanged at 0.7411). New band: ±10% of 0.00142.
Attached calibration evidence: <path to /tmp/nightly-baseline.env> +
cohort_baselines/<hash>/envelope.json.
```

### 5. Close any outstanding drift findings

Any tracked issues opened against the old baseline should be closed
with a reference to the rebase commit.

## Emergency: widen the gate tolerance

If the gate is genuinely broken AND an immediate rebase isn't feasible
(e.g. limited debugging bandwidth), pass a wider `--tolerance-pct`
temporarily (e.g. `--tolerance-pct 100`) on manual invocations, and
open an issue to investigate + revert within a working day. Skipping
the gate check entirely is never appropriate — it removes the only
signal for cross-run variance regressions.

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
- `docs/how-to/triage-psi-drift.md` — what to do when the gate's
  encoder_drift signal fires (often the first symptom of drift
  before the envelope-gate bands widen).
- `docs/how-to/interpret-bisect-output.md` — when the drift is a
  real regression (metric + latency), bisection attributes it to an
  axis.
- `scripts/jseval/jseval/cli.py::cmd_recalibrate_nightly_baseline` —
  the tool.
- ADR-0026 (2026-07-07 amendment) — why the gate is manual-only and
  the recorded baseline above is the sole source of truth.
