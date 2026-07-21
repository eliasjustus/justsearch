---
title: "verified_tool_surface is structurally unsatisfiable at current SDK flake rate — capture hardening first, staged policy amendment as the ready-to-ratify fallback"
type: tempdocs
status: "RATIFIED 2026-07-21 (founder-authorized): the rate-based verified_tool_surface amendment is now the ACTIVE policy (agent-utility-public-v2); the .proposed draft is deleted, folded into utility-claim-policy.v1.json — see §J. Track 1 capture-hardening + Track 2 evaluator support both IMPLEMENTED and merged prior."
created: 2026-07-18
author: agent (Fable orchestration), chartered after the 624 confirmatory campaign's rejected verdict; founder-directed handoff ("proceed accordingly", 2026-07-18)
category: eval-infrastructure / claim-policy / agent-utility
related:
  - 624-agentic-retrieval-eval-rebuild   # §Confirmatory campaign RESULTS — the rejection this remedies
  - 725                                   # exposure-identity capture increments; the flaky get_mcp_status finding's home lane
  - 675                                   # original SDK mcp-status flake observation
---

> Charter. Verify every number below against
> `scripts/jseval/624-run-2026-07-18-confirmatory/combined-3stratum-VERDICT.utility-comparison-cross-corpus.v1.json`
> before building.

# 755 — verified_tool_surface remedy

> **RATIFIED 2026-07-21 (founder-authorized) — see §J.** The Track-2 rate-based amendment is now
> the ACTIVE policy `agent-utility-public-v2` (`scripts/jseval/utility-claim-policy.v1.json`); the
> `utility-claim-policy.v2-draft-755.proposed.json` draft has been DELETED (folded into the active
> file). Dated implementation-log references to the `.proposed` draft in §C–§H below are preserved
> as history — the file no longer exists; §J is the authoritative forward record.

## §A. Problem (measured, 2026-07-18)

The active claim policy (`agent-utility-public-v1`) gate `verified_tool_surface`
(`scripts/jseval/jseval/utility_claim_policy.py:394-406`) requires EVERY attempted with-tool
cell to carry an observed MCP surface hash equal to the declared hash. The per-cell hash is
captured at `scripts/jseval/jseval/agent_utility_inspect.py:816-855` and is `None` whenever the
agent SDK's `get_mcp_status()` returns nothing — a known-flaky signal (675/725). Confirmatory
campaign observation: 4–12 of 60 B-cells per stratum unverified (~8%/cell), 15/180 in the
composed record. P(all ~240 B-cells verified) ≈ 0 at that rate → **no campaign can promote
under the ratified policy as-is**. Data quality was otherwise perfect (0 exclusions, adoption
1.0), and `observed_mcp_tool_surface_consistent` was true — every cell that DID report a
surface reported the same single hash.

## §B. Track 1 — capture hardening (default; no policy change; delegable now)

Reduce the per-cell miss rate as far as engineering allows:

1. Retry `get_mcp_status()` with short backoff inside the cell capture (the call is
   read-only; N=3 retries is cheap relative to a 195s cell).
2. Fallback evidence path: if status stays empty but the cell's transcript contains ≥1
   executed `mcp__justsearch__*` call, capture the offered-surface listing from the SDK's
   tools-listing seam (or the `tool_call_sequence` + declared-surface cross-check) and record
   HOW the hash was obtained (`surface_evidence: "status" | "retry" | "fallback-listing"`) so
   the record distinguishes first-class from fallback verification.
3. Instrument the miss rate: emit `cells_mcp_surface_unverified` per run (already aggregated,
   `agent_utility_observations.py:286-338`) into the run-end summary line so campaigns see the
   rate live.

Acceptance: a 3-seed smoke campaign (any 1k dataset, ~$3) reports 60/60 verified B-cells, or
the residual miss rate is measured and documented as irreducible.

## §C. Track 2 — staged policy amendment (ready-to-ratify draft; DO NOT activate)

If Track 1 leaves a residual miss rate, draft (as a `.proposed` sibling file, never editing the
active policy in place): `verified_tool_surface` becomes
`minimum_surface_verification_rate: 0.9` AND `observed_mcp_tool_surface_consistent == true`
AND single observed hash == declared hash AND zero cells with a *different* hash (a missing
hash is a capture miss; a different hash stays fatal). Include the measured-flake evidence and
the exact diff in the draft header.

## §D. Founder gate <!-- founder-decision -->

Activating any amendment to the ratified policy requires the founder's explicit word. Nothing
in §B needs it.

## §E. Verification

- Unit: fixture with a missing-hash cell → gate outcome matches whichever rule is active;
  roundtrip digest invariant test updated only if the record schema gains `surface_evidence`.
- Live: the §B smoke campaign; `python -m jseval utility-recompose` on its logs must show the
  gate green (or the measured residual rate if Track 2 becomes necessary).

---

## §F. Track 1 implementation — design (2026-07-21, worktree agent-a273…)

Source-verified against `main` base (f192f25e 756 + 52a8178f 758 present).

### Capture flow (verbatim)
- Per-cell MCP surface is captured by `_mcp_surface(client)` (`agent_utility_inspect.py:285-338`),
  called once at the end of `_one_attempt` (`:445-446`) inside the `ClaudeSDKClient` block, under the
  cell's single `asyncio.wait_for` budget. It calls `client.get_mcp_status()` and returns
  `(servers, js_tools, js_surface)`; `(None, [], [])` on exception / non-dict / no-non-null-key.
- `_record_cell` (`:816-892`) turns that into `observed_mcp_tool_surface_hash` (declared hash iff
  `observed_names == declared_names`, else a recomputed hash, else None) and the tri-state
  `mcp_surface_unverified` flag. Gate `verified_tool_surface` (`utility_claim_policy.py:394-406`) —
  **untouched**.

### Design decisions
1. **Retry (item 1).** Widen `_mcp_surface(client, with_tool)` into a bounded 3-attempt loop with
   ~1s `asyncio.sleep` backoff, retrying ONLY when `with_tool` and the justsearch surface is still
   empty (condition-A `servers==[]` is legitimate → never retried). Returns a 4-tuple adding
   `surface_evidence ∈ {"status","status-retry",None}`: `"status"` = populated on attempt 1,
   `"status-retry"` = populated on a later attempt, `None` = never populated. Read-only, ~≤2s worst
   case vs a ~195s cell.
2. **Fallback = documented-unverified (item 2, integrity rule).** Investigated the SDK for an
   independent tools-listing seam: `get_server_info()` returns the cached `initialize` result
   documented for *commands/output-styles* only (`client.py:546-569`), NOT the MCP tool surface;
   `get_context_usage()` issues a fresh control request — the SAME flake surface as `get_mcp_status`.
   **No genuine independent seam exists.** The only remaining evidence is the declared-surface
   cross-check: executed `mcp__justsearch__*` names ⊆ declared surface names. That proves the executed
   tools were *members* of the offered surface, but NOT that the *full* offered surface equalled the
   declared surface (unexecuted extra tools and per-tool schemas are unobservable; the declared hash
   fingerprints name+description+input_schema of the whole set). Per the charter integrity rule this
   **cannot** equate observed hash to declared hash → the cell stays **unverified** (`surface_evidence`
   absent, `observed_mcp_tool_surface_hash` None, gate semantics unchanged). The cross-check is
   recorded as forensic metadata `mcp_surface_fallback` (`{executed_justsearch_subset_of_declared,
   verified:false, reason}`). `"fallback-listing"` stays in the enum for forward-compat but is never
   emitted by this code (documented) — no seam to emit it from.
3. **Aggregation (item 3).** Add `cells_by_surface_evidence` (per-kind counts incl. an `unverified`
   bucket) to each condition aggregate in `all_attempt_tool_call_assertions`
   (`agent_utility_observations.py:273-346`), **conditionally omitted** when no observation in the
   condition carried `surface_evidence` — mirroring the 725 `exposure_*` / `agent_cohort_key`
   precedent (`agent_manifest.py:186-196`) so every historical/replay record stays byte-identical.
   Surface the per-run B-arm unverified count + evidence breakdown in the `utility-run` run-end
   summary (`commands/utility.py:419-431`).
4. **Schema/digest (item 4).** `surface_evidence` + `mcp_surface_fallback` become optional
   observation fields (schema `agent-utility-observation.v1.schema.json`, `_OBSERVATION_KEYS`,
   `sanitize_observation`/`read_evidence` roundtrip). Because the record-level `cells_by_surface_evidence`
   is conditionally omitted for pre-755 evidence, **no historical fixture digest changes** — the pinned
   `test_historical_fixture_semantic_digest_repinned_after_624_itt_change` digest and the roundtrip
   invariant both hold with zero re-pins expected. (`tool_call_assertions` is opaque `{"type":"object"}`
   in `utility-comparison.v1.schema.json:210` — no comparison-schema change.)

### Orphans
None. Retry + fallback + evidence-kind counts are purely additive; nothing is superseded or removed.
The gate and its threshold are deliberately left intact (charter §B: raise capture, don't lower the bar).

### Derisk (2026-07-21) — confidence 8/10
Uncertainties reduced by source-reading:
- **`_mcp_surface` signature widen** — single internal call site (`:445-446`); `_record_cell` consumes
  the `got` dict, not `_mcp_surface`. Existing `_record_cell` tests pass a `got` lacking
  `surface_evidence` → implementation reads `got.get("surface_evidence")` (default None). LOW risk.
- **Digest stability** — conditional omission mirrors the 725 precedent; must confirm by running
  `test_historical_fixture_semantic_digest_repinned…` + `test_evidence_roundtrip_preserves_semantic_digest`
  (expect green, zero re-pins). If a re-pin IS forced, follow the §336 re-pin procedure and report old→new.
- **Schema roundtrip fail-closed** — `read_evidence` rejects unknown observation keys (`utility_evidence.py:274`),
  so a missed `_OBSERVATION_KEYS`/schema entry fails LOUDLY in the roundtrip test, not silently. Add
  `surface_evidence` + `mcp_surface_fallback` to all four: schema, `_OBSERVATION_KEYS`, `sanitize_observation`,
  `read_evidence`. MEDIUM risk, loud-failing.
- **Retry budget** — retry runs AFTER `receive_response()` completes, ≤~2s of `asyncio.sleep`, under the
  existing per-cell `wait_for`. LOW risk.
Difficulty: moderate — 6 code files + 1 schema + tests, each edit small, semantics clear, guards fail-closed.
Model: opus-level care warranted for the integrity/digest nuance (being done directly by the assigned agent).

### Plan (execution order)
1. `agent_utility_inspect.py`: widen `_mcp_surface(client, with_tool)` → 4-tuple w/ retry+backoff;
   thread `with_tool` at call site; add `surface_evidence` to `_fresh_capture`; in `_record_cell`
   set `state.metadata["surface_evidence"]` and the `mcp_surface_fallback` cross-check.
2. `agent_utility_observations.py`: carry `surface_evidence`/`mcp_surface_fallback` into the observation
   dict; add conditionally-omitted `cells_by_surface_evidence` to `all_attempt_tool_call_assertions`.
3. `utility_evidence.py` + `agent-utility-observation.v1.schema.json`: optional fields through
   sanitize/read + `_OBSERVATION_KEYS`.
4. `commands/utility.py`: run-end unverified-count + evidence breakdown line in `utility-run`.
5. Tests: retry path, fallback (documented-unverified), unverified-preserved; run full jseval suite.
6. `utility-claim-policy.v2-draft-755.proposed.json` (Track 2 draft, inert) + prove no selector loads it.

### Reach / principle
This conforms to two principles already load-bearing in this file rather than forking them:
(a) **honest-null / never-manufacture-verification** (the tri-state `mcp_surface_unverified` and the
P3 "honest null, never fabricated zero" comments throughout `agent_utility_inspect.py`) — the fallback
refusing to verify from execution alone is the same principle applied to a new signal; (b)
**conditional-omission for digest stability** (725 `exposure_config` at `agent_manifest.py:186` /
`agent_utility_observations.py:291-297`) — a new derived field must be OMITTED, not null-filled, for
evidence that never captured it. Candidate wider scope: any future per-cell capture signal added to the
observation record. Earns-its-keep evidence: historical fixture digests stay green across the change;
retire the pattern only if the record schema ever adopts explicit versioned migration (making
byte-identical historical replay a non-goal).

## §G. Track 1 — implementation log (2026-07-21)

### Change table (file:line)
| File | Change |
|---|---|
| `scripts/jseval/jseval/agent_utility_inspect.py:85-92` | New retry constants `_MCP_SURFACE_PROBE_ATTEMPTS=3`, `_MCP_SURFACE_RETRY_BACKOFF_S=1.0`. |
| `…/agent_utility_inspect.py:295-374` | `_mcp_surface(client, with_tool)` → 4-tuple; bounded retry loop (retries only a with-tool empty surface); returns `surface_evidence`. |
| `…/agent_utility_inspect.py:481-484` | Call site threads `condition in _WITH_TOOL`, unpacks `surface_evidence`. |
| `…/agent_utility_inspect.py:405-407` | `_fresh_capture` seeds `surface_evidence: None`. |
| `…/agent_utility_inspect.py:903-932` | `_record_cell` sets `state.metadata["surface_evidence"]` + integrity-checked `mcp_surface_fallback` (documented-unverified). |
| `…/agent_utility_observations.py:124-128` | Observation dict carries `surface_evidence` + `mcp_surface_fallback`. |
| `…/agent_utility_observations.py:293-299, 330-345, 342-350` | Aggregate `cells_by_surface_evidence` (per-kind incl. `unverified`), conditionally omitted for pre-755 evidence. |
| `…/utility_evidence.py:23-24, 169-186, 240-241, 337-338` | `_OBSERVATION_KEYS` + sanitize/read roundtrip for both new fields. |
| `…/agent-utility-observation.v1.schema.json` | Optional `surface_evidence` (enum) + `mcp_surface_fallback` (object) properties. |
| `…/commands/utility.py:432-445` | `utility-run` run-end summary emits per-run `mcp-surface[B/C]: N unverified cell(s) evidence={…}`. |
| `…/utility-claim-policy.v2-draft-755.proposed.json` | Track 2 DRAFT (inert, unratified). |
| `…/tests/test_agent_utility_inspect.py` | +6 tests (retry status/status-retry, no-retry-for-A, exhausted-unverified, fallback-documented-unverified, unverified-preserved); updated the pre-existing tri-state test to the new 4-tuple signature. |
| `…/tests/test_utility_evidence.py` | +2 tests (conditional emission + digest stability; unverified bucket). |

### `surface_evidence` semantics (exact conditions)
- `"status"` — `get_mcp_status()` reported the justsearch surface on the FIRST probe.
- `"status-retry"` — first probe empty; a bounded reprobe (≤2 retries, ~1s backoff) recovered it. Fires only for with-tool cells.
- `None`/absent (UNVERIFIED) — no probe reported a surface. Gate treats a missing hash as a capture miss (unchanged). When the cell nonetheless executed ≥1 `mcp__justsearch__*` tool, `mcp_surface_fallback` records the executed-⊆-declared cross-check with `verified: false` — the integrity rule forbids equating the observed hash with the declared hash from execution alone (a subset cannot prove the full offered surface; schemas/extra tools unobservable; no independent tools-listing seam exists).
- `"fallback-listing"` — reserved in the enum for a future genuine seam; NEVER emitted by this code.

### Digest re-pins
NONE. Conditional omission keeps every pre-755 record byte-identical: `test_historical_fixture_semantic_digest_repinned_after_624_itt_change` and `test_evidence_roundtrip_preserves_semantic_digest` pass unchanged.

### Constraint compliance
- Gate `utility_claim_policy.py:394-406` UNTOUCHED (capture rate raised, bar not lowered).
- Draft policy inert: `policy_path()` hardcodes v1; no glob/selector references the `.proposed` file (grep-proven).
- Verification tier: unit tests + offline replay only; no dev stack / live campaign / API spend. Live 3-seed smoke is the orchestrator's follow-up (charter §B acceptance / §E).

### Critical-analysis pass (post-impl, 0 actionable findings)
- Retry gate fires in the target scenario (set-site verified: `:484` passes `condition in _WITH_TOOL`; loop guards `with_tool` so A is probed once, never retried into a false surface).
- Honest-null preserved: `surface_evidence` defaults None and is overwritten only after a successful `receive_response`; timed-out/errored cells stay None → unverified.
- Integrity: `mcp_surface_fallback.verified` is always False and never sets the observed hash — unverified cells still fail the untouched gate.
- Test precision: the conditional-omission test asserts omission-when-absent AND digest-equality (None-key vs key-stripped) AND digest-change-when-present — passes for the right reason.

## §F. Track-1 acceptance smoke — MEASURED VERDICT (2026-07-21, orchestrator-run)

60 B-cells (email-1k, haiku, 3 seeds, B-only, no calibration; ~$8 incl. a duplicate-run
incident — an orphaned first attempt survived a kill and eval_set completed its retry; single
complete 60-sample log finalized): **55 verified / 5 unverified (8.3%)**,
`cells_by_surface_evidence = {"status": 55}` — **zero `status-retry` rescues in 5
opportunities**. The flake is per-session, not per-call: within-session reprobes never recover
a missing surface. Track 1 therefore CANNOT reach 100%; the ~8%/cell residual is irreducible
at the current SDK seam (consistent with the campaign's 4–12/60 per stratum).

**Verdict: the §C policy amendment is the necessary remedy.** The draft
(`scripts/jseval/utility-claim-policy.v2-draft-755.proposed.json`, rate ≥ 0.9 + single-hash
consistency + different-hash fatal) would have passed this smoke at 91.7% with one consistent
hash equal to declared. Activation remains founder-gated <!-- founder-decision -->. The Track-1
code stays: retry is harmless, and `surface_evidence` provenance + per-kind assertion counts
are what make an amended rate-based gate auditable. Ops note filed: Inspect eval_set task
retry can legitimately produce a second log file; `loss_accounting_from_observations` fails
closed on the duplicate (correct), so recompose after any retried run must finalize from the
surviving complete log.

## §H. Track 2 — evaluator support (pre-ratification) — implementation log (2026-07-21, worktree agent-a99c…)

Chartered under §C + §F: §F's measured verdict is that the amendment is necessary (8.3% irreducible
miss). This lands the evaluator semantics NOW, **inert under v1**, so ratification (§D founder gate) is
a config flip, not new code. Base verified: `git log -1` on the draft file = 29ad7f6d (the 755 Track-1
merge); v1.json + publication pointer untouched.

### Design (terse)
`evaluate_claim` gets a rate branch keyed on `thresholds.minimum_surface_verification_rate`. Absent
(v1) → the existing all-cells-verified expression is left byte-for-byte, emitting `observed=bool,
threshold=True` — required because `claim_verdict` is digest-covered (`test_utility_evidence.py:397`).
Present (v2 draft) → `verified_tool_surface` passes iff: cells_total>0 AND cells_total==attempted
with-tool cells AND rate(=verified/total)≥threshold AND `observed_mcp_tool_surface_consistent` is True
AND exactly one distinct observed hash == declared cohort hash AND no different hash (declared present).
No fork: this reuses the same `with_tool_assertions` rollup + `attempted_with_tool` the v1 gate already
reads; only the pass predicate + observed shape change.

### Derisk (terse) — confidence 9/10
- **Byte-stability** — the only risk. v1 path is textually unchanged inside an `if min is None:` guard;
  proven by the pinned historical digest (`test_historical_fixture_semantic_digest_repinned_after_624_itt_change`)
  and the active-policy pin both passing UNMODIFIED. LOW.
- **different-hash-fatal independence** — asserted directly: a second distinct hash fails at rate 1.0
  even with `observed_mcp_tool_surface_consistent` forced True (`single_declared`/`no_different` catch it
  without leaning on the consistency flag). LOW.
- **threshold whitelist** — thresholds are not validated against a whitelist (only `requirements` are, vs
  `SUPPORTED_REQUIREMENTS`), so the new key needs no schema/enum change. Verified by reading the
  `supported_policy_requirements` gate. LOW.
(Skill pipeline note: design/derisk/plan produced terse inline — the semantics were fully specified by
§C, so the value is the appended reasoning, not plan-mode ceremony.)

### Change table (file:line)
| File | Change |
|---|---|
| `scripts/jseval/jseval/utility_claim_policy.py:394-448` | `verified_tool_surface` gate split on `thresholds.minimum_surface_verification_rate`: `None`→ unchanged v1 all-cells-verified expression (`observed=bool, threshold=True`); present→ rate branch (rate≥threshold + single-declared-hash + no-different-hash), `observed={"rate","verified","total"}`, `threshold=<rate>`. |
| `scripts/jseval/utility-claim-policy.v2-draft-755.proposed.json:83-93` | `verified_tool_surface_semantics` block re-aligned to the implemented conditions (added the cells_total==attempted clause + a `gate_observed_shape` + `evaluator_status` field; refreshed `evidence`/`activation_note`). v1.json + pointer untouched; file NOT renamed. |
| `scripts/jseval/tests/test_utility_claim_policy.py:698-…` | +5 tests: v1 gate stays bool + fails on unverified cell; rate passes @0.92 (observed exposes rate); fails @0.88; second distinct hash fatal @rate 1.0; single hash≠declared fails. |

### Gate observed shape (rate branch)
```
{"name": "verified_tool_surface",
 "observed": {"rate": <verified/total float>, "verified": <int>, "total": <int>},
 "threshold": <minimum_surface_verification_rate>,
 "passed": <bool>, "reason": ""}
```
v1 branch is unchanged: `"observed": <bool>, "threshold": true`.

### Verification
- `tests/test_utility_claim_policy.py` + `tests/test_utility_evidence.py`: 107 passed (was 102; +5 new).
  The digest-pinned fixture test and the active-policy pin passed **UNMODIFIED** (byte-stability proof).
- Full jseval suite: 2214 passed, 2 failed — both the pre-existing known-RED
  `test_correction_probe.py::TestLoadManifest` pair (missing data file, expected-state.v1.json). No new failures.
- Offline only; no dev stack / API spend. Draft remains inert (`policy_path()` hardcodes v1; grep of
  `scripts/jseval/jseval` for `v2-draft-755` = 0 hits).

## §J. RATIFIED (2026-07-21, founder-authorized)

Founder authorization on record (2026-07-21): *"fix the loose ends you mentioned. then ill authorise
you to ratify and swap what you mentioned. once nothing else remain, you can launch"* — the
ratification target defined in §C/§F here and in the 624 relaunch pre-registration (launch-gate (1),
merged #260). The §D founder gate is thereby satisfied, and launch-gate (1) of the 624 relaunch
pre-registration is marked SATISFIED.

### What changed
- **Active policy swapped in place.** `scripts/jseval/utility-claim-policy.v1.json` now carries
  `policy_id: agent-utility-public-v2`, `thresholds.minimum_surface_verification_rate: 0.9`, and the
  `verified_tool_surface_semantics` block (evaluator-aligned; the draft-only `draft_note` /
  `evaluator_status` / `activation_note` fields stripped). `status` stays `active`; the required
  four-stratum matrix, all other thresholds, and requirements are BYTE-UNCHANGED from the v1
  activation. `schema`/`schema_version` stay `utility-claim-policy.v1` / `1` (same governing schema).
- **Draft deleted.** `scripts/jseval/utility-claim-policy.v2-draft-755.proposed.json` removed — its
  purpose (a ready-to-ratify fallback) is served. Per retire-with-a-sweep, the `.proposed` /
  `v2-draft-755` references remaining in §C–§H are dated implementation-log history, labeled at
  document scope by the banner under the H1 and this section; the file itself no longer exists.
- **Schema extended.** `utility-claim-policy.v1.schema.json` gained optional
  `thresholds.minimum_surface_verification_rate` (number 0–1) and top-level
  `verified_tool_surface_semantics` (object) so the ratified shape validates (both `additionalProperties:
  false` scopes previously rejected the new keys). Schema is not digest-covered — no verdict moves from
  this.
- **Evaluator unchanged.** The rate branch (`utility_claim_policy.py:394-449`, merged inert in #257)
  now fires against the checked-in active policy because the threshold key is present. No code change was
  needed to activate it — ratification is the config flip §H predicted.

### Digest re-pins (old → new) — SAME class as the #243/624 activation re-pin (cited precedent)
`claim_verdict` carries digest-covered `policy_id` + `policy_hash`, so swapping the active policy moves
any record's composed digest.

| Artifact | Old | New |
|---|---|---|
| Active policy digest (`policy_digest`) | `80b9a6b9c52bbac515084732cf1f95b96c3369d96a7f6c9656b4a0210244d86e` (v1) | `b3697c7aa49e171c405f42cac7cbbda2396f71eb5edf3ea55136455006c6e01e` (v2) |
| Rejected-2026-07-12 fixture `semantic_digest` (`test_historical_fixture_semantic_digest_repinned_after_624_itt_change`) | `2be7446c70b1177353a0c2f1bf127d4d2519299fc4c1d5166ec1ed26bf4071f5` | `3d0bf53b1f205bd8e811cd55c74255664468dddda9dd70b689bcb2fbd390f62b` |

The fixture stays REJECTED (its strata cannot match the confirmatory matrix); only its digest moved.

### Tests
- `test_checked_in_policy_is_active_confirmatory_four_stratum` re-pinned to the v2 shape (`policy_id ==
  agent-utility-public-v2`, `minimum_surface_verification_rate == 0.9`).
- New `test_checked_in_active_policy_evaluates_surface_via_rate_branch` (task-4): the CHECKED-IN active
  policy's `verified_tool_surface` gate evaluates via the rate branch (observed `{rate, verified,
  total}`, threshold `0.9`), not only the synthetic `_rate_policy` fixture.
- `test_verified_tool_surface_v1_gate_is_bool_and_fails_on_unverified_cell` reconstructs a v1-shaped
  policy (pops the rate threshold) so the still-live None-threshold bool branch keeps coverage.
- Affected files: 108 passed. Full jseval suite green except the pre-existing known-RED
  `test_correction_probe` pair.
