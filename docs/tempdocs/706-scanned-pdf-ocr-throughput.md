---
title: "Scanned-PDF OCR throughput: replace Tika's opaque internal per-page-serial OCR_ONLY path with JustSearch's own parallel, capped, budgeted render+OCR loop — >10x on scanned documents — and fix the advisory-timebox/orphaned-child liveness class the investigation confirmed on the same path"
type: tempdocs
status: "open — design settled on measured evidence (2026-07-10), implementation not started. Spun out of 705's founder-directed sidegoal per 686's own out-of-scope rule ('fixing whatever the measurement finds' is a new tempdoc). Owns OCR execution performance + the extraction timebox/orphan liveness fix; does NOT own routing (607), reason codes (671, shipped), or the extraction-tax verdict (705)."
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
