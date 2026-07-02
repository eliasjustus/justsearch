---
title: "Tika OCR-skip routing misclassifies rendered scan-page images as already-textual, silently indexing zero-content documents"
type: tempdocs
status: "stub — not investigated in depth beyond the citations below; a real, live-verified production bug with no prior tempdoc home. Do not treat the root-cause hypothesis in §Expected fix shape as decided design; it needs verification against a live OCR run before implementation."
created: 2026-07-02
updated: 2026-07-02
author: agent investigation (discovered as a byproduct of tempdoc 624's eval-corpus materialization work; this stub written by a follow-up hardening session to give the bug a proper home)
category: indexing / extraction / ocr / vdu / worker-services
related:
  - 624-agentic-retrieval-eval-rebuild   # origin of the discovery — see 624's §As-built #6 (seventh pass), which traces its own scan-corpus nDCG@10=0.0000 result to this bug
  - 607-vdu-ocr-extraction-logic-analysis   # canonical owner of Tika/OCR/VDU extraction routing authority (status: active) — this bug lives inside that subsystem
---

# 671 — Tika OCR-skip routing misclassification

## Problem statement

The production Tika extraction pipeline (`modules/worker-services`) misroutes rendered
scan-page PNG images: the OCR-attempt classifier records `ocrSkipReason: "textual"` and
`route: "structured"`, i.e. it believes the document already has a usable text layer and
skips OCR — but the resulting indexed document has `textCharCount: 0`. The VLM/vision-model
OCR fallback (VDU) is configured but never actually runs; `vdu_status` stays `PENDING`. The
document is indexed with no body content at all, retrievable only by filename/title match.

## Evidence (live-verified, do not re-derive — cite this)

- Live-verified via the running dev-stack's `/api/knowledge/search` debug response, sampled
  on two documents (`olmby1`, `rellgrove4`) from the `golden/synth-scan-v1` corpus (fabricated
  eval-corpus documents rendered to look like degraded scanned pages) — both showed
  `ocrSkipReason: "textual"`, `route: "structured"`, `textCharCount: 0`, `vdu_status: "PENDING"`.
- Real-world impact: this corpus's live lexical-mode nDCG@10 is 0.0000 — a genuine retrieval
  failure, not a "too easy" corpus. An earlier fidelity-gate figure (nDCG@10=0.9693) recorded
  against the same corpus predates this discovery and was measuring a *different*, now-fixed
  bug (a Python eval-harness materialization gap that substituted plain text for the scan
  images) — so that older 0.97 figure never reflected real OCR quality either. Full trace:
  `docs/tempdocs/624-agentic-retrieval-eval-rebuild.md` §As-built #6 (seventh pass, 2026-07-02).
- Raw finding logged at discovery time: `docs/observations.d/2f739aa0-dcf3-4609-beb2-04bf6762970d.md:17`.

## Grounded code citations

Read in full for this stub (not guessed): `modules/worker-services/src/main/java/io/justsearch/indexerworker/extract/PolicyDrivenTikaExtractor.java`,
`.../VisualExtractionEvidence.java`, `.../OcrSkipReason.java`, `.../StructuredDocumentSummary.java`,
`modules/worker-services/src/main/java/io/justsearch/indexerworker/text/TextQualityAnalyzer.java`.

- `PolicyDrivenTikaExtractor.java:111-126` — when a file is a raster image and Tika's default
  (uncontrolled) first-pass parse already produced non-blank content, the extractor
  deliberately discards that content (rewrites it to `""`) and rebuilds `summary` with
  `pagesMissingReadableText = max(pageCount, 1)` so the explicit, bounded OCR path
  (`tryOcr`, not Tika's uncontrolled auto-Tesseract) owns text/metrics/provenance. This part
  is intentional (see the inline comment) and correctly forces `evaluateOcrAttempt` to attempt
  OCR (`pagesMissingReadableText() > 0` at line 184).
- `PolicyDrivenTikaExtractor.java:167-208` (`evaluateOcrAttempt`) — the classifier gate. Line
  184-188 skips OCR with `OcrSkipReason.TEXTUAL` only when `!hasMissingReadablePages &&
  TextQualityAnalyzer.computeQualityScore(content, pageCount) >= 0.3d`. `computeQualityScore`
  returns `0.0` for null/short text (`TextQualityAnalyzer.java:82-84`, threshold
  `MIN_GOOD_TEXT_LENGTH=100`), so this specific gate is **not** where a blank raster image gets
  mislabeled `TEXTUAL` — that happens downstream, once OCR has actually been attempted and failed.
- `PolicyDrivenTikaExtractor.java:258-329` (`tryOcr`) — after `structuredExtractor.extractWithOcr`
  runs and (for a raster image) `tryDirectImageOcr` is also tried as a fallback (line 268), if
  the OCR result is still blank, the success check at line 285-287
  (`ocrResult.content() != null && !ocrResult.content().isBlank() && ocrQuality >= baselineQuality`)
  fails purely on the blank-content clause — quality is never compared. Execution falls through
  to line 306-307: `ocrEvidence.skip(OcrSkipReason.TEXTUAL)`, and the method returns `null`
  (`line 328`). Back in `extractArtifact` (line 145-164), a null `ocrArtifact` falls through to
  building the `"structured"` route evidence from the *original*, already-blanked `result.content()`
  (from the line 111-126 rewrite) — producing exactly the observed
  `ocrSkipReason: "textual"` + `route: "structured"` + `textCharCount: 0` combination.
- `PolicyDrivenTikaExtractor.java:331-344` (`tryDirectImageOcr`) — when the direct-Tesseract
  fallback also returns blank text, the method returns `null` at line 343 **without calling
  `ocrEvidence.skip(...)` at all** — no distinct reason is recorded for "direct OCR ran and
  found nothing"; the caller's later `TEXTUAL` label (set inside `tryOcr`) silently absorbs it.
- `PolicyDrivenTikaExtractor.java:445-479` (`trySelectivePdfOcr`, the mixed-PDF sibling path)
  has the same shape: if the merged OCR text isn't better than baseline (or is still empty),
  line 478-479 also labels the outcome `OcrSkipReason.TEXTUAL`.
- Existing test surface to extend: `modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PolicyDrivenTikaExtractorTest.java:255-284`
  already exercises the OCR-success path against a real packaged-Tesseract fixture image
  (asserts `route: "ocr_full"`, checks `OcrSkipReason.TEXTUAL`/`DISABLED`/`SIZE` skip-metric
  counters for diagnostic context on failure). No existing test exercises the case where OCR
  is attempted, genuinely finds nothing, and the resulting evidence is checked — that's the gap.

## Root-cause hypothesis (unverified — for the implementing session to confirm, not decided design)

`OcrSkipReason.TEXTUAL` is being used as a catch-all "OCR did not improve the result" outcome
in `tryOcr` / `tryDirectImageOcr` / `trySelectivePdfOcr`, conflating two semantically different
outcomes:

1. OCR was skipped or produced text that wasn't better than an already-adequate baseline
   (the reason's actual name/intent — "existing text was fine, no OCR needed").
2. OCR was genuinely attempted against a document with **no existing baseline text either**,
   and the OCR engine(s) — both the primary Tika/Tesseract pass and the direct-Tesseract
   fallback — returned nothing. This is an extraction *failure*, not a "text already good"
   determination, and should route to a distinct reason (and, per the observed
   `vdu_status: PENDING`, should presumably also be eligible to trigger the configured VLM/VDU
   fallback — worth checking why that fallback isn't firing here as a related, possibly
   separate question).

A plausible fix shape: gate the `TEXTUAL` label on `baselineQuality > 0` (i.e. only claim
"text was already adequate" when there *was* baseline text), and introduce a distinct
`OcrSkipReason` (or reuse `UNKNOWN`, if that's judged sufficient) for the "OCR ran, found
nothing, no baseline either" case — including inside `tryDirectImageOcr`'s currently-silent
`return null` at line 343. Whether this should also change VDU/VLM fallback eligibility is a
separate question this stub does not attempt to answer.

## Reproduction

1. Ingest the `golden/synth-scan-v1` corpus (or any `golden/synth-scan-v1` document —
   `olmby1`, `rellgrove4` are the two already sampled) against a running dev stack.
2. Query `/api/knowledge/search` with debug output enabled and inspect the extraction
   evidence for either document — expect `ocrSkipReason: "textual"`, `route: "structured"`,
   `textCharCount: 0`, `vdu_status: "PENDING"`.
3. Confirm the document is retrievable only by filename/title, not by any body-content query.

## Status

Stub only. Not investigated beyond the citations above — no fix attempted, no live OCR run
re-verified by this session. A future session should live-verify the root-cause hypothesis
(e.g. by adding a case to `PolicyDrivenTikaExtractorTest.java` using a blank/no-text scan-style
fixture image alongside the existing real-Tesseract fixture) before implementing a fix.
