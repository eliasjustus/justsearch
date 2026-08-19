---
classification: merge-import
tempdoc: 857
---

`yaml_keys` baseline advanced 110 → 112 and `env_sysprop_pairs` 241 → 243, recording growth that was
**already declared, argued and accepted** in `854-branch-ramp-bounds.md` when #517 merged (the
Stage-3B branch-ramp bounds, a `wrong-gate` fix whose defaults are byte-identical).

**This is the treadmill `config-surface-advance-baseline-to-240.md` documented, recurring.** That
entry (tempdoc 803) spelled out the mechanism: `loadChangesets` does PR-scope discovery — only
changesets *added relative to the baseline ref* count — so once a declaration merges it becomes part
of the baseline and stops counting, while `baseline.txt` still records the pre-growth number. Every
branch opened afterwards then sees the same undeclared growth and fails.

Reproduced exactly here, and worth recording because the reproduction was legible: this branch's
gate run PASSED while its `HEAD~1` was the pre-merge commit (the #517 changeset was still "new" in
that range) and FAILED, with the same tree, the moment a later commit moved `HEAD~1` past the merge.
Same files, same generated matrix (`yaml_keys=112 env_sysprop_pairs=243 config_keys=56 rows=299`),
opposite verdict — the verdict was a function of history position, not of content. `main` is green at
`b816b98e` for the same reason and stops being green for every branch cut after it.

**Filed from an unrelated branch, deliberately.** PR #516 is a frontend-only change (Search v3
run-step keyboard navigation) that touches no configuration key whatsoever. Filing another
per-branch `merge-import` declaration would have unblocked this one PR and left the next one to
rediscover it — which is the treadmill, not a fix. Advancing the baseline is what stops it.

**The ratchet's teeth are unaffected.** With the baseline at 112 / 243, the next growth fires exactly
as before and needs its own justification. Nothing is excused in advance; one accepted decision is
recorded so the gate stops re-litigating it. `--rebalance` cannot do this — the baseline only
ratchets DOWN by design, so advancing it is necessarily a deliberate act.

`854-branch-ramp-bounds.md` is left in place as the record of what was accepted and why.

**The filename deliberately carries no tempdoc number**, matching
`config-surface-advance-baseline-to-240.md`. `check-tempdoc-numbers` reads a leading number in a
changeset filename as a claim on that number, so a `NNN-` prefix here would collide with the tempdoc
that owns it — which is exactly what happened when this entry was first filed as `854-…` and then
`857-…`. A baseline advance belongs to the gate, not to a lane, so it is named for the gate.
