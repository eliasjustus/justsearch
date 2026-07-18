---
title: "exhausted cells carry no usage evidence → ITT efficiency intervals unavailable → outcomes cap at adoption-only even where ITT accuracy benefit is significant — capture usage for exhausted cells (or pre-register an imputation)"
type: tempdocs
status: "open — charter (2026-07-18). Delegable; imputation rule (if capture proves impossible) is a pre-registration change and needs founder ratification."
created: 2026-07-18
author: agent (Fable orchestration), chartered after the 624 confirmatory campaign; founder-directed handoff (2026-07-18)
category: eval-infrastructure / agent-utility / estimands
related:
  - 624-agentic-retrieval-eval-rebuild   # §Confirmatory RESULTS — fourth structural finding; also §Post-hoc sensitivity analysis (the ITT rule's origin)
  - 755-verified-tool-surface-remedy      # sibling gate remedy; a full relaunch waits on BOTH
---

> Charter. The `benefit` outcome tier is unreachable until this closes.

# 757 — ITT usage evidence for exhausted cells

## §A. Problem (measured, 2026-07-18)

The `benefit` outcome requires `efficiency_benefit`, which requires `usage_complete`
(`scripts/jseval/jseval/utility_claim_policy.py:505-536`). Under the pre-registered ITT rule
(resource-exhaustion-as-failure), exhausted cells stay in the paired analysis as
attempted+incorrect — but they carry no token/cost usage evidence, so the composed record
reports `cost_usd: {available: false, reason: "incomplete ITT usage evidence"}` and every
efficiency interval is unavailable. Confirmatory consequence: legal-1k had ITT accuracy
Δ=+0.217 (p=0.00098) yet resolved `adoption-only` — the accuracy benefit cannot promote as
`benefit` without the efficiency family. At 10k the baseline arm exhausts 45% of cells, so
this is not an edge case; it is the dominant regime exactly where the product story lives.

## §B. Fix shape (in preference order)

1. **Capture**: an exhausted cell is still a real API session — the SDK/CLI usage accounting
   up to the kill point exists. Persist partial usage (tokens, cost) for wall-clock/USD
   exhausted cells at sanitize time, flagged `usage_truncated: true`. Truncated usage is a
   LOWER BOUND, which is conservative in the only direction that matters for the B-favoring
   efficiency claim when the exhausted cells are concentrated in arm A (they were: 27/60 A vs
   3–11/60 B) — but the composer must treat lower-bound usage as exact only when the sign of
   the conclusion is unaffected; otherwise mark the interval unavailable as today.
2. **Pre-registered imputation** (only if capture is genuinely impossible): e.g. exhausted
   cell usage := its arm's calibrated per-cell budget ceiling. This changes the estimand
   definition → pre-registration amendment → founder ratification. <!-- founder-decision -->

## §C. Acceptance

- Unit: fixture with one exhausted-with-partial-usage cell per arm → efficiency intervals
  available, `usage_complete` semantics documented; roundtrip digest invariant updated.
- Sensitivity note in the record: efficiency intervals with and without truncated-usage cells
  must both be derivable (the per-protocol secondary already gives the without-view).
- Live: recompose a smoke campaign with forced exhaustion (tiny wall budget) → `cost_usd`
  available, outcome can reach `benefit` when accuracy is noninferior and efficiency CI < 0.
