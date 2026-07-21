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

## §H. Live smoke validation (agent, 2026-07-21 — orchestrator forced-USD-exhaustion run)

Smoke: 16 samples (8 queries × 1 seed × A,B), SDK `max_budget_usd 0.06`. Data under
`scripts/jseval/tmp/smoke757/{logs,out,out-itt}`. **Both flagged anomalies are the DESIGNED
behavior; no code change.** The two reported numbers came from the per-protocol `measured`
block, not the ITT primary — a reading-map issue, not a bug.

### H.1 The n=3 / cost-available numbers are the per-protocol secondary, not ITT

Both records carry TWO efficiency views for the one stratum:

| path in `utility-comparison.v1.json` | n_paired | cost_usd | which view |
|---|---|---|---|
| `/estimands/intention_to_treat/strata[0]` | **8** | **unavailable** (anti-conservative) | ITT primary (retains exhausted) |
| `/measured/mixed/en-email-enron-raw-1k-verbose/haiku` | **3** | available (`delta_ci` present) | per-protocol secondary (drops exhausted) |

The reported "n_paired=3" and "cost available" are the `/measured/…` values (verified by
walking both files). This is exactly the sensitivity pair §C asked for: the WITH-truncation
view (ITT: unavailable) and the WITHOUT view (per-protocol: available) are both present.

### H.2 Anomaly 1 (retention) — RESOLVED, no gap

ITT `per_arm_loss`: A `n_completed=3, n_exhausted=5`; B `n_completed=6, n_exhausted=2`;
`n_paired_observations=8` — all 8 pairs retained, exactly the ITT rule. Root evidence
(`read_inspect_observations` over the raw logs): all 7 budget-killed cells have
`error_class=usd_budget_exhausted` and `classify_error_kind → resource_exhaustion`; a sample
carries `cost_usd=0.061236, unique_tokens=14883, usage_truncated=True` (capture worked). The
kill was the SDK `max_budget_usd` → SDK `is_error` ResultMessage with
`subtype=error_max_budget_usd` → `_error_class` (`utility_evidence.py:48-49`) →
`usd_budget_exhausted` → `classify_error_kind` marker match (`utility_governance.py:34`) →
RESOURCE_EXHAUSTION. Retention: exhaustion falls through to `per_protocol_pairs += 1`
(`utility_recompose.py:235`); only `OTHER_ERROR` pairs `continue`/drop (`:228-229`).
**No smoke-vs-campaign kill-path gap for the USD path** — the smoke's SDK budget produces the
exact `error_max_budget_usd` category the campaign path uses. (The wall-clock-cost gap of §D.2
is a DIFFERENT kill path and is unaffected by this smoke.)

### H.3 Anomaly 2 (direction rule) — RESOLVED, fired correctly, no bug

ITT `cost_usd`/tokens = `{available:false, reason:"…anti-conservative"}` — the rule DID fire:
2 truncated B cells tainted the efficiency family. The taint check
(`utility_recompose.py:245`, `if b_exhausted and with_tool.get("usage_truncated") is True`) sits
INSIDE the `for seed, qid in shared` loop (`:209`) AFTER the `OTHER_ERROR … continue` (`:228-229`)
and the `per_protocol_pairs += 1` (`:235`) — so taint is scoped to RETAINED/CONTRIBUTING pairs
only (coordinator reading (i), confirmed): a pair dropped as missing data never reaches the
check and cannot taint. Here all 8 pairs were retained (`n_excluded=0` both arms), so both
truncated B cells contribute and correctly taint. Not case (ii); no fix.

### H.4 Consequence the founder should note (strict rule vs mixed A/B exhaustion)

The rule fails the efficiency family closed on **any** with-tool truncation (parent-brief
directive: a truncated B value can only make B look better → fail closed). So a smoke/campaign
with even a few B-arm exhaustions (this smoke: 2; real legal-1k: 3–11) yields cost UNAVAILABLE —
capture unsticks the efficiency family only when with-tool truncation is **zero** (all exhaustion
in arm A). To demonstrate `benefit`-reachable end-to-end, force **asymmetric** exhaustion (budget
so only the baseline A arm truncates). Whether to keep this strict blanket rule or refine to a
magnitude-aware sign-preservation check (which could admit a B-truncated HARM/NULL conclusion the
blanket rule over-conservatively suppresses) is a founder call — the current implementation is the
safe, brief-mandated choice. <!-- founder-decision -->

## §I. Hardening log (agent, 2026-07-21 — two independent-review findings)

**Design (terse).** Two follow-up findings on the merged §G work; both are the SAME §D.3
conservative-direction seam applied more completely, not new structure. No schema/policy-json
change, no new record fields.

- **Change A — taint on the authoritative flag alone.** The with-tool taint fired on
  `b_exhausted and with_tool.usage_truncated is True` (`utility_recompose.py`, was :245).
  `usage_truncated` is the authoritative lower-bound stamp; `b_exhausted` (error-classification)
  is redundant *today* (classification implies the stamp) but a silent hole if they ever diverge
  — a truncated-but-unclassified with-tool cell (stamp present, `excluded=False` → `classify → None`,
  so it survives the `OTHER_ERROR` drop and reaches the check) would be treated as exact and
  flatter the with-tool arm. Fixed: gate on `usage_truncated is True` alone.
- **Change B — turns/duration deltas fail closed under with-tool truncation.** The §G fail-closed
  override covered `cost_usd` + provider tokens but not `turns` / `duration.delta_mean`, which are
  understated in the with-tool arm by a truncated cell exactly as cost is (`float(None or 0)==0`;
  a lower-bound count/duration flatters a lower-is-better metric).
  - **Fix site.** The ITT stratum record (`utility_recompose`) projects `duration` but **not**
    `turns` (stratum dict has no turns key; `turns` is only surfaced by the pooled `_arm_comparison`
    caller, whose per-protocol pairs exclude all truncated cells). So the consistent, single-site
    fix is in `_stats_from_pairs` itself (`utility_comparison.py`) — where both `turns` and
    `duration` deltas are computed — driven by a new `with_tool_usage_truncated: bool = False`
    kwarg threaded from the recompose stratum flag. `turns` (no censoring context) is withdrawn
    wholesale like cost; `duration` **keeps** its per-arm censored distributions
    (`n_censored`/`completion_rate` from the exhaustion-ITT work) and withdraws only the tainted
    `delta_mean`. Same `{available:false, reason:<anti-conservative>}` shape/reason as cost.

**Byte-identity / derisk.** The kwarg defaults `False`; the pooled/per-protocol callers
(`_arm_comparison`, stratified sub-stats) never pass it, and every pre-757 record has no
`usage_truncated` cell, so the stratum flag is `False` → the new block is inert → **byte-identical,
zero digest re-pins** (guaranteed by construction, confirmed by the unchanged 624 roundtrip digest
test + the 757 baseline-invisibility digest test staying green). Honest boundary: turns-fail-closed
is defense-in-depth — currently a no-op in every *published* surface (ITT omits turns; per-protocol
excludes truncated cells) — but keeps the whole delta family consistent for any future ITT-style
consumer that publishes turns.

**Tests** (`tests/test_partial_usage_capture_757.py`, +4): Change-A stamp-alone taint (with-tool
cell truncated but NOT exhaustion-classified → cost/tokens unavailable, `usage_complete` still
True — fails under the old `b_exhausted` gate, passes under the stamp-alone gate); Change-B
duration `delta_mean` unavailable in-stratum with censoring retained; baseline-truncation duration
stays available (untainted); direct `_stats_from_pairs` unit — truncated ⇒ turns + duration
`delta_mean` unavailable (censoring kept), default ⇒ both available with exact stats. All assert the
anti-conservative reason string, not mere absence.

### I.1 Change table (file:line)

| File | Change |
|---|---|
| `utility_recompose.py:245-250` | Change A: taint gates on `with_tool.get("usage_truncated") is True` alone (dropped `b_exhausted` conjunct) + rationale comment |
| `utility_recompose.py:266-273` | `_stats_from_pairs(...)` call threads `with_tool_usage_truncated=with_tool_usage_truncated` |
| `utility_comparison.py:935-945` | `_TRUNCATED_WITH_TOOL_USAGE_REASON` constant + `with_tool_usage_truncated: bool = False` kwarg on `_stats_from_pairs` |
| `utility_comparison.py:1100-1120` | Change B: fail-close `turns` (wholesale) + `duration.delta_mean` (censoring kept) on with-tool truncation |
| `tests/test_partial_usage_capture_757.py` | +4 tests (Change A + Change B stratum/unit) + `_stats_from_pairs` import |
