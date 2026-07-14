---
title: Agent-eval capture & instrument integrity
type: tempdocs
status: "open — implemented + adversarially reviewed + shipped on branch (chains A+B, commits 0ddcd7e/f71fe4b): tool-result digests, four-state status, errored-cell cost, tombstones, denominators, seed floor; digest-coverage exact-pinned; L1 live cell validated capture end-to-end (one open observation: furniture markers all-False in the child-session cell, extraction verified correct in-process)"
created: 2026-07-14
author: "agent (area agent, tempdoc 725 remediation program)"
related: [725, 624, 719]
---

# 736 — Agent-eval capture & instrument integrity

Area authority for issues **9, 10, 11, 12, 13, 15** of tempdoc 725's consolidated
remaining-issues inventory (2026-07-14, post-A/B). This tempdoc runs the five thinking
phases (theorize → research → design → derisk → plan) and **stops at plan**; no code is
changed here. The metric authority remains tempdoc 624; this area owns the *instrument*
(the jseval agent-utility capture, projection, sanitization, composition, and contrast
code), not the metric definitions.

## Terms used in this document

- **Cell** — one `(condition, seed, query)` unit of an agent-utility campaign. A campaign
  is a cross-product of conditions × queries, repeated over seeds.
- **Capture** — the point where the harness consumes the Claude Agent SDK message stream
  for one cell (`_one_attempt` in `agent_utility_inspect.py`) and stashes SDK objects into
  a `capture` dict.
- **Projection** — `_record_cell` turning the captured objects into `state.metadata` fields
  the composer later reads.
- **Sanitized observation** — the committed, schema-validated `.jsonl` record produced by
  `sanitize_observation` (`utility_evidence.py`), governed by
  `agent-utility-observation.v1.schema.json`. This is durable public evidence.
- **Ephemeral log** — the Inspect run's log directory under `scripts/jseval/tmp/…`, which
  is **gitignored** (`scripts/jseval/.gitignore:4`, confirmed via `git check-ignore`). Raw
  SDK payloads live here and are never committed.
- **Funnel** — the offered → discovered → invoked → reinforced adoption funnel
  (`_funnel_metrics`), computed over *checked* cells (cells that carry the funnel fields).
- **ITT ledger** — the run-governance loss-accounting (`utility_governance.py`,
  `ArmLoss`), which counts every *attempted* cell including the errored/timed-out tail.
- **Exposure contrast** — the descriptive eager-vs-deferred side-by-side over two composed
  records (`exposure_contrast.py`).

The six issues split cleanly into two clusters: a **capture-fidelity cluster** (9, 10, 12 —
information exists in-hand at capture and is dropped) and an **accounting-legibility
cluster** (11, 13, 15 — the record does not declare which denominator, tier, or eligibility
it carries). Both clusters are instances of tempdoc 725's own adopted principle,
*self-describing results*, turned inward on the measurement instrument.

---

## Phase 1 — THEORIZE

### Framings

**F1 — Capture completeness vs log-size / leak discipline (the governing tension).**
Tool result content is the richest signal the harness throws away. The task estimate is on
the order of ten kilobytes per tool call and roughly one-hundred-fifty calls per campaign,
i.e. of order one megabyte of result text per campaign. Two costs, not one:

- *Size*: a megabyte per campaign of raw text, if it were committed, would bloat the
  durable evidence and pollute `semantic_digest`-style content hashes.
- *Leak*: result content on the JustSearch MCP surface **is corpus excerpt text** (search
  results, previews, evidence-pack bodies). Committing it into the sanitized observation
  would put corpus text into durable public evidence — precisely the echo/leak surface the
  level-1 review hardened against (the `toolsearch_targets` redaction contract: only tokens
  that fullmatch the tool-name grammar survive; free text never leaks). Result content is a
  *new* leak surface of the same class.

The resolution is a **two-tier capture**: full content in the ephemeral (gitignored) log,
a *redacted derivation* (hash, length, structural flags, product-furniture-marker booleans)
in the committed observation. This is the same shape the schema already uses for
`instructions_sha256` (hash, not raw instructions) and for `toolsearch_targets` (grammar-
gated names, not raw query text). Truncation-tiers are a weaker alternative to hashing
(a truncated excerpt is still corpus text); a hash plus derived flags carries the analysis
signal with zero corpus bytes.

**F2 — Capture-at-source vs re-derive.** For issue 9 this is not a genuine choice. The
solver runs a **raw `ClaudeSDKClient` inside the Inspect solver** and hand-captures the
message stream (`agent_utility_inspect.py:407-425`); the tool results never pass through
Inspect's own tool executor, so **Inspect's native event/sample store never sees them**.
There is no second store to re-derive from — if the harness does not capture the result at
`_one_attempt`, the information is gone when the SDK session closes. Capture-at-source is
the only option. (This also pre-answers a RESEARCH question: no Inspect config flag can
recover results that never entered Inspect's machinery.)

**F3 — "Every result declares its own nature" applied to the instrument.** Tempdoc 725
adopted *self-describing results* for the product's tool surface. Issues 11/13/15 are the
same principle owed to the *record*: a composed record should declare which denominator is
primary (13), whether it is decision-grade or a smoke (15), and whether it is eligible for
an exposure contrast at all (11). The defect in each is not a missing computation but a
missing *declaration* — the record knows the fact and does not say it.

**F4 — Ordered per-call status supersedes the unordered blocked side-array.** Issue 10 is
that `tool_call_sequence` collapses "returned an error" into the same `blocked` bucket as
"never executed / permission-denied". The ordered sequence, once it carries a distinct
`errored` status, becomes the authority for per-call outcome; the unordered
`tool_calls_blocked` / `blocked_tool_call_names` side-arrays become name-only convenience
projections, not the source of truth. Named as an orphan-of-role in DESIGN.

### Hidden assumptions surfaced

- **A1 — "blocked" means "not executed".** False today: an *executed* tool that returned
  `is_error=True` is routed to `blocked` by `_blocked()` (`agent_utility_inspect.py:545-550`),
  so error-ejection is indistinguishable from a permission denial. Issue 10 rests on
  falsifying A1.
- **A2 — "the funnel denominator is the denominator".** The record silently carries two
  (funnel n_checked; ITT n_attempted). Neither is wrong; the assumption that there is one
  is wrong.
- **A3 — "cost/turns are unavailable on an errored cell".** Partly false. When the SDK
  emitted a `ResultMessage` (even with `is_error=True`), `num_turns`/`total_cost_usd`/
  `usage` are present — they are dropped only because `_record_cell` early-returns before
  populating them. When no `ResultMessage` arrived at all (timeout/exception), they are
  genuinely unknowable. Issue 12 has a recoverable subset and an honest-null subset.
- **A4 — "a single seed is a data point".** Issue 15's 5-of-20 per-query flips between two
  same-config single-seed campaigns show single-seed accuracy is noise-dominated at this
  scale; a single seed is an *anecdote*, not a decision input.

### Principle candidates (with retirement conditions)

- **P1 — Self-describing measurement records.** A composed/contrast record declares its own
  primary denominator, decision-grade tier, and contrast-eligibility, rather than leaving a
  reader to infer them. *Retire when* a downstream schema makes these declarations
  structurally mandatory (so prose declaration is redundant with a required field).
- **P2 — Redaction-preserving capture.** Newly captured payloads enter durable evidence
  only through a redacting projection (hash / length / grammar-gated flags), never as raw
  text; the raw stays in the ephemeral log. *Retire when* the evidence pipeline gains a
  first-class encrypted/segregated raw-payload store with its own access policy (then raw
  capture has a committed home and the projection is a convenience, not a safety boundary).
- **P3 — Recoverable-vs-absent honesty (tri-state).** When a signal is sometimes present
  and sometimes structurally absent, the record distinguishes "measured" from "absent"
  rather than substituting a fabricated zero/null-as-value. *Retire when* the upstream
  producer guarantees the signal is always present (the tri-state collapses to two).

---

## Phase 2 — RESEARCH

**Verdict: a full internet pass is NOT warranted; the load-bearing facts are answered from
installed-SDK source, which is higher-fidelity than published docs.** Two questions were
load-bearing; both are settled:

1. **Does Inspect AI natively store tool results (making issue 9 a config fix)?** No — and
   the question is moot here regardless. This solver bypasses Inspect's tool machinery
   entirely (raw `ClaudeSDKClient`, `agent_utility_inspect.py:407-425`), so results never
   enter any Inspect sample/event store. Confirmed by reading the solver, not by docs.
   Issue 9 is definitively a **code** fix at the capture site, not a configuration flag.

2. **Does the Claude Agent SDK `ResultMessage` carry usage on error (issue 12)?** Yes.
   `inspect.getsource(ResultMessage)` on the installed `claude_agent_sdk` shows
   `num_turns: int` (required), `total_cost_usd: float | None`, and `usage: dict | None`
   as fields of every `ResultMessage`, independent of `is_error: bool`. And
   `ToolResultBlock.content: str | list[dict[str, Any]] | None` confirms the exact shape of
   the payload dropped at `agent_utility_inspect.py:421` (issue 9). Both facts are from the
   installed package, the version the harness actually runs against.

**Why not a broader pass anyway.** A public-repo license-CI constraint applies; only cited
summaries would be permissible, and the binding facts here come from source we already
control. A *narrow, optional* implementation-time confirmation is worth recording — that
the SDK build in use has not renamed `total_cost_usd`/`num_turns` across a version bump —
but that is a one-line `getource`/`dir()` probe at implementation start, not a research pass.
No external sources are needed to proceed to design.

---

## Phase 3 — DESIGN

The design extends the existing four-stage pipeline (capture → project → sanitize+schema →
compose/contrast) using the established 725 pattern: **new fields are schema-optional**
(historical evidence omits them), **the sanitizer always emits them** (null when absent for
fresh captures), and **strictness lives in the claim-policy / composition gate**, not in the
schema's required-set.

### D9 — Capture tool result content (issue 9), two-tier

**Capture (root cause site, `_one_attempt`).** Extend the result stash to carry content and
error together:

```
capture["results"][b.tool_use_id] = {"is_error": bool(b.is_error), "content": b.content}
```

`b.content` may be `str` or `list[dict]` (SDK-confirmed). This is the ephemeral tier — it
lives only in the run's gitignored log via `state.metadata`; it is the raw material for
local retrieval-vs-synthesis and furniture-engagement analysis, read on the machine that
ran the campaign.

**Projection (redacting derivation).** In `_record_cell`, alongside the existing
`tool_call_sequence`, derive a **committed-safe** per-result summary — no raw text:

- `content_sha256` — identity/dedup handle for the result payload.
- `content_len` — character/element count (a size signal; distinguishes empty from non-empty
  results, the retrieval-happened signal).
- `content_is_error` — the `is_error` flag, now first-class (feeds issue 10).
- `content_shape` — a coarse structural tag (`"text"`, `"blocks"`, `"empty"`, `"json"`),
  derived from the type/first-block, never the text.
- `furniture_markers` — booleans for the presence of JustSearch's *own product-emitted*
  furniture line prefixes (rationale / degradation / coverage / evidence-pack header — the
  level-2 constant markers). These are product constants, not corpus text, so detecting
  their presence is leak-safe and directly answers the furniture-engagement question the
  inventory says is blocked.

**Sanitizer + schema.** Add an optional `tool_result_digests` array (one entry per attempt,
carrying the fields above) to `agent-utility-observation.v1.schema.json` with
`additionalProperties: false` on each entry and **no raw-content property permitted** — the
schema structurally forbids committing raw result text. `sanitize_observation` always emits
it for fresh captures (null/empty for historical evidence, per the existing `$comment`
relaxation pattern). `read_evidence` round-trips it.

**Leak boundary (explicit).** The raw `content` never reaches `sanitize_observation`; it is
consumed only from the ephemeral log. The sanitizer sees the derived digest, which by
construction contains no corpus bytes. A regression test asserts that a result whose content
contains a known corpus string produces a sanitized observation whose serialized bytes do
**not** contain that string (the echo-leak assertion, mirroring the level-1
`toolsearch_targets` leak test).

### D10 — Distinguish errored from blocked (issue 10)

Redefine the per-call status in `tool_call_sequence` from three states to **four**, with a
precise partition (`_record_cell`):

- `disallowed` — tool name is in the campaign's disallowed set (unchanged).
- `blocked` — no result arrived (`results.get(tid) is None`) **or** the tool name appears in
  `permission_denials`: the call never executed.
- `errored` — a result arrived with `is_error=True`: the call **executed and returned an
  error** (new; carved out of today's `blocked`).
- `ok` — a result arrived, not an error, not denied.

`_funnel_metrics` semantics are deliberately **unchanged**: `tool_calls` (executed,
non-error) still excludes errored calls, so "invoked" continues to mean *successful*
invocation. The new visibility is that error-ejection is now legible in the ordered
sequence rather than hidden inside `blocked`. The `content_is_error` digest (D9) and the
`errored` status are cross-consistent by construction (both derive from the same
`results[tid]["is_error"]`), which a test asserts.

**Schema.** Extend the `tool_call_sequence[].status` enum to
`["ok", "blocked", "disallowed", "errored"]` (additive; historical records only ever used
the first three, so no migration).

**Orphan-of-role.** The unordered `tool_calls_blocked` / `blocked_tool_call_names`
side-arrays are **superseded in authority** by the four-state ordered sequence: they remain
as name-only convenience projections (kept, not deleted, to preserve the always-emit
contract and existing readers), but the ordered sequence is henceforth the source of truth
for per-call outcome. Documented in the sequence field's `$comment` so a future reader is
not tempted to reconcile two authorities.

### D12 — Preserve num_turns / cost_usd on errored cells (issue 12)

Split by the tri-state (P3):

- **Recoverable subset (a `ResultMessage` arrived, `is_error=True`).** Populate `cost_usd`
  (`total_cost_usd`), `num_turns`, and `usage` **before** the `is_error` early-return in
  `_record_cell` (currently at `agent_utility_inspect.py:668-681`, which returns before the
  success-only populate block at 682-687). The values are on the message; only the
  early-return drops them.
- **Absent subset (no `ResultMessage`: timeout/exception).** Leave `cost_usd`/`num_turns`
  as null — genuinely unknowable from the SDK, so an honest null, never a fabricated zero.
  These cells are counted by the ITT ledger (D13), so campaign spend accounting includes the
  errored tail as *cells whose incremental spend is unmeasured*, rather than silently
  dropping them.

A regression test constructs a fake `ResultMessage(is_error=True, num_turns=N,
total_cost_usd=X)` and asserts the projected metadata carries `N`/`X`; a second test asserts
a no-`ResultMessage` cell carries null (not zero) for both.

### D11 — Pre-#605 records: tombstone, not descriptive comparator (issue 11)

**Decision: tombstone with a documented marker.** Reasoning against the descriptive-
comparator alternative: `exposure_contrast._identity` (`exposure_contrast.py:100-108`)
**requires** cohort-level `exposure_config` + `mcp_initialize_identity`, which are increment-2
fields never captured pre-#605. Even recomposing Campaign D's raw observations cannot
manufacture that exposure identity — the information was never recorded. So the record is
*permanently* ineligible for an exposure contrast, and a "labeled descriptive comparator"
path would have no funnel/adoption/exposure data to place side-by-side; it would be an empty
ceremony. Campaign D's descriptive analysis already happened by hand in the 725 forensics
section and does not route through `exposure_contrast`.

**Design.** Add a small pure predicate
`exposure_contrast_eligibility(record) -> {"eligible": bool, "reasons": [...]}` in
`exposure_contrast.py` that names the specific disqualifier (empty `measured`; absent
`exposure_config`/`mcp_initialize_identity`; pre-#605 provenance). `exposure_contrast` calls
it first and raises an `ExposureContrastError` whose message *names the tombstone reason*
(e.g. "record predates the #605 exposure-identity capture; permanently descriptive-only")
instead of today's generic "has no measured cells" — so a future agent gets a self-
describing failure, not a puzzle. Optionally, `compose_utility` stamps an
`exposure_contrast_ineligible: {reasons, since}` marker onto records it detects as pre-#605
so the ineligibility is visible on the record itself, not only at contrast time.

### D13 — Declare the primary denominator (issue 13)

**Declaration: the ITT ledger (`n_attempted`) is the PRIMARY denominator for spend and
comparability; the funnel's checked-cell count (`n_checked`) is the SECONDARY denominator,
descriptive of behavior conditional on a usable cell.** Rationale: ITT counts the errored
tail and so is the honest base for "what did this campaign cost / how comparable is it"; the
funnel measures behavior *given* a usable cell, whose natural base is checked-cells. Both
answer legitimate but different questions; the defect is the record not saying which is
which.

**Design.** Add a `denominators` block to the composed record (and mirror a one-line note in
each of the `funnel` and governance blocks) that names `n_attempted` (primary/ITT),
`n_checked` + `n_excluded` (secondary/funnel), and states, in one declarative sentence each,
the question each denominator answers. This is P1 applied to composition. No metric value
changes — the numbers already exist in the governance and funnel blocks; the block makes
their relationship explicit and machine-readable.

### D15 — Seed floor as a decision-grade signal (issue 15)

`--seeds` already defaults to 3 (`commands/utility.py:273`), so this is **not** a default
change; the A/B *smokes* ran single-seed and were nonetheless read for accuracy. This is a
protocol + labeling gap.

**Design.**
- Introduce a named `SEED_FLOOR = 3` constant and a composed-record signal:
  when `seed_count < SEED_FLOOR`, the record self-labels (e.g. `seed_floor_met: false` with
  a reason), analogous to the existing `confidence_tier` mechanism.
- **Claim-policy strictness (the established enforcement tier):** `utility_claim_policy`
  refuses to promote an **accuracy-based** claim from a record below the seed floor — a
  single-seed campaign remains publishable as *exploratory/smoke* but cannot back an
  accuracy decision. This mirrors how strictness for the level-1 fields lives in the claim
  policy, not the schema.
- **Docs:** one protocol line stating any accuracy-based decision needs seeds ≥ 3;
  single-seed campaigns are exploratory only. Home: the agent-utility campaign how-to /
  runbook the exposure A/B protocol lives under (the implementer confirms the exact
  canonical file; if none exists, the line rides in tempdoc 725's protocol section, which is
  where the A/B was pre-registered).

### Orphans owned by this design's implementation

1. `tool_calls_blocked` / `blocked_tool_call_names` — role superseded by the four-state
   ordered sequence (D10). Kept as convenience projections; supersession documented in the
   sequence field `$comment`. **Teardown = documentation, not deletion** (deleting would
   break the always-emit contract and existing readers).
2. `exposure_contrast`'s generic "no measured cells" error — superseded by the tombstone-
   specific eligibility error (D11). **Teardown = the generic path now routes through
   `exposure_contrast_eligibility`.**

### Design reach

All six changes are confined to the jseval agent-utility instrument
(`scripts/jseval/jseval/*.py` + the one observation schema). No product code, no Head/Worker
boundary, no network surface — the hard invariants (Head never touches Lucene; loopback-only
network) are untouched by construction; this is offline eval-harness code. The changes are
additive to the wire schema (all new fields optional), so historical evidence keeps
validating.

---

## Phase 4 — DERISK

### Uncertainties, ranked (highest first)

- **U1 (medium) — Does adding `tool_result_digests` change the `semantic_digest` of
  historical composed records?** The 725 pattern is explicit that unconditionally adding
  even a null field to a no-omission-path dict changes the digest of every pre-existing
  record (see the `cell["identity"]` comment at `utility_comparison.py:319-325`). The digest
  boundary must be respected: the new digest field is emitted only where the existing
  conditional-omission discipline permits, and byte-identity of historical records is
  asserted by test. **Retire by:** running the existing composition/digest tests and adding
  a "historical record digest unchanged" assertion before wiring the new field into any
  always-emit path.
- **U2 (medium) — Furniture-marker detection correctness.** The `furniture_markers`
  booleans must key off the *actual* product-emitted constant prefixes (the level-2
  rationale/degradation/coverage/evidence-pack strings), not guessed ones. A wrong-marker
  mistake (the `wrong-gate` failure class) would silently report zero engagement.
  **Retire by:** grepping the level-2 emit sites for the exact marker strings and asserting
  a digest test over a fixture result that contains a known marker.
- **U3 (low) — Four-state status partition completeness.** Every `(tid, entry)` must land in
  exactly one of the four states; the `errored` carve-out must not accidentally reclassify a
  permission-denied call. **Retire by:** a table-driven test over the cartesian of
  {result present/absent} × {is_error T/F} × {denied T/F} × {disallowed T/F}.
- **U4 (low) — Denominator block does not perturb metric values.** The `denominators` block
  must be pure reporting; no metric numerator/denominator is recomputed. **Retire by:** the
  existing funnel/governance value tests staying green with the block added.
- **U5 (low) — Claim-policy seed-floor gate scope.** The floor must gate only accuracy-based
  claims, not harmful-result publication (a valid harmful finding stays publishable even at
  low n — see `utility_claim_policy.py:450`). **Retire by:** a policy test asserting a
  single-seed harmful claim still publishes while a single-seed accuracy claim is refused.

### What static reads + the existing pytest suite settle now

Confirmed during this analysis (no live backend needed): the baseline suite for the touched
modules is green (`python -m pytest tests/ -q -k "utility or exposure or observation or
evidence or record"` → 493 passed; the only red anywhere is the two unrelated
`test_correction_probe` data-file tests, per the repo's known-expected-state register). The
SDK field shapes for issues 9 and 12 are source-confirmed. The gitignore status of the log
dir is confirmed. **None of the six fixes requires a running dev stack, a running model, or
a paid campaign to implement or unit-test** — they are pure capture/projection/schema/
composition changes with deterministic fixtures.

### Live-probe asks (for the orchestrator's stack lease; not blocking implementation)

- **L1 — One tiny real cell to confirm `ToolResultBlock.content` populates as expected on a
  live JustSearch MCP result** (that the digest fields and furniture markers fire on genuine
  product output, not just a synthetic fixture). Smallest possible: a single `(C, seed=0,
  1 query)` cell against the running stack. This is a *capture-fidelity confirmation*, not a
  measurement; cost is one cell. Everything unit-testable is tested with fixtures first.
- **L2 — Optional: a two-seed re-run of a prior single-seed smoke** to demonstrate the
  seed-floor label flips a record from exploratory to decision-grade. Not required for the
  code; purely illustrative. Owner-gated (spend).

No other live probes are needed; issues 11/13/15 are pure record-shape/policy/docs work with
zero live surface.

### Confidence: 8/10

High confidence the diagnoses are correct and the fixes are well-scoped (all six root causes
were read at `file:line`, both SDK facts source-confirmed, baseline green). The reserved two
points are U1 (the digest-boundary discipline is subtle and has bitten this codebase before)
and U2 (marker correctness is a `wrong-gate`-class trap). Both are retired by tests named
above, not by judgment.

### Model / effort recommendation for implementation

- **D9, D10, D12** (capture-fidelity cluster; touch `agent_utility_inspect.py`,
  `utility_evidence.py`, the schema, `agent_utility_observations.py`): one **sonnet** worker,
  bounded, with the digest-boundary and four-state-partition tests as self-verifying
  acceptance criteria. This cluster shares one file region and one review lens.
- **D11, D13, D15** (accounting-legibility cluster; touch `exposure_contrast.py`,
  `utility_comparison.py`, `utility_claim_policy.py`, one doc): a second **sonnet** worker.
  Independent of cluster 1 (different files, no shared state) — the two workers can run in
  parallel.
- Escalate a specific change to **opus** only if U1's digest-boundary test proves the
  interaction with `semantic_digest` is deeper than the conditional-omission pattern handles.

---

## Phase 5 — PLAN

Bounded increments, each with its own verification command. All commands run with
`PYTHONPATH="F:/justsearch-public/.claude/worktrees/725-response-legibility/scripts/jseval"`
from `scripts/jseval`. Increments 1-3 and 4-6 are two independent chains (the suggested
two-worker split); within a chain, order matters.

### Chain A — capture fidelity (issues 9, 10, 12)

**A1 — Capture result content at source (issue 9, ephemeral tier).**
- Edit `_one_attempt` to stash `content` alongside `is_error` in `capture["results"]`.
- Verify: `python -m pytest tests/ -q -k "inspect or record_cell or capture"` stays green
  (no committed-shape change yet; ephemeral only).

**A2 — Four-state per-call status (issue 10).**
- Edit `_record_cell`: add the `errored` state; keep `tool_calls` (executed non-error)
  semantics unchanged.
- Add the derived `content_is_error` to the per-result digest.
- Verify: new table-driven partition test (U3) green; `_funnel_metrics` value tests
  unchanged.

**A3 — Redacting projection + schema + sanitizer (issue 9, committed tier).**
- Add `tool_result_digests` (hash/len/shape/is_error/furniture_markers) to `_record_cell`.
- Extend `agent-utility-observation.v1.schema.json`: optional `tool_result_digests`,
  `additionalProperties:false`, no raw-content property; extend the
  `tool_call_sequence[].status` enum with `"errored"`.
- Extend `sanitize_observation` (always-emit) and `read_evidence` (round-trip).
- Verify: `python -m pytest tests/ -q -k "evidence or observation or schema or leak"` green,
  **including** the new echo-leak test (a corpus string in raw content is absent from the
  sanitized bytes) and the historical-record digest-unchanged test (U1).

**A4 — Preserve num_turns/cost_usd on errored cells (issue 12).**
- Edit `_record_cell`: populate `cost_usd`/`num_turns`/`usage` before the `is_error`
  early-return; leave null in the no-`ResultMessage` path.
- Verify: the two issue-12 regression tests (recoverable → value; absent → null) green.

### Chain B — accounting legibility (issues 11, 13, 15)

**B1 — Exposure-contrast tombstone (issue 11).**
- Add `exposure_contrast_eligibility`; route `exposure_contrast` through it with a
  reason-naming error; optionally stamp `exposure_contrast_ineligible` at compose time.
- Verify: a test feeds a pre-#605-shaped record (empty `measured` / absent exposure
  identity) and asserts the specific tombstone message, not the generic one.

**B2 — Declare primary denominator (issue 13).**
- Add the `denominators` block to `compose_utility`; mirror the one-line note into funnel +
  governance blocks.
- Verify: a compose test asserts the block names `n_attempted` primary and `n_checked`/
  `n_excluded` secondary; existing metric-value tests unchanged (U4).

**B3 — Seed-floor decision-grade signal (issue 15).**
- Add `SEED_FLOOR = 3` + `seed_floor_met` on the composed record; gate accuracy-claim
  promotion in `utility_claim_policy`; add the protocol doc line.
- Verify: policy tests — single-seed accuracy claim refused, single-seed harmful claim still
  publishes (U5); doc line present.

### Cross-cutting closure

- **Full targeted suite:** `python -m pytest tests/ -q -k "utility or exposure or
  observation or evidence or record or claim or governance"` green (baseline was 493 passed;
  expect that plus the new tests, minus zero).
- **Independent review (reviewer ≠ implementer):** per slice-execution honor-system
  guidance, a second agent runs a source-verbatim pass over the digest-boundary (U1) and
  four-state partition (U3) before closure.
- **Live-probe L1** executes under the orchestrator's stack lease as the final capture-
  fidelity confirmation; it is not a gate on the unit-level work.

### Orphan teardown (in the same work, per increment)

- A2/A3 document the `tool_calls_blocked` supersession in the `tool_call_sequence`
  `$comment` (no deletion).
- B1 removes the generic-error path from `exposure_contrast` in favor of the eligibility
  predicate.

### Suggested implementation-subagent split

Two parallel sonnet workers (Chain A, Chain B), each with the acceptance tests above as
self-verifying criteria and the U1/U2/U3/U5 tests named in their brief. The orchestrator
judges returned evidence and commissions the independent review; no worker drives the dev
stack (L1 stays under the orchestrator's supervised lease).
