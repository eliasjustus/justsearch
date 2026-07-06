---
title: Development Philosophy
type: reference
status: stable
description: "Issue tracking tiers, observation workflow, and documentation update rules."
---

# Development Philosophy

This project follows **organic development**: issues and rough edges are captured as they're noticed, not as separate investigation tasks.

**`docs/observations.md`** is the store for this — a channel of grouped **conditions**, not a flat inbox (tempdoc 680). During any task, if you notice a behavioral issue -- something that affects users, causes bugs, or creates friction -- log ONE flat line via `node scripts/agent-analytics/note-observation.mjs "<description> — \`file:line\`"`. **Do not read the store first and do not check for duplicates**: re-observation is signal — at the next fold it bumps the matching condition's `seen` count, which is the triage ranking. Skip structural commentary (large files, naming style) unless it directly caused a problem. Don't stop to investigate unless explicitly asked. Just record and continue.

## Two tiers of issue tracking

| Tier | Location | Friction | Lifetime | When to use |
|------|----------|----------|----------|-------------|
| Observations | `docs/observations.md` (grouped conditions; per-session shards in `docs/observations.d/`) | Low (one line, write-blind) | Until routed, retired, or parked at a triage pass | Notice something mid-task |
| Formal issues | `docs/reference/issues/` | High (ID, severity, evidence) | Until resolved or decided | Verified bugs, known trade-offs, tracked risks |

Conditions are processed at the maintainer's periodic triage pass (`node scripts/agent-analytics/observations-triage.mjs` is the read-model; `--probe` re-runs each condition's probe — exit 0 means the condition is gone and writes a *proposed* retirement). Kinds route conditions onward: **defect** → `docs/reference/issues/` or a domain register; **environment** (facts about main/CI/machines that verification hits) → `scripts/agent-analytics/expected-state.v1.json`, delivered by the `known-state-hint` hook; **lesson** → hooks / `agent-lessons.md` / postmortems; **follow-up** → its owning tempdoc or register. Resolved conditions are **deleted** when fixed — the commit (or tempdoc) that made the fix is the permanent record — and deletion is always a human act; automation only proposes.

Architectural trade-offs and conscious design tensions are tracked as formal issues with `accepted-trade-off` status in the appropriate issue file. Intentionally closed items (won't-fix, deferred, accepted) move to `docs/reference/issues/decisions.md` with rationale preserved.

**Tempdocs** (`docs/tempdocs/`) are for active implementation work — investigation logs, planning docs, and session-scoped notes. They are not part of the issue tracking system.

## Issue lifecycle rules

- Resolved issues are **deleted** from the issue file, not marked closed.
- Items evaluated and intentionally closed (won't-fix, deferred, accepted) move to `docs/reference/issues/decisions.md` with rationale preserved.
- Issue files must contain only actionable items. If it's not something to fix, it doesn't belong there.

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
