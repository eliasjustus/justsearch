---
title: "Scanned-PDF OCR throughput: replace Tika's opaque internal per-page-serial OCR_ONLY path with JustSearch's own parallel, capped, budgeted render+OCR loop — >10x on scanned documents — and fix the advisory-timebox/orphaned-child liveness class the investigation confirmed on the same path"
type: tempdocs
status: "open — takeover investigation complete (2026-07-10, second session): verdict GO, do it now. All load-bearing evidence claims independently re-verified at file:line against main (no divergence); design confirmed in shape with two design-time corrections required (maxPages gate-vs-truncate semantics; tryOcr's blank-content branch must not be the reroute mechanism under NO_OCR) — see §Takeover investigation. Implementation not started, awaiting go-ahead. Spun out of 705's founder-directed sidegoal per 686's own out-of-scope rule ('fixing whatever the measurement finds' is a new tempdoc). Owns OCR execution performance + the extraction timebox/orphan liveness fix; does NOT own routing (607), reason codes (671, shipped), or the extraction-tax verdict (705)."
created: 2026-07-10
author: agent (Fable, takeover-705 session) — founder-directed sidegoal after the 686 instrumented run showed the extraction cost tax concentrates in scanned PDFs
category: extraction / ocr / indexing-performance / worker
related:
  - 705-document-extraction-improvement-and-tax-reduction  # parent frame — owns the extraction-tax question; this doc is its cost-face first fix. Evidence legs recorded there (§Sidegoal).
  - 686-real-pdf-corpus-and-tika-pressure-measurement      # the measurement that exposed the cost concentration; supplies the corpus (mixed/realdocs-v1) this doc verifies against
  - 607-vdu-ocr-extraction-logic-analysis                  # routing authority — UNCHANGED by this doc (evaluateOcrAttempt decisions and tier semantics stay)
  - 671-tika-ocr-skip-routing-misclassification            # shipped reason-code truthfulness — the classifiers this doc must not regress
principle: "the OCR engine's cost model must be owned, not rented: page caps, budgets, parallelism, and honest timeouts can only exist in a loop we control — a black-box serial path is unfixable at any speed."
---

> Noncanonical working tempdoc. Design settled on measured evidence; implementation pending.

# 706 — Scanned-PDF OCR throughput (+ extraction timebox liveness)

## Problem (measured, 2026-07-10)

The 686 instrumented run over `mixed/realdocs-v1` (620 real files) showed the extraction cost tax
concentrates almost entirely in scanned/mixed PDFs: bimodal completion cadence — median inter-doc
gap ~0s, but stalls of 35-350s per scanned document, ~4-5 docs/min overall, near-zero CPU.

## Evidence base (full detail: 705 §Sidegoal, same date)

Three converging legs — code map (`file:line`), external research, local micro-benchmark:

1. **Dominant path is Tika-internal**: `tryOcr` → Tika `PDFParser` `OCR_STRATEGY=OCR_ONLY` renders
   EVERY page (300 DPI GRAY, `ocrDPI` default confirmed from jar bytecode) and spawns tesseract
   per page, serially — opaque: no page cap, no dimension guard, unparallelizable. JustSearch's
   own loop (`tryRenderedPdfOcr`, 160 DPI RGB hardcoded) is only a blank-result fallback.
2. **Tesseract itself is cheap**: ~1.1s/page OCR + ~0.1s/page render + ~0.1s spawn locally
   (tessdata_fast, 300 DPI gray, corpus scan sample). 65-page doc serial ≈ 80-200s — matches
   the stalls. Parallel 8-way measured **3.98×** on just 10 pages (20 logical cores).
   List-file batching measured 0.94× — worthless (init ~10% of a page); rejected.
   DPI reduction: modest gain, unverified quality anomaly; rejected without accuracy eval.
3. **Redundant invocations (code-confirmed)**: whole-file confidence re-OCR after successful Tika
   pass (`PolicyDrivenTikaExtractor.java:292`); text+TSV = 2 spawns/page in the owned loops.
4. **Timebox is advisory (liveness defect)**: single shared single-thread executor in
   `TimeboxedContentExtractor`; `cancel(true)` can't stop CPU-bound work; interrupted tesseract
   children not `destroyForcibly`'d (orphan leak, `OcrConfidenceExtractor.java:64-66,113-115`);
   next document queues behind the stuck one → logged per-doc times include inherited queue wait;
   true ceiling unbounded. (Sidecar audit's prediction, now runtime-corroborated.)
5. **Config duality**: `application.yaml` ships `per_file_timeout_ms: 5000, max_pages: 10`; code
   fallback `OcrRoutingConfig.defaults()` is `30s/50p`; the run OCR'd a 29+-page doc → code
   fallback was live. Needs an explicit decision.

## Design

1. **Bypass Tika-internal OCR for PDFs** (`OCR_STRATEGY = NO_OCR` on the OCR pass too); make the
   owned render+OCR loop the single authoritative OCR engine. Routing (`evaluateOcrAttempt`) and
   reason-code semantics (671 classifiers) unchanged — this swaps the execution engine of the OCR
   tier, not which tier a document takes.
2. **Owned loop**: 300 DPI GRAY render (PDFBox `renderImageWithDPI(page, dpi, ImageType.GRAY)`,
   subsampling allowed); page OCR fanned out to a bounded pool (~min(cores/2, 8) workers,
   `OMP_THREAD_LIMIT=1` per child, defensive); **one spawn per page emitting `txt tsv` together**
   (text + confidence in one invocation — removes both double-pays); `maxPages` enforced INSIDE
   the loop (truncate + record truncation honestly); an **aggregate per-document OCR budget**
   (elapsed-time circuit breaker) so pages × per-page-timeout can't compound.
3. **Liveness fixes**: `destroyForcibly()` on interrupt in `OcrConfidenceExtractor`; timebox
   cancellation kills the child process tree; consider surfacing a "previous task still draining"
   signal instead of silently queueing (the narrow fix here; the executor architecture itself is
   a bigger question left out of scope).
4. **Config decision**: reconcile yaml (5s/10p) vs code (30s/50p) defaults into one authoritative
   set; wire the owned loop's DPI as a named constant/config, replacing the hardcoded 160s.

## Expected effect

65-page scan: ~15-20s vs current 80-350s (>10×); fast documents unaffected; quality neutral-to-
better (same engine; 300 GRAY ≥ each replaced path's settings).

## Verification plan

- Before/after wall-time on the same scanned corpus docs (`govdocs1-000--000164.pdf` 13p full-scan;
  the 25-65p mixed-PDF stall candidates), plus a full `mixed/realdocs-v1` ingest re-run when wanted.
- Quality: word/char parity (±few %) on the scan sample vs the pre-change path; extraction_method /
  reason-code assertions unchanged (`VduEligibilityPdfFixturesTest` + 671's classifier tests green).
- Liveness: a regression test for the orphan fix (interrupt → child killed), per
  `audit-driven-fixes-need-test`.
- Timing evidence recorded here with the same discipline as 686 §Execution log.

## Boundary

Not owned: OCR routing/eligibility (607), reason-code vocabulary (671), VDU anything (672/677),
the extraction-tax verdict (705), heap sizing (686 follow-up). The single-thread extraction
executor's broader redesign is explicitly deferred — this doc ships the narrow liveness fixes
(forceful child kill, budget) only.

---

## Takeover investigation (2026-07-10, second session, worktree `takeover-706`)

Independent verification pass per `/takeover` (verifier ≠ author: this doc was written by the
takeover-705 session the same day; every load-bearing claim was re-checked against source rather
than trusted). Branch base `b7c9258`; `git diff main...` on `modules/worker-services/src` is
empty, so all citations below are current against `main` (f4889d1).

### Evidence re-verification — all claims CONFIRMED at source

| # | Tempdoc claim | Verified at |
|---|---|---|
| 1 | Full-scan OCR path is Tika-internal `OCR_ONLY` | `StructuredContentExtractor.java:261` (`configurePdfOcrOnly` → `OCR_STRATEGY=OCR_ONLY`), wired from `extractWithOcr:103-121`; non-OCR pass sets `NO_OCR` at `:242` |
| 2 | JustSearch never sets `setOcrDPI` → Tika defaults (300 GRAY) live | repo-wide grep: zero hits for `setOcrDPI`/`ocrDPI`/`setOcrImageType` |
| 3 | Owned fallback loop is 160 DPI RGB, hardcoded, serial | `PolicyDrivenTikaExtractor.java:508` (`tryRenderedPdfOcr`), `:400` (`trySelectivePdfOcr`) |
| 4 | 2 spawns/page in owned loops (text + TSV) | text `:516` + confidence `:524`; **undercounted for the mixed-PDF path**: `trySelectivePdfOcr` is up to **3** spawns/page (Tika-on-page-PNG `:409` + direct-text fallback `:417` + confidence `:428`) — the double-pay claim holds a fortiori |
| 5 | Whole-file confidence re-OCR after successful Tika pass | `PolicyDrivenTikaExtractor.java:292-293` (`OcrConfidenceExtractor.extract(file, …)` on the raw PDF — tesseract can't read PDFs, so fast-fail waste for PDFs, genuine full re-OCR for raster images) |
| 6 | Timebox advisory; next doc queues behind stuck one | `TimeboxedContentExtractor.java:122` (single shared single-thread executor), `:154` (`cancel(true)` best-effort) |
| 7 | Orphaned tesseract children on interrupt | `OcrConfidenceExtractor.java:64-66,113-115` — `InterruptedException` → return **without** `destroyForcibly()`. Nuance: the *timeout* path DOES kill (`:50,:106`); only the interrupt path leaks |
| 8 | Config duality 5s/10p (yaml) vs 30s/50p (code) | `config/application.yaml:120-121` vs `OcrRoutingConfig.java:39`; fallback mechanism found: `DefaultWorkerAppServices.resolvedOcrConfig():483-489` returns `defaults()` whenever the worker's `ConfigStore.globalOrNull()` is unpopulated — that is how a 29+-page doc got OCR'd in the 686 run |
| 9 | Tika can't be configured out of the problem | Tika 3.2.3 `PDFParserConfig` offers `ocrStrategy` (incl. per-page `AUTO`), `ocrDPI`, `ocrImageType` — but **no OCR page cap, no parallelism, no aggregate budget**; OCR inside a Tika parse is strictly serial (SAX). Owning the loop is the only route to the 3.98×-measured parallel win and to honest budgets. (Web-checked 2026-07-10: Tika wiki TikaOCR + PDFParser pages.) |

Also verified: `VduEligibilityPdfFixturesTest` exists (`modules/worker-services/src/test/.../loop/`);
the process-sandbox seam (tempdoc 410, `JUSTSEARCH_EXTRACTION_SANDBOX_MODE=process`,
`DefaultWorkerAppServices.java:452-481`) exists but defaults to in-process and requires an external
command — so this doc's "narrow liveness fix now, executor redesign deferred" boundary correctly
defers to existing infrastructure rather than duplicating it.

### Design corrections required (found by this pass; resolve at design/implementation time)

- **A. `maxPages` is currently an eligibility GATE, not a cap** (`evaluateOcrAttempt`,
  `PolicyDrivenTikaExtractor.java:174`: `pageCount > maxPages` → skip OCR entirely,
  `OcrSkipReason.SIZE`). The design's "maxPages enforced INSIDE the loop (truncate + record)" is
  ambiguous: removing the gate silently converts skip-entirely into partial-OCR — a routing-
  semantics change this doc's own boundary assigns to 607; keeping the gate makes in-loop
  truncation reachable only when pageCount was unknown (0) at gate time. **Recommended: keep the
  gate unchanged (routing untouched, as promised), add the in-loop cap as defense-in-depth for
  unknown page counts.** If truncate-instead-of-skip is wanted as a product improvement, that is a
  conscious 607-adjacent decision to record, not a side effect.
- **B. Setting `NO_OCR` on the OCR pass must not rely on the blank-content branch to reroute.**
  Today the owned loop fires only when the Tika OCR pass returns blank
  (`tryOcr:273-279`). Under `NO_OCR`, `extractWithOcr` returns *embedded text* — blank for pure
  scans (reroute works by accident) but **non-blank for partially-texty PDFs**, whose OCR would
  silently stop happening. `tryOcr` must be restructured to invoke the owned loop directly for
  PDFs, not via the blank check. (Classic wrong-gate shape; flagged per `critical-analysis-pass`.)

### Implementation cautions (record now, apply then)

- **PDFBox `PDFRenderer` is not thread-safe** on a shared `PDDocument`: render serially
  (~0.1s/page measured), fan out only the tesseract spawns.
- **Heap**: a 300 DPI GRAY Letter page ≈ 8-9 MB `BufferedImage`; 686's heap verdict says 1g has
  no margin. Bound in-flight rendered pages (backpressure), write PNG → free image promptly.
- **CPU contention**: the pool competes with enrichment (691's throughput domain);
  `min(cores/2, 8)` is sane, make it config-visible.
- **Budget ↔ timebox interplay is a quality WIN to state**: today a 60s timebox expiry discards
  the whole document (empty result + queue pileup); an in-loop budget returns partial text
  honestly (truncated). Budget must sit below the 60s timebox to fire first.
- **671 preservation**: `OcrOutcomeClassifier` no-improvement classification and the
  first-write-wins `OcrEvidenceBuilder` discipline (see the `:578-583` comment) must survive the
  restructure; 671's classifier tests + `VduEligibilityPdfFixturesTest` are the guard.
- **Config decision (design item 4) interacts with the win**: honoring yaml's 10p cap would have
  *skipped* most of the 686 stall docs (worse extraction, not faster OCR). With the parallel loop,
  50p at 300 DPI is affordable (~15-20s) — recommend reconciling toward the code defaults
  (30s/50p), fixing the `ConfigStore` fallback path, and making yaml the single authority.

## Theorization (2026-07-10, pre-design breadth pass)

Recorded before final design so the settled mechanism is chosen against, not instead of, the
alternatives. Nothing here reopens the GO verdict; it widens the option space and names the
assumptions the design rests on.

### Alternative framings of the same problem

1. **Latency vs throughput vs freshness.** The design optimizes per-document wall-time. But the
   user-visible harm has a second component: extraction is a single-serial lane, so one 300s scan
   blocks *every* document behind it — the stall taxes corpus freshness, not just the one file.
   Two consequences: (a) the liveness fixes (budget + forceful kill) are valuable *independently*
   of any speedup, because they bound the worst case the whole pipeline inherits; (b) per-page
   parallelism attacks the worst-document latency, which is the right first target — but
   document-level parallelism (N extractions concurrently) is the complementary lever left with
   the deferred executor redesign. They compose; this doc deliberately takes only the first.
2. **"Faster OCR" vs "OCR off the hot path".** A different shape entirely: index the document
   immediately with whatever cheap extraction yields, and queue OCR as a background enrichment
   that upgrades the document later — the pattern the VDU path already ships (idle auto-trigger +
   batch mode, tempdoc 672). Ingest latency for scans becomes ~0 regardless of OCR speed;
   the cost is searchable-but-empty documents until OCR lands, plus an update/re-index path.
   Not this doc's scope (it changes indexing-job lifecycle surfaces), but it is the natural
   *next* frame if scanned corpora grow, and the owned loop built here would serve that lane
   unchanged. Worth remembering that this framing exists before anyone proposes heroics inside
   the synchronous path.
3. **Pay-per-index vs pay-once.** No extraction cache exists anywhere in the tree (verified by
   grep). Blue/green schema migrations and `--clean`/`--reset` rebuilds re-pay full OCR for every
   scanned document on every rebuild — a standing multiplier on whatever per-document cost
   remains. A content-hash-keyed extraction cache (key must include engine + DPI + config
   version) would make rebuild OCR ~free. Out of this doc's scope and possibly its own decision
   (interacts with 691's throughput domain and store recoverability), but it may ultimately be
   worth more than the speedup on corpora that get rebuilt often. Recorded so it isn't
   rediscovered from scratch.

### Alternative mechanisms considered (and why the owned loop still wins now)

- **Configure Tika harder** (AUTO strategy, lower `ocrDPI`): no page cap, no budget, no
  parallelism exists behind those knobs (verified §above); rejected for the same reasons as
  before. AUTO's per-page selectivity is already covered by JustSearch's own
  mixed-PDF selective path.
- **In-process OCR via JNI (tess4j-style)**: spawn cost is measured at ~0.1s/page (~10%) — the
  gain is small, and a native tesseract crash would take down the Worker JVM. Process-per-page is
  not overhead to eliminate; it is the crash-isolation architecture working as designed. Rejected.
- **A different OCR engine (ONNX-based PP-OCR-class det+rec models, or the PaddleOCR-VL /
  DeepDoc candidates from 705 F5/F7)**: potentially faster *and* better than tesseract, GPU-
  capable, and JustSearch already owns ORT session infrastructure (`modules/ort-common`) and a
  model-distribution pipeline. Not now — it is a model-selection + quality-eval + distribution
  project (374/705 territory, gated on the 686/677 evidence preconditions). The key structural
  point: **the owned loop is what makes this future swap possible at all** — once the loop is
  JustSearch's, the engine behind it becomes a pluggable seam; inside Tika it is unreachable.
  Design should keep the per-page OCR call behind a minimal internal seam for that reason (a
  method boundary is enough; no speculative abstraction — AHA applies).
- **Page-level result streaming** (emit pages into the index as they finish): interacts with
  document atomicity and the indexing job model; complexity >> value while whole-doc OCR is
  15-20s. Rejected without prejudice.

### Hidden assumptions worth naming (each is cheap to check during implementation)

1. **Absolute tesseract quality is adequate** — the verification plan checks *parity* with the
   old path, not adequacy; nobody has evaluated tesseract-at-300-GRAY accuracy on this corpus.
   Adequacy is 705/686's question; this doc must only not regress it. (`--psm 6` for uniform
   text blocks is likewise inherited from both existing paths, unevaluated — a possible future
   quality lever, not touched here.)
2. **Cores are idle during OCR** — measured true (686: near-zero CPU during stalls), and the
   pool must stay bounded so it remains roughly true alongside enrichment.
3. **Low-end hardware still wins** — a 4-core machine gets ~2 workers ≈ ~2×, not 10×; the >10×
   headline is a 16-20-thread-machine number. Honest reporting should state speedup as a
   function of workers.
4. **Windows environment effects are second-order** — per-page temp PNG + txt/tsv churn invites
   antivirus real-time scanning overhead per file; a per-document temp directory (one create,
   one recursive delete) is cheaper and cleans up orphaned artifacts on kill. Native memory of
   ~8 concurrent tesseract children (each can reach a few hundred MB on dense pages) sits
   *outside* the JVM heap — worker-count choice should respect machine RAM, not just cores.
5. **Out-of-order completion is handled** — parallel page results must be joined in page order,
   and the `maxExtractedChars` cap means late-cancelling in-flight pages once the cap is hit
   (waves or a completion-ordered collector; minor waste acceptable).

### The recurring system shape (principle, not yet design)

This is the third time JustSearch converts a rented lifecycle into an owned one: llama-server
(`InferenceLifecycleManager`/`LlamaServerOps` — hung-process taskkill, zombie recovery, adoption)
and ORT sessions (`ort-common` — session ownership, VRAM budgeting) preceded it. The shape:
**an expensive external engine is invoked only for one bounded unit of work per call (one page,
one inference), while JustSearch owns the loop, the budget, the parallelism, and the child
lifecycle — including forceful termination.** Tika-internal OCR violates the shape (unbounded
work per call, rented loop); this doc restores it for OCR. If a future gate or doc wants the
invariant named: *no black-box call may own an unbounded amount of per-document work.* The
liveness fix should consciously mirror the existing `LlamaServerOps` kill discipline rather than
inventing a new one.

### Sequencing option (de-risking, if wanted)

The design can ship as one change, but it decomposes into three independently-verifiable slices
with strictly increasing risk: **(1)** liveness only — `destroyForcibly` on interrupt + process-
tree kill + aggregate budget (no behavior change on healthy docs, kills the worst UX harm);
**(2)** waste removal — single spawn emitting `txt tsv`, drop the whole-file confidence re-OCR
(pure cost removal, output identical); **(3)** engine swap — bypass Tika OCR, owned parallel
loop at 300 GRAY (the >10×, and the only slice carrying quality-parity risk). If anything forces
a partial ship, that is the order; slice 3 is where corrections A and B (§above) live.

### Verdict

**GO — do it, and now.** This is the opposite case from 705's WAIT-FOR-EVIDENCE: the evidence
this fix needs already exists and is fresh — 686's instrumented run located the cost tax in
scanned PDFs (35-350s stalls, near-zero CPU), the micro-bench measured the parallel win (3.98× at
8-way on 10 pages) and rejected the non-wins (list-file batching 0.94×, DPI reduction unverified),
and every mechanism claim is now independently code-confirmed. The liveness defect (orphan
children + unbounded queue-behind-stuck-doc) is a proven structural defect — per
`structural-defects-no-repeat` it needs no further incidents. **Cheapest validating evidence:
already exists** (the "before" half of the before/after is 686's run; the parallel-speedup leg is
the micro-bench); nothing cheaper than implementing + running the doc's own verification plan
remains. **Displaces**: Tika-internal OCR *execution* for PDFs (config-level bypass, no fork) and
consolidates three overlapping OCR paths (Tika-internal, `trySelectivePdfOcr`,
`tryRenderedPdfOcr`) into one owned engine — a net deduplication. **Duplicates nothing**: routing
(607), reason codes (671), VDU (672/677), corpus/measurement (686), throughput attribution (691),
and the process-sandbox seam (410) are all untouched or deferred-to. Main residual risk is OCR
quality parity, and the verification plan already gates on it. Conditions on GO: resolve
corrections A and B in the design before coding; keep the verification plan's liveness regression
test (`audit-driven-fixes-need-test`).
