---
title: "agent tool-surface economy lane: snippet-first results + fetch(doc_id) + passage-span salience — stop search payloads from eating the synthesis budget and returned gold from going unopened"
type: tempdocs
status: "chartered (2026-07-21). Founder-run implementation lane."
created: 2026-07-21
author: agent (Fable orchestration), 766 program charter
category: agent-tool-surface / mcp
related:
  - 766-eval-content-rebuild-program   # umbrella — READ FIRST
  - 765-agent-economics-lane           # §E Results: payload/friction evidence
  - 763-retrieval-attribution-lane     # §F Results: B3-search (returned-unopened) evidence
  - 735-agent-surface-seam-consolidation  # the MCP surface seam being modified
---

> Charter. Independent of 767/768/769; can run in parallel. Evidence is
> banked — this lane designs and ships tool-surface changes, then verifies
> them with a zero/low-spend live probe.

# 770 — agent tool-surface economy lane

## §A. The three banked findings (do not re-derive)

1. **Payload weight**: `justsearch_search` result sets median ~13k chars
   (p95 34k, max 50k); a with-tool cell spends ~15–20k tokens on payloads —
   directly competing with hop-2 synthesis budget at the small-model context
   ceiling (765 §E).
2. **Read-amplification**: `mcp_call_share` ≈ 0.5 — after searching, agents
   re-Read full files (157 full-file Reads at legal-10k, max 92k chars)
   because the returned snippet doesn't carry the answer span; the rare
   with-tool cell deaths are fallback-grep spirals (765 §E).
3. **Returned-unopened**: 11 census cells had gold at rank 1–7 in returned
   results and the agent never opened it (763 §F, B3-search).

## §B. Direction (design is the lane's — constraints only)

- Candidate shape (766 §B.5 / analysis proposals): snippet-first default
  with tight top-k + a `justsearch_fetch(doc_id)` (or equivalent) for full
  text on demand; answer-bearing passage spans in results so the follow-up
  full-file Read is unnecessary; salience presentation so top-ranked hits
  get acted on. Schema changes respect F-016/F-017 (schema complexity
  measurably degrades small-model tool use — keep schemas minimal,
  capability in descriptions/backend).
- The MCP evidence path has one canonical retrieval (F-037's fix) — do not
  fork a second result-shaping authority; shape at the projection seam
  (`McpEvidenceProjection`).
- No adoption scaffolding: first-search-at-turn-1 is already universal
  (765 §E) — do not add "use the tool" prompt engineering.

## §C. Acceptance

- Token-economy measurement: on a cached-index live probe (haiku, ~10
  queries, zero/low spend), per-cell payload tokens drop materially vs the
  765 baseline WITHOUT gold-reachability loss (replay-verify gold still
  reachable through the new surface at the same k).
- The 11 B3-search reproductions: under the new presentation, an agent probe
  opens/uses the top-ranked gold (spot-check subset acceptable; report
  honestly).
- MCP surface contract tests + full suite green; `check-intent-tier-coverage`
  and the wire gate run if their subjects are touched.
- Findings that indict the engine (not the surface) route to the
  search-quality register, not this lane.
