---
description: "TRIGGER when: editing baseline files (scripts/ci/npm-audit-ratchet-baseline.v1.json, .claude/rules/tier-register.md), authoring changesets under gates/<id>/.changesets/, running the discipline-gate kernel, or seeing a SARIF ruleId from prose-tier-register / npm-audit / consumer-drift / ssot-catalog-sync and unsure what to do. Loads the kernel's protocol + classification grammar + CLI subcommands."
user-invocable: true
---

# Discipline-gate kernel (tempdoc 530)

The kernel that gates ratchet-style hygiene metrics across the repo. The
canonical ratchet-file gates are `npm-audit`, `prose-tier-register`,
`consumer-drift` (tempdoc 531 — substrate consumer-floor enforcement), and
`ssot-catalog-sync` (root↔classpath catalog mirror enforcement);
`governance/registry.v1.json` is the full list.

(The size/count ratchet gates — `class-size`, `clone`, `ui-bundle`,
`exception-count` — were removed end-to-end for go-public, tempdoc 634. The
coverage-style human-audit gates `independent-review` and `ux-audit-closure`
were retired earlier — tempdoc 530 §Remediation.)

## Quickstart

```bash
# What would fail if I committed right now?
node scripts/governance/run.mjs --mode warn

# Just one gate
node scripts/governance/run.mjs --gate npm-audit --mode gate

# What gates does my diff affect?
node scripts/governance/run.mjs --preflight HEAD~1

# I see ruleId X — what does it mean + how do I fix it?
node scripts/governance/run.mjs --explain npm-audit/silent-baseline-shift

# Auto-author stub changesets for predicted-fail gates
node scripts/governance/run.mjs --suggest-changeset

# Apply auto-shrink rebalance writes (only-shrinks)
node scripts/governance/run.mjs --gate npm-audit --rebalance
```

## The changeset protocol

When a gate fails with a `*/silent-*` rule, the author owes a *classified
changeset* — a markdown file under `gates/<gate-id>/.changesets/<id>.md`
declaring why the failure-shaped change is acceptable.

```markdown
---
classification: declared-regression   # see allowed values below
tempdoc: 524                          # or adr: 0026 — one of these is required
---
A new high-severity advisory landed in a transitive dep with no patched
version yet; tracked upstream, accepted until the fix ships.
```

Without a `tempdoc:` or `adr:` field, the changeset-loader throws (Pass-5
discipline). The body is free-form; explain context not obvious from git.

## Allowed classifications per gate

| Gate | Classifications |
|---|---|
| `npm-audit` | `declared-regression` · `lockfile-import` · `emergency-override` · `severity-decrease` |
| `prose-tier-register` | `tier-change` · `new-rule-registered` · `rule-retired` · `emergency-override` |
| `consumer-drift` | `slot-retraction` · `grace-extension` · `emergency-override` (changeset frontmatter needs a `slot:` field naming the affected slot) |
| `ssot-catalog-sync` | `intentional-divergence` · `mirror-retirement` · `emergency-override` (changeset frontmatter needs a `mirror:` field naming the affected mirror) |

Read the gate's own README for nuances:
`gates/<id>/.changesets/README.md`.

## What the kernel catches (silent-bypass closures)

The kernel closes the silent escape-hatch classes the Pass-3+5 work
documented:

1. **Silent baseline-shifts** — relaxing the npm-audit baseline without a
   changeset → `npm-audit/silent-baseline-shift`.
2. **Silent tier-changes** — moving a register row's tier without a
   changeset → `prose-tier-register/silent-tier-change`.
3. **Silent unanchored rules** — new must/never/always sentences in
   `CLAUDE.md` / `.claude/rules/` outside any anchored section →
   `prose-tier-register/untagged-sentence`.

If you're authoring a rule, anchor it with `<!-- rule:<slug> -->` and add a
row to `.claude/rules/tier-register.md` with the same slug.

## See also

- `docs/tempdocs/530-class-size-ratchet-automation.md` — the design tempdoc (historical).
- `docs/reference/contributing/discipline-gate-kernel.md` — substrate reference.
- `governance/registry.v1.json` — gate registry (read-only at runtime).
