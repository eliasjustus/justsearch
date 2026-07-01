---
title: "Eval-corpus & ratchet-provenance integrity: a read-only audit found four defect classes in the corpus/qrel/generator/ratchet code; a federated-extension design (no new kernel — three targeted additions to 635/623-640/530's already-existing owners) was settled, fully implemented and live-verified across nine passes (including a self-caught live regression a prior 'verified' claim had unknowingly bypassed), and is now the shipped state of this layer. A ninth-pass design-theorization exercise then examined the open-ended ideas an eighth-pass brainstorm produced (a corpus-doctor CLI, an auto-generated health report, public-facing trust evidence) and found they all read the same not-yet-formalized per-corpus certification data — recognized as a further instance of this codebase's canonical-record-plus-governed-projections seam (553/623/640/625/646), deliberately NOT built yet since no second real consumer exists to fork against. General design only in the ninth pass — no implementation."
type: tempdocs
status: "COMPLETE — NO REMAINING WORK (thirteenth pass) — 2026-07-01, worktree tempdoc-664-eval-corpus-integrity. Seventh pass: original §Design fully implemented and live-verified (1114 tests). Eighth/ninth/tenth passes: an open-ended future-ideas brainstorm, its structural design theorization (canonical-record-plus-projections, deliberately not built — no second consumer yet), and a targeted external-research check. Eleventh pass: triaged the ninth pass's concrete 'Extend' follow-ups by actual readiness. Twelfth pass: implemented and live-verified everything the eleventh pass found ready (corpus write-order interleaving, 5-corpus regeneration + re-certification, leak-gate naming fix, `.changesets` scaffold, a real `jseval calibrate` run). Thirteenth pass: a conceptual-alignment re-read found one real gap the twelfth pass left behind — regenerating `needle-burial-v1` orphaned five exact-number findings (F-023/F-024/F-025/D-004/Q-011) in `docs/reference/search-quality-register.md` from the corpus they cited. Fixed with a dated 'Corpus provenance note' + five short cross-references + a corpus-signature citation (`jseval.corpus_identity.corpus_signature()`) as a durable anchor, without re-running any cited experiment (the register's own rule) and without retroactively bumping validation dates (which would recreate the same label-not-binding problem). This closes tempdoc 664's last open item — no remaining work. See §Thirteenth pass at the foot. Prior passes retained below as dated history."
created: 2026-07-01
updated: 2026-07-01
author: agent analysis — four independent read-only audits (jseval corpus pipeline, Java test fixtures, ratchet/baseline governance, synthetic golden-corpus generation), cross-verified against source, followed by a broad theorization pass (first pass), then a design-and-reach pass against adjacent tempdocs and existing governance machinery (second pass)
related:
  - 625-asserted-measurement-provenance            # the system-wide "verified binding, not a label" principle this audit gives concrete new instances of; this tempdoc is evidence the 625 trigger has fired again
  - 635-contamination-resistant-eval-corpus        # owner of the corpus-generation/identity extensions (regeneration-determinism + descriptor-collision fidelity checks)
  - 641-contamination-corpus-regeneration          # G3's regeneration precondition assumed the generator was already stable; this design is a precondition-fix for G3, not G3 itself
  - 623-reproducible-benchmark-release             # owner of the release/cohort projection this design extends (hardware-in-cohort, envelope consumption, coverage visibility)
  - 640-engine-performance-budget-latency-throughput-footprint  # sibling that already used the same "extend, don't replace" move for perf, and already flagged 625's trigger as fired a second time
  - 530-class-size-ratchet-automation               # origin of the discipline-gate kernel's baseline-shift-detection + changeset pattern this design ports as a convention into jseval
  - 553-canonical-search-execution-record           # the canonical-record + governed-projection template this design measures the Java/Python fork against, and judges disproportionate to build in full
---

> **CURRENT STATE (2026-07-01, thirteenth pass) — COMPLETE, NO REMAINING WORK.** Read this first, then
> §Thirteenth pass at the foot. The twelfth pass implemented everything the eleventh pass found ready; a
> conceptual-alignment re-read afterward found one real gap it left behind — regenerating
> `golden/needle-burial-v1` (twelfth pass, item 3) orphaned five exact-number findings in
> `docs/reference/search-quality-register.md` (F-023, F-024, F-025, D-004, Q-011) from the corpus content
> they cited, the tempdoc's own "verified binding, not a label" principle applied reflexively to its own
> work. Fixed with a dated provenance note, five cross-references, and a corpus-signature citation as a
> durable anchor — without re-running any cited experiment or retroactively bumping a validation date (either
> of which would have recreated the same problem). `baseline_ref` PR-scoping stays excluded (confirmed not
> applicable) and the "New UX" ideas remain untouched (still the user's call) — both correctly out of scope,
> not overlooked. Nothing remains open. Prior "CURRENT STATE" banners below are retained as dated history.
>
> **CURRENT STATE (2026-07-01, seventh pass) — ALL DESIGN ITEMS IMPLEMENTED.** Read this first, then
> §Seventh pass at the foot. The sixth pass's "IMPLEMENTED + CRITICALLY REVIEWED + FIXED" banner (still
> below, retained as dated history) was itself incomplete — a conceptual re-read against §Design found three
> named design items were never built, and investigating them surfaced a live regression in already-shipped
> code that a prior "live re-verification" had unknowingly bypassed. Everything is now fixed and verified
> through the real CLI paths, not just unit tests or manual function calls — see §Seventh pass for detail.
>
> **CURRENT STATE (2026-07-01, fifth pass) — IMPLEMENTED.** Read this first, then §Implemented (fifth pass)
> at the foot for what shipped and how it was verified. Everything below the banner (§Investigation through
> §Confidence-building pass) is retained as dated history showing how the design got here.
>
> **CURRENT STATE (2026-07-01, third pass) — DESIGN SETTLED + EXTERNALLY CHECKED.** Read this first. The
> investigation (§Investigation) and first theorization pass (§Theorization — first pass) below are retained
> as dated history. §Design / §Reach (second pass) are the settled conclusion: **no new governance kernel is
> warranted** — every provenance/governance-shaped finding resolves into an extension of an already-existing
> owner (635, 623/640, or the discipline-gate kernel's pattern ported into jseval); the qrel collision is a
> plain data-fidelity bug, addressed separately. §External research pass (third pass, foot of document) then
> checked the design against current (2026) published work in the two areas that are genuinely fast-moving —
> long-context/needle-in-haystack eval design and synthetic-benchmark construction QA — plus current MLOps
> baseline-governance practice. Result: one design item (needle position) was sharpened with a citable,
> concrete technique; two others (descriptor-collision detection; baseline-relaxation-requires-justification)
> were validated as already aligned with current practice, not changed. **General design only — no
> implementation, no effort estimate, no code changed in this pass.**

# 664 — Eval-corpus & ratchet-provenance integrity: verified identity for benchmarking/eval/test corpora

## The idea / what prompted this

The project's search-quality register and several tempdocs (635, 636, 625, 640) already establish that
*measurement outputs* — nDCG numbers, perf floors, recall-leak rates — need governed, verified provenance
rather than trusted labels. What had not been independently audited was whether the *inputs* to those
measurements — the corpora themselves, their relevance judgments (qrels), the code that loads them, the
code that generates the synthetic ones, and the Java-side test fixtures that make parallel relevance
claims — hold up to the same scrutiny. A four-pass read-only investigation (jseval's Python corpus
pipeline, Java test fixtures across the search/ranking modules, the ratchet/baseline governance layer, and
the synthetic `golden/needle-burial-v1` generator) looked for concrete code-level defects rather than
re-stating what the search-quality register already documents as known/settled.

## Investigation summary

Confirmed findings, most independently corroborated by more than one investigation pass and then verified
directly against source:

- **Non-deterministic corpus generator, contradicting its own docstring.** `scripts/jseval/jseval/corpus_generate.py:290`
  seeds its RNG with `seed + hash(axis) % 1000`. Python randomizes `str.__hash__` per process (PEP 456)
  unless `PYTHONHASHSEED` is pinned, which it is nowhere in this repo. The module's own docstring
  (`corpus_generate.py:19`) claims "Fully procedural + seeded → reproducible." Regenerating a corpus from
  the documented nominal seed does not reproduce it. In-process tests never see this because `hash()` is
  stable within one process — the bug is invisible to the exact test style used to check it.
- **A confirmed qrel mislabeling collision in the committed `golden/needle-burial-v1` corpus**, not merely a
  theoretical risk. Gold chains get a deterministic, cycled `(type, place)` descriptor pair
  (`corpus_generate.py:118-120`); distractors draw an *independent uniform random* pair from the same pool
  (`corpus_generate.py:122`, `rng.choice(types), rng.choice(places)`). The pool is 12 types × 26 places = 312
  combinations; 20 are reserved as gold. With `distractor_ratio=6` and 20 gold chains (`meta.json`), roughly
  120 distractors are drawn. Each has a ~20/312 ≈ 6.4% chance of exactly reproducing a gold descriptor pair;
  across ~120 independent draws the expected collision count is **≈ 7-8**, and the probability of at least
  one collision is **≈ 99.96%** (1 − (1 − 20/312)^120). This is not a rare edge case — it is close to
  guaranteed by the generator's own parameters. It is also observed: gold doc `mirmire21` and distractor
  `kanreach51` (`docs.jsonl:21,51`, a third occurrence at `:153`) share the identical `(vineyard, sunny
  valley)` descriptor; the paraphrase query for that descriptor semantically matches all three documents, but
  qrels mark only the gold pair relevant.
- **Buried-fact position is fixed at document start, not varied**, in every doc (`corpus_generate.py:132-136,
  179-207`) — the corpus measures "signal at depth 0," a narrower claim than "buried distinctive head"
  implies, and every doc pads with the same verbatim filler paragraph, which is itself a distinguishing
  signature a model could exploit without genuinely surviving dilution.
- **The qrels TSV parser mis-parses TREC-format files that also carry a header row**
  (`scripts/jseval/jseval/corpora.py:134`): the 4-column TREC branch only fires when there is no header;
  a headered 4-column file silently falls into the 3-column BEIR branch, misreading the doc-id column.
  Untested intersection (`tests/test_corpora.py:189-216` covers each case separately, never both together).
- **A structural fork between Java and Python "relevance eval."** `modules/system-tests/.../GoldenCorpusLoader.java`
  and `RelevanceMetrics.java` independently reimplement Recall@K / nDCG@K / MRR in Java against a separate
  10-doc/7-query hand-authored manifest, wholly disjoint from jseval's `datasets/golden/` +
  `corpora.py`/`scoring.py`. The Java-side assertions run against hand-placed "frozen" embedding vectors
  (`frozen-vectors.json`, `"model": "test-deterministic"`), not real model output — the test can stay green
  regardless of real embedding-model regressions.
- **Ratchet baseline re-pinning has no regression safeguard, and it has already been used to absorb one.**
  `perf-ratchet-baselines.v1.json` contains a comment admitting relevance floors were "re-baselined in the
  same recompose to the user-accepted post-636 levels (enron/courtlistener drops)." The recompose/re-pin
  commands (`jseval release`, `perf-gate --update-baseline`, `leak-gate-derive`) apply unconditionally, with
  no check that the run they're pinning from is itself free of regression.
- **Cohort identity for ratchet comparisons excludes hardware.** `release.py:120-140`'s cohort key hashes
  commit/model/policy but never GPU/driver/VRAM; hardware is recorded from only the first cohort member with
  no cross-member equality check.
- **Six registered corpora have zero gate coverage** (`cord19-qddf`, `desktop-mixed-v1`, and the four
  `ohr-bench-*` variants) — regressions there never fail any ratchet.
- **A per-dataset, not structural, fix for Windows case-sensitivity in qrel matching** — patched once, in
  `courtlistener-200`'s qrels file only; no normalization exists in the actual read/match path
  (`corpora.py:_read_qrels_tsv`, `retriever.py:_filename_to_doc_id`), leaving every other mixed-case-ID
  corpus exposed on the platform this repo actually runs on.

None of these were previously recorded in the search-quality register or in 635/641's own text — they are
new code-level findings, not re-statements of already-known issues (F-007's SciFact qrel-coverage gap and
similar are separate, already-documented issues and are not repeated here except where a new root cause was
found, e.g. the un-committed `tmp/build-mixed-corpus.py` builder script).

---

## Theorization — first pass (2026-07-01), retained as dated history

> Superseded by §Design below. Kept for the alternative framings it raised, several of which fed directly
> into the settled design (in particular Frame B and Frame D turned out to name exactly the two existing
> principles the design conforms to, and Frame E is explicitly rejected in §Design with reasons).

Everything in this section was exploratory. It is meant to widen the option space, not to converge on one.
Several directions are mutually exclusive; several are complementary; some turned out to be wrong once
looked at harder (noted inline where §Design corrects them).

### Ways to frame the underlying problem

**Frame A — four unrelated bugs.** The narrowest reading: patch each defect independently (stable hash,
fixed TSV branch, structural case-folding, a regression check before re-pin). This is cheap and defensible,
but it treats four instances of the *same shape* of failure — a claim of determinism, certification, or
control that turns out to be unverified — as coincidence rather than pattern. Given how cleanly the same
shape recurs across four independently-audited layers (generation, parsing, testing, governance), the
project's own standing rule against waiting for repeat incidents before treating something as structural
plausibly applies here — worth naming even though this tempdoc isn't the place to decide it.

**Frame B — "verified vs. asserted identity" as the load-bearing idea.** 635 already closed with exactly
this framing, handed to 625: *"a recorded identity is only trustworthy as a verified binding to the measured
artifact, not a label."* Read this way, every finding above is a fresh, concrete instance of that same
sentence. *(§Design confirms this frame for most — but not all — findings; see §Reach for which ones.)*

**Frame C — canonical-authority-vs-fork, applied to eval concepts instead of execution-trace concepts.**
The project already has a working answer to "two representations of the same thing will drift" for
search-execution data (`SearchTrace`, the `execution-surfaces` register). The Java/Python relevance-metric
fork is the same shape of problem in a different domain. Whether the fix looks like the execution-surfaces
pattern (a register + gate) or something lighter is open. *(§Design judges the full register pattern
disproportionate at this fork's current size and recommends disambiguation first.)*

**Frame D — ratchets as a trust system, not just a measurement.** A ratchet's entire value proposition is
"fails loudly on regression." A re-pin command with no safeguard is a laundering path that quietly converts
that promise into "fails loudly on regression, unless someone re-pins." *(§Design identifies this as
tempdoc 530's already-shipped baseline-shift-detection pattern, simply not yet adopted by jseval's Python
ratchets — not a new problem shape.)*

**Frame E — should the eval *harness itself* have a self-integrity ratchet, the way the engine has a
relevance ratchet?** *(§Design rejects a new unifying "meta-gate" — the extensions to existing owners already
cover every concrete finding; see §Design's closing subsection for why.)*

**Frame F — zoom out further: is the public-benchmark-shaped corpus/qrel paradigm even the right frame to
keep hardening, versus doubling down on the governed-synthetic direction 635 already started?** Left open;
not resolved by this pass either.

### Candidate solution directions, hidden assumptions, risks, and ideas worth keeping

(Retained in full from the first pass — omitted here for length; see git history / the original first-pass
content for the complete per-area candidate-direction lists, hidden-assumption list, and risk analysis. The
substance that survived independent verification is carried forward into §Design.)

---

## Design (long-term, settled) — second pass, 2026-07-01

This section answers the question the first pass deliberately left open: given everything found, **what
should actually be built, and where does it belong?** It follows the assignment: no short-term/tactical
fixes, scope matched to the problem the findings actually establish, and — the key move — an explicit check
of what already exists before proposing anything new.

### What already exists, and must not be replaced

Three pieces of existing machinery already implement most of what a "verified-identity" fix for this layer
would need:

1. **635's dual corpus-certification gate** (`scripts/jseval/jseval/corpus_certify.py`,
   `corpus_identity.py`). `corpus_certify.py` already returns a structured, extensible verdict with a named
   `fidelity` block (`memory_independence`, `retrieval_difficulty`) alongside `closed_book_certification` —
   i.e. it is already designed as a small family of named checks on a corpus, not a single pass/fail. Its
   docstring states the intent plainly: *"Verdict is derived, never hand-asserted."* `corpus_identity.py`
   already defines **the single** corpus-signature function (`sha256(corpus.jsonl + qrels/test.tsv)`),
   explicitly documented as shared by the run manifest, the corpus metadata, and the release's
   `corpus_source.sha256` — "conform, don't fork," in the file's own words. This is precisely 625's
   principle, already built for one layer (does the *materialized* corpus match its *recorded* identity).
2. **623/640's release/cohort/ratchet-provenance projection** (`scripts/jseval/jseval/release.py`,
   `ratchet_kernel.py`, the perf/relevance/leak gate family). 640 already executed the "extend, don't
   replace" move once — promoting perf into the canonical record's metric-family shape instead of building a
   parallel perf-measurement system — and its own frontmatter records that **625's deferred trigger ("the
   fork bites a second time") had already fired** by the time 640 was written. This tempdoc's findings are a
   further instance of that same trigger, not a new one.
3. **Tempdoc 530's discipline-gate kernel** (`scripts/governance/`, `governance/registry.v1.json`). Its
   **baseline-shift detection** already solves, for four other ratchets (`npm-audit`, `consumer-drift`,
   `ssot-catalog-sync`, `test-efficacy`), exactly the problem this audit found unsolved for jseval's ratchets:
   *"If the baseline file itself was relaxed in the PR... the gate fails... unless a classified changeset is
   present."* The kernel doc names its own origin bug as *"any commit could bump a pin without
   justification"* — the identical shape as the perf-ratchet-baseline comment this audit found.
4. **553/559's execution-surfaces register** (`governance/execution-surfaces.v1.json`) — the canonical-record
   + auto-scanned-projection template this codebase already uses when a concept genuinely spans many files
   across languages (SearchTrace: 26+ registered surfaces across Java/proto/TS/OTel/Python). Read in full for
   this pass; its scale is the yardstick against which the Java/Python relevance-eval fork is judged below.

### The settled design: three targeted extensions, one deliberately-deferred item, one plain bug fix

**1. Corpus generation/identity — extend 635, do not build a parallel corpus-integrity system.**
Two new named checks join the existing `fidelity` block, using the same "verdict is derived, never
hand-asserted" shape `corpus_certify.py` already establishes:
   - *Regeneration-determinism*: generate a corpus twice, in two separate subprocesses (closing the
     in-process blind spot that hid the current bug from tests), from the same declared seed, and diff the
     output. This turns "seeded → reproducible" from a docstring claim into a verified property — the same
     move `corpus_identity.py` already made for cache staleness (does the *materialized* corpus match its
     source), one step further upstream (does the *source* match what its own recorded seed claims to
     produce).
   - *Descriptor/qrel self-consistency*: before a corpus is certified, check whether any two chains (gold or
     distractor) that are supposed to be distinguishable in fact carry colliding relevance-bearing
     descriptors, and fail certification if so. This is a natural third axis alongside
     `memory_independence`/`retrieval_difficulty`, not a new gate command. *(§External research pass sizes
     this concretely: exact-match collision checking is sufficient for the current templated generator;
     embedding-based near-duplicate detection is the standard escalation if generation becomes less
     templated — see below.)*
   - *(Added after §External research pass, third pass)* **Needle-position variation**: the fixed-depth
     placement noted in §Investigation is a real, citable gap, not a nitpick — vary placement across a
     bounded range per the technique below, rather than a fixed or fully unbounded position.
   Both/all are extensions to an existing, already-extensible verdict shape — no new corpus-quality subsystem.

**2. Ratchet/release provenance — extend `release.py`/`ratchet_kernel.py`, do not build a fourth gate kernel.**
   - Fold a hardware fingerprint into the existing cohort-identity hash (`config_cohort_key`), consistent
     with how it already treats commit/config/model fingerprints — this closes the gap without new
     architecture, since the hashing mechanism to extend already exists.
   - Wire the already-computed `±2σ` tolerance band (`release.py`'s `_tolerance_band`) into
     `relevance_gate.py`'s comparison in place of its current flat absolute default — the noise-aware data
     already exists and is already computed; this is a consumption fix, not new measurement.
   - Make gate *coverage* (which registered corpora a given gate actually watches) a visible, queryable
     property of the release/corpus-catalog relationship, rather than something each gate silently
     hand-picks. Whether every registered corpus *should* be gated is a policy call left open (see §Design's
     closing note); making today's silent gap visible is the design-level requirement.

**3. Baseline-relaxation justification — port the *convention*, not the *mechanism*, into jseval.**
   Any operation that lowers a pinned floor (`perf-gate --update-baseline`, `leak-gate-derive`, a `jseval
   release` recompose that lowers a previously-pinned value) should require a small, classified, dated
   justification artifact, checked against the git diff versus the baseline ref — the same shape as the
   discipline-gate kernel's `.changesets/<gate>/*.md` frontmatter convention, applied natively inside
   jseval's own Python commands rather than by routing jseval through `scripts/governance/` (the JS kernel).
   This deliberately does **not** pull jseval's ratchets into the discipline-gate kernel itself — see the
   next subsection for why that boundary is intentional, not an oversight to fix.

**4. Java/Python relevance-eval fork — disambiguate first; a register is not yet warranted.**
   Measured against the execution-surfaces register's actual scale (26+ surfaces across five languages,
   auto-scanned because manual tracking at that size would fail silently), the Java/Python fork is two code
   clusters, not a system-spanning concept. Building equivalent machinery for two clusters is structure the
   problem, as currently sized, does not require. The design-scoped move is **disambiguation**: rescope the
   Java "Golden Corpus" suite's naming and docs to state plainly what it actually verifies — fusion-mechanics
   correctness given a fixed, hand-placed vector, not retrieval quality — removing the implicit claim that it
   measures the same thing jseval measures. This resolves the "two authorities" risk (someone trusting the
   Java suite as evidence of real relevance quality) without new governance machinery. If a genuine future
   need for Java-side retrieval-quality coverage arises (not established by this audit), the lighter
   escalation — both sides consuming the same `datasets/golden/<name>/` source files, without necessarily
   sharing metric-computation code — is the first thing to reach for before a full register.

**Deliberately excluded — plain bugs, not design questions:**
The qrels TSV header/format-branch parsing bug and the Windows case-folding bug are ordinary correctness
defects with a clear, narrow fix and an obvious regression test each. They don't raise an architecture
question and are left for an implementation pass, not because they're unimportant but because there is
nothing to design.

### Why federated extension, not one new kernel

625 explicitly deferred choosing between "the discipline-gate kernel" and "a projection step in 623" as
generalized-enforcement's home, pending a second real trigger — and separately recorded that as of tempdoc
640, that trigger had already fired once. This design closes that open question by actually investigating
the candidates: the honest answer is **neither, exclusively**. The three domains above (corpus generation,
release/ratchet provenance, baseline-change discipline) already have distinct, appropriate owners with
genuinely different runtime constraints:

- 635's corpus certification runs with **no dev stack** (a `claude` CLI closed-book pass).
- 623/640's release/ratchet gates require a **live backend + GPU + a full eval run** — expensive, and why
  they are advisory (`search-engine-hint` hook nudge), not CI-blocking, by deliberate design.
- The discipline-gate kernel's gates are **fast, CI-blocking, GPU-free** checks wired into the public hosted
  CI lane.

Routing jseval's live-stack-dependent checks through the JS kernel would force a GPU/backend requirement into
a CI lane structurally built to avoid it; routing the fast-CI gates through jseval's slower Python runtime
would weaken guarantees that currently run on every PR. A single shared kernel would trade a real, already
deliberate architectural boundary for a smaller number of files — the wrong trade at this scope. Conforming
to the *principle* while respecting the *existing runtime boundary* is the correct match: each domain keeps
its own owner and adopts the specific check its own domain was missing.

---

## Reach

### Is this design an instance of a principle or seam that already exists elsewhere? Yes — two, distinctly.

1. **625's "verified binding, not a label" / projection-vs-fork principle** (553/559/622/623/640 lineage)
   covers the corpus regeneration-determinism gap, the hardware-cohort gap, the unconsumed tolerance
   envelope, and the Java/Python relevance-metric fork. This design conforms to it by extending 635's/623's/
   640's existing machinery, not by building a parallel verification layer.
2. **Tempdoc 530's baseline-shift-detection / changeset-justification pattern** (already shipping for
   `npm-audit`, `consumer-drift`, `ssot-catalog-sync`, `test-efficacy`) covers the ratchet re-pin laundering
   finding. This design conforms to it by porting the *convention* — not the literal JS mechanism — into
   jseval's own Python runtime, respecting the fast-CI/live-stack boundary that put jseval's ratchets outside
   the discipline-gate kernel's membership in the first place.

Neither finding required inventing a new seam. Both are cases of an already-generalized principle being
under-applied in a layer (eval-corpus code) that had not previously been audited against it.

### What is *not* an instance of an existing principle

The qrel mislabeling collision (the gold/distractor descriptor collision) is a plain data-fidelity defect,
not a provenance or governance question — nothing was mislabeled as verified when it wasn't; the generator
simply produced a wrong label. It's addressed as a new *check* inside 635's existing fidelity-gate shape, not
as an instance of either principle above. Worth stating plainly so neither principle gets stretched to
explain something it doesn't actually cover.

### A candidate narrower corollary worth naming (recognized, not built)

**"A claimed seed does not make a process reproducible; only a diff-tested regeneration does."** This is a
specific, recurring failure shape distinct from (though related to) 625's general principle: any code that
seeds a pseudo-random process and asserts reproducibility without ever actually running that process twice
and diffing is exposed to hidden non-determinism sources — here, Python's per-process `hash()` randomization;
elsewhere it could be dict/set iteration order, floating-point summation order that varies with thread count,
or an unpinned library default. This audit found one confirmed instance (`corpus_generate.py`). Whether any
other generator in this codebase that claims reproducibility (candidates not checked in this pass: the GPL
synthetic training-query generator referenced in the search-quality register's F-021, or the eval
calibration/envelope sampling machinery) shares the same defect is an open, **unverified** question — not a
finding. Per the same discipline 625 and 640 already modeled in their own REACH sections, this corollary is
recorded here as a named, scoped candidate; no general "does every seeded generator have a round-trip test"
enforcement is proposed or built.

### Where the two existing principles would extend, and whether anything already violates them there

- 625's own candidate-scope list (register table, ratchet baselines, the agent-utility number, business-doc
  citations, a tempdoc's own current-state) does not yet name **parallel test-fixture implementations of a
  measurement concept**. This audit's Java/Python finding is a concretely verified instance of that shape,
  worth folding into 625's candidate-scope list the next time that tempdoc is touched.
- Tempdoc 530's baseline-shift-detection pattern was scoped, when written, to the four ratchets already
  living inside the discipline-gate kernel. This audit is the first evidence that a ratchet family *outside*
  that kernel's membership (jseval's Python relevance/perf/leak/corpus gates) has the identical unguarded-
  relaxation exposure. Worth recording in the discipline-gate-kernel doc as a confirmed instance beyond the
  kernel's current membership — even though, per §Design, the fix stays local to jseval rather than pulling
  jseval into the kernel itself.

### What remains deliberately undecided

No code has changed. No effort has been estimated. Whether the three extensions in §Design land as one
implementation pass or three separate ones is unresolved. Whether any of the six currently-ungated corpora
should gain mandatory (not just visible) gate coverage is a policy call, not a design conclusion, and is left
open. Whether 625 itself should be marked "settled" by this tempdoc or remain its own stub citing this as a
concrete instance is left to whoever next reconciles the two documents.

## External research pass (2026-07-01, third pass)

Before treating §Design as final, three areas were checked against current (2025-2026) published work,
chosen because they are genuinely fast-moving external fields the design touches — not because everything in
the design needed checking. **Not researched, deliberately:** Python's `hash()` per-process randomization
(a stable, well-documented CPython language fact since PEP 456 — not an active research question), the
discipline-gate kernel's changeset pattern (purely internal to this repo), and the two plain parsing/
case-folding bugs (no research question attached to a bug fix).

All citations below are paraphrased summaries with a link, in the same style already used throughout this
codebase's tempdocs (e.g. the search-quality register's F-009/F-019 arXiv citations) — no external text or
code was copied into this repository. This repo is public and carries a license/notices CI check; nothing in
this pass adds a dependency, an asset, or a code excerpt, only citations.

**1. Needle-in-haystack / buried-signal eval design — needle position, and low-lexical-overlap construction.**
   - [NoLiMa](https://arxiv.org/html/2502.05167v2) (2025) confirms the codebase's existing "low lexical
     overlap via associative keyword bridging" choice (`corpus_generate.py`'s synonym-bridge descriptors) is
     already aligned with current practice — no change indicated there. It also documents a concrete,
     citable placement convention worth adopting: needles restricted to the 20%-80% range of the document/
     context (avoiding edge effects), with a minimum separation of ~20% of the content length from
     distractor-dense regions.
   - [Semantic Needles in Document Haystacks](https://arxiv.org/html/2604.18835v1) (2026) independently
     confirms positional bias is real and significant even in *short* documents (4-8 sentences), and is
     model-dependent in direction (some models penalize early differences more, at least one recent model
     shows the reverse) — reinforcing that §Investigation's "fixed at document start" finding is a genuine
     methodological gap, not a nitpick: a fixed-position corpus cannot distinguish "the pipeline handles
     buried signal" from "the pipeline handles signal specifically at position zero."
   - **Design outcome:** sharpens (does not replace) the existing design-item — vary buried-fact placement
     across a bounded range (informed by NoLiMa's 20-80% convention) rather than either a fixed position or
     fully unbounded randomization, and treat position as a controlled/reported variable of the corpus rather
     than an incidental one. Still a design note, not implementation — matching NoLiMa's *general shape*, not
     copying its code or exact parameters (JustSearch's corpus is doc-level, not single-long-context-window,
     so the "20-80% of context length" convention is a starting point to adapt, not a value to import as-is).
   - **Negative/validating finding, stated plainly:** [Semantic Needles in Document Haystacks](https://arxiv.org/html/2604.18835v1)
     explicitly does *not* address collision-prevention between needle and distractor content — its random-hay
     construction has no dedup/collision-avoidance mechanism at all. This means the descriptor-collision defect
     found in this audit is not "a solved problem the JustSearch generator missed" — it appears to be a gap
     even in closely-adjacent, recently published needle-benchmark work. The fidelity-gate extension proposed
     in §Design is a genuine (if small) contribution to an under-addressed construction-QA gap in the field
     generally, not a catch-up to an existing standard. Worth stating plainly rather than either overclaiming
     novelty or underclaiming the design's value.

**2. Synthetic/contamination-resistant IR benchmark construction QA.**
   - Search results confirm embedding-based near-duplicate detection (sentence-embedding similarity, e.g.
     SimCSE-family models) combined with MinHash-LSH fuzzy clustering is the standard toolkit for benchmark
     construction QA/dedup, and that "detect near-duplicate ground-truth pairs, then drop/flag one" is already
     a named step in published benchmark-construction pipelines — validating the *shape* of the
     descriptor/qrel self-consistency check in §Design as an instance of known practice, not a novel
     invention.
   - **Design outcome — sizing, not a new mechanism:** because `corpus_generate.py`'s content is procedurally
     templated (a fixed catalog of `(type, place)` pairs), exact-match collision checking is sufficient today
     and cheaper than embedding similarity. If the generator is later extended to produce less-templated
     content (a real possibility given 641/G3's deferred "new entities, new scales, new type-axes" goal),
     embedding-based near-duplicate detection is the documented escalation path — and this system already
     runs an in-house multilingual embedder (`gte-multilingual-base`, already used for dense retrieval) that
     could serve this without adding a new dependency. This is sizing guidance for whoever implements, not a
     commitment to build the embedding-based path now — the present problem (a templated generator with exact
     collisions) only requires the exact-match check.

**3. MLOps-style baseline/data governance.**
   - Current practice (2025-2026 sources on MLOps governance) confirms "policy as code" gates that block
     silent baseline/threshold relaxation, tied to CI, with automated checks before a model/dataset "advances"
     — the same shape as the discipline-gate kernel's changeset-justification pattern already used internally.
   - **Design outcome:** validates, does not change, §Design item 3 (porting the changeset-justification
     *convention* into jseval's ratchet re-pin commands). No externally-discovered pattern surfaced that is
     meaningfully different from what the codebase already has internally — this pass found confirmation, not
     a better alternative.

**Net effect of the research pass:** one design item sharpened with a specific, citable technique (needle
position bounding); one item's sizing clarified (exact-match now, embedding-based dedup as a named future
escalation, reusing an in-house model rather than adding a dependency); one item validated with no change.
No new dependency, asset, or external code was introduced by this pass — only citations.

## Confidence-building pass (2026-07-01, fourth pass — investigation + experiments, no implementation)

Before implementation, the shakiest assumptions in §Design were converted from static reading into either
confirmed facts (via actual code execution) or corrected/refined claims. **No tracked file was modified; no
committed corpus was regenerated in place.** Everything below ran against a scratch directory or was a
read-only Bash/Python/Grep check.

**Environment & baseline:** `scripts/jseval` runs locally (Python 3.14.4); the existing corpus-related test
suite (`test_corpora.py`, `test_corpus_governance.py`) is green (41 passed) — a real baseline to compare
against once fixes land.

**1. Determinism bug — now empirically confirmed, and worse than the static estimate.** Regenerating
`golden/needle-burial-v1` twice from the documented `seed=636` (two separate `python` process invocations,
identical params) produced **280/280 documents different** — not a subtle drift, the entire corpus content
(entity names, all descriptor assignments) changes between "identical" runs. Re-running with `PYTHONHASHSEED`
pinned identically across both invocations produced a **byte-identical** corpus (0 diff lines) — confirming
the fix direction from §Design works and is sufficient on its own for this specific bug. This is now a
verified fact, not a citation of PEP 456.

**2. Descriptor-collision defect — measured, and the real severity differs from the estimate.** Running an
exact-title-match collision detector against the *already-committed* `docs.jsonl` found **24 colliding
descriptor groups spanning 51 of the 280 documents (18%)** — of which **7 involve a gold chain (35% of the 20
gold chains have at least one qrel-corrupting collision)**, and **17 are distractor-only duplicates** (two
"different" hard negatives sharing identical text — a real corpus-diversity defect, but not a qrel-label
corruption; §Investigation did not previously name this class). The measured gold-collision count (7) lines
up closely with §Investigation's probabilistic estimate (~7-8 expected) — that estimate was accurate for the
severity that matters. The total collision count (24/51), and the distractor-only class specifically, is new
information this pass surfaced, and both should be folded into any future revision of §Investigation.
**Correction:** §Investigation's claim of "a third occurrence at `docs.jsonl:153`" for the
`mirmire21`/`kanreach51` collision does **not** reproduce under this direct re-check — line 153 is a different
descriptor (`vexgrove79`/`pellgrove153`, "old courthouse," not "sunny valley"). That specific sub-claim from
the first-pass audit appears to have been a grep false-positive from the originating subagent and should not
be repeated as fact.

**3. Cohort-key hardware fix — §Design item 2a needs revision after reading `release.py` in full.** The
existing `config_cohort_key()` (`release.py:120-140`) **deliberately excludes** GPU/hardware execution flags
from model identity (`_MODEL_EXECUTION_FLAGS`, `release.py:63`) — the code comment explains this is
intentional: nDCG-style relevance comparisons shouldn't be split into separate cohorts just because two runs
used different hardware. Folding hardware into *that* key, as §Design originally proposed, would be the wrong
fix — it would over-partition cohorts for the relevance ratchet, which correctly doesn't care about hardware.
The real gap is narrower and confirmed at an exact line: `compose()` (`release.py:329`) sets
`"hardware": _hardware_projection(ref_manifest)` from only the *first* cohort member, with no equality check
across the rest. **Revised design**: hardware identity needs its own check, scoped to where it actually
matters (the perf-gate's own cohort composition, since latency/throughput *do* depend on hardware) — not a
change to the general-purpose `config_cohort_key` the relevance ratchet also relies on. This is a real,
confidence-relevant correction, not a restatement.

**4. Fidelity-gate extension point — confirmed and more precise than assumed.** `scripts/jseval/jseval/commands/corpus.py`
confirms three already-existing, cleanly-separated corpus gates: `corpus-build` (materialize + sign),
`corpus-certify` (no-stack, closed-book memory check), and `corpus-fidelity` (stack-bound, retrieval-difficulty
+ **an existing `shortcut_leak_rate` check** — the established sibling-metric pattern a new
descriptor-collision check would extend). Both `corpus-certify` and `corpus-fidelity` already share a
careful merge-don't-clobber discipline when writing to `metadata.json`'s `fidelity` block
(`corpus.py:67-77,176-181`) — any new check must follow that same pattern, which is now a concrete,
already-proven template rather than an assumption. **Refinement**: since descriptor-collision detection needs
only `docs.jsonl`/`queries.json` content (no retrieval, no live backend — confirmed by this pass's own
detector script), it does not need to live in the stack-bound `corpus-fidelity` gate at all; it fits more
naturally as a no-stack check alongside `corpus-certify`, or even earlier at `corpus-build` time — cheaper
than §Design assumed.

**5. Baseline-shift-detection port into Python — risk downgraded after reading the actual source.**
`scripts/governance/lib/git-utils.mjs` (164 lines) and `changeset-loader.mjs` (183 lines) are small,
mechanical wrappers around `execFileSync('git', [...])` calls (`diff`, `show`, `tag`, `ls-files`) plus a
directory scan and frontmatter parse — no complex logic, no Node-specific dependency without an obvious
Python equivalent (`subprocess.run(['git', ...])` mirrors `execFileSync` directly). This downgrades
§Design item 3 from "medium-high risk, unknown effort" to "low-medium effort, a mechanical port" — a
meaningful, checked reduction in uncertainty.

**6. Java disambiguation — slightly larger footprint than scoped.** Grepping the full repo for
`GoldenCorpusLoader`/`GoldenCorpusTest`/`GoldenCorpusIntegrationTest`/`RelevanceMetrics` found **four** Java
test files with direct references (`GoldenCorpusTest.java`, `GoldenCorpusIntegrationTest.java`,
`RelevanceMetricsTest.java`, and — previously unnoted — `RagQualityEvalTest.java` and
`PassageRetrievalIntegrationTest.java` also appear in the match set and need checking for whether they
depend on the same classes or merely share a package). Still low technical risk, but a rename/rescope needs
to touch more call sites than §Design's "two files" framing implied.

**7. Plain-bug fixes — confirmed contained.** `_read_qrels_tsv` and `_filename_to_doc_id` each have exactly
their own test file plus one production caller inside `scripts/jseval` — no hidden fragile dependents found.

### Confidence rating and implementation sizing (post-investigation)

**Confidence in the remaining implementation work: 7/10.** Every design item now has either direct empirical
confirmation (determinism, collision detection — both demonstrated against real data, not just reasoned
about) or a concretely-read extension point (`corpus.py`'s three-gate pattern, `git-utils.mjs`/
`changeset-loader.mjs`'s small size). The two points knocked off a higher score: (a) the cohort-key finding
shows the *first* design pass had at least one real correctness error (fixing it the way originally scoped
would have broken the relevance ratchet's cross-hardware comparability) — a reminder that a "settled design"
without execution is still provisional; (b) `corpus_fidelity.py` and the perf-gate's own cohort-composition
code were not read in this pass (time-bounded), so the hardware-fix's *exact* new shape is still directional,
not fully specified.

**Per-item difficulty, revised:**
- Determinism fix (pin the seed correctly) — **trivial**, now proven sufficient by direct experiment.
- Descriptor-collision check — **small**, no-stack, a natural extension of an already-read, already-patterned
  gate command.
- Hardware-in-cohort fix — **small-to-medium**, now correctly scoped (not the general cohort key), but the
  exact perf-gate composition path still needs a read before coding.
- Baseline-shift-detection Python port — **small-to-medium**, mechanical, now de-risked by reading the actual
  JS source.
- Java disambiguation — **trivial-to-small**, slightly wider footprint (4 files, not 2) but still a rename/
  rescope, not new logic.
- Two plain-bug fixes — **trivial**, confirmed no hidden dependents.

None of the items are individually hard; the difficulty is **coordination breadth** (five small-to-medium
changes across two languages and several modules that must each conform to an existing pattern rather than
invent one) plus **precision under an already-corrected design** (the hardware finding shows a first draft
can look right and still be subtly wrong until read against the real code).

## Open questions (updated 2026-07-01)

- ~~Does 625's principle get one system-wide mechanism, or local fixes per layer?~~ **Resolved by §Design:**
  federated extension of existing owners, matching each domain's runtime constraints — not one mechanism.
- ~~Is the Java/Python relevance-eval fork a defect to close, or a legitimate two-tier design?~~ **Partially
  resolved:** disambiguation (stop implying equivalence) is the design-scoped move now; a shared-data,
  independent-code middle tier is the next escalation if a real need for Java-side quality coverage emerges;
  a full register is judged disproportionate at the fork's current size.
- ~~Should corpus/qrel integrity get its own standing ratchet?~~ **Resolved by §Design:** no — extend 635's
  existing dual-gate with two new named checks instead of building a parallel system.
- **Still open:** is 20/312 descriptor-pool coverage (with a 6× distractor ratio) an isolated parameter
  choice, or does the same collision risk recur at other pool-size/distractor-ratio combinations the
  generator might be asked to scale to (relevant if/when 641's regeneration capability is eventually built)?
  This is an empirical question about the generator's parameter space, not resolved by this design pass.
- **New:** does the "claimed seed ≠ verified reproducibility" corollary (see §Reach) apply to any other
  generator in the codebase that asserts reproducibility? Not checked in this pass.
- **New:** should 625 be updated with this tempdoc's two concrete new instances (the test-fixture fork; a
  ratchet family outside the discipline-gate kernel with the same unguarded-relaxation exposure), or does
  this tempdoc stand alone as the citation? Left to whoever picks this up.
- **New (from §External research pass):** NoLiMa's 20-80%-of-context-length placement convention was
  measured on single-long-context-window needle tests, not JustSearch's per-document corpus shape — the
  right bounded-placement parameters for `corpus_generate.py`'s documents (which are much shorter than a
  long-context window) need their own calibration, not a direct import of NoLiMa's numbers. Left for
  implementation, not resolved here.

## Implemented (fifth pass, 2026-07-01)

Everything §Design scoped as implementation (not the deliberately-deferred items) shipped in worktree
`tempdoc-664-eval-corpus-integrity` (branch `worktree-tempdoc-664-eval-corpus-integrity`). Each item has a
regression test that would have caught the original defect; `python -m pytest` (1088 passed, 2 pre-existing
unrelated failures deselected) and `./gradlew.bat build -x test` (repo-wide) are both green.

1. **Plain bugs** (`scripts/jseval/jseval/corpora.py`, `retriever.py`) — the TSV header/format branch now
   keys on column count alone; doc-ids are lowercased at both mint sites structurally. New tests:
   `test_read_qrels_tsv_trec_format_with_header`, `test_read_qrels_tsv_normalizes_doc_id_case`,
   `TestFilenameToDocId.test_case_normalized`.
2. **Corpus-generator determinism** (`corpus_generate.py:290`) — `hash(axis)` replaced with a SHA-256-based
   stable digest. New test `test_generate_is_deterministic_across_processes` spawns `generate()` in two
   separate `python` subprocesses and diffs the output — the exact experiment that found the bug, now a
   permanent guard. Manually re-verified against the real `needle-burial-v1` params (unpinned, no
   `PYTHONHASHSEED`): byte-identical across two runs, confirming the fix without relying on the env
   workaround.
3. **Descriptor-collision check** — new `corpus_certify.descriptor_collision_report(docs, queries)`, wired
   into `cmd_corpus_certify` via the existing merge-don't-clobber `fidelity` pattern. Run against the real
   committed `golden/needle-burial-v1` source: reproduces the confidence-building pass's exact measured
   numbers (24 groups / 51 docs / 7 gold-involved / `passed: False`) through the actual production code
   path, not a scratch script. Uses each query's `evidence_ids` as gold-membership ground truth — more
   robust than the line-number heuristic the earlier manual check used.
4. **Hardware-homogeneity check** — `release.py:compose()` gained an additive `cohort["hardware_homogeneous"]`
   field (did **not** touch `config_cohort_key`, correctly guarded by the pre-existing
   `test_config_key_ignores_gpu_execution_flags`); `perf_gate.py:project_release_to_perf_baselines` refuses
   to project when it's explicitly `False`, permissive when absent (backward compatible with releases
   composed before this change).
5. **Baseline-shift-detection** — new `scripts/jseval/jseval/baseline_shift.py` (git-subprocess + YAML
   frontmatter, mirroring the existing `manifest.py`/`run_config.py` idioms, not the JS kernel itself), wired
   into `perf-gate --update-baseline`, `leak-gate-derive`, and `jseval release`'s recompose. A per-metric
   relaxation without a classified, justified changeset now raises. New `.changesets/README.md` documents
   the convention; `test_baseline_shift.py` covers load/justify/reject paths.
6. **Java disambiguation** — `GoldenCorpusLoader` renamed to `ManifestCorpusLoader` (`git mv`, preserving
   history) with Javadoc explaining it is generic infrastructure shared by three unrelated manifests, not
   scoped to one "golden corpus." `RelevanceMetrics`, `GoldenCorpusTest`, `GoldenCorpusIntegrationTest`,
   `RagQualityEvalTest`, and the module README all gained a one-clause caveat: these suites measure
   fusion/ranking-code correctness against hand-placed vectors, not real embedding-model retrieval quality.

**Verification detail, including two false leads correctly ruled out:** a filtered `--tests` run surfaced a
`ConfigStore not initialized` failure in `GoldenCorpusIntegrationTest` and, on the full suite, 6 unrelated
E2E test classes failed with "Backend failed to become ready." Both were reproduced identically against
unmodified `main` / traced to a worktree never run through `prepare-worktree.cjs` — confirmed pre-existing
and environmental, not regressions, before being logged to the observations inbox rather than "fixed." Two
more pre-existing, unrelated issues (a `test_correction_probe.py` resource-loading failure; an off-by-one in
`cmd_perf_gate`'s default `--baselines` path computation, discovered while wiring baseline_shift.py) were
similarly logged, not fixed, per scope discipline. No UI/browser verification — nothing under
`modules/ui-web/` was touched.

**Deliberately not built** (per §Design's own scope boundary, still correct): mandatory gate coverage for
the six previously-ungated corpora (a policy call, not implemented here — only the coverage *gap* was
already visible before this pass); a full execution-surfaces-style register for the Java/Python fork
(judged disproportionate at this fork's size; disambiguation was the scope-matched move); bounded
needle-position variation in `corpus_generate.py` (the External Research pass's citable refinement,
correctly left as a follow-up rather than implemented speculatively in this pass).

## Critical review + fixes (sixth pass, 2026-07-01)

The fifth-pass implementation was reviewed critically — direct re-verification against the actual diff plus
an independent adversarial reviewer agent (fresh context, no exposure to the implementer's own summary,
explicitly told to distrust it) — against tempdoc 664's own design intent. No security/privacy issues found.
Five real, substantive issues surfaced, all confirmed with `file:line` evidence before being accepted as
findings, and all fixed:

1. **`commands/release.py`'s baseline-shift guard missed `run_metrics`.** It only checked the per-mode
   `metrics` family (nDCG@10, ce_p50_ms); `run_metrics` (`primary_docs_s`, `enrich_docs_s`, `resident_bytes`)
   — a sibling family `perf_gate.py`'s own floor projection explicitly reads — was never guarded. 3 of 4
   perf-relevant metrics could regress through a release recompose with zero protection. **Fixed**: a second
   loop over `run_metrics` inside the same guard, same composite-key convention. New tests exercise refusal,
   justified-acceptance, and improvement-without-changeset via a real `CliRunner` invocation of `cmd_release`
   (not just the underlying function).
2. **The new descriptor-collision verdict was never surfaced.** `corpora.py:_validate_golden_set` already
   warns on failed/missing `closed_book_certification` and `fidelity.passed` — two precedented, symmetric
   branches — but had no third branch for `fidelity.descriptor_collisions.passed`. The check computed and
   persisted correctly but was invisible in normal `jseval run`/corpus-load usage. Confirmed live: the real,
   shipping `needle-burial-v1` corpus fails this check and produced zero warning before the fix. **Fixed**: a
   third branch mirroring the existing two exactly; live-reverified afterward — materializing the real
   committed corpus, running the real `descriptor_collision_report`, and loading it through the real
   `_load_local` production path now correctly emits "FAILED the descriptor-collision check (7 gold
   chain(s)... )".
3. **A pre-existing off-by-one (`parents[1]` vs `parents[2]`) specifically neutered two of this tempdoc's own
   new guards.** Previously logged as a narrow, out-of-scope pre-existing bug for `cmd_perf_gate`'s default
   `--baselines` path; the review found the identical bug at 4 more sites in the same file (`gates.py`) —
   `relevance-gate`, `leak-gate`, `leak-gate-derive`, `llm-gate` all shared it. For `leak-gate-derive`
   specifically, it made this tempdoc's own new baseline-shift guard a structural no-op under the documented
   default invocation (the guard always read `old_baselines = {}` from the wrong path). **Fixed**: corrected
   uniformly across all 5 occurrences in `gates.py` (leaving 2 fixed and 3 identical siblings broken would
   have been an inconsistent partial fix once the pattern was understood) — a source-level regression test
   asserts no `parents[N != 2]` pattern can silently reappear in that file.
4. **The disambiguation Javadoc itself overstated what it disambiguates.** `ManifestCorpusLoader`'s new
   Javadoc claimed it was "shared infrastructure loaded by three unrelated test manifests," but
   `RagQualityEvalTest` parses its own manifest directly and never calls this loader — the loader handles
   two manifests; `RelevanceMetrics` (not the loader) is the piece actually shared with `RagQualityEvalTest`.
   Ironic given the fifth pass's entire purpose was correcting an overclaim. **Fixed**: reworded the Javadoc
   and the module README's key-classes table entry for accuracy.
5. **`baseline_shift.py` overclaimed replay protection it doesn't enforce.** The module builds real PR-scope
   anti-replay machinery (`git_diff_added_modified`, mirroring the JS kernel), but none of the three real
   call sites pass a `baseline_ref` — so a single changeset currently justifies every future relaxation of
   its declared `(gate, dataset)`, not just the one it was written for. Building real git-ref scoping needs a
   "since when" anchor that doesn't naturally exist for a locally-run, non-CI-scoped tool — judged speculative
   structure the current problem doesn't clearly need, not a targeted bug fix. **Fixed (docs-only, no new
   mechanism, matching that scope judgment)**: corrected the module docstring, `git_diff_added_modified`'s
   docstring, and `.changesets/README.md` to state plainly what's actually enforced today.

**Verification**: full jseval suite green (1096 passed, up from 1088 — 8 new tests across the 5 fixes; the 2
pre-existing unrelated failures remain correctly deselected), repo-wide `./gradlew build -x test` green,
`spotlessApply` + `:modules:system-tests:compileJava` clean for the Javadoc fix. Finding #2 was additionally
live-reverified against real data, not just its test — the same discipline used throughout this tempdoc's
prior passes.

## Seventh pass (2026-07-01) — the sixth pass's own "IMPLEMENTED" claim was incomplete

A conceptual re-read of the full tempdoc (focused on §Design's actual named outcomes, not the sixth pass's
own summary of itself) found three concrete design items were never built, despite the sixth pass's banner
claiming "ALL items from §Design shipped." Investigating them surfaced a fourth, more serious problem: a
live regression in already-shipped code that a prior "live re-verification" had unknowingly bypassed. All
four are now fixed and verified through the real CLI paths.

### 0. [Newly found, highest priority] `cmd_corpus_certify` looked for a file that never exists

`commands/corpus.py` checked for `dataset_dir / "docs.jsonl"`, but `corpus_build.build_golden()` only ever
writes `corpus.jsonl` — confirmed live via a `CliRunner` invocation against a real materialized corpus:
`descriptor_collisions` was never computed in production. The sixth pass's own "live re-verification" of
that exact fix had called `descriptor_collision_report()` directly (bypassing the CLI's file lookup) rather
than exercising `cmd_corpus_certify` end-to-end — a textbook case of a verification step that looked
rigorous but tested around the bug rather than through it. Fixed (`corpus.jsonl`, matching what a sibling
command in the same file already reads correctly); a new `CliRunner`-level regression test exercises the
real path this time; re-verified live against `needle-burial-v1` through the actual CLI — correctly reports
24 groups / 51 docs / 7 gold-involved, matching every prior measurement, but now via the code path that
actually runs in production.

### 1. Regeneration-determinism as a certification-time check (§Design item 1, never built)

`generation_provenance` was missing `n_chains`/`doc_words` — confirmed against the real `needle-burial-v1`
metadata — so a corpus's own recorded provenance couldn't reconstruct the exact `generate()` call needed to
verify it. Fixed the write (`corpus_generate.py`); backfilled the one committed corpus (`needle-burial-v1`)
whose exact parameters could be *verified* by actually regenerating and diffing against the committed
`docs.jsonl` — not assumed from defaults. The other 4 procedurally-generated `635-corpora/*` sources could
**not** be reproduced even after a systematic search across plausible `n_chains`/`doc_words` values,
suggesting the generator's rendering code has drifted since they were originally committed — an honest,
recorded finding, not forced. New `corpus_certify.regeneration_determinism_report()` spawns `generate()`
twice in separate subprocesses (closing the same in-process blind spot the original bug hid behind) and
diffs the output; gracefully skips (`passed: None`) hand-authored or incomplete-provenance corpora rather
than false-failing them — confirmed live for both a skip case (`synth-code-v1`, missing provenance) and a
pass case (`needle-burial-v1`) through the real CLI. `_validate_golden_set` gained a fourth, symmetric
warning branch — a documented skip stays silent, distinct from "never run" and "failed."

### 2. Tolerance-band wiring into `relevance_gate.py` (§Design item 2b, never touched at all)

`git diff main -- relevance_gate.py` showed zero changes anywhere before this pass. Investigation found
`evaluate()` has no `manifest` parameter, ruling out mirroring `perf_gate.py`'s per-run-manifest-derived
envelope pattern directly — but `release.py` already computes and stores `tolerance_band` on each
`measured[ds]` entry at compose time, so the fix is narrower: carry that already-computed value through
`project_release_to_baselines` as `tolerance_band_abs`, and prefer it in `evaluate()` over the flat default
when present. A new test demonstrates the fix matters, not just that it's plumbed through: a run that would
have silently PASSED under the old flat 0.02 tolerance now correctly FAILS under the corpus's actual
measured (tighter) noise envelope. Live-verified end-to-end using the real `release.compose()` →
`project_release_to_baselines()` → `evaluate()` chain, not just hand-built test fixtures.

### 3. Gate-coverage visibility (§Design item 2c, never built)

No canonical, machine-readable "registered corpus" list exists anywhere in the repo (confirmed by a
repo-wide search: `jseval datasets` only reflects local, gitignored materialization; the mixed-BEIR builder
script takes arbitrary CLI args rather than declaring a fixed catalog; the search-quality register's Dataset
Catalog is prose). Rather than build a new catalog, extended the existing `jseval datasets` command with a
`gated_by` field per entry, computed against the three committed ratchet baseline files — reusing
`ratchet_kernel.load_baselines_doc` (the same live-projection-aware loader `commands/gates.py` already uses
for relevance-gate/perf-gate) rather than re-deriving the projection logic. This surfaced a further,
out-of-scope finding: `leak-gate-baselines.v1.json` keys BEIR corpora by bare name (`"scifact"`) while
relevance-gate/perf-gate use the canonical slug (`"beir/scifact"`) for the same corpus — a real, pre-existing
inconsistency, logged to the observations inbox rather than fixed (not this feature's scope), with the
command's own coverage check made robust to it (checks both forms) rather than silently misreporting
coverage. The command's docstring states its honest limit plainly: a corpus never locally materialized won't
appear at all, matching the confidence-building pass's finding that this limitation is structural, not a gap
in this specific implementation.

**Verification**: `python -m jseval datasets` run live, showing `scifact` correctly reported as
`gated_by: leak-gate, perf-gate, relevance-gate` and other BEIR corpora as `UNGATED` in this worktree (no
`datasets/mixed|golden/` materialized here) — the exact visibility the design asked for. Full jseval suite
green after all four fixes.

**What this pass's own retrospective is**: a "critical review" (sixth pass) checked the *correctness* of
what was built; it did not check *completeness* against the design, and its own live-verification step was
itself insufficiently adversarial (it verified the fix's logic, not the fix's wiring into the real CLI). The
conceptual-alignment re-read (before this pass) is what actually caught the completeness gap; investigating
that gap is what caught the live regression. Both checks — "is what I built correct" and "does what I built
match what was asked for" — were needed; neither alone would have found everything.

## Future directions (2026-07-01, eighth pass — brainstorming only, nothing committed)

With the design fully implemented (seventh pass) and its remaining "reach" gaps named plainly (the
conceptual-alignment re-check that followed), this section asks a different question: now that this
machinery exists, what could be built *on top of* it? Nothing here is scoped, prioritized, or decided —
per the assignment, this is an open menu, not a plan. Sources are cited (paraphrased, no external text or
code copied — consistent with the license-safe citation style already used in §External research pass).

### Where this connects to work already planned elsewhere in the repo

Two open, unimplemented tempdoc stubs turn out to be natural homes for the more ambitious ideas below,
rather than this tempdoc needing to invent new scope from nothing:

- **Tempdoc 658** ("Retrieval inspectability and diagnostic bundle") already asks for retrieval behavior to
  be "explainable enough for users, agents, and contributors to trust," and is explicitly related to
  `623-reproducible-benchmark-release` and the search-quality register. It owns *runtime* inspectability
  ("why this query, this result"); corpus-level trustworthiness (is the *benchmark itself* sound) is the
  natural upstream sibling, not a duplicate.
- **Tempdoc 659** ("Public release trust evidence") asks for JustSearch's release/security/model claims to be
  "externally verifiable instead of merely well-documented." Its named scope is supply-chain/security
  (checksums, signing, SBOMs) — a different kind of trust than eval-methodology trust — but the same
  underlying instinct (evidence over assertion) applies directly to "is our benchmark suite actually
  contamination-resistant and collision-free," which this tempdoc's corpus-certify machinery already
  computes and just doesn't yet publish anywhere.

### Polish (small, low-risk improvements to what already exists)

- `regeneration_determinism_report()`'s subprocess-spawning logic duplicates the shape of
  `test_generate_is_deterministic_across_processes` (same technique, two independent implementations).
  Factoring a shared helper would close a small "two authorities for one check" risk — the same
  projection-vs-fork concern this whole tempdoc is about, just applied reflexively to test code.
- `.changesets/*.md` files are hand-authored YAML frontmatter today. A tiny `jseval changeset-new` scaffold
  (prompt for gate/dataset/tempdoc, write the file) would lower the friction of using the
  baseline-relaxation-justification mechanism correctly — a real risk given the mechanism only protects
  what people actually use it for.
- `corpus-certify`'s human-readable (non-JSON) output prints a bare SKIP for
  `regeneration_determinism`/`descriptor_collisions` without the reason unless you already know to check JSON
  mode or `metadata.json` directly — a small discoverability gap.

### Simplify

- `_gate_coverage()` treats leak-gate's baseline file as a plain static read and relevance-/perf-gate as
  live-projected (`current_release`-aware). This is currently correct (confirmed by reading each file), but
  is an asymmetry that would silently need updating if leak-gate ever adopts the same `current_release`
  pattern. Calling `load_baselines_doc` uniformly for all three (it already degrades gracefully for files
  without `current_release`) would remove the special case.
- The bare-name-vs-canonical-slug inconsistency between `leak-gate-baselines.v1.json` and the other two
  baseline files (found and logged, not fixed, during this pass) is the root cause `cmd_datasets` currently
  works around with a dual-form check. Fixing the root inconsistency (canonicalizing `leak-gate-derive`'s
  output) would let that check collapse back to a single, simpler form.

### Extend (deepen mechanisms that now exist, several already named earlier in this tempdoc as deferred)

- **Close the "narrow reach" gaps the conceptual-alignment check found**: regenerate the 4 currently-
  unverifiable `635-corpora/*` sources fresh so the whole self-demo suite becomes regeneration-verified, not
  just 1 of 5; run `jseval calibrate` for real releases so `tolerance_band` actually has data to consume
  instead of always falling back to the flat default; materialize (or build committed sources for) the
  `mixed/*` corpora so gate-coverage visibility shows the corpora that originally motivated the feature.
  These aren't new designs — they're finishing the ones already built.
- Wire `baseline_ref` into the `.changesets` checks for real PR-scope anti-replay protection (deferred in the
  sixth pass as speculative structure the problem didn't yet need — worth revisiting now that the mechanism
  has real usage history to judge that against).
- Escalate `descriptor_collision_report` from exact-title-match to embedding-based near-duplicate detection
  if the generator's content ever becomes less templated (already named as the standard escalation path in
  §External research pass — this system already runs an in-house embedder that could serve it without a new
  dependency).
- Build the still-deferred needle-position variation (bounded placement informed by NoLiMa's convention,
  named in §Design item 1 and §External research pass but never implemented in any pass).
- Extend hardware-homogeneity checking to the perf-gate re-pin flow itself, not just release-compose time.

### New UX/DX features (bigger, more visible — external precedent for each, not invented from scratch)

- **A `jseval corpus-doctor` command** combining every corpus-quality check (closed-book, fidelity,
  descriptor-collision, regeneration-determinism) into one pass/warn/fail summary with actionable
  remediation hints. This is a well-established, widely-adopted CLI pattern — `brew doctor`, `flutter doctor`,
  `wp-cli doctor-command`, Salesforce CLI's and WorkOS's `doctor` commands all follow the same shape
  (pass/warn/fail per check, "here's the exact command to fix it"). [Homebrew Troubleshooting](https://docs.brew.sh/Troubleshooting),
  [Flutter doctor diagnostics](https://docs.flutter.dev/install/troubleshoot), [WorkOS Agent Experience](https://workos.com/blog/agent-experience).
- **An auto-generated "corpus health" report**, mirroring `scripts/docs/gen-scorecard.mjs` (which already
  turns `release.v1.json` into `docs/reference/benchmarks/scorecard.md` — an existing, working precedent in
  *this* codebase) but for corpus-certification data instead of retrieval-quality data. The broader pattern
  this mirrors externally is [Great Expectations' "Data Docs"](https://docs.greatexpectations.io/docs/0.18/reference/learn/terms/data_docs/) —
  auto-compiled, continuously-updated HTML documentation generated directly from a project's data-quality
  checks and their results, so the documentation can't drift from what was actually verified.
- **A per-corpus "corpus card"**, generated from the same certification JSON already being produced
  (contamination class, closed-book %, collision status, determinism status, retrieval-difficulty band).
  This isn't a bespoke format to invent — "Datasheets for Datasets" (Gebru et al.) and "Data Cards"
  (Pushkarna et al., Google) are established, recognized academic conventions for exactly this kind of
  standardized dataset disclosure, cited as improving "transparency, reproducibility, and accountability."
  [Datasheets for Datasets (CACM)](https://cacm.acm.org/research/datasheets-for-datasets/),
  [Data Cards (ACM FAccT)](https://dl.acm.org/doi/fullHtml/10.1145/3531146.3533231).
- **A public-facing "how we verify our benchmark corpora" page**, feeding from the same corpus-card data,
  connected to tempdoc 659's mandate above. Current (2026) research on AI benchmark credibility is
  unambiguous that this is a live, not solved, problem: annotation error rates and contamination are cited
  as actively undermining benchmark reliability industry-wide, and "transparency about who judged what under
  which conditions" is named as part of the fix — not a stale concern this would be catching up to.
  [AI Benchmarks 2026: Top Evaluations and Their Limits](https://kili-technology.com/blog/ai-benchmarks-guide-the-top-evaluations-in-2026-and-why-theyre-not-enough).
  **Public-claims caveat, since this repo and any resulting page are public**: this would need to state only
  what the corpus-certify machinery actually verifies (determinism, collision-freedom, closed-book score) —
  not imply a broader compliance/certification status the checks don't cover. The existing public-claims CI
  lane (`check-frontend-stack-claims.mjs`-style pattern) is the precedent for how this codebase already
  guards against exactly that kind of overclaim.

### What this pass deliberately did not do

No code was written or changed. No idea above was chosen over another, prioritized, or scoped into an
implementation plan — the assignment was explicitly open-ended ("nothing specific... no rush"). Turning any
of these into real work is a future decision, not one this pass makes.

## Design theorization for the eighth pass's ideas (ninth pass, 2026-07-01)

The eighth pass produced an unstructured menu. Before any of it is built, this pass asks the same question
§Design asked of the original audit: **what already exists, does any of this need new structure, and if so
how much?** General design only — no implementation, matching the discipline of the original §Design pass.

### The actual question underneath the menu

Four of the eighth pass's ideas (`corpus-doctor`, the auto-generated health report, the corpus card, the
public trust page) all read the *same* underlying data: the per-corpus certification verdicts
(`closed_book_certification`, `fidelity.retrieval_ndcg`/`retrieval_difficulty`, `fidelity.descriptor_collisions`,
`fidelity.regeneration_determinism`) that `corpus_certify.py`/`corpus_fidelity.py` write into each corpus's
`metadata.json`. If two or more of those consumers are ever built independently, each re-deriving its own
read/interpretation of that data, that is exactly the fork class this entire tempdoc has been about — just
one layer further downstream than anything audited so far (corpus data → certification verdicts → what
*reads* those verdicts).

### What already exists, and must not be replaced

- **`metadata.json`'s `fidelity` block is already, in practice, a canonical record** — confirmed by reading
  `corpus_certify.py`/`corpus_fidelity.py` in full: exactly two functions write it, both merge (never
  clobber) into the same block, both follow "verdict is derived, never hand-asserted." There is currently
  **one writer path** (`commands/corpus.py`'s `cmd_corpus_certify`/`cmd_corpus_fidelity`) and **zero readers**
  beyond that same CLI's own human/JSON output. No fork exists today — there is nothing to fork yet, because
  there is only one consumer.
- **553's canonical-record + governed-projections pattern** (`SearchTrace`) and **623/640's `release.v1.json`
  as a canonical record**, with `scripts/docs/gen-scorecard.mjs` as a governed projection of it — the
  established shape this codebase already reaches for whenever multiple consumers need to agree on one
  measurement's meaning. If a shared corpus-certification abstraction is ever needed, this is the pattern to
  conform to, not a new one to invent.
- **625's own recorded reach** already named "the INPUT side of every measurement record" as instance-worthy,
  and **646's "event-sourced tempdoc" idea** independently re-derives the identical seam applied reflexively
  to tempdocs themselves. Both are prior instances of the same principle now under discussion a third time.
- **The search-quality register's Dataset Catalog table and `jseval datasets`** — both already confirmed
  (confidence-building pass) to be non-canonical: one is prose, the other is local-materialization-dependent.
  This is a *distinct* gap from the certification-data one above (it's about which corpora exist, not
  whether a given corpus is verified) but shares the identical missing-canonical-record shape.

### The settled design: do not build a shared abstraction now — name the trigger instead

Because there is one writer and zero real readers today, formalizing `metadata.json`'s fidelity block into a
typed schema, a shared reader module, or any other new abstraction **now** would be structure for consumers
that do not exist — exactly what the assignment says not to do. The scope-matched design is:

1. **The first UX consumer that actually gets built** (of the eighth pass's four — most plausibly
   `corpus-doctor`, since it is CLI-only, has no public-facing risk, and needs no new data) **should read
   `metadata.json` directly, in its current shape.** This is "extend what exists," not "build new."
2. **Recognize, do not build, the trigger for generalizing**: the moment a *second* independent consumer
   needs the same data (e.g., the health-report generator, once `corpus-doctor` already exists), that is
   when extracting a shared read/projection function (something like `corpus_certify.read_certification_record
   (dataset_dir)`, returning the union of both files' verdicts in one typed shape) stops being speculative and
   starts being the same "extend, don't fork" move 640 already made for perf metrics. This mirrors this
   codebase's own established discipline — 625 waits for "the fork bites a second time," 646 defers its
   machinery "to the rule-of-three trigger" — not a new practice invented for this pass.
3. **The corpus-catalog gap (which corpora exist) is a separate, pre-existing, already-named problem**, not
   something this pass's ideas should silently try to solve as a side effect of building a UX feature. It
   already has its own disposition from the confidence-building pass (a real question needing its own scope
   decision, likely its own tempdoc, given it may require either building committed sources for the `mixed/*`
   corpus family or accepting a narrower honest scope). Recorded here as the same underlying shape as the
   certification-data gap, not conflated with it.
4. **Public-claims safety for the two public-facing ideas (corpus card, public trust page), stated precisely
   since this repo is public**: any published claim must state only what the certify/fidelity machinery
   mechanically verifies — e.g. "this corpus's regeneration was diff-verified on `<date>`" or "0 of N
   descriptor collisions detected" are safe, factual, mechanically-derived statements. Words like "certified,"
   "compliant," or "guaranteed" without that same mechanical precision would overclaim what a closed-book
   contamination threshold and a collision count actually establish — the exact overclaim class the
   `frontend-stack-is-lit`-style public-claims CI lane already exists to catch in a different domain (README/
   `docs/business` framing), and the same discipline applies here before any public page is built, not after.

### Why this scope, and not more or less

Building the shared abstraction now would be premature — there is no second consumer to fork against.
*Not* naming the shared-data risk at all would repeat this tempdoc's very first mistake at one remove: it
took a full read-only audit to notice that corpus/qrel *data* needed the same governance its measurement
*outputs* already had; the eighth pass's UX ideas would put the exact same risk one layer higher (independent
*readers* of already-governed data) if nobody names it before two of them get built independently. Recording
the trigger is the right-sized move: real enough to prevent the fork from recurring silently, small enough
not to build anything for a problem that, today, does not exist.

## Reach (ninth pass)

### Is this design an instance of a principle that already exists elsewhere? Yes — a third confirmed instance.

553 (SearchTrace), 623/640 (release.v1.json + `gen-scorecard.mjs`), and now this pass's corpus-certification-
data question are three independent instances of one seam: *when more than one consumer needs to agree on
what a measurement means, that measurement needs exactly one canonical record and every consumer is a
governed projection of it — never an independent re-derivation.* 646's reflexive application of the same
seam to tempdocs' own current-state is a fourth. This design conforms to that seam by name; it does not
invent a parallel one.

### A recurring practice worth naming plainly (already used here, not invented by this pass)

**Generalize on the second real consumer, not in anticipation of one.** 625 ("build only when the fork bites
a second time"), 646 ("machinery deferred to the rule-of-three trigger"), and this pass's "extract a shared
reader once a second UX consumer exists" are the same discipline stated three times independently across
this codebase's history. Naming it plainly: a shared abstraction is *earned* by a second real, concrete
consumer — not by two or more *plausible* future ones, however likely they seem. Candidate scope where this
already applies or would apply: any place in this codebase where one producer's output currently has exactly
one consumer (a common shape — most CLI commands, most single-purpose JSON writers) is, by this same logic,
correctly *not* pre-abstracted, and should stay that way until a real second consumer shows up. This is not
a violation to fix anywhere — it is a description of already-correct restraint, worth having a name for so
it stops looking like an oversight when someone next notices a "single-consumer, ad-hoc-shaped" data format
and wonders whether it should already be a formal schema.

### What remains deliberately undecided

Whether `corpus-doctor` is actually the right first UX idea to build (vs. the health report, the corpus card,
or nothing at all) is a product/priority call this pass does not make — the eighth pass was explicit that
none of its ideas are prioritized, and this pass's job was the structural question underneath them, not a
roadmap. Whether the corpus-catalog gap gets its own tempdoc, gets folded into a future corpus-certification-
record design, or stays unaddressed is likewise left open.

## Was an internet research pass warranted for the ninth-pass design? (tenth pass, 2026-07-01)

**Mostly no, for the core design; yes, for one specific refinement.** The ninth pass's two load-bearing
ideas — canonical-record-plus-governed-projections, and generalize-on-the-second-real-consumer — are
decades-old, extremely stable software-engineering principles (closer to CQRS/single-source-of-truth and
YAGNI/Rule-of-Three than to anything currently in flux). Re-researching them would not change the
conclusion; they were correctly reasoned from this codebase's own history (553/623/640/625/646), not from
external state.

One adjacent, genuinely fast-moving question was worth checking: **if a formal "corpus certification record"
schema is ever built** (the ninth pass explicitly deferred this until a second real consumer exists), should
its *shape* be bespoke, or align with an external standard? Dataset-metadata standardization has seen real,
current movement — worth a targeted check rather than assuming either "nothing has changed" or "surely there
is a relevant standard."

**What I found**: [MLCommons Croissant](https://docs.mlcommons.org/croissant/) is an actively-developed
(a "Croissant Baker" tooling paper appeared on arXiv in May 2026), JSON-LD metadata format built on the
`schema.org/Dataset` vocabulary, now integrated by Hugging Face Datasets, Kaggle, and OpenML (400,000+
datasets described in it). It describes a dataset across four layers: Dataset Metadata, Resource, Structure,
and Semantic.

**The refinement this produces**: Croissant describes *what a dataset contains and how it's structured* — it
is not a fit for the concern §Future directions' health-report/corpus-card ideas actually have, which is
*verification results* (was this corpus's determinism diff-checked, is it collision-free, what was its
closed-book score). That concern remains better matched by the Great Expectations "Data Docs" precedent
already cited in §Future directions (auto-compiled reports from checks-and-results, not dataset-content
description) than by Croissant. Croissant is the right thing to reach for if the **separate**, already-named
corpus-*catalog* gap (§Design theorization, ninth pass — "which corpora exist" as opposed to "is this corpus
verified") is ever formalized publicly — dataset discovery/description is exactly Croissant's stated purpose,
and a bespoke catalog schema would be the same kind of needless fork with the outside world this tempdoc has
been correcting internally. Recorded as a refinement to that already-deferred item, not a new item.

**License/attribution**: no code, JSON-LD examples, or text were copied from any source into this repository
or this tempdoc — only concepts are described in this agent's own words, with links to the originals, matching
the citation style already used in §External research pass (third pass) and §Future directions (eighth pass).
Croissant is a community-governed, openly-published specification (MLCommons Croissant Working Group,
built on the public `schema.org` vocabulary); if it is ever actually adopted for a corpus catalog, that
adoption should reference the published spec directly (`docs.mlcommons.org/croissant`) rather than
reimplementing a look-alike format from memory — the public repo's license-and-notices CI check is the
backstop for exactly this kind of provenance discipline, and citing the source now (rather than only at
adoption time) keeps that discipline intact from the first mention.

## Confidence-building pass for the remaining work (eleventh pass, 2026-07-01)

Unlike the earlier confidence-building pass (which de-risked one crisp, numbered plan), this pass's honest
first finding is that **"the remaining work" is not one thing** — it spans items at very different
readiness levels. Investigation (read-only; no code changed) covered six specific uncertainties named going
in; results below, then an overall confidence rating.

**A. "Regenerate the 4 unverifiable corpora fresh" — de-risked, smaller than feared.** Read
`corpus_generate.py` in full to check for an unexplored parameter space that could still reproduce the 4
corpora's committed content byte-for-byte — confirmed none exists (the seventh pass's grid search was not
the gap; the generator's rendering code has genuinely drifted). The feared alternative — accepting new
content and triggering a re-baselining cascade — turned out not to apply: grepping all three ratchet
baseline files and the search-quality register confirms **none of the 4 corpora are pinned anywhere** —
they're self-demo/closed-book showcase corpora, not ratcheted. Regenerating them fresh is a small, isolated,
low-risk change after all.

**B. `jseval calibrate` — confirmed real cost; needs explicit go-ahead, not an autonomous default.** Reading
`calibrate.py` confirms each of the default 5 runs invokes `jseval run --start-backend --clean --pipeline`
— a full backend start, GPU model load, clean ingest, and enrichment-completion wait, repeated per corpus.
This is real wall-clock and GPU cost, not a cheap dry-run, and by the same logic already applied to
LLM-tier benchmarks in this codebase's own jseval skill doc ("multi-tier sweeps require explicit user budget
sign-off"), running calibrate for real releases should be proposed and confirmed with the user before an
agent executes it, not treated as a default autonomous step.

**C. `baseline_ref`/PR-scoped anti-replay — the ninth pass's "worth revisiting" framing does not survive
investigation.** A repo-wide search of `.github/workflows/` found **zero** CI invocations of
perf-gate/leak-gate/relevance-gate/`jseval release` — confirming what `.changesets/README.md` already said
in its own words: jseval's ratchets are purely local/agent-invoked, never CI-gated. "PR-scope" is a concept
that only makes sense when there's a PR-diff boundary to scope against, which the JS discipline-gate kernel
this pattern was borrowed from has and jseval's actual invocation model does not. This item should be
downgraded from "worth revisiting" to **not applicable to jseval's current usage pattern** — a correction to
the ninth pass, not new remaining work.

**D. Needle-position variation — the framing carried across nine prior passes may not map onto this
generator at all.** Reading `corpus_generate.py`'s chain-rendering functions in full: there is no
NoLiMa-style "buried fact at X% depth within one long document" concept here — each hop is a *separate*,
independently-retrieved short document, and the answer attribute always sits on the *last* entity of a
fixed-length hop chain. The only "position" concept that actually exists in this generator is corpus
write-order (every gold-chain doc is appended to `docs.jsonl` before any distractor doc) — a different,
smaller thing than NoLiMa's convention describes, and its effect on retrieval quality is unverified (BM25
and vector indices are not expected to have positional bias from ingestion order). This is a genuine,
previously-unexamined gap: an idea cited across multiple passes turns out to need re-scoping — what "needle
position" should even mean for a multi-document hop-chain generator — before it can be implemented as
described, not just implemented.

**E. New-UX-ideas spot check — confirmed unchanged.** `cmd_datasets` and `corpus_certify.py`/
`corpus_fidelity.py` were last edited by this same tempdoc's seventh pass; nothing has drifted since the
eighth/ninth pass's read of them. No new finding.

**F. Polish/Simplify items — confirmed as scoped.** The leak-gate bare-name-vs-canonical-slug inconsistency
and `_gate_coverage`'s asymmetric load path are exactly as small and isolated as the eighth/ninth pass
described; re-reading them produced no new uncertainty.

### What this changes about "the remaining work"

- **Ready to implement directly, low uncertainty**: item A (regenerate the 4 corpora), all Polish/Simplify
  items (F), the eighth pass's `.changesets` scaffolding-tool idea.
- **Ready to implement, but needs the user's explicit go-ahead first for cost/scope reasons, not technical
  ones**: item B (`jseval calibrate` for real releases) — an agent can build the plan but should not
  autonomously spend the GPU/wall-clock cost without confirmation.
- **Needs re-scoping before an implementation plan makes sense, not just picking up**: item D (needle-
  position variation) — the first step of implementing this is now "decide what 'position' should mean for
  this generator's structure," not writing the change described in five prior passes.
- **Downgraded to not-applicable**: item C (`baseline_ref` PR-scoping) — investigation reversed the ninth
  pass's "worth revisiting," not confirmed it.
- **Still explicitly undecided, unaffected by this pass**: which (if any) of the eighth pass's "New UX"
  ideas gets built first, and whether/when the corpus-catalog gap gets its own tempdoc — both remain product/
  priority calls for the user, not something this investigation resolves.

### Confidence rating and implementation-effort recommendation

**7/10** for the *now-ready* subset (item A + Polish/Simplify + the changeset scaffold tool) — meaningfully
higher than an unscoped "8/10 on everything," because three real risks were found and resolved (A's feared
blast radius did not materialize; B needs a cost gate, now named explicitly; C should not be built at all).
The residual 3 points are ordinary implementation risk (need for careful per-item tests, matching this
tempdoc's established live-verification discipline), not open unknowns.

**Not yet ratable**: item D (needs a scoping decision first, not an implementation plan) and any of the New
UX ideas (nothing has been chosen to build).

**Recommendation**: **sonnet** is sufficient for the ready subset (A + Polish/Simplify) — these are small,
well-precedented, single-file-or-few-file changes following patterns already established repeatedly across
this tempdoc's ten prior passes; no architectural judgment calls remain unresolved for them. If the user
decides to also tackle item D, its *re-scoping conversation* (what should "needle position" mean here)
benefits from a stronger reasoning tier or the user's own direct input before implementation starts — but
the implementation itself, once scoped, would likely also be sonnet-appropriate. Opus/fable-tier is not
warranted for any of this: nothing here involves the kind of open-ended architectural judgment or long,
autonomous multi-system reasoning that would justify the higher cost.

## Twelfth pass (2026-07-01) — implemented everything the eleventh pass found ready

Two items were resolved with the user before implementation (both flagged in the eleventh pass as
genuinely needing input, not an autonomous guess): run `jseval calibrate` despite its real GPU/wall-clock
cost (yes), and reinterpret needle-position variation as interleaving gold/distractor write order rather
than a NoLiMa-style in-document depth (which the generator doesn't structurally have). With both resolved,
everything the eleventh pass found ready was implemented and live-verified. `baseline_ref` PR-scoping stayed
excluded (confirmed not applicable — jseval's ratchets are never CI-invoked), and none of the eighth pass's
"New UX" ideas were touched (still an explicit, undecided product call).

**1. Interleaved gold/distractor write order.** `generate()` now shuffles `all_docs` with the existing
seeded `rng` (not a fresh `Random()`) before writing `docs.jsonl` — closing the one real positional
non-uniformity the generator had. Verified: query `evidence_ids` are ID-based, not positional, so no
query-correctness impact; a new assertion in the cross-process determinism test confirms gold and
distractor doc ids are actually interleaved, not still one unbroken leading block.

**2. Extracted `corpus_generate.regenerate_and_diff()`.** The subprocess-spawn-twice-and-diff technique,
previously duplicated between `corpus_certify.regeneration_determinism_report()` and its pytest sibling, is
now one function both call — the same "projection vs fork" principle this tempdoc has been about, applied
to test code. `out1`/`out2` are caller-supplied so a caller needing to inspect content afterward (the
interleave assertion) still can, while certification's ephemeral-tempdir caller is unaffected.

**3. Regenerated and fully re-certified all 5 procedurally-generated corpora.** Because item 1 changes
`generate()`'s output for every corpus, `needle-burial-v1` was regenerated too (with its own already-correct
parameters), not just the 4 previously-unverifiable ones. Parameters for those 4 were derived from their
current committed doc counts via the generator's own doc-count formula, not guessed: **n_chains=20** for
`synth-tabular-v1`/`synth-multiling-de-v1`/`synth-code-v1` (360 docs, exact match), **n_chains=26** for
`synth-multihop-prose-v2` (468 docs, exact match — 26 is also the English semantic-place-pool cap,
corroborating this was the original value); **doc_words=520** for all.

Measured results, live through the real CLI for all 5 (`needle-burial-v1`, `synth-tabular-v1`,
`synth-multiling-de-v1`, `synth-multihop-prose-v2`, `synth-code-v1`):
- **Closed-book: PASS for all 5** (0.000 accuracy, well under the 0.15 threshold).
- **Regeneration-determinism: PASS for all 5** — up from 1 of 5 before this pass. This was the actual point
  of the exercise: the whole self-demo suite's "seeded → reproducible" claim is now a verified property, not
  a docstring claim for 4 of them.
- **Descriptor-collision: FAIL for all 5** (17-27 colliding groups each) — the same pre-existing, already-
  documented generator defect found in the sixth/seventh pass (not newly introduced by regeneration, and not
  in this item's scope to fix — a deeper generator-logic change, left as-is).
- **Fidelity: FAIL for all 5 under the CLI's default `--modes bm25_splade`** (nDCG 0.017–0.214, all below the
  0.3 band floor) — but this was a measurement-mode problem, not a corpus problem: these corpora use
  `semantic=True` synonym-bridging *by design*, specifically so that lexical/SPLADE-only retrieval fails at
  the entry point and only dense retrieval can bridge it. `bm25_splade` mode has no dense leg at all, so
  failing under it is expected, not a regression. Re-run with **`--modes hybrid`** (this codebase's actual
  production-default mode): **PASS for all 5**, nDCG 0.53–0.84, all in-band, zero shortcut leaks. No corpus
  generation parameter needed adjusting — the fix was using the right mode, not tuning the corpus. Logged as
  an out-of-scope observation: `corpus-fidelity`'s CLI default mode is a real, separate footgun for any
  `semantic=True` corpus, worth its own follow-up (whether the default should be `hybrid`, or the CLI should
  warn).

**4. Fixed the leak-gate bare-name-vs-canonical-slug inconsistency at its source.** `cmd_leak_gate_derive`
now canonicalizes only its *output* key (via `release.canonical_dataset_slug`) — directory lookup still uses
the raw operator-typed slug, since run directories are named from `jseval run`'s literal `--dataset`
argument, never canonicalized (confirmed by reading `artifacts.py`; canonicalizing the lookup too would have
broken BEIR run-directory matching, a real near-miss caught before shipping). The already-committed
`leak-gate-baselines.v1.json`'s `"scifact"` key was hand-renamed to `"beir/scifact"` (pure key rename, value
unchanged). With the root cause fixed, `cmd_datasets`'s `gated_by` computation was simplified back to a
single canonical-slug check, removing the seventh pass's dual-form workaround.

**5. `jseval changeset-new`.** Scaffolds a `.changesets/*.md` file in the exact frontmatter shape the README
already documents; refuses to overwrite an existing file; live-verified end-to-end and via a round-trip
through `baseline_shift.load_changesets`.

**6. Ran `jseval calibrate` for `beir/scifact` and live-verified the tolerance-band wiring with real data.**
5 runs, `hybrid` mode, 50 queries each (cohort `eecb40dfacf3c53a…`) — real GPU/backend cost, as flagged.
Getting the calibrated envelope actually embedded into a subsequent run required matching **both**
`--max-queries` (a documented requirement) **and** the `JUSTSEARCH_DATA_DIR` environment variable (undocumented
in the command's own `--help`, discovered by tracing `run.py`'s envelope lookup — it reads the env var
directly, not a `--data-dir` CLI flag) — a real, easy-to-miss gotcha. Composed the fresh calibrated run into
an **isolated verification release** (`tmp/release-verification-scifact.json`, gitignored), deliberately
**not** overwriting the real committed `release.v1.json` — that file's `cohort` block describes one shared
git_sha/config across all 5 of its corpora, and merging in a scifact entry measured at today's different SHA
would have created a real cohort-consistency inconsistency for a benefit (proving the wiring works) fully
achievable without that risk. Confirmed end-to-end with the real calibrated data:
`tolerance_band_abs=0.00295` (a real, measured, much tighter tolerance than the flat `0.02` default), and
`evaluate()` correctly prefers it — a −0.01 nDCG shift that would have silently passed under the old flat
tolerance now correctly regresses under the calibrated one.

**Regression caught by the test suite itself**: adding `changeset-new` to the CLI surface broke a committed-
inventory drift lock (`test_command_surface.py`, 6 failures) until `python -m jseval.commands.inventory
--write` was run and the regenerated `inventory.generated.json` committed — exactly the mechanism this check
exists for, working as intended.

**Deliberately not touched**: `docs/reference/search-quality-register.md` (main-checkout canonical doc,
outside this worktree's scope; its `needle-burial-v1` row's doc/query counts are unchanged by this pass,
only its exact byte content, so no re-validation entry is owed there for this specific change).

**Verification**: full jseval suite green — **1116 passed** (up from 1114 — the 2 net-new tests: leak-gate-
derive canonicalization + the changeset-new round-trip, minus one test whose premise the item-4 fix
resolved). Live CLI re-verification for every item, not just unit tests, matching this tempdoc's discipline
throughout. No Java/Gradle or `modules/ui-web` files touched — no build/browser verification applies.

## Thirteenth pass (2026-07-01) — closed the last gap: the search-quality register's stale corpus citation

A conceptual-alignment re-read after the twelfth pass found one real, unresolved gap: regenerating
`golden/needle-burial-v1`'s content (twelfth pass, item 3) left `docs/reference/search-quality-register.md`
citing exact, specific numbers (F-023, F-024, F-025, D-004's shared-index A/B evidence, Q-011's evidence)
against a corpus that no longer exists in that exact form — the tempdoc's own "verified binding, not a
label" principle, applied reflexively to a change this tempdoc itself made. The first pass at this dismissed
it as "outside this worktree's scope" — a wrong assumption, since the register file is an ordinary tracked
file that exists in this worktree like any other.

A subsequent confidence-building pass found the gap larger than first assessed (five findings cite exact
numbers, not one row) but the fix smaller and safer than feared: the register's own rule against re-running
cited experiments without justification rules out re-measurement; the Dataset Catalog table is hand-
maintained (only a separate, clearly-marked block is auto-generated by `register-headline-sync.mjs`); and
`jseval.corpus_identity.corpus_signature()` already provides this codebase's established durable-binding
mechanism to cite.

**Fix**: added one "Corpus provenance note" under `## Findings` (before the first affected finding) stating
plainly what changed, which five findings/sections now predate it, that already-shipped decisions based on
those findings are unaffected (the measurements genuinely happened), and citing the current corpus signature
(`1ade35791b1db58b9a7e1ff21246278d8e588e1705cbeda36d8529ceab6699ec`) as a durable, checkable anchor. Added a
short cross-reference parenthetical to each of the five affected sections (F-023, F-024, F-025, D-004,
Q-011) rather than duplicating the caveat five times — each finding's actual numbers were left untouched, as
an accurate historical record of what was measured, when. Added a short pointer in the Dataset Catalog row's
Notes column; deliberately did **not** bump "Last Validated"/"Validated By," since changing those without a
real re-validation would recreate the exact label-not-binding problem this fix closes.

Ran the required post-edit regeneration (`llmstxt-generate.mjs`, `skills-sync.mjs`) since this is a canonical
doc; confirmed the diff is scoped to exactly the intended edits (register: +36/−2 lines; the `search-quality`
skill's generated `SKILL.md`, its only derivative, mirrors the same diff; `docs/llms.txt` unchanged).

This closes the last open item from the conceptual-alignment critique and the confidence-building pass that
followed it. Tempdoc 664 has no further remaining work: the original design (passes 1-7) is implemented and
live-verified; the eighth-pass brainstorm's "New UX" ideas remain an explicit, undecided product call (not
this tempdoc's to resolve); the ninth-pass's deferred canonical-record trigger has not fired (still correctly
not built); and every concrete "Extend" item the eleventh pass found ready is implemented (twelfth pass) with
its one downstream documentation gap now closed (this pass).
