---
classification: declared-growth
tempdoc: 854
---

`yaml_keys` 110 → 112 and `env_sysprop_pairs` 241 → 243:
`EnvRegistry.HYBRID_BRANCH_RAMP_FULL_WEIGHT_MAX_TOKENS` /
`EnvRegistry.HYBRID_BRANCH_RAMP_ZERO_WEIGHT_MIN_TOKENS`
(`index.hybrid.branch_ramp.full_weight_max_tokens` /
`index.hybrid.branch_ramp.zero_weight_min_tokens`,
`JUSTSEARCH_HYBRID_BRANCH_RAMP_FULL_WEIGHT_MAX_TOKENS` /
`JUSTSEARCH_HYBRID_BRANCH_RAMP_ZERO_WEIGHT_MIN_TOKENS`).

**This is a wrong-gate fix, not a new tunable.** Tempdoc 784 §K (the fusion-attribution
study) proved `justsearch.splade.full_weight_max_tokens` / `.zero_weight_min_tokens` were
ONE pair of raw sysprop constants read by TWO independent levers in `HybridFusionUtils`:
the Stage-3A SPLADE parent-length fade (`spladeParentLengthMultiplier`) and the Stage-3B
whole-vs-chunk branch ramp (`chunkBranchParentLengthMultiplier`). Raising the bound for a
SPLADE experiment silently retuned branch balance ~4x (measured: the multiplier flipped
1.0 → 0.25 for every doc found by both branches) — a defect of the `wrong-gate` class, not
a design choice anyone made. Tempdoc 854 W1 charters the fix §K itself specified: give the
Stage-3B ramp its own bounds.

**Two new keys is the minimum, not a convenience split.** The ramp needs both the upper
and lower interpolation bound to be independently addressable — one new key alone (e.g.
only the zero-weight bound) would still leave the full-weight bound shared with SPLADE,
reproducing the same class of defect at the other edge of the ramp.

**Defaults are byte-identical, so this changes no shipped behavior today.** Both new keys
default to 1024 / 4096 — the exact values the shared constant used — pinned by
`HybridFusionUtilsPropertyTest`'s grid test and `ResolvedConfigBuilderTest`'s default-value
test. The growth is that a future SPLADE-bound experiment can no longer silently move the
branch ramp (and vice versa); no existing knob's value moves.

**Why it is not deletable.** The pre-fix state was not "two knobs sharing a
constant by design" — it was one constant with two accidental readers. Reverting to the
shared constant to keep the surface count flat would reintroduce the exact 784 §K defect
this lane exists to close.
