# `gates/npm-audit/.changesets/`

Per-PR classification declarations for new high/critical GitHub advisory identities or
severity escalations in npm lockfiles (tempdocs 530 and 921). The `npm-audit` directory
name is the stable historical gate id; npm's audit endpoint is no longer the evidence
provider.

## When to author a changeset

When a PR introduces a tracked-severity advisory identity (default: `high` + `critical`)
or raises an accepted identity's severity above its pin in
`scripts/ci/github-advisory-baseline.v1.json`, declare why and repin that exact identity
in the same change. Without a
changeset, the gate fails with `npm-audit/silent-regression` — the same
mechanism the legacy `--write-baseline` flag silently bypassed.

Create a file named after the work, e.g., `upgrade-react-18.md`:

```markdown
---
classification: lockfile-import
adr: 0026-manual-ci-triggering
---
React 18 upgrade added one transitive high-severity advisory we can't patch upstream yet;
tracked in the owning tempdoc's open-items section. Its GHSA identity and severity are
accepted in the baseline until the upstream fix lands.
```

## Allowed `classification` values

| Value | Use when | Effect |
|---|---|---|
| `declared-regression` | A specific advisory was reviewed and accepted | Pass with `npm-audit/declared-regression` |
| `lockfile-import` | Regression imported via lockfile sync from upstream | Pass with `npm-audit/lockfile-import` |
| `emergency-override` | Must merge before an advisory is fixable upstream | Pass with `npm-audit/emergency-override` |
| `severity-decrease` | Author wants to explicitly document a resolution or severity improvement (optional; kernel auto-rebalances) | Informational |

## Required fields

- `classification:` — one of the values above.
- `tempdoc:` / `adr:` — references the work this regression covers.

## Aggregation

Same shape as the class-size gate: any non-shrink classification covers all
regressions in the PR. PR-scope discovery applies (only changesets added or
modified vs. the baseline ref count).

## See also

- Tempdoc 530: `docs/tempdocs/530-class-size-ratchet-automation.md`
- Producer: `scripts/ci/report-github-advisories.mjs`
- Enforcer: `scripts/governance/gates/npm-audit/enforcer.mjs`
- Baseline file: `scripts/ci/github-advisory-baseline.v1.json`
