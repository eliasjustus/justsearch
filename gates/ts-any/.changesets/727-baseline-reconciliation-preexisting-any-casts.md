---
classification: merge-import
tempdoc: 727
---

Both any-casts are the identical, pre-existing `(import.meta as any).env?.DEV === true` Vite
dev-flag check — `devMode.ts` since commit `daa74bd` (tempdoc 683, PR #77) and
`MultiplexedStream.ts` since `a9694aa` (tempdoc 662, PR #22). Neither was ever registered in
`gates/ts-any/baseline.txt`, which has been untouched since the initial-release seed commit
(`29579e5`) — this is legacy content arriving via merge/history, not new growth introduced by
any recent branch. Found during tempdoc 727's publish verification pass (a full,
un-filtered governance-gate-kernel run against the merged branch surfaced it); reconciling the
baseline to reality here rather than leaving it silently red for the next PR that happens to
touch either file.
