---
classification: declared-growth
tempdoc: 854
---
Advances the baseline to the counts 854 W1 already grew and declared
(`854-branch-ramp-bounds.md`, merged in PR #517): yaml_keys 110 -> 112,
env_sysprop_pairs 241 -> 243 (the two `index.hybrid.branch_ramp.*` bounds that
separate the Stage-3B branch ramp from SPLADE's shared constants).

Why a second changeset: the changeset-loader only honors a changeset that is in
the CURRENT diff against the baseline ref, so once #517 merged, every
subsequent PR's merge-group run compared live 112/243 against the stale 110/241
baseline with no eligible changeset and failed `config-surface/undeclared-growth`
(observed: PR #515's merge-group run 32273721902, and it would have hit every
queued PR). This advance makes the post-merge baseline match main, mirroring the
`config-surface-advance-baseline-to-240.md` precedent. Net growth remains exactly
the two W1 knobs; nothing new is licensed here.
