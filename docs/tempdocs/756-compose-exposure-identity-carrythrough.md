---
title: "cross-corpus compose drops exposure identity every per-run record carries — source_identity_complete fails on evidence that is actually complete; carry the verified-identical blocks through"
type: tempdocs
status: "open — charter (2026-07-18). Small, mechanical, fully delegable; no founder input needed."
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

## §D. Acceptance

- New unit test: compose two synthetic runs with identical exposure identity → composed record
  carries it verbatim and `source_identity_complete` evaluates true on an otherwise-complete
  fixture; compose two runs with DIFFERENT exposure modes → `UtilityComposeError`.
- Recompose the committed confirmatory logs (if retained) or fixture evidence: the
  `source_identity_complete` gate line flips to passed; `required_strata_exact` and
  `verified_tool_surface` remain the only failures on the 3-stratum record.
