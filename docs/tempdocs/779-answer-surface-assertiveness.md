---
title: "answer-surface assertiveness: when the evidence pack already contains the answer, show the answer — the measured 28-cell gap between retrieval success and synthesis failure, turned into a product question"
type: tempdocs
status: "chartered (2026-07-22). Small product lane; UX-owner scoped."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 762→771 measurement program's inventory (founder-directed 2026-07-22)
category: product / chat-rag-ux
related:
  - 763-retrieval-attribution-lane   # §F: the 28 B3-answer cells (gold in the pack, answer wrong)
  - 775-evidence-span-authority      # the span substrate this surface would consume
---

> Charter. The smallest lane from the 2026-07 inventory, kept separate
> because its owner is the chat/RAG UX, not the retrieval substrate.

# 779 — answer-surface assertiveness

## §A. The measured gap

In the v5 census, 28 failed with-tool cells had the gold passage INSIDE the
`justsearch_answer` evidence pack — retrieval succeeded, model synthesis
failed (763 §F, B3-answer class ≈ 22% of all failures). The product
currently treats the model's synthesis as the only answer path; when the
pack demonstrably contains the answer span, the surface could present the
extracted evidence assertively (top passage with highlighted span +
provenance) alongside — or instead of — a shaky synthesis.

## §B. Questions for the lane (design is the lane's)

- Which surfaces: chat answers, the agent `justsearch_answer` payload, or
  both? (The agent-facing half couples to 775's span authority — sequence
  after it.)
- Confidence gating without a validated calibration (Q-009 is open): when is
  showing the raw passage MORE honest than a fluent synthesis? The 561
  transparency-tooltip precedent (relative, uncalibrated, labeled) is the
  pattern to extend, not a new confidence invention.
- Presentation-authority closure discipline applies (measured UX audit,
  auditor ≠ committer).

## §C. Acceptance

A shipped, flag-gated presentation change with a live-verified demonstration
on the dev stack (real model, real corpus — ai-offline-isnt-a-wall); a
measured UX audit; and one honest paragraph on what it does NOT claim
(no calibrated confidence until Q-009 closes).
