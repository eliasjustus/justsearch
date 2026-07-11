---
title: "VDU extraction abstention gate: the vision model hallucinates plausible text on scans it cannot read, and that text is indexed as real document content with no confidence check anywhere on the output path"
type: tempdocs
status: "open — DESIGN SETTLED (2026-07-10, takeover session §Investigation + design pass): three-stage cheap-first cascade (input-legibility gate → same-call logprob/finish_reason/OCR-cross-check → seed-varied agreement probe) + new REJECTED_SUSPECT_TEXT outcome retaining baseline. Implementation not started; first implementation step is the live mmproj-logprobs probe. Original filing context: a live-observed production correctness defect (624 twenty-second pass, 2026-07-03); 607 owns routing, 671 reason-code truth, 672 wiring — this doc owns extraction OUTPUT quality/abstention."
updated: 2026-07-10
created: 2026-07-03
updated: 2026-07-03
author: agent retrospective (624 scan-battlefield session), filed by agent — STUB
category: vdu / extraction-quality / indexing-correctness / worker
related:
  - 624-agentic-retrieval-eval-rebuild        # origin of the evidence — the scan-corpus fidelity attempt that surfaced this
  - 672-vdu-offline-coordinator-bootstrap-wiring  # made VDU actually run at scale for the first time, which is what EXPOSED this
  - 671-tika-ocr-skip-routing-misclassification   # sibling lineage: extraction reason-code truthfulness
  - 607-vdu-ocr-extraction-logic-analysis         # adjacent owner of routing, explicitly NOT of output quality
principle: "an extractor that cannot read a document must say so — a confident wrong answer written into the index is strictly worse than an honest empty one, because search will serve it as truth and nothing downstream can tell the difference. (The extraction sibling of the engine's own funnel honesty: every stage must be accountable for what it emits, not just whether it ran.)"
---

> Noncanonical working tempdoc. STUB: goals and context only — no design decisions, no implementation
> specifics.

# 677 — VDU extraction abstention/confidence gate

## Goal

Ensure that when the VDU/vision extraction path cannot genuinely read a document, the pipeline
records an honest can't-read outcome instead of indexing whatever text the vision model produces —
so that unreadable inputs degrade to absence (searchable by filename/metadata, honestly flagged)
rather than to confident fabrication.

## Context — the live evidence (2026-07-03, first genuine full-scale VDU run post-672)

During tempdoc 624's scan-corpus fidelity attempt, all 360 synthetic degraded-scan documents were
processed end-to-end by the real VDU path (`vdu_status: COMPLETED`, `extraction_method: VDU`). The
degradation on these scans defeats the vision model — and instead of abstaining, **the model
confabulated fluent, plausible, entirely unrelated text** (observed verbatim: a generic bibliography
of mathematics-history books, for a document whose true content was a synthetic facility
description). That confabulated text was **written into the index as the document's real content**:
`content_preview` carries it, enrichment (embeddings/SPLADE) ran over it, and retrieval serves it.
Nothing on the output path — no confidence signal, no input-legibility check, no consistency probe —
distinguished this from a successful extraction. Retrieval quality over the corpus measured
nDCG@10 = 0.0000, which is how the fabrication was noticed at all; a production user's mixed corpus
would never produce such a clean tell.

Why this matters beyond the eval that found it: unreadable-in-practice documents (bad scans, photos,
degraded faxes, exotic layouts) are core to the product's own pitch for VDU. For every such document
today, the failure mode is silent index poisoning — search results that confidently attribute
fabricated content to a real file. This is an indexing-correctness defect, not an eval concern; the
eval merely built the first corpus adversarial enough to expose it at a 100% rate.

## Relevant adjacent facts for the design pass

- The extraction pipeline already has a structured outcome vocabulary (extraction status/reason-code
  fields per document, made truthful by 671) — an abstention outcome has an obvious home in the
  existing vocabulary rather than needing a new representation.
- Hallucination-on-unreadable-input is a known, well-studied VLM failure class; the design pass should
  survey current mitigation practice rather than invent from scratch.
- The eval side retains a purpose-built adversarial corpus (`golden/synth-scan-v1`) whose every
  document currently triggers the failure — a ready-made regression battlefield for whatever gate is
  designed, independent of whether that corpus ever becomes agent-utility-measurable (see 624's
  scan-band finding).

## Explicit non-goals

Not extraction routing (607), not reason-code plumbing (671), not VDU lifecycle/wiring (672), and not
the separate research question of constructing scans that are pipeline-readable but agent-unreadable
(that stays with 624/the register). Not a general OCR-quality program — the scope is the honesty of
the output path when reading fails.

## Corpus availability note (2026-07-10, from 686)

A real binary-document corpus now exists: `mixed/realdocs-v1` (620 real PDF/office files incl.
scanned gov PDFs with genuine low-legibility pages; pinned manifest at
`scripts/jseval/666-corpora/realdocs-v1/`, rebuild via `scripts/search/fetch-realdocs-corpus.py`).
Complements `golden/synth-scan-v1` (adversarial synthetic) with REAL scan-quality diversity for
whatever abstention gate this doc designs.

## Investigation + design pass (2026-07-10, takeover session; design only — no implementation)

Two evidence legs: a full code map of the VDU output path (file:line) and a survey of 2024-26
VLM-OCR abstention practice (per this doc's own "survey, don't invent" note). Load-bearing findings:

### What the code map established

- **The only output check anywhere is blank/non-blank** (`VduBatchProcessor.java:239`, duplicated
  worker-side `GrpcIngestService.java:669-676`). Confabulated fluent text passes trivially.
- **No confidence signal crosses any hand-off**: logprobs are never requested from llama-server
  (`OnlineModeOps.java:774-798` builds the request without `logprobs`; response parsing reads only
  `message.content`, `:828`); `finish_reason` is not inspected on the non-streaming VDU path; no
  image-legibility measure is computed pre-send (`ImagePreparer` is resize-only).
- **The reusable evidence vocabulary exists and is never consulted post-VDU**:
  `visual_extraction_evidence` (ocrMeanConfidence, low-confidence word counts, textQualityScore,
  pagesMissingReadableText…) is persisted per-doc at routing time and read only by
  `VisualRoutingDecision` — write-once, never re-read when VDU's output is accepted.
- **`SUCCESS_TEXT` overwrites unconditionally** (content, preview, language, re-chunk, re-embed) —
  there is NO outcome for "VDU attempted, output rejected, baseline retained"; `COMPLETED_EMPTY`
  is reachable only when the model itself returns blank. The baseline a rejected output would fall
  back to is the honest low/empty pre-VDU text — exactly this doc's "degrade to absence".
- **Sampling is temperature-0 deterministic** (`SamplingParams.VDU`) — a naive re-sample repeats
  the same hallucination; a consistency probe must vary seed/temperature explicitly.
- **Pass 2 is not a check**: it summarizes Pass 1's own output, trusting it unconditionally.
- **FE gap**: `PreviewController.computeTextProvenance` has no `COMPLETED_EMPTY` case — docs where
  VDU ran-and-found-nothing silently display the pre-VDU provenance.

### What the practice survey established

- Instructed sentinel abstention ("output UNREADABLE") is a documented weak lever (AbstentionBench:
  models confidently answer rather than abstain) — acceptable only as a free extra layer.
- llama-server exposes per-token probabilities (`n_probs`; OAI-compat `logprobs` since PR #10783) —
  near-zero marginal cost on the same call — but **behavior with mmproj/vision requests is
  undocumented**; must be probed live before the design leans on it.
- Cheap input gates are standard practice: Laplacian-variance blur scoring (ms-scale; a dedicated
  2026 pre-VLM blur-gate paper reports F1≈0.98) and tesseract-confidence-as-proxy with
  area-weighted aggregation.
- Two-sample agreement (CE-OCR pattern: pairwise edit-distance entropy over re-extractions,
  threshold ≈0.5, ~7% escalation rate reported) is the only surveyed signal that directly measures
  confabulation rather than proxying for it; cost 2× inference, so reserve for the ambiguous band.
- No open-source VLM-OCR pipeline ships a runtime confidence gate today (olmOCR defends input-side
  via text-layer anchoring — inapplicable to pure scans); per-span confidence is a cloud-commercial
  feature (Azure `logprobsConfidence`). This gate is ours to design; nothing to copy wholesale.

### Proposed design: three-stage cheap-first cascade + an honest reject outcome

**Stage 0 — input legibility gate (pre-send, ~ms):** compute Laplacian variance + contrast in
`ImagePreparer` (already touches every pixel; near-free) and consult the persisted
`visual_extraction_evidence`. CAUTION: OCR-failed-is-why-we're-here — the gate must key on
"no textual signal present for anything" (blur/contrast floor), not "OCR confidence low", or it
would defeat VDU's purpose. Below floor → do not call the model; record honest can't-read.

**Stage 1 — same-call signals (free, on every VDU call):** request `logprobs` on the vision
completion (first implementation step: live-probe that llama-server returns them for mmproj
requests — undocumented); compute mean logprob + fraction-of-low-confidence-tokens; parse
`finish_reason` (truncation ≠ clean completion). Also cross-check against the baseline when OCR
produced words (word-overlap between VDU text and baseline OCR text — near-free, already on
disk; wild disagreement where OCR read *something* is suspicious). Thresholds calibrated, not
borrowed: `golden/synth-scan-v1` (360 docs, ~100% known-confabulated) vs the readable fixtures +
`mixed/realdocs-v1` real scans (known-legible).

**Stage 2 — agreement probe (2× cost, ambiguous band only):** re-run Pass 1 once with varied
seed/temperature; normalized edit-distance agreement (CE-OCR); low agreement → reject. Expected
to run on a small fraction of documents (survey anchor: ~7%).

**Reject path (the vocabulary home):** new `VduUpdateOutcome.REJECTED_SUSPECT_TEXT` → worker
branch that RETAINS baseline content (no overwrite, no re-chunk/re-embed), sets a new
`VDU_STATUS` value (e.g. `REJECTED`), keeps the honest evidence trail (which stage rejected, the
scores), and does not re-queue (poison-pill discipline as with PROCESSING). Prompt-level sentinel
("UNREADABLE") added as a free extra signal feeding Stage 1, never load-bearing. Also fix the FE
`COMPLETED_EMPTY` provenance fall-through as part of the same change (and give `REJECTED` a case
from day one).

**Verification plan:** synth-scan-v1 expect ≥~95% rejected (today: 0%); legible set expect
low single-digit % false-abstain (product decision on the exact bar); reason-code/classifier
tests in the 671 style (outcome injectivity); live-stack pass per `static-green ≠ live-working`.

**Open questions for owner review:** (a) mmproj logprob support — probe first; the cascade
degrades gracefully to Stages 0+2 if absent; (b) whether `REJECTED` warrants FE surfacing beyond
provenance; (c) threshold governance — config (OcrRoutingConfig-style) vs constants with
derivation comments.

## Probe result (2026-07-11, implementation step 0): mmproj logprobs CONFIRMED

Live probe against the shipped cuda12 llama-server (Qwen3.5-9B-Q4_K_M + mmproj-F16, `-np 1
--cache-ram 0`, direct `/v1/chat/completions`): **per-token logprobs populate for vision/mmproj
requests** (`"logprobs": true` in the request body; `choices[0].logprobs.content[]` in the
response). `chat_template_kwargs: {"enable_thinking": false}` confirmed required — with thinking
on, reasoning tokens consume max_tokens and `content` comes back empty.

First separation datum (VDU-shape prompt, temperature 0):
- legible test image → exact transcription; mean logprob **-0.058**, min -0.27, 0% tokens < -1.0
- noise image → model refused/described (did not transcribe); mean **-0.442**, min -1.79,
  **14% tokens < -1.0**

Stage 1 of the cascade is therefore fully viable; no degraded-mode fallback needed. Caveat for
calibration: the noise image produced REFUSAL-shaped output, not the fluent confabulation this
doc's defect exhibits — thresholds must be calibrated on `golden/synth-scan-v1` (known ~100%
fluent-confabulation) vs legible real scans, not on refusal cases. Probe scripts in the session
scratchpad; numbers above are the durable record.

## Implementation log (2026-07-11, worktree impl-677)

Slices, each committed separately on this branch:
- **Step 0 (probe)**: mmproj logprobs confirmed (§Probe result above).
- **S2-core**: `VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT` + `VDU_STATUS_REJECTED` + worker
  branch retaining baseline (no overwrite/re-embed/re-chunk; evidence in vdu_enrichment).
  Regression: fabricated non-blank text cannot reach the index.
- **S1**: `visionCompletionDetailed` → `VisionCompletionResult(content, finishReason,
  tokenCount, meanLogprob, lowConfidenceFraction)`; logprobs requested for the vision path ONLY
  (scope-isolation test); per-token arrays reduced then discarded.
- **S3**: `ImageLegibility` (Laplacian variance + RMS contrast, 512px-bounded, conjunctive
  floors) — standalone, calibration-free.
- **S2-wire** (in flight): `VduAbstentionGate` stages 0+1 wired into VduProcessor/
  VduBatchProcessor; PROVISIONAL thresholds pending calibration.

**Design refinement vs the original sketch (recorded, not silent):** Stage-0 pre-call skips use
the same `REJECTED` status as post-call output rejection — the gate evidence's `stage` field
(`input_legibility` vs `logprob`) carries the distinction. Rationale: `REJECTED` = "the
abstention gate stopped this (before or after the call)"; `COMPLETED_EMPTY` keeps its
established meaning "the model itself found nothing". One honest umbrella beats a third status
value's wire/FE ripple. Additional S2-wire decisions: partial-legibility documents still send
their legible pages; pass-2 enrichment is skipped for rejected documents (never summarize
suspect text); truncation (finish_reason=length) alone does not reject.

**Remaining after S2-wire:** FE provenance cases (COMPLETED_EMPTY fall-through fix + REJECTED),
Stage-2 agreement probe (seed-varied re-extraction, ambiguous band only), threshold calibration
on golden/synth-scan-v1 vs legible realdocs scans (thresholds are PROVISIONAL until then), and
the live-stack pass (static-green ≠ live-working).
