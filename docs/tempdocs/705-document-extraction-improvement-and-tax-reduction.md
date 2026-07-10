---
title: "Document-extraction improvement & extraction-tax reduction: a general direction frame that asks whether and how JustSearch should change how it turns files into indexable content — holding both faces of the 'extraction tax' (the QUALITY tax: structure/content lost at extraction → nDCG lost on complex documents; and the COST tax: time/compute spent per document at indexing time) as one problem, with the HOW left deliberately open. Picks up the Tier-3 slot that tempdoc 252 shipped Tier 1 for and explicitly deferred, per 252's own 'open a new tempdoc citing this one' resume policy. Routes already-owned pieces (677 output-quality/abstention, 686 real-doc corpus + parse pressure, 691 enrichment throughput, 607 routing authority) to their owners rather than absorbing them."
type: tempdocs
status: "open — takeover investigation complete (2026-07-10), verdict: WAIT-FOR-EVIDENCE. The question is real but currently unanswerable at this doc's own credibility bar: every load-bearing question is gated on 686's real-document corpus (doesn't exist) and 677's confabulation defect (measuring a defective target). Reopen triggers recorded in §Takeover investigation. No design or implementation performed; awaiting founder decision on sequencing (686/677 first)."
created: 2026-07-10
updated: 2026-07-10
author: agent (Opus orchestration) — opened at founder request as the takeover frame for an extraction-quality/tax investigation; not a design. Cites 252's explicit resume policy ("if this work should resume, open a new tempdoc citing this one by title").
category: indexing / extraction / ingestion-quality / performance / search-quality
related:
  - 252-ingestion-quality                         # THE predecessor — measured the extraction quality tax (15–33% nDCG on OHR-Bench), shipped Tier-1 SAX structure preservation (StructuredContentExtractor), and explicitly deferred Tier-3 (Docling-class layout) as opt-in/non-goal with a "open a new tempdoc citing this one" clause. This doc is that successor. Status: done.
  - 677-vdu-extraction-abstention-gate            # OPEN — owns extraction OUTPUT quality (the vision path confabulates on unreadable scans; honest can't-read vs confident fabrication). Route here, do not absorb.
  - 686-real-pdf-corpus-and-tika-pressure-measurement  # OPEN — owns the measurement substrate: no real binary-document corpus exists, so Tika/PDFBox/POI parse pressure AND real extraction quality are both unexercised by evals. Any verdict here needs this. Coordinate (shared real-doc corpus need with the 624/704 eval campaign).
  - 691-corpus-build-throughput                   # OPEN — owns enrichment throughput; the COST-tax side that any quality change trades against (adding extraction work raises this).
  - 607-vdu-ocr-extraction-logic-analysis         # ACTIVE — owns Tika/OCR/VDU ROUTING authority (which path a document takes), not output tier. Adjacent owner, not this doc's concern.
  - 671-tika-ocr-skip-routing-misclassification   # done — shipped routing/reason-code fix; evidence of how fragile the extraction path has been. History, not open work.
  - 672-vdu-offline-coordinator-bootstrap-wiring  # done — shipped the VDU-wiring fix that first made the offline path run at scale. History.
principle: "'better extraction' and 'cheaper extraction' are two faces of one lever, not two projects: what you extract (structure, tables, real text vs flattened/empty/confabulated output) and what it costs to extract it trade against each other, so the decision must price both together. Problem first, mechanism open — the current path may already be right, an incremental extension may win, or a different shape entirely may; the doc exists to find out, not to ratify a preselected answer."
---

> NOTE: Noncanonical working tempdoc. STUB: goals and context only — **no design decisions, no
> implementation specifics, and deliberately no prescribed mechanism.** Verify every claim against
> `main` + canonical docs + live measurement before treating it as truth. This doc asserts that the
> extraction tax is *felt and undiagnosed*, not that any particular fix is warranted.

# 705 — Document-extraction improvement & extraction-tax reduction

## Purpose

**Determine if and how to improve JustSearch's document extraction and reduce the extraction tax.**

Problem first; solution open. The doc owns the question and the decision — not a predetermined build.

## The idea (why this is one problem, not two)

"Extraction tax" has two faces, and this doc holds both:

- **The quality tax** — structure and content lost when a file is turned into indexable text, so
  search degrades on complex documents (tables, multi-column layouts, scans/image-PDFs, and files
  that extract to empty or wrong content). Tempdoc 252 *measured* this face directly: a 15–33% nDCG
  loss on OHR-Bench attributable to text extraction destroying document structure — its stated
  "single largest quality bottleneck."
- **The cost tax** — the time and compute extraction spends per document at indexing time (Tika /
  PDFBox / POI parsing, OCR, and the enrichment that follows). This is the throughput side (691's
  domain) and it is what any quality change trades against: extracting *more* structure usually
  costs *more* per page.

The two faces are the same lever seen from opposite ends. The point of the doc is to find where
JustSearch actually overpays — on quality, on cost, or both — and what the best way to pay less is,
**without assuming the answer**.

## Why this doc exists now (public-safe grounding)

1. **252 predicted and deferred exactly this.** 252 ("Ingestion Quality — Document Processing
   Pipeline") shipped **Tier 1**: a SAX-based `StructuredContentExtractor` that preserves Tika's
   XHTML structure (tables, headings, lists) instead of flattening via `parseToString()`. It
   explicitly scoped **Tier 3 (Docling-class layout understanding) as opt-in / a non-goal**, and
   closed with: *"If this work should resume, open a new tempdoc citing this one by title."* This
   is that successor. The Tier-1 path handles documents Tika **can** structurally parse; the
   documents it **can't** (scans, image-PDFs, complex tables) are the deferred territory — one of
   the directions this doc may or may not pick up.
2. **The output side is open and known-defective.** 677 documents the VDU/vision path confabulating
   plausible-but-wrong text on unreadable scans and indexing it as real content, with no confidence
   check anywhere on the output path. That is extraction quality of a different kind (honest
   absence vs confident fabrication) and it is unresolved.
3. **We cannot currently measure any of this.** 686 establishes that no real binary-document corpus
   exists in the eval harness — every "PDF" dataset is pre-extracted text — so real Tika/PDFBox/POI
   parse pressure *and* real extraction quality are both unexercised. Any verdict this doc reaches
   needs that measurement substrate to be credible.

## Scope stance (the HOW is open)

This doc does **not** prescribe a solution and does not preselect a mechanism. 252's SAX tier, a
Docling-class layout tier, an output-abstention gate, a cheaper/faster parse path, or a reframe of
the problem entirely are **inputs to consider, not the answer**. The investigating agent is free to
find entirely new options, to conclude the current path is already right, or to close a candidate
direction with a recorded reason. What this doc owns is the investigation and the decision.

## What this doc owns vs routes

**Owns:** the extraction-tax question as one problem; the quality-vs-cost trade framing; and the one
genuinely-unowned decision — whether to extend extraction beyond the shipped Tier-1 structure path
(and if so, toward what).

**Routes (do not absorb or reopen):**

| Concern | Owner | State |
|---|---|---|
| Extraction *output* quality / can't-read abstention | **677** | open — stays there |
| Real-document corpus + Tika/parse-pressure measurement | **686** | open — the measurement substrate (shared real-doc need with the 624/704 eval campaign — coordinate) |
| Enrichment throughput / the cost tax | **691** | open — the other end of the trade |
| Tika/OCR/VDU routing authority | **607** | active — routing, not output tier |
| Tier-1 SAX predecessor (where Tier 3 was deferred) | **252** | done — cite by title, per its resume policy |

## Goal / deliverable

A grounded verdict a founder can act on from one page: **either** a recommended direction for
changing extraction, with its quality-vs-cost trade priced honestly and a measurement plan (via 686)
to prove the win before anyone builds it; **or** a recorded decision to leave extraction as-is, with
a revisit-trigger — instead of the current state, where the tax is felt but never diagnosed or
priced.

## Success condition (falsifiable)

Done when a founder can, on one page, either greenlight a scoped direction with a known trade or
close the question with a reason and a revisit-trigger. Not done while "extraction could be better
and/or cheaper" remains an unquantified intuition.

## First questions for the investigating agent (starting points, not a plan)

1. **Where is the tax actually paid?** Attribute before prescribing (the 647/691 method): on a real
   mixed corpus (686), how much of the quality loss is empty/wrong extraction vs. lost structure vs.
   confabulated output (677)? How much of build cost is parse vs. enrichment (691 has a baseline)?
2. **Did Tier 1 land the win 252 predicted?** `StructuredContentExtractor` is wired
   (`DefaultWorkerAppServices`); `ContentExtractor` (flat `parseToString`) still exists in the tree.
   Which path do real documents actually take today, and what did Tier 1 measurably recover?
3. **What are the candidate directions and their trades?** Enumerate without preselecting — extend
   Tier 1, add a layout/table tier, harden the output path (677), reduce cost (691), or reframe —
   each with a rough quality gain and cost delta on target (local, modest, cross-platform) hardware.
4. **What measurement would make a verdict credible?** Define, with 686's owner, the real-document
   corpus and the extraction-quality metric a recommendation would be gated on.

## Boundary (what this doc does NOT own)

Routing authority (607), output-abstention design (677), the corpus build (686), throughput
optimization mechanics (691), and VDU vision-model *selection*/pinning (374). This doc coordinates
across them and owns only the top-level "if and how" — it must not silently absorb an owned concern,
and it must not ship a mechanism decision that belongs in one of the routed docs without that owner's
sign-off.

---

## Takeover investigation (2026-07-10, worktree `takeover-705`)

Investigation pass per `/takeover`: full read of 252/346-llama/677/686/704, subagent-verified reads
of 607/691/671/672, a codebase map of the live extraction path with `file:line` evidence, and a
landscape re-check against 252's March-2026 research. **No design or implementation performed.**
Verdict at the end.

### F1 — The "genuinely-unowned decision" has already been decided twice, with recorded reasons

The stub frames "layout/structure understanding beyond Tier-1" as the one unowned decision. The
history is stronger than "deferred":

- **252 didn't just defer Tier 3 — it ran the bake-off and picked a winner.** Phase 5 measured
  Docling end-to-end (nDCG 0.8621 vs Tika 0.7947 vs clean 0.9487 on OHR-Bench), then **cancelled
  Docling** (Python dep, 8.4 GB models, VRAM contention) in favor of Phase 6: VLM extraction via the
  existing chat model (Qwen 3.5 9B + mmproj through the VDU pipeline). Experiment B (2026-03-28)
  validated the choice: VLM word-overlap 81.9% vs Docling 71.5% vs Tika 66.3%; VLM 2× better than
  Docling on hard pages (66.9% vs 33.8%); estimated tax reduction 15.1% → ~5% if VLM replaces Tika
  on PDFs. Production integration was delegated to 346, and the VDU path now runs at scale
  (672 shipped wiring + idle auto-trigger + batch mode, 2026-07). **The de-facto Tier 3 exists and
  is the VDU/VLM path** — `.claude/skills/search-quality/SKILL.md:427` records it: "VLM extraction
  via existing chat model (Qwen 3.5) is the chosen path (252). Docling integration cancelled."
- **607 §20 re-examined the alternatives in June 2026 and declined again**, with a recorded reason
  and revisit condition: Docling/MinerU framed as "future enrichment backend, not a replacement for
  baseline OCR"; ColPali/ViDoRe visual retrieval explicitly gated on "a storage/performance budget
  and a local evaluation set."

So what this doc actually owns is narrower than the stub implies: **the decision to REOPEN
252/607's choice** — legitimate only against new evidence, of which two pieces exist (F4, F5).

### F2 — Tier-1 wiring verified; its PDF win was small by 252's own numbers; "did it land" is unmeasurable today

- Every indexed document flows through `StructuredContentExtractor` unconditionally
  (`PolicyDrivenTikaExtractor.java:65,103-104` via `ExtractionSandboxFactory.inProcessStructured`,
  wired at `DefaultWorkerAppServices.java:455`). The flat `ContentExtractor` (`parseToString`,
  line 118) is **not** a live alternate path — it survives only as the exception fallback inside
  `StructuredContentExtractor.extractWithStatus`'s catch block (`StructuredContentExtractor.java:87-100`)
  plus shared static helpers/records. The stub's question 2 premise ("which path do real documents
  take") is settled: structured, always.
- Caveat the stub misses: by 252's own measurements, Tier 1's structural recovery applies to
  DOCX/XLSX/HTML; for **untagged PDFs (most real PDFs) Tier 1 adds only page-break structure** —
  "Tika Structured PDF" scored identical nDCG to the layout-detector variant (0.7947, still -16.2%).
  The PDF quality tax was never claimed to be closed by Tier 1; it was assigned to the VDU tier.
- Tables are indexed as triplet lines (`row_header, col_header = value`,
  `StructuredDocument.java:53`), headings as `##` markers — relevant when defining any
  extraction-quality metric later.
- Whether Tier 1 measurably recovered quality **on real user-shaped documents** is unknowable right
  now: every eval "PDF" dataset is pre-extracted text (686), so the question is 686-gated.

### F3 — Extraction cost is instrumented but unexercised; enrichment, not parse, dominates measured builds

- Per-document extract timing exists: `JobBatchExtractor.java:229` → `stageMs` histogram
  (`indexing.worker` pipeline), plus an `indexing.extract` OTel span and an
  `extraction.timeout_total` counter. The instrument is there; **real parse pressure has simply
  never been fed through it** (686: three fixture PDFs on the whole machine).
- On text corpora, 691 shows the cost tax lives in enrichment, not parse: enrichment-complete is
  ~12× slower than keyword-indexed; post-fix attribution = embedding 62%, NER 20.5%, SPLADE 10.6%.
  VDU is the outlier cost when it fires: 13.7s/page (Qwen3.5-9B, build 8571).

### F4 — New evidence A: the shipped Tier-3-equivalent has a known correctness defect (677)

The VDU/VLM path confabulates fluent wrong text on unreadable scans and indexes it as real content
(100% rate on `golden/synth-scan-v1`, 2026-07-03; nDCG 0.0000 was the only tell). Consequence for
this doc: **any extraction-quality measurement taken now measures a defective target** — 677's
abstention gate (or at least a characterization of when the path can be trusted) is a validity
precondition for 705's verdict pass, alongside 686.

### F5 — New evidence B: the model landscape moved since 252's research pass (routes to 374, but changes this doc's calculus)

252's R5 (2026-03-23) rejected specialized doc-parsing VLMs because none had GGUF/llama.cpp
support. That is no longer true: **PaddleOCR-VL 1.5/1.6 (0.9B) now ships official GGUF +
llama.cpp/llama-server support** (1.6 released 2026-05-28, 96.3 OmniDocBench v1.6;
`PaddlePaddle/PaddleOCR-VL-1.6-GGUF`, runs via `llama-server --mmproj`), and olmOCR-2 has GGUF
conversions. A ~0.9B specialized extractor vs the 9B chat model plausibly collapses VDU's per-page
cost by an order of magnitude while raising doc-parsing quality — attacking both faces of the tax
at once. Model *selection* stays 374's; but this is the strongest single fact justifying an eventual
candidate-direction pass here, and it should be priced in 705's eventual enumeration (Q3).
(Sources: PaddleOCR docs + HF model card + llama.cpp integration announcements, checked 2026-07-10.)

### F6 — Every load-bearing question in this doc is gated on 686 (and 704 says so programmatically)

Q1 (where is the tax paid on a real mixed corpus), Q2 (did Tier 1 land), and Q4 (what measurement
makes a verdict credible) all require the real-document corpus + instrumented ingest that 686 owns
and has not started. Q3 (candidate enumeration) is the only question actionable now, and it is
~90% already answered by 252 Phase 5/6 + 607 §20; the only new inputs are F4/F5. The same-day
sibling direction frame (704) reaches the same conclusion for the whole measurement program: "the
engine is not the bottleneck; knowing the truth about the engine is."

### Sidegoal (founder-added 2026-07-10): make scanned-PDF baseline OCR fast — evidence + design

> **SPUN OUT → tempdoc 706** (`706-scanned-pdf-ocr-throughput.md`), at founder direction, per
> 686's own out-of-scope rule ("fixing whatever the measurement finds" is a new tempdoc) and this
> doc's boundary (705 owns the question, not mechanism builds). 706 owns the design +
> implementation + the timebox/orphan liveness fix; the evidence record below stays here as
> takeover history. Add to the routing table: OCR execution performance → **706**.

The 686 run showed the extraction cost tax concentrates in scanned PDFs (minutes/doc, serial).
Founder directed a speedup investigation. Three evidence legs (code map with `file:line`, external
research, local micro-benchmark on corpus scans) produced a converging picture:

**Where the time actually goes (measured + code-confirmed):**

1. **The dominant OCR path is Tika's internal `OCR_ONLY` strategy, not JustSearch's own loop.**
   `tryOcr` → `StructuredContentExtractor.extractWithOcr` → Tika `PDFParser`/`TesseractOCRParser`
   renders EVERY page at 300 DPI GRAY and spawns tesseract per page, strictly serially — opaque to
   JustSearch, **no page cap, no dimension guard, no parallelism possible** (`PDFParserConfig`
   defaults confirmed from jar bytecode: `ocrDPI=300`, `ocrRenderingStrategy=ALL`; JustSearch never
   sets `setOcrDPI`). The observed `tika-pdfbox-rendering-*.png` temp files are this path.
   JustSearch's own `tryRenderedPdfOcr` (160 DPI RGB, hardcoded) fires only as fallback when Tika's
   pass returns blank.
2. **Raw tesseract is NOT the bottleneck**: local bench (tessdata_fast, 300 DPI gray, corpus scan
   `govdocs1-000--000164.pdf`): **~1.1s/page OCR, ~0.1s/page render, spawn+init only ~0.1s**. A
   65-page mixed PDF at ~1.2-3s/page serial ≈ 80-200s — matches the observed 35-350s stalls.
3. **Two code-confirmed redundant invocations**: (a) after Tika OCR succeeds, `tryOcr:292` runs a
   whole-file tesseract confidence pass on the raw PDF (fast-fail at best, full re-scan at worst);
   (b) in JustSearch's own fallback loops each page is OCR'd TWICE (text pass + TSV confidence
   pass = 2 spawns/page).
4. **The 60s timebox is advisory**: `TimeboxedContentExtractor` submits the whole extraction to a
   single shared single-thread executor; `future.cancel(true)` can't interrupt CPU-bound render or
   non-blocking work, interrupted tesseract children are NOT `destroyForcibly`'d (orphan leak), and
   the next document queues behind the stuck one — so logged per-doc times include inherited queue
   wait and the true ceiling is unbounded. (This is the live confirmation of the extraction-sandbox
   liveness observation.)
5. **Config duality**: `application.yaml` ships `per_file_timeout_ms: 5000, max_pages: 10`, but the
   code fallback `OcrRoutingConfig.defaults()` is `30s/50 pages`; the eval run OCR'd a 29+-page doc,
   implying the code fallback was active. Which default is authoritative needs deciding.
6. **Benchmarked levers**: 8-way parallel page OCR = **3.98×** on only 10 pages (20 logical cores);
   list-file batching = 0.94× (worthless — init is ~10% of a page); DPI reduction = modest speed
   gain with unverified quality effects (word-count anomaly) — rejected without an accuracy eval.
   External practice corroborates: process-pool-per-core, `OMP_THREAD_LIMIT=1` defensively (UB-
   Mannheim Windows builds ship OpenMP-disabled), 300 DPI GRAY PNG, tessdata_fast/OEM1.

**Design (proposed, not yet implemented):**

1. **Bypass Tika's internal OCR for PDFs entirely** (set `OCR_STRATEGY = NO_OCR` on the OCR pass
   too) and make JustSearch's own render+OCR loop the single authoritative OCR path — it is the
   only place page caps, dimension guards, parallelism, and honest budgets CAN live. Routing
   decisions (`evaluateOcrAttempt`) and reason-code semantics (671) unchanged; this changes the
   execution engine of the OCR tier, not which tier a document takes (607 untouched).
2. In the owned loop: render at **300 DPI GRAY** (up from 160 RGB fallback quality, matching the
   quality of the Tika path being replaced); **parallelize page OCR** across a bounded pool
   (~min(cores/2, 8) workers, `OMP_THREAD_LIMIT=1` per child); **one spawn per page** emitting
   `txt tsv` together (text + confidence from a single invocation — kills both double-OCR
   patterns); enforce **maxPages inside the loop** (truncate + record) and an **aggregate
   per-document OCR budget** (elapsed-time circuit breaker).
3. **Fix the orphan/queue-pileup class**: `destroyForcibly()` on interrupt in
   `OcrConfidenceExtractor`; timebox cancellation must kill the child process tree.
4. Resolve the config duality (yaml 5s/10p vs code 30s/50p) explicitly.

**Expected effect**: 65-page scan ≈ 65×1.2s/8 workers + render ≈ **~15-20s vs the current
80-350s** (>10× on the documents that matter); fast docs unaffected. Quality expected neutral-to-
better (same engine, same-or-higher DPI/colorspace than each replaced path) — verify with
before/after on the corpus scan sample + `VduEligibilityPdfFixturesTest`.

### Verdict

**Should this be done at all?** Yes — the question is real (15.1% full-pipeline extraction tax on
OHR-Bench is the best number we have, and it is large), the doc's routing map is accurate, and
keeping one owner for the reopen-decision prevents the alternative failure mode (someone re-running
the 252 bake-off ad hoc). **But not now.** Launching the investigation today would (a) re-derive
252/607's candidate analysis without the measurement its own success condition demands, and
(b) measure a pipeline with a known index-poisoning defect (677) on a corpus that doesn't exist
(686).

**Recommended state: WAIT-FOR-EVIDENCE, with two explicit reopen triggers:**
1. **686 delivers** its real mixed-document corpus + one instrumented ingest run (gives the
   extraction_method/vdu_status distribution, the parse-vs-enrichment cost split, and a real
   extraction-quality substrate — the cheapest evidence that validates or invalidates this doc's
   premise, and it does not exist today).
   > TRIGGER 1 SUBSTANTIALLY MET (2026-07-10, same session, founder-directed): 686 executed —
   > corpus `mixed/realdocs-v1` exists (620 real files, pinned manifest), jseval ingests raw
   > binaries, and a partial instrumented run (31min/120 docs, founder-stopped) already yielded
   > the first real cost-tax attribution: **the extraction cost tax concentrates almost
   > entirely in scanned PDFs** (serial per-page render→tesseract OCR, minutes per document,
   > bimodal cadence — everything else ~free), plus a heap verdict (1g survives real parse
   > pressure with no margin; humongous parse-buffer churn, 72 evacuation-failure events/31min).
   > See 686 §Execution log. A full-corpus run + extraction_method distribution remains open.
2. **677 is resolved or characterized** enough that VDU output quality is measurable without the
   confabulation confound.

When both hold, the verdict pass this doc owns becomes days of work with credible numbers, and the
candidate list should be priced with F5 (PaddleOCR-VL-class specialized extractor via existing
llama-server infrastructure) alongside extend-Tier-1 / harden-output / leave-as-is.

**Cheapest useful advance work if the founder wants movement before 686 lands** (doc-level, no
build): define the extraction-quality metric with 686's owner (Q4) — e.g. word-overlap vs a
ground-truth subset plus the extraction_method/quality-score distribution — so 686's corpus is
built measurement-ready rather than retrofitted.

**What this displaces/duplicates:** as a decision frame, nothing — measurement is 686's, cost
mechanics 691's, output honesty 677's, routing 607's, model pinning 374's. If executed *now* as an
investigation, it would duplicate 252 Phase 5/6 and 607 §20's recorded declines. The stub's claim
of a "genuinely-unowned decision" should be read as "unowned *reopen* decision" (F1).

### F7 — Addendum (2026-07-10, same day): private-sidecar cross-check corroborates the verdict

A follow-up pass over the founder's private strategy sidecar (not published; contents summarized
here public-safely) found nothing that overturns the verdict and three things that sharpen it:

- **Same missing prerequisite, independently:** every extraction/layout discussion there names "a
  table/PDF corpus" as the still-missing gate — the same gap 686 owns. No real binary-document
  corpus exists anywhere.
- **Independent corroboration of F4:** the 624 agent-utility campaign (different harness, different
  metric than OHR-Bench) hit the same VLM-confabulation defect on its scan/OCR arm and had to rule
  that arm unmeasurable — the 677 blocker is confirmed from a second angle.
- **One more candidate for the eventual enumeration (Q3):** an internal competitive-research pass
  recommends evaluating **DeepDoc** (RAGFlow's ONNX-based OCR + layout + table-structure engine;
  local, permissive license — notably *not* Docling/MinerU/ColPali) once a table/PDF corpus exists.
  Carry it on the candidate list next to F5's PaddleOCR-VL — both are contingent on exactly the
  two reopen triggers above. Priority signals in the same material rank layout/table extraction
  below cheaper already-identified search-quality levers, consistent with WAIT-FOR-EVIDENCE.
