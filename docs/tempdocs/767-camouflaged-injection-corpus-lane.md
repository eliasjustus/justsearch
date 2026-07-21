---
title: "camouflaged-injection corpus lane: domain-native payload generator (entity bank + register templates), multi-schema questions, leak-free golds, and the certification gates that prove all of it — the 766 program's corpus half"
type: tempdocs
status: "chartered (2026-07-21); design settled 2026-07-21 (§D theorize / §E research / §F design). Founder-run implementation lane; ready to plan."
created: 2026-07-21
author: agent (Fable orchestration), 766 program charter
category: eval-design / corpus-generation
related:
  - 766-eval-content-rebuild-program   # umbrella: design decisions D2-D5, D8 — READ FIRST (and 762 §X for evidence)
  - 707-pillar1-inband-utility-corpus  # the pipeline being extended
  - 741-eval-corpora-are-derived-artifacts
---

> Charter. Execute after 766 §B (D2/D3/D4/D5/D8) and 762 §X.6.1/.3. The
> injection machinery, determinism proofs, and certification framework
> already exist — this lane replaces the PAYLOAD and adds GATES.

# 767 — camouflaged-injection corpus lane

## §A. Work items

1. **Entity bank + minting (D2).** Harvest a typed entity bank from each
   host corpus (deterministic NER or per-domain heuristic harvesting —
   mechanism is yours; determinism given corpus+seed is mandatory, no LLM in
   the build path). Mint gold entities type- and length-matched to native
   entities, collision-checked against the bank (must not exist in any real
   doc) and against each other. Kill the syllable-pair minting path.
2. **Register-matched fact sentences (D2).** Per-domain sentence templates
   in the host register (email prose for Enron hosts, judicial prose for
   CLERC hosts) replacing `_FILLER` and the current template sentences.
   Interleave via the existing `corpus_inject.assemble` seam.
3. **Leak-free golds (D5).** Format-diverse, domain-plausible gold values;
   value minting decoupled from entity uid counters (delete
   `corpus_generate.py:320-323`'s coupling); exact-match after
   normalization stays the scoring contract.
4. **Multi-schema questions (D4).** Single-fact lookup + 2-hop bridge +
   multi-doc aggregation, each with per-schema difficulty calibration;
   `question_type` labeling per the register's hop-count vocabulary note.
5. **Certification gates (D3).** In `corpus_certify` (structural-check and
   SCIENTIFIC_GATES seams, see 762 §X.6.1): distractor-flood index,
   injected-entity indistinguishability, naming/format-leak, gold
   dispersion, per-schema difficulty band. Wire thresholds into the 707
   certification policy file; a failing corpus is unbuildable into a
   campaign.
6. **Strata + distribution (D8).** Regenerate en-legal-clerc and
   en-email-enron-raw strata (1k/10k) on the new payload; "synthetically
   altered" header stamp as a build invariant; PII-scrub gate for published
   Enron samples; fetch-then-inject recipes only (no modified real docs
   committed). Optional (explicitly severable): third gov-docs stratum.
7. **Register duty.** Update the search-quality register Dataset Catalog
   (new strata rows; annotate the v5 strata as superseded-for-claims) and
   `/search-quality` + re-run `retrieval_calibration` baselines for the new
   strata before closing.

## §B. Acceptance

- All 766 §D orphan items 1/2 deleted in this lane's PR (retire-with-a-sweep).
- New strata pass ALL certification gates including the new five; closed_book
  ≈ 0 at haiku AND at the hero tier (coordinate with 768 for the tier run).
- A grep-simulation probe (the 764 mechanism-probe method) shows the
  baseline arm's expected grep experience is now vocabulary-matched across
  strata — the confound is measured dead, not assumed dead.
- Determinism: cross-interpreter regeneration proof green (existing seam).
- jseval suite green; `check-language-agnostic-analysis` unaffected (payload
  generation is corpus tooling, not engine analysis).

## §C. Constraints

- Zero paid-API in the build path (local model via `ai_activate` permitted
  for VALIDATION probes only, never generation).
- Windows: PYTHONUTF8=1; Edit/Write or python UTF-8 scripts only.
- 762 §D data inventory for all prior-campaign references; step2-powered
  worktree read-only.

## §D. Theorize (2026-07-21)

The charter fixes the *what* (D2-D5, D8); this section explores the *how* before
settling it in §F. The generator being replaced renders one question schema
(a fabricated entity-relation chain) three ways — prose, code, tabular
(`corpus_generate.py` `_render_prose`/`_render_code`/`_render_tabular`) — over
syllable-minted entity names (`_name`, ~:288), `_FILLER` boilerplate (:48), and
gold values coupled to the entity uid counter (`_chain`, :319-323). The real-text
injector (`corpus_inject.assemble`) already interleaves each gold's sentences into a
genuine host document and proves cross-interpreter determinism. So the substrate is
real; only the payload is alien. Four directions structure the rebuild.

**Direction 1 — where the camouflage actually comes from.** Two sources of
"domain-native" are available and they are not equivalent. (a) *Minted entities that
look like the domain's* (a fake judge that reads like a judge, a fake sender that
reads like an email address). (b) *Carrier sentences that are actually the domain's*
— rather than author a "fact sentence" and hope it matches the register, take a real
host sentence of the right relational shape and substitute minted entities into it.
(b) is strictly stronger against shape-grep because there is no authored sentence to
fingerprint. The tension: a planted needle needs a *new* fact (an un-guessable value
that is the answer and appears in no real doc), which pure in-place substitution
cannot manufacture. The design must therefore blend: real carrier where one exists,
authored scaffolding only at relation junctions, and certify that the blend leaves no
shape signature.

**Direction 2 — entity harvest: NER vs heuristics, and where the model runs.** The
hidden assumption worth killing early is "harvest must happen in the build path."
It must not. If the typed entity bank is harvested once, offline, and *committed as a
hashed artifact*, then the deterministic, LLM-free, cross-interpreter-proof build path
never imports the harvester — it only samples the frozen bank. That reframes the
spaCy-vs-regex question from "what goes in the build path" (answer: neither) to "what
best fills a committed bank" — where a 500 MB NER dep confined to an offline mint step
is cheap, and regex harvesters for domain-structural entities (email addresses, docket
numbers, case captions) are a complement, not a rival.

**Direction 3 — certification as a null-hypothesis test, not a constant.** Every leak
the charter names (distractor-flood, entity indistinguishability, shape/format leak,
dispersion) is really the same question: *is the injected profile distinguishable from
the host's own native profile?* That argues against fixed thresholds and for deriving
each gate's bound from a native-vs-native null computed on the specific host corpus —
which is also how the repo already sets per-cell retrieval bands (they vary per cell in
the certification policy, i.e. measured, not magic). The template-fingerprint gate is
the novel one: it must prove no delexicalized shape pattern selects gold docs above its
native base rate.

**Direction 4 — difficulty without curve-fitting.** The prior regime killed ~45% of a
10k stratum as dead questions, and any per-model difficulty tuning risks fitting one
tier. The escape: anchor the difficulty band on *retrieval-reachability* (an engine
property — does the gold surface in top-k on the actual engine at certify time?) rather
than on any model's answer rate. Reachability floors kill dead questions pre-spend; a
top-1-triviality cap kills ceiling questions; both endpoints are model-independent.
The existing closed-book gate stays the orthogonal, model-tier-run un-guessability
control.

**Rejected alternatives.** (i) *spaCy in the build path* — puts a heavy non-stdlib dep
and cross-version drift into the determinism proof, for no gain over a committed bank.
(ii) *Pure regex harvest, no NER* — misses the free-text PERSON/ORG/GPE mass that is
most of the camouflage surface in prose-heavy opinions; the bank would be too thin for
type-matched minting. (iii) *Keep authoring register templates and rely on a large
library* — a library reduces but cannot prove the absence of a shape signature; without
the null-calibrated gate it is hope, which is exactly the failure mode D3 exists to end.
(iv) *Fixed indistinguishability constants* — a constant that passes on Enron can fail
on CLERC; the host corpus must be its own null.

## §E. Research (2026-07-21)

- **Faithfulness-QA construction (the D2-cited recipe), verbatim.** A typed entity bank
  is built by running **spaCy `en_core_web_lg` NER once, offline, over all source
  contexts**, collecting 8 types (PERSON, GPE, ORG, DATE, CARDINAL, NORP, LOC, EVENT),
  discarding spans <2 or >100 chars → a **static 76,953-entity bank**. Replacement:
  sample `e_new` with `type(e_new)=t`, `e_new≠e_orig`; **resample ≤5 times** to enforce
  `0.3 ≤ |e_new|/|e_orig| ≤ 3.0`, else reject; enforce `e_new ∉ context` (collision),
  `count(e_orig,context) ≤ 10`, `|e_new| ≥ 2`; replace all case-insensitive occurrences.
  Quality filters (100% audit pass on 200 samples): replacement present, original fully
  removed, context changed, modified/original length ratio in band. Crucially the bank
  is a **committed artifact**, and the substitution carrier is a **real source sentence**
  — there is no authored template. [arXiv 2604.25313](https://arxiv.org/abs/2604.25313),
  [HTML](https://arxiv.org/html/2604.25313v2).
- **spaCy NER determinism.** `en_core_web_lg` NER is a greedy transition-based parser
  with no inference-time randomness; **CPU inference is reproducible run-to-run**.
  Reported non-determinism is GPU *training*, not CPU inference — not applicable to an
  offline harvest. Confirms a committed bank can be regenerated in principle, though its
  reproducibility anchor should be the artifact hash, not a spaCy re-run.
  [spaCy #6490](https://github.com/explosion/spaCy/issues/6490),
  [model card](https://huggingface.co/spacy/en_core_web_lg).
- **Shape/leakage critiques of needle benchmarks.** Recent long-context work flags that
  synthetic benchmarks are "susceptible to data leakage, short-circuiting, and … a
  priori identifiable," motivating designs "resistant to leakage" with distractors that
  are *retriever-dependent* rather than query-independent (Haystack Engineering,
  [arXiv 2510.07414](https://arxiv.org/pdf/2510.07414); MLRBench, referenced therein).
  This is the external warrant for a standing shape-indistinguishability gate rather than
  a one-off audit.
- **NoCha minimal-pair device (D3's un-guessability certifier).** 1,001 minimally-
  different true/false claim pairs over recent fiction; the minimal pair "guards against
  models being right for the wrong reasons while enabling easy verification," and the set
  is withheld to prevent contamination. The transferable idea: an un-guessable planted
  fact plus a control that floors near-zero by construction — which the existing
  closed-book gate already measures. [arXiv 2406.16264](https://arxiv.org/abs/2406.16264),
  [leaderboard](https://novelchallenge.github.io/).
- **Repo machinery confirmed against source (durable, repo-relative paths).** Leak-gate
  seam: `SCIENTIFIC_GATES` and the structural-check set live in
  `scripts/jseval/jseval/corpus_certify.py:38-52`; per-cell measured thresholds (bands
  differ per cell — the measured-derived pattern) in
  `scripts/jseval/707-corpus-certification-policy.v1.json`. Deterministic assembly +
  cross-interpreter proof: `corpus_inject.py` `assemble`/`_interleave` (:25-105) and
  `_cross_process_assembly` (:108-162). Existing collision gate to generalize:
  `descriptor_collision_report` (`corpus_certify.py:1017`, exact-title-match) and
  `regeneration_determinism_report` (:1067). Orphan payload paths to delete:
  `corpus_generate.py` `_SYL_*`/`_ATTR_*` (:38-47), `_FILLER` (:48), `_name` (:288),
  `_chain` value/counter coupling (:319-323), and the domain-alien `_SEM_TYPE`/`_SEM_PLACE`
  synthetic descriptor pools (:65-104).

## §F. Design (2026-07-21)

**F1 — Entity harvest: committed-bank boundary (settles the open question).** The typed
entity bank is a **committed, sha256-pinned artifact per host corpus**, harvested
**offline, once**, by a two-source minter: (a) spaCy `en_core_web_lg` NER (the exact
Faithfulness-QA recipe — 8 types, 2-100 char span filter) for free-text PERSON/ORG/GPE/…
entities; (b) stdlib per-domain regex harvesters for domain-structural entities NER
under-collects (email local-parts/domains for Enron; docket numbers, reporter citations,
party/judge captions for CLERC). Provenance records the spaCy model version and the
bank hash. **The build/regeneration path never imports spaCy or the harvesters** — it
samples the frozen bank with the seeded RNG only. *Rationale:* this confines the 500 MB
NER dependency to an offline mint step, keeps the LLM-free cross-interpreter regeneration
proof (`corpus_inject._cross_process_assembly`) stdlib-only and green, matches the cited
recipe (which itself commits a static bank), and leans on the fact that spaCy CPU NER is
deterministic — while making the *artifact hash*, not a re-run, the reproducibility
anchor. Rejected: spaCy-in-build-path (dep + drift into the determinism gate) and
pure-regex (bank too thin for type-matched minting).

**F2 — Minting (replaces syllable + counter-coupled paths).** Gold entities are minted
**type- and length-band matched** (`0.3-3.0×`, resample ≤5 then reject — Faithfulness-QA),
from a **type-shaped generator** (a minted PERSON reads as a name, a minted ORG as an
org), collision-checked against the *entire host bank* (must equal no real entity of any
type), the *distractor pile*, and *other minted golds*. Gold **values** are minted from a
per-schema, **format-diverse value space decoupled from any uid counter** (kills
`corpus_generate.py:319-323`), collision-checked, never format-uniform. This deletes
orphans 766 §D-1 and §D-2 in this lane's PR (retire-with-a-sweep), including the
`_SYL_*`/`_ATTR_*`/`_FILLER`/`_name`/`_chain` paths.

**F3 — Carrier realism over authored realism (template-fingerprint defense).** Fact
sentences prefer **real host carrier sentences** of the target relation, harvested from
the host corpus, with minted entities substituted in place (no authored template → no
authored signature). Authored scaffolding is confined to relation *junctions* where no
real carrier can guarantee chain connectivity, and there it draws from a **large,
register-matched realization library** (many surface forms per relation). The
`_SEM_TYPE`/`_SEM_PLACE` synthetic-descriptor pools are **orphaned here**: they are
themselves domain-alien ("reactor in the northern marshlands" is as greppable inside a
legal opinion as `_FILLER`), so the semantic-bridge retrieval-difficulty property they
provided is **recast onto host-derived descriptors** (real captions/subjects paired with
their own paraphrases) rather than a synthetic pool. Naming this orphan explicitly is
part of the sweep.

**F4 — The template-fingerprint CERT GATE (new).** A standing gate in `corpus_certify`
alongside `SCIENTIFIC_GATES`: compute a **delexicalized shape signature** over gold
sentences (entities masked; token/character shingles plus a POS/dependency skeleton) and
over a native host-sentence sample. **Fail if any shape pattern selects gold docs at a
rate significantly above its native base rate** (the enumerable-signature condition), and
fail if the gold shape distribution's divergence from native exceeds the **native-vs-native
null** (below). This is the gold-side sibling of the distractor-flood index; together they
prove "shape-grep cannot enumerate gold docs."

**F5 — Null-calibrated thresholds (settles threshold derivation).** Every leak gate's
bound is **derived from a native-vs-native null on the specific host corpus**, not a fixed
constant — the same measured-derived pattern the certification policy already uses
(per-cell bands in `707-corpus-certification-policy.v1.json`). Concretely: distractor-
flood bound = native cross-domain grep base rate ± native split variation; entity
indistinguishability = minted grep-hit profile within the native same-type distribution
(e.g. KS-test non-rejection / [p5,p95] band); shape divergence ≤ native split divergence;
dispersion ≥ a fraction of uniform derived from native doc spread. Thresholds are recorded
per stratum in the policy file with the null-sample provenance, so a skeptic can re-derive
them.

**F6 — Per-schema gold contract + scorer (settles gold-format vs substring scoring).**
Each schema declares a `gold_kind`; the substring-exact-after-normalization scorer becomes
a small **deterministic comparator registry keyed on `gold_kind`** (no judge):
single-fact and 2-hop-bridge → `single_value` (existing substring/exact-normalized path);
multi-doc aggregation → `set` (order-insensitive all-present), `count`/`sum` (numeric
exact after normalization), or `extremum` (single latest/max value). Golds stay exact-
matchable and format-diverse; only aggregation adds a comparator, and each comparator is
exact and deterministic. `question_type` keeps the edge-count labeling (it is
commitment-bound via `query_gold_sha256`; new schemas ship as new committed strata, so no
relabel of existing bytes).

**F7 — Difficulty band without curve-fitting (settles per-tier calibration).** The
per-schema difficulty band extends `retrieval_calibration` and is **anchored on
retrieval-reachability**, a model-independent engine property measured at certify time per
stratum: a **floor** requiring each gold to surface in top-k on the actual engine (kills
dead questions pre-spend — the ~45% failure the prior 10k regime hit) and a **cap**
rejecting single-hop-trivial top-1 golds. Because both endpoints are retrieval quantities,
the band does not track any model tier. The existing `closed_book` gate stays the
orthogonal un-guessability control and **runs at both the haiku floor and the hero tier**
(charter §B), the only model-tier-dependent gate.

**F8 — Distribution (D8).** Fetch-then-inject deterministic recipes for all strata (no
modified real document committed); a machine-visible "synthetically altered" header stamp
as a build invariant; a PII-scrub gate on any published Enron sample. Reuses the 709 fetch
cache and 741 derived-artifact posture unchanged.

**Orphans owned by this lane's PR** (deleted/relabeled here, not a later sweep): the
`_SYL_*`/`_ATTR_*` pools, `_FILLER`, `_name`, `_chain` counter-coupling
(`corpus_generate.py:38-48,288,319-323`); the `_SEM_TYPE`/`_SEM_PLACE` synthetic descriptor
pools (:65-104); and the register-side duty on the v5 strata (annotate superseded-for-claims)
per charter §A-7.

**Principle and reach.** This lane instantiates the program's "camouflage is certified,
not assumed" (766 §E) and sharpens it into a narrower, testable rule: **the host corpus is
its own null hypothesis** — a planted artifact is *indistinguishable* exactly when its
measured profile falls inside the host's native-vs-native null, so every leak gate is an
adversary-relative, null-calibrated test rather than a fixed constant. A second shape:
**carrier realism over authored realism** — substitute into real sentences rather than
author synthetic ones, because a real carrier has no authored signature to leak. *Where
else it applies:* any fixture that plants sentinel content in real data (leak canaries,
honeytokens, watermark probes) and any future synthetic-in-real corpus. *Evidence it earns
its keep:* the first certification run that rejects an alien payload pre-spend, and a
grep-simulation probe (charter §B) showing the baseline arm's expected grep experience is
vocabulary- and shape-matched across strata — the confound measured dead, not assumed dead.
*Retirement condition:* retire the null-calibrated framing if corpora ever become fully
real (no injection), or if a single fixed threshold is demonstrated to generalize across
host corpora (making the per-host null redundant); retire "carrier over authored" once
generation models author sentences that pass the shape null as reliably as real carriers.

**Could not fully settle (flagged for planning).** One quantity is genuinely
measurement-dependent and cannot be fixed in design: the **exact numeric bounds** of the
null-calibrated gates (F4/F5) and the retrieval-reachability band (F7). By construction
they are derived from each host corpus's native null at build time, so the design fixes the
*derivation method and provenance*, not the numbers — the numbers land during
implementation from the measured native samples, and must be recorded in the policy file
with their null-sample provenance before any paid run.
