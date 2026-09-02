---
classification: declared-growth
tempdoc: 911
---

`src/api/generated/schema-types/index.ts`: **51 → 53 unused exports.**

One row, entirely this branch's, and mechanical. Tempdoc 911 (885 UL.9) adds one generated wire
projection — `SSOT/schemas/failed-indexing-jobs-response.v1.json` →
`schema-types/failed-indexing-jobs-response.ts` — and `gen-wire-schema-types.mjs` re-exports every
target's root type and Zod const from the barrel it also generates. Two new targets' worth of
symbols in the barrel, two more unused exports there.

**The new module itself adds zero.** `failed-indexing-jobs-response.ts` exports exactly two symbols
and `shell-v0/components/FailedJobsDrawer.ts` imports both, so it does not appear in the report at
all. Only the barrel row moves — which is the same shape tempdoc 884 recorded when it added
`surface.ts` (45 → 51, `884-surface-projection-plus-preexisting-drift.md`), for the same reason.

**Why not simply consume them from the barrel instead.** That would zero the growth, but it would
break the thing the barrel exists beside: `governance/contract-surfaces.v1.json` declares
`FailedJobsDrawer.ts` as the consumer of `schema-types/failed-indexing-jobs-response`, and the
`contract-projection` gate's declared-consumer check requires that file to import *that module*, not
the barrel. Every existing consumer (e.g. `api/domains/indexing.ts` → `failed-jobs-response.js`)
imports the module directly for the same reason. The barrel's unused re-exports are the cost of
having a barrel over per-record modules; trading a gate that checks a real contract for a count is
not a trade worth making.

This is growth in generated output whose generator is the single authority — not a hand-written
export that lost its consumer.
