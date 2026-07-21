---
title: "exhausted cells carry no usage evidence → ITT efficiency intervals unavailable → outcomes cap at adoption-only even where ITT accuracy benefit is significant — capture usage for exhausted cells (or pre-register an imputation)"
type: tempdocs
status: "implemented (2026-07-21) — §B option 1 (capture) landed with the conservative-direction check; live forced-exhaustion smoke pending (orchestrator-run). §B option 2 (imputation) untouched, still founder-gated. Wall-clock COST remains uncapturable in the current SDK — flagged founder-decision (§D.2)."
updated: 2026-07-21
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

---

## §D. Design (agent, 2026-07-18 — skill:design pass, source-verified)

### D.1 Where usage is read today, and where exhausted cells lose it (verbatim trace)

- Cost/tokens are read from the terminal Anthropic `ResultMessage` ONLY
  (`agent_utility_inspect.py:965-1006`): `cost_usd = rmsg.total_cost_usd`,
  `unique_tokens = usage.get("cache_creation_input_tokens")`. `AssistantMessage.usage`
  exists (SDK-confirmed: `AssistantMessage` has a `usage: dict|None` field) but carries
  **no cost** — `total_cost_usd` lives on `ResultMessage` alone. No `TaskUsage`/`SystemMessage`
  carries cost either (SDK dataclass probe).
- Two exhaustion shapes lose usage differently:
  - **`usd_budget_exhausted`** — the SDK delivers an `is_error` `ResultMessage`
    (`subtype=error_max_budget_usd`). `cost_usd` and `usage`/`model_usage` ARE captured
    (`:987-990`, `:965-973`), but `unique_tokens` is dropped because the `is_error`
    early-`return` (`:1004`) precedes `:1006`. So usd cells lose ONLY the token field.
  - **`wall_clock_budget_exhausted`** — `asyncio.wait_for` cancels mid-stream, no
    `ResultMessage` arrives → `rmsg is None` → the `else` (`:1007`) sets nothing.
    Both cost AND tokens lost. This is the dominant regime (A-arm wall-clock, 27/60).
- The composed record's `usage_complete` is set in `utility_recompose.py:231`
  (`all(value is not None …)` over `a_cost/c_cost/a_tok/c_tok`) — any `None` flips it False,
  and `:255-261` then stamps both efficiency intervals `{available:false,
  reason:"incomplete ITT usage evidence"}`. Claim policy `:505-536` requires BOTH
  intervals len-2 for any efficiency outcome. `_stats_from_pairs` already coerces
  `None→0.0` (`:964-965,974-975`) and computes the CI regardless — so `usage_complete`
  is the sole availability gate. **This is the one integration point.**

### D.2 What capture can rigorously recover (honest boundary)

| exhaustion shape | tokens (lower bound) | cost (lower bound) | source |
|---|---|---|---|
| `usd_budget_exhausted` | ✅ from `rmsg.usage` (fix the early-return drop) | ✅ `rmsg.total_cost_usd` (spend at cutoff) | authoritative ResultMessage |
| `wall_clock_budget_exhausted` | ✅ per-field MAX over streamed `AssistantMessage.usage` | ❌ **no authoritative source in this SDK** | stream / none |

- **Token capture for wall-clock** uses per-field **MAX** across streamed
  `AssistantMessage.usage` dicts, not SUM. MAX is a valid lower bound under BOTH possible
  SDK semantics: if `usage` is per-turn, `max_turn ≤ Σturns = true`; if cumulative,
  `max = last = true`. SUM would over-count under cumulative semantics → **anti-conservative**
  (over-states A's cost) → rejected. Robust to an assumption I cannot verify offline.
- **Wall-clock cost is genuinely uncapturable** in the current Agent SDK (cost only on the
  terminal ResultMessage, which never arrives on cancel). Pure "capture" (§B option 1) cannot
  produce it without either a hard-coded price table (a drift-prone external fact the repo
  avoids) or cross-cell rate monetization (which drifts toward §B option-2 imputation — the
  founder-gated estimand change). **Both are out of scope for option 1.** Wall-clock-cost
  strata therefore stay fail-closed (unchanged) until option 2 is ratified. Consequence for
  the confirmatory case: capture unsticks the **USD-exhaustion** regime and the token metric
  universally; the **legal-1k wall-clock** regime needs option 2. Recorded as the founder
  decision point, not silently forced green. <!-- founder-decision -->

### D.3 Conservative-direction rule (composer, load-bearing)

Truncated usage is a LOWER BOUND; the efficiency delta is `with_tool − baseline`, lower-is-better.

| truncated arm | effect of treating LB as exact | conclusion effect | composer action |
|---|---|---|---|
| baseline (A) only | understates A → delta ↑ (larger/less-neg) | B looks WORSE than reality | **safe** — treat as exact, interval available |
| with_tool (B), any | understates B → delta ↓ (more-neg) | B looks BETTER than reality | **anti-conservative** — interval UNAVAILABLE (fail closed) |

Implemented as: any paired cell whose **with_tool** side is `usage_truncated` ⇒ force both
efficiency intervals unavailable (distinct reason), exactly as today's None path. A-arm-only
truncation with all values present ⇒ `usage_complete` stays True ⇒ intervals available.
Note: a wall-clock A-cell (tokens present, cost None) still fails the `None` check ⇒ unavailable,
so the missing-wall-clock-cost gap is fail-closed automatically — no special case.

### D.4 Surfaces touched (projection, not fork)

1. `agent_utility_inspect.py` — capture partial usage on exhausted cells; set `usage_truncated`.
2. `agent-utility-observation.v1.schema.json` + `_OBSERVATION_KEYS` — new **optional**
   `usage_truncated` (omitted-when-absent → pre-757 evidence byte-identical; 755 precedent).
3. `utility_evidence.py` (sanitize write + `read_evidence`) + `agent_utility_observations.py`
   (raw path) — thread the flag through both compose paths.
4. `utility_recompose.py:227-261` — the direction check (the only scientific change).
- No new record field, no policy-json change. `usage_complete` semantics gain awareness of
  `usage_truncated` ONLY in the fail-closed (B-arm) direction, per charter constraint.
- Per-protocol secondary is derived from `successful_summaries` which drops ALL errored cells
  (incl. exhausted) — so the without-truncation view is unaffected **by construction**
  (verified: `agent_utility_observations.py` `successful_summaries`; exhausted cells carry
  `excluded=True`). Both with-view (ITT strata) and without-view (per_protocol) remain derivable.

### D.5 Reach / principle

Same shape as the 624 duration family and 755 surface fields: **a truncated/right-censored
measurement is retained as a directional bound, and a bound may substitute for an exact value
only in the direction that cannot flatter the favored arm.** Already instantiated by the
duration family's right-censoring (`_censored_distribution`). Candidate scope: any future
partial-evidence metric. Earns its keep if it lets a real, conservative claim promote that
was previously stuck (USD-exhaustion strata); retire if the estimand ever moves to
imputation (option 2), which supersedes the bound with an assumed ceiling.

## §E. Derisk (agent, 2026-07-18 — confidence 8/10)

Baseline: affected suites GREEN pre-change (`test_duration_exhaustion_624`,
`test_utility_evidence`, `test_utility_comparison`, `test_utility_claim_policy` — 206 passed).
Residual risks + mitigations:
- **R1 byte-identity** — `usage_truncated` emitted only when truthy (conditional add), never
  in the always-present sanitize literal; `read_evidence`/raw-path default to absent→None. A
  pre-757 fixture (no truncated cells) composes identically. Covered by an explicit digest-
  stability test. *Mitigated.*
- **R2 raw↔evidence agreement** — both paths default the flag to None when absent, so
  `test_raw_and_evidence_roundtrip_agree_on_exhaustion` still agrees. *Mitigated, re-run.*
- **R3 SDK per-turn-vs-cumulative `usage`** (live-only, unverifiable offline) — neutralized by
  per-field MAX (valid LB under both). Also inert for the outcome: wall-clock cost stays None
  so the stratum is fail-closed regardless of token capture. Flagged for the orchestrator's
  live smoke. *Bounded, not blocking.*
- **R4 policy blast radius** — no `utility_claim_policy` / policy-json edit; the change is
  isolated to `usage_complete` computation + a fail-closed override in the composer.
  *Contained.*

## §F. Plan

1. `agent-utility-observation.v1.schema.json`: add optional `usage_truncated` (bool|null).
2. `utility_evidence.py`: `_OBSERVATION_KEYS += usage_truncated`; sanitize emits it iff truthy;
   `read_evidence` threads it (default None).
3. `agent_utility_observations.py`: raw path threads `metadata.get("usage_truncated")`.
4. `agent_utility_inspect.py`: MAX-accumulate streamed `AssistantMessage.usage`; on exhaustion
   set partial `usage`/`unique_tokens` (fix usd early-return drop) + `usage_truncated=True`.
5. `utility_recompose.py`: direction check — B-arm truncation ⇒ efficiency intervals
   unavailable (distinct reason); A-arm-only truncation with values present ⇒ available.
6. Tests: `tests/test_partial_usage_capture_757.py` — per-arm direction cases, digest
   byte-identity, per-protocol unaffected, roundtrip; plus a harness capture unit.

## §G. Implementation log (agent, 2026-07-21)

### G.1 The conservative-direction decision table AS IMPLEMENTED

`utility_recompose._intention_to_treat_estimand`. Efficiency delta = `with_tool − baseline`,
lower-is-better; truncated usage = LOWER BOUND.

| paired-cell truncation | `usage_complete` | `with_tool_usage_truncated` | efficiency intervals | reason emitted |
|---|---|---|---|---|
| none (all cells complete) | True (values present) | False | **available** | — (byte-identical to pre-757) |
| baseline (A) arm, values present | True | False | **available** (LB treated as exact — conservative) | — |
| baseline (A) arm, cost=None (wall-clock tokens-only) | False | False | unavailable | `incomplete ITT usage evidence` |
| with-tool (B) arm, values present | True | **True** | **unavailable (fail closed)** | `with-tool usage truncated (lower bound); … anti-conservative` |
| with-tool (B) arm, cost=None | False | True | unavailable | `incomplete ITT usage evidence` (None dominates) |

Baseline-arm safety is automatic: A-arm captured values are non-None so `usage_complete`
stays True and the interval is admitted; only the with-tool arm is explicitly tracked and
fail-closed. No `utility_claim_policy` / policy-json edit — `usage_complete` gains awareness of
truncation only through the fail-closed with-tool override, per charter constraint.

### G.2 Change table (file:line)

| File | Change |
|---|---|
| `agent-utility-observation.v1.schema.json:27` | new optional `usage_truncated` (bool\|null), omitted-when-absent |
| `utility_evidence.py:15` | `usage_truncated` in `_OBSERVATION_KEYS` |
| `utility_evidence.py:270` | sanitize emits `usage_truncated` iff truthy (byte-identity) |
| `utility_evidence.py:339` | `read_evidence` threads the flag (default None) |
| `agent_utility_observations.py:110` | raw path threads `metadata.get("usage_truncated")` |
| `agent_utility_inspect.py:73` | import `classify_error_kind`/`RESOURCE_EXHAUSTION` |
| `agent_utility_inspect.py:433` | `usage_accum` in `_fresh_capture` |
| `agent_utility_inspect.py:461-475` | per-field MAX accumulation of streamed `AssistantMessage.usage` |
| `agent_utility_inspect.py:990` | usd early-return no longer drops `unique_tokens` |
| `agent_utility_inspect.py:1012-1020` | wall-clock (no-rmsg) token fallback from `usage_accum` |
| `agent_utility_inspect.py:556-566` | `solve` stamps `usage_truncated` on exhaustion + surviving usage |
| `utility_recompose.py:203-214` | `with_tool_usage_truncated` init + direction-rule comment |
| `utility_recompose.py:235-241` | with-tool truncation detection |
| `utility_recompose.py:266-282` | fail-closed override + distinct anti-conservative reason |
| `tests/test_partial_usage_capture_757.py` | 10 tests: capture, direction, byte-identity, per-protocol, roundtrip |

### G.3 Verification

- New file: 10/10 pass. Full jseval suite: **2209 passed, 2 failed** — the failures are only
  the pre-existing known-RED `test_correction_probe::TestLoadManifest` pair (data file absent
  from git history), unrelated to this change.
- Digest re-pins: **none.** Byte-identity holds by construction (optional field
  omitted-when-absent; baseline-arm flag is record-invisible) and is asserted by
  `test_baseline_truncation_flag_is_record_invisible` + the raw↔evidence roundtrip digest
  equality. The pre-existing 624 exhaustion roundtrip digest test is unchanged and green.

### G.4 Boundary for the founder (carried from §D.2)

Capture rigorously unsticks the **USD-budget-exhaustion** regime (cost + tokens from the
is_error ResultMessage) and captures a token lower bound universally. **Wall-clock cost stays
uncapturable** in the current Agent SDK (cost lives only on the terminal ResultMessage, which a
wall-clock cancel pre-empts) and is therefore fail-closed — so the legal-1k wall-clock
confirmatory case is NOT unstuck by capture alone; that needs §B option 2 (imputation),
founder-gated. The orchestrator's live smoke should use a tiny **USD** budget to demonstrate the
capture win end-to-end (cost_usd available → benefit reachable); a tiny wall budget will
correctly still show cost unavailable. <!-- founder-decision -->
