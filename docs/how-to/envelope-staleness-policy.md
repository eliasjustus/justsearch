---
title: Cohort Envelope Staleness Policy (LR1-b)
type: how-to
status: stable
description: "When a cohort envelope (non-determinism σ per metric) should be rotated, and what triggers automatic vs manual invalidation."
---

# Cohort Envelope Staleness Policy (LR1-b)

A cohort envelope (`<data_dir>/cohort_baselines/<hash>/envelope.json`)
records the natural σ of each metric across N identical reruns of the
same cohort. Consumers (`jseval gate`, `jseval compare`, `history
.check_trend`) use it to distinguish noise (±2σ) from signal.

The envelope is **durable by design** — it does not expire on a
schedule. It rotates only when the cohort itself becomes meaningless,
or when infrastructure drift moves the natural σ significantly.

This document explains what triggers invalidation (automatic + manual)
and how operators check whether a current envelope is still valid.

## Two kinds of invalidation

### Automatic (via cohort identity)

The envelope is keyed by the cohort's `manifest_hash`. When any
cohort-identity axis changes, the envelope for the old cohort is
simply inaccessible from the new cohort — consumers look up under a
different hash and get a miss.

Axes that invalidate automatically (change → new hash → new cohort):

- `git_sha` — any code change on the branch.
- `dataset` — e.g. scifact → nfcorpus.
- `doc_count` — corpus materialization size changed.
- `query_count` — eval query set size changed.
- `commit_metadata` (8 identity hashes) — schema / analyzer /
  similarity / boosts / grammar / synonyms / field-catalog /
  index-schema evolution.
- `corpus_identity` — content hash of the corpus changed.
- `model_fingerprints` (embed / splade / ner / ce) — model file
  swap or re-quantization.
- `policy_hash` — pipeline config change (fusion algorithm, weights,
  CE toggle, etc.).
- `eval_protocol_hash` — metric set / top-K changed.

Operators need not do anything; the new cohort starts fresh. Capture
a new envelope via `jseval calibrate` when appropriate.

### Manual (infrastructure drift)

Some changes DO NOT enter cohort identity but DO shift natural σ:

- GPU driver / CUDA / cuDNN version update on the eval host.
- ONNX runtime or ORT-extensions version update.
- OS kernel / scheduler change.
- Hardware replacement (new GPU, new CPU, more RAM changing heap
  pressure).

None of these change the cohort_hash — the envelope is still "valid"
in that it exists and is used — but its σ numbers describe an older
runtime profile and over- or under-band the new reality.

Symptom: the nightly `jseval gate` starts filing false-alarm issues
(σ drifts outside the ±10% band stably). Operators then follow
`docs/how-to/recalibrate-phase3-baseline.md` to rebase the gate and
regenerate the envelope.

## `envelope_staleness_days`

Each manifest records `envelope_staleness_days` — the days between
the envelope's `calibrated_at` and the current run's timestamp.
Informational only; does NOT invalidate. Surfaces to operators for
eyeball sanity checks (e.g. "envelope is 90 days old — worth
recalibrating alongside the Q1 hardware refresh").

Default informal TTL suggestions (not enforced):

- **Stable hardware, stable software:** envelopes remain valid
  indefinitely. Some cohorts in shipped datasets have ~year-old
  baselines that still match current σ.
- **Dev box with frequent driver updates:** rotate every 30-60 days
  or after any material driver bump.
- **Shared CI / cloud runners:** rotate quarterly as a discipline,
  since the underlying instance may have been replaced silently.

## Checking current envelope validity

### Is the envelope embedded?

```bash
python -c "
import json
m = json.load(open('<run_dir>/manifest.json'))
env = m.get('non_determinism_envelope')
if env is None:
    print('null — no envelope for this cohort (capture one via jseval calibrate)')
else:
    print(f\"n_runs={env.get('n_runs')} calibrated_at={env.get('calibrated_at')}\")
    print(f\"staleness_days={m.get('envelope_staleness_days')}\")
"
```

### Is σ still representative?

Run 2-3 fresh reruns at the same cohort and compare their spread to
the envelope's σ. If the new runs' IQR is within ±25% of the
envelope's ±2σ band, the envelope is fine. If significantly
tighter or looser, rotate.

## Rotation procedure

1. **Identify the trigger** — driver update, ORT version, hardware
   change. Document it in the rotation commit message.
2. **Capture new envelope** — `jseval calibrate --runs 5` at the
   current cohort (~25 min wall time on scifact).
3. **Rotate the drift baseline too** — `span_distributions.json`
   should be refreshed alongside because encoder duration
   distributions shifted. See
   `docs/how-to/calibrate-drift-baseline.md`.
4. **Update the nightly gate baseline** — if the cohort is the one
   the nightly workflow guards, also follow
   `docs/how-to/recalibrate-phase3-baseline.md` to rebase the
   hardcoded σ in the workflow env.

## Non-triggers (do NOT rotate)

- Single-run envelope staleness warnings with no identified
  infrastructure cause. Transient noise; wait for multiple runs.
- A single `jseval gate` failure with σ narrowly outside band.
  Re-run; confirm across 3+ nights before rotating.
- A code change that shifted a metric — that's a regression, not
  envelope drift. Investigate via `docs/how-to/interpret-bisect-
  output.md` first.

## Related

- Tempdoc 400 §8.1 LR1-b — envelope design.
- Tempdoc 400 §24 — Phase 2.0 cohort-identity fix (which axes
  entered the hash and which moved to `_VOLATILE_FIELDS`).
- Tempdoc 400 §25.4 Category B — deliberate exclusions
  (tail-latency calibration, multi-metric-set) — explains why the
  envelope covers specific metrics and not others.
- `docs/how-to/recalibrate-phase3-baseline.md` — nightly-gate
  rebase procedure.
- `docs/how-to/calibrate-drift-baseline.md` — encoder distribution
  baseline (sibling artifact under `cohort_baselines/<hash>/`).
- `scripts/jseval/jseval/calibrate.py` — envelope tool.
