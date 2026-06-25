---
title: Triage PSI Drift on Encoder Distributions (LR4-g)
type: how-to
status: stable
description: "Investigation checklist when encoder_drift flags PSI > 0.2 on an encoder.ort_run duration distribution."
---

# Triage PSI Drift on Encoder Distributions (LR4-g)

The `encoder_drift` projection (tempdoc 400 LR4-g) flags
`drift_flagged: true` whenever the Population Stability Index between
the current run's `encoder.ort_run` durations and the cohort baseline
exceeds `0.2`. This is an observability-layer signal — it says
"encoder call latencies shifted distribution-wise" without taking a
position on whether that matters for quality.

This guide is the investigation checklist when the signal fires.

## PSI thresholds

Industry convention (followed by the projection):

- `PSI < 0.1` — no meaningful drift.
- `0.1 ≤ PSI < 0.2` — minor drift; watch but don't act.
- `PSI ≥ 0.2` — flagged; investigate.

Projection output is per-encoder (embed, splade, ner, bgem3). Each
encoder's PSI is independent — `splade` can drift while `embed` is
steady.

## Step 0 — is the baseline itself stale?

Before investigating the run, rule out a stale baseline:

```bash
# How old is the baseline?
stat <data_dir>/cohort_baselines/<hash>/span_distributions.json
```

If the baseline predates any of:
- A GPU driver or CUDA version change.
- An ONNX runtime or ORT-extensions version change.
- An encoder model swap or re-quantization.
- A corpus refresh that changes input-token distributions.

… the baseline is stale and the PSI signal is an artifact. Rotate
the baseline per `docs/how-to/calibrate-drift-baseline.md` and
re-run the projection.

## Step 1 — bound the magnitude

```bash
python -c "
import json
d = json.load(open('<run_dir>/projections/encoder_drift.json'))
for name, enc in d.get('encoders', {}).items():
    print(name, enc.get('psi_score'), enc.get('current_n'), enc.get('reference_n'))
"
```

A PSI of 0.2-0.5 is "something moved"; 0.5-1.0 is "noticeable"; >1.0
is "one distribution replaced the other". Note `current_n` —
PSI on `n < 50` samples is noisy; consider waiting for more data
before investigating.

## Step 2 — check the latency effect, not just the distribution

Distribution drift without latency regression is benign. From the
run's `summary.json`:

```bash
python -c "
import json
d = json.load(open('<run_dir>/summary.json'))
for mode, m in d.get('per_mode', {}).items():
    ls = m.get('latency_stats', {})
    print(mode, 'mean', ls.get('mean_ms'), 'p95', ls.get('p95_ms'))
"
```

If mean/p95 are flat vs the baseline run, the PSI flag captured
redistribution — e.g. more of the same mean but a fatter tail or
narrower main mode. Still worth noting, but not a regression.

If mean/p95 shifted significantly alongside the PSI flag, there IS
a latency effect — proceed.

## Step 3 — isolate the trigger

Ordered by frequency of root cause:

### 3a. Thermal / external hardware state

GPU temperature / power-limit / thermal throttling is the most common
PSI source on long-running dev boxes. Check:

```bash
nvidia-smi  # temp column; watch for > 80°C sustained
```

If `nvidia-smi` reports ≥ 80°C during the run window, thermal
throttling is the likely cause. Fix: wait for the box to cool,
re-run. Not a bug.

### 3b. Driver / runtime version change

```bash
nvidia-smi --query-gpu=driver_version --format=csv,noheader
```

Compare against the baseline's captured version (if recorded). Any
driver version drift since baseline capture warrants a baseline
rotation, not an investigation.

### 3c. Model / quantization change

If a model file was swapped, re-quantized, or re-exported since the
baseline was captured, the duration distribution shifts. Check
`<run_dir>/manifest.json` `model_fingerprints` against the baseline
git_sha's equivalent (e.g. from the baseline run's manifest).

### 3d. Input-shape distribution change

The encoder takes batched token sequences; if the corpus changed
(longer docs, more short queries, different padding strategy) the
per-call work changes. Compare:

- Mean `encoder.seq_len` attr on `encoder.ort_run` spans between
  current run and the baseline's source runs.
- Batch-size distribution (`encoder.batch_size`).

If either shifted materially, the PSI is capturing that shift, not a
latency regression.

### 3e. Kernel cache / warmup

A cold dev box on first run has larger CUDA kernel-cache fills in the
first N calls. Phase 6 / 6.2 made the baseline opt-in specifically to
avoid baselining against a cold run. If the current run is cold, this
is likely noise — re-run the eval 2-3× and see if PSI stabilizes
below threshold.

### 3f. Contention from another process

Other GPU work (another eval, llama-server running, video encoding,
screen recording) competes for the same GPU. Check:

```bash
nvidia-smi | grep -i python
```

… for other GPU consumers during the run window.

## Step 4 — decide

After investigation:

- **Baseline is stale** (Step 0, 3b, 3c): rotate via
  `docs/how-to/calibrate-drift-baseline.md`.
- **Transient cause** (3a thermal, 3e cold-start, 3f contention):
  re-run; verify PSI returns below threshold.
- **Legitimate regression** (latency rose in Step 2 *and* root cause
  is a code change): treat as a bug, open a tempdoc, bisect per
  `docs/how-to/interpret-bisect-output.md` to find the change that
  caused it.
- **Benign distribution shift** (Step 2 latencies flat): document
  the observation in `docs/observations.md` but no action.

## Related

- Tempdoc 400 §8.4 LR4-g — projection design.
- Tempdoc 400 post-implementation critique C-1.8.1 — why the baseline
  is opt-in (cold-start poisoning).
- `docs/how-to/calibrate-drift-baseline.md` — baseline capture.
- `docs/how-to/envelope-staleness-policy.md` — when baselines rotate.
- `scripts/jseval/jseval/projections/encoder_drift.py` — projection.
