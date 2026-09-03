---
classification: declared-growth
tempdoc: 916
---
Declares the two configuration keys tempdoc 916 Part 2 (decision-review lane E) adds for the
aggregate-then-cut chunk-branch parent collapse:

- `index.hybrid.chunk_collapse_overfetch_multiplier` (Int, default **1**, clamped `>= 1`) — how
  many distinct parents the collapse scans before cutting, expressed as a multiple of the existing
  collapse cap (`limit × chunk_collapse_limit_multiplier`).
- `index.hybrid.chunk_collapse_aggregation_lambda` (Double, default **0.0**, clamped to `[0,1]`) —
  the weight a parent's non-best chunks carry in its collapse score,
  `max + λ · Σ 0.5^(i-1) · scoreᵢ`.

**Why these are keys and not constants.** The pair is the A/B instrument for a measured decision,
not a preference. `SearchExecutor.collapseChunkHitsToParents` was first-wins by input order and
stopped scanning the fused chunk list the instant it had enough distinct parents (lane E audit
finding 2), so a document whose evidence is spread over several mid-ranked passages could not
surface behind a handful of documents that owned the top chunks. Whether aggregating that evidence
*helps* is a retrieval-quality question with a real downside — corroboration-weighting can demote a
document with one excellent passage — and the register requires every such number to move only with
a run id and a σ statement. A knob that can be flipped on a backend restart is what makes the two
arms share one index; a rebuild between arms would itself be the confounder (the same argument
tempdoc 885 item 19 made for the NRT cadence keys).

The two are deliberately not three: the geometric decay ratio is fixed at `0.5`
(`SearchExecutor.CHUNK_COLLAPSE_REST_DECAY`) rather than exposed. λ already spans the whole
"how much does corroborating evidence count" axis; a second free parameter would double the sweep
matrix for no separable effect, and the register's cost of an unmeasured knob is the same as any
other dead config.

**Both defaults reproduce today's behaviour bit-for-bit.** At `(1, 0.0)` the scan cap equals the
collapse cap (so the loop breaks exactly where it did), every parent's aggregate equals its best
chunk's score (so no re-ordering key changes), and the sort is stable (so equal scores keep
first-seen fused order). `SearchExecutorChunkCollapseAggregationTest.defaultsReproducePre916`
asserts this against a reimplementation of the pre-916 loop as an oracle, at every limit from 1 to
5, rather than against hand-written expectations. Shipping OFF is therefore not caution — it is the
control arm the measurement needs.

**Post-measurement expectation, on the record:** if the pre-registered rule in 916 §D parks the
lever, these two keys come out rather than being left behind as selectable dead weight. The one
thing that would keep a parked lever is a follow-up arm the owner asks for; absent that, the
same "the losing arm's keys are removed" commitment 885 made applies here.

## Post-measurement status (2026-09-03) — PARKED, and why the keys are still here

The rule parked it on a split: legal-clerc-200 **+0.0050 R@10 / −0.0050 leak**, enron-qa
**−0.0067 R@10**, scifact bit-identical (916 §G.4, register F-056). Defaults are unchanged, so
the shipped engine behaves exactly as it did before this PR.

That triggers the commitment above, and it is **not yet honoured** — deliberately, and flagged
rather than quietly skipped. Deleting the keys now would leave the aggregate-then-cut code
permanently pinned to `(1, 0.0)` and therefore **unreachable**: dead *code* instead of dead
*config*, which is the worse of the two. The coherent alternatives are (a) keep the keys and run
one more arm, or (b) revert the mechanism entirely.

(b) is premature on the evidence: the mechanism demonstrably helps one chunked corpus on both
recall and leak, is provably inert on the short-corpus control, and was measured at exactly one
point of a one-dimensional axis — λ=0.3, a value the lane brief supplied as a starting guess, not
a tuned one. The λ arm that looked best (0.1: +0.0102 nDCG, +0.0150 R@10, −0.0150 leak) was
**discarded** for a degraded-CE defect, so the axis is genuinely unswept rather than explored
and rejected.

**Decision requested from the owner** (916 open item 8): authorise one clean λ ∈ {0.05, 0.1, 0.15}
sweep across *both* chunked corpora, or say no — in which case this PR's mechanism and both keys
should be reverted together, as one sweep, not left parked indefinitely. "Parked forever" is not
one of the options this changeset offers.

## The sweep was authorised, run, and it parks again — keys stay (2026-09-03)

The owner authorised the sweep; the rule was committed before it ran (`96a088f5`) and the arms are
in 916 §I.4. 18 arms, 17 admissible, 40 machine signatures with zero game processes, replicate
spread 0.0000 throughout.

**No λ passes.** On `legal-clerc-200` every λ ∈ {0.05, 0.10, 0.15} is identical to OFF to four
decimals — R@10, leak and nDCG@10 all unmoved. On `enron-qa` every λ costs the same −0.0033 R@10 and
+0.0033 leak, flat across the axis. The multiplier probe is inert too (m3 ≡ m5 at λ=0.10 on both
corpora), so the earlier arms were not multiplier-starved. With §G's λ=0.3 the axis reads: the lever
never helps enron at any λ, and helps legal only at λ=0.3 by less than the noise floor.

**So the two keys stay, and this is now a decided state rather than a pending one.** The owner
accepted the reachable-code-not-dead-code argument explicitly: deleting the keys would pin the
aggregate-then-cut path to `(1, 0.0)` and make it unreachable. Defaults are unchanged, nothing
ships, and no baseline row moves — the ratchet advance in this changeset accounts for two keys that
exist as a measured-and-parked instrument, not as an aspiration.

**What would reopen it:** not another λ point — that axis is swept. Only a *different* aggregation
shape (the §D.4 / F-056 pool-floor bound is the reason low-λ arithmetic cannot reach the parents
this was meant to rescue) or a corpus whose evidence really is spread across many mid-ranked
passages, which neither chunked corpus in the register turns out to be. If neither materialises, the
honest end state is reverting the mechanism and both keys together, and this changeset stands as the
record of why.

## Baseline advance (same commit, tempdoc 883 rule)

`gates/config-surface/baseline.txt` moves in this commit, alongside the keys it accounts for:

| metric | was | now | delta |
| :--- | ---: | ---: | :--- |
| `yaml_keys` | 111 | **113** | +2 = exactly the two keys above (both resolve from YAML as well as env) |
| `env_sysprop_pairs` | 250 | **252** | +2 = the same two |
| `config_keys` | 56 | 56 | unchanged — no new `ConfigKey` entry (these are EnvRegistry-backed, not YAML-only) |

Measured with `node scripts/docs/generate-runtime-config-matrix.mjs` on this branch
(`yaml_keys=113 env_sysprop_pairs=252 config_keys=56 rows=308`). The pre-merge pin of 111/250 is
what `main` measured on 2026-09-02; this branch adds these two and no others, so the advance is
fully attributable and the ratchet keeps its meaning — it still only ratchets DOWN from here.
