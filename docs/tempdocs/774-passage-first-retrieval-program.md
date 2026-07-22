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

## §F. Code audit (2026-07-22, same session — full retrieval-path read; feeds theorize)

Primary-source verification of the charter's premises against HEAD (`fc7d538a`).
Files read end-to-end: `SearchExecutor.java`, `SearchPlanner.java`,
`CorpusProfile.java`, `HybridFusionUtils.java`, `HybridSearchOps.java`,
`ChunkSearchOps.java`, `ChunkDocumentWriter.java`, `SearchResponseBuilder.java`,
`KnowledgeSearchEngine.java` (CE section), `RagContextOps.java` (retrieval),
`ResolvedConfigBuilder.java` (defaults).

### F.1 The "fused side-branch" claim is CORRECT — and understated. Six code-level mechanisms:

1. **Chunk branch is conditional on the doc branch.** It never runs when the
   doc-level legs return empty (`SearchExecutor.maybeApplyChunkMerge` —
   `SKIPPED_EMPTY_BASE_RESULTS`, SearchExecutor.java:512-526), plus planner
   gates: first page only, non-LUCENE syntax, relevance sort, corpus profile
   (`SearchPlanner.planChunkMerge`:240-284). A recall-gate shape (D-005).
2. **The chunk branch is internally BM25-dominant by default.** Its 3-way CC
   reuses the DOC-level weights `cc_weight_{sparse,dense,splade}` =
   0.60/0.20/0.20 (SearchExecutor.java:622-626; defaults
   ResolvedConfigBuilder.java:1444-1446) with `cc_zero_exclude=false` (:1443)
   — so on a default hybrid (no-splade) query, chunk-dense — the only leg
   that can bridge camouflaged paraphrase — carries effective weight
   **0.25 inside its own branch** (0.20/0.80), and a chunk found by dense
   alone still gets only 0.25×norm. **There is no independent chunk-branch
   weight config** — the branch cannot be tuned without retuning the doc legs.
   (Exception: `vector` mode zeroes the sparse/splade slots, so ITS chunk
   branch is pure chunk-dense order — load-bearing for reading M5, see F.3.)
3. **Collapse cap.** The chunk branch delivers at most 2×limit parents to
   branch fusion (`collapseLimit = max(limit*2, limit)`,
   SearchExecutor.java:641; `collapseChunkHitsToParents` breaks at the cap,
   :899-921), chosen in the mechanism-2 (BM25-dominant) fused order. At the
   eval/agent depth of 10, twenty parents. Chunk KNN itself runs at
   `candidateBudget = 10×limit` (k=100 chunks at limit 10; `resolveVectorQueryK`
   honors ef_search override, ReadPathOps.java:296-303).
4. **Branch fusion reaches parity, never primacy.** CC 0.50/0.50
   (`branch_cc_weight_whole/chunk`, defaults :1449-1450), chunk weight
   length-modulated from 0.25× (≤1024 tokens) to 1.0× (≥4096 tokens)
   (`chunkBranchParentLengthMultiplier`, HybridFusionUtils.java:826-834). Even
   at full modulation the whole-doc branch injects its candidates at equal
   weight — on camouflaged long docs, that branch is noise: F-035's dilution
   mechanism with the sign flipped.
5. **The strongest judge is passage-blind.** The Head CE scores each candidate
   as `title + ~1500-char query-focused snippet` extracted from
   `content_preview` (**first 4KB of the doc**), centered on **lexical** match
   spans (KnowledgeSearchEngine.java: `RERANK_SNIPPET_LENGTH=1500`, docTexts
   loop). The winning chunk's text — which IS present on the wire hit as
   `CHUNK_CONTENT` (ChunkSearchOps `buildChunkHits` allowlist; survives
   collapse via `normalizeChunkHitToParent` + `mergeFields`) — is never given
   to the CE. A doc discovered ONLY by the chunk branch carries no
   `content_preview` at all → the CE scores it on its title. This violates
   D-005's own judge-aligned-truncation principle at the pipeline's designated
   strong judge. (The RAG path does NOT have this defect — its CE reranks
   chunk text, RagContextOps.)
6. **Evidence delivery is lexical-anchored.** `excerptRegions`/`matchSpans`
   are computed only under `hasLexicalTerms` and anchor to term matches
   (SearchResponseBuilder.java:464-514; chunk-branch hits do use
   `CHUNK_CONTENT` as excerpt source :490-492 — but only when lexical terms
   match). A dense-only match delivers no anchored evidence → 771 item 1b's
   45% legal carriage is structural, not incidental. (775's lane; boundary
   with mechanism 5 must be drawn in design — 5 is a RANKING defect, not
   delivery.)

Additional gap found: the F-024 recall-complete pool (default ON,
`leg_recall_complete_enabled`, :1479) protects only the DOC-level dense/bm25
top-N through branch fusion (SearchExecutor.java:766-788 reads the whole-doc
result's provenance) — **the chunk branch's own top-N has no recall guarantee**
into the CE window.

### F.2 The RAG path is ALREADY passage-first — the program is a convergence, not a greenfield

`RagContextOps.searchChunksWithMeta`: chunk-level hybrid (chunk BM25 + chunk
KNN, RRF + low-signal gating, `searchChunksHybrid` Phase-6 path,
ChunkSearchOps.java:578-651), doc-level union leg for chunkless (<2000-char)
parents (F-038, default ON), chunk-level CE rerank, MMR/position diversify,
token budgeting. The passage-first end-state for the product's RAG/agent-answer
surface substantially exists. What is doc-primary is the interactive
`/api/knowledge/search` path — which is also what the MCP `justsearch_search`
tool serves (F-037 aligned it to the hybrid preset), i.e. the surface the
agent-utility campaigns measure. "Rewrite-scale" should therefore be treated
as a hypothesis for theorize, not a premise: the passage substrate (per-chunk
BM25 always; per-chunk CLS vectors always, post-F-032; chunk SPLADE flag-gated
at +108% enrichment, F-036; offsets/heading/line metadata on every chunk,
ChunkDocumentWriter.java:114-176) and the passage query legs already run on
every chunk-eligible interactive query — the marginal query-time cost of
primacy inversion is small. The genuinely new work: aggregation semantics
(doc-as-aggregation for display/facets/dedup), decision-planner + reason-code +
searchTrace surface (execution-surfaces register), TEXT-only feature carve-out
(facets/sort/cursor/fuzzy are Lucene Query-collector features on parent docs —
`runSparseShortcut` path — and cannot move to passages), eval-mode wiring, and
baseline re-pins.

### F.3 What the M5 floor does and does not already tell us (sharpens the §E.2 probe)

`vector` mode's chunk branch is PURE chunk-dense order (mechanism F.1-2
exception) at depth ~100 chunks → top-20 parents → 50/50 branch-fused with
parent-dense → top-10. M5 says gold missed top-10 in vector mode too for
82-90% of legal-10k queries. Therefore the floor already implies: **gold is
not in the engine chunk-dense top-~5-10 parents** at 10k. What remains open —
and what the probe must measure — is the band below: per-query gold-chunk rank
in (a) offline exact-NN chunk-dense (F-034 harness) and (b) engine chunk-KNN
at depth ≥100, on the certified camouflaged strata. Decision bands:
- gold parent within ~top-20 of PURE chunk-dense but outside fused top-10 →
  burial (fusion/cap/weights) — architecture (or even config) recovers it;
- ranks ~20-100 → passage-primary + passage-CE (mechanism-5 fix) plausibly
  recovers;
- outside exact-NN top-100 → representation floor at passage granularity —
  the program's floor-attack premise dies; surviving value = F.1 mechanisms
  5-6 (judge/evidence passage alignment, shared with 775) + bounded fusion
  gains.
Also compare exact-NN vs engine-ANN (HNSW at ~50k+ chunk vectors) to rule
ANN loss in or out.

### F.4 Judgment on the charter as written

- §A.1 (floor real, scale-shaped): **stands** (771 §E, leak-free strata).
- §A.2 ("architecture consumes passage capability only as a fused
  side-branch"): **code-confirmed, and stronger than charted** — the branch is
  gated, internally lexical-dominant, capped, parity-fused, judge-blind, and
  evidence-blind (F.1). The charter under-claims: even the CE stage is
  passage-blind, which no fusion-weight change fixes.
- §A.3 (delivery layer wants passages): **confirmed** (RAG already
  passage-first; chunk metadata rich; 775 owns delivery).
- §B candidate ingredients: chunk-MaxP already exists as the collapse
  primitive (best-chunk-per-parent); "late-interaction-style scoring" remains
  the expensive outlier (new index format + kernels) — only reachable if
  chunk-CLS-primary saturates below the offline ceiling.
- §C sequence: **amendment from §E.2 stands and is strengthened** — the probe
  is now precisely specified (F.3) and discriminates the program's premise at
  ~$0. Theorize should additionally cost the "convergence, not rewrite"
  shape (F.2) and the two cheap defect-fixes (CE passage input, chunk-side
  recall-complete) as possible pre-program lanes with independent value.
- §D constraints: perf concern is real only for late-interaction and
  chunk-SPLADE; chunk-primacy itself is near-cost-neutral at query time (F.2).

## §G. Theorize pass (2026-07-22, same session — §C.1; no design decided here)

### G.1 Five framings of the same problem (the design pass must pick one deliberately)

- **A. Granularity inversion** (the charter's): the passage becomes the
  retrieval unit; documents are aggregations. Cleanest story, largest blast
  radius.
- **B. Dilution elimination**: the unit is fine; the defect is that every
  stage mixes strong passage signal with weak-or-noise doc signal (§F.1's six
  mechanisms). Attack the dilution points as a policy stack — independent
  chunk-branch weights, cap removal, chunk-side recall-complete, passage-aware
  CE — without touching the data model. Cheapest; risk is a local maximum and
  a still-two-branch architecture that keeps drifting.
- **C. Evidence-chain**: for an agent-facing product, retrieval's job is to
  deliver an answer-bearing span + enough context to continue (the 771/775
  lineage). The document is a citation/UI concept, not the ranking concept.
  Under this framing, interactive search *converges with the RAG path* (which
  already works this way, §F.2) — one retrieval core, two presentations.
- **D. Hierarchical two-level**: passages for evidence, the doc level as a
  context/prior signal; final score = aggregation (MaxP + doc prior) rather
  than branch fusion. What the code does today is a degenerate version of this
  (50/50 CC with the doc branch as a *competing candidate list* instead of a
  feature).
- **E. Judge-centric (D-005-native)**: keep the funnel dumb, broad, lossless;
  make every truncation judge-aligned and give the judge passage sight.
  Candidate generation per leg at passage granularity, no early caps, CE
  scores passages, aggregation *after* judging.

These are not exclusive — C is the product framing, E the engine discipline,
A/B/D the implementation spectrum. The honest ordering question the probe
(§F.3) answers: does B alone clear the floor band, or is A/E needed?

### G.2 Hidden assumptions surfaced

1. **"Passage granularity bridges camouflaged paraphrase."** Untested — every
   passage win was measured on citation-form CLERC or uncamouflaged corpora.
   The probe is the discriminator; the program's headline premise rests on it.
2. **"Fixing the floor corpus is capability, not corpus-fit."** D-005 forbids
   designing for a presumed workload — and the floor lives on one adversarial
   synthetic stratum. The program's justification must therefore be stated as
   corpus-agnostic *guarantees* (leak elimination, judge-aligned truncation,
   evidence coherence — G.4), with legal-10k as the demonstration, not the
   target. If a passage-first default helps legal and regresses enron
   (short-doc, the healthy domain), D-005 says it does not ship as a default.
3. **"Chunks are good passages."** Chunking is fixed 500-token windows with
   50-token overlap; heading-aware only for markdown/pdf/office; recent
   splitter corners were buggy (F-038 adjacent finds). Passage-first promotes
   the splitter from helper to load-bearing ranking substrate — boundary
   effects (a bridge sentence straddling two chunks dilutes both) become a
   first-order quality variable nobody has measured.
4. **"MaxP is the right aggregation."** Best measured so far (F-033/F-034
   offline), but MaxP discards multi-passage corroboration — multi-hop and
   aggregation questions (776's new schemas) may prefer top-k-sum. Aggregation
   choice is a measurable design-pass decision, not an inheritance.
5. **"Interactive and RAG should fully converge."** Facets, sort, cursor,
   TEXT-syntax, fuzzy correction are doc-native Lucene collector features
   (§F.2) — convergence is necessarily partial; the design must name the
   carve-out rather than discover it late.
6. **"The parent representation loses its job."** F-035 ("parent NOT
   redundant, −0.204 without it") was measured under *branch fusion*, where
   the parent is a competing candidate list. In an aggregation architecture
   (framing D) the parent vector can be a *feature* (doc gist prior) rather
   than a rival ranking — F-035 constrains fusion shapes, not aggregation
   shapes. The design must state which role the parent plays; deleting it is
   still measurement-forbidden.
7. **"Depth-10 is the horizon that matters."** Humans see ~10; agents can
   paginate and re-query. A gold at passage-rank 30 is a floor for the UI but
   an affordance question for agents. The program's acceptance metrics should
   report both k=10 and a deeper k.

### G.3 Rejected shapes (recorded so they stay rejected)

- **Per-corpus router** (route long-doc corpora to passage-primary): D-005
  bucket-A violation. Per-DOC signals (length modulation, as today) stay legal.
- **Curve-fitting the existing fusion gates** to the floor corpus: D-004's
  explicit lesson; the gates are not to be tuned further without a signal that
  separates dense-right from dense-confidently-wrong.
- **Query-side rescue** (expansion/reduction/synonyms): measured dead
  (F-024 synonym deletion; F-030's BM25-verbosity monotonicity makes query
  reduction anti-lexical).
- **Encoder swap**: closed, F-034.
- **Canonical late-chunking** (span-mean chunk vectors from the single pass):
  measured regression on this CLS-pooled model (F-031 "what did NOT ship").

### G.4 Principle candidate — the evidence-coherence invariant

The §F audit shows three stages answer "which part of this document is the
evidence?" with three different answers: fusion ranks by the winning chunk,
the CE judges the doc's first-4KB preview, delivery excerpts by lexical term
anchors. Candidate invariant: **the span that made a hit rank is the span the
judge scores and the span the user/agent receives.** This generalizes D-005's
judge-aligned-truncation clause, subsumes 775's "one span authority" (775
builds the representation; 774 makes ranking produce and consume it), and
gives the program a corpus-agnostic justification independent of the floor
probe's outcome (assumption G.2-2). If the program dies at the probe, this
invariant survives as the salvage scope (mechanisms §F.1-5/6 + chunk-side
recall-complete). Not yet a design: the invariant's cost surface (CE input
assembly, wire shape, trace stages) belongs to the design pass, and its
register home (execution-surfaces vs a new guarantee row) must be decided
with 775, not unilaterally.

### G.5 Cheap evidence available before any design (extends §C.3 / §F.3)

- The cert artifacts already hold a free signal: per-cell `vector` vs `hybrid`
  recall@10 on the camouflaged strata. `vector` mode's chunk branch is pure
  chunk-dense (§F.3); if vector ≈ hybrid everywhere at 1k (where the floor is
  partial), the chunk branch's internal BM25-dominance (§F.1-2) is not the
  binding constraint at k=10 — reading this costs one script over
  `tmp/analysis-624/771/` inputs, before any new run.
- The §C.3a chunk-SPLADE re-litigation has a sharpened hypothesis: F-036's
  hybrid-neutrality was measured where dense+CE already carried the corpus;
  on camouflaged strata lexical is dead and dense is the only live leg — the
  sparse-revival's fusion contribution could flip sign. It is also a cost
  decision (+108% enrichment) and so needs the probe's verdict first: no
  point paying it if passage-dense alone clears the band.
- The entity-linking ingredient (§B) is severable and overlaps 733's
  hop-absorption option (b); recommend the design pass treats it as a
  separate candidate lane, not core 774 scope — bundling it risks the
  program carrying an unrelated join-substrate debate.

### G.6 What theorize changes about the mandated sequence

Nothing structural — it confirms §E.2's probe-first amendment and adds:
(a) the probe should be read against the G.1 framings (B vs A/E is the real
fork, not "do vs don't"); (b) the free cert-artifact reading (G.5) belongs
before even the probe; (c) the design pass owes explicit positions on
assumptions G.2-3 (chunker as ranking substrate), G.2-4 (aggregation
function), G.2-5 (TEXT carve-out), G.2-6 (parent's role), and the G.4
invariant's ownership boundary with 775.
