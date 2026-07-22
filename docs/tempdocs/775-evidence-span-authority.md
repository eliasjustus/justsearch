---
title: "evidence-span authority + delivery governor: one canonical answer-bearing-span representation for search excerpts, RAG passages, and the MCP preview — plus graceful degradation at the delivery cap; absorbs 771's two surviving items"
type: tempdocs
status: "chartered (2026-07-22). Near-term lane; absorbs tempdoc 771 items 1b (evidence-content excerpt gap) and 4 (response-size governor). Pre-hero-campaign: any surface change here bumps cohort identity, so this lands BEFORE the hero pre-registration or not at all."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 762→771 measurement program's inventory (founder-directed 2026-07-22)
category: search-engine / evidence-delivery / agent-tool-surface
related:
  - 771-post-rebuild-retrieval-residue   # items 1b + 4 transfer here; measurements banked in its §E
  - 770-agent-tool-surface-economy-lane  # the delivery reshape this builds on; truncation cap characterized
  - 749-rag-chunk-retrieval-fallback-bug # F-038 union leg — the RAG passage machinery to conform to
  - 774-passage-first-retrieval-program  # the deep sibling; this lane is the delivery half, buildable first
---

> Charter. Evidence is banked (771 §E, 770 §E.3) — this lane designs and
> ships; re-measurement is only needed where the design changes what 771
> measured.

# 775 — evidence-span authority + delivery governor

## §A. The two banked defects (from 771, transferred)

1. **Evidence-content gap (771 item 1b).** Delivered search excerpts carry
   the answer-relevant entity in 93% of successful enron retrievals but only
   **45% on legal** — long CLERC docs bury the key sentence at median offset
   ~5,000 chars, past the ~4KB preview. On the hardest domain, even
   successful retrieval often cannot seed the agent's next step. Same class
   as the deferred read-amplification item: agents re-Read full files
   because the delivered span isn't answer-bearing.
2. **Response-size cliff (771 item 4).** Raw payloads overshoot the
   46.6–52.8k truncation threshold (770 §E.3) at realistic limits: up to
   288KB at limit 30; reachable at limit 10 on legal. The truncation notice
   delivers neither content tier. Residual from 771: whether 770's gated
   delivery degrades gracefully at the cliff is unverified.

## §B. The structural problem (why one lane, not two patches)

Three span-selecting systems exist and drift independently: search excerpt
regions (IDF-weighted, worker-side), the RAG chunk/passage machinery
(F-038's union leg, citation resolution), and the MCP preview/snippet
(770's reshaped delivery). Each answers "which part of this document is the
evidence?" with different code. The lane's job is ONE span authority —
selection that is answer-bearing (query-term AND entity coverage, not just
lead/IDF), sized for delivery, consumed by all three surfaces — plus a
governor that degrades deterministically at the cap (drop tail results,
never truncate mid-payload). Projection-vs-fork discipline applies
(execution-surfaces register; conform to F-038's machinery, don't fork a
fourth system).

## §C. Acceptance

- The 771 §E probes re-run green: legal evidence-carriage materially up from
  45% (target: parity-with-enron band, measured not asserted); no
  gold-reachability loss at the same k.
- Governor: at limit 30 on legal, delivery degrades by result-count
  reduction with an explicit notice — never a mid-payload truncation; the
  770 golden/totality guards extended to the governor path.
- Perf ratchets green; MCP contract tests + full suites green; cohort bump
  (TOOL_SURFACE_VERSION) landed once, deliberately, pre-hero.
- 771 closed: its status updated to fully-dispositioned on this lane's
  opening (done in the same PR as this charter).

## §D. Constraints

F-016-as-weak-prior (770 §A.4): schema changes arguable on merit; capability
lives in selection/shaping, not new required parameters. D-005: span
selection reacts to query + document content, never corpus identity.
