---
title: "cross-corpus compose drops exposure identity every per-run record carries — source_identity_complete fails on evidence that is actually complete; carry the verified-identical blocks through"
type: tempdocs
status: "implemented (2026-07-21). Fix + fail-closed spanning check + 3 tests landed; full jseval suite green modulo pre-existing known-RED correction-probe pair."
created: 2026-07-18
author: agent (Fable orchestration), chartered after the 624 confirmatory campaign's rejected verdict; founder-directed handoff (2026-07-18)
category: eval-infrastructure / agent-utility
related:
  - 624-agentic-retrieval-eval-rebuild   # §Confirmatory campaign RESULTS, failing gate 2
  - 725                                   # the increment that made exposure identity part of source identity
---

> Charter. Reproduce the failure from committed evidence before fixing (it replays offline).

# 756 — compose exposure-identity carry-through

## §A. Problem (measured, 2026-07-18)

`source_identity_complete` (`scripts/jseval/jseval/utility_claim_policy.py:309-341`) requires
`cohort.exposure_config.exposure_mode ∈ {eager, deferred}` and a populated
`cohort.mcp_initialize_identity` (instructions_sha256 + server_version). Every confirmatory
per-run record carries them (`exposure_mode="deferred"`, server 0.4.0 — verified in all five
run records), but the composed cross-corpus record has `exposure_mode: None` and an empty
`mcp_initialize_identity` → the gate fails on complete evidence. Assembly site:
`compose_utility_cross_corpus` cohort construction, `scripts/jseval/jseval/utility_comparison.py`
(~line 1324 area; the same function that enforces the single-cohort checks).

Reproduce offline: `python -m jseval utility-recompose --log-dir <any 2 confirmatory log dirs>
…` against `scripts/jseval/tmp/confirm/**` or by recomposing the committed evidence fixtures —
then inspect `cohort.exposure_config` in the output.

## §B. Fix shape

Carry `exposure_config` and `mcp_initialize_identity` into the composed cohort the same way
the other cohort-identity fields are carried: assert they are IDENTICAL across all input runs
(fail closed on mismatch, same style as the existing `agent_cohort_key` /
`search_config_cohort_key` spanning checks) and copy the single agreed value. A silent
first-wins copy is NOT acceptable — mixing two differently-exposed campaigns must stay an
error.

## §C. Consequences to handle in the same PR

- `semantic_digest` of composed records changes (the cohort block gains fields) → re-pin any
  fixture digests (`scripts/jseval/tests/test_utility_evidence.py` — one digest was already
  re-pinned for the policy activation; same procedure).
- The observations-shard note for this bug (session shard 109145ac, 2026-07-18) can be marked
  resolved at the next fold.

## §E. Design (2026-07-21)

Localized fix, mirrors the single-corpus sibling with one strengthening.

- **Site.** `compose_utility_cross_corpus` (`scripts/jseval/jseval/utility_comparison.py`)
  builds its `cohort` dict (was ~1364-1386) without the two blocks; the single-corpus
  `compose_utility` (same file, ~341-367) already carries them via `ref = run_summaries[0]`
  (first-wins) and EXCLUDES-when-absent (so pre-725 evidence hashes unchanged).
- **Strengthening (why not first-wins here).** Cross-corpus *pools multiple campaigns* —
  first-wins could fuse two differently-exposed campaigns into one identity-complete-looking
  record. So carry-through goes through a fail-closed spanning check
  `_one_cross_corpus_cohort_block(run_summaries, field)`: raises `UtilityComposeError` on ANY
  disagreement (including present-on-some / absent-on-others), same style/precedent as the
  `agent_cohort_key` (~1315-1328) and per-corpus-identity (~1350-1363) spanning checks already
  in this function. Absent-everywhere returns `None` → block excluded (not set to `None`),
  preserving byte-identical digests for pre-725 evidence (mirrors `agent_manifest.agent_cohort_key`
  186-196).
- **`agent_cohort_key` only folds the SCALAR `exposure_mode`+`instructions_sha256`**, not the
  full blocks — so two runs can share a cohort key yet carry differing `server_version` /
  `always_load` / `protocol_version`. That is exactly the silent-first-wins hole the new check
  closes; the mismatch tests hold the folded scalars equal to isolate it (right-reason).
- **Orphans:** none. Fills a gap the sibling already closed.
- **Reach / principle:** *cohort-level identity fields must span-check, not first-wins, when a
  composer pools across a boundary the field is invariant over.* Already instantiated 3× in this
  function; single-corpus `compose_utility` legitimately keeps first-wins (one campaign). Earns
  its keep when a real mixed-campaign recompose raises; retire/relocate the check if exposure
  identity ever becomes legitimately per-stratum.

## §F. Derisk (2026-07-21)

- **Does the digest re-pin actually fire?** `finalize_evidence` (test_utility_evidence.py's path)
  calls `compose_utility` (single), NOT `compose_utility_cross_corpus` — and the committed
  `agent-utility-rejected-2026-07-12` fixture predates exposure identity (blocks absent →
  excluded). So the fix is expected to leave that fixture's digest UNCHANGED. Verify empirically;
  re-pin only what actually moves. Confidence 9/10, sonnet-grade mechanical.
- **Existing cross-corpus tests** (`_xcorpus_fixture`) carry no exposure → all-absent → excluded →
  unchanged. No existing digest is pinned on a cross-corpus record.
- **Known-RED (not mine):** `test_correction_probe.py::TestLoadManifest` (2) — missing data file.

## §G. Plan (2026-07-21)

1. Add `_one_cross_corpus_cohort_block` helper above `compose_utility_cross_corpus`.
2. Call it for both blocks after the per-corpus-identity loop; add both to `cohort` conditionally
   (mirror `compose_utility` 364-367).
3. Tests in `test_utility_comparison.py`: (a) carry-through verbatim + `source_identity_complete`
   true on an otherwise-complete `_record()` fixture; (b) mismatched `exposure_config` →
   `UtilityComposeError`; (c) mismatched `mcp_initialize_identity` → `UtilityComposeError`.
4. `python -m pytest scripts/jseval/tests -q` (PYTHONPATH set); re-pin any moved digest.
5. Tempdoc → implemented + implementation log; note observation resolved-by-756.

## §D. Acceptance

- New unit test: compose two synthetic runs with identical exposure identity → composed record
  carries it verbatim and `source_identity_complete` evaluates true on an otherwise-complete
  fixture; compose two runs with DIFFERENT exposure modes → `UtilityComposeError`.
- Recompose the committed confirmatory logs (if retained) or fixture evidence: the
  `source_identity_complete` gate line flips to passed; `required_strata_exact` and
  `verified_tool_surface` remain the only failures on the 3-stratum record.

## §H. Implementation log (2026-07-21, session 109145ac)

**Code** (`scripts/jseval/jseval/utility_comparison.py`):
- New helper `_one_cross_corpus_cohort_block(run_summaries, field)` (~1274-1301): returns the
  single agreed value of a cohort-level manifest block across all runs; raises `UtilityComposeError`
  on ANY disagreement (including present-on-some/absent-on-others); returns `None` when absent
  everywhere. Pairwise `!=` compare, mirroring the per-corpus-identity check already in the function.
- `compose_utility_cross_corpus`: compute `exposure_config` / `mcp_initialize_identity` via the
  helper after the per-corpus-identity loop (~1394-1400); add both to `cohort` conditionally
  (excluded-when-absent) after the cohort dict literal (~1420-1426). Mirrors the single-corpus
  `compose_utility` (364-367) exactly, plus the fail-closed spanning check.

**Tests** (`scripts/jseval/tests/test_utility_comparison.py`, ~1755-1850): 3 new + fixtures
`_xcorpus_identity_summary` / `_xcorpus_identity_fixture`:
- `test_cross_corpus_carries_exposure_identity_and_satisfies_source_identity_gate` — both blocks
  carried verbatim; `source_identity_complete` gate `passed is True` on an otherwise-complete
  `_record()` fixture (imported from `tests.test_utility_claim_policy`) using the carried blocks.
- `test_cross_corpus_fails_closed_on_mismatched_exposure_config` — one run exposed `eager` vs
  `deferred` (folded scalar held equal to isolate this check from the cohort-key check) →
  `UtilityComposeError`.
- `test_cross_corpus_fails_closed_on_mismatched_mcp_initialize_identity` — differing MCP
  `server_version` → `UtilityComposeError`.

**Test evidence** — `python -m pytest scripts/jseval/tests -q` (PYTHONPATH=<worktree>/scripts/jseval,
PYTHONUTF8=1, INSPECT_DISPLAY=none):
`2 failed, 2166 passed, 60 warnings in 313.90s`. The 2 failures are the pre-existing known-RED
`test_correction_probe.py::TestLoadManifest` pair (data file
`scripts/jseval/jseval/data/correction-eval-queries.v1.json` absent on base commit `bcd4bf20` and
nowhere in git history — expected-state hook `correction-eval-queries-missing`, not caused by this
change). New cross-corpus subset: `8 passed`.

**Digest re-pin — NOT NEEDED (verified empirically).** §F's hypothesis held: `finalize_evidence`
(the test_utility_evidence.py digest path) composes via single-corpus `compose_utility`, which this
change does not touch; the committed `agent-utility-rejected-2026-07-12` fixture predates exposure
identity (blocks absent → excluded → digest unchanged). All three digest-pinned tests
(`test_historical_fixture_semantic_digest_repinned_after_624_itt_change`,
`test_evidence_roundtrip_preserves_semantic_digest`,
`test_source_complete_evidence_digest_is_checkout_independent`) stayed green with no edit. No
cross-corpus record carries a pinned digest. §C's anticipated re-pin therefore did not materialize.

## §I. Observation resolution

The observations-shard note for this bug (session shard 109145ac, 2026-07-18, per §C) is resolved by
this implementation. Left the shard in place for the next `fold-observations.mjs --apply` to mark
resolved; not editing another session's shard directly.
