# `gates/adr-coverage/.changesets/`

Per-PR classification declarations for the `adr-coverage` gate (tempdoc 530 Layer 2).

When the gate fails (`adr-coverage/stale-coverage`, `adr-coverage/probe-failed`,
`adr-coverage/risk-instrument-unresolved` or `adr-coverage/risk-register-malformed`),
author a changeset under this directory:

```markdown
---
classification: covers-updated   # see classifications.mjs for the full vocabulary
tempdoc: NNN                     # or adr: NNNN — required
---
Justification for why this change is acceptable.
```

The vocabulary is `covers-added` / `covers-updated` / `adr-superseded` /
`probe-added` / `probe-updated` / `probe-retired` / `risk-added` /
`risk-instrument-updated` / `emergency-override` — this gate does
**not** use the count-ratchet family (`declared-growth` and friends); the loader rejects them.

`adr-coverage/probe-failed` is a prompt to re-examine the decision
(`docs/decisions/README.md` § How to re-examine an ADR), not a lint to silence: a
`probe-updated` changeset is for a probe whose *mechanism* moved, never for a premise
that stopped being true.

`adr-coverage/risk-instrument-unresolved` works the same way for
`docs/reference/architectural-risks.md`: `risk-instrument-updated` is for an instrument
whose *target* moved (a test renamed, a lane's tempdoc finally numbered), never for a
promised instrument that was never built. Deleting the reference is not a fix — a row whose
instrument stops resolving is a lane that closed without building what it promised.

See `docs/reference/contributing/discipline-gate-kernel.md` for the full protocol.
See `scripts/governance/gates/adr-coverage/classifications.mjs` for allowed classification values.
