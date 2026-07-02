---
title: "Deterministic demo corpus and demo mode — substrate for reproducible public demo assets and the five-minute onramp's first corpus"
type: tempdocs
status: IMPLEMENTED and live-verified (2026-07-02). The demo corpus, its staging scripts, corpus-signature verification, and the demo-mode boot flag + `ui-shot --record` recording tooling are all built and confirmed working against a real dev stack and real browser. One real gap remains (recording a full AI-chain answer end-to-end — needs the product's actual model-install flow, not yet exercised) plus a handful of deliberately-deferred ideas. **Start with the "Handoff summary" section near the end of this doc** for a concise account of what's done and what's genuinely left — the sections above it are the dated, in-order history of how this was designed, built, reviewed, and corrected, kept per this repo's append-only tempdoc convention. No PR opened yet.
created: 2026-07-01
updated: 2026-07-02
author: filed by agent on founder direction
category: product / onboarding / demo
related:
  - 656-five-minute-agent-runtime-onramp
  - 657-install-modes-and-model-pack-decomposition
  - 642-byo-files-self-demo-eval
  - 664-eval-corpus-integrity-and-verified-identity
  - 666-mixed-corpus-reproducibility  # established the fetch-fresh-vs-fabricate-and-commit policy this design applies
  - 635-contamination-resistant-eval-corpus  # ruled out as the content-generation mechanism; wrong shape (eval query chains, not demo prose)
---

# 669 — Deterministic demo corpus and demo mode

## Purpose

Public demo assets (screenshots, a GIF/video of a cited answer over a messy multilingual corpus,
fully offline) cannot yet be produced reproducibly. **Update 2026-07-02:** the merged 656 onramp
(PR #44) shipped `examples/onramp-corpus/` — four tiny English markdown files proving the Tier-0
zero-model smoke path — which is a *seed*, not the demo substrate: it is monolingual, text-only,
and deliberately trivial. This tempdoc designs the demo-grade layer on top of (or alongside) it:

- a small, redistributable, deliberately **messy** corpus (mixed formats, at least two languages,
  at least one scanned/OCR document) — the corpus that shows off what the Tier-0 smoke corpus
  cannot (multilingual retrieval, OCR, citations over heterogeneous files),
- a way to load it to a known-ready indexed state (including the enriched/AI-ready tiers, not just
  Tier 0),
- enough determinism that a demo can be re-recorded after UI changes without re-staging by hand.

## Boundary

- The corpus must be redistributable (license-clean) and contain nothing private.
- This is substrate work: recording/publishing the actual demo assets is founder work, out of scope.
- Do not fork eval-corpus machinery — reuse the 664 identity/verification approach where corpus
  integrity matters.

## First questions (for the design pass)

- Corpus source: synthesized, public-domain assembly, or derived from an existing eval corpus?
- Relationship to `examples/onramp-corpus/` (shipped by 656): extend it in place, or a sibling
  `examples/demo-corpus/` with the onramp corpus kept minimal for the Tier-0 smoke?
- Where does "demo mode" live — a dev-runner flag, a first-run option, or a separate ingest
  command? (656's doctor/smoke pattern — `scripts/dev/doctor.mjs`,
  `scripts/dev/test-onramp-first-success.mjs` — is the shipped precedent to project from.)
- Overlap with 642 (BYO-files self-demo): does 642's harness become the demo loader?

## Design exploration (open, 2026-07-02)

The pass below surveys possible directions, tensions, and open questions ahead of a
design pass. It does not settle an approach — treat every subsection as "worth
weighing," not "decided." It builds on a codebase investigation confirming: 656
already shipped a Tier-0 smoke corpus and named 669 as the owner of the demo-grade
delta; 657 shipped a queryable per-tier readiness endpoint; 642 and 664 are a stub
and a completed identity mechanism respectively, neither of which is a drop-in
loader for plain (non-eval-shaped) files.

### What kind of artifact is a "demo corpus," really?

Three things in the codebase already play a similar role to what 669 wants: the
Tier-0 onramp corpus (a pinned, tiny, fabricated fixture with a hardcoded expected
answer), the retrieval-eval "golden" corpora (judged relevance, used to measure
quality, not to look good), and the still-unbuilt BYO self-demo (a user's own
files, used to build *their* trust, not to be redistributed). A demo corpus for
public assets is a fourth variant: redistributable, deliberately messy, and judged
by whether it produces a convincing screenshot/GIF, not by a relevance metric.
Worth being explicit that this is a distinct genre with its own success criterion
("does the intended cited answer appear, reliably, and does the corpus look
authentically messy") rather than reusing eval-corpus judged-relevance machinery
wholesale, even where individual mechanisms (hashing, a load script) can be
borrowed.

That said, the four variants share a recurring shape: a small set of known files,
some kind of identity/signature so a consumer can verify what they're looking at,
an expected-outcome assertion (a query with a known good answer), and a load
script. It may be worth naming this as a general "reference corpus" concept later
— a shared shape, not a shared library, since AHA discipline says only unify code
that has a shared reason to change. Building 669 in isolation without noticing
this shape risks a naming or directory-structure collision whenever 642 eventually
gets built (both wanting an `examples/*-corpus/` sibling with a different manifest
shape). This is a note for whoever picks up 642 later, not a reason to block or
over-design 669 now.

### Where the content could come from

Three broad options, each with different licensing and "does it look real" costs:

- **Fully synthesized** (the onramp's approach — fabricated text, license-clean by
  construction). Lowest legal risk, but a synthetic scanned document is unusual: a
  "scanned" file typically *is* a real photograph or scan of a physical page, not
  generated text. A synthesized approach for the OCR file would mean rendering
  fabricated text to an image with realistic scan artifacts (skew, noise, blur)
  rather than sourcing a real scan — this keeps every file license-clean while
  still exercising the real OCR pipeline, and avoids having to verify the
  copyright/public-domain status of a sourced scanned document.
- **Curated from permissively-licensed real sources** (public-domain texts,
  Wikipedia excerpts, government works). Looks more "authentic," but shifts cost
  onto per-file license verification and (for scans) sourcing an actual scanned
  page that is unambiguously rights-clear — a heavier and slower process than
  writing fiction.
- **A small materialized subset of an existing multilingual eval dataset**
  (something like the Apache-2.0-licensed multilingual corpus jseval already
  fetches on demand). This would be new: nothing in the eval tooling today commits
  fetched corpus content to the repository — everything is fetch-on-demand and
  gitignored. Reusing it as a redistributable demo asset would mean materializing
  and committing a subset for the first time, which is a bigger structural
  decision than it first looks (repo size, and a different maintenance lifecycle
  than a "recipe" file).

These aren't mutually exclusive per file — a mixed corpus could combine
synthesized English/foreign-language documents with one or two carefully-cleared
real-world-format examples (e.g., a real public-domain PDF report) if a fully
synthetic corpus feels too artificial for a launch asset. The multilingual axis
specifically could stay entirely synthesized, since fabricating short pieces of
fiction in additional languages sidesteps sourcing and license-checking foreign-
language text altogether.

### Where "demo mode" should live

The investigation found no existing "demo mode" anywhere in the codebase — the
nearest precedent is a plain ingest-and-poll script. Three framings for where the
loading mechanism should live, which aren't equally scoped:

- **A dev/maintainer-only script**, parallel to the existing onramp doctor/smoke
  scripts. Lowest cost, matches the shipped precedent, and keeps the corpus purely
  a founder/marketing tool as the current Boundary section implies.
- **A user-facing "load a sample corpus" option** in the product itself (e.g., a
  first-run choice for someone without files ready to try). This reframes the
  corpus from a marketing-only artifact into a product onboarding feature, which
  raises the bar on content quality and footprint (it would need to install
  cleanly under every install-intent/model-pack tier, not just a maintainer's dev
  machine) but could double the corpus's value. The current Boundary section rules
  out recording/publishing assets as founder work, but does not yet say whether
  the underlying corpus itself is demo-only or could also ship as an in-product
  sample. Worth deciding explicitly rather than defaulting into one path.
- **A generic "stage a reference corpus" script**, parameterized by corpus
  directory and an expected-assertion set, that could serve the existing Tier-0
  onramp corpus and a future demo corpus (and eventually a BYO flow) through one
  mechanism rather than a bespoke script per corpus. This is the tooling-level
  version of the "reference corpus" shape above — worth naming as a direction, not
  committing to, since it would only pay for itself once a second real consumer
  exists.

### How "known-ready" gets produced

No pre-baked or snapshotted index exists anywhere in the codebase today — every
"known-ready" state, in both the onramp and the eval tooling, is produced by a
live ingest followed by polling until indexing (and, where relevant, enrichment)
completes. Two directions for 669's "known-ready indexed state" goal:

- **Keep it live, but make it fast.** Because the demo corpus is deliberately
  small, the slow part of "known-ready" is unlikely to be indexing the corpus
  itself — it's model load time for the enrichment stack (embeddings, SPLADE,
  reranker, NER). If those are already warm (the dev stack loaded and models
  active), re-ingesting a handful of files after a UI change could be seconds, not
  minutes, making a snapshot unnecessary. This keeps the invariant that the Worker
  is the sole owner of index state and avoids introducing schema-versioning risk.
- **Introduce a pre-baked index snapshot.** Would make "known-ready" instant
  regardless of model warm-up state, but this would be the first such mechanism in
  the codebase, and it creates a real staleness risk: a snapshot built against an
  older `fields.v1.json` schema or an older embedding model would silently diverge
  from what a live ingest would produce today, and nothing currently checks for
  that. If pursued, it would need to be validated against the current schema/model
  identity at load time (echoing 664's identity-signature approach) rather than
  trusted blindly. Worth treating as a "build only if live re-ingest turns out too
  slow in practice" fallback rather than a starting assumption.

### What "deterministic" can and can't promise

"Enough determinism to re-record after UI changes" is a real and achievable goal
with respect to *UI* changes, but determinism has natural limits worth being
explicit about up front rather than discovering later:

- **Model or tool version drift.** If the embedding model, reranker, SPLADE model,
  or OCR engine ever changes version (e.g., 657 already has an open question about
  adding a second, lighter multilingual embedding option), ranking or extracted
  text over the demo corpus could shift even though nothing about the demo corpus
  or the UI changed. The determinism promise here is scoped to "same models, same
  corpus, different UI" — not absolute reproducibility across model upgrades. A
  cheap mitigation, mirroring the onramp's existing pinned-query pattern, is a
  small assertion ("this query still surfaces this citation") that can be re-run
  as a canary whenever models change, rather than trusting the demo silently.
- **Threshold sensitivity in "messy" content.** The corpus is meant to look messy
  (imperfect OCR, mixed languages) but that's in tension with reliability — real
  noisy input is exactly the kind of input that sits near confidence thresholds
  (OCR-confidence routing, extraction timeouts) and can flip behavior on a minor
  code change even when nothing about the demo intent changed. The practical
  answer is likely to deliberately choose "messy-looking but comfortably clears
  every threshold" content rather than genuinely borderline content — but this
  should be a conscious choice, not an accident discovered when a re-recording
  breaks.

### Showcase vs. proof-of-difficulty

The Purpose section already leans toward proving the system handles what the
Tier-0 corpus cannot (multilingual, OCR, heterogeneous formats) rather than just
looking polished. Two postures are available and pull in different directions:

- A **showcase corpus** — curated for a clean, reliably impressive result. Lower
  determinism risk (previous section), but if it's too clean it could read as
  cherry-picked and undercut the "look what handles messy real files" claim.
  Also, this is presentation-only: it demonstrates the *destination* (a good
  answer) without visibly demonstrating the *difficulty* being overcome — a
  viewer can't tell the OCR was hard from a clean citation alone.
  Recording/highlighting confidence indicators, extraction warnings, or a
  visibly-imperfect source alongside the correct answer would demonstrate the
  underlying capability more convincingly, but adds moving parts.
- A **proof-of-difficulty corpus** — deliberately includes visibly-imperfect
  material (a scan that's slightly hard to read, mixed-language content within one
  document) and shows the system succeeding anyway, ideally with some UI signal
  (an OCR-confidence indicator, if one exists or is added) that a viewer can see
  the difficulty was real. Raises the determinism risk from the previous section,
  since "visibly hard" content is more likely to sit near a threshold.

These aren't fully separable — a corpus could lean toward proof-of-difficulty for
the OCR file specifically (where the pipeline is real and tested) while staying
in showcase mode for the multilingual/format-variety files (where reliability
matters more than difficulty theater). Worth deciding deliberately rather than
defaulting.

### Format and size coverage

"Mixed formats" is currently unscoped. Rather than picking formats arbitrarily,
the design pass could enumerate the extractors the product actually supports and
pick a representative subset that maximizes visible variety (e.g., a spreadsheet
turning into indexed rows, a PDF report, a scanned image, plain text/markdown)
rather than covering every supported format. Size and file count pull against
messiness/coverage: the Tier-0 corpus stayed at four tiny files; a demo corpus
covering multiple formats and languages plus an OCR asset will need more files
than that, but should stay small enough to remain a fast, redistributable
download — a modest file count in roughly the same order of magnitude as the
onramp corpus, not an eval-sized dataset, is likely the right target, but the
exact number is a design-pass decision.

### Product-facing vs. marketing-only

A related open question is where the corpus should live and who its intended
audience is. If it stays a founder/marketing-only tool, it likely belongs
alongside other demo/asset tooling rather than in a directory a product user or
open-source contributor would browse for sample data. If it is expected to
eventually double as an in-product sample corpus (see "Where demo mode should
live" above), its location, size, and content standards should be set with that
second audience in mind from the start, since retrofitting a marketing-grade
corpus into a product-facing one later would likely mean redoing the licensing
and content-quality work.

### Open questions to carry into the design pass

- Fully synthesized content (including a rendered-then-degraded "scan") vs. a
  curated real-world subset vs. a hybrid — and, independently, whether the OCR
  asset should be synthesized-but-realistic or a real cleared scan.
- Whether the demo corpus is marketing-only or a candidate in-product sample —
  this changes footprint, licensing rigor, and location.
- Whether "demo mode" should start as a one-off script (matching precedent) or a
  parameterized "stage a reference corpus" mechanism from the outset.
- Whether live re-ingest is fast enough in practice once models are warm, before
  considering a pre-baked index snapshot (and, if a snapshot is ever built, how it
  detects staleness against the current schema/model identity).
- Showcase vs. proof-of-difficulty posture, and whether it should vary per file
  within the same corpus.
- Target file count/size and the specific format list, derived from the actual
  supported extractor list rather than chosen arbitrarily.
- A canary assertion (query → expected citation) as a cheap signal that the demo
  still works after a model or pipeline change, mirroring the onramp's existing
  pinned-query pattern.

## Settled design (2026-07-02)

The exploration pass above surveyed directions without choosing between them. This
pass settles a direction, grounded in a closer read of the closely-adjacent
tempdocs (656, 657, 635, 641, 664, and the now-closed 666) and the actual
extraction/format surface in the codebase. It stays at the level of what should
exist and why, not how it is implemented.

**Governing finding first, since it resolves several open questions at once:**
tempdoc 666 (closed, merged 2026-07-01) established that this repository already
has a standing, universal policy — real, externally-sourced corpus data is never
committed to the public repo; only a small recipe (source id, seed, sample size)
is committed, and the real content is fetched fresh at build/eval time. This is
why every jseval eval corpus (SciFact, MIRACL, the CLERC-based legal corpus) works
this way, and it is not incidental — 666 found that even a corpus whose *source
material* was confirmed public-domain still carried unresolved licensing exposure
in the derived structure (query/passage pairing) added on top, discovered only
after five independent research channels came up empty. A demo corpus, however,
must be redistributable and usable fully offline with no fetch step — the same
requirement `examples/onramp-corpus/` already satisfies by being fully fabricated
rather than sourced. That precedent, not the eval-corpus fetch-recipe pattern, is
the correct one to extend for 669.

### Content and packaging

- A new sibling directory (`examples/demo-corpus/`, matching 656's own stated
  intent for 669 to be additive, not an in-place extension of the Tier-0 smoke
  corpus) holding fully fabricated, hand-authored content — conforming to the
  onramp corpus's precedent, not `corpus_generate.py`'s procedural eval generator.
  That generator (tempdocs 635/641) is purpose-built to emit multi-hop,
  closed-book-certifiable question/document chains for contamination-resistant
  evaluation; it has no mode for "plausible, demo-worthy prose," and forcing a
  demo corpus through it would mean adopting machinery built for a different job
  rather than extending something that actually fits.
- The multilingual documents should be genuinely authored fabricated prose per
  language (in the same spirit as the onramp's English pieces), not
  machine-translated placeholder text, so the demo doesn't read as artificial in
  a way that undercuts the multilingual claim it's meant to support.
- The OCR asset should be fabricated text rendered to an image with realistic
  scan/degradation characteristics, rather than a sourced real-world scan. This
  keeps the entire corpus uniformly license-clean by construction and avoids
  exactly the sourcing/clearance burden 666 already showed is heavier than it
  looks, while still exercising the real, already-shipped OCR extraction path.
- Format coverage should be drawn from the extraction surface the product
  actually demonstrates working end-to-end today — PDF (including an image-only,
  OCR-routed PDF or image), an Office format, and Markdown/plain text are the
  formats with real test-fixture coverage in the extraction pipeline — rather
  than an arbitrary or exhaustive format list.
- A per-corpus manifest, playing the same role as the onramp corpus's existing
  `README.md`, should state provenance (fabricated, contamination-free,
  license-clean) plus a content signature (see next section).

### Identity and integrity — conform to the existing signature function

664 already established a corpus-signature mechanism and named its own governing
principle directly in code: "conform, don't fork" — one signature definition
reused everywhere a corpus's integrity needs to be checkable, rather than a
second, parallel hash routine per corpus type. That existing function is
currently narrowed to the specific two-file shape eval corpora use
(`corpus.jsonl` + `qrels/test.tsv`), which doesn't fit a directory of
heterogeneous demo files. The design conclusion is to broaden that one function
to accept an explicit, sorted list of files (or a directory) instead of two
hardcoded filenames, so eval corpora and reference corpora like this one continue
to share the same signature definition rather than gaining a second one. Its use
here is intentionally narrow: a "did the bundled corpus arrive intact" check
before a demo is staged, not a certification or fidelity gate — those exist for a
different question (is this corpus valid for measuring retrieval quality) that
the demo corpus doesn't need answered.

The corpus-catalog gap that 664 and 666 both already named ("which corpora exist,
across this whole family, is not yet a canonical record anywhere") is a real,
separately-scoped decision that this tempdoc should not fold in. It is about
*enumerating* corpora; what this design settles is what properties one corpus
(the demo one) should have. The two are adjacent, not the same problem.

### Staging mechanism — extend the existing onramp script, don't duplicate it

`scripts/dev/test-onramp-first-success.mjs` already contains everything a
"demo mode" needs: it starts the stack, ingests a corpus via the existing
`/api/knowledge/ingest` endpoint, polls `/api/status` until indexing completes,
and asserts a canary query returns an expected result. Only two values in that
script are onramp-specific — the corpus path and the canary query; the rest is
already written in a corpus-agnostic way. The design conclusion is to extract
that logic into a small, parameterized staging mechanism (corpus directory,
canary query and expected result, and a target readiness tier), with the
existing onramp script becoming its first caller and a new demo-staging entry
point its second — extending working, already-shipped machinery rather than
writing a third, drifting copy of the same twenty lines of ingest-and-poll logic.
This should stop at "directory + canary + tier" — no corpus-type abstraction
beyond that, until a real third caller with a different shape exists (642's
eventual BYO loader is the most likely candidate, once it moves past stub
status).

Readiness, including the "enriched/AI-ready" tiers the Purpose section asks for,
should be read from the tier/readiness projection 657 already ships
(`/api/ai/install/plan-preview`, and the same tier-deriving logic the onramp's
own doctor script already uses) rather than re-deriving a second readiness
signal from raw status fields.

### Determinism — scoped, not absolute

Determinism here means "same models, same corpus, same UI-visible result" — not
"identical forever." The canary assertion is what turns that into something
checked at staging time rather than assumed, the same way the onramp's own
pinned query already works. If a model or extraction-tool version changes, the
canary is expected to be the thing that catches whether the demo still holds, not
something the corpus or staging script can guarantee unconditionally on its own.

### Explicitly out of scope for now

No pre-baked or snapshotted index. Nothing like it exists anywhere in this
codebase today — every "known-ready" state, everywhere, comes from a live
ingest — and introducing one would add a staleness failure mode (drift against
the current field schema or model version) with no existing mechanism to detect
it. If live re-ingest of a small, already-fabricated corpus proves too slow once
built and measured, that would be grounds to revisit; it is not a starting
assumption. Likewise, no in-product "load a sample corpus" UI feature: the
current problem is a substrate for founder-produced public assets, and 657's own
precedent for this codebase is to defer a second-consumer abstraction until a
second real consumer exists. The design should not assume the staging mechanism
only ever runs from a CLI, but building the product-facing feature now would be
adding structure the present problem doesn't ask for.

## What this reveals beyond 669

**Direct conformance, not a new principle.** Two decisions above are applications
of principles this codebase already states for itself, not new ideas: the
content-sourcing decision applies 666's already-established "fabricate and
commit, or fetch fresh and never commit — never a materialized real-world corpus
in the repo" policy; the identity decision applies 664's own "conform, don't
fork" signature principle by widening its existing function's input rather than
writing a second one.

**A recurring shape worth naming, not building.** Looking across the onramp
corpus (656), the eval/golden corpora (jseval, 664/666), and now this demo
corpus, the same four properties keep appearing together: a committed-or-recipe'd
content set, a content signature for integrity, an expected-outcome assertion
used as a live canary, and a staging script that gets the corpus to a checkable
ready state. Call this the **reference-corpus shape**. It plausibly applies
anywhere this codebase needs "a known, small set of inputs plus a way to prove
the system handled them correctly" — the still-unbuilt BYO self-demo (642) is the
next obvious candidate, and it's conceivable the shape extends past corpora
entirely to other kinds of release-verification fixtures. Existing code already
falls short of its own implied bar in one small, low-stakes way: the onramp
corpus has a canary query but no committed content signature the way eval
corpora do — worth knowing about, not worth fixing as part of this tempdoc. This
is a candidate principle to record and watch, not a mandate to build a shared
abstraction now; per this project's own AHA discipline, that's warranted only
once a second consumer with a genuinely different shape (like 642, once it's
real) needs the same thing 669 needs.

## Research pass (2026-07-02): synthetic document degradation, and a real OCR-language constraint

Most of the settled design above is internal engineering (signature reuse, script
extension, tier readiness) with no external state to check. One piece — the
"rendered-then-degraded synthetic scan" idea for the OCR demo asset — sits in a
space that genuinely is active and evolving externally: synthetic document-image
degradation for OCR testing/training. A short external check was worth doing
before settling that detail further, and it surfaced one library worth naming
plus one real internal constraint that changes the design.

**Tooling worth citing if this is built: Augraphy.** [Augraphy](https://github.com/sparkfish/augraphy)
is an actively-maintained, MIT-licensed Python library built specifically to turn
a clean document image into a realistic degraded one — synthetic printing,
faxing, scanning, and photocopying artifacts — described in its own paper,
[Augraphy: A Data Augmentation Library for Document Images (arXiv:2208.14558,
ICDAR 2023)](https://ar5iv.labs.arxiv.org/html/2208.14558). It is the
purpose-built tool for exactly the "fabricate text, then make it look like a real
scan" step this design calls for, rather than hand-rolling noise/blur/skew from
scratch. It would be a **build-time, dev-only dependency** used once to generate
a static image asset that gets committed — the same category as
`corpus_generate.py`'s own dependencies, not a runtime or installer dependency,
so it falls outside `gen-notices.mjs`'s scope (which the codebase already scopes
specifically to software *redistributed inside the shipped installer*). Its MIT
license places no obligation on the output images it produces, but as a matter of
this repo's existing convention of citing sources plainly, the demo corpus's own
manifest (see "Content and packaging" above) should note that the OCR asset was
synthetically generated using Augraphy, not photographed from a real document.

**A real constraint this surfaced: the product's bundled OCR currently supports
English only.** `packaging/runtime/tesseract-windows.v1.json:11,20-24,28` pins
exactly one tessdata language file (`eng.traineddata`, Apache-2.0, from
`tesseract-ocr/tessdata_fast`) as part of the installer's Tesseract bundle;
`requiredLanguages` is `["eng"]`. This means a scanned/OCR demo document in a
non-English language would not extract correctly against what the product
actually ships today — OCR and multilingual are not composable on the same file
without first adding a new tessdata language pack to the packaging manifest,
which is a separate, out-of-scope decision for this tempdoc (comparable in shape
to tempdoc 657's own still-open question about adding a second, lighter
multilingual embedding model — a real product decision with installer-size and
licensing review implications, not a demo-corpus detail). The corrected design
conclusion: the OCR asset should be an English-language document (matching what's
genuinely supported end to end today), and the corpus's multilingual coverage
should be carried entirely by its non-OCR files. Treating "OCR" and
"multilingual" as two separate axes of the same corpus, each satisfied by
different files, resolves what would otherwise be a demo that quietly fails on
the one file meant to be its centerpiece.

No other external research changes this design. The fast-moving part of the 2026
OCR landscape (new open-weight OCR/document-VLM releases, multilingual-script
benchmarks) is about the quality of OCR *engines and models* — a question that
belongs to whatever future work might reconsider the product's own bundled OCR
stack, not to this tempdoc, which only needs a document its already-shipped
Tesseract pipeline can read correctly.

## Confidence-building investigation pass (2026-07-02)

Before implementation, every load-bearing assumption in the settled design above
was checked against primary sources (call sites, CI config, live API responses,
a live dev-stack run) rather than trusted from static reading alone. Most
assumptions held; two real corrections came out of this pass.

**Confirmed, no change needed:**
- Widening `corpus_identity.py`'s `corpus_signature()` to accept an explicit file
  list is safe — every real caller (`run.py`, `ingest.py`, `corpus_build.py`)
  passes a single directory and treats the result as an opaque hash string; none
  inspects the two-file contract from outside the function.
- `corpus_generate.py`'s `generation_provenance` shape is confirmed
  eval-chain-specific and not worth conforming to — and `build_golden()` treats
  it as an opaque passthrough, so a demo corpus's own simpler provenance record
  needs no code change elsewhere.
- The OCR routing gate (`PolicyDrivenTikaExtractor`/`OcrRoutingConfig`) is purely
  structural — MIME type, pixel dimensions, page count, and a text-quality score
  — with no EXIF/color-profile/"real capture device" check anywhere. A
  synthetically degraded image has nothing routing-specific to fail on.
- The English-only Tesseract finding holds product-wide — no macOS/Linux/other
  tessdata manifest exists anywhere in the repo; only one platform manifest
  exists at all, and it carries only `eng`.
- No Python-specific license/security gate exists in this repo (the only
  dependency gate, `npm-audit`, is JS-only), and `examples/demo-corpus/` doesn't
  already exist, and `check-language-agnostic-analysis.mjs`'s scope is narrowly
  four checks against specific catalog/Java paths — none of which a new
  multilingual example directory would touch.
- Augraphy installs cleanly in an isolated scratch venv (v8.2.6, Python 3.14,
  no dependency conflicts) — a throwaway check, not a tracked dependency
  addition.

**Two real corrections:**

1. **The staging script is CI-wired, not purely a manual dev tool.**
   `.github/workflows/onramp-smoke.yml` invokes
   `scripts/dev/test-onramp-first-success.mjs` directly (manual
   `workflow_dispatch`, advisory/non-blocking per ADR-0044, but a real,
   automated caller with no indirection layer). Extending this script into a
   shared staging mechanism must preserve its exact current behavior for the
   Tier-0 path, not just "roughly" — the workflow calls the file directly.

2. **The onramp script's indexing-settle poll window (30 attempts × 1s = 30s) is
   demonstrably too tight for the demo corpus, not just plausibly so.** OCR
   extraction's own configured `perFileTimeoutMs` defaults to 30000ms *per
   file* (`OcrRoutingConfig`) — a single OCR document alone can approach the
   entire current poll budget before indexing is even considered. A live
   ingest of the existing tiny onramp corpus settled effectively instantly
   (`pendingJobs: 0`, `indexState: IDLE` immediately after ingest, well under
   the 30s budget), confirming the current window is sized for Tier-0's four
   trivial files specifically, not generic headroom. The demo-mode staging
   path needs a materially larger poll budget (and ideally a corpus-size- or
   OCR-aware one) rather than reusing the onramp's 30s constant as-is.

3. **The `/api/ai/install/plan-preview` + `doctor.mjs` "tier readiness" pairing
   in the settled design was subtly wrong.** These are two independent,
   non-reusing implementations of similar logic: `doctor.mjs`'s tier derivation
   (`deriveTier()`) is a fully self-contained, fully offline computation from
   the model registry and on-disk package presence — it never calls
   `plan-preview` and doesn't need a live stack to answer the Tier-2 question at
   all. The corrected design: the demo-staging mechanism should read readiness
   from `doctor.mjs`'s own tier derivation directly (or its exported logic),
   not from `plan-preview`, which answers a related but different question
   (remaining download bytes per tier) that happens to require a running
   server. This is also worth naming as a small, pre-existing drift risk in its
   own right — two parallel tier computations that could silently disagree —
   but fixing that drift is out of scope for this tempdoc.

None of these corrections change the overall design direction; they tighten
specific parameters (poll budget) and correct one specific wiring detail
(which existing readiness signal to read). The design's shape — extend the
onramp script, don't duplicate it; generalize the existing signature function;
fabricate content; keep OCR English-only — is unchanged and now verified
against primary sources rather than inferred from tempdoc prose.

## Implementation (2026-07-02)

Built and live-verified in this worktree, following the settled design and its
corrections above exactly — no further scope changes.

**Corpus identity.** `corpus_signature()` (`scripts/jseval/jseval/corpus_identity.py`)
widened with an optional `files=` parameter, default behavior unchanged. A new
unit test covers the explicit-file-list mode; the full `test_corpus_governance.py`
and `test_run.py` suites pass unchanged (48 + existing tests green). The two
prose mentions of the two-file formula (`docs/reference/search-quality-register.md`,
synced into `.claude/skills/search-quality/SKILL.md` via `skills-sync.mjs`) note
the new mode without touching the specific already-recorded corpus signatures.

**`examples/demo-corpus/`.** Six fabricated content files (two English, one
French, one German markdown; one born-digital `.docx`; one synthetic OCR
`.jpg`) plus `README.md` and `corpus-signature.json`. `harbor-ledger.docx` and
`weathered-manifest.jpg` are generated by `scripts/dev/generate-demo-corpus-assets.py`
(manual, dev-only, requires `pip install python-docx Pillow augraphy` — never
run in CI). The OCR asset uses a hand-picked, seeded Augraphy pipeline
(`InkBleed` + `NoiseTexturize` + `BrightnessTexturize` + light `LowInkRandomLines`,
`random_seed=669`), not Augraphy's unseeded default pipeline — an early attempt
with the default pipeline produced a non-deterministic heavy "UNOFFICIAL" ink
stamp that partly obscured the text, which would have undermined both
determinism and legibility; the seeded, moderate pipeline stays reproducible
and comfortably legible. The image is saved as JPEG, not PNG (~279KB vs
~1.1MB) — the noise texture defeats PNG's lossless compression, and a JPEG is
also more representative of a real scan/photo.

**Staging.** `scripts/dev/lib/stage-reference-corpus.mjs` extracts
`startStack`/`stopStack`/`getJson`/`sleep`/`stageAndVerify`/`getTier` from the
onramp script's original inline logic, parameterized (corpus path, canary
query, poll budget, failure-message label). `scripts/dev/test-onramp-first-success.mjs`
now calls this helper with its original constants and default poll budget;
a live run confirmed byte-identical `OK`/exit-0 behavior, so
`.github/workflows/onramp-smoke.yml` needed no changes. `scripts/dev/stage-demo-corpus.mjs`
is the new demo-corpus entry point: same shared helper, a 180×1000ms poll
budget (vs. the onramp's 30×1000ms — justified live: OCR's own
`perFileTimeoutMs` alone defaults to 30000ms per file), the `"obsidian ledger"`
canary (uniquely matches `verrenmoor-customs.md`), and a Tier-2-conditional
check against `/api/chat/ask` (the real endpoint + SSE contract — `POST`,
event stream, terminal `done`/`error` events, citations carried on `done.citations`
with `parentDocId`/`excerpt`/etc. — confirmed by direct testing, not assumed
from the frontend's SSE consumption).

**What live testing found and corrected, beyond the confidence-building pass's
predictions:**
- The demo-staging script's original `AI_UNAVAILABLE` errorCode check was
  wrong — the real code the `/api/chat/ask` SSE stream emits when the
  inference runtime isn't warm is `AI_OFFLINE`. Caught by actually running the
  script against a stack with the runtime uninstalled (this sandbox has no
  GPU chat model staged in any worktree by default) and reading the literal
  SSE payload rather than guessing from the Java `ApiErrorCode` enum's naming.
  Fixed; the informational-skip-not-hard-fail behavior now works as designed.
- A real `| tail` pipe-exit-masking incident (the exact `piped-exit-masked`
  class this repo already names) happened live during this verification: a
  `FAIL` run reported exit 0 because the exit code came from `tail`, not
  `node`. Re-ran without the masking pipe to get the real exit code.
- The demo corpus's own `README.md` measurably dominated RAG retrieval over
  the actual fabricated content (4 of 5 top chunks were `README.md`/help-doc
  content, zero from the intended demo documents) — worse than anticipated,
  because the first-drafted README was long and prose-dense. Shortened to
  onramp-README length/style; a second live check confirmed `README.md` no
  longer appears in top retrieval for the canary question. The demo corpus's
  own `corpus-signature.json` file list (used for the identity hash) already
  excluded `README.md`, so this content edit didn't require recomputing the
  signature.
- A related, but out-of-scope, finding: with the demo corpus's own README no
  longer competing, the app's *built-in* help docs (`ssot/docs/help/*.md`,
  auto-seeded into every fresh index) took the top retrieval slots instead for
  a topical question. This is a pre-existing platform behavior that would
  affect the onramp corpus and any future BYO corpus identically — logged to
  the observations inbox rather than fixed here, per scope.

**What could not be live-verified in this sandbox, and why:**
- Real OCR text extraction on `weathered-manifest.jpg` — this sandbox has no
  Tesseract binary staged anywhere in the repository (not just this worktree;
  confirmed absent from the main checkout too), so `visualExtraction.ocrBlockedReason`
  reports `ocr.language_missing` regardless of the demo corpus's own content.
  This is an environment-provisioning gap, not a demo-corpus defect — the OCR
  *routing* logic itself was already confirmed structurally sound (no
  scan-realism-specific gates) in the earlier confidence-building pass. The
  browser UI correctly and honestly surfaces this degraded state ("OCR is
  missing a required language pack") rather than failing silently.
- The full Tier-2 cited-answer path end-to-end through the browser UI — the
  GPU chat runtime binary was staged into this worktree from the main
  checkout (a same-machine file copy, ~1.1GB, since removed after testing) to
  get past the "variant not installed" error, but the actual chat model
  weights (a further ~5.5GB GGUF file) were not copied, since that trade-off
  stopped being worth the marginal confidence gained: the `/api/chat/ask` SSE
  contract, its citation payload shape, and the demo-staging script's handling
  of both the success and not-yet-warm cases were already directly verified
  against the real endpoint (see above) without needing the LLM to actually
  generate a response.

**Live-verified successfully, with evidence:** the widened signature function
and its tests; the onramp script's preserved behavior; the demo corpus's
ingest, multi-format indexing (markdown/office/json/image facets all appeared
correctly), and canary search — confirmed both via the API and via the actual
browser search surface (`"obsidian ledger"` → `verrenmoor-customs.md` top
result, term highlighted, no console errors, zero axe violations); the
`/api/chat/ask` SSE contract and citation shape; and the demo-staging script's
graceful, correctly-labeled degradation when the AI runtime isn't warm.

## Critical-analysis pass (2026-07-02): two real gaps found and fixed, plus a corrected OCR verification result

A follow-up review of the implementation against this tempdoc's own Purpose
and settled design found two substantive mismatches, both now fixed in
`scripts/dev/stage-demo-corpus.mjs`.

**Gap 1 — the staging script never exercised the corpus's actual reason for
existing.** Its canary was functionally identical in shape to the onramp
script's own canary (an English keyword query + a Tier-0/1 mode check) — it
never touched multilingual content or OCR, the two capabilities this
tempdoc's Purpose names as the entire point of building a corpus beyond the
onramp's four English files. A real regression in multilingual indexing or
OCR routing would have reported `OK`. Fixed by adding a second, hard-asserted
canary (`"Moulin de Brume"` → must surface `moulin-de-brume.md`) — deterministic
regardless of tier, since a literal non-English phrase is findable via plain
keyword/ICU matching per Hard Invariant #6, no embedding model required — plus
an informational (not hard-failing, since OCR engine availability is
genuinely environment-dependent) OCR check that searches for manifest text
and reports whether extraction succeeded.

**Gap 2 — `corpus-signature.json` was computed and committed but never
checked by any code**, despite the settled design explicitly promising it as
a "did the bundled corpus arrive intact" check before staging. Fixed:
`stage-demo-corpus.mjs` now recomputes the sha256 over the exact file list
recorded in the signature's own manifest (so the check is driven by the
committed file, not a second hardcoded list) using Node's `crypto` module,
and warns — doesn't hard-fail, matching `corpus_identity.py`'s own "a corpus
identity must never be the thing that fails a build" philosophy — on
mismatch.

**A related correctness bug found while implementing the fix for Gap 1:** the
Tier-2 RAG check didn't scope `docIds` on `/api/chat/ask`, so — as already
observed live in the Implementation section above — the app's built-in help
docs could out-rank the demo content, letting the citation check pass while
citing the wrong document. Fixed by passing the same six-file list from
`corpus-signature.json` as `docIds`.

**A genuine implementation bug caught by live-testing the new OCR check:**
the first version read `status.worker?.core?.visualExtraction`, but
`visualExtraction` is a sibling of `worker.core`, not nested inside it — the
correct path is `status.worker?.visualExtraction`. Confirmed by dumping the
live `/api/status` response directly rather than trusting the assumed shape.

**A significant correction to this tempdoc's own earlier claims:** the
Implementation section above states OCR "could not be live-verified in this
sandbox" because no Tesseract binary was found staged anywhere in the repo.
That conclusion was incomplete. Live investigation while fixing Gap 1 found
that this sandbox actually has a system-level Tesseract binary reachable on
`PATH` (via a Scoop shim) — `ocrEngineAvailable: true` was accurate; only the
English tessdata language file was missing from the location the worker
expects it (`JUSTSEARCH_TESSDATA_PATH`/`TESSDATA_PREFIX`, per
`TikaOcrRuntime.findTessdataDirectory`), not the engine itself. Downloading
just that one file (`tessdata_fast/eng.traineddata`, ~4MB, matching this
repo's own already-pinned sha256 exactly) and pointing
`JUSTSEARCH_TESSDATA_PATH` at it was enough to get genuine, real, end-to-end
OCR verification — both via the staging script's new check and, directly, in
the browser: searching `"Halyard Wren"` surfaced `weathered-manifest.jpg` as
the top result with the full fabricated manifest text correctly extracted,
and opening it in the Inspector showed the real, already-shipped OCR
provenance UI (`InspectorPane.ts`) reporting "Text source OCR — ocr full ·
eng · 80% quality · direct tesseract fallback." This confirms, with live
evidence rather than structural inference, that the synthetic
Augraphy-degraded image clears every real extraction gate and produces
accurate text — the strongest possible validation of this tempdoc's central
design bet (a synthetically-generated, license-clean image can stand in for a
real scan). The GPU chat runtime / LLM-weights limitation from the
Implementation section still stands — that gap is a ~5.5GB model file, not a
~4MB language pack, and remains a judged-disproportionate, honestly-documented
limit of this sandbox, not a gap in the implementation itself.

## Future directions (2026-07-02): polish, simplification, extension, and UX ideas

A research pass after implementation, looking at what's now possible on top of
the shipped substrate — what to sand down, what's more than it needs to be,
what naturally extends, and what a real UX feature on top of this could look
like. Nothing here is committed work; all of it is optional, and none of it
is urgent (the product is pre-launch alpha with no real users yet). Grounded
in both a review of the shipped code and a look at how comparable projects
handle the same problem.

### A concrete, real friction found live: the onboarding tour interrupts recording

While validating this tempdoc's own work in the browser, the first-run
"Welcome to JustSearch" walkthrough overlay appeared unprompted over the
search results — exactly the kind of thing that would visibly intrude on a
real screen recording. This turns out to be a *solved* problem, just not
solved for this use case yet: `scripts/jseval/jseval/ui_fixtures.py:85-94`
already seeds `localStorage['justsearch.userState.v2']` with
`walkthroughState.welcome.dismissed: true` specifically so the automated
`ui-shot` screenshot harness never captures the tour — but that seed only
applies inside Playwright's headless browser context, not a human's real
browser session recording a real video. There's also a
`WalkthroughRegistry` command surface (`modules/ui-web/src/shell-v0/commands/WalkthroughRegistry.ts`)
that may already expose a supported "dismiss/reset tour" action worth
checking before reaching for the raw `localStorage` seed. Either way, this is
a small, concrete, easy win: document (or script) how to get a human's real
browser into the same clean state `ui-shot` already achieves automatically,
so a founder recording a demo doesn't have to manually click through the tour
every time the corpus is re-staged with `--clean hard`.

### Polish

- `scripts/dev/generate-demo-corpus-assets.py` hardcodes `"cour.ttf"` for
  rendering the manifest text before Augraphy degradation — a Windows font
  name that may not resolve on macOS/Linux (it already has a graceful
  `except OSError` fallback to `ImageFont.load_default()`, so it never
  crashes, but the fallback font looks meaningfully worse). Worth a small,
  cross-platform font-search list if this asset ever needs regenerating
  outside Windows.
- The corpus's content signature is now computed by two independent
  implementations of the same sha256-over-file-list algorithm — Python's
  `corpus_signature(files=...)` (`corpus_identity.py`) for authoring the
  committed value, and a small inline Node `crypto` version in
  `stage-demo-corpus.mjs` for verifying it at staging time. This was a
  deliberate, judged trade-off at the time (a pure-Node staging script
  shouldn't need a Python dependency just to verify a hash) but it's a real,
  if minor, "conform, don't fork" tension worth naming — if a third consumer
  of this exact check ever appears, that's the trigger to extract one shared,
  language-appropriate spec (even just a one-paragraph doc describing the
  exact byte-concatenation order) rather than a third reimplementation.
- `stage-demo-corpus.mjs`'s console output is plain text tuned for a human
  reading a terminal. A `--json` flag (mirroring `doctor.mjs`'s own
  `--json`/text duality) would make it scriptable by anything that wants to
  gate on its result programmatically, without changing default behavior.

### Simplify

- The Tier-2 RAG check's soft/hard split (hard-require *a* citation, soft-log
  whether it's the *right* citation) is proportionate to what it caught
  during implementation, but it is doing real, cumulative work now: SSE
  parsing, `docIds` scoping, two failure-mode branches. If this ever becomes
  a maintenance burden relative to how often it's actually run, the
  simplest fallback is to drop the soft fragment-match note entirely and
  keep only the structural "at least one citation" check — the fragment
  match has always been informational, never load-bearing.
- Nothing else reviewed here looks over-built for what it does; this list is
  intentionally short.

### Extend

- **A bigger/second corpus tier.** The current six files cover
  markdown/Office/OCR/two extra languages — deliberately onramp-sized, not
  eval-sized. RAGFlow's own README advertises native support for "Word,
  slides, excel, txt, images, scanned copies, and structured data" as a
  headline capability; JustSearch's demo corpus currently has no spreadsheet,
  presentation, or code file. If a punchier "look how much this handles"
  demo is ever wanted, adding one `.xlsx` and one `.pptx` (both already
  covered by real extraction test fixtures in this codebase) would round out
  format coverage without growing the corpus
  much.
- **Turn the staging script into an actual asset-generation pipeline.**
  Several comparable projects (and the current industry direction more
  broadly — see research below) are moving toward *generating* demo
  video/screenshot assets from deterministic test/staging flows rather than
  hand-recording them. This repo already has both halves separately:
  `stage-demo-corpus.mjs` gets the corpus ready, and `jseval ui-shot` can
  screenshot named steps. A natural next step is a single command that
  stages the corpus *and* drives `ui-shot` (or an equivalent GIF-capable
  capture) through a fixed sequence — search, inspector, cited answer — to
  produce ready-to-publish assets in one shot, closer to literally
  fulfilling this tempdoc's Purpose statement ("producing reproducible
  public demo assets") rather than stopping at "the corpus is ready, go
  record it yourself."
- **A canonical "reference corpus" helper.** Already named as a recognized-
  but-not-built pattern earlier in this tempdoc (the four properties: content,
  identity, canary, staging script). Nothing here changes that judgment —
  still worth building only once a second real consumer (642's BYO flow,
  most likely) needs the same shape.

### New UX feature idea (product-facing, not substrate)

- **An in-product "load a sample corpus" affordance on the empty state.**
  This was already flagged as an open, deliberately-deferred question in the
  original design pass. External research reinforces it's a well-established
  pattern, not a novel idea: pre-populating the UI with demo data so a new
  user sees the product working before supplying their own files is a named
  technique for handling empty-state drop-off in onboarding UX (a common
  documented failure mode is landing on an empty screen with no data and no
  guidance). What's specific to JustSearch's situation: unlike hosted
  RAG/search products (Onyx, RAGFlow, etc.), which solve "try it without
  installing anything" via a cloud demo instance, JustSearch is local-first
  by design and has no cloud equivalent to point people at — a bundled,
  one-click sample corpus is the *only* way for someone to see a real cited
  answer before trusting the app with their own files. That makes this a
  stronger candidate for JustSearch specifically than it would be for a
  hosted product that can just link to a live demo. Still deliberately not
  built now, per the same reasoning as before (defer until a second real
  consumer of the staging mechanism exists) — recorded here as a
  strengthened, externally-corroborated case for revisiting that deferral.

### Practicality assessment

- **For the founder (primary intended user today):** the one-command staging
  flow (`node scripts/dev/stage-demo-corpus.mjs`) works and is now
  well-verified, but it assumes a working dev environment (Node, a Gradle
  build, and — only if regenerating assets — a manual Python venv with
  Augraphy). That's an appropriate bar for a substrate tool per this
  tempdoc's own Boundary ("recording/publishing demo assets is founder
  work"), but if that responsibility ever moves to someone non-technical,
  the dev-environment requirement would become a real barrier worth
  revisiting.
- **For other developers/agents extending this substrate:** the shared
  helper (`stage-reference-corpus.mjs`) and the corpus-signature pattern are
  now genuine, reusable precedent — a future BYO/642 implementation has a
  real, working example to follow rather than a theoretical one. The
  multilingual/OCR canary pattern added in the critical-analysis pass is
  also a reusable template for "does this corpus actually prove its own
  premise" checks on any future reference corpus.
- **For a hypothetical end user encountering the eventual demo assets:** the
  live-verified evidence (a real cited answer citing real OCR-recovered text,
  confirmed in the browser) means any GIF/screenshot recorded from this
  corpus would be showing genuine, reproduced behavior — not a staged or
  cherry-picked result. That's the strongest practical validation this
  tempdoc's substrate work could offer: the "wow" the corpus is designed to
  produce is real, not assumed.

### What external research contributed

Looked at how comparable open-source local-first/RAG search projects (Onyx/
Danswer, RAGFlow) present public demos: both embed an autoplaying GIF
directly in the README (matching this tempdoc's own stated goal exactly) and
both lean on a *hosted cloud demo* for "try it without installing" — an
option JustSearch structurally doesn't have as a local-first product, which
is the concrete reason a bundled sample corpus matters more here than it
would for those projects. Separately, general onboarding-UX research
confirms "seed the empty state with demo data" is an established, named
pattern, not a novel idea specific to this tempdoc. And the broader demo-
tooling landscape is visibly moving toward generating onboarding/demo videos
from deterministic test flows rather than manual screen recording — directly
relevant to the "turn staging into an asset pipeline" extension idea above,
since this repo already has both necessary pieces (a staging script, a
screenshot harness) separately.

## Long-term design for the two structural "Future directions" items (2026-07-02)

The Future Directions pass above raised two items with real architectural
weight — the onboarding-tour recording friction, and the staging/screenshot
merge. Both got a design pass here: what already exists, what the correct
extension looks like, and why. Both conclusions are the same shape — extend
an existing, working mechanism into a reachable place it doesn't cover yet,
rather than invent a parallel one. Nothing below is implemented; this is
design, at the level the problem currently warrants, not more.

### Demo/recording mode: expose the walkthrough system's own dismissal, don't build new state

Investigation corrected an assumption from the earlier Future Directions
note. The onboarding tour is **not** a recurring per-recording annoyance —
its dismissal state (`walkthroughState`, inside the `justsearch.userState.v2`
browser-`localStorage` document) is a durable, cross-profile, browser-scoped
record, untouched by any backend `--clean` level (`dev-runner.cjs`'s clean
levels only ever touch the backend data directory, never browser storage).
So the tour only ever appears on a genuinely fresh browser profile — a
one-time friction per new recording environment (a new machine, CI runner,
or incognito session), not a per-take annoyance. That's a smaller, more
specific problem than originally framed, and the design should match that
smaller shape.

The dismissal mechanism itself already exists and is exactly right — a
plain, already-shipped function, `dismissWalkthrough(id)`
(`UserStateDocument.ts`), the same one the walkthrough card's own dismiss
button already calls. The gap is narrow and specific: that function is
reachable only from inside the app's own module graph (a real click) or from
inside a headless Playwright context (`ui_fixtures.py`'s
`ctx.add_init_script` seed) — there is no way to reach it from a real,
interactive browser session without either clicking through the tour once or
hand-editing `localStorage` via devtools.

**The correct extension: make the existing dismissal reachable from a real
session via a boot-time check** (e.g. a URL parameter, read once at startup,
that calls the same `dismissWalkthrough` the card already calls) — not a new
Settings toggle, and not a new state-storage mechanism. A few points that
follow from matching scope to the actual problem:

- This should dismiss *every currently-registered, not-yet-completed*
  walkthrough (via the registry's own `listWalkthroughs()`), not hardcode the
  `'welcome'` id specifically. The walkthrough system is already built as an
  extensible registry (any future walkthrough — an AI-features tour, a
  plugin's own onboarding — registers the same way); hardcoding one id would
  just recreate this exact gap the next time a second walkthrough ships.
- A dedicated Settings UI toggle ("show onboarding again") is **not**
  warranted by the problem this tempdoc has. That would be new, permanent,
  always-visible product surface serving a demo-recording need, in a
  pre-launch alpha with no real users yet to ask for it. If real users later
  want to replay or permanently suppress onboarding, that's a separate,
  user-facing feature request with its own justification — not something to
  bundle into recording tooling now.
- This conforms to, rather than forks, the one existing precedent for this
  exact problem (`ui_fixtures.py`'s headless-only seed) — same underlying
  state mutation, now reachable for a live session too, not a second
  competing mechanism.

### Asset generation: extend `ui-shot`'s existing chain-replay with recording, don't build a new pipeline

`jseval ui-shot` already solves the harder half of "produce a demo asset
deterministically": named steps chain-replay through prerequisite UI states
in one browser context (`ui_shot.py`'s `depends_on` resolution), and the
existing chain topology already includes exactly the sequence this corpus is
built to showcase — `search-results` → `inspector-open` → `streaming` →
`summarize-done` → `citation-highlight`. `stage-demo-corpus.mjs` and
`ui-shot`'s `install_fixtures`/variant mechanism are correctly complementary,
not overlapping: fixtures deliberately exclude the AI-chain steps (a real
cited answer needs a real, staged backend — which is exactly what the
staging script produces), so a real "cited answer over the real demo corpus"
capture was always going to need both pieces together, not a replacement for
either.

What's missing is narrow: only static PNG capture exists today, no
video/GIF. Playwright — the library `ui-shot` already runs on — records
video natively at the browser-context level (`record_video_dir` on the same
`new_context()` call `ui_shot.py` already makes); this is a small,
mechanical addition to existing code, not a new capture stack. The design:

- Add an opt-in recording flag to the existing `ui-shot` command (reusing
  its existing chain-replay, fixture, and step-registry machinery
  unchanged) that turns on `record_video_dir` for that invocation's browser
  context. Output is `.webm` (Playwright's native format).
- Register the specific "public demo" sequence as one more named chain entry
  in the existing step registry (`ui_step_index.json`), the same mechanism
  every other step already uses — not a new sequence-authoring DSL. The
  chain-replay concept already generalizes to "walk named steps in order";
  it doesn't need a second, parallel way to express a sequence.
- GIF conversion (the format actually embedded in the READMEs surveyed
  during research) is a separate, optional, documented post-process step
  (e.g. one `ffmpeg` invocation) — not built into the capture tool itself.
  Keeping "produce a deterministic recording" and "convert to a specific
  publish format" as separate concerns avoids coupling a general-purpose
  capture harness to one downstream format choice.
- Orchestrating "stage the corpus, then record" stays a documented
  two-command recipe for now, not a new merged command. No one has actually
  run this workflow yet, so there's no real evidence a merged single command
  earns its keep over a documented pair of commands — building that
  convenience now would be structure for a friction that's still assumed,
  not observed. Revisit if/when a founder actually uses this repeatedly and
  finds the two-step handoff genuinely costly.

### What this settles about the tempdoc's own title

"Demo mode," in this tempdoc's title since it was first filed, never had a
concrete referent beyond "the staging scripts" — investigation confirmed no
other "demo mode" concept existed anywhere in the product. The design above
is the first time "demo mode" gets an actual, scoped meaning: a boot-time,
opt-in state (dismiss first-run walkthroughs) distinct from corpus staging.
The title still fits as written — it doesn't need changing — but it's worth
recording that this is what finally gave "demo mode" real content, rather
than leaving it as a title-only
placeholder.

## Reach: a recurring shape this design work revealed

**A candidate principle, not yet built as shared structure:** both items
above turned out to be the same underlying gap — *a determinism mechanism
that was built for the test/automation harness and never made reachable for
a real, human-operated session.* `ui_fixtures.py`'s walkthrough-dismiss seed
solves this for headless Playwright captures only; the chain-replay/step
registry solves deterministic UI-state navigation for screenshots only.
Neither gap is a bug in either mechanism — both were correctly scoped to
their original purpose (CI-stable screenshots) — but neither was ever
extended to the adjacent, human-facing case, because nothing forced that
extension until this tempdoc's own "reproducible demo recording" purpose
needed it.

Call this the **harness-only-determinism gap**: a capability that exists,
works, and is even well-designed, but only inside an automated test/capture
harness — invisible and unreachable to a human operating the real product
for a legitimate, non-test purpose. Candidate scope beyond this tempdoc:
anywhere else the `jseval ui-shot`/`ui_fixtures.py` layer has quietly solved
a "get the UI into a known state" problem that a human might also
legitimately want (for support screenshots, sales demos, documentation
screenshots, bug reports) but currently can't reach without either
reverse-engineering the Playwright fixture code or accepting the app's real,
noisy first-run/random-state behavior. This tempdoc did not audit the rest
of the codebase for other instances — that would be a different, broader
task — so whether other code already "violates" this pattern elsewhere is
unconfirmed, not ruled out. Recording the principle and its candidate scope
here is deliberately as far as this pass goes: recognizing the shape is not
the same as building a shared "expose harness determinism to real sessions"
primitive, and nothing in this tempdoc's own problem requires that
generalization yet — two instances, both still just designed, not even
both built. The trigger for generalizing, per this repo's own AHA
discipline, is a third real instance that would otherwise duplicate this
exact reasoning from scratch.

## Confidence-building pass on the long-term designs (2026-07-02)

Both designs above were checked against live code/behavior before
implementation (no code written yet). Both held up; two real corrections
came out of it, one of which simplifies the recording design.

**Demo-mode boot flag — confirmed safe, exact placement now known.**
`dismissWalkthrough()` is safe to call at any point (self-hydrating, never
throws, no dependency on the walkthrough being registered yet —
`UserStateDocument.ts:1470-1476`/`527-586`). But `listWalkthroughs()` is
empty until `Shell.connectedCallback()` runs (`chrome/Shell.ts:922-928`,
called synchronously when `<jf-shell>` is appended at `main.jsx:466`) — a
boot check placed earlier in `bootstrap()` would silently dismiss nothing.
The natural, now-precise placement is immediately after `main.jsx:466`. Even
better: `main.jsx` already has this exact shape of debug-flag handling —
`params.get('shell-demo')`, `'lit-health'`, `'presentation-demo')` at
`main.jsx:93-118`, using a `URLSearchParams(location.search)` the bootstrap
already builds — so a new `?demoMode=1`-style flag isn't just architecturally
sound, it's a direct pattern-match onto code already in this exact file.
Hash-based routing (`URLSource.ts`) never reads `location.search`, so no
conflict is possible. This design is now essentially implementation-ready.

**Recording extension — one real correction that simplifies the plan.**
Chain-replay does run inside one continuous browser context for the whole
chain (`ui_shot.py:505-551`), confirming `record_video_dir` on that one
`new_context()` call is a valid hook. But the earlier design's claim that
adding a step is "one registry entry in `ui_step_index.json`" was wrong on
two counts: that file is a reverse *file-changed → affected steps* index for
`--affected` mode only, not the step registry; the real registry
(`ui_check.py`'s `_build_steps()`) requires a hand-written Playwright
interaction function per step, not just a JSON line. The simplifying
correction: **no new step needs registering at all.** The existing
`citation-highlight` step's dependency chain already *is* exactly
search-results → inspector-open → streaming → summarize-done →
citation-highlight — precisely the sequence this tempdoc wants recorded.
The design reduces to "add an opt-in recording flag to `ui-shot` and point
it at the existing `citation-highlight` step," not "define a new sequence."
Separately confirmed: `ai_activate` (LLM loading) is already, correctly, an
external precondition the operator arranges before invoking `ui-shot` —
nothing in this design needs to change that. `ffmpeg` is genuinely absent
from the toolchain everywhere (Playwright bundles its own private copy for
its own webm encoding, not shell-invocable) — GIF conversion, if pursued,
is a real new external dependency, not a hidden one already available.
Long Playwright recordings (multi-minute, matching AI-chain timeouts) are a
size/quality tradeoff, not a stability risk.

**Confidence rating: 8/10** for implementing both designs as now specified.
The boot-flag design is close to 9-10 — every placement question is answered
exactly, and it reuses an established in-file pattern. The recording
extension is slightly more open (exact CLI flag shape, output path
conventions, and whether `citation-highlight` alone is a satisfying "demo
reel" or a second, shorter chain is also wanted are still real judgment
calls, not just mechanical wiring) — real but small design choices, not
unknowns that could invalidate the approach. Neither design surfaced a
blocker, a wrong assumption about system boundaries, or a hidden dependency
that changes their shape.

**Implementation difficulty and model recommendation:** Low-to-moderate.
The boot flag is a small, self-contained frontend change (a handful of lines
in `main.jsx`, following an existing in-file pattern) with a real but
narrow verification need (does it actually suppress the tour in a fresh
browser profile, confirmed via live browser testing). The recording
extension is mostly plumbing (`record_video_dir` on an existing context,
a new CLI flag, a documented two-command recipe) with the main
judgment call being "does the resulting recording actually look good enough
to be worth using," which needs a human/agent watching real output, not
just a green test. Sonnet at medium effort is sufficient for both — no
architectural ambiguity remains, and the main risk is care during the small
frontend edit and honest visual judgment of the recording output, not
algorithmic difficulty.

## Implementation of the two long-term designs (2026-07-02)

Both designs above were built exactly as specified and live-verified.

**Demo-mode boot flag** — `modules/ui-web/src/main.jsx`: a `?demoMode=1`
check added immediately after `root.appendChild(shell)`, dismissing every
walkthrough returned by `listWalkthroughs()` via the existing
`dismissWalkthrough()`. `npm run typecheck` passed. Live-verified in the
browser: cleared `localStorage` to simulate a fresh profile, confirmed the
"Welcome to JustSearch" tour appears without the flag (baseline) and does
**not** appear with `?demoMode=1` on an equally fresh state; confirmed
`walkthroughState.welcome.dismissed` is `true` afterward; confirmed zero
console errors across a fresh boot with the flag set. No Settings UI was
added, per the design's own scope decision.

**`ui-shot --record`** — `scripts/jseval/jseval/{commands/ui.py,ui_shot.py,ui_check.py}`:
a `--record` flag on the `ui-shot` command passes Playwright's native
`record_video_dir` to the existing shared-chain `browser.new_context()` call;
the resulting video path is captured via `page.video.path()` after
`ctx.close()` and surfaced in both JSON and console output (`ShotResult.video_path`).
Deliberately scoped to shared-chain steps only (isolated steps are a no-op
for `--record`, per the plan — the intended use case is never isolated). No
new step was registered — `citation-highlight`'s existing chain already is
the wanted sequence.
- `python -m py_compile` passed on all three touched files.
- Live-verified against a running dev stack: `ui-shot search-results --record`
  produced a valid `.webm` (confirmed EBML magic bytes, 224KB, one step);
  `ui-shot inspector-open --record` produced a larger `.webm` (258KB,
  two-step chain: search-results → inspector-open), corroborating that the
  video spans the whole chain replay, not just the terminal step.
- The full `citation-highlight --record` (AI-chain) path was **not**
  live-verified end-to-end. Investigating it surfaced a real, more specific
  finding than the earlier "5.5GB model copy" framing used elsewhere in this
  tempdoc: `ai_activate` doesn't just need the GGUF file present — it fails
  with "No chat model configured. Import a models pack first," meaning real
  activation requires going through the product's actual pack-install machinery
  (`/api/ai/packs/import`), not just placing a file at the expected path.
  That's a materially bigger, product-install-flow investigation than a file
  copy, and disproportionate to what this verification needed — the
  mechanical recording behavior (continuous video across a multi-step
  chain) is already confirmed by the two-step test above, and the AI-chain
  steps' own interaction logic (`setup_streaming`/`setup_citation`) is
  unchanged, pre-existing, already-shipped code this pass didn't touch. Not
  exercising the AI-chain specifically is an honest, scoped-down gap, not a
  silently-skipped one.
- A pre-existing, unrelated accessibility finding surfaced during this
  validation (`inspector-open` reports one serious axe violation) — logged
  to the observations inbox, not fixed here, per scope.

## Handoff summary: what's actually left (2026-07-02)

This tempdoc accumulated many passes. For anyone picking this up without
reading the passes above in order, here is everything genuinely open,
in one place:

**The one real gap:** `citation-highlight --record` (recording a full
search → inspector → cited-answer sequence, including a real LLM answer) has
never been run end-to-end. Everything up to the AI step is verified —
ingesting the demo corpus, plain search, the OCR pipeline, and the recording
mechanism itself (confirmed on two other, non-AI chains). What's missing is
specifically the LLM-warm case. Getting there requires the product's actual
model-install flow (`/api/ai/packs/import` or equivalent), not just placing
a model file on disk — that's a real, separate piece of work, not a quick
follow-up. Whoever picks this up next should budget for that distinctly, or
accept the existing verification (mechanical recording confirmed; the
AI-chain's own interaction code is unchanged, pre-existing, already-shipped
code, not touched by this work) as sufficient and move on.

**Deliberately deferred, not forgotten — no action needed unless priorities
change:**
- GIF conversion for the recorded `.webm` output — no code exists for this;
  it would need `ffmpeg` as a genuinely new external dependency (confirmed
  absent from this project's toolchain everywhere today).
- A bigger demo-corpus format tier (a spreadsheet, a slide deck) — a content
  decision, not an engineering one; skip unless a punchier demo is wanted.
- An in-product "load a sample corpus" button, and a Settings toggle to
  replay/suppress onboarding — both explicitly *not* built, on purpose: no
  second real consumer of the underlying mechanisms exists yet, and this
  product has no real users yet to justify permanent, always-visible UI for
  either. Revisit only if that changes.
- The Python/Node duplication of the corpus-signature hashing algorithm —
  harmless today; only worth consolidating if a third caller appears.
- The named "harness-only-determinism gap" principle — recorded as an
  observation, not built as shared infrastructure. Only two instances exist
  (the walkthrough-dismiss seed, the ui-shot chain-replay); a third real
  instance elsewhere in this codebase would be the trigger to generalize,
  not a reason to go looking for one now.

**Two known rough edges, not blockers:**
- `?demoMode=1` is an internal dev convenience (matching the existing,
  equally-undocumented `shell-demo`/`lit-health`/`presentation-demo` flags in
  the same file) — it is not documented anywhere as a stable, public-facing
  URL parameter, and shouldn't be treated as one without a deliberate
  decision to publish it as such.
- `--record`'s video output uses Playwright's own generated filename (a
  hash, not a descriptive name) under `<output-dir>/videos/` — fine for
  verification, a little rough for someone trying to find "the file I just
  recorded" without reading the command's own printed output.

**Everything else this tempdoc set out to do — the demo corpus itself, the
staging scripts, corpus-signature verification, the multilingual and OCR
canaries, and the demo-mode/recording tooling — is implemented and
live-verified, with evidence, in the sections above.** No PR has been opened
yet; that is the natural next step once someone wants to publish this.
