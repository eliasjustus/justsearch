---
classification: merge-import
tempdoc: 802
---

`env_sysprop_pairs` 239 → 240, inherited by merging `origin/main` (#350, tempdoc 617).

**This branch adds no configuration.** Its diff is documentation plus one Python
`sources` section in the release composer. The growth is `EnvRegistry.APP_VERSION`
(`justsearch.app.version` / `JUSTSEARCH_APP_VERSION`), already declared and accepted in
`617-app-version-registered.md` when #350 landed.

**Why a second declaration is needed at all.** `loadChangesets` does PR-scope discovery —
only changesets *added or modified relative to the baseline ref* count. Once #350 merged,
its changeset became part of the baseline and stopped counting, while
`gates/config-surface/baseline.txt` still records 239. So the ratchet sees an undeclared
239 → 240 on every branch opened across that merge, regardless of what the branch touched.
`--rebalance` does not help: the baseline only ratchets DOWN by design.

`merge-import` is the classification the gate provides for exactly this — growth arriving
through a merge rather than authored here — so this records the inheritance without
restating #350's case or claiming ownership of the decision.

**Not a workaround.** The ratchet's pressure is against *undeclared* regrowth, and the
growth it is pointing at was declared. The next real growth (240 → 241) still needs its own
justification.

Noted for follow-up rather than fixed here, because it is #350's bookkeeping and a gate
design question rather than this branch's: baseline.txt was never advanced to 240, so every
subsequent branch pays this same toll until it is. Logged to the observations inbox.
