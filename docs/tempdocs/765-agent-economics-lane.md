---
title: "agent economics & ergonomics lane: token/time anatomy of all 480 cells, exhaustion post-mortems, MCP friction, A-arm behavior — the empirical inputs for the USD-binding benefit campaign design"
type: tempdocs
status: "EXECUTED (2026-07-21, same day — orchestrator-run, pinned opus worker). Cost anatomy + exhaustion taxonomy + friction findings complete; campaign options drafted; see §E Results."
created: 2026-07-21
author: agent (Fable orchestration), founder-directed analysis program (umbrella: 762)
category: eval-analysis / agent-economics / tool-ergonomics
related:
  - 762-agent-utility-analysis-program   # umbrella: priors §P, data §D, constraints §C — READ FIRST
  - 763-retrieval-attribution-lane       # feeds B0 (exhausted-before-retrieval) + B1/B3 ergonomics evidence here
  - 757-itt-usage-evidence-exhausted-cells  # the capture semantics this lane's data rests on
---

> Charter. Execute after reading 762 §P/§D/§C. This lane's product is the cost
> and behavior anatomy of the run: where tokens/time/turns went in each arm,
> what killed the cells that died, where the MCP surface added friction, and —
> as the synthesis deliverable — the concrete design parameters for the next
> (USD-binding, benefit-claiming) campaign.

# 765 — agent economics & ergonomics lane

## §A. Questions

1. **Cost anatomy per arm × stratum**: distribution of tokens (in/out), wall
   time, turn count, and tool-call count per cell; medians + tails. Where does
   the A-arm's budget go at 10k (the completion collapse 0.57–0.58 — is it
   exploratory grep/read churn, as tempdoc 719 hypothesized)? Where does the
   B-arm spend its ~2× completion advantage? Respect 757's capture semantics:
   `usage_truncated` cells have conservative-direction partial usage —
   segment truncated vs complete rather than mixing them.
2. **Exhaustion post-mortems**: for every non-completed cell (both arms), what
   was the terminal state (`error_class`, censoring fields) and what was the
   agent doing in its final turns? Taxonomy: budget-spiral (repeating a failing
   strategy), late-start (wandered before first productive action —
   `first_mcp_call_index` for B), genuinely-too-hard, harness/other. B0 cells
   arrive from 763.
3. **MCP friction**: time/tokens from session start to first productive search
   (`first_mcp_call_index`, `mcp_call_share`, `toolsearch_targets`); retry and
   malformed-call patterns in `tool_call_sequence`; result-payload sizes vs
   context budget (are search results eating the budget that synthesis needs?).
   Each friction finding should name the tool-surface change that would remove
   it (schema, description, result shaping, pagination) — these are engine-side
   ergonomic levers distinct from 763's relevance levers.
4. **A-arm behavior anatomy**: what does a grep/read agent actually do at 1k vs
   10k — strategy census (filename scan, grep-then-read, sampling), and why it
   still wins on email-1k (feeds 764's discrimination question with mechanism
   evidence).
5. **Next-campaign design parameters** (the synthesis deliverable): given cost
   per cell per arm per tier (haiku known; estimate sonnet from token counts ×
   price ratio + the Phase-2 probe cells if usable), produce the cost table for
   the candidate hero campaign: sonnet-class, n from 764's power table, strata
   from 764's email verdict, USD-binding budgets per 757's receipts design.
   Output: 2–3 costed campaign options with expected headline numbers per 762
   §T, ready for founder choice.

## §B. Method notes

- Primary source: per-cell fields in each stratum's
  `out/utility-comparison.v1.json` (762 §D) — this lane is mostly dataframe
  work over committed/on-disk JSON; build one tidy cells table (480 rows v5,
  plus v4 for context) and commit it (CSV) with the tempdoc.
- Transcript reads only where the numbers demand narrative (exhaustion final-
  turns, A-arm strategy census) — sample, don't exhaust; use jseval Inspect
  helpers, never raw-Read the logs.
- Wall-clock caveats: 757 documents which duration fields are trustworthy on
  killed/exhausted cells; read its §semantics before computing time stats.
- Zero API spend expected in this lane (pure analysis); GPU not required.

## §C. Deliverables & acceptance

1. **Cost anatomy tables** (per arm × stratum; truncated segmented) + the tidy
   cells CSV. Acceptance: totals reconcile against the known run spend
   ($89.77) and the per-run records' ledgers.
2. **Exhaustion taxonomy** with counts + ≥2 narrated exemplars per class.
3. **Friction findings**, each with: evidence, proposed tool-surface change,
   expected effect (tokens saved / earlier first-search). Engine-side changes
   get filed as observations or register entries per 762 §C.5 — not fixed here.
4. **Costed next-campaign options memo** (2–3 options: model tier, n, strata,
   budgets, projected cost, expected headline shape per 762 §T).
5. **§L rows for 762** for every lever ≥ moderate expected effect.
6. Implementation log here; out-of-scope finds → observation shards.

## §D. Constraints & practicalities

- Inherit 762 §C. step2-powered worktree read-only; jseval env per 762 §D.
- Cost projections must state price assumptions with dates/sources; the memo's
  dollar numbers are planning figures, not commitments — the founder sets the
  actual cap at pre-registration.
- Coordinate with 763/764 through 762 §L rows, not by editing their tempdocs.

## §E. Results (2026-07-21)

Artifacts: `tmp/analysis-624/765/` (`cells.csv` 960 rows = 480 v5 + 480 v4,
`summary_by_arm.csv`, `campaign_cost_projection.csv`, scripts). Extraction
validated against all five 762 §P priors exactly. Charter correction: the
per-cell fields live in the Inspect logs' `state.metadata`, not the per-run
record (762 §D inaccuracy, same as 763's).

**Cost anatomy.** The with-tool arm is CHEAPER per cell in every stratum
(mean capturable $0.197 vs baseline $0.280) by converging in ~half the turns
(medians 27→14 legal-1k, 27→10 email-1k). Reconciliation: SDK per-cell costs
sum to $96.86 over 411 capturable cells vs the $89.77 ledger (~+8% SDK
price-table skew); 69 wall-clock-cancelled cells carry null cost by design
(757 §D.2) — segmented, never imputed.

**Exhaustion taxonomy: one dominant class.** All 86 non-completions are
budget exhaustions (zero max-turns/API/harness deaths): 17 USD-exhausted
(receipts retained), 69 wall-clock (receipts lost), concentrated in the
baseline arm at 10k (23+23 cells). Final turns are pure grep-reformulate →
read-one-candidate churn; no cell reached synthesis; 719's exploratory-churn
hypothesis confirmed with transcripts. Rare B-arm deaths are the agent
ABANDONING the tool for filesystem grep. No late-start class exists.

**Friction findings.** (1) No adoption latency — `first_mcp_call_index`
median AND p95 = 1 in all strata; do not add "encourage tool use"
scaffolding. (2) Search payloads eat the synthesis budget: median ~13k
chars/result-set, ~15–20k tokens/cell on payloads → snippet-economy +
`fetch(doc_id)` lever (tempdoc 770). (3) `mcp_call_share` ≈ 0.5 — the B-arm
re-Reads full files after search (157 full-file Reads at legal-10k, max 92k
chars) → passage-span/salience lever (770).

**Campaign options (DRAFT; price basis 2026-06; n finalized by 764 power).**
haiku actual $0.187/cell; sonnet intro (≤2026-08-31) ~$0.37, standard ~$0.56;
opus ~$0.93 — price-ratio lower bounds. Option A (recommended): sonnet,
legal 1k+10k, both arms, 240 cells ≈ $90 intro / $135 standard. Option B:
legal-10k only at larger n. Option C: full 4-strata replication ≈ $180/$270.
All superseded in the strata dimension by the 767 rebuild — re-costed at 766
pre-registration. USD-cap (not wall-clock) is the standing recommendation so
100% of cells keep receipts.
