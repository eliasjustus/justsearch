---
classification: merge-import
tempdoc: 844
---

`yaml_keys` 110 → 112 and `env_sysprop_pairs` 241 → 243, inherited by merging `origin/main`
(#517, tempdoc 854).

**This branch adds no configuration.** `git diff 8ffbd449...0af66617 --
modules/configuration/src/main/java/io/justsearch/configuration/EnvRegistry.java` is empty.
The growth is `EnvRegistry.HYBRID_BRANCH_RAMP_FULL_WEIGHT_MAX_TOKENS` /
`HYBRID_BRANCH_RAMP_ZERO_WEIGHT_MIN_TOKENS`, already declared and accepted in
`854-branch-ramp-bounds.md` when #517 landed.

**Why a second declaration is needed.** `loadChangesets` does PR-scope discovery — only
changesets added or modified relative to the baseline ref count. Once #517 merged, its
changeset became part of the baseline and stopped counting, while
`gates/config-surface/baseline.txt` still records 110 / 241. The ratchet therefore sees an
undeclared 110 → 112 / 241 → 243 on every branch opened across that merge, whatever the
branch touched. `--rebalance` cannot help: the baseline only ratchets DOWN by design.

`merge-import` is the classification the gate provides for exactly this, so this records the
inheritance without restating #854's case or claiming ownership of the decision. The next
real growth still needs its own justification.

**Diagnosis trail, since the symptom is confusing.** The gate passed on PR #513's own CI,
then failed in the merge queue's `merge-group` run — the queue evaluates the PR merged with
current `main`, which is where the inherited growth first appears. Locally the gate passed
with matrix inputs byte-identical to CI's (`yaml_keys=112 env_sysprop_pairs=243
config_keys=56 rows=299`), because the local run had already merged `main` and so saw #854's
changeset as part of the working tree rather than as PR-scope. Same data, different verdict,
entirely from changeset scoping.

**This is the third occurrence, which is the part worth acting on.**
`801-merge-import-app-version.md` and `802-merge-import-app-version.md` are the same toll for
`env_sysprop_pairs` 239 → 240, and 802 already logged the follow-up: "baseline.txt was never
advanced to 240, so every subsequent branch pays this same toll until it is." It was later
advanced (`config-surface-advance-baseline-to-240.md`), the pattern repeated, and here we are
at 241 → 243. Not fixed here — advancing another lane's ratchet mid-publish is #854's
bookkeeping and a gate-design question, not this branch's — but re-logged to the inbox with
the recurrence count, because "log it and move on" has now demonstrably failed twice. The
durable fix is to make advancing `baseline.txt` part of merging a `declared-growth`
changeset, rather than a separate act someone must remember.
