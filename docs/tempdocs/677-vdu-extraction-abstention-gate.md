---
title: "VDU extraction abstention gate: the vision model hallucinates plausible text on scans it cannot read, and that text is indexed as real document content with no confidence check anywhere on the output path"
type: tempdocs
status: "open — STUB, no design or implementation. A live-observed production correctness defect, not a speculative improvement: the evidence run is documented and reproducible (tempdoc 624, twenty-second pass, 2026-07-03). Register check at filing: 607 owns extraction ROUTING (which path a document takes), 671 owned OCR-skip reason-code truth, 672 owned VDU bootstrap wiring — no existing tempdoc owns extraction OUTPUT quality/abstention; this one does."
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
