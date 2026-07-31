---
classification: merge-import
tempdoc: 801
---

`env_sysprop_pairs` 239 → 240, inherited by merging `origin/main` (#350, tempdoc 617).

**This branch adds no configuration.** `EnvRegistry.java` has no diff against `origin/main`
(verified before writing this). The growth is `EnvRegistry.APP_VERSION`
(`justsearch.app.version` / `JUSTSEARCH_APP_VERSION`), declared and accepted in
`617-app-version-registered.md` when #350 landed.

**Same toll `802-merge-import-app-version.md` already paid and documented**: `loadChangesets`
does PR-scope discovery, so a changeset stops counting the moment it merges, while
`baseline.txt` still recorded 239 — every branch opened across the #350 boundary saw an
undeclared 239 → 240 regardless of what it touched. This is the second branch to pay it.

**This changeset also ends the toll**: `baseline.txt` advances 239 → 240 here, which 802
flagged as the outstanding bookkeeping. The truth table treats a baseline raise under a
declared classification as `info`, so the raise is covered by this declaration — it records
617's already-accepted growth, not new headroom. The next real growth (240 → 241) still
needs its own justification.
