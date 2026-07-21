---
title: "harness attribution lane: rank-of-gold capture at run time, USD-binding budgets, schema-stratified records, claim-policy v3 draft — the 766 program's instrument half"
type: tempdocs
status: "chartered (2026-07-21). Founder-run implementation lane."
created: 2026-07-21
author: agent (Fable orchestration), 766 program charter
category: eval-infrastructure / agent-utility
related:
  - 766-eval-content-rebuild-program   # umbrella: D6, D7 — READ FIRST
  - 757-itt-usage-evidence-exhausted-cells  # budget/receipt semantics this extends
  - 755-verified-tool-surface-remedy   # surface-evidence fields adjacent to the new capture
---

> Charter. Execute after 766 §B (D6/D7) and 762 §X.6.4. The exact change
> points were code-verified at research; this lane is small and
> high-leverage — it converts the 763 forensic-replay program into two
> captured fields.

# 768 — harness attribution lane

## §A. Work items

1. **Gold identity into the sample (D6).** Propagate `evidence_ids` (already
   in the queries file) into `Sample.metadata` at
   `agent_utility_inspect.py:1101-1102`.
2. **Rank-of-gold at capture (D6).** Extend `_tool_result_digest_entry`
   (`agent_utility_inspect.py:767-804`, call site `:881-883`) to parse the
   structured `content.results[]` (ids + scores rank-ordered by
   `McpEvidenceProjection.java:72/79`) into `ordered_doc_ids`, `scores`,
   `gold_rank` (null for non-search calls / no gold hit); guard on the
   structured-json delivered tier. Ids+ranks only — never payload text (the
   redaction rationale stands).
3. **USD-binding per-cell budgets (D7).** Make USD the binding per-cell
   budget for campaign chains (wall-clock stays a safety backstop) so every
   exhausted cell retains cost receipts (765 §E: 69/86 lost receipts under
   wall-clock kills; 757's conservative-direction rule remains for the
   backstop path).
4. **Schema-stratified records (D4/D7).** Per-schema strata in the
   comparison record + estimands (ITT / per-protocol / completion triple
   always emitted); dual-budget subsample support for the robustness figure.
5. **Replay tooling first-class pinned adoption.** `serve`-side support for
   adopting a pinned index-cache entry by selector key (763 §F had to
   monkeypatch the selector when HEAD advanced past the campaign commit).
6. **Claim-policy v3 DRAFT.** New strata/schema matrix, rate-based surface
   gate carried over, closed_book-at-hero-tier requirement, triple-reporting
   wording constraints. DRAFT only — ratification (and the v2 orphaning, 766
   §D.3) is a founder action.
7. **Register duty.** `/inference-runtime` and `/search-quality` untouched
   unless findings emerge; observations for out-of-scope finds.

## §B. Acceptance

- A smoke campaign cell (haiku, 1 stratum, few queries, cached index)
  produces per-call `gold_rank` that MATCHES a manual replay of the same
  queries (the 763 replay harness is the oracle — reuse
  `tmp/analysis-624/763/replay/replay_stratum.py`).
- Exhausted-cell receipts: a USD-capped kill retains cost; regression test.
- Record schema changes covered by digest/fixture re-pins where they
  actually move (756 §F method: verify empirically, re-pin only what moves).
- Full jseval suite green (PYTHONPATH per 762 §D; known-RED correction-probe
  pair excepted).

## §C. Constraints

- Small paid-API budget authorized for the smoke cell (order $1); nothing
  else spends.
- Coordinate with 767 on the certification-at-hero-tier run (one shared
  spend decision, founder-gated).
