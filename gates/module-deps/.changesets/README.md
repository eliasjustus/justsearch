# `gates/module-deps/.changesets/`

Per-PR classification declarations for the `module-deps` gate (tempdoc 530 Layer 2).

When the gate fails (typically with `module-deps/silent-growth` or `module-deps/silent-baseline-shift`),
author a changeset under this directory:

```markdown
---
classification: declared-growth  # or declared-regression / merge-import / emergency-override
tempdoc: NNN                      # or adr: NNNN — required
---
Justification for why this change is acceptable.
```

See `docs/reference/contributing/discipline-gate-kernel.md` for the full protocol.
See `scripts/governance/gates/module-deps/classifications.mjs` for allowed classification values.
