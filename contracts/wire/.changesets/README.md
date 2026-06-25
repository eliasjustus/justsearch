# `contracts/wire/.changesets/`

Per-PR classification declarations for the wire-Category contract substrate
(slice 3a-1-8f §A.14).

## Authoring a changeset

When a PR modifies `contracts/wire/*.proto`, declare the **evolution rule**
that classifies the change. Create a new file in this directory named after
the change (e.g., `add-event-body-thresholds.md`):

```markdown
---
evolution-rule: additive-optional
---
Added `optional ThresholdSummary thresholds = 7` to `HealthEventBody`.
Backward-compatible with all existing producers and consumers.
```

## Allowed `evolution-rule` values

| Value | Severity | Required VERSION bump |
|---|---|---|
| `additive-optional` | low | patch |
| `additive-required` | medium | minor |
| `enum-value-added` | low | minor |
| `rename` | high | major |
| `remove` | high | major |
| `enum-value-removed` | high | major |
| `enum-value-renamed` | high | major |
| `type-change` | high | major |
| `package-rename` | high | major |

The contract-governance gate evaluates declared classification × structural
diff (via `buf breaking`) × VERSION delta as a single truth-table verdict.
Misclassified changes (e.g., removing a field but declaring `additive-optional`)
fail the gate.

## Aggregation rule

Highest-bump-wins across all `*.md` files in this directory (PR-scope: only
files added or modified in the current PR's diff are considered, per §A.18).

## Release lifecycle

At release time, files in `.changesets/` are consolidated into
`../CHANGELOG.md` under the new VERSION's heading; this directory is then
emptied. Consolidation tooling lives with slice 3a-1-8b's release-cadence
work; until then, consolidation is manual.

## See also

- Slice tempdoc: `docs/decisions/0040-wire-contract-format.md`
- Kernel runner: `scripts/contract-governance/run.mjs`
