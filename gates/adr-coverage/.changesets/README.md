# `gates/adr-coverage/.changesets/`

Per-PR classification declarations for the `adr-coverage` gate (tempdoc 530 Layer 2).

When the gate fails (`adr-coverage/stale-coverage` or `adr-coverage/probe-failed`),
author a changeset under this directory:

```markdown
---
classification: covers-updated   # see classifications.mjs for the full vocabulary
tempdoc: NNN                     # or adr: NNNN — required
---
Justification for why this change is acceptable.
```

The vocabulary is `covers-added` / `covers-updated` / `adr-superseded` /
`probe-added` / `probe-updated` / `probe-retired` / `emergency-override` — this gate does
**not** use the count-ratchet family (`declared-growth` and friends); the loader rejects them.

`adr-coverage/probe-failed` is a prompt to re-examine the decision
(`docs/decisions/README.md` § How to re-examine an ADR), not a lint to silence: a
`probe-updated` changeset is for a probe whose *mechanism* moved, never for a premise
that stopped being true.

See `docs/reference/contributing/discipline-gate-kernel.md` for the full protocol.
See `scripts/governance/gates/adr-coverage/classifications.mjs` for allowed classification values.
