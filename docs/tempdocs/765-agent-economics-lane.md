---
title: "agent economics & ergonomics lane: token/time anatomy of all 480 cells, exhaustion post-mortems, MCP friction, A-arm behavior — the empirical inputs for the USD-binding benefit campaign design"
type: tempdocs
status: "chartered (2026-07-21). Awaiting lane orchestrator pickup."
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
