# `gates/runtime-state/.changesets/`

Declaration history for the `runtime-state` gate (tempdoc 737 §12c — the AI-runtime fork-killer,
sibling of `execution-surface`/tempdoc 553).

The gate's own baseline is `kind: git` (current-state fail-hard on the three checks in
`scripts/governance/gates/runtime-state/truth-table.mjs`), not a ratchet-file with a silent-*
ruleId — like `execution-surface`/`operation-surface`, it does not gate merges on a classified
changeset today. This directory exists for the same reason `prose-tier-register`'s does: a durable,
greppable record of *why* the register looks the way it does at each point in its history, starting
with its introduction.

```markdown
---
classification: new-rule-registered
tempdoc: 737
---
Why the register/gate was introduced or a row changed.
```

## Allowed `classification` values

| Value | Use when |
|---|---|
| `new-rule-registered` | The register/gate was introduced, or a new surface row was added |
| `tier-change` | An existing row's guard strength changed (e.g. exempt: → a real gate:/test:) |
| `rule-retired` | A surface row (or the whole register) was retired |

## See also

- `governance/runtime-state.v1.json` — the register.
- `scripts/governance/gates/runtime-state/` — the enforcer + truth table.
- `docs/tempdocs/737-ai-runtime-lifecycle-model.md` §12c — the design.
