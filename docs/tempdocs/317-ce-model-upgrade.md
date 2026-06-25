---
title: "317: Cross-Encoder Model Upgrade to GTE-ModernBERT"
type: tempdoc
status: done
created: 2026-03-17
depends-on: [309]
---

# 317: Cross-Encoder Model Upgrade to GTE-ModernBERT

## Purpose

Upgrade the cross-encoder reranker from ms-marco-MiniLM-L6-v2 (22.7M params,
512-token context) to gte-reranker-modernbert-base (149M params, 8192-token
context). This would eliminate per-document CE length gating entirely and
improve reranking quality.

## Background (from tempdoc 309 §15)

309 §15 researched the CE model landscape:

- **gte-reranker-modernbert-base** sits on the quality-size Pareto frontier —
  matches 1.2B-param models (nemotron-rerank-1b) at 8x smaller size.
- **8192-token context** via ModernBERT alternating attention (local 128-token
  window + global every 3rd layer). Eliminates the per-document CE gating
  concern from 309 §6.
- **VRAM**: ~150 MB INT8, fits within 8 GB coexistence budget.
- **ONNX export**: Supported via optimum v1.24+.
- **CPU fallback**: ~160-300 ms for top-20 (borderline acceptable). Dual-model
  strategy (GTE on GPU, MiniLM on CPU fallback) provides graceful degradation.

## Current state (verified 2026-03-16)

- `models/onnx/reranker/model.onnx` is MiniLM-L6-v2 (23 MB INT8)
- Tempdoc 288 claims GTE is "current" but it's not deployed
- `maxSequenceLength` defaults to 512 (matching MiniLM)
- `CrossEncoderReranker` hardcodes `model.onnx` filename

## Scope

- [ ] Obtain GTE-ModernBERT ONNX model (export or download)
- [ ] INT8 quantization for CPU path, FP16 for GPU path
- [ ] Deploy to `models/onnx/reranker/model.onnx` (replacing MiniLM)
- [ ] Update `JUSTSEARCH_RERANK_MAX_SEQ_LEN` default to 8192
- [ ] Verify ONNX Runtime compatibility (alternating attention, RoPE)
- [ ] Benchmark: latency (GPU vs CPU), VRAM consumption
- [ ] Eval: SciFact nDCG comparison (MiniLM vs GTE)
- [ ] If GTE confirmed better: keep MiniLM as CPU-only fallback model
- [ ] Update tempdoc 309 §6 and §15 status

## Experiment: GTE vs MiniLM on SciFact (2026-03-17)

| Mode | MiniLM (current) | GTE-ModernBERT | Delta |
|------|-----------------|----------------|-------|
| lexical (no CE) | 0.6619 | 0.6557 | −0.9% |
| full (with CE) | 0.6841 | 0.6836 | −0.1% |

**No quality improvement on SciFact.** 8192-token context doesn't help because
SciFact docs are ~200 tokens (within MiniLM's 512 window). GTE's value would
only appear on **long-doc corpora** where MiniLM truncates. A CourtListener
(median 5115 words) eval is needed to measure the upgrade's actual benefit.

## Out of scope

- Per-document CE gating by length (moot with 8192 context)
- Cascade CE (small → large model, per 309 §15 analysis)

---

## Staleness review (2026-05-18)

Open with no closure activity in >60 days. Marking `done` to clear the staleness signal; body content preserved as design history. If this work should resume, open a new tempdoc per the title-linking convention. Classification: ABANDONED. Stale for 62 days at audit time.

