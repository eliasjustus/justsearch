---
classification: new-rule-registered
tempdoc: 563
---
New rule 32 (`tempdocs-are-dated-history`) registered at `prose-only` tier in the
CLAUDE.md Agent Discipline table.

Tempdoc 563 (frontend-rewrite backend-impact analysis) surfaced the motivating
failure: an agent synthesized across the whole `docs/tempdocs/` corpus treating
old and new tempdocs as equally current, and reported already-resolved/shipped
states as live blockers (§7 corrected 4 of 6 load-bearing claims against `main`).
Root cause: tempdocs are append-only, non-canonical design history, and their
codebase claims are a lagging indicator. The rule records the corrective
discipline — newer tempdocs have higher numbers (check the highest-numbered first
to gauge staleness), read frontmatter (`status`/`created`/`updated`), and verify
against `main` + canonical docs before trusting a tempdoc's claim. It is
`verify-don't-guess` (Hard Invariant #4) applied to documentation.

Tier is `prose-only`: "weight recency / read before trusting" is investigator
judgment with no mechanical check. The `catchesVia` cell notes the upgrade path —
a `PostToolUse(Read)` hook that stamps each `docs/tempdocs/**` read with its age +
status would move this to `hook` tier (a future `tier-change` changeset).

Rule anchored in `CLAUDE.md` (`<!-- rule:tempdocs-are-dated-history -->`); the
`Resolves to` column is `—` (no gate/hook/archunit marker at prose tier).
