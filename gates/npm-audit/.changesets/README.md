# `gates/npm-audit/.changesets/`

Per-PR classification declarations for npm-audit severity regressions
(tempdoc 530).

## When to author a changeset

When a PR raises the count of tracked-severity vulnerabilities (default:
`high` + `critical`) above its baseline in
`scripts/ci/npm-audit-ratchet-baseline.v1.json`, declare why. Without a
changeset, the gate fails with `npm-audit/silent-regression` — the same
mechanism the legacy `--write-baseline` flag silently bypassed.

Create a file named after the work, e.g., `upgrade-react-18.md`:

```markdown
---
classification: lockfile-import
adr: 0026-manual-ci-triggering
---
React 18 upgrade added one transitive high-severity advisory we can't
patch upstream yet; tracking in observations.md. Counts will be re-pinned
when the upstream fix lands.
```

## Allowed `classification` values

| Value | Use when | Effect |
|---|---|---|
| `declared-regression` | A specific advisory was reviewed and accepted | Pass with `npm-audit/declared-regression` |
| `lockfile-import` | Regression imported via lockfile sync from upstream | Pass with `npm-audit/lockfile-import` |
| `emergency-override` | Must merge before an advisory is fixable upstream | Pass with `npm-audit/emergency-override` |
| `severity-decrease` | Author wants to explicitly document an improvement (optional; kernel auto-rebalances) | Informational |

## Required fields

- `classification:` — one of the values above.
- `tempdoc:` / `adr:` — references the work this regression covers.

## Aggregation

Same shape as the class-size gate: any non-shrink classification covers all
regressions in the PR. PR-scope discovery applies (only changesets added or
modified vs. the baseline ref count).

## See also

- Tempdoc 530: `docs/tempdocs/530-class-size-ratchet-automation.md`
- Legacy script: `scripts/ci/check-npm-audit-ratchet.mjs` (parallel-runs during cutover)
- Enforcer: `scripts/governance/gates/npm-audit/enforcer.mjs`
- Baseline file: `scripts/ci/npm-audit-ratchet-baseline.v1.json`
