---
title: Development Philosophy
type: reference
status: stable
description: "Issue tracking tiers, observation workflow, and documentation update rules."
---

# Development Philosophy

This project follows **organic development**: issues and rough edges are captured as they're noticed, not as separate investigation tasks.

**`docs/observations.md`** is a living document for this. During any task, if you notice a behavioral issue -- something that affects users, causes bugs, or creates friction -- add a one-line entry to the relevant section. Skip structural commentary (large files, naming style) and anything already tracked in `docs/reference/issues/`. Don't stop to investigate unless explicitly asked. Just record and continue.

## Two tiers of issue tracking

| Tier | Location | Friction | Lifetime | When to use |
|------|----------|----------|----------|-------------|
| Observations | `docs/observations.md` | Low (one line) | Long-lived | Notice something mid-task |
| Formal issues | `docs/reference/issues/` | High (ID, severity, evidence) | Until resolved or decided | Verified bugs, known trade-offs, tracked risks |

Observations get promoted to formal issues when they need code evidence and severity assignment. Resolved observations are **deleted** from `docs/observations.md` when fixed, the same as formal issues below — not checked off and left in place. The commit (or tempdoc) that made the fix is the permanent record; the inbox does not need to also carry one.

Architectural trade-offs and conscious design tensions are tracked as formal issues with `accepted-trade-off` status in the appropriate issue file. Intentionally closed items (won't-fix, deferred, accepted) move to `docs/reference/issues/decisions.md` with rationale preserved.

**Tempdocs** (`docs/tempdocs/`) are for active implementation work — investigation logs, planning docs, and session-scoped notes. They are not part of the issue tracking system.

## Issue lifecycle rules

- Resolved issues are **deleted** from the issue file, not marked closed.
- Items evaluated and intentionally closed (won't-fix, deferred, accepted) move to `docs/reference/issues/decisions.md` with rationale preserved.
- Issue files must contain only actionable items. If it's not something to fix, it doesn't belong there.

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
