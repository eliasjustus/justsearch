---
classification: tier-change
tempdoc: 620
---
Add a `hook-hint` tier and move two delivery-backed rows onto it.

**New tier `hook-hint` (~85%)** — a PreToolUse/PostToolUse *non-blocking* hint hook that
*delivers* a rule at its moment of relevance (raises salience) but does not enforce. It sits
between blocking `hook` (~100%) and `prose-only` (~70%).

**Rows retiered (`prose-only` → `hook-hint`):**
- Row 22 `after-compaction-verify` — `compact-restore.mjs` now writes the worktree dir + branch
  into the post-compaction state block (tempdoc 620 Part V), delivering the check instead of
  relying on the agent to recall it. Resolves-to: `hook:compact-restore.mjs`.
- Row 32 `tempdocs-are-dated-history` — `tempdoc-age-hint.mjs` age-stamps every `docs/tempdocs/**`
  Read with date/status + newer-count (tempdoc 620 Part V). Resolves-to: `hook:tempdoc-age-hint.mjs`.

**Why (tempdoc 620 VI.B):** the register previously had no tier between `prose-only` (70%) and
blocking `hook` (100%), so it could not honestly classify a *delivery hint* — a hook that raises a
rule's salience at the right moment but does not block. Both rows are delivery-backed, not enforced,
so `hook-hint` is their accurate tier.

**Scope:** deliberately limited to these two rows. Other delivery-assisted rows stay put — row 6
(`fix-root-causes`) has a real `lint` subset via `check-suppression-ratchet.mjs`, and row 5
(`explore-before-implementing`) is a broad judgment rule with only partial `seam-hint` delivery.
The tier enum lives in `scripts/governance/gates/prose-tier-register/truth-table.mjs`; the
marker-required set in `enforcer.mjs` now includes `hook-hint` (a hint hook resolves to a real
hook file).
