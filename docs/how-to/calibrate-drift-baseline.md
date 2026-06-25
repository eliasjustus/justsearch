---
title: Capture an Encoder-Drift Baseline (LR4-g)
type: how-to
status: stable
description: "Procedure for capturing the first cohort baseline of encoder.ort_run span duration distributions used by the encoder_drift projection."
---

# Capture an Encoder-Drift Baseline (LR4-g)

The `encoder_drift` projection (tempdoc 400 LR4-g) compares the current
run's `encoder.ort_run` span duration distributions against a cohort
baseline using Population Stability Index (PSI). Before the first
comparison can run for any cohort, the baseline must be captured.

Post-Phase-6/6.2 the baseline is **opt-in and warm-run-only**. The
original design auto-captured from the first-ever run for a cohort,
but a single cold-start measurement poisoned the baseline permanently
(inflated tails from JIT warmup and CUDA kernel-cache fills). The
fix forces `≥ 3` warm runs before a baseline can land.

This document is the operator procedure for capturing the baseline.

## When to run

- A new cohort is stable (same git SHA, same models, same dataset,
  same policy) and has produced `≥ 3` warm runs.
- An existing cohort has been invalidated (e.g., after a GPU driver
  update or model reload — see
  `docs/how-to/envelope-staleness-policy.md`) and needs a fresh
  baseline.

## When NOT to run

- Before `≥ 3` warm runs exist at the target cohort_hash. The
  subcommand rejects attempts with fewer runs unless `--force` is
  passed; do not pass `--force` to work around the guard.
- On a cold dev box immediately after a reboot or a long idle period
  without at least one prior warm-up run.
- During active code changes that modify encoder call shapes (batch
  size, sequence length) — let the changes settle first.

## Prerequisites

1. A calibrated cohort (`<data_dir>/cohort_baselines/<hash>/envelope.json`
   exists). Produced by `jseval calibrate` — see
   `docs/how-to/recalibrate-phase3-baseline.md` for that procedure.
2. `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed` exported when producing
   the source runs — otherwise `encoder.ort_run` spans are not
   emitted and the baseline has no data.
3. At least 3 `jseval run` directories, each at the same
   `manifest_hash`, mirroring their `traces.ndjson` into the run dir
   (automatic when `JUSTSEARCH_DATA_DIR` is set during the run).

## Procedure

### 1. Identify the cohort_hash

From any of the warm runs:

```bash
python -c "import json; print(json.load(open('<run_dir>/manifest.json'))['manifest_hash'])"
```

All three (or more) runs must print the same hash. If they differ,
something in the cohort identity changed — stop and investigate
(see `docs/how-to/envelope-staleness-policy.md` for the identity
axes).

### 2. Capture the baseline

```bash
export JUSTSEARCH_DATA_DIR=/path/to/data/dir   # absolute path

python -m jseval calibrate-drift-baseline \
    --cohort-hash <HASH> \
    --data-dir "$JUSTSEARCH_DATA_DIR" \
    --from-runs /path/to/run1 /path/to/run2 /path/to/run3
```

The subcommand:
1. Verifies each `--from-runs` directory's `manifest.manifest_hash`
   matches `--cohort-hash` (cross-cohort mixing rejected).
2. Merges `encoder.ort_run` span durations per encoder across all
   runs (cap at `MAX_SAMPLES_PER_ENCODER` — currently 5000 per
   encoder per cohort).
3. Writes `<data_dir>/cohort_baselines/<hash>/span_distributions.json`.

### 3. Verify

Run `encoder_drift` against one of the warm runs (or a fresh run at
the same cohort):

```bash
# Fresh run (requires live backend)
python -m jseval run --dataset scifact --modes full --max-queries 50 \
    --skip-ingest

# Inspect the projection output
cat <run_dir>/projections/encoder_drift.json | python -m json.tool
```

Expected: `"status": "ok"` and per-encoder `"drift_flagged": false`
with `"psi_score"` close to zero. If `"status": "no-baseline"`,
re-check that the file at
`<data_dir>/cohort_baselines/<cohort_hash>/span_distributions.json`
exists and the cohort_hash in the fresh run matches.

## When the baseline should be rotated

A baseline is durable — it does not expire on its own. Rotate it
explicitly when:

- A GPU driver / CUDA / cuDNN version changes on the eval machine.
- An encoder model is swapped or re-quantized.
- An ORT or ORT-extensions version changes.
- A corpus refresh materially changes the input-token distribution.

For rotation, delete the existing `span_distributions.json` and
re-run this procedure with fresh warm runs at the updated cohort.

## Related

- Tempdoc 400 §8.4 LR4-g — the projection design.
- Tempdoc 400 post-implementation critique C-1.8.1 — the auto-capture
  defect that prompted Phase 6 / 6.2's opt-in fix.
- Tempdoc 400 §23.8 D-1 — the `duration_ms` structural field the
  projection consumes.
- `docs/how-to/triage-psi-drift.md` — what to do when PSI flags drift.
- `docs/how-to/envelope-staleness-policy.md` — when to rotate.
- `scripts/jseval/jseval/cli.py::cmd_calibrate_drift_baseline` — tool.
- `scripts/jseval/jseval/projections/encoder_drift.py` — projection.
