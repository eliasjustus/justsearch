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

## §E. Takeover verdict (2026-07-22, worktree `774-passage-first`)

Investigation pass over the full lineage (771 §E/M5, F-030..F-039, 712/713/708,
733, siblings 775–779) + pipeline architecture + a light external check.
**Verdict: GO for the program's passes — with one sequencing amendment and four
coordination duties. NOT lite-class. No implementation licensed (charter's own
rule; unchanged).**

1. **The premise is real but has one untested crux.** Every passage-granularity
   win cited in §A.2 (F-033 chunk-SPLADE 0.545, F-034 chunk-MaxP 0.855/0.643)
   was measured on `mixed/legal-clerc-200` — a *citation-retrieval* task with
   real CLERC queries — NOT on the camouflaged paraphrase-injection strata
   where the §A.1 floor lives. Worse for the premise: the M5 "true floor"
   (82–90%) was computed over modes that already *include* the chunk branch
   (vector/hybrid legs carry `chunk_merge`; 771 §E notes hybrid recall@10 >
   3-leg union *because* of it) — so fused passage-granularity signal is
   already inside the floor number and still floored at k=10. Two live
   hypotheses: (i) fusion dilution (F-035's parent-noise mechanism, inverted)
   + the depth-10 horizon hide chunk-level signal a passage-primary path would
   surface → the program attacks the floor; (ii) the camouflaged paraphrase
   barrier is encoder-domain (F-030) and binds at passage granularity too →
   the floor is architecture-untouchable and this program's value collapses to
   775's delivery lane + bounded fusion gains. Nothing measured to date
   discriminates (i) from (ii).
2. **Sequencing amendment (recommended): promote §C.3(b) to a go/no-go gate
   run FIRST** (before or alongside theorize — the theorize-first mandate
   binds implementation, not measurement). The probe is ~$0: offline
   chunk-MaxP (712/708 harness pattern) + pure-chunk-branch ranking at depth
   100 on the certified camouflaged legal-10k stratum (materialization
   doc-ID-matched to cert qrels; re-verify chunk-vector liveness per F-032
   probe discipline before trusting any arm). A negative result re-scopes the
   entire program before the heavy passes spend anything; a positive result
   gives theorize its load-bearing number. §C.3(a) (chunk-SPLADE re-litigation
   on camouflaged strata) stays valid and second — F-036's default-off verdict
   was measured where lexical carried legal (0.686); on the rebuild lexical is
   0.00, so the fusion-overlap mechanism that neutralized it is gone.
3. **Displacement/duplication check.** Displaces (candidate): doc-primary leg
   plumbing + branch-fusion machinery (§C.4 already names orphans). Does NOT
   duplicate 775 (delivery half vs retrieval unit) **but shares one concept**:
   the passage/span representation. Fork risk is real — 775 builds "one span
   authority" while 774 would make the passage the index-side unit; the design
   pass must produce ONE canonical passage representation (projection
   discipline, execution-surfaces register), not two. 733's option-(b)
   entity-neighbor hop absorption overlaps §B's "entity linking as a join
   substrate" — reconcile before design (charter already flags; 733 is open,
   awaiting owner A/B authorization). 777's listwise LLM reranker is a natural
   judge over per-leg passage pools (D-005: intelligence in the judge) — a
   passage-first design should leave that seam open, not compete with it.
4. **"Rewrite-scale" is itself an assumption to test.** The engine already
   carries ~80% of the passage substrate: per-chunk BM25/CLS-vector/SPLADE
   fields, chunk 3-way CC fusion, MaxP-style parent collapse (stage 13a–13c),
   chunk-first RAG, F-038's union leg. Passage-first may be primacy
   re-plumbing (chunk branch primary, doc branch as aggregation/context)
   rather than a rewrite; the theorize pass should cost both shapes before
   accepting the rewrite framing. Full late-interaction (ColBERT/PLAID
   per-token multi-vector, learned-codebook residual compression, custom
   scoring kernels) is a *large* new footprint/dependency on a desktop
   product — treat as the expensive end of the design space, only reachable
   if chunk-CLS-primary measurably saturates below the offline ceiling.
5. **Honest tension, resolved:** 771 concluded the floor "NOT engine-fixable /
   no new engine charter licensed" — that verdict was scoped to F-039's
   *ranking-bug* residue at doc granularity under the current architecture
   (708 closed model swap, not architecture). 774 is the founder's deliberate
   bet on the one untested axis. Legitimate — but item 2's probe is what
   decides whether the bet has a payout surface, which is why it goes first.
6. **Timing.** 775 is chartered "buildable first" and must land pre-hero
   (cohort identity). 774 implementation is rewrite-scale and invalidates
   baselines/cohorts — it must NOT land before the hero campaign's cohort
   pins (766/776 ordering); the passes + probe can and should run now.
