---
title: "794: Stack identity on the runtime manifest, and the comparison slices it forces"
type: tempdoc
status: "chartered (2026-07-28) from 792 §33. Design is settled in 792 Part II as corrected by Parts V and VI; implementation NOT yet licensed. Source evidence: 792 Part V (seven-item derisking pass) and the shipped precedent at tempdoc 623 U7."
created: 2026-07-28
related:
  - 792-stack-currency-audit-round-3   # audit + design of record; Part V is this lane's derisking
  - 501-runtime-manifest-design        # the closure rule that decides where stack identity lives
  - 654                                # projection-not-fork precedent (RuntimeContract)
  - 623                                # U7 — ort.version: the shipped instance this lane generalizes
  - 644 / 553                          # representation-drift class; realized-capability single reader
  - 664                                # baseline-shift convention this lane must not breach
---

# 794 — Stack identity, and the comparison slices it forces

## Why this lane exists

A dependency bump changes the stack **underneath** the measurement. The measurement apparatus models
corpus, models, policy, eval protocol, git and hardware — but not the stack. So a metric shift after a
bump cannot be attributed: `git_sha` moves on every commit, and the native payloads that matter most
resolve outside git entirely (792 §8).

Under 792 §30 this lane builds the *record*, not a gate. Detection comes from a standing evaluation;
attribution comes retrospectively from `bisection.py` — which is exactly what a stack axis enables.

## What is already shipped (generalize this; do not reinvent it)

Tempdoc 623 U7 built the whole path, for one axis:

- `OnnxSessionCache.getOrtVersion()` → `OrtEnvironment.getEnvironment().getVersion()` — a genuinely
  **realized** runtime query, not a declared constant.
- `GrpcHealthService` publishes it as `effective_config["ort.version"]`, riding the existing
  Head→Worker divergence-detection map (329) with **no new proto field**.
- Head consumes it (`RemoteKnowledgeClient` → `WorkerDebugView` → `/api/debug/state`), jseval reads it
  (`release.py`), and it reaches a composed release, the public benchmark page, and the register
  headline.

Its source comment states this lane's thesis almost verbatim: *"record what ORT produced these eval
numbers."* **This lane's work is to generalize instance #1**, not to build a mechanism.

## Design constraints inherited from 792

1. **Where it lives is already decided.** Tempdoc 501 §6's closure rule — gate-enforced — makes any
   runtime fact a non-JVM consumer needs a field on the runtime manifest. jseval is such a consumer.
   Projection, not fork (654's stance): read each version from its existing single source.
2. **Identity is question-relative** (792 §10). **Four** slices now exist independently
   (`manifest.py` cohort hash, `bisection.py` axes, `release.py` `config_cohort_key` +
   `_MODEL_EXECUTION_FLAGS`, and — added 2026-07-29 by the 719 agent-utility publication work —
   the publication cohort in `utility_evidence.py`). The stack axis must be added across all of
   them, which is why they are declared once here rather than hand-edited a fifth time.

   **The fourth slice matters disproportionately, on two counts.** First, it *independently invented
   stack identity*: it carries `cli_version` and `mcp_tool_surface_hash` — "what software produced
   this number", arrived at separately, for a different question, under different names. That is the
   792 §10 principle confirmed by a case that appeared **while** 792 was being written, not before it.
   Second, it raises this lane's blast radius: the publication record is content-addressed and
   byte-stable, PR #322 made digest reproduction **fail-closed**, and the record composes
   `search_config_cohort_key` into itself. So changing what enters that key now breaks a published-record
   guarantee that did not exist when hazard R5 was assessed.

   **Scope-stability caveat, recorded honestly:** this slice was found by reading `main` after the
   fact, not by 792's derisking pass. One appearing by luck is reason to assume the enumeration is
   not closed — re-enumerate the slices at implementation time rather than trusting this list.
3. **Stack membership differs by slice.** Attribution always carries stack; the σ-envelope slice
   carries it only for axes the stack can reach. Otherwise every bump orphans every envelope, which
   would make the design unusable.
4. **A stack change may never justify relaxing a floor** (792 §12; 664's convention). Predictable
   evasion, named: *"the new version is simply tuned differently, so the baseline should follow."*

## The two hazards this lane must close at introduction

Both confirmed concrete in 792 Part V. Neither can be deferred — they are introduced *by* the axis.

- **Absent stack identity compares equal (R4).** The axis comparator is `if a != b`. Two pre-axis
  manifests both yield `None`, so runs from genuinely different stacks read as identical — silently,
  which is worse than refusing. A second function in the same module handles absent keys differently
  again (it omits them from the hash). Both must move together. The correct precedent is one file
  away: `ratchet_kernel.compare_engine_sets` refuses on mismatch with a named override and treats
  unknown as *skip*, not *equal*.
- **Persisted cohort keys become non-recomputable (R5).** `release.v1.json` stores
  `cohort.config_cohort_key` as an opaque hash, and `perf-ratchet-baselines.v1.json` points at it via
  `current_release`. Changing what enters that hash breaks the baseline → release → cohort chain. A
  transition stance is required **before** implementation: grandfather absent identity as
  unknown-but-comparable, or take one deliberate, changeset-justified recomposition and date it.
  Drifting into this unstated would breach constraint 4 by way of the change that introduces it.

## Firing condition

A record that never fires becomes decoration (792 §25) — 682's expected-vs-actual llama build check
only `LOG.warn`s. Two precedents to conform to instead of inventing a rule: `OnnxSessionCache` already
treats an ORT version change as **cache-invalidating**, and `preflight.assert_capabilities` compares
intended against realized and returns refusals, fail-closed.

## What this lane orphans (deletion belongs here, not to a later sweep)

1. `release._MODEL_EXECUTION_FLAGS` — an exclusion tuple that exists only because stack facts
   (`embed_gpu`, `splade_gpu`, `ner_gpu`, `reranker_gpu`) had no record of their own.
2. `model_fingerprints.realized_engines` and the `*_gpu` keys — execution/stack facts filed under
   models. They move.
3. `release.py`'s `hardware.ort_version` — a software fact in a hardware record. **This one has public
   blast radius**: two generators render it into public-facing text, so the migration must preserve
   those claims' content.
4. Per-call-site hand-rolled axis lists at the three slice sites.

**Not orphaned:** `preflight.realized_engine_set` stays — it is already the correct single reader
(644/553); only the filing location of its output moves.

## Scope refusals

- **No reachability register and no per-bump evidence gate** (792 §30 reverses this). The one free bit
  — test/build-only coordinates cannot reach a measured number — is kept; the four-way tiering is not
  built.
- No new benchmark harness; no per-bump CI evaluation; no SBOM on the manifest; no general identity
  framework beyond declaring the three slices that already exist.

## Acceptance

- Stack identity is published as a projection on the runtime manifest, with realized values where
  realized values are observable and declared values labelled as such.
- The three slices project from one declaration; no call site hand-rolls an axis list.
- Absent stack identity **cannot** compare equal — demonstrated by a test that fails under the current
  `!=` semantics.
- The transition stance for persisted cohort keys is declared, applied, and dated; public benchmark
  text is unchanged in content.
- The orphan list above is deleted in this lane, not deferred.

## Verification tiers

Compile + module tests; the jseval unit suite for the slice and comparator changes; and a live-stack
run to confirm realized values actually populate — a declared-only fingerprint would silently satisfy
every static check while failing the lane's whole purpose. Per 792's own discipline the reviewer
should not be the implementer.

## Sequencing note

Held deliberately until 793 restores library flow, and **ONNX Runtime, Lucene and llama.cpp are held
back as this lane's validation set** (792 §33): three changes independently expected to move
something. If the axis cannot detect a shift across them, the axis is wrong — and that is worth
learning where the answer is known not to be "no change."
