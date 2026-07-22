---
title: "resident-LLM ranking activation: the measured, unexploited advantage of a local-first engine with a resident model — listwise reranking, the judge-blend default decision, and validating the gated QU/sufficiency features"
type: tempdocs
status: "chartered (2026-07-22)."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 762→771 measurement program's inventory (founder-directed 2026-07-22)
category: search-engine / ranking / local-inference
related:
  - 643 (judge-stage ranking quality, F-026 — the U1 probe + shipped default-off blend this activates or retires)
  - 366 (QU/tool consolidation — the gated features)
---

> Charter. New number per dated-history convention; 643/F-026 are the
> evidence base, not reopened. Load `/search-quality` AND
> `/inference-runtime` before work; both registers carry duties here.

# 777 — resident-LLM ranking activation

## §A. The thesis and the banked evidence

A local-first product with a resident LLM can spend inference on ranking
quality in ways cloud-priced competitors cannot. The engine already ships
the seams, all dormant:

1. **Listwise LLM reranking (F-026 U1)**: the packaged Qwen3.5-9B, used as a
   single structured listwise call, MEASURED +0.042 nDCG over the shipped
   pipeline on a 40-query scifact sample (0.874 vs 0.831; captures 36% of
   the AI-free ceiling). One sample, one corpus — "whether to revisit is a
   live open question" (F-026). This lane answers it: multi-corpus
   measurement (scifact + enron + legal-clerc + the rebuilt strata),
   latency/VRAM cost characterized, opt-in shape designed if it holds.
2. **The judge-blend default decision (F-026 E1/E2)**: shipped default-off;
   scifact net-positive, enron thin net-negative. Decide the default from
   the D-004 template (measure → default-on or retire), not leave it in
   limbo.
3. **Gated features awaiting validation**: QU boostFilters (F-018 measured
   safe; disabled for LLM scheduling contention — is that still true
   post-737 lifecycle work?), context-sufficiency (Q-007: no labeled set),
   user-facing confidence (Q-009: no calibration). Each gets a
   validate-or-retire verdict — a gated feature that never activates is
   residue (retire-with-a-sweep applies).

## §B. Constraints

- Perf co-equal: llm-gen ratchets (640) + interactive latency budgets bind;
  listwise reranking may only ever be an opt-in/async tier if it costs
  hundreds of ms.
- D-005: the LLM is a JUDGE (allowed intelligence locus), never a router.
- VRAM arbitration with embedding/CE sessions is the known hard part
  (ort-common seams; serial-swap precedent from 674).

## §C. Acceptance

Per item: a measured verdict (activate with config shape + register
baselines, or retire with the measurement cited). No item left gated-and-
undecided. Registers updated before close.
