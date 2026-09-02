---
classification: unit-renormalization
tempdoc: 910
---

**Eleven baseline rows moved up because the unit changed, not because the tree gained dead code.**

Knip reports a module in exactly one of two shapes, and the ratchet stores one number per path:

- no consumer at all -> one `files[]` entry, i.e. **1**;
- some consumer -> one entry **per unused export/type**, i.e. **N**.

So importing a single symbol from a whole-file-unused module flips its row `1 -> N` with no new
dead code, and the gate calls that `dead-code/silent-growth`. Measured on `origin/main` at
`bff70561`, 2026-09-02: **23** of the 186 baseline rows were pinned at the whole-file `1`, i.e. 23
rows one import away from a false red.

**Reproduced, not reasoned about.** Adding `import { fetchFolders } from './domains/browse'` to
`modules/ui-web/src/api/http.ts` and re-running `npm --prefix modules/ui-web run knip:report` moved
`src/api/domains/browse.ts` from its `files[]` shape to two per-export findings, and `origin/main`'s
enforcer run against `origin/main`'s baseline reported exactly:

```
error dead-code/silent-growth | src/api/domains/browse.ts: 1 → 2 unused exports without declared changeset
```

The module declares four exports and gained a consumer. That is a strict improvement being gated as
a regression.

**The fix normalizes the unit** (`scripts/governance/gates/dead-code/export-count.mjs`): a whole-file
finding now counts the module's own declared export surface, floored at 1. That number is an upper
bound on any per-export count knip can later report for the module, so `1 -> N` becomes `N -> (<= N)`
— a shrink — while a genuinely new dead export still pushes past the pin. After the fix the same probe
reports `dead-code/rebalance-available | src/api/domains/browse.ts: 2 < pinned 4` and nothing else:
the import alone produces no growth finding anywhere, which is the whole point.

**That real growth is still caught was measured separately, not inferred from the same probe.**
Appending a genuinely new dead export to a whole-file-unused module —
`export const scratchDeadExport = 1;` in `src/api/domains/status.ts` — and re-running gives:

```
error dead-code/silent-growth | src/api/domains/status.ts: 2 → 3 unused exports without declared changeset
```

Both probe files were restored byte-for-byte afterwards.

**"Own declared" is measured, not assumed.** A named import *through* a pure `export * from` barrel
(`src/api/index.ts`) removes that barrel's row entirely — knip attributes the still-unused names to
the origin module, never to the barrel. Crediting a barrel with its transitive surface would have
pinned `src/api/index.ts` at **105** instead of **1**, which is the over-permissive mistake this
changeset would otherwise have shipped. Named re-exports a module writes itself
(`export { A } from './x'`) *are* attributed to it, so those count — hence
`shape-handlers/index.ts 9` and `aggregate-substrate/index.ts 29`.

**What moved — 11 of the 23 whole-file rows; the other 12 were already correct at 1:**

| path | before | after |
|---|---|---|
| `src/shell-v0/aggregate-substrate/index.ts` | 1 | 29 |
| `src/api/domains/indexing.ts` | 1 | 13 |
| `src/api/generated/shape-handlers/index.ts` | 1 | 9 |
| `src/api/domains/inference.ts` | 1 | 7 |
| `src/api/domains/settings.ts` | 1 | 5 |
| `src/api/domains/browse.ts` | 1 | 4 |
| `src/api/wireContractVersion.ts` | 1 | 3 |
| `src/spike/NativePopoverSpike.ts` | 1 | 3 |
| `src/api/domains/status.ts` | 1 | 2 |
| `src/shell-v0/commands/coreSelectionActions.ts` | 1 | 2 |
| `src/shell-v0/router/tauriBridge.ts` | 1 | 2 |

Unchanged at 1 (0 declared exports, floored, or genuinely 1): `scripts/capture-evidence-bundle.mjs`,
`src/api/index.ts`, `src/api/domains/index.ts`, `src/api/domains/suggest.ts`,
`src/shell-v0/controllers/retainedScroll.ts`, `src/shell-v0/components/ResolutionStats.ts`,
`src/shell-v0/components/SelectionActionsMenu.ts`, and the five
`src/api/generated/shape-handlers/core-*.ts` handlers.

Row count is unchanged at 186; no row was added or deleted. The gate is green with **0 findings** —
green because the pin equals the measurement, not because this file suppresses anything.

**This changeset covers nothing, deliberately.** `unit-renormalization` is a new classification
introduced for exactly this shape and is **not** in the enforcer's growth-covering set
(`declared-growth` / `merge-import` / `emergency-override`), because those set `growthCovered`, which
suppresses `silent-growth` for the WHOLE run. A counting change must never buy a blanket. It is filed
because a baseline move with no record is the silent pin-bump the kernel exists to stop.
