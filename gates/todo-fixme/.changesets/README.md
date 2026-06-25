# `gates/todo-fixme/.changesets/`

Per-PR classification declarations for the `todo-fixme` gate (tempdoc 530 Layer 2).

When the gate fails (typically with `todo-fixme/silent-growth` or `todo-fixme/silent-baseline-shift`),
author a changeset under this directory:

```markdown
---
classification: declared-growth  # or declared-regression / merge-import / emergency-override
tempdoc: NNN                      # or adr: NNNN — required
---
Justification for why this change is acceptable.
```

See `docs/reference/contributing/discipline-gate-kernel.md` for the full protocol.
See `scripts/governance/gates/todo-fixme/classifications.mjs` for allowed classification values.
