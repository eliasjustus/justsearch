---
title: "German semantic bridging collapses at 10k: the engine's semantic leg bridges zero-lexical-overlap German descriptors at ~half CLERC strength at 1k and goes dark at 10⁴ (hybrid 0.043, union recall 0.10) on post-F-031/F-032 code — the 707 engine finding, chartered as its own attribution investigation (NOT a 708 re-litigation)"
type: tempdocs
status: "open — attribution pass executed 2026-07-29 (§A-§H): phases 0-2 done OFFLINE, Q-018 NOT closed. Interim verdict: (c) gold-design/task-shape is dominant — the identical EN construction collapses at 10k too (0.0996/0.1105) and its lexical leg is 0.0 by construction, not by German; (d) largely eliminated and (a) weakened by real German scoring 0.7283 nDCG / 0.9805 recall with recall holding at zero lexical overlap; (b) refuted on the EN member by F-040's already-recorded exact-NN collapse. A residual DE-vs-EN bridge gap survives and is confounded with payload version. A defillered DE rebuild exists at scripts/jseval/748-corpora/de-miracl/ but is STRUCTURALLY CERTIFIED ONLY and claim_eligible:false — no paid closed-book, no backend run. DE remains a non-claim-bearing secondary stratum. Remaining phase-3 work is scripted in §G, not run."
created: 2026-07-16
updated: 2026-07-29
author: agent (Fable orchestration), chartered at founder direction ("go ahead as you suggested") after the 707 chain-2 verdict routed the finding to an encoder-lane successor
category: search-quality / dense-retrieval / multilingual / attribution
related:
  - 707-pillar1-inband-utility-corpus       # the measurement that produced the finding (chain-2 8-cell matrix, DE v2 recalibration verdict)
  - 708-encoder-domain-fit-legal-professional-text  # CLOSED (NO MODEL SWAP, EN-legal): do not re-litigate its bake-off; this doc is the *German-at-scale* question 708 never tested
  - 678-nl-question-query-robustness        # the pillar-5 attribution playbook this charter reuses (eliminate-in-order discipline)
  - 636-retrieval-buried-signal-long-documents  # staged-recall instrument — the diagnostic machinery here too
  - 704-measurement-substrate-correct-data-program  # program frame; scoped-claim principle applies to any close of this doc
---

> NOTE: Noncanonical working tempdoc. STUB + investigation charter. Verify every inherited
> number against tempdoc 707's chain-2 table and the cited run artifacts before building on it.

# 748 — German semantic bridging at scale (the 707 DE engine finding)

## The question (falsifiable)

On `mixed/de-miracl-*` (real German Wikipedia distractor mass + fabricated DE v2 gold at
hops=1, pure zero-lexical-overlap synonym descriptors), measured 2026-07-16 on one engine
cohort ≡ origin/main post-#201 (i.e. WITH the F-031/F-032 fixes shipped):

| cell | hybrid nDCG@10 | union recall | lexical leg |
|---|---|---|---|
| de-miracl-1k-verbose (v2) | 0.2053 | 0.40 | 0.0 |
| de-miracl-1k-short-natural (v2) | 0.2660 | 0.40 | 0.0 |
| de-miracl-10k-verbose (v2) | **0.0431** | **0.10** | 0.0 |
| de-miracl-10k-short-natural (v2) | **0.0428** | **0.10** | 0.0 |

The lexical zero is the *pre-registered, confirmed* German grep-collapse prediction — not the
finding. The finding is the **semantic leg**: it bridges German synonym descriptors at roughly
half CLERC's strength at 1k and collapses by 10⁴ (union recall 0.40 → 0.10), while the same
engine on EN legal text stays in-band at 10k (0.3238) and on EN email reaches 0.66–0.80.

**This doc's question:** is the 10k collapse a property of (a) the incumbent encoders'
*German* representation quality, (b) a scale/candidate-depth interaction specific to this
corpus shape (ANN/fusion starving a weak-but-nonzero leg as distractor mass grows), (c) the
DE v2 *gold design* (pure zero-lexical-overlap descriptors are a strictly harder task than
CLERC/email gold — a corpus artifact, not an engine defect), or (d) German text mechanics
(compounding/tokenization) in chunking/indexing?

## What is already eliminated (do not re-test)

- **Chain length:** DE v2 regenerated at hops=1 (parity with CLERC) moved 1k by only
  +0.02–0.04 and 10k not at all (707 chain-2, commit 8a562519). Largely refuted.
- **Query shape:** verbose vs short-natural differ by ≤0.06 at 1k, ≈0.000 at 10k. Not the axis.
- **F-031/F-032 construction defects:** fixed and shipped before this cohort ran; the EN
  members' health on the same cohort is the positive control.
- **708's EN-legal bake-off verdict (NO MODEL SWAP)** stands for EN-legal; nothing here
  reopens it. 708 never measured German at 10⁴ — that gap is exactly this charter.

## First experiments (all ~$0, short local GPU runs, signature-bound)

1. **Gold-design control (decides (c) cheaply):** build an *EN* gold stratum with the identical
   zero-lexical-overlap synonym-descriptor construction on the existing EN distractor mass
   (raw-Enron or CLERC hosts) at 1k+10k. If EN-synonym gold also halves at 1k and collapses at
   10k, the finding is task-shape, not German — close with a scoped claim and re-design DE gold
   with partial anchors (707's option (b), now justified by a design pass).
2. **Staged-recall decomposition on the existing de-miracl-10k artifacts** (projections already
   on disk from the 4 runs): where does gold exit the funnel — embedding candidate set, ANN
   truncation, or fusion rank? Distinguishes (a) from (b) without a single new run.
3. **Candidate-depth sweep at 10k** (if 2 implicates depth): does raising the vector leg's
   candidate K recover union recall? A recovery ⇒ (b), a knob decision; no recovery ⇒ (a).
4. **Chunk-granularity probe on DE** (708 measured +3pts on EN-legal — expected low yield, but
   German compounding makes doc-vs-chunk a live variable; one run per size).

## Boundary

- **No corpus redesign here** — 707 owns corpus design; this doc may *recommend* a DE gold
  iteration but not execute one.
- **No model swap without 708-grade evidence** — any candidate-encoder claim needs the same
  signature-bound bake-off discipline (Gate 0, sign tests) 708 set as precedent.
- **No claim changes until closed** — DE stays 1k-only secondary stratum, stratum-scoped
  claims only (the ratified policy deliberately excludes DE cells).
- Close = an attribution verdict ((a)/(b)/(c)/(d) or a scoped combination) + either a routed
  fix with an owner, or an honest scoped claim per 704's scoped-claims principle.

---

## §A. Execution record — 2026-07-29 attribution pass (phases 0-2, offline)

> **Status of this section:** results, not plan. Every number below comes from a script
> committed in this PR under `scripts/jseval/experiments/`; each subsection names its script
> and its output artifact. Nothing here required a paid API call, a running backend, or the
> GPU — the machine's GPU and eval backend were held by another worker for the whole window,
> so this pass was executed deliberately as an **offline** campaign.
>
> **Where the artifacts live.** The JSON outputs cited below sit under `scripts/jseval/tmp/748/`,
> which is **gitignored** (`scripts/jseval/.gitignore:4`), as is every other jseval run artifact
> this repo produces. The committed reproduction path is the four scripts under
> `scripts/jseval/experiments/` plus the exact invocations in §G — re-running a script regenerates
> its artifact. Two of the four measurements additionally read inputs that are *also* local-only
> (a June `mixed/miracl-de-2k` run directory in the main checkout, and an assembled EN cell in a
> sibling worktree); those paths are named inline so a later reader can tell reproducible-here from
> reproducible-only-on-this-machine.

**Constraints this pass ran under (they shape what is and is not answered):**

| Constraint | Consequence |
|---|---|
| No paid API calls of any kind | No closed-book certification. The DE rebuild is certified **structurally only** and is explicitly **not claim-eligible**. |
| Shared GPU + eval backend held by another worker | No `jseval run`. Charter experiments 3 (candidate-depth sweep) and 4 (DE chunk-granularity) are **not** executed; §G scripts them. |
| One Gradle build machine-wide | No Gradle invocation was made by this pass. |
| **Thermal event mid-pass** — the machine reached 100 °C core temp and CPU-heavy processes were killed; a one-heavy-lane-at-a-time rule was imposed, with the slot owned by another worker | The EN exact-NN scale probe was killed mid-run and **deliberately not restarted** — the question it was going to answer turned out to be already answered by a recorded measurement (§E.4), which is the better outcome anyway. No further encoder probes were started. Remaining compute-heavy steps are scripted in §G rather than run. |

**Two of the charter's four experiments rested on premises that no longer hold**, and finding
that out is part of the result:

1. Experiment 2 ("staged-recall decomposition on the existing de-miracl-10k artifacts —
   *projections already on disk from the 4 runs*") — **the artifacts are gone** (§C).
2. Experiment 1 ("build an *EN* gold stratum with the identical zero-lexical-overlap
   synonym-descriptor construction") — **it already exists, and it was already measured** on a
   defillered substrate by the 767/781 rebuild (§D). The charter could not know this: 767's
   rebuild post-dates it.

## §B. Phase 0 — the defillered `de-miracl` rebuild

The 776 provenance caveat is confirmed by direct measurement, not inherited: **240 of 240**
fabricated gold documents in every `707-corpora/de-miracl` cell contain the exact `_FILLER`
string from `scripts/jseval/jseval/corpus_generate.py:52`, against **0 of 100** in the rebuilt
English member `781-corpora/en-legal-clerc`. Measured by
`scripts/jseval/experiments/gold_bridge_pair_748.py` (`docs_with_filler_before`,
`filler_share_before` in `scripts/jseval/tmp/748/gold-bridge-pair.json`). Every de-miracl
retrieval number in this tempdoc's header table and in register row Q-018 was therefore
measured on a substrate where one grep enumerates the entire gold set — **in English, inside a
German corpus**.

### §B.1 What was rebuilt, and the three defects that had to be fixed first

The rebuilt member lives at `scripts/jseval/748-corpora/de-miracl/`, laid out like the English v2
member (`781-corpora/en-legal-clerc/`). It uses the **same construction the English member uses**,
so the two become a matched pair differing only in language and host corpus: `axis=prose, lang=de,
n_chains=50, hops=1, distractor_ratio=0, doc_words=None, semantic=True, seed=635,
payload_version=payload.v2`, assembled by `corpus-inject-real` at seed 707, style interleave,
`host_min_words=60`, into 900 / 9,900 real MIRACL-de distractors.

Verified on disk (measured, not asserted):

- **`_FILLER` occurrences: 0 of 100 gold docs in every one of the four cells** — against 240 of 240
  in the corresponding v1 cells. The leak is gone.
- The entity bank at `748-corpora/de-miracl/entity-bank/` is harvested from the **real** German
  hosts (`host.corpus miracl-de-hosts-30k`, `n_docs 30001`, `raw_source_signature bb2d828b…` —
  matching the `real_source_sha256` recorded in the v1 recipe), under a new `wiki` domain.
- Assembly is reproducible: each cell's `recipe.json` records
  `assembly_determinism.passed: true` by cross-process regeneration diff, and every cell carries a
  `commitment.v1.json` with per-file sha256.
- The German payloads read as German and carry rotated phrasings — `… wurde von X geleitet /
  gegründet / beauftragt / entworfen / erbaut`, tails such as `Die abschließende Notiz zu … lautet
  …` — against v1's single fixed `Das Objekt {x} ist mit {y} verknüpft.` on every doc.

Three defects blocked a *correct* rebuild and were fixed rather than worked around:

- **D-1 — no encyclopedic host domain.** `entity_bank.DOMAINS` was `("legal", "email")` and
  `validate_entity_bank` enforces the set, so a German Wikipedia bank could only have been
  committed by mislabelling it `legal`. A third value `wiki` was added and threaded through the
  harvester, deliberately contributing **no** domain-specific extraction patterns (encyclopedic
  prose has no citation/docket apparatus to key on). `host.domain` is committed provenance;
  mislabelling it would misdescribe the artifact for every later reader.
- **D-2 — the German render path never received tempdoc 767 §I.3's template-anchor fix.** The
  per-chain relation/tail rotation that stopped every English gold doc from sharing one 5-gram was
  English-only, so on the German path the link sentence and the tail were *fixed strings*:
  `verknüpft` appears in 120 of 240 v1 docs and `Über` in another 120
  (`leak-validity-de-v1.json`). `_RELATIONS_DE` and `_TAIL_PHRASINGS_DE` now exist and rotate per
  chain — and, importantly, the existing invariant guards
  (`test_relation_phrasings_are_token_disjoint`, `test_tail_phrasings_have_no_fixed_5gram`,
  `test_relation_and_tail_templates_are_spread_across_gold`) were **parametrized over the German
  pools**, so the invariant is machine-checked for German rather than trusted.
- **D-3 — a cross-language minting leak, avoided.** The bank harvester's classifier is
  English-lexicon; on German hosts it recovers ~79k unique PER surfaces but only ~2.4k ORG and
  **22** LOC, and those ORG/LOC surfaces are themselves English foreign proper names that entered
  German Wikipedia as such. Minting from them would splice English-shaped names into German hosts —
  a language-of-origin signal separating gold from native, i.e. a *new* leak of exactly the class
  this rebuild removes. Handled with a per-bank `parameters.mintable_types` narrowing
  (`entity_bank.bank_mintable_types`) rather than by mutating the module-level `MINTABLE_TYPES`
  constant, because it is a fact about *this* bank, not about the generator.

**Consequence that must not be discovered later by surprise:** fixing D-2 changes what the German
generator emits, and the German verbose→short-natural transform in
`corpus_query_strata._short_natural` was updated to match the new template. Therefore **the v1
German cells (`707-corpora/de-miracl/`) can no longer be regenerated byte-identically** — their
`cross_process_regeneration` check would now fail against current code. This is inherent to
repairing a generator defect and is the same situation tempdoc 664 and 767/781 recorded for their
own regenerations ("new content, verified reproducible", not a byte-restoration of the original).
The v1 cells remain committed as dated history and their committed bytes are unchanged; what is
lost is the ability to *re-derive* them from today's generator. Anyone re-running the v1
certification should expect that check to fail and read this note, not treat it as a new defect.

### §B.2 Certification is LITE, and the record says so

The rebuilt member carries `claim_eligible: false` and a `certification_scope` field stating
that only the structural/offline gates were run. The four `SCIENTIFIC_GATES`
(`scripts/jseval/jseval/corpus_certify.py:43-45`) — `closed_book`, `retrieval_calibration`,
`union_recall`, `leak_floor` — are recorded as **pending**, because `closed_book` costs money
(excluded by this pass's hard constraint) and the other three need the shared eval backend.
This is not a downgrade of the member; it is the honest status of a corpus certified without
the paid tier. **DE therefore remains a non-claim-bearing secondary stratum after this pass**,
exactly as the charter's Boundary requires — the rebuild removes the leak, it does not promote
the member.

## §C. Phase 1 — the staged-recall decomposition cannot run on "existing artifacts"

The charter's cheapest experiment assumed the four 2026-07-16 `mixed/de-miracl-*` runs were
still on disk. They are not. Searched across every jseval run root on this machine
(`scripts/jseval/tmp/eval-results` in the main checkout and in all 21 worktrees): **zero**
directories matching `de-miracl`. The only surviving MIRACL runs are six June
`mixed/miracl-de-2k` / `mixed/miracl-fr-2k` runs in the main checkout, and those were
single-mode (`modes: ["hybrid"]`), so their own
`projections/staged_recall_accounting.json` already reports `status: "insufficient-modes"` —
the projection needs at least one of `{vector, lexical, splade}` per-query artifact plus a
final mode (`scripts/jseval/jseval/projections/staged_recall_accounting.py:78-82`).

**Verdict: experiment 2 is re-routed to Phase 3.** It needs a fresh multi-mode run, not an
analysis pass; the command is in §G. Little is lost: on the leaky substrate the decomposition
would have been decomposing the leak. The rebuilt member (§B) is its correct input.

What Phase 1 delivered instead is the decomposition the surviving artifacts *do* support, and
it attacks the same (a)-vs-(c) fork — §E.1.

## §D. Phase 2 — the EN control already exists, and English collapses too

The charter's experiment 1 asks for "an *EN* gold stratum with the identical
zero-lexical-overlap synonym-descriptor construction on the existing EN distractor mass ... at
1k+10k", and pre-registers the decision rule: *"If EN-synonym gold also halves at 1k and
collapses at 10k, the finding is task-shape, not German."*

That stratum is `781-corpora/en-legal-clerc` — same generator, same semantic
zero-lexical-overlap descriptors, 1k and 10k, planted in real CLERC hosts — and it was
measured to full certification on 2026-07-22. The numbers live inside its own certification
record, `scripts/jseval/781-corpora/en-legal-clerc/structural-certification.v1.json`, in the
base64 `retrieval_calibration` measurement artifact of each cell:

| cell (EN, defillered; `field_selectivity` separability 0.0, closed-book 0.000) | hybrid nDCG@10 | vector | lexical |
|---|---|---|---|
| en-legal-clerc-1k-verbose | 0.3103 | 0.2582 | **0.0** |
| en-legal-clerc-1k-short-natural | 0.2419 | 0.2755 | **0.0** |
| en-legal-clerc-10k-verbose | **0.0996** | 0.0867 | **0.0** |
| en-legal-clerc-10k-short-natural | **0.1105** | 0.1007 | **0.0** |

Provenance check (these numbers were not taken on trust): the four values decoded here from the
certification artifact match, digit for digit, the four the search-quality register already records
independently for the v2 cohort in its 781 Corpus provenance note ("legal 0.3103 / 0.2419 / 0.0996 /
0.1105"). Two independent paths to the same numbers.

Set beside this tempdoc's header table (DE, *leak-inflated*: 1k 0.2053 / 0.2660,
10k 0.0431 / 0.0428):

- **The 10k collapse is not German.** English, on English hosts, with English queries, falls
  from ~0.24-0.31 at 1k to ~0.10 at 10k — the same shape, on a member otherwise healthy enough
  to be fully certified and claim-bearing.
- **The lexical zero is not German either.** The charter calls the DE lexical 0.0 "the
  pre-registered, confirmed German grep-collapse prediction". The English member's lexical leg
  is *also* exactly 0.0 at every size. Zero lexical overlap is something the generator
  **constructs on purpose** (`corpus_generate.py:60-75`: the two members of every synonym pair
  must share no token, guarded by `test_sem_pools_are_root_disjoint`). Attributing that zero to
  German compounding was a mis-attribution — German is doing no work in that number.
- **A residual DE-vs-EN level gap is real** and is what the rebuild exists to quantify: DE 10k
  0.043 vs EN 10k 0.10, and the DE figure is leak-*inflated*, so the true gap is wider, not
  narrower.

**This satisfies the charter's own pre-registered decision rule for hypothesis (c).** By the
rule written before the data was seen: "EN-synonym gold also ... collapses at 10k" ⇒ "the
finding is task-shape, not German."

## §E. Offline instrument results

### §E.1 Real German, real queries, real qrels: the encoders are fine (against (a) and (d))

`scripts/jseval/experiments/de_bridge_lexical_stratification_748.py`, over the surviving
`mixed/miracl-de-2k` artifacts (3,104 real German Wikipedia docs, 305 real MIRACL questions,
real qrels — **no fabricated gold, no `_FILLER`, no injection, hence no leak**). Artifact:
`scripts/jseval/tmp/748/lexical-stratification-de.json`.

Overall: **nDCG@10 0.7283, R@10 0.9805, P@1 0.5213.**

Stratified by IDF-weighted query/gold lexical overlap (corpus-derived IDF, Unicode tokenizer,
no authored German stopword list — so the statistic introduces no per-language lever of its
own):

| IDF-coverage stratum | n | nDCG@10 | R@10 | P@1 |
|---|---|---|---|---|
| [0.00, 0.20) | 18 | 0.3861 | **0.8704** | 0.0556 |
| [0.20, 0.40) | 64 | 0.6602 | 0.9922 | 0.4219 |
| [0.40, 0.60) | 132 | 0.7408 | 0.9943 | 0.4773 |
| [0.60, 0.80) | 76 | 0.7999 | 0.9689 | 0.6974 |
| [0.80, 1.00] | 15 | 0.9568 | 1.0000 | 1.0000 |

And the sharpest datapoint: **three queries share literally zero tokens with their gold
document** (`coverage` 0.000, `idf_coverage` 0.000) — and the engine returns the gold document
in the top 10 for **all three** (`recall@10` 1.0, nDCG 0.33-0.36). The six queries at
`idf_coverage <= 0.05` are likewise all `recall@10` 1.0.

**Reading:** ranking degrades as the lexical anchor disappears (nDCG 0.96 → 0.39), which is
expected and healthy; but *recall* — "is the gold document in the building at all", the exact
quantity that reads 0.10 on de-miracl-10k — stays at 0.87-1.00. The German semantic leg does
bridge zero-lexical-overlap German queries. Hypotheses **(a)** (German representation quality)
and **(d)** (German compounding/tokenization breaking indexing) are both substantially
weakened at this scale, on a corpus with no construction artifact at all.

### §E.2 Exact-NN scale curve on real German: no ANN/fusion tax to speak of (against (b))

`scripts/jseval/experiments/encoder_bridge_scale_748.py` — the production encoder
(`models/onnx/gte-multilingual-base`, CLS pooling, no prefixes, mirrored from the model
directory's own `pooling_config.json` / `prefix_config.json`), CPU only, **exact** cosine
nearest-neighbour: no ANN graph, no candidate cut-off, no fusion, no reranker, no engine.
Artifact: `scripts/jseval/tmp/748/bridge-scale-real-miracl-de.json` (612 s of CPU encoding).

| pool size | exact-NN R@10 | nDCG@10 | P@1 | gold margin | gold rank p90 |
|---|---|---|---|---|---|
| 803 | 1.0000 | 0.9805 | 0.9705 | 0.1849 | 1.0 |
| 1,000 | 1.0000 | 0.9540 | 0.9180 | 0.1293 | 1.0 |
| 2,000 | 1.0000 | 0.8621 | 0.7541 | 0.0417 | 3.0 |
| 3,104 | **0.9934** | 0.7749 | 0.6066 | 0.0176 | 4.0 |

Two things follow. First, the engine's measured hybrid nDCG on this corpus (0.7283) sits just
under the *exact-NN dense ceiling* (0.7749) — so on real German at ~3k there is no large
ANN-truncation or fusion tax to find. Second, the gold-vs-best-distractor cosine margin decays
steeply with distractor mass (0.185 → 0.018: a 10x compression over 4x the documents) while
recall holds. That is the mechanism to keep in view: **crowding compresses the margin long
before it costs recall — until the margin the construction starts with is small enough that
crowding eats it.** Which is precisely the fabricated gold's situation (§E.3).

### §E.3 The matched-pair bridge probe: the fabricated bridge starts thin, and DE starts thinner

`scripts/jseval/experiments/gold_bridge_pair_748.py` — gold payloads only, no host corpus, no
engine, exact cosine, `_FILLER` stripped before encoding so the German side is not scored on
English text it should never have carried. Competitor pools capped to a common 100 documents
(`--pool-cap 100`), because the two members were generated with different `distractor_ratio`
(EN v2 = 0, DE v1 = 5) and both margin and rank depend on pool size. Artifacts:
`scripts/jseval/tmp/748/gold-bridge-pair.json` (uncapped) and `gold-bridge-pair-cap100.json`
(controlled).

| cell (pool = 100) | n queries | bridge P@1 | R@10 | nDCG@10 | gold margin | gold rank p90 |
|---|---|---|---|---|---|---|
| en-legal-clerc-v2-verbose | 50 | **0.84** | 1.00 | 0.5737 | **0.0460** | 2.0 |
| en-legal-clerc-v2-short-natural | 50 | **0.88** | 1.00 | 0.5775 | **0.0416** | 2.0 |
| de-miracl-v1-defillered-verbose | 20 | 0.55 | 0.85 | 0.4449 | 0.0164 | 12.2 |
| de-miracl-v1-defillered-short-natural | 20 | 0.55 | 0.90 | 0.4537 | 0.0137 | 7.5 |

The EN-vs-DE P@1 gap is statistically real (Fisher exact, two-sided: p = 0.0154 verbose,
p = 0.0074 short-natural). Two readings, and the distinction matters:

- **Both bridges are thin in absolute terms.** Even English tops out at a mean gold margin of
  ~0.046 among only 99 competitors. Set that against §E.2 — 1k→3k of real distractors
  compresses the margin ~10x — and a construction whose margin *starts* at 0.04 has no
  headroom left by 10⁴. **That is the collapse mechanism, and it is language-neutral.**
- **The German bridge starts ~3x thinner still** (0.014-0.016 vs 0.042-0.046), with a 90th
  percentile gold rank of 7.5-12.2 against English's 2.0. This is the residual DE gap, and it
  is present *at the payload level*, before any host document or engine is involved.

**CONFOUND, stated plainly:** this pair is EN **payload.v2** against DE **v1**. They differ in
`payload_version`, in `n_chains` (50 vs 20), in `distractor_ratio` (0 vs 5 — controlled here
by `--pool-cap`), and in whether entity surfaces are minted from a bank harvested off the real
host corpus. Language is therefore **confounded with payload version** in the table above, and
the honest form of the DE number is "the DE-v1 bridge", not "the German bridge". Removing
exactly this confound is what the §B rebuild is for; §G.1 is the one-command rerun that turns
it into a clean single-variable comparison.

### §E.4 Hypothesis (b) is already refuted by a recorded measurement — do not re-run it

The charter's experiment 3 (candidate-depth sweep) exists to test whether ANN truncation or fusion
depth is starving the semantic leg as distractor mass grows. Before spending an hour of CPU on a
fresh exact-NN scale curve over the fabricated cells, the register was checked — and the
measurement already exists.

**F-040 (tempdoc 774 §J.5, 2026-07-22)** ran a Gate-0-anchored **offline exact-NN** passage probe
(chunk-MaxP, 500/50, incumbent encoder — no ANN graph, no candidate cut-off, no fusion, no
reranker) over the certified camouflaged EN strata, and reports:

| cell | offline exact-NN recall@10 | R@100 | shipped engine hybrid recall@10 |
|---|---|---|---|
| en-legal-clerc-1k-verbose | 0.20 | 0.50 | 0.54 |
| en-legal-clerc-10k-verbose | **0.04** | **0.20** | 0.16 |

Two conclusions, neither of which needed a new run:

- **Exact NN collapses 1k → 10k by the same factor the engine does** (0.20 → 0.04 vs 0.54 → 0.16).
  A retrieval method with *no* candidate cut-off cannot be losing gold to a candidate cut-off.
  **Hypothesis (b) is refuted**, and the candidate-depth sweep would be answering a closed
  question — §G records that as an explicit skip-with-reason rather than an unrun item.
- **The engine BEATS its own isolated-passage exact-NN ceiling** on these cells (0.54 vs 0.20 at
  1k; 0.16 vs 0.04 at 10k) — the whole-doc, context-bearing representation is doing real work that
  isolated passage vectors do not. F-040's H.4 arm sharpens the mechanism: a uniform 150-char
  doc-lead prefix on every chunk embed lifts the 10k floor from R@100 0.20 to 0.42. **The
  fabricated gold's problem is context starvation of a short planted payload**, which is the same
  story §E.3 tells from the margin side (a bridge that starts at ~0.04 cosine) and §E.2 tells from
  the crowding side.

Caveat, stated because it bounds the transfer: F-040 measured the 767-era camouflaged strata, one
rebuild before the 781 title fix, and on the EN member only. The DE-side replication is §G.2.

## §F. Interim attribution verdict

Against the charter's four candidates, on the evidence above. **This does not close Q-018.**

- **(c) gold design / task shape — SUPPORTED, and dominant.** The charter's own pre-registered rule
  fires: the identical construction in English collapses at 10k (0.0996 / 0.1105) from a 1k value
  (0.2419-0.3103) that was already banded "hard". The lexical-leg zero is constructed, not German.
  §E.3 supplies the mechanism — the synthetic synonym bridge starts with a cosine margin of ~0.04 —
  and §E.2 supplies the scaling law that eats it: distractor mass compresses margins ~10x per 4x of
  corpus.
- **(a) German representation quality — WEAKENED as the primary cause, NOT eliminated as a
  secondary one.** Real German with real questions scores 0.7283 nDCG / 0.9805 recall at 3k, and
  holds 0.87-1.00 recall at (or at exactly) zero lexical overlap (§E.1). But the DE payload bridge
  is ~3x thinner than the EN one at matched pool size and the gap is significant (§E.3), so a
  genuine German-specific level offset survives — confounded with payload version until §B's
  rebuild is measured.
- **(b) scale / candidate-depth interaction — REFUTED on the EN member, by a measurement that
  already existed.** F-040's offline exact-NN passage probe — no ANN, no candidate cut-off, no
  fusion — collapses from recall@10 0.20 at 1k to 0.04 at 10k on these very cells (§E.4). A method
  with no candidate cut-off cannot be losing gold to one. Independently, on real German the
  engine's hybrid sits just under the exact-NN ceiling (§E.2), so ANN/fusion are not where the
  recall goes there either. The DE-side replication of the exact-NN curve is the one remaining
  gap (§G.2).
- **(d) German text mechanics — LARGELY ELIMINATED.** Real German of the same encyclopedic register
  indexes and retrieves at 0.7283 / 0.9805 through the same locale-invariant analysis chain.
  Compounding is not preventing retrieval.

**What this does NOT license.** It does not close Q-018, and it does not promote DE. The verdict is
"the collapse is chiefly a property of the fabricated zero-lexical-overlap construction at scale,
with a smaller German-specific offset on top" — and the size of that offset is precisely the number
this pass could not measure, because measuring it requires running the rebuilt member through the
engine.

## §G. The Phase-3 remainder — exactly what to run, and under what conditions

Everything below needs the shared eval backend and/or the GPU. **Contention rules that bind whoever
runs this:** verify the port is free first and prefer a non-default port; declare a
`leaseDurationSec` long enough for the campaign; stop the backend when finished. Set
`PYTHONPATH=<this-worktree>/scripts/jseval` and `PYTHONUTF8=1 INSPECT_DISPLAY=none` for any
backgrounded invocation.

### §G.1 Re-run the two offline probes against the REBUILT DE member (removes the §E.3 confound)

Zero backend, zero GPU, ~2 minutes. This is the *first* thing to run, because it converts §E.3's
confounded EN-v2-vs-DE-v1 comparison into a clean single-variable one.

```bash
python scripts/jseval/experiments/gold_bridge_pair_748.py --pool-cap 100 \
  --cell scripts/jseval/781-corpora/en-legal-clerc/1000-verbose --label en-legal-v2 \
  --cell scripts/jseval/748-corpora/de-miracl/1000-verbose      --label de-miracl-v2 \
  --out scripts/jseval/tmp/748/gold-bridge-pair-v2.json
```

Decision rule, pre-registered here: if the DE-v2 bridge P@1 and margin land inside the EN-v2 range,
the residual German offset in §E.3 was payload-version, not language, and hypothesis (a) is
eliminated. If the gap persists at matched payload version, (a) is a real secondary cause and earns
a scoped claim.

**§G.1 RESULT (2026-08-14, session 7eb0297f / tempdoc 832 lane A; artifact
`scripts/jseval/tmp/748/gold-bridge-pair-v2.json`, exact command above, pool-cap 100, n=50/cell,
CPU exact-cosine):** the gap PERSISTS at matched payload version — **hypothesis (a) survives as a
real, scoped secondary cause.** EN-legal-v2: bridge P@1 **0.84**, margin mean **+0.046** (positive
share 0.84), gold rank median 1.0 / p90 2.0 — reproduces §E.3's EN band exactly (0.84-0.88 /
0.042-0.046), which is the control that makes the arms comparable. DE-miracl-v2 (defillered
payload.v2 rebuild): bridge P@1 **0.30**, margin mean **−0.026** (positive share 0.30), gold rank
median 3.0 / p90 18.1, recall@10 0.78. Both cells confirmed filler-free by the instrument
(`docs_with_filler_before: 0`). Two honest notes: (1) DE-v2 scores BELOW the §E.3 DE-v1 figure
(P@1 0.55, margin +0.014..0.016) — consistent with the English `_FILLER` block having inflated
even the pool-only v1 measurement, so the §E.3 Fisher gap was, if anything, understated; (2) the
mean margin is *negative* — at matched construction the median German gold doc scores below its
best same-pool distractor, i.e. the fabricated DE bridge starts underwater before any corpus,
scale, or host-dilution effect. Interpretation unchanged in rank: (c) task-shape remains the
dominant collapse mechanism at 10k (the EN member collapses too); (a) is now a measured,
unconfounded secondary cause specific to DE. Q-018 stays OPEN pending §G.2/§G.3.

### §G.2 The DE-side exact-NN scale curve (no backend, CPU, ~1 h)

The EN half of this is already answered by F-040 (§E.4); what is missing is the German replication,
so that "(b) is refuted" is a measured statement about the DE member rather than a transfer.

```bash
python scripts/jseval/experiments/encoder_bridge_scale_748.py \
  --docs   <datasets>/mixed/de-miracl-10k-verbose/corpus.jsonl \
  --queries <prepped>/de-miracl-10k-verbose.queries.json \
  --qrels   <prepped>/de-miracl-10k-verbose.qrels.json \
  --pool-sizes 1000,2000,5000,10000 --label de-miracl-v2-10k-verbose \
  --out scripts/jseval/tmp/748/bridge-scale-de-miracl-v2.json
```

Two things whoever runs this must handle, both learned the hard way in this pass:

1. **The assembled gold ids are the perturbed HOST ids, not the fabricated `evidence_ids`.** Derive
   `qrels.json` from the cell's own `qrels/test.tsv` and key queries by `query_family_id`; joining
   on `evidence_ids` silently yields zero resolvable gold.
2. **Whole-doc truncation is a validity trap on long hosts.** The script truncates at
   `--max-len` (512) tokens. If the injected payload sentence sits beyond that offset in its host
   document, the probe measures "the gold text was cut off", not "the bridge failed. Check the
   payload's character offset within each assembled gold doc first; if a material share falls
   outside the window, use a chunk-MaxP condition (F-034's condition C / F-040's probe) instead of
   whole-doc truncation. This is exactly why the EN run was retired in favour of F-040's
   already-recorded chunk-MaxP numbers rather than re-run here.

### §G.3 Fidelity re-measurement of the rebuilt DE cells (backend, alternate port)

```bash
python scripts/jseval/serve-eval-backend.py --port 33231 --clean \
  --ready-file  tmp/748/be.ready  --stop-file tmp/748/be.stop \
  --stopped-file tmp/748/be.stopped --failed-file tmp/748/be.failed
# then, per cell:
python -m jseval corpus-fidelity --dataset de-miracl-10k-verbose \
  --base-url http://127.0.0.1:33231 --modes vector,lexical,splade,hybrid --embedding
```

This is also the input the charter's experiment 2 needs: a **multi-mode** run writes the
`{vector,lexical,splade}_per_query.json` artifacts that `staged_recall_accounting` requires
(`scripts/jseval/jseval/projections/staged_recall_accounting.py:78-82`), which the surviving
single-mode runs do not have (§C).

### §G.4 The two charter experiments that still have not been attempted

- **Candidate-depth sweep (charter experiment 3) — SKIP, with reason.** Its premise is hypothesis
  (b), which §E.4 refutes on the EN member using a probe that has no candidate cut-off at all. A
  sweep over candidate K cannot recover recall that an unbounded method also fails to find. Re-open
  it only if §G.2's German replication *contradicts* F-040 — i.e. if DE exact-NN holds flat 1k→10k
  while the DE engine collapses. Recorded as a deliberate skip rather than an unrun item, per the
  tempdoc-is-a-contract rule.
- **DE chunk-granularity probe (charter experiment 4)** — one run per size against the rebuilt
  cells. Expected low yield (708 measured +3 pts on EN-legal) but German compounding keeps
  doc-vs-chunk a live variable.

## §H. Measurement-validity caveats (found while doing the work)

1. **The corpus-leak instruments are ASCII/English-shaped.**
   `scripts/jseval/jseval/corpus_leak.py:81-86` tokenizes with `[a-z0-9']+`, and `:61-77`
   drops an English-only stopword list. On German, `Straße` becomes `stra`+`e`, `über` becomes
   `ber`, and `der`/`die`/`das`/`und`/`mit` count as *content* tokens on both sides. Quantified
   by `scripts/jseval/experiments/leak_instrument_language_validity_748.py`
   (`scripts/jseval/tmp/748/leak-validity-de-v1.json`): on the DE v1 1k-verbose gold the
   shipped instrument reports mean query/gold content overlap **0.2064**, while a
   language-agnostic recomputation (Unicode word runs over NFC, corpus-derived stopwords)
   reports **0.1228** — the shipped instrument over-states German overlap by ~68% relative. For
   `query_overlap_report` that direction is conservative (low overlap is the healthy
   direction, so the gate is merely stricter than reality); for `ngram_selectivity_report` the
   fragmentation direction can *hide* a German anchor, which is **not** conservative. Logged to
   the observations inbox; deliberately **not** fixed here — it is a pre-existing
   instrument-scope issue rather than a 748 regression, and changing it would move gate
   outputs for members other agents are actively measuring.
2. **The measured fragmentation ratio on DE v1 looks deceptively small** (1.0018, 23 non-ASCII
   token types) *because the documents are 3,878 characters of mostly English filler*. On the
   defillered German rebuild the same ratio gets materially worse, so caveat 1 becomes *more*
   load-bearing after the rebuild, not less.
3. **The DE render path never got 767 §I.3's template-anchor fix.** On the German branch the
   non-head sentence (`corpus_generate.py:555`) and the tail (`:569`) are fixed strings — the
   per-chain relation/tail rotation that killed the template-anchor leak for English is
   English-only. Directly visible in the v1 payload: `verknüpft` appears in 120 of 240 docs and
   `Über` in another 120 (`leak-validity-de-v1.json` → `top_fragmented_tokens`). Addressed by
   the §B rebuild.
