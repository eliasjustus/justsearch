---
title: "ship-then-learn feedback capture: click/open signals + consolidating the already-persisted agent-citation tuples — the unlock for learned ranking and the eventual replacement for constructed benchmarks"
type: tempdocs
status: "chartered (2026-07-22). Long-horizon; capture half is buildable now, consumption half waits for real usage."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 762→771 measurement program's inventory (founder-directed 2026-07-22)
category: product / learned-ranking / telemetry
related:
  - 580 (F-021 refinement — the harvest-not-build citation signal; why GPL-synthetic labels failed)
---

> Charter. Two halves with different clocks: capture (build now, before
> launch, so day-one usage is not lost) and consumption (gated on real
> users existing).

# 778 — ship-then-learn feedback capture

## §A. The evidence base (F-021 lineage, do not re-derive)

- GPL-synthetic-trained LambdaMART is measured non-viable (F-021); real
  labels are necessary; the V1 2-feature schema is structurally capped below
  fusion regardless of labels (580 §13.7) — any learned layer needs BOTH
  real labels AND richer features.
- The agentic path ALREADY persists a graded real-query signal (retrieved ⊃
  grounding ⊃ cited, with parentDocId/chunkIndex + similarity —
  AgentCitationResolver/AgentInteractionMapper; 580 §16): real-query,
  harvest-not-build, but reorder-only and LLM-judged.
- User click/open/dwell capture is ABSENT (confirmed 580 §12.1) — the
  highest-value signal has no pipeline.

## §B. Work items

1. **Capture**: local-only click/open/dwell events on search results and
   chat citations — loopback-privacy by construction (nothing leaves the
   machine; the product's own privacy story is the constraint AND the
   feature), schema versioned, storage recoverability per StoreCatalog
   discipline.
2. **Consolidation**: one queryable store unifying user events + the
   persisted agent-citation tuples; a jseval-side reader so future ranking
   work consumes one interface.
3. **Deliberate non-goals now**: no learned model training, no
   fusion-weight fitting, no telemetry upload of any kind — consumption
   designs wait for real data volume (the F-021 lesson: labels first,
   models second).
4. **The benchmark bridge** (recorded, not built): once real query
   distributions exist, they seed question sets that replace constructed
   schemas — the endgame for the 766-line of work.

## §C. Acceptance

Capture live behind a default-on local flag with a visible privacy note;
events verified end-to-end on the dev stack; store passes
check-store-recoverability; zero network egress verified (loopback-only
invariant test extended); registers/docs updated.
