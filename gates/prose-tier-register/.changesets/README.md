# `gates/prose-tier-register/.changesets/`

Per-PR classification declarations for changes to
`.claude/rules/tier-register.md` (tempdoc 530 §Meta-loop).

## When to author a changeset

When a PR modifies the tier register — changes a rule's tier, removes a rule
row, adds a new row — declare why. Without a changeset, the gate fails with
`prose-tier-register/silent-tier-change` or `prose-tier-register/silent-row-removal`.

The point: the *choice of enforcement tier* for a discipline rule is itself a
load-bearing artifact. Moving a rule from `prose-only` to `gate` (because a
gate now exists) is a discipline event worth surfacing in PR review, not a
silent edit.

```markdown
---
classification: tier-change
tempdoc: 530
---
Rule 16 (Before Appending to CLAUDE.md) moved from prose-only → gate now
that prose-tier-register is wired. References gate id 'prose-tier-register'.
```

## Allowed `classification` values

| Value | Use when | Effect |
|---|---|---|
| `tier-change` | An existing rule's tier was updated (e.g., new gate became available) | Pass |
| `new-rule-registered` | A new rule was added to the register | Pass |
| `rule-retired` | A rule row was removed (rule deprecated) | Pass |
| `emergency-override` | Must merge without normal review | Pass with warning |

## Required fields

- `classification:`
- `tempdoc:` / `adr:` (recommended)

## See also

- `.claude/rules/tier-register.md` — the seed data
- Tempdoc 530 §Meta-loop
- Kernel runner: `scripts/governance/run.mjs`
