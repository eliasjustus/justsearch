---
classification: merge-import
tempdoc: 803
---

`env_sysprop_pairs` baseline advanced 239 → 240, recording growth that was **already declared and
accepted** in `617-app-version-registered.md` when #350 merged.

**Why the baseline had to move, rather than another per-branch declaration.** `loadChangesets` does
PR-scope discovery — only changesets *added relative to the baseline ref* count. Once a declaration
merges it becomes part of the baseline and stops counting, while `baseline.txt` still records the
pre-growth number. Every branch opened afterwards therefore sees the same undeclared 239 → 240 and
must file its own `merge-import` entry. This session filed one on PR #353 and hit the identical
failure again on the very next branch — twice in one day, for growth accepted once.

That is a treadmill, and its cost is not the ceremony: it is that a gate which asks for a
justification nobody reads teaches contributors to write empty ones. The pressure 799 K.4 wanted was
against *undeclared regrowth*, and this growth was declared, argued, and reviewed.

**The ratchet's teeth are unaffected.** With the baseline at 240, the next growth (240 → 241) fires
exactly as before and needs its own justification. Nothing is being excused in advance — one
accepted decision is being recorded so the gate stops re-litigating it.

`--rebalance` cannot do this: the baseline only ratchets DOWN by design, so advancing it is
necessarily a deliberate act.

The two prior changesets (`617-app-version-registered.md`, `802-merge-import-app-version.md`) are
left in place as the record of what was accepted and why.
