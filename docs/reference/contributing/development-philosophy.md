---
title: Development Philosophy
type: reference
status: stable
description: "Issue tracking tiers, observation workflow, and documentation update rules."
---

# Development Philosophy

This project follows **organic development**: issues and rough edges are captured as they're noticed, not as separate investigation tasks.

**`docs/observations.md`** is the store for this — a channel of grouped **conditions**, not a flat inbox (tempdoc 680). During any task, if you notice a behavioral issue -- something that affects users, causes bugs, or creates friction -- log ONE flat line via `node scripts/agent-analytics/note-observation.mjs "<description> — file:line"`. **Do not read the store first and do not check for duplicates**: re-observation is signal — at the next fold it bumps the matching condition's `seen` count, which is the triage ranking. Skip structural commentary (large files, naming style) unless it directly caused a problem. Don't stop to investigate unless explicitly asked. Just record and continue.

## Two tiers of issue tracking

| Tier | Location | Friction | Lifetime | When to use |
|------|----------|----------|----------|-------------|
| Observations | `docs/observations.md` (grouped conditions; per-session shards in `docs/observations.d/`) | Low (one line, write-blind) | Until routed, retired, or parked at a triage pass | Notice something mid-task |
| Domain registers | `docs/reference/search-quality-register.md`, `docs/reference/inference-runtime-register.md` | High (ID, evidence, verification date) | Until the finding is superseded by shipped work | Empirical findings and standing trade-offs in a register's domain |

The standing per-domain issue registers under `docs/reference/issues/` were **retired** (tempdoc 821 §7 D5, 2026-08-12) — most had not been stamped since 2026-02/04 and real tracking had already migrated to the observations store, the two domain registers, and tempdocs. Live entries were routed into the store at retirement; the deleted files' content is recoverable from git history.

Conditions are processed at the maintainer's periodic triage pass (`node scripts/agent-analytics/observations-triage.mjs` is the read-model; `--probe` re-runs each condition's probe — exit 0 means the condition is gone and writes a *proposed* retirement). Kinds route conditions onward: **defect** → the owning domain register, or its owning tempdoc when a fix is being planned; **environment** (facts about main/CI/machines that verification hits) → `scripts/agent-analytics/expected-state.v1.json`, delivered by the `known-state-hint` hook; **lesson** → hooks / `agent-lessons.md` / postmortems; **follow-up** → its owning tempdoc or register. Resolved conditions are **deleted** when fixed — the commit (or tempdoc) that made the fix is the permanent record — and deletion is always a human act; automation only proposes.

Architectural trade-offs and conscious design tensions belong in an ADR (`docs/decisions/`) when the "why not X?" answer is worth preserving, or as a register entry with its revisit trigger. A trade-off that is neither is a condition in the store with `status: parked (<reason>)`.

**Tempdocs** (`docs/tempdocs/`) are for active implementation work — investigation logs, planning docs, and session-scoped notes. They are not part of the issue tracking system.

## Issue lifecycle rules

- A fixed finding is **deleted** from its register or the store, not marked closed — the commit that fixed it is the permanent record.
- An item evaluated and intentionally closed (won't-fix, deferred, accepted) needs a durable home for its rationale: an ADR, a register entry with a revisit trigger, or a parked condition. Do not leave closed items sitting in an active list.
- Registers and the store hold only items someone could act on. If it is not something to fix or revisit, it belongs in an ADR or nowhere.

## Softness portfolio

Seams where enforcement is **deliberately left soft** — each with its reason and the condition
under which the choice is revisited. This section exists so deliberate fail-open choices stay
visible decisions instead of silent drift; it is also the routing destination for
posture-adjudication findings (tempdoc 680's routing lanes). Add a row when a softening choice is
made deliberately; remove it when the revisit condition fires and the seam is hardened or the
softness re-affirmed.

| Seam | Deliberate posture | Reason | Revisit when |
|------|--------------------|--------|--------------|
| PIT mutation ratchet (`test-efficacy` gate) | `workflow_dispatch`-only, not per-PR CI | Self-hosted-runner availability (ADR-0026) | A hosted lane can carry the PIT run within budget |
| Governance kernel gates in public CI | Not in the required-check set | Runner-availability constraint (ADR-0026 / ADR-0044); local-first verification remains primary | A hosted kernel lane proves stable — then add to required checks |
| FE runtime schema validation (`parseWireContract` / `validateWithFallback`) | Degrade-and-log in production rather than crash | Crashing the user's UI on wire drift punishes the user, not the developer | A dev/CI-throws split lands, or a real-use drift incident shows logging alone is insufficient |

## Upgrade-safe defaults

New config keys that change search or ranking behavior must register their programmatic default via `putDefault(key, value)` in the relevant `contributeYaml*()` method of `ResolvedConfigBuilder`. This ensures the effective value appears in:
- Startup logs (`logResolutions()` at INFO level)
- `/api/debug/effective-config` as `source: "default", ordinal: 100`

Without this, operators see "unset" when the runtime is silently using the default. The `putDefault` ordinal (100) is the lowest priority, so any explicit YAML, env var, or system property setting wins.

## Doc update rules

- When you change behavior/contract: update `docs/explanation/` and/or `docs/reference/`.
- When you record an architectural decision with alternatives: create an ADR in `docs/decisions/`.
- After adding/changing canonical docs: run `node scripts/docs/llmstxt-generate.mjs` to regenerate the index.
- When the Gradle module graph changes (`settings.gradle.kts` or `modules/**/build.gradle.kts`): run `node scripts/architecture/module-deps.mjs --update-canonical` and verify with `--check-canonical`.
- When you write notes/ideas: use `docs/tempdocs/` or `docs/future-features/` (noncanonical).
- Full guide (frontmatter, CI checks, doc types): `docs/reference/contributing/writing-docs-for-ai.md`
