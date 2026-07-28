---
title: "hero campaign pre-registration protocol — the binding measurement contract the 766 program closes with"
type: tempdocs
status: "DRAFTED, NOT FROZEN (2026-07-27) — §E carries the six frozen-section drafts; 11 PENDING-AT-FREEZE items indexed in §E.7. FREEZES when both triggers land: distribution readiness (760) + rebuilt corpora fully-certified (781 §E.4). Founder decision 2 (766 §G) is the authority: sonnet-class, enron-1k/10k + legal-1k, n=60/stratum, USD-binding, cap ~$300, legal-10k excluded."
created: 2026-07-22
updated: 2026-07-27
author: agent (Fable orchestration), chartered from the 2026-07-22 remaining-work map (founder-directed)
category: eval-campaign / pre-registration
related:
  - 766-eval-content-rebuild-program   # "program closes with the hero campaign's pre-registration"
  - 781-corpus-rebuild-v2-camouflaged-metadata  # corpus prerequisite
  - 760-installer-distribution-readiness       # trigger prerequisite (founder lane)
  - 771-post-rebuild-retrieval-residue         # item 3 (at-capture instrumentation) lands here
  - 765-agent-economics-lane                   # pre-flip read-amplification baseline for §B.4
  - 624 (relaunch pre-registration)            # the precedent protocol shape
---

## §A. Purpose

One document that makes the hero run auditable before it is expensive: every rule that could
be bent after seeing results is written and frozen before any paid call. Precedent: the 624
relaunch pre-registration (adoption-only target, budget guard, honest-abort semantics) — this
protocol inherits that shape and adds what 624's post-mortem and the 767/774 leak history
taught.

## §B. Contents to draft (each becomes a frozen section)

1. **Design:** strata (enron-1k/10k, legal-1k; legal-10k EXCLUDED per decision 2 — the
   representation floor is 783's problem, not a claim), n=60/stratum, judge=sonnet,
   cheapest-first ordering, USD cap ~$300 with the 624-style guard + pre-registered abort.
2. **Validity rules decided blind:** the leak checks the probe history mandates (a
   measurement probe without pre-registered validity rules once reported its own leak as a
   win — agent-lessons 2026-07-22); identity gates; corpus-signature pins to the 781 v2
   commitments; single-cohort requirement; verified_tool_surface rate ≥ 0.9 (624 policy).
3. **Judge validity:** a pre-run judge-agreement spot-check (sonnet vs held-out human/founder
   adjudication on a small sample, 764's method) with a pre-registered agreement floor;
   below floor → the run does not start.
4. **At-capture instrumentation (771 item 3 lands here):** per-query rank-of-gold, span/
   evidence carriage, tool-call counts, and the READ-AMPLIFICATION after-measure — 765
   baselined agents re-Reading full files pre-flip; the hero captures the post-flip
   distribution for free. Also exhaustion/duration ledgers per 624.
5. **Claim grammar:** exactly what sentence(s) the run can promote, with confidence
   intervals (bootstrap over queries, reported alongside every headline number), and what
   verdicts are possible (accepted / adoption-only / rejected — 624's ladder). No public
   number leaves 623's founder-gated pipeline (decision 3).
6. **Spend + incident ledger format**, resume-safety rules, and the two-failed-relaunches
   stop rule (inherited from the 624 supervision spec).

## §C. Acceptance

- Protocol frozen (this doc's freeze commit named) BEFORE the first paid call; any later
  edit to frozen sections voids the run's claim eligibility.
- Both triggers verified at freeze time: installer artifact exists (760); 781 cohort
  fully certified incl. gold-selectivity.
- The run itself is a separate execution record (new tempdoc or 766 closing section) citing
  this protocol by commit.

## §D. Notes

- Founder-gated actions stay founder-gated: launching the paid run, publishing any number
  (623), and any cap increase.
- The protocol should be short enough to be read before every supervision shift; verbosity
  here is a validity risk, not thoroughness.

---

# §E. THE PROTOCOL (draft 2026-07-27 — **NOT FROZEN**)

> **Freeze mechanics.** This §E is the frozen artifact. It freezes when a founder-authorized
> commit (a) resolves every `PENDING-AT-FREEZE:` marker in §E.7, (b) records the freeze commit
> SHA in §E.0, and (c) verifies both triggers (760 installer artifact exists; 781 cohort
> `fully-certified`). Until then §E is a draft and **no paid call may be made**. After freeze,
> any edit to §E.1–§E.6 voids the run's claim eligibility; amendments made *before the first
> measured cell* follow 624's dated-amendment convention and must be appended, never inlined.

## §E.0 Freeze record

| field | value |
|---|---|
| freeze commit | `PENDING-AT-FREEZE: <sha>` |
| freeze timestamp (UTC) | `PENDING-AT-FREEZE:` |
| trigger 1 — 760 installer artifact | `PENDING-AT-FREEZE: <artifact + run id>` |
| trigger 2 — 781 cohort `fully-certified` | `PENDING-AT-FREEZE: <member.v1.json status per member>` |
| active claim policy at freeze | `PENDING-AT-FREEZE: <policy_id>` (see §E.2 P0) |
| launch authorization | founder-gated; recorded in §E.6 ledger, never here |

## §E.1 Design (frozen section 1)

**Authority: founder decision 2 (766 §G). Nothing in this section may be re-optimized.**

- **Strata (3, all `verbose`):**

  | # | stratum | dataset | corpus_member | size |
  |---|---|---|---|---|
  | 1 | enron-1k | `mixed/en-email-enron-raw-1k-verbose` | `en-email-enron-raw` | 1000 |
  | 2 | enron-10k | `mixed/en-email-enron-raw-10k-verbose` | `en-email-enron-raw` | 10000 |
  | 3 | legal-1k | `mixed/en-legal-clerc-1k-verbose` | `en-legal-clerc` | 1000 |

- **legal-10k is EXCLUDED** — not in `required_strata`, not run, not reported, not a control.
  Reason (771 §E M5): true retrieval floor 0.82–0.90 there, so a delta measures corpus
  difficulty, not the tool. Adding it back is a founder decision, not a supervision-shift one.
- **Model:** sonnet-class for both arms (`requested_model: "sonnet"`); every cell must resolve
  to one `resolved_provider_model`. haiku is **not** run in this campaign.
- **Arms:** A = file tools only; B = A + JustSearch MCP. Primary estimand `addition_b`, ITT
  (resource-exhaustion-as-failure) primary, per-protocol secondary.
- **n = 60 paired observations per stratum** = 20 committed qids × 3 seeds `{0,1,2}` × 2 arms
  = **120 cells/stratum, 360 cells total**. The v2 cells carry 50 queries each; the 20 are
  selected by a deterministic committed rule, never by hand.
  `PENDING-AT-FREEZE: the 20-qid list per stratum + its sha256, and the selection rule.`
- **Ordering: cheapest-first** — enron-1k → enron-10k → legal-1k. (624 confirmatory amendment 1:
  the max-extrapolation guard over-projects when the most expensive stratum calibrates first;
  the fix is order, not cap.)
- **Budget:** hard cap **$300 USD**, running guard `python scripts/jseval/step2-budget-guard.py
  --cap 300` (max-extrapolation over `cost_estimate_usd` of known calibrations) before each
  stratum launch. **Binding limit is USD, never wall-clock** (765 §E: 69/86 wall-clock kills lost
  their cost receipt; USD kills retain them).
  `PENDING-AT-FREEZE: per-cell max_budget USD from fresh sonnet calibrations; concurrency.`
- **No partial-value ladder.** `required_strata_exact` means a 2-stratum record cannot promote,
  so a projected over-cap → **ABORT the whole campaign and report** (624 confirmatory §Budget).

## §E.2 Validity rules decided blind (frozen section 2)

Every rule below is evaluable mechanically, before any result is interpreted. **P-rules are
pre-launch (fail → do not launch); R-rules are per-run/at-compose (fail → the affected stratum
is void, see §E.6).**

**P0 — policy identity.** ✅ **RESOLVED 2026-07-28 by the §G ratification.** The active policy is
`scripts/jseval/utility-claim-policy.v3.json` (`policy_id: agent-utility-public-v3`,
`status: "active"`), and it declares exactly the §E.1 three strata with `requested_model: "sonnet"`,
`query_count: 20`, `seed_ids: [0,1,2]` — see §G for the verbatim block. The pre-launch check is:
that file is the one `utility-claim-policy.*.json` with `status: "active"`, its `unresolved` is `[]`,
and its `required_strata` match §E.1. `utility-claim-policy.v1.json` is now
`status: "superseded"` (it is history, not the operative policy) and the v3 DRAFT file — whose
matrix contradicted decision 2 — is deleted.

**P1 — corpus_signature pins (v2 cohort, 781 §E.2).** Each stratum's computed
`corpus_signature` must equal, byte for byte:

| stratum | `corpus_signature` | `query_gold_sha256` (verbose) |
|---|---|---|
| enron-1k | `3391fc9781c6dafc4881322337558810b739b1a1d55a4eac5e1f035bec18cff7` | `1138c58c809859a5fb8e4dcd6fe54845d67834e474d7d49be7a2144db3584352` |
| enron-10k | `6a80af3b5bdbb59d578eedfd00a4c35815ffcf07450afce2de73d4dd818399cb` | `1138c58c809859a5fb8e4dcd6fe54845d67834e474d7d49be7a2144db3584352` |
| legal-1k | `6df707031abcd296773a0bf8c6a7750bb0b8704ce4ab4035105cf88b8df01fae` | `2797469a5f80aa8881553323880cd910dd83b621dabd935e7d577337f0f3f565` |

Read from `scripts/jseval/781-corpora/<member>/structural-certification.v1.json`; asserted
against the composed record's `strata[*].corpus.corpus_signature`. A mismatch is fatal — never
"the corpus moved, re-pin the protocol". `PENDING-AT-FREEZE: re-confirm these three signatures
are unchanged by 781 §E.4 (they hash corpus.jsonl+qrels only, so they should not move; verify,
do not assume).`

**P2 — gold-selectivity certification.** Every stratum's member must be `fully-certified`
(`781-corpora/<member>/member.v1.json`), and the `field_selectivity` gate must be PASS on
**every field × every cell** with `max_field_separability <= native_base_rate`. A
`structurally-certified` member is NOT sufficient — that is 781's current state, and the whole
point of the trigger. This is the direct guard against the title-presence leak class (774 §J.7).

**P3 — closed-book at hero tier.** Closed-book accuracy measured **at sonnet** on each stratum
must be `0.000` (781 §C). Any non-zero value means the answers are derivable without the corpus
→ do not launch. ✅ **The two-number conflict is RESOLVED (§G, 2026-07-28):** the ratified
`maximum_closed_book_accuracy` is `0.1`, and it is a post-hoc failure **CEILING** enforced by the
`closed_book_at_hero_tier` gate — it does **not** relax this P-rule. The pre-launch bar stays
`0.000` at sonnet. Read it as: below 0.000 to launch; above 0.1 voids the benefit headline for
that stratum even if it somehow launched.

**P4 — probe-self-leak checks (the 2026-07-22 lesson: a probe without pre-registered validity
rules reported its own leak as a win).** All four run pre-launch over committed artifacts, output
committed to the run dir:
  1. **Arm symmetry** — `prompt_template_hash` and the instruction text are byte-identical
     across arms A and B; the arms differ *only* by MCP tool availability. Any arm-specific
     prompt content voids the design.
  2. **No gold text in the prompt** — for every selected qid, the question text contains neither
     the gold answer string nor any gold doc id, and no gold-only *field* (title, author, entity)
     is injected into either arm's context. This is the exact shape of the discarded
     title-prepend arm.
  3. **Answer-key path isolation** — the agents' `--add-dir` resolves to the exploded
     `corpus-dir` only; `qrels`, `fabricated-queries.json`, `fabricated-meta.json` and
     `commitment.v1.json` are outside every readable root. Verified by path check pre-launch and
     by `leak_suspect_tool_calls == []` on every cell at compose.
  4. **Shipped-config parity** — `captured_search_config` equals the shipped product default
     cohort (post-775 flip). No eval-only boost, field, or limit. We measure the product users
     get (766 §G decision 1).

**P5 — scorer/judge floor.** §E.3 must be green. Below floor → the run does not start.

**R1 — identity gates (per run + at compose).** One `search_config_cohort_key`, one
`agent_cohort_key`, one `cli_version` for the whole campaign, `git_dirty: false`, clean committed
tree, corpus-dir derivation check enforced in root mode (624 §Identity hardening), exposure
identity carried through compose.

**R2 — `verified_tool_surface`** (policy `agent-utility-public-v2` semantics, carried into v3):
rate ≥ **0.9**, exactly one observed hash equal to the declared cohort hash, **any single
different hash is fatal regardless of rate**, a missing hash after retry+fallback is a capture
miss that lowers the rate only.

**R3 — data-quality thresholds** (from the active policy `thresholds`, not restated as new
numbers): `minimum_seeds` 3, `minimum_paired_observations` 54, `maximum_exclusion_rate` 0.15,
`minimum_paired_retention` 0.7, `minimum_excluded_jaccard` 0.5, `significance_alpha` 0.05,
`minimum_adoption_rate` 0.9. Plus `no_leak_suspect_cells`, `contamination_class:
private-synthetic`.

**R4 — single cohort, one compose.** All three strata compose in ONE
`utility-recompose` invocation. No retroactive stratum substitution; a voided stratum is rerun
under the SAME cohort window or the campaign records `rejected` (624 relaunch §Analysis plan).

**R5 — nothing changes after the first measured cell.** Strata, n, qid list, arms, model,
thresholds, α, estimand, cap. Any post-hoc change → the run is not claim-eligible, full stop.

## §E.3 Scorer / judge validity + pre-registered agreement floor (frozen section 3)

**Scorer identity first.** 764 §E established that the production scorer is `substring_scorer`
(gold string ⊆ answer), **not** an LLM judge — and that its full-string requirement was the only
thing neutralizing the v1 naming leak. So the audit's shape depends on what is active at the
freeze commit. `PENDING-AT-FREEZE: read the scorer wired in the jseval Inspect task at the freeze
commit and record it here verbatim (file:line).` Then:

- **If `substring_scorer` is active** — run the *derivability* audit instead of an agreement
  audit: for every selected qid, assert the gold answer string is **not** derivable from any
  token in the question or from any entity name in the corpus metadata (the
  `corpus_generate.py:320-323` naming-leak class, 764 §E). Floor: **0 derivable qids**. Any hit →
  the qid is replaced from the committed pool *before* launch, and the replacement is recorded.
- **If an LLM judge is active** — run the pre-registered agreement spot-check below.

**Agreement spot-check (method: 764 §A.2 — zero-cost first, paid only for tiebreaks).**

- **Sample:** 40 completed cells, stratified: ≥10 per stratum, balanced across arms A/B, and
  balanced across scored-correct / scored-incorrect. Drawn with a seed fixed here
  (`random.Random(2026)`), from a **pre-hero pilot or banked cells** — never from the hero cells.
- **Reference:** founder adjudication (held-out human), rubric held fixed — the rubric is
  measured, never redesigned in this audit (764 §B).
- **Floors, both required:** exact agreement **≥ 0.95** (≥38/40) **AND** at most **1**
  disagreement in the direction that favours arm B. An arm-asymmetric error pattern is the
  failure mode that manufactures a win, so it carries the tighter floor.
- **Below either floor → the run does not start.** No remediation-then-relaunch inside the
  campaign; a failed audit sends the scorer back to design and this section is re-frozen.
- Cost: the local model (`ai_activate`, ~11s load) does the bulk pass at zero API cost; paid
  sonnet is used only to break local-vs-original disagreements (764 §D).

## §E.4 At-capture instrumentation (frozen section 4) — 771 item 3 lands here

Everything below is captured **during** the hero cells; none of it costs an extra paid call.
Raw tool content is never committed — digests only (`_tool_result_digest_entry`,
`agent_utility_inspect.py:934`).

**Per search call** (`state.metadata["tool_result_digests"][*]`, 768 D6):
`ordered_doc_ids`, `scores`, `gold_rank` (honest `None` when gold is absent — **never a
fabricated 0**), `delivered_tier`, `delivered_fields`, `component_bytes`.

**Per cell:** `tool_calls`, `tool_call_sequence`, `tool_calls_blocked`,
`disallowed_tool_calls`, `leak_suspect_tool_calls`, `mcp_tools_offered`,
`observed_mcp_tool_surface_hash`, `usage`, `cost_usd`, `unique_tokens`, `turns`,
`usage_truncated`.

**Derived at compose (committed as JSON in the run dir):**

1. **Rank-of-gold distribution** per stratum × query: median/p90 `gold_rank`, and the
   share of search calls with `gold_rank is None`.
2. **Evidence/span carriage**: share of B cells whose delivered payload carried the
   answer-bearing span (post-775 flip); `delivered_tier` census.
3. **Read-amplification after-measure (771 item 3).** From `tool_call_sequence`, per B cell:
   (a) count of filesystem `Read` calls occurring after the first `justsearch_search` call;
   (b) the share of those Reads whose target is **not** in any preceding `ordered_doc_ids`;
   (c) `mcp_call_share`. **Pre-flip reference (765 §E / 770 §A.2, different cohort, model and
   corpora): `mcp_call_share` ≈ 0.5, and 50.3% of post-search Reads (N=862) targeted documents
   search never returned.** This after-measure is **pre-registered as DESCRIPTIVE ONLY** — it is
   not a comparison, not a claim, and no delta against the pre-flip reference may be promoted.
   Naming this before launch is what stops "we cut re-reads by X%" from appearing afterwards.
4. **Exhaustion ledger** per stratum × arm: `n_attempted / n_completed / n_exhausted /
   n_excluded / n_pending`, `per_arm_loss`, plus the 765 §E taxonomy split —
   **USD-exhausted (receipts retained)** vs **wall-clock-cancelled (cost null by design)**.
   Null costs are **segmented, never imputed** (757 §D.2).
5. **Duration ledger** per stratum × arm: median + p95 wall-clock with an explicit censoring
   flag; efficiency intervals **fail closed** wherever with-tool truncation exists — that is a
   pre-registered accepted outcome, not a defect (624 relaunch §Registered target).

## §E.5 Claim grammar (frozen section 5)

**Verdict ladder (624's, unchanged).** The composed record's `claim_verdict` is the verdict; no
human re-reads it into something better.

| verdict | when | what may be said |
|---|---|---|
| `accepted` / outcome `benefit` | all gates pass **and** efficiency intervals are available and direction-checked | the accuracy/efficiency sentence in the templates below |
| `accepted` / outcome `adoption-only` | all gates pass, efficiency intervals unavailable (truncation) | the adoption sentence only — **no numeric benefit language** |
| `rejected` / `inconclusive` | any gate fails | nothing. The measured numbers stay in this tempdoc as history |

**Confidence-interval convention (applies to every headline number).** Report a **95% cluster
bootstrap CI over queries**: resample **qids with replacement, carrying each qid's 3 seed
replicates as a cluster** (seeds within a qid are not independent), 10,000 resamples, BCa,
`random.Random(2026)`. Report it *beside* the exact-McNemar p (α = 0.05) — both, always. If the
two disagree, both are reported and the disagreement is stated; it is never resolved post hoc.

**Power honesty line, mandatory beside every null (764 §E).** At n = 60 pairs, exact McNemar has
power 0.89 for Δ = 0.20 (discordant rate 0.25) and is underpowered below Δ ≈ 0.15 (n@80%: 92 for
Δ = 0.15, 208 for Δ = 0.10). A null is therefore reported as **"no effect detected at n = 60"**,
never as "no effect".

**Triple reporting (v3 `triple_reporting_semantics`).** The ITT number is the headline; the
per-protocol accuracy **and** the completion rate are published beside it; the per-cell budget is
stated explicitly.

**Forbidden headlines** (any occurrence makes the statement non-promotable):
- raw absolute accuracy of a weak arm presented alone;
- any ratio off a near-zero base — an infinite/undefined multiplier must be reframed as
  completion-rescue or absolute lift;
- adoption rate presented as benefit;
- a null stratum as a headline;
- **anything about legal-10k** (excluded per §E.1);
- **any read-amplification delta** (§E.4 item 3 is descriptive only);
- any number without its CI and its estimand named.

**Publication.** No number leaves the 623 founder-gated pipeline (766 §G decision 3): immutable
publication manifest + owner-selected pointer. Drafting a public sentence is not publishing it;
publishing is founder-only, per action.

## §E.6 Spend + incident ledger, resume-safety, stop rules (frozen section 6)

**Run directory:** `scripts/jseval/782-run-<YYYY-MM-DD>-hero/` — per-stratum
`utility-comparison.v1.json`, the single combined cross-corpus record, all calibrations, the
chain script, the §E.2 pre-launch check outputs, the §E.4 derived JSONs, and both ledgers below.

**Spend ledger** (`spend-ledger.md`, appended at every phase boundary — before each calibration,
before each stratum launch, after each stratum completes):

| ts (UTC) | phase | stratum | planned $ | actual $ | cumulative $ | guard projection $ | headroom vs $300 |
|---|---|---|---|---|---|---|---|

Actuals reconcile against the per-run records' ledgers at close; an unreconciled gap is reported,
not smoothed (765 §E found a ~+8% SDK price-table skew — report the skew, don't pick a number).

**Incident ledger** (`incident-ledger.md` — 624's confirmatory night logged six):

| id | ts (UTC) | what fired | cohort-affecting? | remedy | $ consumed | stratum voided? |
|---|---|---|---|---|---|---|

Every guard that fires gets a row, including guards that fired correctly. A fired guard is
evidence, not an obstacle — never hand-patch an identity to get past one.

**Resume-safety.**
- Resume uses Inspect `eval_set` durable per-cell resume keyed on `sample_id`, into the **same**
  `log_dir` with **byte-identical task args**. A changed task arg creates a new run and **splits
  the cohort** — treat it as a failed relaunch, not a resume.
- After any resume, re-assert R1 (single `search_config_cohort_key` / `agent_cohort_key` /
  `cli_version`) before continuing.
- Calibrations are never adopted across git SHAs or CLI versions (624 incident #5: a banked
  calibration from another SHA split the cohort and the recompose refused).
- The harness window runs with `DISABLE_AUTOUPDATER=1`; `cli_version` is recorded at start and
  re-checked at every phase boundary. A mid-campaign CLI change voids every cell after it (624
  incident #6).

**Stop rules (all mandatory; each is a full stop, not a judgment call).**
1. **Two-failed-relaunches** — a stratum may be relaunched at most **twice** inside the cohort
   window. On the second failed relaunch of **any** stratum the campaign STOPS and records
   `rejected`. No third attempt, no stratum substitution, no cohort-window extension.
2. **Guard over-cap** — projected total > $300 → ABORT the campaign (§E.1: no partial ladder).
3. **Pre-launch failure** — any P0–P5 check red → do not launch.
4. **Fatal identity** — a different observed tool-surface hash (R2), or a mid-campaign cohort
   split that a resume cannot repair → stop and record.
5. **Post-first-cell change request** — any request to change a frozen parameter mid-campaign →
   stop, record, escalate to the founder. Not an amendment; the amendment window closed.

**Founder-gated, never agent-initiated:** launch authorization, any cap increase, any
publication, any change to §E.1's design parameters, policy ratification.

**The execution record is a separate document** (§C): a new tempdoc or a 766 closing section that
cites this protocol **by its §E.0 freeze commit SHA**. Results are never written into §E.1–§E.6 —
that would be editing a frozen section.

## §E.7 PENDING-AT-FREEZE index (the mechanical freeze checklist)

| # | item | section | resolved by |
|---|---|---|---|
| 1 | freeze commit SHA + timestamp | §E.0 | the freeze commit itself |
| 2 | 760 installer artifact identity | §E.0 | 760 lane |
| 3 | 781 members `fully-certified` (not `structurally-certified`) | §E.0 / P2 | 781 §E.4 phases B–E |
| 4 | ~~ratified `policy_id` + `required_strata` re-pinned to §E.1~~ ✅ **CLOSED 2026-07-28 (§G)** — `agent-utility-public-v3`, `scripts/jseval/utility-claim-policy.v3.json`, strata = §E.1 verbatim | P0 | founder ratification (delegated) |
| 5 | re-confirm the three `corpus_signature` pins are unmoved by 781 §E.4 | P1 | verify against `structural-certification.v1.json` |
| 6 | ~~ratified `maximum_closed_book_accuracy`~~ ✅ **CLOSED 2026-07-28 (§G)** — `0.1` as a post-hoc failure CEILING; P3's pre-launch bar stays `0.000` at sonnet | P3 | founder |
| 7 | the 20-qid list per stratum + sha256 + the deterministic selection rule | §E.1 | committed before launch |
| 8 | per-cell `max_budget` USD from fresh **sonnet** calibrations; concurrency | §E.1 | calibration run |
| 9 | active scorer identity at the freeze commit (`substring_scorer` vs LLM judge), recorded `file:line` | §E.3 | read the wired task |
| 10 | which §E.3 branch runs (derivability audit vs agreement spot-check) — follows item 9 | §E.3 | follows item 9 |
| 11 | run-directory date suffix | §E.6 | launch day |

Nothing in this table is a judgment call at supervision time: each row is read from a named file
or is a founder decision recorded before launch. **9 of 11 remain open** (rows 4 and 6 closed by
the §G ratification, 2026-07-28).

## §G Claim policy v3 — RATIFIED 2026-07-28 (closes §E.7 items 4 and 6)

**Authority.** Founder decision 2 (766 §G); the ratification act itself was delegated to the
orchestrator on 2026-07-28 with a fixed composition rule — **v3-DRAFT's structural machinery +
v1's (`agent-utility-public-v2`'s) numeric thresholds VERBATIM + founder-decision-2 strata**.
No threshold was tuned and no stratum was chosen by an agent.

**File.** `scripts/jseval/utility-claim-policy.v3.json` — `policy_id: agent-utility-public-v3`,
`status: active`. `required_strata` are exactly §E.1, in §E.1's cheapest-first order:

| # | `stratum_id` |
|---|---|
| 1 | `en-email-enron-raw\|mixed/en-email-enron-raw-1k-verbose\|1000\|verbose\|sonnet` |
| 2 | `en-email-enron-raw\|mixed/en-email-enron-raw-10k-verbose\|10000\|verbose\|sonnet` |
| 3 | `en-legal-clerc\|mixed/en-legal-clerc-1k-verbose\|1000\|verbose\|sonnet` |

each `query_count: 20`, `seed_ids: [0,1,2]`. **legal-10k is absent** — item 4's contradiction
(the draft declared legal-10k×sonnet + legal-1k×sonnet + legal-10k×haiku) is resolved in favour
of decision 2. Item 6 is resolved by keeping the draft's `maximum_closed_book_accuracy: 0.1` as a
post-hoc failure **CEILING**; it does not relax P3, whose pre-launch bar stays 0.000 at sonnet.

**Selector change.** `utility_claim_policy.policy_path()` now returns the v3 file. The v2
document (`utility-claim-policy.v1.json`) is `status: "superseded"` +
`superseded_by: "agent-utility-public-v3"`, retained as history and as the byte-source the
no-tuning test compares against. `utility-claim-policy.v3-DRAFT.json` is DELETED, and its
`v3_draft_policy_path`/`load_v3_draft_policy` accessors are gone; the only live references that
moved were `scripts/docs/gen-public-agent-utility.mjs` (+ its test), which also stopped hardcoding
"four-stratum … 1k and 10k" and now renders the stratum count from the policy.

**Three additive requirements are now wired, not decorative.** `completion_triple_reported`,
`closed_book_at_hero_tier` and `schema_strata_reported` were declared by the draft but would have
failed the `supported_policy_requirements` gate closed, making every record unpromotable. Each is
now a real conditional gate (fires only when a policy declares it, so records under v1/v2 project
byte-identically):

- `completion_triple_reported` — every ITT stratum must carry `n_per_protocol_pairs` and a per-arm
  `completion_rate` (`estimands.completion.strata[*].by_arm[*]`).
- `closed_book_at_hero_tier` — every stratum's 707 certification snapshot must carry a measured
  `scientific_gates.closed_book.observed.closed_book_accuracy` at or below the ceiling, with a
  named measurement model.
- `schema_strata_reported` — every composed measured cell must publish a `schema_stratified.by_stratum`
  covering both known schemas. Cells are located by their own `primary_arm` marker, not by joining
  on a dataset key (the measured key is the canonical slug `beir/fixture` while the ITT stratum's
  `corpus` is `fixture` — a join there would silently miss).

**Defect found and fixed by that third gate (768 D4 round-trip).** `sanitize_observation` wrote
`question_type` and `_OBSERVATION_KEYS` accepted it, but `read_evidence` never restored it — so
every *offline replay* recomposed with no schema tag and silently dropped `schema_stratified`,
contradicting the write-side comment's own promise. Repaired at
`scripts/jseval/jseval/utility_evidence.py` (`read_evidence`). This mattered directly: the
publication/replay path is how a hero record gets published.

**Test evidence.** Full jseval suite: **2520 passed, 2 skipped** (`python -m pytest tests/`).
`node scripts/docs/gen-public-agent-utility.test.mjs` → OK (98 assertions); `--check` in sync after
regenerating README/RESEARCH/`docs/reference/benchmarks/agent-utility.md`. Deliberate pin changes:

| pin | change | why it is the new truth |
|---|---|---|
| `test_checked_in_policy_is_active_confirmatory_four_stratum` | renamed `…_is_ratified_v3_three_stratum_sonnet_hero`; asserts v3 id + the §E.1 ordered stratum list | the four-stratum haiku matrix is superseded by decision 2 |
| `test_checked_in_active_policy_evaluates_surface_via_rate_branch` | `agent-utility-public-v2` → `…-v3` | same rate semantics, new carrier (§E.2 R2) |
| `test_supported_requirements_match_schema_properties_exactly` | `required` now compared against a new explicit `MANDATORY_REQUIREMENTS` constant, still an exact set equality | supported ≠ mandatory once requirements became additive; keeps the superseded v2 document schema-valid without back-dating keys into it |
| `test_historical_fixture_semantic_digest_repinned_after_624_itt_change` | `ed81f79b…` → `c3f98ebd…` | 4th occurrence of the documented policy-identity re-pin class; the sole-mover proof is now the runnable `test_v3_repin_is_policy_identity_only_prior_pin_reproduces` (see §G.1), not prose |

New test: `test_v3_ratification_tuned_no_threshold_and_dropped_legal_10k` — literally compares v3's
thresholds against the superseded v2 file on every shared key, asserts the only divergence is the
additive `maximum_closed_book_accuracy`, and asserts 3 strata / all sonnet / no legal-10k. That
makes "no threshold was tuned during ratification" a mechanical property rather than a claim.

## §G.1 Independent-review response (2026-07-28)

An independent refute-first review of the ratification commit returned SHIP-WITH-FIXES. The
ratification content was confirmed sound (thresholds re-diffed verbatim vs v1; strata verbatim vs
§E.1; conditional gates confirmed key-present-and-truthy; old-policy records reproduced
byte-identically). Six items landed as a follow-up commit.

**1. A false verification recipe I wrote — corrected.** §G originally claimed the prior digest pin
`ed81f79b…` reproduces "when the SUPERSEDED v2 document is passed as the policy". It does not: the
committed superseded document yields `d32dffba…`, because *this commit's own* `status` flip to
`superseded` plus the added `superseded_by` change the policy hash the verdict carries. The
conclusion (policy identity is the sole mover) was true, but the recipe would have sent the next
auditor chasing a phantom regression. The claim is now a runnable test rather than prose —
`test_v3_repin_is_policy_identity_only_prior_pin_reproduces` reconstructs the *pre-ratification*
v2 shape from the committed file (pop `superseded_by`, restore `status: "active"`) and asserts
`ed81f79b…`. Lesson worth keeping: a prose verification recipe that nobody re-runs is exactly where
a phantom hides — pin it or drop it.

**2. Negative tests for the three new gates.** Only the pass branch was covered, so a gate inverted
to always-True would have shipped green (a passing gate and an absent gate are indistinguishable
from `accepted is True`). Added four refusal tests, each asserting the SPECIFIC gate name surfaces:
`completion_triple_reported` (strip `estimands.completion`), `schema_strata_reported` (strip the
breakdown, asserting the missing-schema payload), and two for `closed_book_at_hero_tier` (evidence
removed; accuracy above the ceiling). **Honest isolation note:** the closed-book measurement rides
the 707 snapshot, which is cross-validated against its own embedded certificate, so any mutation of
it *also* trips `corpus_certification_complete`. That co-failure is asserted rather than glossed,
and the gate's independent work is proved by a before/after on the same gate plus its observed
payload — the first draft of these tests claimed an isolation they did not have, which would have
been a second false recipe.

**3. Sweep residue in the operator-facing sections.** §E.2 P0 still told the operator to verify
`utility-claim-policy.v1.json` had `status: "active"` (now false) — repointed at the v3 file with a
status-based check. §E.2 P3 now records the ceiling-vs-0.000 resolution inline. §E.7 rows 4 and 6
are struck through and marked CLOSED, with the running count (9 of 11 open) stated. Stale comment at
`gen-public-agent-utility.test.mjs` corrected.

**4. Canonical doc.** `docs/reference/benchmarks/agent-utility.md` now states the three
promotion-blocking reporting requirements in its verdict contract. Docs regen pair run
(`llmstxt-generate` + `skills-sync`); neither produced a diff.

**5. Generator robustness.** `gen-public-agent-utility.mjs` no longer hardcodes a version-numbered
policy filename — the same defect class its own test comment says was fixed for the policy ID. It
now selects the single `utility-claim-policy.*.json` with `status: "active"` and FAILS generation
when zero or two are active, or when `unresolved` is non-empty, rather than rendering the sentence
"is active and fully resolved" over a policy that is neither. Covered by a new fail-closed scenario
(generator test: 98 → 110 assertions).

**6. Pre-campaign landmine — CLOSED (this was the one that could have cost money).** The §G
follow-up below was fixed rather than deferred, because a collapsed schema could have made a PAID
hero record unpromotable for a reporting artefact. Two composer defects, in
`scripts/jseval/jseval/utility_comparison.py`:

- `_stratified_breakdown` dropped a label whose paired observations collapsed. It now emits an
  explicit null entry (`available: false`, `n_paired_observations: 0`, collapse reason) — opt-in via
  `emit_null_strata`, passed only by the schema axis, so the corpus axis (§T.4) composes
  byte-identically to before.
- The deeper cause, found only by writing the test: the label vocabulary was derived from the
  *surviving pairs*, so a fully collapsed label had no label left to report and vanished anyway. The
  null-emitting path now takes its vocabulary from the whole `qid → label` map. **This is why the
  first fix looked correct and was not** — the drop happened one level above where it appeared to.
- `_default_schema_stratify` returned `None` for a single-schema cell, conflating "one schema
  measured" with "schema coverage never computed". It now returns `None` only when NO query carries
  a `question_type` — the genuine pre-768 byte-identity contract, still asserted.

The pre-existing contract test asserting a single-schema cell adds no key was flipped deliberately
(`test_single_schema_cell_still_reports_its_one_schema_but_untagged_adds_no_key`): v3's
`require_all_present` needs those two cases distinguishable. New tests:
`test_collapsed_schema_reports_an_honest_null_never_a_dropped_key` (the exact collapsed case) and
`test_null_strata_emission_is_opt_in_so_the_corpus_axis_is_unchanged`.

**Residual limit, stated rather than hidden.** A schema absent from a cell's *query set* entirely
still fails `schema_strata_reported`, because the composer has no access to the policy's
`known_schemas` vocabulary and cannot invent a null for a schema it never saw. That failure is
correct under `require_all_present`, and it is detectable pre-launch from the committed 20-qid list
(§E.7 row 7) rather than at compose time on a paid record — which is the property that mattered.
