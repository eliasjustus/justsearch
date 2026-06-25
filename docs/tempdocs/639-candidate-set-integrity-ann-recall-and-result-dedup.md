---
title: "Candidate-set integrity — the returned set's completeness (ANN recall at scale) and non-redundancy (near-duplicate collapse / diversity) are unowned and unmeasured. Both are regime-blind correctness properties of the candidate set *as a set*, upstream and downstream of the per-item ranking that every recent retrieval effort optimized."
type: tempdocs
status: proposed
created: 2026-06-24
author: agent analysis — originated as a STUB (idea + purpose only), filed from the tempdoc 636 coverage-gap analysis (this session). No design chosen, no implementation. Records the purpose and why it matters only; the design phase is explicitly deferred.
related:
  - 636-retrieval-buried-signal-long-documents   # the coverage analysis that surfaced this gap; its eval also raised the `ann_proof FAIL` flag it then dropped
  - 643-judge-stage-ranking-quality-next-lever   # the symmetric SIBLING — 643 owns the judge side (does the judge rank a present answer well); this owns the candidate-set side (is the answer in the set)
  - 580-relevance-freeze-and-fw001-thaw          # records "no ANN action" (HNSW inherited from Lucene) — i.e. ANN recall is parked, not owned
  - 635-contamination-resistant-eval-corpus      # the measurement-input side; any integrity measurement needs governed corpora
---

> **Purpose-only STUB (2026-06-24).** Captures two adjacent, currently-unowned engine-quality gaps that a
> coverage analysis of the retrieval work to date (tempdoc 636) surfaced and confirmed had no recent owner.
> **No design is chosen and nothing is implemented here** — this file records *what the gap is* and *why it
> matters*, so the work can be prioritized. The first step when it is picked up is **measurement**, not a fix
> (per the codebase's eval-first discipline). Everything about *how* to close either gap is out of scope.

# 639 — Candidate-set integrity: completeness (ANN recall) and non-redundancy (dedup / diversity)

## The idea

Recent retrieval work has optimized the **per-item ranking** of results — how the dense / lexical / sparse
legs are fused and reranked (the whole tempdoc 636 arc). Two correctness properties of the candidate set
*as a set* have never been owned or measured:

1. **Completeness — approximate-nearest-neighbour (ANN) recall at scale.** The dense leg retrieves via an
   approximate index (HNSW). Approximate recall is, by construction, *less than* exact recall, and the gap
   widens with corpus size and with metadata filtering. If the right document is dropped by the ANN search
   **before** fusion or the cross-encoder ever see it, every downstream ranking lever is operating on an
   already-truncated candidate list — a recall gate sitting *upstream* of the fusion recall gate 636 spent
   its effort on. Today this is treated as "inherited from Lucene, no action" (tempdoc 580), and 636's own
   margin-at-scale eval raised an `ann_proof FAIL` comparability flag as the corpus grew — a recall-at-scale
   signal that was noticed and then dropped. **The size of any exact-vs-approximate recall gap is unknown.**

2. **Non-redundancy — near-duplicate collapse / result diversity.** Personal and local file collections are
   dense with near-identical content: quoted email threads, forwarded copies, versioned drafts, templated or
   auto-generated boilerplate, the same document in two folders. Nothing in the pipeline collapses or
   diversifies the returned set, so a typical query can return a top-N that is several copies of one
   document. This is the **common-case** experience — what most queries feel like — as opposed to the rare
   buried-needle case the recent work optimized.

## Why it matters

- **It is the typical query, not the rare one.** A top-N full of near-duplicates of a single document is a
  worse everyday experience than one canonical hit plus genuine alternatives — and near-duplication is the
  *normal* shape of personal files, not an edge case.
- **ANN recall is a silent ceiling on everything downstream.** Claims that "the dense leg already finds the
  answer" (a load-bearing conclusion in the 636 arc) are only as true as the ANN search's recall. An
  un-measured approximate-recall gap means the engine could be losing answers before any ranking stage runs,
  invisibly.
- **Both are regime-blind capability properties.** Candidate-set completeness (recall@k vs exact KNN) and
  non-redundancy (no duplicate-dominated result set) are properties that should hold for *any* workload —
  consistent with the engine-wide direction recorded at the close of 636 (improve fixed, regime-blind
  capability; do not tune for a guessed corpus). Neither requires speculating about who the user is.
- **No standing measurement exists for either.** There is no recall-leakage probe and no duplicate-rate
  measurement, so neither a regression nor an improvement in set integrity would currently be visible.

## Scope boundary (purpose only — design deferred)

Out of scope for this stub, to be decided in a future design phase:
- *How* completeness would be measured or improved (ANN parameters, exact re-scoring, filtered-search recall,
  etc.).
- *How* redundancy would be detected or collapsed (any dedup / near-duplicate / diversification mechanism).
- *Whether* either is worth building — that judgment follows the measurement, not this stub.
- Any interaction with the existing fusion / chunk-collapse / cross-encoder path beyond noting that ANN sits
  upstream of all of it.

This file records only the **purpose and why it matters**. The first concrete artifact, when the work is
prioritized, is a measurement (does an ANN exact-vs-approximate recall gap exist at scale? how prevalent are
duplicate-dominated result sets?) — not a fix.
