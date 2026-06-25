# `gates/adr-coverage/.changesets/`

Per-PR classification declarations for the `adr-coverage` gate (tempdoc 530 Layer 2).

When the gate fails (typically with `adr-coverage/silent-growth` or `adr-coverage/silent-baseline-shift`),
author a changeset under this directory:

```markdown
---
classification: declared-growth  # or declared-regression / merge-import / emergency-override
tempdoc: NNN                      # or adr: NNNN — required
---
Justification for why this change is acceptable.
```

See `docs/reference/contributing/discipline-gate-kernel.md` for the full protocol.
See `scripts/governance/gates/adr-coverage/classifications.mjs` for allowed classification values.
