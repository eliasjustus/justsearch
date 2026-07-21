---
title: "harness attribution lane: rank-of-gold capture at run time, USD-binding budgets, schema-stratified records, claim-policy v3 draft — the 766 program's instrument half"
type: tempdocs
status: "IMPLEMENTED (2026-07-21) in worktree agent-a978d255079b4c6ce. All §D steps 1-10 landed and GREEN; smoke oracle PASS 18/18 gold_rank vs replay; §E log added. Awaiting orchestrator review + publish. Claim-policy v3 is a DRAFT only — founder ratifies."
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

## §D. Plan (2026-07-21)

All file:line anchors verified against `step2-powered` worktree source
2026-07-21. One correction to the charter's own claims: none — §A's cited
lines (1101-1102, 767-804, 881-883, 700-720) and 762 §X.6.4's
`McpEvidenceProjection.java:72/79` all check out verbatim (`h.put("id",
hit.id())` at :72, `h.put("score", hit.score())` at :79, per-hit list order =
rank order of `resp.results()`). One gap the charter doesn't mention: the
763 oracle script (`tmp/analysis-624/763/replay/replay_stratum.py`) is
gitignored/ephemeral and exists only in the main checkout, not in any
worktree — step 8 below copies it in as a prerequisite.

1. **Gold identity into Sample.metadata** (D6, item 1).
   `agent_utility_inspect.py:1102` — add `"evidence_ids": r.get("evidence_ids")`
   to the `metadata=` dict (the queries file already carries this field per
   query, e.g. `624-corpora/battlefield-en-v1/queries.json:6-10`; it is read
   nowhere downstream today). Test: `tests/test_agent_utility_inspect.py`,
   new `test_sample_metadata_carries_evidence_ids`. Verify:
   `PYTHONPATH=<worktree>/scripts/jseval PYTHONUTF8=1 python -m pytest scripts/jseval/tests/test_agent_utility_inspect.py -k evidence_ids -q`.

2. **`gold_rank`/`ordered_doc_ids`/`scores` at capture** (D6, item 2; depends
   on step 1 for `evidence_ids` on `state.metadata`).
   `agent_utility_inspect.py:767-804` (`_tool_result_digest_entry`) — parse
   `content` when `_delivered_tier(content) == _DELIVERED_TIER_STRUCTURED`
   (guard already exists at :700-720) into the JSON object, read
   `parsed["results"]` (list, id-order = rank order per
   `McpEvidenceProjection.java:65-89`), emit `ordered_doc_ids` (list of
   `results[*]["id"]`), `scores` (list of `results[*]["score"]`), and
   `gold_rank` (0-based index of the first id in `evidence_ids` found in
   `ordered_doc_ids`, else `null`) — `null` for non-`justsearch_search` calls
   and calls with no gold hit, never a fabricated `-1`. Needs `evidence_ids`
   threaded in from the caller (`_record_cell`, :826-883) since
   `_tool_result_digest_entry` today takes only `result`; add an
   `evidence_ids: list[str] | None = None` parameter. Call site
   `agent_utility_inspect.py:881-883` — pass
   `state.metadata.get("evidence_ids")`. Ids+ranks only, never raw payload
   text (redaction rationale unchanged). Test:
   `tests/test_agent_utility_inspect.py`, new
   `test_tool_result_digest_entry_gold_rank_{hit,miss,non_search_null}`.
   Verify: same pytest invocation as step 1, `-k gold_rank`.

3. **`question_type` propagation to sanitized evidence** (prerequisite for
   D4's schema stratification; `question_type` is set on `Sample.metadata`
   at :1102 already but is dropped at the observation-sanitizer boundary
   today). `agent_utility_observations.py` — add
   `question_type = metadata.get("question_type")` and a
   `"question_type": question_type` entry at each of the ~4 observation-dict
   construction sites (:97, :197, :249, :272 — mirror the existing
   `condition` read at :75-76/:97). `utility_evidence.py` — add
   `"question_type"` to `_OBSERVATION_KEYS` (:14) and to the `sanitized`
   dict in `sanitize_observation` (:206-269, alongside `"qid"` at :213).
   Test: `tests/test_utility_evidence.py` (or nearest existing sanitizer
   test file), new `test_sanitize_observation_carries_question_type`.
   Verify: `pytest scripts/jseval/tests/ -k question_type -q`.

4. **Schema-stratified records + ITT/per-protocol/completion triple** (D4/D7,
   item 4; depends on step 3). Two independent sub-changes:
   - `utility_comparison.py` — add `_default_schema_stratify(cell_summaries)`
     as a sibling of `_default_corpus_stratify` (:1199-1218), keyed on
     `question_type` instead of corpus signature, returning `None` when the
     cell has only one schema (same single-population no-op contract). Wire
     it into `_compose_cell` (:1258-1290) as an *additional* stratification
     alongside the existing `stratify_by=_default_corpus_stratify(...)`
     call at :1276-1280 — `_arm_comparison`'s `stratify_by` param is already
     generic (any `qid -> label` map), so this is composition, not a new
     mechanism.
   - `utility_recompose.py`'s `_intention_to_treat_estimand` (:127-440)
     currently returns only two of the three named estimands (`"primary":
     "intention_to_treat"` + `"per_protocol": {"role": "secondary", "source":
     "measured"}` at :433-439) — no `"completion"` key. Add a `"completion"`
     sibling per 762 §T.4's definition (read that section before
     implementing — it is the source of truth for what distinguishes
     completion from ITT/per-protocol, not this plan). Test:
     `tests/test_utility_comparison.py` (schema stratify) and
     `tests/test_utility_recompose.py` (completion triple), new
     `test_compose_cell_schema_stratified_by_question_type` and
     `test_itt_estimand_emits_completion_triple`. Verify:
     `pytest scripts/jseval/tests/test_utility_comparison.py scripts/jseval/tests/test_utility_recompose.py -q`.

5. **USD-binding per-cell budget** (D7, item 3). Current bug: the outer
   wall-clock (`agent_utility_inspect.py:517-530`, `asyncio.wait_for(...,
   timeout=remaining)` wrapping `_one_attempt`) races the SDK-native
   `max_budget_usd` cap (`:447`, `ClaudeAgentOptions(max_budget_usd=...)`)
   and can fire first — a wall-clock kill cancels the coroutine before any
   terminal `ResultMessage` arrives, so `cost_usd` stays `null` (the
   no-ResultMessage branch at :1040-1051 says so explicitly: "cost is
   genuinely unrecoverable here"), whereas a USD-cap kill (`error_class ==
   "usd_budget_exhausted"`, `utility_evidence.py:49`) DOES get a terminal
   `ResultMessage` with cost intact. Fix: widen `cell_timeout_s` (or the
   per-condition map) to a generous backstop multiple of the expected
   time-to-exhaust-`max_budget_usd`, so the USD cap is what fires in the
   modal exhausted-cell case; the wall-clock stays only for the genuine
   hang case. Test: `tests/test_agent_utility_inspect.py`, new
   `test_usd_cap_kill_retains_cost_receipt` (mock a cell whose spend hits
   `max_budget_usd` before `cell_timeout_s` elapses; assert `cost_usd is not
   None`) — this is the acceptance criterion's "exhausted-cell receipts"
   regression test. Verify: `-k usd_cap_kill`.

6. **Replay-tooling pinned adoption** (item 5). `backend.py`'s
   `_run_with_cache` (:257-349) resolves `selector.key` fresh every run via
   `index_identity.compute_selector(...)` (:309) then
   `index_cache.lookup(selector.key)` (:328) /
   `index_cache.adopt(entry, resolved_data)` (:344) — no path to force a
   specific historical `selector_key` when HEAD has moved past the campaign
   commit (763 §F's monkeypatch workaround). Add an explicit override: a
   `pin_selector_key: str | None` parameter threaded from a new
   `--pin-index-selector-key` CLI flag (mirror `--max-budget`'s
   `commands/utility.py:282` option-definition pattern) through to
   `_run_with_cache`, which — when set — skips `compute_selector` and calls
   `index_cache.lookup(pin_selector_key)` directly, keeping the same
   miss/adopt/error handling below it (:329-349) unchanged. Test:
   `tests/test_index_cache.py` (or nearest backend cache test), new
   `test_run_with_cache_pinned_selector_key_bypasses_compute_selector`.
   Verify: `-k pinned_selector`.

7. **Claim-policy v3 draft** (item 6; independent, can run in parallel with
   1-6). `utility_claim_policy.py` — v2's `required_strata_exact` gate
   (:222-228, matrix compared at :210-221) stays untouched and ACTIVE per
   766 §D.3 item 3. Add a new, separate v3 draft function (do not edit v2's
   gate logic) covering: the schema/strata matrix from step 4, the
   rate-based surface gate carried over from v2, closed_book-at-hero-tier
   requirement, and triple-reporting wording constraints. DRAFT status only
   — no gate wiring, no v2 orphaning; founder ratifies separately. No new
   test required (draft, not wired); note in the module docstring that it
   is inert.

8. **Smoke-cell oracle verification** (§B acceptance, run after steps 1-2
   land). Copy `tmp/analysis-624/763/replay/replay_stratum.py` from the
   main checkout (`F:/justsearch-public/tmp/analysis-624/763/replay/`) into
   the working worktree (gitignored, not tracked — this is a manual copy,
   not a git operation). Run one haiku, 1-stratum, cached-index smoke cell
   with the step-2 capture live; compare its captured `gold_rank` per call
   against `replay_stratum.py`'s independent manual replay of the same
   queries over the same cached index. MATCH is the acceptance bar (§B).
   Order-$1 paid-API budget authorized for this cell only (§C).

9. **Digest/fixture re-pin, verify-empirically** (756 §F method, applies to
   steps 2-4's schema changes). After steps 2-4 land, run the existing
   `test_agent_utility_inspect.py` / `test_utility_comparison.py` /
   `test_utility_recompose.py` fixture-digest tests; re-pin ONLY the
   digests/fixtures that actually moved (diff the failure output field-by
   -field before touching any expected-value literal) — do not blanket
   re-pin. Record which digests moved and why in this tempdoc's evidence
   log.

10. **Full suite** (§B acceptance, run last).
    ```
    PYTHONPATH=<worktree>/scripts/jseval PYTHONUTF8=1 INSPECT_DISPLAY=none \
      python -m pytest scripts/jseval/tests/ -q
    ```
    Known-RED correction-probe pair (762 §D) is the only permitted exception —
    confirm it is still exactly that pair, not a new failure, before closing.

## §E. Implementation log (2026-07-21)

Implemented end-to-end in worktree `agent-a978d255079b4c6ce` (branch
`worktree-agent-a978d255079b4c6ce`), §D steps in order. All paths below are in
that worktree's `scripts/jseval` tree.

### What landed, by step

1. **Gold identity into `Sample.metadata`** — `agent_utility_inspect.py:1102`
   now emits `"evidence_ids": r.get("evidence_ids")`. Test
   `test_sample_metadata_carries_evidence_ids` (a query without the field → `None`,
   never a fabricated `[]`). GREEN.

2. **`gold_rank`/`ordered_doc_ids`/`scores` at capture** — new `_gold_rank_capture`
   + `_normalize_doc_id` helpers and a 3-key extension to `_tool_result_digest_entry`
   (`agent_utility_inspect.py`); call site threads `state.metadata.get("evidence_ids")`.
   Tests: `test_tool_result_digest_entry_gold_rank_{hit,miss,non_search_null,
   null_without_evidence_ids,normalizes_path_ids_to_basenames}`,
   `test_normalize_doc_id_basename_ext_lower`,
   `test_record_cell_threads_evidence_ids_into_gold_rank`. GREEN.
   - **DEVIATION / bug found by the smoke pre-check (§E oracle).** The plan assumed a
     direct `evidence_ids`↔`results[*].id` match. The LIVE
     `McpEvidenceProjection.results[*].id` is an *absolute corpus path*
     (`…\corpus-dir\limker1.txt`) while the queries file's `evidence_ids` are
     extensionless basenames (`limker1`) — a raw exact-match would return
     `gold_rank=None` on every real gold hit. Fix: `_normalize_doc_id`
     (basename → strip one extension → lowercase) applied to BOTH sides for the
     gold match, mirroring the 763 oracle's own normalization; `ordered_doc_ids`
     stay the RAW delivered ids. Every synthetic unit test missed this (simple ids);
     the smoke oracle is exactly what caught it.

3. **`question_type` propagation** — added to the `read_inspect_observations`
   observation dict AND the `successful_summaries` `per_query` projection tuple
   (`agent_utility_observations.py`), plus `_OBSERVATION_KEYS` + the `sanitized`
   dict in `utility_evidence.py`, plus a `question_type` property in
   `agent-utility-observation.v1.schema.json`. Test
   `test_sanitize_observation_carries_question_type`. GREEN.
   - **DEVIATION from the plan's named sites.** The plan cited `:97, :197, :249, :272`
     mirroring `condition`. Those latter three are *cell-level* (condition), but
     `question_type` is *per-query*; putting one value at cell granularity
     misrepresents a multi-schema cell. Step 4's `_default_schema_stratify` actually
     reads it from `per_query[qid]`, so the required (and semantically correct)
     threading is the observation dict + the `per_query` tuple + the sanitizer — not
     the cell-level manifest/summary. Implemented the correct threading.

4. **Schema-stratified records + completion triple.**
   - `utility_comparison.py`: `_default_schema_stratify` (sibling of
     `_default_corpus_stratify`, keyed on `question_type`, `None` when single-schema);
     `_arm_comparison` refactored to share `_stratified_breakdown` and gained a
     `schema_stratify_by` param emitting a sibling `schema_stratified` key
     (corpus `stratified` untouched); wired into `_compose_cell`. The measured-cell
     schema has no `additionalProperties:false`, so no schema change was needed there.
     Tests `test_compose_cell_schema_stratified_by_question_type`,
     `test_no_schema_stratify_field_when_cell_is_single_schema`.
   - `utility_recompose.py`: `_intention_to_treat_estimand` now emits the third
     estimand `completion` (`role/source/strata`, per-arm `completion_rate =
     n_completed / n_attempted`, an exhausted cell is a non-completion per 762 §T4).
     Added `completion` to the estimands block of `utility-comparison.v1.schema.json`
     (property, not required — pre-768 records lack it). Test
     `test_itt_estimand_emits_completion_triple` (A exhausted→0.5, B→1.0). GREEN.

5. **USD-binding per-cell budget** — new constant `_WALL_CLOCK_BACKSTOP_MULT = 3`;
   the resolved `cell_timeout_s` is widened by it so the SDK `max_budget_usd` cap
   (which delivers a terminal `ResultMessage` with cost intact) fires before the
   wall-clock (which loses cost) in the modal exhausted case. Regression test
   `test_usd_cap_kill_retains_cost_receipt` (a USD-cap `is_error` ResultMessage
   retains `cost_usd`/`unique_tokens`; error classifies as `usd_budget_exhausted`).
   Re-pinned the 3 timeout-resolution tests to `× _WALL_CLOCK_BACKSTOP_MULT`
   (per-condition ordering preserved). GREEN.

6. **Replay-tooling pinned adoption** — `backend.py:_run_with_cache` gained
   `pin_selector_key` (bypasses `compute_selector`, looks the pinned key up directly,
   keeps miss/adopt/confirm handling); threaded through `start_backend` and a new
   `--pin-index-selector-key` CLI flag on `jseval run` (`commands/run.py`). Tests
   `test_run_with_cache_pinned_selector_key_bypasses_compute_selector`,
   `test_run_with_cache_without_pin_still_fails_closed_on_no_axis`. GREEN — and the
   smoke cell (step 8) live-verified it (`cache_outcome.mode == "adopted"`,
   `selector_key == 7a2b8823…`).

7. **Claim-policy v3 DRAFT** — new file `utility-claim-policy.v3-DRAFT.json`
   (`status: "draft"`, `policy_id agent-utility-public-v3-DRAFT`): schema/strata
   matrix (`required_schema_strata` keyed on `question_type`), the carried-over
   rate-based `verified_tool_surface_semantics`, `hero_tier.closed_book_required`,
   and `triple_reporting_semantics` (headline ITT + per-protocol + completion,
   budget-explicit, forbidden headlines). Inert accessors `v3_draft_policy_path` /
   `load_v3_draft_policy` + a module-docstring note added to `utility_claim_policy.py`;
   v2's ACTIVE gate logic and `utility-claim-policy.v1.json` are UNTOUCHED. Not wired
   to any gate; ratification is a founder action (766 §D.3).

8. **Smoke-cell oracle verification — PASS (18/18).** Copied
   `tmp/analysis-624/763/replay/replay_stratum.py` into the working area, booted the
   eval backend from `step2-powered` adopting the pinned `en-legal-clerc-1k` index
   via the new `--pin-index-selector-key` path (boot 25.5s, `mode=adopted`,
   `live embeddingDocCount=1001` == expected), ran **3 real haiku condition-C cells**
   (`max_budget=0.30`/cell; order-$1 authorized) with the step-2 capture live, then
   replayed each agent-issued search query via `/api/knowledge/search` (the 763 oracle
   path) and compared. **Verdict: 18/18 captured `gold_rank` values MATCH the
   independent replay** across 13+2+3 search calls, and `ranking_prefix_match` held on
   every call (the captured MCP ranking equals the REST replay ranking). The matches
   include genuine gold HITS (rank 3, rank 0) — not just concordant `None`s — so the
   normalization fix from step 2 is exercised, not bypassed. Port 33221 was FREE before
   start and the backend shut down cleanly (port released). No paid-run auth/port
   blocker occurred.

9. **Digest/fixture re-pin (verify-empirically, 756 §F).** The full suite surfaced two
   movers, both verified field-by-field before re-pinning:
   - `test_utility_evidence.py::test_tool_result_digests_echo_leak_absent_from_sanitized_bytes`
     and `test_agent_utility_inspect.py::…furniture_markers_block_list…` and the
     `never_stash_raw_content` exact-dict — the digest now carries the 3 new
     redaction-safe keys (`ordered_doc_ids`/`scores`/`gold_rank`); the sanitizer
     `_tool_result_digests` was extended to pass them through (ids+ranks only) and the
     observation-schema digest item gained the three (additive, not required). Exact
     dicts re-pinned with the 3 keys (all `None` for prose/blocks deliveries).
   - `test_historical_fixture_semantic_digest_repinned_after_624_itt_change` — the
     `estimands.completion` addition is digest-COVERED measurement content. **Proven
     empirically that completion is the SOLE mover**: stripping it from the semantic
     projection reproduces the prior pin `3d0bf53b…` byte-for-byte; the new pin is
     `ed81f79b34a3537da84c20bc3b978b804dc0419dedaae88597bfc95c5827876b`. Docstring
     updated with the derivation. `tool_result_digests`' new fields stay
     digest-EXCLUDED (evidence/sanitizer tier only, U1).

10. **Full suite** — `python -m pytest scripts/jseval/tests -q`: **2241+ passed**,
    only the known-RED `test_correction_probe.py::TestLoadManifest` pair (762 §D,
    `correction-eval-queries.v1.json` absent from history) remains RED — confirmed it
    is exactly that pair, no new failures. Diff is UTF-8-clean (`§`/`—` are valid
    UTF-8, matching codebase style; no mojibake introduced).

### Register duty (§A item 7)

All changes are confined to the `scripts/jseval` eval harness — no search-orchestration
or inference-runtime code touched — so `/search-quality` and `/inference-runtime` need
no updates, and no out-of-scope findings arose that would require an observation.

### Files touched

Code: `agent_utility_inspect.py`, `agent_utility_observations.py`, `utility_evidence.py`,
`utility_comparison.py`, `utility_recompose.py`, `utility_claim_policy.py`, `backend.py`,
`commands/run.py`. Schemas: `agent-utility-observation.v1.schema.json`,
`utility-comparison.v1.schema.json`. New: `utility-claim-policy.v3-DRAFT.json`. Tests:
`test_agent_utility_inspect.py`, `test_utility_comparison.py`, `test_duration_exhaustion_624.py`,
`test_index_cache.py`, `test_utility_evidence.py`.
