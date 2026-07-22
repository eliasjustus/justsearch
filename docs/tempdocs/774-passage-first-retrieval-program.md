---
title: "passage-first retrieval program: make the passage the retrieval unit end-to-end — the one architectural direction that attacks the measured long-document floor no model swap can fix"
type: tempdocs
status: "chartered (2026-07-22). Theorize-first: the full theorize→research→design sequence is MANDATED before any implementation — this is rewrite-scale."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 762→771 measurement program's inventory (founder-directed 2026-07-22)
category: search-engine / retrieval-architecture
related:
  - 771-post-rebuild-retrieval-residue   # M5: the corpus-wide legal-10k floor this program attacks
  - 713-dense-authority-consolidation    # F-035: parent/chunk dual-representation verdict
  - 712-sparse-leg-long-doc-death        # F-033/F-036: chunk-SPLADE offline revival, hybrid-neutral on the LEAKY corpus
  - 691-rmw-preservation-and-followup-designs  # F-031: single-pass long-context lineage
  - 733-second-hop-compensation          # adjacent second-hop work — reconcile before designing
---

> Charter only — deliberately no design. The evidence licenses a program, not
> yet an architecture. Load `/search-quality` before any pass.

# 774 — passage-first retrieval program

## §A. The evidence that licenses this

1. **The floor is real and scale-shaped.** On the leak-free certified strata
   (767/#273), doc-level retrieval collapses on long legal documents at 10k:
   gold unreachable in ANY leg for 82–90% of queries (771 §E M5), while enron
   holds. Lexical is dead post-camouflage on paraphrase queries; dense/splade
   cannot bridge at doc granularity (F-030 family); 708 closed the encoder
   question — **no model swap fixes this**.
2. **The engine's own passage-granularity ceiling is far above its shipped
   doc-level path.** Offline chunk-MaxP on legal: R@10 0.855 / nDCG 0.643
   with the incumbent encoder (F-034); chunk-SPLADE revives the sparse leg
   6–10× offline (F-033). The capability exists at passage granularity; the
   architecture consumes it only as a fused side-branch.
3. **The delivery layer already wants passages** (775's evidence-span work,
   the RAG chunk-first path, F-038's union leg). Retrieval is the last layer
   where the document is still the primary unit.

## §B. Program question

What does the engine look like if the PASSAGE is the retrieval unit
end-to-end — candidate generation, fusion, ranking, evidence, citations —
with documents as aggregations over passages rather than the other way
around? Candidate ingredients the passes must weigh (not commitments):
chunk-MaxP as the primary dense/sparse path; late-interaction-style scoring;
hierarchical retrieve (passage→doc context); index-time entity linking as a
join substrate (NER fields exist; F-010 killed *boosting*, linking is
untested); per-leg passage pools with D-005 recall-completeness.

## §C. Mandated sequence

1. **Theorize** — directions, tensions (latency/footprint on a local-first
   desktop product; the F-035 verdict that the whole-doc parent is NOT
   redundant; D-003/D-005 constraints), hidden assumptions, rejected shapes.
2. **Research** — internal archaeology (the full 691/711/712/713 lineage,
   branch-fusion seams, chunk store) + external practice (late-interaction
   engines, passage-first production systems, 2025-26 literature).
3. **Early cheap measurements** (before design): (a) re-litigate chunk-SPLADE
   on the CAMOUFLAGED corpora — F-036's default-off verdict was measured
   where lexical carried legal, a premise now dead; (b) engine-integrated
   chunk-MaxP probe on the certified strata vs the offline ceiling.
4. **Design** — with orphans named (the doc-level legs this supersedes, the
   branch-fusion machinery it may retire) and register duties (baselines
   re-pinned per D-005 measured-derived pattern).
5. Implementation in bounded lanes only after founder review of the design.

## §D. Constraints

- D-003 (no per-language levers), D-005 (no per-corpus routers; funnel-and-
  judge; capability levers only) bind every candidate.
- Perf is co-equal: CE p50 / throughput / footprint ratchets (640) must hold;
  a passage-first path that doubles latency on a desktop product is a
  non-answer.
- Register discipline: every measurement lands as F-numbers/baselines before
  the lane closes.
