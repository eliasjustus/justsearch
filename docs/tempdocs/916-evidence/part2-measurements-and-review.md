# Tempdoc 916 evidence — Part 2 measurements (§G), λ sweep follow-up arm (§I), independent review (§J)

Split from `docs/tempdocs/916-lane-e-search-quality-rederivation.md` (size-cap split, 930 §19.3 F4).

## §G Measurements

### G.1 Protocol as executed

Per corpus, one index, three sequential jseval runs: a `--clean --pipeline` **build** arm (its own
query pass discarded — a pass taken straight after ingest is not comparable to one after a cold
restart), then **OFF** and **ON** as `--skip-ingest --start-backend` restarts. `--modes
lexical,vector,splade,hybrid` throughout, so `staged_recall_accounting` and the union/leak
projections populate. Driver `tmp/916-ab-driver.py` (detached `Start-Process`, `.done` marker,
per-arm machine signatures), extractor `tmp/916-extract.py`.

**Machine signature — the honest version.** A game client (League of Legends) was launched by the
machine's owner during the enron **build** arm and closed before it finished. Signatures per arm
(`tmp/916-ab/machine-signatures.jsonl`):

| arm | GPU | games |
| :-- | :-- | :-- |
| enron pre | 2730 MiB, 46 % | LeagueClient + Riot |
| enron post-build / post-off / post-on | 936 / 952 / 959 MiB, 4 / 3 / 6 % | **none** |
| legal pre → post-on | 949-959 MiB, 5-8 % | **none** |
| scifact pre → post-on | 871-949 MiB, 3-5 % | **none** |

So **all six measured arms ran on a quiet machine**; the contamination is confined to one index
build, whose only output is the index both arms then share. Its cost is visible and is reported,
not hidden: enron `primary_docs_s` **56.8** against 885's 112.6 baseline and `ingest_docs_s` **3.6**
against 18.2.

> **No throughput or latency number from this campaign is cited as evidence anywhere — not in this
> tempdoc, not in F-056, not in the PR.** The index build was contended, so every such number is
> void by construction; only the quality metrics, which are computed from a shared index by arms
> that each ran on a quiet machine, carry any weight here.

### G.2 The first legal pair was void, and the guard is what said so

| corpus | arm | `ce_coverage` | detail |
| :-- | :-- | :-- | :-- |
| legal-clerc-200 | OFF | **degraded-ce** | CE applied to 186/200, **13 silent `DEADLINE_EXCEEDED` drops**, silent-drop rate 0.0650 > 0.02 tolerance |
| legal-clerc-200 | ON | ok | CE applied to 198/200, 1 silent drop, rate 0.0050 |

The harness states the consequence itself: *"these queries were delivered in pure fusion order with
no deterministic reason, so this run's ranking is a blend of two pipelines and its metrics are not
comparable"*. The first legal delta (−0.0132 nDCG@10) was therefore **not a measurement of this
change** — it compared a degraded OFF against a clean ON. It is reported here because discarding it
silently would be the more dangerous habit. Legal was rebuilt and re-run with 3 replicates per arm
(`tmp/916-legal/`, driver `tmp/916-legal-replicates.py`).

### G.3 Replicate spread — the pipeline is deterministic

| arm | nDCG@10 (n=3) | R@10 | P@1 | leak | guards |
| :-- | :-- | :-- | :-- | :-- | :-- |
| legal OFF | 0.5816, 0.5816, 0.5816 — **sd 0.00000** | 0.8100 ×3 | 0.3600 ×3 | 0.1300 ×3 | 3/3 `ok`, comparable |
| legal ON (λ=0.3) | 0.5826, *0.5888*, 0.5826 | 0.815, *0.810*, 0.815 | 0.3600, *0.3700*, 0.3600 | 0.1250, *0.1300*, 0.1250 | 2/3 `ok`; *replicate 1 `degraded-ce`* |

**σ(R@10) on this machine, at fixed configuration, is 0.** Three identical OFF runs produced
bit-identical R@10, leak and P@1. **This does NOT extend to nDCG@10** (§J review, BL-4): enron
`λ0.10-m5` replicates differ at the fifth decimal — `20260903T023442_mixed_enron-qa` 0.79685 (5
silent CE drops) against `20260903T023643_mixed_enron-qa` 0.79764 (0 drops), and **both are
`ce_coverage: ok`** because 5/300 sits inside the 2% tolerance. So the residual variance source is
cross-encoder deadline drops that `ce_coverage` *tolerates*, not ones it flags — the guard bounds the
contamination, it does not remove it. Every "spread 0.0000" claim in this tempdoc is an **R@10**
claim and is now written as one. This **supersedes the σ ≈ 0.0034 borrowed from F-055 in
§D.7** — that figure was measured on a contended machine and is an upper bound, not this cohort's
noise. Consequence for the rule: a "> 2σ" test degenerates when σ = 0, so the deltas below are
**real, not noise** — they are simply very small, and far inside the relevance ratchet's 0.02 band.
Reading σ=0 as "every nonzero delta is significant, therefore ship" would be exactly the
opportunistic re-reading the pre-registration exists to prevent.

### G.4 The A/B table (mode `hybrid`; OFF = shipped defaults 1 / 0.0, ON = 5 / 0.3)

| corpus | arm | run id | nDCG@10 | P@1 | R@10 | union | leak | CE p50 | comparable | ce_cov | chunk_compl |
| :-- | :-- | :-- | --: | --: | --: | --: | --: | --: | :-- | :-- | :-- |
| enron-qa | OFF | `20260903T003601_mixed_enron-qa` | 0.8034 | 0.6733 | 0.9200 | 0.9633 | 0.0500 | 151 ms | True | ok | ok |
| enron-qa | ON | `20260903T003814_mixed_enron-qa` | 0.7981 | 0.6700 | 0.9133 | 0.9633 | 0.0533 | 153 ms | True | ok | ok |
| legal-clerc-200 | OFF | `20260903T010337` / `010706` / `011043` | 0.5816 | 0.3600 | 0.8100 | 0.9300 | 0.1300 | — | True | ok x3 | ok |
| legal-clerc-200 | ON | `20260903T010518` / `011225` (clean) | 0.5826 | 0.3600 | 0.8150 | 0.9300 | 0.1250 | — | True | ok | ok |
| scifact (control) | OFF | `20260903T005443_scifact` | 0.7591 | 0.6300 | 0.8942 | 0.9333 | 0.0267 | 148 ms | True | ok | chunk-free |
| scifact (control) | ON | `20260903T005634_scifact` | 0.7591 | 0.6300 | 0.8942 | 0.9333 | 0.0267 | 147 ms | True | ok | chunk-free |

Deltas (ON − OFF):

| corpus | Δ nDCG@10 | Δ P@1 | Δ R@10 | Δ union | Δ leak |
| :-- | --: | --: | --: | --: | --: |
| **enron-qa** | −0.0053 | −0.0033 | **−0.0067** | 0.0000 | +0.0033 |
| **legal-clerc-200** | +0.0010 | 0.0000 | **+0.0050** | 0.0000 | −0.0050 |
| scifact (control) | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |

Three facts worth more than the deltas themselves:

1. **`leg_union_recall` is unchanged on every corpus** (0.9633 / **0.9300** / 0.9333). The retrieval legs
   are byte-identical across arms; only the collapse moved. That is the causal isolation the
   experiment needed, and it is what makes these deltas attributable.
2. **scifact is bit-identical across arms** — every metric to 4 dp. scifact is `chunk-free` (short
   corpus, chunk merge skipped), so the lever is *provably inert exactly where it should be*. This is
   both the negative control passing and a second, independent refutation of a wrong-gate mistake.
2b. **The legal +0.0050 R@10 is INDEX-BUILD-SPECIFIC and is withdrawn as a result** (§J review,
   BL-2). The independent reviewer rebuilt legal and measured ON at 0.8100 / 0.1300 — identical to
   OFF — and my own §I sweep, on a *third* legal build, measured OFF 0.8150 with every λ also 0.8150.
   Across three independent builds the +0.0050 appeared once. It is dropped from every headline;
   only the enron sign and the scifact inertness survive as cross-build claims.
3. **The numbers moved at all on the chunked corpora**, and `summary.json.env_overrides` records
   `{OVERFETCH_MULTIPLIER: "5", AGGREGATION_LAMBDA: "0.3"}` on every ON arm. Together with §E.2 this
   closes the wrong-gate question empirically: the lever reaches the Worker.

### G.5 λ response on legal (supporting evidence; does NOT change the verdict)

| λ | nDCG@10 | R@10 | leak | `ce_coverage` | usable |
| --: | --: | --: | --: | :-- | :-- |
| 0.0 (OFF) | 0.5816 | 0.8100 | 0.1300 | ok | yes |
| 0.1 | 0.5918 | 0.8250 | 0.1150 | **degraded-ce** | **no** |
| 0.2 | 0.5827 | 0.8150 | 0.1250 | ok | yes |
| 0.3 | 0.5826 | 0.8150 | 0.1250 | ok | yes |

λ=0.1 is the most attractive row in this whole tempdoc and it is **discarded**: its arm carries the
same degraded-CE defect that voided the first legal pair. Accepting it because it says what the
change would like it to say is precisely the failure the guard exists to prevent, and it is recorded
here so the next agent re-runs it rather than citing it. On the two usable rows the response is flat
between 0.2 and 0.3.

### G.6 Verdict against the pre-registered rule (§D.7)

| criterion | result |
| :-- | :-- |
| 1. R@10 improves > 2σ on **both** chunked corpora | **FAIL** — legal +0.0050, enron **−0.0067** |
| 2. scifact within ±2σ | **PASS** — exactly 0.0000 on every metric |
| 3. ratchets + guards green on ON | **PASS** for the applicable ones (§G.7) |

**PARK.** The rule's split-result clause is explicit and was written before any number existed:
*"A split result — one chunked corpus up, the other down — is a park, not a 'mixed but promising':
F-055 parked on exactly that shape and the reason has not changed."* That is this result exactly.
Shipped defaults stay `(1, 0.0)`, which is the pre-916 behaviour bit-for-bit.

The mechanism is not refuted — legal gains recall *and* drops leak, scifact is provably inert, and
the effect is causally isolated. What is refuted is **λ=0.3 as a shipped default**, on one point of a
one-dimensional axis whose starting value came from the brief rather than from evidence.

> **Superseded by §J, twice over.** (1) The legal +0.0050 is **index-build-specific** and is
> withdrawn (BL-2): two later builds show it at 0.0000. (2) The aggregate never reached the branch
> blend, so this arm measured set membership, not aggregation — "the mechanism is not refuted" was
> true of a mechanism that was not running. §J re-measures correctly and the verdict is REVERT.

### G.7 Ratchets

Run against the ON arms (`python -m jseval <gate> --dataset <ds> --run-dir <arm>`):

| gate | enron ON | scifact ON |
| :-- | :-- | :-- |
| `relevance-gate` (`ndcg10-no-regression`) | **ok** | **ok** |
| `leak-gate` (`leak-rate-no-regression`) | **ok** | **ok** |
| `union-recall-gate` (`union-recall-no-regression`) | **ok** | **ok** |
| `perf-gate` | **not applicable** — see below | not applicable |

`perf-gate` is **not evaluable on this A/B's arms, and not because of this change**: the OFF/ON arms
are `--skip-ingest` query-only runs, so `primary_docs_s` and `enrich_docs_s` are literally absent
(`None` in both arms' `summary.json`, verified) and their checks cannot pass. The two checks that
*are* meaningful for a query-path change both pass on the ON arm: **`ce_p50_ms: ok`,
`retrieval_p50_ms: ok`**. The OFF arm additionally exits 2 on an engine-set mismatch against the perf
baseline (`realized ['reranker']` vs baseline `['dense','reranker','splade']`) — a property of
query-only arms, present in the control as well, i.e. not attributable here. Claiming "perf-gate
green" would have been false; claiming a regression would also have been false.

**Legal arm, run afterwards on §J review request, with the exact flags recorded** (the §J review
found the original ratchet claim did not say which arm or which flags produced it). Arm
`20260903T034732_mixed_legal-clerc-200` (λ0.3 r0), **no `--allow-*` override on any gate**:

```
python -m jseval <gate> --dataset mixed/legal-clerc-200 \
    --run-dir tmp/916-J/mixed_legal-clerc-200/l0.3-r0/20260903T034732_mixed_legal-clerc-200
```

| gate | check | current | floor | verdict |
| :-- | :-- | --: | --: | :-- |
| `relevance-gate` | `ndcg10-no-regression` | 0.5957 | 0.5580 | **ok** |
| `leak-gate` | `leak-rate-no-regression` | 0.1150 | 0.1850 | **ok** |
| `union-recall-gate` | `union-recall-no-regression` | 0.9350 | 0.8850 | **ok** |
| `perf-gate` | `ce_p50_ms`, `retrieval_p50_ms` | — | — | **ok** |
| `perf-gate` | 5 ingest/memory checks | — | — | **fail — identical in the OFF control** |

**And the control check that makes the "not attributable" claim real rather than asserted:** running
the same `perf-gate` on the OFF arm `20260903T034234_mixed_legal-clerc-200` fails **the same five
checks, check for check** — `primary_docs_s`, `enrich_docs_s`, `resident_bytes`, `embed_bytes`,
`splade_bytes` — with `ce_p50_ms`, `retrieval_p50_ms`, `reranker_bytes`, `ner_bytes` ok in both.
Three of those five (`resident_bytes`, `embed_bytes`, `splade_bytes`) were **not named** in the earlier
draft of this section or in F-056, which understated how much of `perf-gate` is silent on a
`--skip-ingest` arm. Corrected in both places.

### G.8 Cadence (RECORDED, NOT ACTED ON)

`commit_total` / `reopen_total`: enron 261 / 1132 (identical in both arms), legal 42 / 202
(identical), scifact 56 / 215 (identical). Identical across arms, as expected for a query-only
difference. Per 912 §E-live the floor is `BackfillScheduler.CYCLE_BUDGET_MS = 5_000`; the fix is 912
open items 8/9 and is **not** this lane's.

---


## §I λ sweep (owner-authorised follow-up arm) — PRE-REGISTERED BEFORE RUNNING

The §G verdict parked λ=0.3 on a split. λ=0.3 was the lane brief's starting guess, and the one
attractive point on the axis (λ=0.1, +0.0102 nDCG / +0.0150 R@10 / −0.0150 leak on legal) was
**void** for `degraded-ce`, so the axis is unswept rather than explored and rejected. The owner
authorised exactly one clean sweep to settle whether the two keys stay or the mechanism reverts.

### I.1 Design

- **λ ∈ {0.05, 0.10, 0.15}** at `overfetch_multiplier = 5`, on `mixed/enron-qa` and
  `mixed/legal-clerc-200`, same-index per corpus, backend restarted per arm.
- **2 replicates per ON arm.** OFF is measured once per corpus: §G.3 established σ(OFF) = 0.0000
  across 3 identical replicates, so replicating the control again buys nothing. The ON arms are
  where variance can appear (they are the ones that can trip the CE deadline tail), so that is where
  the replicates go.
- **Multiplier probe:** `multiplier ∈ {3, 5}` at λ=0.10 (the axis midpoint), 2 replicates, run inside
  the same per-corpus index window because the index is wiped between corpora. Chosen at the
  midpoint rather than "at the best λ" because the best λ is not known until the index is gone.
- **`beir/scifact` control at the winning λ only** — it is `chunk-free`, so the expectation is
  bit-identical output; it is a wrong-gate detector, not a tuning target.

### I.2 Admissibility (hard, per arm — checked before any number is read)

An arm is **void** unless `ce_coverage.verdict == "ok"` AND `per_mode.hybrid.comparable == true`.
A void arm is **re-run, never cited** — this is the rule that already saved this tempdoc twice
(§G.2 reversed the legal sign; §G.5's λ=0.1 was the campaign's most attractive number and was
discarded). Machine signature recorded before and after every arm; an arm whose signature shows a
game process or elevated GPU is re-run.

### I.3 Decision rule — WRITTEN AND COMMITTED BEFORE THE SWEEP RAN

> Let `spread(λ)` = max−min across an arm's admissible replicates on R@10, and let the noise
> reference be `max(spread, 0.0068)` — the 2σ figure §D.7 borrowed from F-055 — so a measured
> spread can only ever make the test *stricter*, never looser. This is the same guard against
> "σ came out 0, therefore everything is significant" that §G.3 flagged.
>
> **SHIP a λ** (flip both defaults to `multiplier 5, λ=<winner>`) only if ALL hold:
> 1. **R@10 improves on BOTH chunked corpora** by more than the noise reference, at the same λ;
> 2. `beir/scifact` at that λ is **bit-identical** to OFF (it is chunk-free — anything else is a
>    wrong-gate signal, not a quality result);
> 3. `leak_rate` does not worsen on either chunked corpus;
> 4. every contributing arm admissible per §I.2, and `relevance-gate` / `leak-gate` /
>    `union-recall-gate` green on the shipped ON arm.
>
> **PARK and KEEP the keys** if no λ satisfies (1)-(4). The owner has already accepted the
> reachable-code-not-dead-code reasoning, so a second park does **not** trigger key removal; it
> triggers writing the sweep table into F-056 so nobody re-runs this blind.
>
> **A split at every λ is a park**, not "pick the least-bad λ" — the same clause that decided §G.
> **Monotonicity is not a tiebreaker:** a λ that wins on one corpus and loses on the other is a
> split even if the trend across λ looks encouraging. Predictable evasion to name now, before the
> numbers exist: "λ=0.05 is directionally right on both and would probably win at 0.03" — an
> unmeasured extrapolation is not a measurement, and the axis does not get a second extension.

### I.4 Sweep results

Ran 2026-09-03 04:09-04:44 (`tmp/916-lambda-sweep.py`, analysed by `tmp/916-sweep-analyze.py`, which
applies §I.3 mechanically). **40 machine signatures, 0 with a game process, GPU 754-755 MiB flat** —
the whole sweep ran on a quiet machine, unlike §G's index builds.

18 arms, **17 admissible**, 1 void.

| corpus | arm | run id | `ce_cov` | adm | nDCG@10 | R@10 | leak |
| :-- | :-- | :-- | :-- | :-- | --: | --: | --: |
| legal | OFF | `20260903T015459_…legal-clerc-200` | ok | YES | 0.5795 | 0.8150 | 0.1200 |
| legal | λ0.05 m5 r0 | `20260903T015646_…` | ok | YES | 0.5778 | 0.8150 | 0.1200 |
| legal | λ0.05 m5 r1 | `20260903T015846_…` | **degraded-ce** | **VOID** | *0.6176* | *0.8350* | *0.1000* |
| legal | λ0.10 m5 r0/r1 | `20260903T020109_…` / `020250_…` | ok | YES | 0.5795 | 0.8150 | 0.1200 |
| legal | λ0.15 m5 r0/r1 | `20260903T020434_…` / `020614_…` | ok | YES | 0.5796 | 0.8150 | 0.1200 |
| legal | λ0.10 **m3** r0/r1 | `20260903T020753_…` / `020935_…` | ok | YES | 0.5795 | 0.8150 | 0.1200 |
| enron | OFF | `20260903T022732_mixed_enron-qa` | ok | YES | 0.8010 | 0.9200 | 0.0433 |
| enron | λ0.05 m5 r0/r1 | `20260903T022932_…` / `023129_…` | ok | YES | 0.7976 | 0.9167 | 0.0467 |
| enron | λ0.10 m5 r0/r1 | `20260903T023442_…` / `023643_…` | ok | YES | 0.7969/0.7976 | 0.9167 | 0.0467 |
| enron | λ0.15 m5 r0/r1 | `20260903T023843_…` / `024039_…` | ok | YES | 0.7977 | 0.9167 | 0.0467 |
| enron | λ0.10 **m3** r0/r1 | `20260903T024234_…` / `024439_…` | ok | YES | 0.7976 | 0.9167 | 0.0467 |

Admissible means vs OFF (replicate spread **0.0000 on every arm**, so the noise reference is the
0.0068 floor in every row — the measured spread never tightened it):

| arm | legal Δ R@10 | enron Δ R@10 | legal Δ leak | enron Δ leak | beats noise? |
| :-- | --: | --: | --: | --: | :-- |
| λ0.05 m5 | **+0.0000** | **−0.0033** | +0.0000 | +0.0033 | no |
| λ0.10 m5 | **+0.0000** | **−0.0033** | +0.0000 | +0.0033 | no |
| λ0.15 m5 | **+0.0000** | **−0.0033** | +0.0000 | +0.0033 | no |
| λ0.10 m3 | **+0.0000** | **−0.0033** | +0.0000 | +0.0033 | no |

### I.5 Verdict — PARK, and the axis is now swept rather than unmeasured

**No λ satisfies the rule.** Condition 1 (R@10 beats the noise reference on both chunked corpora)
fails on every arm, and condition 3 (leak not worse) fails on enron on every arm.

The shape is not a split this time — it is **inertness plus a small consistent cost**:

- **On legal, low λ moves nothing that matters — but "identical at every λ" was wrong.** R@10 and
  leak are identical to OFF at every λ ∈ {0.05, 0.10, 0.15}; nDCG@10 is identical at λ 0.10/0.15
  (0.5795 / 0.5796 against OFF 0.5795) but **λ0.05 is 0.5778, i.e. −0.0017** (§J review, BL-3;
  corrected here, in F-056 and in the skill mirror). It does not move the verdict: −0.0017 is a
  regression, far inside the noise floor, and in the wrong direction for shipping. The +0.0050 R@10 that λ=0.3 produced in §G was the whole
  effect, and it sat below the 0.0068 noise floor anyway.
- **On enron, every λ costs the same −0.0033 R@10 / +0.0033 leak** — flat across the axis, i.e. the
  cost is not λ-proportional in this range; a single reordering flips at any λ > 0 and stays flipped.
- **The multiplier axis is inert too.** m3 and m5 at λ=0.10 are identical on both corpora, so the
  earlier arms were not multiplier-starved.

Together with §G (λ=0.3: legal +0.0050, enron −0.0067) the axis now reads: **the lever never helps
enron at any λ, and helps legal only at λ=0.3 by less than the noise floor.** That is a refutation of
λ as a shipped default, not an absence of evidence — which is exactly what this arm was authorised to
establish. **Per §I.3 the keys STAY** (owner accepted reachable-code-not-dead-code); defaults remain
`(1, 0.0)`, no baseline row moves, and nothing ships.

> **Superseded by §J.** This verdict was reached against a lever whose aggregate never reached the
> branch blend, so what §I actually swept was set membership at the collapse cut, not aggregation.
> The conclusion survives — no λ helps — but the *reason* stated above is not the one the numbers
> support. §J re-measures with the aggregate emitted as the score and reaches REVERT; the keys do
> **not** stay. Read §I as the set-membership half of the answer.

**Scifact control not run, and this is not a skipped condition.** §I.3 condition 2 is gated on there
being a winning λ; there is none. It would also be uninformative: §G measured scifact **bit-identical
at λ=0.3**, and the aggregate at λ ≤ 0.15 is strictly closer to the max-only baseline than at 0.3, so
a weaker λ cannot produce a difference the stronger one did not. Recorded rather than quietly
omitted.

### I.6 Void arms: five in total, and the bias is drop-rate-dependent

Corrected after the §J review, which found "three of three" undercounted. **Five** arms across all
campaigns carry `ce_coverage: degraded-ce`:

| run id | campaign | drops | rate | nDCG@10 | clean comparator |
| :-- | :-- | --: | --: | --: | :-- |
| `20260903T004513_mixed_legal-clerc-200` | §G legal OFF | 13 | 6.5% | 0.6008 | 0.5816 -> **higher** |
| `20260903T010858_mixed_legal-clerc-200` | §G legal ON r1 | 33 | 16.5% | 0.5888 | 0.5826 -> **higher** |
| `20260903T011647_mixed_legal-clerc-200` | §G.5 lambda=0.1 | 9 | 4.5% | 0.5918 | 0.5816 -> **higher** |
| `20260903T015846_mixed_legal-clerc-200` | §I lambda0.05 r1 | 41 | 20.5% | 0.6176 | 0.5778 -> **higher** |
| `20260903T005237_scifact` | §G scifact **build** arm | 1 | 1 of 5 q | 0.5021 | none — 5-query build arm, not comparable |

**The claim, restricted to what the data supports.** On `legal-clerc-200`, at drop rates *above* the
2% `ce_coverage` tolerance — 4.5% to 20.5% observed — a degraded arm scores **higher** than its clean
comparator, four for four. The mechanism is F-055's adjacent datapoint: on legal, delivering fusion
order instead of the cross-encoder's is worth **+0.131 nDCG@10**. **Carry F-055's own caveat whenever
that number is quoted:** it was measured on a contended machine and its CE-off arms were
OOM-produced, so it is directional evidence for the mechanism, not a calibrated magnitude.

**The bias does not extend below the tolerance.** The one sub-tolerance case points the other way:
enron `20260903T023442_mixed_enron-qa` (5 drops, 1.7%, verdict `ok`) scored 0.79685 against its
0-drop sibling `20260903T023643_mixed_enron-qa` at 0.79764 — **lower**. So the operational rule is
not "CE drops inflate results" but: **above the 2% tolerance on legal a degraded arm is inflated and
must be re-run; below it, drops are ordinary sub-0.001 nDCG jitter.** The first half is what makes
`ce_coverage` load-bearing — it changed this tempdoc's headline once (§G.2).


## §J Independent review (NEEDS-FIXES) — the lever did not do what its javadoc said

An independent review of #621 at `a6558f09` confirmed the λ=0 bit-identity under a **stronger oracle
than mine** (full `SearchResult` equality, 60k cases) and confirmed the pre-registrations are
genuine. It then found the mechanism itself was not wired.

### J.1 BL-1, verified at source before acting on it

`CollapsedParent.toHit()` returned `hit`, whose score is `winner.score()` — the parent's **max**
(`SearchExecutor.java:1173`, `mergeCollapsedChunkParentHit`). The aggregate was a **sort key that
never left the method**. Downstream, branch fusion is `cc` by default
(`ResolvedConfigBuilder.java:535`) and `fuseWithCCNamed` blends **min-max-normalized scores keyed by
docId** (`HybridFusionUtils.java:506`, `:511`, `:521-524`); the per-branch ranks it also collects are
debug-only.

So λ's only reachable effect was **set membership at the cut** — and at the shipped
`scan_cap_multiplier = 1`, `scanCap == limit == collapseLimit`, so λ was **inert end-to-end**.

**What that means for §G and §I: they measured "does a wider collapse scan change set membership"
(answer: essentially no), never "does score aggregation help".** Both prior verdicts stand as
correct answers to the wrong question. My §D.4 claim that "only within-branch order and relative
spacing reach the blend" was **wrong**: the emitted score was the max, so the aggregate's ordering
reached nothing. This is a `wrong-gate` instance in my own work — I grepped that the accessors were
read and that the numbers moved, but never checked that the quantity I computed was the quantity the
next stage consumes.

### J.2 The fix

The aggregate is now the parent's **emitted chunk-branch score**:

```
emitted = (max + λ·Σ_{i≥1} 0.5^(i-1)·s_i) / (1 + 2λ)
```

**Normalization, stated:** the decay series sums to 2, so the aggregate is bounded above by
`max·(1+2λ)`. Dividing by exactly that bound keeps the chunk branch inside `[0,1]` whenever its input
was, is a **single uniform divisor** so it preserves the aggregate ordering exactly, and is the
**identity at λ=0** (divisor 1, aggregate = max), which is what keeps the control arm bit-identical.
A data-dependent min-max over the collapsed set was rejected: it would rescale the λ=0 case and
destroy bit-identity.

Also fixed in the same change: the maximum is now **tracked** rather than assumed to arrive first
(production input is descending, but that is an assumption of the caller, not a guarantee of this
method); `limit <= 0` reproduces the pre-916 edge behaviour (one hit) instead of throwing; λ is
clamped to `[0,1]` at the method as it already was in `ResolvedConfigBuilder`.

**Key renamed** `chunk_collapse_overfetch_multiplier` → **`chunk_collapse_scan_cap_multiplier`**
(my call, per the review's nit): it caps how many distinct parents the collapse *scans*, and never
fetched anything — the chunk legs already over-fetch at ×10 independently. Renamed across
`EnvRegistry`, `ResolvedConfig`, `ResolvedConfigBuilder`, tests, `environment-variables.md`, the
regenerated ownership matrix, the changeset and the register in one commit; `config-surface` counts
are unchanged (113 / 252 / 56) because a rename is not growth.

### J.3 Pre-registered rule for the decisive A/B — COMMITTED BEFORE THE RUN

Driver: **`scripts/jseval/916_collapse_ab.py`** — committed, not gitignored, because the review's
point that the admissibility filter deciding which arms count was itself untracked is correct.

> Arms: `mixed/legal-clerc-200` and `mixed/enron-qa`, one index each, OFF plus λ ∈ {0.1, 0.3} at
> `scan_cap_multiplier = 5`, **2 replicates per ON arm**, backend restart per arm, machine signature
> before and after each. Admissibility unchanged (§I.2): `ce_coverage == "ok"` AND
> `comparable == true`, enforced in the committed driver; a void arm is re-run, never cited.
> Noise reference `max(replicate spread, 0.0068)` — a measured spread can only tighten it.
>
> **SHIP** the winning λ (flip both defaults) only if: (1) R@10 beats the noise reference on **both**
> chunked corpora at the same λ; (2) `leak_rate` does not worsen on either; (3) `beir/scifact` at
> that λ is bit-identical to OFF; (4) all four ratchets green on the shipped ON arm.
>
> **REVERT** — mechanism *and* both keys, `config-surface` pin back to 111/250 in the same commit —
> if no λ satisfies (1)-(3). This is the owner's "no third round" instruction and it is binding: a
> parked mechanism does not merge. A split is a revert, not a "pick the better corpus".
>
> Predictable evasion, named before the numbers exist: "the mechanism is now correct so the earlier
> parks do not count against it". They do not count *for* it either — this arm is the whole
> evidence, and it is the last one.

### J.4 Results — the mechanism is REFUTED and REVERTED

Ran 2026-09-03 03:42-04:19 UTC via the committed driver `scripts/jseval/916_collapse_ab.py`. **The
rule was committed before the first arm and the timestamps prove it**: `52d35001` is dated
03:34:57 UTC and the first arm's run id is `20260903T034234`, 7m37s later. **10 arms, 10 admissible** (`ce_coverage: ok` and `comparable: true`
on every one — no void arm), **24 machine signatures, 0 game processes, GPU 754-758 MiB**,
replicate spread **0.0000 on R@10** throughout.

| corpus | arm | run id | ce_cov | comparable | nDCG@10 | R@10 | leak |
| :-- | :-- | :-- | :-- | :-- | --: | --: | --: |
| legal | OFF | `20260903T034234_mixed_legal-clerc-200` | ok | True | 0.6040 | 0.8350 | 0.1000 |
| legal | lambda 0.1 r0/r1 | `20260903T034414` / `20260903T034553` | ok | True | 0.6033 | 0.8400 | 0.1000 |
| legal | lambda 0.3 r0/r1 | `20260903T034732` / `20260903T034910` | ok | True | 0.5957 | 0.8300 | 0.1150 |
| enron | OFF | `20260903T040949_mixed_enron-qa` | ok | True | 0.7990 | 0.9167 | 0.0467 |
| enron | lambda 0.1 r0/r1 | `20260903T041147` / `20260903T041345` | ok | True | 0.7934 | 0.9100 | 0.0567 |
| enron | lambda 0.3 r0/r1 | `20260903T041550` / `20260903T041751` | ok | True | 0.7978 | 0.9167 | 0.0500 |

| lambda | legal d R@10 | enron d R@10 | legal d leak | enron d leak | noise | passes? |
| :-- | --: | --: | --: | --: | --: | :-- |
| 0.1 | +0.0050 | **-0.0067** | +0.0000 | **+0.0100** | 0.0068 | no |
| 0.3 | **-0.0050** | +0.0000 | **+0.0150** | **+0.0033** | 0.0068 | no |

**The lever is now genuinely live** — that is the one thing this arm establishes that the earlier two
could not. At lambda 0.3 legal moves -0.0050 R@10 and +0.0150 leak where the dead-sort-key version
moved it 0.0000. So this is a measurement of aggregation, not of scan breadth.

**And aggregation loses.** No lambda satisfies the rule: at 0.1 enron drops -0.0067 R@10 with leak
+0.0100; at 0.3 legal drops -0.0050 with leak +0.0150. **`leak_rate` worsens on at least one corpus
at every lambda tested** — condition 2 fails outright, independently of the R@10 floor. The single
positive cell (legal +0.0050 at lambda 0.1) sits below the 0.0068 noise reference, and BL-2 already
established that a legal +0.0050 does not survive an index rebuild.

**Verdict: REVERT, per the committed rule.** The mechanism and both keys are removed in the same
commit as the `config-surface` pin returning to 111 / 250. Production code in this branch is now
**byte-identical to `main`** (`git diff origin/main -- 'modules/*/src/main'` is empty). "No third
round" was the standing instruction and this is it.

### J.5 What is kept, and why it is not nothing

- **`SearchExecutorChunkCollapseCharacterizationTest`** (8 tests) — the collapse had no direct unit
  coverage before this lane. It now has: audit finding 2 pinned as an executable statement of the
  limitation, per-parent best-score carry, determinism, fused-order tie-breaking, parentless hits,
  sibling evidence merging by max, and the non-positive-limit edge. The refuted design is gone; the
  characterization of what actually ships stays.
- **The measurement itself.** Audit finding 2 is now answered rather than open: aggregating spread
  chunk evidence was tested at the set-membership level (sections G and I) and, after the section J
  fix, at the score-aggregation level, across three index builds and two corpora, and it does not
  help. That is the durable output of Part 2.
- **The committed driver** `scripts/jseval/916_collapse_ab.py`, so the next A/B on this seam does not
  re-implement admissibility filtering in a gitignored script. **It hardcodes no reverted key name**
  (`retire-with-a-sweep`): the swept env var is now `--sweep-key` / `--sweep-values`, with
  `--fixed-env` for the rest of the arm. Re-running the table above through the generalized driver
  reproduces every cell and the PARK verdict, which is the regression check on the generalization.

### J.6 What I got wrong, recorded plainly

1. **`wrong-gate` in my own work.** I verified the config keys resolved, that the accessors were read
   at both call sites, and that the numbers moved — and never checked that the quantity I computed
   was the quantity the next stage consumes. The emitted score was the max; branch fusion blends
   scores. Two full campaigns measured the wrong thing and reported confident verdicts.
2. **My oracle was too weak.** Comparing parent ids let a wrong emitted score pass. The reviewer
   compared whole `SearchResult`s. An equivalence oracle must cover every field the consumer reads,
   not the field the test author was thinking about.
3. **"Identical to four decimals"** (BL-3) and **"replicate spread 0.0000"** (BL-4) were overstated
   summaries of tables that contradicted them in the same document. Both are corrected in place.
4. **A single-index result was promoted to a headline** (BL-2). The legal +0.0050 did not survive a
   rebuild, and I had a third build of my own that already disagreed.


---

