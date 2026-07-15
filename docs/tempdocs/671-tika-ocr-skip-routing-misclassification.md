---
title: "Tika OCR-skip routing misclassifies rendered scan-page images as already-textual, silently indexing zero-content documents"
type: tempdocs
status: "IMPLEMENTED — both the original OcrSkipReason fix AND the ExtractionStatus/IngestionOutcomeClass fix it led to are shipped, tested (100% PIT strength on all four new logic seams), and live-verified end-to-end against the real corpus and the real browser UI. See §As-built and §As-built, round 2 for what shipped. The Inspector UI fix (diagnostic now renders for zero-content documents) and VduCapabilityState guard tests are also shipped. Full repo build + test suite green throughout. The separate VDU/retrieval-quality follow-on (offline-processing trigger wiring gap; Tesseract preprocessing; vision-model selection) remains open and explicitly out of scope — see §Status."
created: 2026-07-02
updated: 2026-07-02
author: agent investigation (discovered as a byproduct of tempdoc 624's eval-corpus materialization work; this stub written by a follow-up hardening session to give the bug a proper home)
category: indexing / extraction / ocr / vdu / worker-services / reason-code-authority / logic-seams
related:
  - 624-agentic-retrieval-eval-rebuild   # origin of the discovery — see 624's §As-built #6 (seventh pass), which traces its own scan-corpus nDCG@10=0.0000 result to this bug
  - 607-vdu-ocr-extraction-logic-analysis   # canonical owner of Tika/OCR/VDU extraction routing authority (status: active) — this bug lives inside that subsystem
  - 658-retrieval-inspectability-and-diagnostic-bundle   # future inspector that should project from a correctly-fixed OcrSkipReason rather than forking a parallel evidence surface
  - 555   # law-backed logic-seams register (governance/logic-seams.v1.json) — the admission-policy seam states the total/injective-mapping law this bug violates, in a sibling package
  - 596-unavailable-affordance-reason-authority   # sibling "single reason-and-remedy authority" design precedent (frontend availability, not this bug's backend routing, but the same single-authority-over-forked-vocabulary discipline)
  - 374-app-packaging-and-distribution   # owns VDU vision-model selection/pinning; §External landscape check below explicitly defers that question to this tempdoc rather than deciding it here
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

## Follow-up: the mislabel and the stuck VDU fallback are probably two separate problems

A follow-up investigation (still static analysis, not yet a live OCR/VDU run) answers the
"why doesn't the VLM fallback fire" question this stub left open, and the answer changes the
shape of the problem:

- `VisualRoutingDecision.decide` (`modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/VisualRoutingDecision.java:21-50`),
  the code that decides `vdu_status`, never reads `OcrSkipReason` or the `ocrSkipReason`
  evidence field at all. It gates on file extension, the resolved `extractionMethod` string,
  and a quality score computed straight from the (blank) extracted content. For the exact
  scenario this stub describes — blanked raster content, `extractionMethod` resolving to the
  structured/non-OCR value because `tryOcr` returned `null` — that quality score is `0.0`,
  which *correctly* sets `vdu_status: PENDING` with `vdu_demand_kind: baseline_text`
  regardless of the `TEXTUAL` mislabel. So the VDU eligibility layer already treats these
  documents as needing enrichment; the mislabel does not appear to block it.
- The more likely explanation for `vdu_status` staying `PENDING` is architectural, not a bug
  in this routing path: VDU batch processing is an idle-time or explicitly-triggered
  background job, not something that runs synchronously during ingest. `OfflineCoordinator`
  (`modules/app-services/src/main/java/io/justsearch/app/services/vdu/OfflineCoordinator.java:11-14`)
  documents itself as "Called when user goes idle or manually triggers 'Process Now'", and
  `VduBatchProcessor.processPendingFiles` (`modules/app-services/src/main/java/io/justsearch/app/services/vdu/VduBatchProcessor.java:102-128`)
  additionally requires sufficient VRAM and a loaded vision projector (mmproj) before it will
  process anything, else it records a capability blocker and returns without processing. A
  manual trigger operation exists (`core.trigger-offline-processing`, handled by
  `TriggerOfflineProcessingHandler.java`), confirming this is deliberately not an automatic,
  synchronous part of ingestion.
- Put together: a document can sit at `vdu_status: PENDING` indefinitely simply because
  nothing ever went idle or clicked "Process Now" during the session that produced the
  live-verified evidence in this stub — independent of whether the `TEXTUAL` mislabel is ever
  fixed. This was not confirmed by actually running offline processing to completion and
  watching a `PENDING` row resolve (that would need a GPU + mmproj-equipped run and is a good
  next verification step), but the architecture described above is enough to treat the
  mislabel and the "VDU never resolves" symptom as two distinct problems rather than one
  problem with two symptoms, until a live run says otherwise.
- This reframes the fix's expected effect: correcting `OcrSkipReason` improves diagnosability
  (the evidence would honestly say "OCR was attempted and failed" instead of implying "text
  was fine") but does **not**, by itself, make these documents searchable. Retrievability
  still depends on whichever of (a) OCR itself succeeding on this degradation band, or (b) VDU
  actually running to completion, turns out to be true — both open questions below.

## Broader framing and alternative directions (theorization — not decided design)

> A later pass (§Long-term design below) investigated what the codebase already has for this
> exact shape of problem and found concrete, load-bearing precedent. Read that section for the
> grounded recommendation; the menu of options below is the earlier, less-grounded survey that
> motivated looking for that precedent in the first place.

The paragraphs below are exploratory: things worth weighing before committing to an
implementation, not a decision. None of this should be read as superseding the root-cause
hypothesis above, which remains the most concrete, lowest-risk starting point.

**Is this one bug or two layered problems?** The stub's own evidence contains two distinct
claims that are easy to merge into one story but probably shouldn't be: (1) a *labeling* bug —
the system says "text was fine" when it wasn't — and (2) a *capability* question — can the
production OCR/VDU stack actually recover text from this degradation band at all, given
enough tries? Fixing (1) alone makes the system honest about failure without necessarily
making the documents searchable; treating (1) as if it were the whole fix risks declaring
victory on a metrics/evidence improvement while the underlying retrieval gap (the reason this
was discovered in the first place — a corpus scoring nDCG@10 = 0.0000) persists untouched.
Any implementation pass should probably say explicitly which of these two problems it is
solving, rather than letting the label fix stand in for both.

**Reframing beyond "fix one enum value."** A few different levels of intervention are
possible, roughly from narrowest to broadest:

1. *Local fix*: gate `TEXTUAL` on `baselineQuality > 0` at the three call sites identified
   above, and add one more reason value for "attempted, found nothing, no baseline." Small,
   contained, matches the stub's own hypothesis.
2. *Structural split*: treat "why OCR was never attempted" (config/size/engine/language
   reasons — genuinely a priori decisions) and "what happened once OCR was attempted"
   (succeeded / attempted-but-no-improvement / attempted-and-found-nothing) as two different
   concepts rather than one overloaded enum. `OcrSkipReason` currently mixes both: some values
   are set before OCR ever runs, others (`TEXTUAL` in the buggy path, and the exception-driven
   `UNKNOWN` case) are set only after an attempt. Enums that answer "why didn't X happen" are a
   recognizable shape for this kind of drift — a value meant for "we chose not to try" gets
   reused for "we tried and it didn't help" because no separate vocabulary exists yet for the
   second case. Whether that's worth the wire/metrics churn of a real split, versus just adding
   one more value to the existing enum, is a real tradeoff and not obvious from static reading
   alone.
3. *Evidence-first alternative*: instead of (or alongside) refining the reason code, carry
   orthogonal boolean facts in the routing evidence — something like "OCR was attempted" and
   "OCR produced usable text" — so that downstream readers (VDU eligibility, an operator
   dashboard, a future UI affordance) don't have to reverse-engineer intent from a single
   human-readable reason string that may grow more values over time. This trades a small
   amount of evidence-schema size for resilience against the same catch-all drift recurring
   under a different name later.
4. *Upstream fix*: invest in making OCR itself more likely to succeed against this
   degradation band (deskew, contrast normalization, upscaling before the Tesseract pass)
   rather than only relabeling failure. The rendering parameters that produced the sampled
   documents (moderate blur, several-degree rotation, salt-and-pepper noise) were explicitly
   chosen to be readable by a capable model but were never confirmed to be within reach of the
   production OCR path specifically — so it is not yet known whether better labeling alone
   would ever surface a "success" case for images like these, or whether they are permanently
   destined for the OCR-failure branch regardless of how it's labeled.
5. *Trigger-gap fix*: separately address that VDU is idle/manual-triggered rather than run
   promptly for documents that end up with zero baseline text, or at least make "N documents
   indexed with zero extractable text, awaiting background enrichment" visible somewhere an
   operator would see it. This targets the invisibility of the outstanding-work backlog rather
   than the labeling or the OCR quality ceiling.

These aren't mutually exclusive: (1) is close to a prerequisite for any of the others, and
(3)-(5) could be pursued independently or layered on top of it later. Scoping how many of
these an implementation pass should attempt is an open call, not something this theorization
pass resolves.

**Hidden assumptions worth naming before implementing:**

- That fixing the reason code meaningfully improves *retrieval*, not just *evidence honesty*.
  It doesn't, on its own — see "one bug or two" above.
- That a uniform fix applies identically at all three call sites. `trySelectivePdfOcr`'s
  "no improvement" case is subtly different from the other two: a mixed PDF that already has
  *some* real baseline text on other pages is not the same situation as a raster image with no
  baseline text at all, even though both currently fall into `TEXTUAL`. Whether they deserve
  the same new reason code, or different ones, hasn't been examined closely.
- That `ocrSkipReason`/`ocr.skipped_total` has no existing consumers who would need to account
  for a new value appearing (dashboards, alerting, or any pinned baseline keyed on the
  `textual` tag). This wasn't checked in this pass.
- That the synthetic degraded-scan corpus used to discover this bug is representative of
  real-world scanned documents. It was deliberately tuned to sit at the edge of what a casual
  multimodal read can handle; whether typical real-world scans (phone photos of paper,
  standard flatbed scans) hit this same failure mode often, rarely, or not at all is unknown,
  and materially affects how urgently this should be prioritized against other work.

**A possible broader principle.** Beyond this specific bug, the underlying shape may recur:
a document can be indexed "successfully" (no exception, a plausible-looking route and reason)
while carrying zero or near-zero extracted content, and nothing distinguishes that state from
a genuine, intentional "no OCR needed" outcome unless someone reads the routing evidence by
hand. That is a general risk, not specific to OCR — any extractor for any MIME type could in
principle produce empty output for a document that structurally "succeeds." A durable
invariant worth considering (not proposed here as a concrete design) is something like:
*documents indexed with no meaningfully extracted content should be distinguishable and
countable as a class, independent of which extractor or code path produced that outcome* —
so that a whole category of silent, zero-content indexing is visible in aggregate rather than
requiring a debug-response sample of individual documents to notice, the way this bug was
found. Whether that becomes a cross-extractor concept, a metric, or nothing at all is a
question for a later, dedicated design pass — flagged here only because this bug is a
plausible first instance of a pattern that could reappear elsewhere.

## Long-term design: conform to two authorities the codebase already has

This pass looked for existing, load-bearing structure this bug should conform to before
proposing anything new — per the project's own explore-before-implementing discipline. Two
things already exist that answer most of §Broader framing's open questions.

### What already exists

**1. A general "reason code" convention, at two different altitudes, both already in this
codebase.**

- `LifecycleReasonCode` (`modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/LifecycleReasonCode.java`)
  is a closed, stable, wire-mapped taxonomy of *system-capability* degradation causes — one
  entry per distinct reason a capability (Worker, inference, OCR, VDU, telemetry, GPU…) is not
  fully available, each documented with the tempdoc that introduced it and, in several cases,
  an explicit note on why it's distinct from its nearest neighbor (e.g. "distinct from
  transient `worker.unavailable`, which retries"). It already has OCR- and VDU-level entries
  (`ocr.disabled`, `ocr.engine_missing`, `vdu.insufficient_vram`, `vdu.missing_mmproj`, etc.) —
  but those describe *whole-capability* unavailability, not a *per-document* extraction
  outcome, so this bug's fix does not belong there; it's the wrong altitude.
- `SearchReasonCode` (`modules/worker-services/src/main/java/io/justsearch/indexerworker/services/SearchReasonCode.java`)
  is the closer analog: a per-Worker-decision reason-code enum, module-scoped ("package-private
  to `services/` — minimum-surface promotion... promote only when a second consumer outside
  `services/` emerges"), wire-mapped via a `.wire()` method, and backed by a cross-module
  contract test. Its chunk-merge partition already keeps `APPLIED` and eleven distinct
  `SKIPPED_*` values in **one enum**, not two separate types — an a-priori "we chose not to"
  vocabulary living alongside an outcome vocabulary, exactly the shape §Broader framing's
  option 2 asked whether was worth building. It already exists, one file over.
- `OcrSkipReason` is architecturally the same shape as `SearchReasonCode`: small, module-scoped
  (`extract` package inside `worker-services`), wire-mapped as a string inside a
  schema-versioned JSON evidence record (`VisualExtractionEvidence`, `schemaVersion=1`), with
  exactly one real external consumer (the Inspector UI, which reads the evidence JSON — per
  tempdoc 607 — to render a "text source" explanation). It is not proto-wired and has no
  cross-language contract doc of its own, unlike `SearchReasonCode`
  (`docs/reference/contracts/search-and-rag-reason-codes.md`) — appropriately so, given its
  smaller footprint and single consumer.

**2. A registered mechanism for exactly this failure mode, in the same package family.**

`governance/logic-seams.v1.json` (tempdoc 555) is a small, deliberately-kept-small registry of
pure, branch-dense, high-blast-radius logic whose failure mode is a silent wrong value rather
than an exception — each entry states a `law`, is backed by a guard test, and is
mutation-tested for how well that test actually catches violations of the law. One already-
registered seam, `admission-policy`
(`modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/AdmissionPolicy.java`),
states almost exactly this bug's failure mode as a law, for a *different* mapping in the same
`io.justsearch.indexerworker.loop` package family:

> "Total, injective mapping from a post-snapshot `SourceValidationResult` to its ingestion
> reason code; every change kind maps to a distinct code. A swapped/dropped arm emits a
> misleading stale diagnostic."

`AdmissionPolicy` is a tiny, pure, `static` function — a single exhaustive `switch` over an
enum, extracted out of the IO-entangled orchestrator that calls it (`WorkerIngestionAuthority`)
so the mapping itself can be unit- and mutation-tested in isolation. This is precisely the
functional-core/imperative-shell split this bug is missing: the OCR reason-code decision is
currently inline inside `tryOcr` / `tryDirectImageOcr` / `trySelectivePdfOcr`, which are
themselves IO-heavy (they call the OCR engine, read files, render PDF pages) — the actual
"given these facts, which reason code applies" decision has never been pulled out into its own
pure, testable, law-bearing unit, so nothing enforced totality or injectivity on it, and it
silently stopped being injective (two different causes both mapping to `TEXTUAL`).

### The design

Conforming to both of the above, rather than inventing a third structure:

- Extract the reason-code decision inside `tryOcr` / `tryDirectImageOcr` / `trySelectivePdfOcr`
  into a small, pure function (or a few pure functions, one per call site if the per-site facts
  genuinely differ — see the mixed-PDF note below) in the same `loop`/`extract` package family,
  following `AdmissionPolicy`'s shape: an exhaustive mapping from the input facts already
  available at each call site (was there baseline text; was OCR attempted; did OCR produce
  usable text) to an `OcrSkipReason`. State its law the same way `admission-policy` does: the
  mapping is total and injective — every distinct outcome gets its own code, and a fixture
  covering each branch is required to prove it (mirroring the gap already identified in
  `PolicyDrivenTikaExtractorTest`).
- Extend `OcrSkipReason`'s existing vocabulary with the missing value(s) for "OCR was genuinely
  attempted against a document with no baseline text, and found nothing" — following
  `SearchReasonCode`'s precedent of holding both the a-priori "chose not to attempt" values and
  the outcome-of-an-attempt values in the same enum, rather than splitting into two types. This
  directly resolves §Broader framing's option-2 question: the existing local precedent already
  answers it — one enum, richer vocabulary, not a structural split.
- Once the guard test exists, register the extracted function in
  `governance/logic-seams.v1.json` alongside `admission-policy`, using the same law shape
  ("total, injective mapping from OCR-attempt facts to a reason code; a wrong or reused arm
  produces a document that is silently indexed with no body content and no honest diagnostic").
  This is not new machinery — it is the exact registration the project's own `seam-hint`
  authoring-time nudge already points new pure, branch-dense, IO-free logic in a seam-bearing
  module toward.
- Do not build a `docs/reference/contracts/*ocr-reason-codes.md` document or a generation gate
  for this enum now. `SearchReasonCode`'s contract doc + gate exist because it crosses a
  proto/gRPC wire boundary and (via `search-and-rag-reason-codes.md`) a cross-language surface;
  `OcrSkipReason` does neither today — it is a string inside one module's JSON evidence blob
  with one internal consumer. If a future retrieval inspector (tempdoc 658, currently a stub)
  starts projecting `ocrSkipReason` values into a user-facing or cross-module surface at the
  scale `SearchReasonCode`'s values are, that escalation becomes the natural next step — not
  before. Tempdoc 658 itself already states the relevant discipline: "avoid inventing a second
  authority for search traces or pipeline state... project from existing trace/status/reason-code
  surfaces." A correctly-fixed `OcrSkipReason`, kept in its current versioned-evidence shape, is
  exactly what such an inspector should project from.
- The mixed-PDF call site (`trySelectivePdfOcr`) may deserve its own distinct value rather than
  reusing whatever new value the raster-image call sites get: a mixed PDF that already has real
  baseline text on other pages is not the same situation as a raster image with no baseline
  text anywhere, even though both currently collapse into `TEXTUAL`. Whether that distinction
  earns a separate code (mirroring how `SearchReasonCode`'s `SKIPPED_*` partition has eleven
  distinct values rather than one generic "skipped") or is folded into one shared value is an
  implementation-level call, not decided here.
- None of this reaches into `LifecycleReasonCode` or `VduCapabilityState` — both are the
  correct authority for a different, capability-level question ("is OCR/VDU available at all
  right now") and are not where a per-document routing outcome belongs.

## Naming the underlying principle (recognized here, not built as new structure)

`governance/logic-seams.v1.json`'s `admission-policy` entry already states, for a sibling
mapping in the same package, a law this bug violates:

> Reason-code / outcome-classification mappings from a decision space to a stable code must be
> **total and injective** — every distinct cause maps to its own distinct code — or diagnostics
> silently conflate causes that look identical downstream but aren't.

This tempdoc's bug is a second, previously-unregistered instance of that exact law being
violated, in a sibling seam of the same subsystem (`OcrSkipReason.TEXTUAL` absorbing two
distinct causes). That is worth naming plainly as a recurring shape, not just a one-off typo:
**any enum that answers "why didn't X happen" is at risk of silently being reused to also
answer "X happened but didn't help," because the second vocabulary looks unnecessary until the
first real case appears.**

Where this principle already holds correctly in this codebase: `AdmissionPolicy` (registered,
guarded, mutation-tested), `SearchReasonCode`'s `APPLIED`/`SKIPPED_*` partition (each skip arm
already has its own code), and `LifecycleReasonCode` (each degraded-capability cause has its
own code, with several entries' comments existing specifically to explain why a new code was
added instead of reusing a neighboring one — the same discipline this bug's fix should apply
to `OcrSkipReason`).

Where it is not yet known whether the principle holds: `VduCapabilityState`'s blocker reasons
(`REASON_INSUFFICIENT_VRAM`, `REASON_MISSING_MMPROJ`, `REASON_CIRCUIT_OPEN`, …) are a similarly
-shaped "why is this not happening" vocabulary in the sibling VDU subsystem. Whether its mapping
is total and injective was not checked in this pass — it is named here only as a candidate to
audit against the same law if it is ever touched, not as a claim that it is broken.

This pass deliberately stops at naming the principle and pointing at the one place in this
subsystem where it is currently violated. It does **not** propose a new cross-cutting
"reason-code law" framework, gate, or audit sweep of every enum in the codebase — the register
that would hold such a law already exists (`governance/logic-seams.v1.json`, tempdoc 555), and
registering one more seam (this bug's classifier, once extracted and guarded) is the
correctly-scoped action. Recognizing that the principle exists is separate from building
generalized structure for it, and only the former is warranted by what this tempdoc's problem
actually requires today.

## External landscape check (2026-07-02 web search — informational, not a model recommendation)

§Broader framing direction 4 ("upstream fix": improve OCR itself rather than only relabel
failure) and direction 5 (fix the VDU trigger gap) both hinged on an unverified assumption:
whether the current local extraction stack can plausibly recover text from this degradation
band (moderate rotation, Gaussian blur, salt-and-pepper noise) *at all*, by any path. Two
targeted web searches were run to check whether recent, dated, external material changes that
assumption — not to pick a model or write code from what was found. No external text or code
was copied into the codebase or this document; only factual claims are summarized below, each
attributed to its source.

- **Tesseract (the engine `PolicyDrivenTikaExtractor` actually calls) is not fed any
  deskew/denoise preprocessing today** — confirmed by reading the extraction code for this
  pass; `tryOcr`/`tryDirectImageOcr` hand the raw file straight to Tesseract. General 2025
  OCR-practice writeups consistently report that skipping this step is costly specifically for
  rotated/noisy/uneven-lighting scans: deskew + adaptive thresholding alone is commonly cited
  as a ~30% character-error-rate reduction on rotated documents, and 15–30% overall accuracy
  gains from basic OpenCV-style preprocessing (binarization + deskew) before Tesseract, per
  [Boost Tesseract OCR Accuracy: Advanced Tips & Techniques](https://sparkco.ai/blog/boost-tesseract-ocr-accuracy-advanced-tips-techniques)
  and [Tesseract Production Practices](https://markaicode.com/best/best-tesseract-production-practices/).
  This is standard, well-established OCR practice, not a research frontier — it sharpens
  direction 4 from speculative ("might help") to concrete ("a known, commonly-applied gap in
  the current pipeline"), without this pass claiming it would fully close the gap on this
  specific degradation band, which was not tested.
- **The vision-model side of this stack (VDU) has plausible headroom this Tesseract path
  doesn't.** `ImagePreparer.java`'s own comment says its resize constraint is "inherited from
  the Qwen3-VL era," and tempdoc 374 records `Qwen3-VL-8B-Thinking` as a previously-bundled VDU
  model. This section originally described current vision-model selection as unresolved and
  tracked elsewhere; §Confidence-building pass below corrects that — the live model registry
  now pairs a vision projector directly with the already-present chat model (Qwen3.5-9B), and
  that exact projector file is verified present (by hash) on this machine, just not yet staged
  where the runtime looks for it. That correction doesn't change this bullet's point about
  vision-model robustness on this degradation band, only the "is a model even available" framing
  around it. A third-party OCR
  comparison writeup ([Hall of Multimodal OCR VLMs and Demonstrations](https://huggingface.co/blog/prithivMLmods/multimodal-ocr-vlms))
  reports Qwen3-VL performing measurably better than a comparison baseline on degraded-image
  OCR, and Qwen's own model announcement
  ([Qwen3-VL: Sharper Vision, Deeper Thought, Broader Action](https://qwen.ai/blog?id=99f0335c4ad9ff6153e517418d48535ab6d8afef&from=research.latest-advancements-list))
  describes built-in robustness to blur and tilt plus an explicit rotation-correction
  capability. These are vendor/third-party claims, not independently reproduced here, and are
  cited only as directional evidence, not a benchmark this tempdoc ran.
- **What this changes:** it does not resolve which of direction 4 or 5 is more valuable — that
  still needs a live run — but it does suggest the two directions are asymmetric in a useful
  way. Direction 4 (Tesseract preprocessing) targets a gap that is well-documented and
  inexpensive to close, but Tesseract's ceiling on this degradation band even with
  preprocessing is unknown. Direction 5 (fix the VDU trigger gap identified in §Follow-up) may
  matter more: if a vision-capable model already handles this degradation band reasonably well
  by design, the bottleneck for these particular documents is less "can any local engine read
  this" and more "does the already-plausibly-capable engine ever get a chance to run." Neither
  claim was live-verified in this pass. Vision-model *selection* for VDU is explicitly out of
  this tempdoc's scope — it belongs to tempdoc 374's model-registry work — this section only
  notes that the selection question is more consequential to this bug's real-world impact than
  §Broader framing's original phrasing suggested.

## Confidence-building pass (2026-07-02, live-verified against real code + real Tesseract)

Everything above this section was static reading. This pass ran the actual code, with the
actual OCR engine, against the actual two documents named in §Evidence, to convert the
root-cause hypothesis from "plausible on static reading" to "confirmed." No code changes were
made to the fix's target files; a temporary test file was written, run, its output captured
below, then deleted (not committed).

- **The bug reproduces live, today, in this worktree, exactly as documented.** A real Tesseract
  5.5.0.20241111 (English tessdata) is genuinely installed and reachable on this machine's
  `PATH`; the existing `realTesseractRuntime*` tests in `PolicyDrivenTikaExtractorTest` ran
  for real (`skipped="0"` in the JUnit XML), not via their `assumeTrue` skip path. Feeding the
  exact sampled files — `datasets/golden/synth-scan-v1/corpus-dir/olmby1.png` and
  `.../rellgrove4.png`, present on disk in this worktree — directly through
  `PolicyDrivenTikaExtractor.extractArtifact()` with OCR enabled (`OcrRoutingConfig(true, ...)`,
  matching production defaults) reproduced the exact evidence from §Evidence, byte for byte:
  `parserId=tika-policy-structured`, `textCharCount=0`,
  `ocrSkipReason=textual`, `route=structured` for both files.
- **The root-cause hypothesis's core mechanism is confirmed, not just plausible.** A synthetic
  fully-blank white PNG (900x600, zero drawn content, no OCR attempted before this — a
  from-scratch minimal reproduction, not derived from the corpus) run through the same code path
  produced the **identical** evidence signature. This directly confirms §Root-cause hypothesis's
  central claim: the `TEXTUAL` label is reached via a genuinely blank Tesseract result on a
  document with no baseline text either, not a near-miss quality-threshold edge case or some
  other branch. The two real corpus documents and the synthetic blank image are
  indistinguishable to the classifier today — which is exactly the conflation this tempdoc
  describes.
- **OCR is enabled by default in the assembled product**, confirming the bug is reachable
  without any non-default configuration: `OcrRoutingConfig.from(ResolvedConfig.Ocr)`
  (`modules/worker-services/src/main/java/io/justsearch/indexerworker/extract/OcrRoutingConfig.java:42-54`)
  defaults `enabled` to `true` unless explicitly set `false`, and `OcrRoutingConfig.defaults()`
  (used when no `Ocr` config section is present) is also `enabled=true`.
- **No dashboard, baseline, or test outside the extractor's own module pins the `TEXTUAL`/
  `"textual"` value or its metric counts.** A repo-wide grep for `ocr.skipped_total`,
  `OcrSkipReason.TEXTUAL`, and the wire string `"textual"` outside
  `modules/worker-services/src/{main,test}/.../extract/` turned up nothing — no CI baseline
  JSON, no dashboard config, no other test file. Extending the enum's vocabulary has no known
  blast radius beyond the module already being changed.
- **The `AdmissionPolicy`-style extraction is structurally feasible**, based on a full read of
  all three call sites (`tryOcr`, `tryDirectImageOcr`, `trySelectivePdfOcr`): each one already
  reduces, at the point it emits `TEXTUAL`, to a small number of local facts (an
  attempted-output-was-blank boolean, an attempted-output quality score, and a baseline quality
  score) that a pure function could take as parameters and return a distinct `OcrSkipReason`
  from — the same shape as `AdmissionPolicy.staleReasonCode`'s single `switch`. The mixed-PDF
  site (`trySelectivePdfOcr`) has one extra fact worth keeping distinct (whether *any* page
  produced appended text at all, vs. merged text that wasn't better than baseline) — consistent
  with §Long-term design's note that it may deserve its own value.
- **Registering a new seam has a known, low-risk bootstrap path**, not a novel gotcha: the
  `test-efficacy` gate's own rule set
  (`scripts/governance/gates/test-efficacy/rule-descriptions.mjs:21-22`) documents
  `baseline-missing` as expected on a fresh seam, resolved by
  `node scripts/governance/run.mjs --gate test-efficacy --rebalance` after a clean PIT run —
  the same mechanical step used to raise any seam's floor when its measured strength improves.
- **Correction to an initial claim in this pass:** an early check of only the shared models
  directory (`F:\justsearch-public\models`) for `*mmproj*`/`*vl*` filenames found nothing there
  and led to an overly broad claim that "no vision-projector model file exists" in this
  environment. That was too narrow a search, and wrong in effect. `model-registry.v2.json`
  (`modules/ui/src/main/resources/ai/model-registry.v2.json:225-252`) declares `mmproj-F16.gguf`
  as a `supportingFile` of the same `chat` package as the already-present
  `Qwen_Qwen3.5-9B-Q4_K_M.gguf` (`targetDir: ""`, i.e. the models root) — so Qwen3.5-9B is
  designed to be vision-capable via this companion file, not a separate model as an earlier
  version of this note (and `VduProcessor.java`'s own code comment) implied. On this specific
  machine, that exact file already exists — verified by an independent SHA-256 hash
  (`97f420245a85ce129bb764e86a5e21e27d782fe6d6056c6839b9c5fdb8f38289`) matching the registry's
  pinned hash for `mmproj-F16.gguf` byte for byte — but sitting in an unrelated temp
  download-mirror-verification cache directory rather than the models root the runtime resolves
  `JUSTSEARCH_MMPROJ_MODEL` against (`docs/reference/configuration/environment-variables.md`:
  both `JUSTSEARCH_VLM_MODEL` and `JUSTSEARCH_MMPROJ_MODEL` are filenames resolved under
  `JUSTSEARCH_MODELS_DIR`). So the accurate statement is: **this machine has a capable GPU
  (RTX 4070, ~12 GB VRAM) and the correct, verified vision-projector artifact already
  downloaded — it is one file-placement step (copying/staging the existing, hash-verified
  `mmproj-F16.gguf` into the shared models root) away from being VDU-capable, not blocked on
  downloading or building anything.**
- **Follow-up: the file was staged and a live VDU attempt was actually run.** With explicit
  direction, `mmproj-F16.gguf` was copied into the shared models root (hash re-verified
  identical post-copy) and a real dev stack was started against it:
  - After also setting `llm.modelPath` via `POST /api/settings/v2` (the dev stack's persisted
    chat-model path was empty; file presence alone doesn't configure it — a distinct,
    unsurprising bootstrap step, not a bug) and activating the `cuda12` runtime,
    `GET /api/status`'s `readiness.components.visualDocumentUnderstanding` reported `READY`
    with no blocker — confirming the earlier "one staging step away" claim for real, not just
    by file-hash inference.
  - The full `golden/synth-scan-v1` corpus (360+ docs) was ingested through the real pipeline
    (via an indexing root, not the ad-hoc ingest endpoint, which silently no-ops on paths
    outside a registered root — worth knowing, not a bug). `olmby1.png` re-confirmed the exact
    original bug signature end-to-end through live ingest (not just the earlier unit-level
    reproduction): `vdu_status: PENDING`, `vdu_demand_kind: baseline_text`,
    `ocrSkipReason: "textual"`, `route: "structured"`, `textCharCount: 0`. Other corpus images
    (leftover files in the same corpus-dir from earlier corpus-generation iterations, not the
    two originally-sampled documents) landed on the *other* VDU path instead —
    `extraction_method: OCR_TIKA`, `route: "ocr_full"`, real but low-confidence OCR text
    (`ocrMeanConfidence` ~0.17–0.23), `vdu_demand_kind: "visual_enrichment"` — a useful
    reminder that this corpus-dir mixes at least two distinct extraction outcomes, only one of
    which is this tempdoc's bug.
  - **Triggering VDU itself (`POST /api/offline/process`) failed** with
    `SERVICE_UNAVAILABLE: "Offline processing not available"`, even after the model was staged,
    activated, and confirmed `READY`, and even after a full stack restart. Traced one layer:
    `BrainRuntimeServiceImpl.triggerOfflineProcessing()` throws when its
    `offlineProcessingTrigger` field is `null`
    (`modules/app-services/.../brainruntime/BrainRuntimeServiceImpl.java:62-65`), which is only
    non-null if `OfflineCoordinatorBuilder.build()` succeeded at Head bootstrap, which in turn
    requires `ServicePhase`'s `in.inferenceManager()` to be non-null *at boot time*
    (`OfflineCoordinatorBuilder.java:35-38`, `ServicePhase.java:149-167`). This pass did not
    trace far enough to find why `inferenceManager` was null in this dev-stack launch — logged
    as a separate observation rather than chased further, since it's unrelated machinery to
    this tempdoc's bug (`docs/observations.d/`). **Net effect: the live end-to-end "does VDU
    actually resolve a PENDING document" question remains unanswered** — not because the model
    is missing anymore, but because of this second, independent wiring gap discovered only by
    attempting the real trigger.
  - Dev stack and its state were torn down after this attempt (`dev_stop`); the staged
    `mmproj-F16.gguf` was left in place in the shared models root since it is a correct,
    registry-declared artifact, not scratch state.

## Reproduction

1. Ingest the `golden/synth-scan-v1` corpus (or any `golden/synth-scan-v1` document —
   `olmby1`, `rellgrove4` are the two already sampled) against a running dev stack.
2. Query `/api/knowledge/search` with debug output enabled and inspect the extraction
   evidence for either document — **pre-fix** this showed `ocrSkipReason: "textual"`;
   **post-fix** (see §As-built) it correctly shows `ocrSkipReason: "no_text_found"`. Both cases
   still show `route: "structured"`, `textCharCount: 0`, `vdu_status: "PENDING"` — the fix
   corrects the diagnostic label, not the underlying zero-content/VDU-pending state (that's the
   separate, still-open follow-on — see §Status).
3. Confirm the document is retrievable only by filename/title, not by any body-content query
   (still true post-fix — the fix is diagnostic, not a retrieval-quality change).

## As-built (2026-07-02): the scoped reason-code fix

Implemented per §Long-term design, with two refinements found during implementation:

- **A fourth call site had the identical bug**, missed by every prior investigation pass:
  `tryRenderedPdfOcr` (the fully-image-PDF OCR fallback) had the same
  `ocrEvidence.skip(OcrSkipReason.TEXTUAL)` catch-all. Its internal skip/increment call was
  **removed** rather than fixed in place — it was always immediately followed by `tryOcr`'s own
  tail classification (the nested-call structure means that tail is the single, correctly-scoped
  terminal point for this path), so the internal call was both redundant and, after the fix,
  capable of disagreeing with the outer call (the two sites' local `baselineQuality` values use
  different `TextQualityAnalyzer.computeQualityScore` overloads). Removing it is a net
  simplification, not new complexity.
- **The mixed-PDF site needs no distinct reason code** — confirmed, not just theorized:
  `trySelectivePdfOcr` only runs when `summary.mixedPdf()` is true, which structurally
  guarantees real baseline text exists on other pages, so its own local `baselineQuality`
  (moved out of a narrower `if`-block scope during implementation to stay in scope for the fix)
  is always correctly gated by the same single classifier.

**New**: `OcrOutcomeClassifier.java` (pure, mirrors `AdmissionPolicy`'s shape exactly) +
`OcrOutcomeClassifierTest.java` (exhaustive-mapping + injectivity, matching
`AdmissionPolicyTest`'s shape). **Changed**: `OcrSkipReason.java` (+`NO_TEXT_FOUND`),
`PolicyDrivenTikaExtractor.java` (3 call sites), `PolicyDrivenTikaExtractorTest.java` (+1
permanent regression test — a genuinely blank image through the real pipeline with real
Tesseract, promoted from this tempdoc's earlier throwaway verification test), and
`governance/logic-seams.v1.json` (+1 seam, `ocr-outcome-classifier`).

**Verification, all real (not just planned):**
- `./gradlew.bat build -x test` and the full `./gradlew.bat test` suite: green.
- New tests genuinely execute (confirmed via JUnit XML `skipped="0"`), not skipped —
  Tesseract 5.5.0 is genuinely available in this environment.
- PIT mutation testing on the new seam: **100% strength, 0 no-coverage** (3/3 mutations killed)
  — the guard test provably bites. Baseline set via
  `node scripts/governance/run.mjs --gate test-efficacy --rebalance`; `test-efficacy` gate passes.
- **Live-verified against the real dev stack**, not just unit tests: after a false start (the
  dev stack defaults to launching from the *main checkout's* installed dist even when called
  from inside a worktree — logged as a separate observation; fixed by passing `distFrom`
  explicitly), a fresh ingest of the real `golden/synth-scan-v1` corpus through the fixed build
  showed both `olmby1.png` and `rellgrove4.png` now reporting
  `"ocrSkipReason":"no_text_found"` (previously `"textual"`) in the live
  `visualExtractionEvidenceJson`.
- **Browser-verified**: opened the real web UI, searched for `olmby1.png`, opened the Inspector
  pane — no crash, no visual breakage. One real (pre-existing, not a regression) UI finding:
  `InspectorPane.ts`'s "Text source" detail line (which would generically render
  `"OCR skipped: no text found"` via its existing `.replace(/_/g, ' ')`) is gated behind having
  non-empty preview text (`if (!this.previewText) return "No preview available."`), so for a
  genuinely zero-content document — exactly this bug's class — the corrected reason never
  reaches this particular UI surface today. This was equally true before the fix (with the wrong
  reason hidden the same way), so it's not a regression; it's a pre-existing UI gap, logged as an
  observation rather than fixed here (out of this tempdoc's scope — it's about the classifier
  being correct, not about surfacing zero-content diagnostics in the Inspector, which would be
  its own small feature).

### Follow-up (2026-07-02): critical-analysis pass closed a real test-coverage gap

A post-implementation critical-analysis pass found the "As-built" work above had only closed
the tempdoc's own stated testing gap ("no existing test exercises the case where OCR is
attempted, genuinely finds nothing") for one of the three non-trivial call sites — the raster
image path (`tryOcr`'s tail). `trySelectivePdfOcr` (mixed-PDF) and `tryRenderedPdfOcr`
(image-only-PDF) — the two sites with the trickiest changes (a moved variable scope, and removed
code) — had no test proving the wiring was correct beyond the pure classifier's own unit test.
Two new permanent regression tests close this:

- `realTesseractRuntimeKeepsTextualForMixedPdfWithUnreadableImagePage` — a mixed PDF (real text
  page + an unreadable page) through the real pipeline. Passes, and **empirically confirms**
  (not just statically argues, as the original design pass did) that `trySelectivePdfOcr` never
  needs its own distinct reason code: a mixed PDF's baseline text always survives, so its
  no-improvement tail correctly stays `TEXTUAL`.
- `realTesseractRuntimeNeverMislabelsTextualForImageOnlyPdfWithBlankPage` — an image-only PDF
  with a blank page. This surfaced a genuine, unexpected finding while writing it: real
  Tesseract on a `PDFRenderer`-rasterized blank PDF page deterministically returns a few stray
  noise characters in this environment (unlike a raw synthetic blank PNG, which reliably returns
  truly empty text — see the tempdoc's other, raster-image regression test). Combined with a
  pre-existing, unrelated `mergedQuality >= baselineQuality` check where both sides are `0`, that
  noise gets promoted to the *success* branch (`route: "ocr_full"`) instead of reaching the
  no-improvement tail this test originally set out to exercise. Rather than force a brittle
  fixture, the test asserts the invariant tempdoc 671 actually cares about regardless of which
  branch fires: the document must never be mislabeled `"textual"`, and if a skip reason is
  recorded at all, it must be `"no_text_found"`. Logged as an observation for future test authors
  in this codebase (prefer raw raster fixtures over rendered-PDF fixtures when a test needs a
  guaranteed-empty OCR result).

Both new tests pass, execute for real (not `assumeTrue`-skipped), and the full test suite +
`test-efficacy` gate remain green — the `ocr-outcome-classifier` seam's PIT strength is unchanged
(100%, 0 no-coverage), as expected since its `targetClass` is the pure classifier only. A second,
minor finding from the same pass — pre-existing metric double-counting on an internal-exception
path in `tryRenderedPdfOcr`, unrelated to this tempdoc's scope and not a regression — was logged
as an observation rather than fixed.

## Confidence-building pass on the remaining, unbuilt work (2026-07-02)

§Long-term design, part 2 (the `ExtractionStatus` fix) and the Inspector UI fix (§Post-
implementation research idea 1) were both designed from static reading plus one subagent
investigation each. This pass ran real code and read real call sites to convert the riskiest
unverified claims into confirmed facts. No code was changed by this pass (one throwaway test was
written, run, and deleted — not committed).

- **The `ExtractionStatus` bug is confirmed broader than OCR — it is not a "scans" problem, it is
  a general `PolicyDrivenTikaExtractor` problem.** Fed a genuinely empty `.txt` file and a
  whitespace-only `.txt` file through the real extractor (no OCR involved at all — plain text
  isn't OCR-eligible, confirmed via `isOcrEligibleFile`,
  `PolicyDrivenTikaExtractor.java:626-630`). Both came back `status=SUCCESS_FULL` with empty
  content, live, today. This matters for scoping the eventual fix: it should not be framed or
  built as an OCR-adjacent change — it's a defect in `ExtractionArtifact.full()`'s own default
  behavior (`truncated ? SUCCESS_PARTIAL : SUCCESS_FULL`, never inspecting content), reachable
  from any file type Tika parses to nothing, which is a strictly larger and more common class of
  document than degraded scans.
- **A third, independent call site reaches the same defaulting behavior**, not found by the
  prior pass: `ExtractorContributionRegistry.java:134` — the Worker's third-party/plugin
  extractor-contribution composer (tempdoc 560) — calls the *same* `ExtractionArtifact.full(result,
  parserId)` two-argument overload, which `ExtractionArtifact.java:63-74` always resolves to
  `SUCCESS_FULL` regardless of content. Its default `withCoreTika()` composition routes most
  calls through `PolicyDrivenTikaExtractor` anyway (the same root cause), but a genuinely
  third-party, installed extractor plugin that returns empty content for its own reasons would
  independently get the exact same false "full success" label, through a code path this
  tempdoc's design work had not considered. Confirms the fix belongs at the shared factory
  (`ExtractionArtifact.full`), not scattered per-caller — the design already recommended this
  shape; this raises confidence it's the *necessary* shape, not just the tidier one.
- **A fourth, wider-scoped consumer exists that the design work did not account for**:
  `IngestionOutcomeClass` (`modules/worker-core/src/main/java/io/justsearch/indexerworker/ingest/IngestionOutcomeClass.java:5-18`)
  is a *separate*, coarser enum for the whole ingestion boundary (also has its own `SUCCESS_FULL`/
  `SUCCESS_PARTIAL`), built via `IngestionOutcomeJournal.fullSuccess()`
  (`IngestionOutcomeJournal.java:184-186`) and directly asserted in tests
  (`IndexingLoopTest.java:817`: `assertEquals(IngestionOutcomeClass.SUCCESS_FULL,
  queue.lastOutcome.outcomeClass())`). This pass did not fully trace how (or whether)
  `ExtractionStatus` currently feeds `IngestionOutcomeClass`'s own success/partial decision — that
  wiring needs to be read in full before implementing, since a real, tested consumer exists one
  layer up that a naive `ExtractionStatus`-only fix could leave inconsistent (e.g. a document
  correctly labeled `extraction_status=SUCCESS_EMPTY` but still rolled up into
  `IngestionOutcomeClass.SUCCESS_FULL` at the ingestion-boundary level, reintroducing the same
  conflation one layer higher). This is the single most consequential open unknown from this pass.
- **No UI-web consumer of `extraction_status`/`ExtractionStatus` exists** — a repo-wide grep for
  the field name across `.java`/`.ts` files found only backend Java files (extraction, ingestion,
  status-schema tests); nothing in `modules/ui-web`. Adding a 6th value carries no frontend
  blast radius.
- **`SUCCESS_PARTIAL`'s semantics are confirmed narrow and non-overlapping**: it is set purely by
  `truncated` (a size/char-limit cutoff), never referenced by name anywhere else in the `extract`
  package's main code. A new "ran clean, produced nothing" value would not collide with or
  redefine what `SUCCESS_PARTIAL` already means.
- **The Inspector UI fix needs no backend change — confirmed, not just plausible.**
  `PreviewController.java:192-194` (`response.put("visualExtractionEvidence", ...)`) and
  `PreviewController.java:172` (`response.put("content", slice.content())`) both write
  unconditionally — the evidence field is never omitted or gated on content length. Combined with
  the live evidence already captured earlier in this tempdoc (`olmby1.png`'s
  `visual_extraction_evidence` was present and correct post-fix), this confirms `/api/preview`
  already sends everything the Inspector fix needs; the fix really is scoped to
  `InspectorPane.ts`'s render-order alone, as designed.
- **The proposed Inspector fix is very unlikely to trip the ui-web gates flagged as a risk.**
  Read `check-presentation-purity.mjs` and `check-layout-purity.mjs` in full: the former guards
  hand-written `ops.*.label` keys and a specific resolver-import ban (neither applies — the fix
  reorders existing template logic, adds no new label keys or resolver imports); the latter
  guards top-level `jf-*-surface` layout and `position: fixed` placement (also inapplicable —
  `InspectorPane` is a component, not a surface, and the fix touches no positioning). Confidence
  this specific concern was overstated in the design pass, not that the fix is risk-free in
  general (still needs `npm run typecheck && npm run test:unit:run` before merging, as always).

## Status

**The scoped reason-code fix is implemented, tested, and live-verified — see §As-built.** Six
passes total: (1) the original stub; (2) a follow-up resolving "does this block VDU" (mislabel
and stuck `vdu_status: PENDING` are separate problems); (3) a design pass grounding the fix in
`SearchReasonCode` + the `admission-policy` logic-seam precedent; (4) an external web-search
pass on Tesseract/VLM landscape; (5) a confidence-building pass live-verifying the diagnosis
against real code and the real OCR engine; (6) **implementation** — the fix shipped per §As-built,
including two refinements (a fourth affected call site, and confirmation the mixed-PDF site
needs no distinct code) found only once the code was actually written and tested.

**Update (2026-07-02, §As-built round 2): items 3 and 4 below are now SHIPPED**, along with the
`VduCapabilityState` guard tests. Only the VDU-trigger wiring gap (item 1) and the optional
Tesseract-preprocessing idea (item 2) remain open — both were always explicitly out of scope for
the reason-code work and still are.

Remaining, explicitly out-of-scope follow-on work — not touched by any of this tempdoc's
implementation passes, tracked here for whoever picks it up next:

1. Diagnose and fix why `BrainRuntimeServiceImpl.offlineProcessingTrigger` is null in a
   dev-stack launch (§Confidence-building pass's follow-up) — `mmproj-F16.gguf` is staged in
   the shared models root and VDU readiness reports `READY`, but the manual
   `POST /api/offline/process` trigger still fails with `SERVICE_UNAVAILABLE` because
   `OfflineCoordinatorBuilder.build()` didn't run (or didn't succeed) at Head bootstrap in that
   launch path. This is the actual remaining blocker for live-verifying the VDU-trigger
   hypothesis in §Follow-up, not the model artifact.
2. If pursued, Tesseract preprocessing (deskew/denoise, §External landscape check) as a
   separate, optional improvement — unrelated to the reason-code fix.
3. ~~The pre-existing Inspector UI gap...~~ **SHIPPED (§As-built, round 2).** `InspectorPane.ts`
   now renders the "Text source" diagnostic for zero-content documents; live-verified in the
   real browser against a real ingest.
4. ~~**The `ExtractionStatus` fix**...~~ **SHIPPED (§As-built, round 2).** Both seams
   (`ExtractionOutcomeClassifier` and `IngestionSuccessClassifier`) implemented, tested at 100%
   PIT strength, and live-verified: `olmby1.png` now reads `extraction_status: SUCCESS_EMPTY`,
   `extraction_reason_code: SUCCESS_EMPTY`, not the previous mislabeled `SUCCESS_FULL`.

### Confidence rating: 8/10 for the scoped reason-code fix (shipped); 3/10 for the VDU/retrieval-quality follow-on

The part of this tempdoc that's actually ready to implement — fixing `OcrSkipReason`'s
conflation, per §Long-term design — is now backed by a live reproduction against real code, the
real OCR engine, and the real corpus files that discovered the bug (re-confirmed twice: once at
the unit level, once through a full live ingest of the whole corpus); a confirmed, near-zero
blast radius; a structurally-checked (not just analogized) extraction shape; and a known,
mechanical seam-registration bootstrap. The residual uncertainty is ordinary implementation
risk (getting the mixed-PDF distinction right, writing property/mutation-adequate tests to
satisfy `test-efficacy`), not open unknowns about whether the diagnosis is correct.

The VDU follow-on (§Broader framing direction 5, whether VDU actually resolves these documents
once triggered) is **still unverified, and the rating moved down, not up**, despite real
progress: the model-availability blocker this pass originally (wrongly) reported is fully
resolved — `mmproj-F16.gguf` is staged, hash-verified, and VDU readiness reports `READY` — but
attempting the actual live trigger surfaced a second, independent blocker (the
`offlineProcessingTrigger` wiring gap) that was invisible until the first blocker was cleared.
That's the opposite of a confidence increase: it demonstrates that "readiness looks green"
undersells the true precondition count for this specific follow-on, so the honest estimate is
lower, not higher, than the pre-staging guess of 4/10. It should still not be scoped into the
same implementation pass as step 1 above — it's unrelated machinery (Head bootstrap wiring, not
extraction routing) — but whoever picks it up next should expect at least one more layer to
debug before a live VDU resolution can be observed.

### Recommended model/effort for implementation

The scoped fix (§Long-term design step 1, ~3 call sites in one file + one enum + one new test
file + one seam registration) is mechanical, well-precedented (mirrors `AdmissionPolicy`
almost exactly), and low-ambiguity after this pass — **Sonnet at medium-to-high effort** is
sufficient and appropriately cost-efficient; the design decisions that would otherwise need
Opus-level judgment (which existing pattern to conform to, whether to split the type, what the
blast radius is) were already resolved by the design and confidence-building passes, so the
implementing session is mostly execution against a concrete, cited plan rather than open-ended
design work. Reserve Opus/higher effort for two specific sub-decisions that remain genuinely
judgment calls rather than mechanical: (a) whether the mixed-PDF call site earns its own
`OcrSkipReason` value or shares one with the raster-image case, and (b) writing the
mutation-adequate guard test(s) well enough to clear the `test-efficacy` gate's strength floor
on the first PIT run (weak tests that pass functionally but don't "bite" under mutation are a
known failure mode for new seams). A Fable/very-high-effort pass is not warranted here — this
is a contained, single-subsystem bug fix with an already-validated design, not an open-ended or
cross-cutting problem.

**Post-implementation note**: this held up. The fix landed exactly at the predicted footprint
(3 call sites + 1 enum value + 1 new pure class + 2 test files + 1 seam registration, plus one
additional call site found only during the actual re-read of the file for implementation), the
guard test cleared the `test-efficacy` gate at 100% mutation strength on the first PIT run
(no weak-test iteration needed), and the mixed-PDF question resolved to "no distinct code
needed" rather than requiring the harder sub-decision the effort note flagged as a real
judgment call — the prior passes' analysis was precise enough that implementation was, in
practice, close to mechanical.

## Post-implementation research: practicality, polish, and what this unlocks (2026-07-02)

A follow-up research pass (documentation-only — no code changed in this pass) asked two
questions the implementation itself didn't: **is the shipped fix actually practically useful
to anyone today**, and **what does it now make cheap or possible that wasn't before**. Findings
below are ideas and analysis, not decisions — none of this is scoped or committed.

### Honest practicality check: who benefits from the fix as it stands today?

- **End users: effectively nobody, yet.** `ocrSkipReason` is a debug/evidence field. It isn't
  rendered anywhere in the normal search flow, and — per the Inspector UI gap already logged in
  §As-built — it isn't even reachable in the one UI surface that knows how to display it,
  because that surface is gated behind having non-empty preview text (exactly the case this bug
  is about). A user hitting this bug today still just sees a document that doesn't turn up in
  content search, with no visible explanation anywhere in the product. The fix makes the
  *diagnosis* honest; it does not yet make the *symptom* visible or fixed.
- **Developers/operators debugging this class of issue: real, immediate value.** Anyone
  querying `/api/knowledge/search` debug output or reading Worker logs now gets an accurate
  reason instead of a misleading one — this is the audience the fix was actually built for
  today, and it was already exercised for real during this tempdoc's own confidence-building and
  implementation passes (the mislabel is what made the original bug hard to root-cause at all).
- **Future consumers: this is where most of the value sits.** Tempdoc 658 (retrieval
  inspectability, still an open stub — re-read in full for this pass) explicitly lists
  "skipped-file and parser/OCR status" as something a future retrieval inspector should surface,
  and explicitly instructs against forking a second evidence authority — "project from existing
  trace/status/reason-code surfaces." A correctly-fixed `OcrSkipReason` is exactly the kind of
  surface that instruction points at. Today's fix is a prerequisite for that future work being
  honest, not a feature in its own right yet.

### Small polish opportunities in the shipped code

- The three (now four) call sites each compute `baselineQuality` slightly differently
  (`tryOcr`'s tail uses the single-argument `computeQualityScore(text)` overload; the two
  PDF-fallback sites use the two-argument, page-count-aware overload). This divergence is
  already the reason `tryRenderedPdfOcr`'s own internal skip call had to be removed rather than
  fixed in place (§As-built) — two sites computing "the same" quality differently is a
  standing, if currently harmless, source of confusion. A small polish: a single
  `resolveBaselineQuality(ExtractionResult baseline, StructuredDocumentSummary summary)` helper,
  used identically at all four sites, would remove the divergence entirely rather than just
  working around its one observed consequence.
- `OcrOutcomeClassifier.classifyNoImprovement(double baselineQuality)` takes a raw quality score.
  A slightly more self-documenting signature — e.g. `classifyNoImprovement(boolean
  hadAdequateBaselineText)` with the quality-to-boolean conversion done once at each call site —
  would make the law ("baseline was adequate → TEXTUAL, otherwise → NO_TEXT_FOUND") readable
  from the method signature alone, without needing to know `TextQualityAnalyzer`'s threshold
  semantics. Purely a readability trade; the current form is not wrong.
- Neither of these affects behavior or the seam's law — they're both "reduce the chance the next
  person reintroduces a variant of this bug" polish, not required by anything the tempdoc's
  design asked for.

### Extension ideas, roughly ordered by how directly they build on this fix

1. **Fix the Inspector UI gap logged in §As-built** (investigated this pass, not implemented).
   Confirmed genuinely small: `InspectorPane.ts`'s preview API response (`/api/preview`) already
   populates `previewEvidence`/`previewProvenance` independently of whether `previewText` is
   empty — the gate that hides the "OCR skipped: no text found" line for zero-content documents
   is purely a rendering-order issue (the evidence-detail block sits after an early return keyed
   on `previewText` alone), not a missing backend capability. A matching unit test pattern
   already exists in `InspectorPane.test.ts` to copy. This is the most direct way to make this
   tempdoc's fix visible to an actual user for the first time, rather than only to log/API
   readers.
2. **Audit `VduCapabilityState`'s reason-mapping**, which this tempdoc's "Naming the underlying
   principle" section named as an unaudited candidate. Checked this pass: it does **not** have
   the same conflation bug (`OcrSkipReason.TEXTUAL`'s shape — one code covering two different
   causes) — each of its four blockers already maps to a distinct reason. It does have a
   *different*, smaller risk worth a follow-up: `block()` silently no-ops on any reason code
   outside its known set, and it holds only one active reason in a single mutable slot shared
   across two classes (`VduBatchProcessor`, `OfflineCoordinator`), so if two blocking causes are
   true simultaneously (e.g. insufficient VRAM *and* missing mmproj at once), only the
   most-recently-set one survives — the other is silently lost, not merged or queued. No guard
   test exists for this today. Smaller in scope than the `OcrOutcomeClassifier` extraction (this
   is closer to "add 2-3 assertions to an existing test" than "extract a new pure seam"), but a
   real, previously-unverified gap in a sibling subsystem this tempdoc's own investigation named.
3. **An aggregate "documents with no extractable text" count**, echoing the tempdoc's own
   deferred "broader principle" (§Broader framing: *"documents indexed with no meaningfully
   extracted content should be distinguishable and countable as a class"*). Investigated the
   feasibility this pass: `/api/status`'s existing `visualExtraction` block already has exactly
   this shape of counter (`visualTextNeededCount`, `visualEnrichmentNeededCount`,
   `IndexStatusOps.java:305-314`), built on a generic, already-used Lucene term-count helper
   (`countPendingByStatus(field, value)`, `IndexStatusOps.java:865`). The blocker: `ocrSkipReason`
   is **not** its own indexed field today (confirmed — no entry in
   `SSOT/catalogs/fields.v1.json`) — it only lives inside the `visual_extraction_evidence` JSON
   blob, which isn't cheaply term-countable. So this idea is genuinely cheap to *query* once a
   small schema addition exists (a first-class `ocr_skip_reason` field, or simpler, a boolean
   `has_zero_extracted_text` flag set alongside the existing text fields), but that schema step
   itself is a real prerequisite (touches the dual-copy SSOT catalog sync, per this repo's own
   `ssot-catalog-sync` gate), not a query-only change.
4. **Feed `ocrSkipReason` (correctly, post-fix) into the future retrieval inspector** (tempdoc
   658). Not actionable yet — 658 is still an open stub with no design — but this fix removes
   one reason that future work would have inherited wrong data if built before this bug was
   caught.
5. **Expose extraction completeness as an agent/MCP-queryable facet.** JustSearch's own stated
   identity (per its `docs/llms.txt`/system-overview framing referenced earlier in this session)
   is as a local retrieval backend other AI agents connect to over MCP. An agent searching a
   user's files today has no way to ask "which of these results might be missing body text
   entirely" — a correctly-labeled `ocrSkipReason`, once promoted to an indexed/filterable field
   (idea 3's prerequisite), would make that a natural, low-effort MCP-tool addition later. Purely
   speculative — no design work done here, flagged only because it's a distinctive angle for a
   product whose primary consumer is sometimes an agent, not a human.

### External research: how comparable products handle this (informational, not adopted)

A few searches were run to see whether other local-first document-search/PKM tools have already
solved "tell the user this file's text couldn't be read" well enough to borrow from, rather than
inventing UX from scratch:

- Enterprise/cloud search platforms (Azure AI Search, AWS/OpenSearch) treat failed extraction as
  a first-class **indexer-run status**, not a per-document silent field — failed documents show
  up in an "execution history" list with hover-for-detail error messages, and dedicated
  "index error count" alerts exist for operators
  ([Indexer Errors and Warnings — Azure AI Search](https://learn.microsoft.com/en-us/azure/search/cognitive-search-common-errors-warnings),
  [Index Error Count — SearchStax](https://www.searchstax.com/docs/hc/index-error-count/)). The
  closest local-scale analog to idea 3 above (an aggregate, always-visible count) would be
  JustSearch's own `/api/status` / Library or Health surface, which already carries comparable
  enrichment counters.
- Local-first, self-hosted document tools closer to JustSearch's own category (Paperless-ngx)
  document OCR failure as a known operational condition tied to specific causes (missing
  Tesseract language packs, OCR engine misconfiguration) surfaced through logs/troubleshooting
  docs rather than a polished in-app diagnostic
  ([Paperless-ngx troubleshooting](https://docs.paperless-ngx.com/troubleshooting/),
  [OCR fails for many uploaded PDFs — paperless-ngx#3593](https://github.com/paperless-ngx/paperless-ngx/issues/3593)) —
  i.e. even a mature, widely-used tool in this exact space largely stops at "the reason is in the
  logs," which is roughly where this tempdoc's fix currently leaves JustSearch too. This is a
  useful calibration: closing the gap between "correct reason in a debug field" (done) and "a
  polished in-app explanation" (idea 1) would put JustSearch modestly *ahead* of at least one
  well-established comparable tool, not just catching up.
- No existing product research surfaced a ready-made UX pattern specifically for the
  agent-facing angle (idea 5) — unsurprising, since "an AI agent queries which documents failed
  extraction" is a newer, less-established interaction shape than a human-facing dashboard.
  Flagged as an open, JustSearch-specific opportunity rather than something to copy from
  elsewhere.

### Summary judgment

None of the above is scoped, prioritized, or decided here — this section is deliberately just
research and ideas, per the pass that produced it. If asked to rank: idea 1 (Inspector UI fix)
is the highest-leverage-for-effort next step, since it's the only one of the five that would
make this tempdoc's already-shipped backend fix visible to an actual person using the product,
and it was confirmed small during this pass. Idea 2 (`VduCapabilityState` guard test) is the
cheapest pure-correctness follow-up. Ideas 3-5 are real but each carries a genuine prerequisite
(a schema change, an unbuilt future tempdoc, or a not-yet-existing MCP surface) that make them
next-next steps rather than immediate ones.

**Correction from a later pass (§Long-term design, part 2 below):** idea 3's "schema change"
prerequisite turned out to be smaller and differently-shaped than described above — the
cross-extractor field it needs already exists (`extraction_status`); it just currently has the
same conflation bug this tempdoc fixed in `OcrSkipReason`, one level more foundational. Read the
next section before treating idea 3's framing above as current.

## Long-term design, part 2: the same law is violated one level deeper, in a field that already exists (2026-07-02)

A further design-theorization pass — triggered by idea 3 above ("promote `ocrSkipReason` to an
indexed field so it can be counted in aggregate") — asked the question this tempdoc's own
discipline requires before building anything new: does a durable, cross-extractor place for
"was this document's content actually usable" already exist? It does, and it changes the
recommended shape of idea 3 substantially.

### What already exists

`SchemaFields.EXTRACTION_STATUS` (`modules/indexing/src/main/java/io/justsearch/indexing/SchemaFields.java:193`,
paired with `EXTRACTION_REASON_CODE` at line 206) is an **already-indexed, cross-extractor**
field — not OCR-specific, not new — recording "trust/provenance status of the parser output"
with values `SUCCESS_FULL | SUCCESS_PARTIAL | FAILED | TIMED_OUT | BUDGET_EXCEEDED`
(`io.justsearch.indexerworker.extract.ExtractionStatus`). This is exactly the concept
§Broader framing's "possible broader principle" speculated might need building from scratch —
*"documents indexed with no meaningfully extracted content should be distinguishable and
countable as a class, independent of which extractor or code path produced that outcome"* — and
it already exists, already indexed, already paired with a reason-code sibling
(`IngestionReasonCodes`, the exact same architectural shape as `OcrSkipReason`/`SearchReasonCode`).
Per this tempdoc's own recurring discipline (conform to existing structure before building new),
any design for "count documents with no real content" belongs here, not in a new
`ocr_skip_reason`-indexed field or a parallel counter mechanism.

### It has the same bug this tempdoc just fixed — confirmed, not theorized

Investigated this pass (not assumed): `ExtractionStatus` is set ad hoc at each
`ExtractionArtifact` construction site, and every one of them decides `SUCCESS_FULL` vs.
`SUCCESS_PARTIAL` purely from a `truncated` boolean (`ExtractionArtifact.java:76-89`) — never
from whether `result.content()` is actually non-empty. `FAILED`/`TIMED_OUT`/`BUDGET_EXCEEDED`
are reserved for pipeline-level exceptions only. Traced the exact `olmby1.png` case this tempdoc
fixed: after `tryOcr` returns `null` and `extractArtifact` falls through to the structured
fallback (`PolicyDrivenTikaExtractor.java:154`), `truncated=false`, so
`ExtractionArtifact.full(...)` sets `status = SUCCESS_FULL` — on a document with zero extracted
characters. This is confirmed by live evidence captured earlier in this tempdoc's own passes:
the same `olmby1.png` sample shows `"extraction_status":"SUCCESS_FULL"`. `IngestionReasonCodes`
has the identical gap on the sibling reason-code side — no existing value fits "pipeline
completed cleanly, zero content produced," the same absence this tempdoc found in
`OcrSkipReason` before the fix.

In plain terms: fixing `OcrSkipReason` (already shipped) corrected the OCR-specific evidence
field, but the *more authoritative, cross-extractor* field a reader is more likely to actually
consult still unconditionally claims full success on the exact same document. This is a second,
confirmed instance of the law this tempdoc names — one level deeper than the field this tempdoc
already fixed, and one this tempdoc's own "broader principle" section anticipated without yet
having proof.

### The design (theorized — not built in this pass)

Conforming to structure that already exists, twice over:

- **Extend `ExtractionStatus` with a distinct "ran clean, produced nothing" value** — the
  codebase already has the exact naming convention for this, established and thoroughly tested
  elsewhere in the very same subsystem: `SchemaFields.VDU_STATUS_COMPLETED_EMPTY`
  (`modules/indexing/src/main/java/io/justsearch/indexing/SchemaFields.java:73`), with its own
  gRPC outcome (`VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY`) and real test coverage
  (`GrpcIngestServiceVduHardeningTest.java`, `PreviewControllerTest.java`). VDU already
  distinguishes "produced text" (`COMPLETED`) from "ran fine, found nothing" (`COMPLETED_EMPTY`).
  `ExtractionStatus` should adopt the identical shape — a `SUCCESS_EMPTY` value (name not
  decided) alongside `SUCCESS_FULL`/`SUCCESS_PARTIAL` — rather than inventing new vocabulary.
- **Move the classification off the ad hoc, five-call-site pattern onto a total, injective, pure
  function**, the same template this tempdoc already used twice (`OcrOutcomeClassifier`,
  mirroring `AdmissionPolicy`): given (content-empty?, truncated?, exception-occurred?), return
  the correct status. Register it as a logic seam once the guard test exists, the same mechanical
  path already exercised in this tempdoc's own implementation.
- **Add the matching `IngestionReasonCodes` value** so `EXTRACTION_REASON_CODE` is no longer
  null on this path — currently it stays unset for the OCR-fallthrough case specifically because
  nothing computes a reason for "succeeded, produced nothing."
- **This directly and more cheaply satisfies idea 3** from the prior research section: once
  `ExtractionStatus` correctly reports the empty case, `IndexStatusOps`'s existing counting
  pattern (`countPendingByStatus(field, value)`, already used for `visualTextNeededCount` etc.,
  `IndexStatusOps.java:865,305-314`) can count `extraction_status = <the new value>` directly —
  **no new indexed field, no SSOT catalog change** — because `extraction_status` is already
  indexed today. This is cheaper and more architecturally correct than idea 3's original framing
  (promote `ocrSkipReason` itself to an indexed field), which would have built a second,
  OCR-specific authority sitting alongside a first, more general one that already exists.
- **A stronger-than-"gate" option, per this codebase's own documented prevention ladder**
  (tempdoc 548/557: *Collapse > Unrepresentable-by-type > Generate > Gate*, strongest first —
  the mutation-tested-seam approach this tempdoc has used twice sits at "Gate," the weakest tier
  that still counts as real prevention): worth asking, not deciding, whether `ExtractionStatus`
  could be pushed to "Unrepresentable" — e.g. by having `ExtractionArtifact`'s own factory
  compute status *from* the content it's given, rather than accepting status as an
  independently-suppliable parameter a caller could get wrong. Not evaluated for feasibility in
  this pass; named because the codebase already has a vocabulary for ranking how strong a fix is,
  and "add a guard test" is not automatically the strongest available option.
  - **External grounding, checked this pass (2026-07-02 web search):** "make illegal states
    unrepresentable" is an established, well-sourced principle (coined by Yaron Minsky, 2010,
    popularized more broadly by Richard Feldman's 2016 "Making Impossible States Impossible" —
    see the survey at [aipatternbook.com](https://aipatternbook.com/make-illegal-states-unrepresentable)),
    so the terminology this tempdoc borrows from 548/557 is not a local invention. There is also
    a documented, reasoned critique worth weighing before leaning on it too hard:
    ["'Make invalid states unrepresentable' considered harmful"](https://www.seangoedecke.com/invalid-states/)
    argues the *harder* a constraint is to change later (the author's examples are DB foreign-key
    constraints and required fields in a cross-service protobuf contract — infrastructure that
    spans process/deployment boundaries), the more it becomes a liability once real-world
    requirements need an exception the model didn't anticipate. That critique's actual target —
    hard, cross-boundary constraints — does not obviously apply to what's proposed here: a single
    factory method inside one process deciding one field from data it already has, trivially
    revisable in one file. Still, it's a real reason not to over-invest in this beyond that local
    scope (e.g. not worth encoding as a database/schema-level constraint) — noted as a boundary
    on the idea, not a reason to drop it.
  - **Separately, external research (2025-2026) on production RAG/document pipelines confirms
    this bug *class* — not just this specific instance — is an actively-recognized, commonly-cited
    failure mode right now**, not a narrow or theoretical concern: "silent failures... poison
    downstream accuracy without anyone noticing" and empty/meaningless chunk extraction are
    named among the most common production RAG failure causes in current writeups
    ([Your Chunks Failed Your RAG in Production](https://towardsdatascience.com/your-chunks-failed-your-rag-in-production/),
    [Why RAG Pipelines Fail in Production](https://forage.ai/blog/why-rag-pipelines-fail-in-production/),
    [RAG Failure Mode Checklist — LlamaIndex](https://developers.llamaindex.ai/python/framework/optimizing/rag_failure_mode_checklist/)).
    This is useful calibration, not a new argument: it means the underlying defect class this
    tempdoc (and its theorized `ExtractionStatus` follow-on) addresses is one of the more common,
    currently-discussed ways document-search/RAG systems silently degrade in the wild — the
    priority judgment already implicit in fixing it stands on more than this codebase's own
    internal reasoning alone.
- This does **not** reach into `EMBEDDING_STATUS`, `CHUNK_EMBEDDING_STATUS`, `NER_STATUS`, or
  `SPLADE_STATUS` (the sibling `PENDING/COMPLETED/FAILED` triads in the same `SchemaFields.java`)
  — flagged as candidates for the same audit in the next section, not touched here.

### Naming the underlying principle, again — now with a second confirmed instance

This tempdoc already named the law: *reason-code / outcome-classification mappings must be total
and injective, or diagnostics silently conflate causes that look identical downstream but
aren't.* A sharper, complementary way to state the specific shape found twice now in this
codebase: **a "did the pipeline run without erroring" signal and a "did the pipeline produce a
real result" signal are two different questions, and any status field that only tracks the first
will silently answer "yes" to both.** `OcrSkipReason.TEXTUAL` conflated a routing decision with
an extraction failure; `ExtractionStatus.SUCCESS_FULL` conflates "no exception, no truncation"
with "produced usable content" — the same shape, one layer up.

Where this is now **confirmed** (not just theorized) to hold or fail in this codebase:

- **Violated**, confirmed this pass: `ExtractionStatus` (this section).
- **Fixed**, per this tempdoc's own earlier work: `OcrSkipReason`.
- **Held correctly, with an established naming convention worth reusing**: `VduBatchProcessor`'s
  `VDU_STATUS_COMPLETED_EMPTY` already distinguishes "ran, found nothing" from "ran, succeeded" —
  this is the precedent the `ExtractionStatus` fix above should copy, not invent past.
- **Untested/unaudited candidates**, named but not investigated in this pass: `EMBEDDING_STATUS`,
  `CHUNK_EMBEDDING_STATUS`, `NER_STATUS`, `SPLADE_STATUS` — each a `PENDING/COMPLETED/FAILED`
  triad in the same file; whether any of them can report `COMPLETED` for a degenerate result
  (e.g. a zero vector, an empty entity list) the same way `ExtractionStatus` can report
  `SUCCESS_FULL` for empty content is unknown and not claimed here.

This pass, like the ones before it in this tempdoc, deliberately stops at naming where the
principle is confirmed to hold or fail and does **not** propose a codebase-wide audit sweep or a
new cross-cutting enforcement mechanism — `governance/logic-seams.v1.json` already is that
mechanism, and registering the `ExtractionStatus` classifier there (once built) is the
correctly-scoped action, matching exactly what this tempdoc already did for `OcrSkipReason`.
Building that fix is not undertaken in this pass — this section is design theorization only, per
its own instructions.

## Confidence rating and recommended effort for the remaining (unbuilt) work

Two genuinely different pieces of remaining work, rated separately — they should not be
implemented in the same pass, and not by the same effort tier.

### The Inspector UI fix (§Post-implementation research idea 1): 8/10, low difficulty

Confirmed this pass to need no backend change, to have an existing, directly-analogous test to
copy (`InspectorPane.test.ts`'s `'renders OCR provenance for preview text'`), and to be unlikely
to trip either ui-web gate initially flagged as a risk. The only residual uncertainty is ordinary
implementation care (getting the render-order restructuring right without regressing the
existing `!previewText` empty-state message). **Sonnet at low-to-medium effort** is sufficient —
this is a small, well-scoped, single-file rendering-logic change with a clear existing test
pattern to follow, not a design problem.

### The `ExtractionStatus` fix (§Long-term design, part 2): 5/10, meaningfully harder than it first looked

Higher uncertainty than the already-shipped `OcrSkipReason` fix was at the equivalent point,
for a specific, named reason: this pass found the correct classifier design is unlikely to be a
straightforward analog of `OcrOutcomeClassifier`, because `ExtractionStatus`'s full value space
spans two different layers (in-process `truncated`-only decisions vs. sandboxed exception
handling for `FAILED`/`TIMED_OUT`/`BUDGET_EXCEEDED`), and because a real, tested, wider-scoped
consumer (`IngestionOutcomeClass`) exists that this pass could not fully trace in the time
available — whoever implements this must read that wiring in full before writing code, or risk
fixing `extraction_status` while leaving a higher-level field still claiming full success on the
same document. The confirmed-broader-than-OCR scope (plain text, third-party extractor plugins)
is good news for architectural correctness (one fix covers more cases) but bad news for
"how many places does this touch" — it is not the contained, single-file change `OcrSkipReason`
turned out to be. **Recommend Opus, or Sonnet at high effort with explicit instruction to fully
trace the `IngestionOutcomeClass` wiring before implementing** — the risk here is not that the
fix is conceptually hard (the design is sound and well-grounded, per §Long-term design, part 2),
it's that an implementer moving quickly could correctly fix `ExtractionStatus` and still leave
the bug's *effect* (a document that silently looks like a full success) alive one layer up. A
Fable/very-high-effort pass is not warranted — the remaining unknown is a specific, boundable
piece of tracing work (one enum's producer chain), not an open-ended design question.

### VDU-trigger wiring gap and everything else in §Status's remaining list

Unchanged from the earlier pass: still tracked, still explicitly out of scope for whoever
implements the above, no new confidence work done on it in this pass.

## As-built, round 2 (2026-07-02): the ExtractionStatus fix, the Inspector UI fix, and VduCapabilityState guard tests

All three remaining-work items above were implemented, tested, and live-verified in this pass —
following the plan built from the confidence-building pass, not a fresh design.

### The `ExtractionStatus` fix — two seams, exactly as the confidence-building pass predicted

- **Seam A** (per-artifact classification): `ExtractionOutcomeClassifier.classify(content,
  truncated)` (new, `modules/worker-services/.../extract/ExtractionOutcomeClassifier.java`),
  mirroring `OcrOutcomeClassifier` exactly — total, three-way mapping to the new
  `ExtractionStatus.SUCCESS_EMPTY` value. Wired into both `ExtractionArtifact.full()` overloads,
  which previously hardcoded status from `truncated` alone, never content.
- **Seam B** (ledger-transition classification): `IngestionSuccessClassifier.classify(artifactStatus)`
  (new, `modules/worker-services/.../loop/IngestionSuccessClassifier.java`) replaces
  `IngestionOutcomeJournal`'s inline `isPartialSuccessTransition` boolean with a total, three-way
  classifier; `drainPending()`'s 2-way partition became 3-way; a new `emptySuccess()` factory
  (mirroring `fullSuccess()`/`partialSuccess()`) and matching `IngestionOutcomeClass.SUCCESS_EMPTY`
  + `IngestionReasonCodes.SUCCESS_EMPTY` values were added. This is the seam the
  confidence-building pass flagged as undertraced before implementation — traced in full this
  round: `LedgerEntryFactory.forEnvelope()` sets the ledger's `artifactStatus` string directly
  from `artifact.status().name()`, so Seam A's fix flows into Seam B automatically; only Seam B's
  own classification of that string needed a second, explicit fix.
- **A third, previously-unfound consumer surfaced immediately at compile time**:
  `IndexingDocumentOps.deriveReasonCode()` has an exhaustive `switch` (no `default` arm,
  deliberately, per its own doc comment) over `ExtractionStatus` — adding `SUCCESS_EMPTY` was a
  compile error until a case was added. This is the codebase's own "Unrepresentable-tier"
  enforcement (§Long-term design, part 2's prevention-ladder discussion) working exactly as
  designed: the new value couldn't silently fall through with an unset `EXTRACTION_REASON_CODE`
  — the compiler forced the decision. The new case wires
  `IngestionReasonCodes.SUCCESS_EMPTY`, closing the exact "why is
  `EXTRACTION_REASON_CODE` still null for this document" gap the design pass predicted.
- Both classifiers are registered as logic seams (`extraction-outcome-classifier`,
  `ingestion-success-classifier`) with guard tests
  (`ExtractionOutcomeClassifierTest`, `IngestionSuccessClassifierTest`) — both measured at **100%
  PIT mutation-test strength, 0 no-coverage** on first run.
- The two tests the confidence-building pass identified as affected were updated, not deleted:
  `AdversarialCorpusIngestionTest.zeroByteFileReachesSuccessOnDrain` and
  `.malformedZipFailsTyped` now assert `SUCCESS_EMPTY` where they previously asserted the buggy
  `SUCCESS_FULL`. The tempdoc's own existing `PolicyDrivenTikaExtractorTest` regression tests
  (blank raster image, mixed PDF, image-only blank PDF) were extended to also assert
  `artifact.status()`, tying both the `OcrSkipReason` fix and this fix to the same documents.
- **Full-repo test suite green** (`./gradlew.bat test`, all modules) — no regressions from
  touching this shared ingestion/ledger code, confirming the confidence-building pass's blast-radius
  concern was real but fully contained.

### The Inspector UI fix

`InspectorPane.ts`'s `renderPreview()` now computes the provenance/evidence block before the
`!previewText` check and renders it in both the empty-state and content branches, exactly as
designed. A new test (`InspectorPane.test.ts`) covers the zero-content case. Live-verified
end-to-end, not just unit-tested: after a real ingest of the `golden/synth-scan-v1` corpus
against this build, `olmby1.png` (the tempdoc's own reproduction case) shows
`extraction_status: SUCCESS_EMPTY`, `extraction_reason_code: SUCCESS_EMPTY`, and
`ocrSkipReason: no_text_found` via the live search API, and the real browser UI's Inspector now
renders "Text source **VDU pending** — structured · 0% quality · OCR skipped: no text found"
where it previously showed a bare "No preview available." with no diagnostic at all — the exact
UX gap named at the start of this tempdoc's practicality research, now closed.

### `VduCapabilityState` guard tests

`VduCapabilityStateTest.java` (new) characterizes current behavior per the plan: each of the 4
known reasons round-trips correctly, unknown/null reason codes are confirmed no-ops, and the
single-slot-overwrite behavior is pinned as a test rather than changed (no design was committed
for changing it).

### Verification summary

Full repo build (`./gradlew.bat build -x test`) green; full repo test suite
(`./gradlew.bat test`) green; both new seams at 100% PIT strength; `test-efficacy` gate passes
after rebalance; ui-web unit suite green (3492/3493 passing — the one pre-existing failure,
`HealthLitView.test.ts`'s SSE-paused-badge-tone test, is unrelated to this work, untouched by
this session's diff, and reproduces in isolation on a clean checkout — logged as an observation,
not fixed, per this repo's "log pre-existing issues, don't fix them" discipline); live dev-stack
verification against a real ingest confirms both the backend fields and the real browser UI.

No PR opened — implementation stops here per instructions.

## Corpus availability note (2026-07-10, from 686)

A real binary-document corpus now exists: `mixed/realdocs-v1` (620 real PDF/office files incl.
scanned gov PDFs; pinned manifest at `scripts/jseval/666-corpora/realdocs-v1/`, rebuild via
`scripts/search/fetch-realdocs-corpus.py`). The OCR-skip routing this doc fixed can now be
exercised against a distribution of real documents instead of the handful of fixtures.
