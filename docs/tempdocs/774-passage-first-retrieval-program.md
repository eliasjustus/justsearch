---
title: "passage-first retrieval program: make the passage the retrieval unit end-to-end — the one architectural direction that attacks the measured long-document floor no model swap can fix"
type: tempdocs
status: "chartered (2026-07-22); takeover (§E), code audit (§F), theorize (§G), research (§H), design theorization (§I), derisk incl. the go/no-go probe (§J) complete same day. PROBE VERDICT (§J.5): the legal-10k floor is a representation floor — passage granularity does NOT bridge camouflaged paraphrase, and the engine already beats the offline passage ceiling there — so §I Stage 3 (primacy inversion) is SHELVED; Stages 1-2 (chunk-branch hygiene + evidence-coherent judging) remain licensed on evidence-delivery grounds; the H.4 contextual-enrichment A/B is the promoted next measurement. Implementation not licensed (founder review pending per §C.5)."
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

### G.6 What theorize changes about the mandated sequence (see end of §G)

## §H. External research pass (2026-07-22, same session — §C.2's external half; internal archaeology was §F + the register)

Web pass over 2024–26 literature and engine capabilities. No external code or
text copied into the repo; sources cited inline (URLs in the session record;
key identifiers below are citable as-is).

### H.1 Passage-primary + aggregation is the field's standard answer to long-doc retrieval

The PLM/LLM-era long-document retrieval survey (arXiv:2509.07759, Sept 2025)
frames exactly our fork: passage-aggregation methods (FirstP/MaxP/PARADE
lineage; Rep-max / Score-max / score-sum variants) vs hierarchical retrieval
vs long-context single-vector. Consensus points relevant to us: **MaxP is the
best simple aggregator but loses distributed/multi-passage relevance**;
learned aggregation (PARADE-style) wins but costs a model; hierarchical
retrieve "retrieves documents but fails to rank passages" — i.e., our G.1
framing-D needs the passage ranking to stay primary. The DAPR benchmark
(ACL 2024, "Document-Aware Passage Retrieval") names our exact task shape —
passage retrieval where document context carries signal — and its headline is
that pure context-free passage retrieval measurably loses to
document-context-aware passage retrieval. This is the literature's version of
F-035: **the parent representation survives as context/feature, not as a
competing candidate list** (G.2-6 confirmed externally).

### H.2 Late interaction is re-priced: the engine half is nearly free, the MODEL is the bottleneck

- **Lucene 10.3+ ships native late-interaction support** —
  `LateInteractionField` (multi-vector per doc, BytesRef-encoded) plus a
  rescorer using maxSim against a query multi-vector. **This repo is on
  Lucene 10.4.0** (`gradle/libs.versions.toml:9`) — the capability is already
  on the classpath. Elasticsearch 9.x ships ColBERT/ColPali on the same
  substrate. So §B's "late-interaction-style scoring" is NOT a
  new-engine-dependency lift as assumed; as a **rescoring tier over a
  passage-primary candidate stage** it is a moderate, Lucene-native addition.
- **MUVERA** (NeurIPS 2024, arXiv:2405.19504; Google) reduces multi-vector
  retrieval to single-vector MIPS via fixed-dimensional encodings — ~10%
  higher recall at ~90% lower latency than PLAID, 32× PQ compression — i.e.
  even *primary-stage* multi-vector could ride the existing HNSW machinery.
  CRISP (arXiv:2505.11471) prunes/clusters multi-vectors further. The field
  has a dedicated ECIR 2026 workshop (LIR) — active, not settled.
- **The binding constraint is D-003-eligible models, not engine capability**:
  there is no obvious Apache/MIT *multilingual* late-interaction encoder in
  the incumbent stack's family; jina-colbert-v2 is CC-BY-NC (ineligible, same
  screen that excluded jina-v3/v4 in F-034), answerai-colbert-small is
  English-only. A late-interaction tier is therefore gated on a model search
  with F-034's eligibility screen — the inverse of the usual build-vs-model
  situation, and a fact the design pass must carry.

### H.3 The CE-input defect (§F.1-5) is a named anti-pattern with a named fix

Practitioner and survey literature converge: first-512/first-4KB truncation
for cross-encoder input silently mis-scores long documents ("relevant passage
past the truncation → the reranker never sees it"); the standard remedy is
**passage-level CE scoring + score aggregation** — exactly the shape our RAG
path already implements and our interactive path lacks. Efficient variants
exist if CE-per-passage costs too much (block-level embedding rerankers with
top-k interaction refinement, arXiv:2501.17039). This upgrades the
mechanism-5 fix from "our audit's opinion" to "field-standard correction,"
strengthening its candidacy as a pre-program lane (F.4).

### H.4 New candidate ingredient the charter didn't name: passage contextualization at enrichment time

Anthropic's "contextual retrieval" (LLM-generated ~50-100-token chunk-context
headers prepended before embedding; vendor-reported 49–67% retrieval-failure
reduction combined with hybrid+rerank) and late chunking (arXiv:2409.04701 —
already evaluated here: span-mean variant measured incompatible with our
CLS-pooled encoder, F-031) both attack the same mechanism our camouflaged
strata stress: **a chunk in isolation lacks the document context needed to
match a paraphrase query**. Two tiers for the design pass to weigh:
- **Free/mechanical tier**: prepend title + heading-path (already stored per
  chunk: `CHUNK_HEADING_TEXT`, ChunkDocumentWriter) to the chunk text at
  embed/SPLADE time. Cheap, deterministic, D-003-clean; unmeasured here.
- **Resident-LLM tier**: LLM-generated chunk context at enrichment — a
  local-first advantage play that couples to 777's thesis (spend resident
  inference on quality) but collides with enrichment-throughput ratchets
  (640); would need its own cost/quality measurement.

### H.5 Production practice sanity check

Industry chunk-retrieval practice matches our substrate almost exactly
(chunks carry source-doc id, offsets, heading, metadata; per-leg top-k 20-200
fused via RRF/weighted-score) — with one delta: **our primary-stage passage
depth (~100 chunks, ~20 parents post-collapse) sits at the low end** of the
20-200 norm, consistent with §F.3's cap-burial hypothesis.

### H.6 Consequences for the passes

1. The probe (§F.3) stays first — nothing found changes its go/no-go role.
2. If the probe lands in the "burial/recoverable" bands, the design pass has
   a cheaper ladder than assumed: passage-primary chunk-CLS (existing
   substrate) → passage-CE aggregation (H.3) → optional Lucene-native
   late-interaction rescoring tier (H.2, model-gated) → MUVERA-style primary
   multi-vector (research-tier). Each rung is measurable independently.
3. Aggregation function (G.2-4): the survey confirms MaxP-vs-distributed
   tension is real; the design should A/B MaxP against top-k-sum on the
   multi-schema corpora (776's new aggregation questions are the natural
   test bed — coordinate).
4. H.4's free tier (title/heading prepend into chunk representations) is a
   legitimate §C.3-class early cheap measurement: it directly targets the
   camouflage mechanism and costs one enrichment A/B.
5. License duties recorded: no external code adopted; any future
   late-interaction model pick must re-run the F-034 eligibility screen
   (named ineligible: jina-colbert-v2 CC-BY-NC).

## §I. Design theorization (2026-07-22, same session — general shape, not implementation; Stage 3 probe-conditional)

### I.1 Thesis and chosen framing

The design realizes G.1's framings **C + E through D**: retrieval's product
job is delivering answer-bearing evidence (C); the engine discipline is a
dumb, broad, lossless funnel with judge-aligned truncation (E); the
implementation shape is hierarchical — **passages are the evidence and
ranking substrate; the document is a context feature and the
presentation/citation unit** (D). Framing A (passage-only index) is
**rejected**: TEXT-only features are doc-native Lucene collector features
(§F.2), short docs are the product's healthy majority case, and both F-035
and the external DAPR result (H.1) show document context carries real signal.
The existing design being *extended* rather than replaced: the RAG path's
chunk-first machinery, F-038's union pattern (chunkless doc = its own single
passage), and F-024's recall-complete pool. The interactive path converges on
what the RAG path already proves out, instead of the two continuing to drift.

### I.2 Staged shape (each stage independently measurable; later stages gated)

**Stage 1 — un-handicap the passage branch** (unconditional; pure
capability/guarantee fixes, D-005-clean, corpus-agnostic):
- The chunk branch gets its **own leg-weight config**, decoupled from the
  doc-level `cc_weight_*` keys (§F.1-2's silent coupling), with zero-exclude
  semantics matching branch fusion (a passage found by a single leg is not
  penalized for the other legs' absence).
- The **collapse cap** (2×limit parents) is raised to CE-window scale; the
  chunk branch's per-leg top-N gains the same **recall-complete guarantee**
  into the judge window that doc legs already have (extends F-024's pool —
  same mechanism, second granularity).
- The **base-results gate falls**: the passage branch runs even when doc
  legs return empty ("fusion is a ranking step, not a recall gate", applied
  at the branch level).
- Stated guarantee: *no stage may drop a passage-leg top-N candidate before
  the judge sees it* — the passage-granularity twin of F-024/F-028.

**Stage 2 — evidence-coherent judging** (unconditional; H.3
field-standard):
- Every CE candidate is scored on its **evidence text**: the winning
  passage's text when the hit has passage provenance; the head preview only
  for short/chunkless docs (where head ≈ whole doc — which is exactly why
  the CE works on email today). One selection, two consumers: the same
  selected span feeds 775's delivery representation.
- Follow-on (2b, measured before adopting): for long-doc hits with no
  passage provenance, CE-score top-M passages and aggregate — the
  passage-CE aggregation the literature recommends and the RAG path already
  practices.
- Cleanup candidate discovered here: the CE's `DOCS_TOO_LONG` /
  `maxAvgDocLengthChars` corpus-level skip exists because CE input was
  doc-head-shaped; with evidence-sized input, average doc length no longer
  disqualifies the judge. Verify the gate's set-site before touching
  (wrong-gate discipline).

**Stage 3 — passage-primacy inversion** (CONDITIONAL on the §F.3 probe
landing in a recoverable band):
- Candidate generation flips: passage legs (+ the generalized union leg for
  chunkless parents) produce the primary pool at production depth (raise
  passage depth toward the 20–200 industry norm, §H.5); parent collapse
  happens late; the whole-doc branch stops being a competing candidate list
  and becomes an **aggregation feature** (doc-gist prior — F-035's signal
  preserved in its defensible role, per G.2-6/H.1).
- Aggregation function: MaxP incumbent (the existing collapse primitive),
  A/B'd against top-k-sum on 776's multi-schema cells before pinning.
- Branch-fusion machinery is retired (orphan inventory I.4).
**Stage 4 — optional quality tiers** (each self-gated):
chunk-SPLADE default re-litigation on camouflaged strata (§C.3a; also a
+108% enrichment cost decision); passage contextualization free tier
(title/heading prepend, H.4) then resident-LLM tier (777-coupled);
Lucene-native late-interaction rescoring (H.2 — gated on a D-003-eligible
multilingual model existing); MUVERA-class primary multi-vector
(research-tier only).

### I.3 What stays doc-level (explicit carve-outs)

TEXT-syntax collector features (facets, sort, cursor, fuzzy correction,
LUCENE syntax) remain doc-level — the planner's existing skip gates remain
their contract. Entity/metadata filters keep the two-stage parent-prefilter
pattern. Titles/filenames/citations remain doc-anchored. The parent
single-pass vector and its enrichment (F-031/F-032 machinery) remain — role
re-scoped in Stage 3, never deleted on this evidence.

### I.4 Orphan inventory (deletion/tombstone belongs to THIS lane's stages)

- Stage 1: chunk-branch reuse of `cc_weight_*` (the coupling, not the keys);
  the collapse-cap constant; the `SKIPPED_EMPTY_BASE_RESULTS` behavior (its
  reason code becomes unreachable → remove per operation-surface duties).
- Stage 2: the CE preview-snippet path as sole input
  (`extractQueryFocusedSnippet` on `content_preview`); likely the
  `DOCS_TOO_LONG` corpus-level CE skip (verify set-site first).
- Stage 3: `ChunkMergeDirective` + branch-fusion execution
  (`fuseWithCCNamed`/`fuseWithRRFNamed` branch usage,
  `chunkBranchParentLengthMultiplier`, `branch_cc_*` config keys,
  `chunkMergeApplied`/branch-fusion trace stages + reason codes),
  `computeRetrievalLimit`'s 2× doubling, `CorpusProfile`'s chunk-merge
  eligibility role (per-corpus profile yields to per-doc structure).
  Retire-with-a-sweep applies at each stage boundary — no residue rides to a
  later cleanup.

### I.5 Boundaries with sibling lanes

- **775**: owns the canonical evidence-span *representation* and delivery
  governor; 774 Stage 2 makes ranking *produce and consume* that same
  representation. One record, two producers is forbidden — if 775 lands
  first (its charter says it should), Stage 2 conforms to its type.
- **733**: hop-absorption and entity-neighbor expansion stay in 733; the
  charter's "entity linking as join substrate" ingredient is **severed from
  774's core scope** (G.5) — revisit only as its own lane.
- **776**: supplies the multi-schema cells the aggregation A/B needs; the
  register-provenance sweep must precede any 774 headline number.
- **777**: the listwise resident-LLM judge is a rescoring tier over the same
  evidence pool — Stage 2's evidence-text contract is written so a second
  judge can consume it unchanged.
- **778**: click/citation capture will eventually supply real labels for the
  aggregation function — the design keeps aggregation a pluggable policy,
  not a hardcoded formula, for that reason alone (no learned structure now).
- **779**: downstream consumer via 775; no direct coupling.

### I.6 Measurement and register duties bound to this design

Probe first (§F.3 bands decide Stage 3); free cert-artifact vector-vs-hybrid
reading before that (G.5); heading-prepend A/B as an early cheap measurement
(H.6-4); every stage re-runs the guard set (relevance + perf + leak +
union-recall) with enron strata as the regression sentinels; baselines
re-pinned measured-derived per stage that changes defaults;
`staged_recall_accounting` extended with passage-granularity leg-recall
(Q-013's extend-don't-fork); trace/reason-code changes go through the
execution-surfaces / operation-surfaces registers; no default flips between
hero-campaign cohort pinning and campaign completion (§E.6).

### I.7 Reach judgment

**Conformance (instances of existing seams, not new ones):** this design is
D-005's funnel-and-judge stance applied at passage granularity — nothing in
it invents a new discipline; Stage 1's guarantee is F-024/F-028's pattern at
a second granularity; the union generalization is F-038's pattern; the
recall-accounting extension follows Q-013's ruling; orphan handling follows
retire-with-a-sweep.

**Principle 1 — evidence coherence** (from G.4, now design-anchored): *the
span that makes a hit rank is the span the judge scores and the span the
surface delivers.* Applies beyond 774: RAG citation resolution (already
conforms), MCP/UI delivery (775/779), the evidence-pack curation
disagreement (F-037's open half is an evidence-coherence violation between
search ranking and pack curation). Earning-its-keep evidence: 771 §E's
legal evidence-carriage rises toward enron parity; CE regression-rate
(judge-arbitration-report) falls on long-doc strata; read-amplification
drops at hero capture. Retirement: if Stage 2 ships and none of those move
outside noise, the invariant reverts to a delivery-only concern (775's) and
774 stops citing it.

**Principle 2 — guarantees are granularity-scoped** (recognized, NOT built):
every recall guarantee should name the granularity it protects; today's
leak/union gates are doc-level and were blind to the passage branch's caps.
Candidate scope: a passage-level union-recall floor twin IF Stage 3 ships.
Earning-its-keep: the passage-granularity accounting catches a real
regression the doc-level gates miss. Retirement: if Stage 3 never ships,
the doc-level gates remain sufficient and no twin is built.

## §J. Derisk pass (2026-07-22, same session — measurements + runtime verification; no feature code)

Artifacts: `tmp/analysis-624/774/` (this worktree). Plan: five items (D1-D5);
D2 (the §F.3 go/no-go probe) delegated to a measurement worker — results
recorded below when complete.

### J.1 (D1) Free cert-artifact reading — the chunk branch's internal weighting is NOT the k=10 binding constraint

Script over the 771 cert per-query detail (all 8 cells, n=50 each;
`d1_vector_vs_hybrid.json`). Results (recall@10 per mode):

| cell | lex | vec | spl | hyb |
|---|---|---|---|---|
| legal-1k-v | 0.00 | 0.46 | 0.12 | 0.54 |
| legal-1k-sn | 0.00 | 0.42 | 0.12 | 0.46 |
| legal-10k-v | 0.00 | 0.12 | 0.02 | 0.16 |
| legal-10k-sn | 0.00 | 0.08 | 0.02 | 0.10 |
| enron-1k-v | 0.00 | 0.86 | 0.20 | 0.84 |
| enron-1k-sn | 0.02 | 0.80 | 0.32 | 0.78 |
| enron-10k-v | 0.00 | 0.44 | 0.06 | 0.54 |
| enron-10k-sn | 0.00 | 0.44 | 0.10 | 0.48 |

Readings: (a) **lexical is 0.00 on every cell including enron** — camouflage
kills BM25 outright; production hybrid on these strata is effectively
semantic-legs-plus-CE. (b) **vector ≈ hybrid everywhere** (hybrid usually
slightly ahead). Since `vector` mode's chunk branch is pure chunk-dense
(§F.3), the §F.1-2 BM25-dominant internal weighting is **not** what floors
legal at k=10 — Stage 1's weight-decoupling is hygiene, not the rescue.
The floor question is representation-or-depth, which D2 discriminates.
(c) Hybrid recovering slightly more than vector despite dead lexical means
the extra legs/CE are mildly additive, not harmful, at k=10 on these strata.

### J.2 (D3) Runtime verification of §F.1 audit claims

- **CE doc-length gate (`DOCS_TOO_LONG`) — defect-shaped, contradiction
  found.** The gate input is a **worker-session-lifetime running average**
  of extracted content length (`OperationalMetrics.recordContentLength`,
  called from `JobBatchWriter.java:147`; cached via
  `WorkerStatusCache.java:147-153`), NOT an index property: fresh worker
  over an existing index → avg 0 → gate can never fire; one long-doc corpus
  poisons the average for every later corpus in the session. Measured
  ground truth: `legal-clerc-200` mean content = **35,508 chars > the
  16,000 default** — the gate *should* have disabled the CE on every
  same-session eval, yet register rows list `cross_encoder` in observed
  legs there. Either the gate doesn't fire as coded, the leg tracking
  mislabels CE execution, or the cached average is never populated under
  eval — all three readings are defects. Logged to the observations inbox
  (out of 774's scope to fix); needs ONE live probe at implementation time.
  **Decision-relevant for 774:** the 767 strata hosts average **14,401
  chars < 16,000**, so the cert numbers (D1 table, M5 floor) are NOT
  CE-gate-confounded regardless of which reading is true.
- **Chunk-only hits reaching the CE as title-only text**: static chain
  re-verified (builder skips `CHUNK_CONTENT`; `resolveParentMetadata` adds
  only title+filename; no `content_preview` on chunk-only hits). No unit
  test covers CE docText assembly (`extractQueryFocusedSnippet` has no
  direct test) — recorded as a test gap for Stage 2's implementation.
  Gate-coverage test exists only for the disabled case
  (`KnowledgeHttpApiAdapterHarmfulCombinationsTest.java:328`).

### J.3 (D4) Stage-1 blast radius

Structurally unaffected: `beir/scifact` (chunk merge `SKIPPED_SHORT_CORPUS`,
F-014) and every short-doc corpus below the chunk threshold. Affected —
sentinel set for every Stage-1 flag: `mixed/enron-qa` (chunk merge fires on
all queries; +1.3% lexical contribution measured, F-014 — the regression
canary), `mixed/legal-clerc-200`, the 767 strata, miracl (partial chunk
rates). Convention: one default-off flag per Stage-1 mechanism (D-004
template), rollback = flag off; enron + scifact A/B before any default flip;
scorecard/baseline re-pins only at stage boundaries.

### J.4 (D5) Currency note

`origin/main` moved past this worktree's base: #279 finds **all four
`golden/*` corpora LEAKY** (id-shape enumeration; DE member `_FILLER`) —
774 must not use needle-burial/battlefield corpora as measurement grounds;
certified 767 strata + register corpora only. #280 lands **certified
multi-schema cells (single_fact + aggregation)** — the aggregation-function
A/B (I.2 Stage 3, G.2-4) has its test bed ready earlier than assumed.
Merge-up before implementation.

### J.5 (D2) The go/no-go probe — VERDICT: representation floor; Stage 3's floor-attack premise is measurement-REJECTED

Offline exact-NN passage probe (`tmp/analysis-624/774/probe/`: `probe_774.py`,
per-cell `*_summary.json` + `*_perquery.json`; gte-multilingual-base ONNX
fp16, CLS pool, 500/50 chunking parity; corpora materialized from the
committed 707 recipes, signatures recorded in the summaries).

- **Gate-0 anchor PASSED**: real `legal-clerc-200` chunk-MaxP R@10 **0.845 /
  nDCG@10 0.632** vs the F-034 register expectation 0.855/0.643 (Δ≤0.011),
  AND the doc-level window-mean arm reproduces F-030's 0.100/0.060 exactly.
  The harness's deltas are engine-meaningful.
- **Camouflaged legal-1k-verbose** (n=50): gold-parent MaxP rank buckets
  [1-10] 20%, (10-20] 2%, (20-100] 28%, **>100/not-found 50%**; median
  gold-parent rank 90.5, median gold-chunk rank 231 of 10,701.
- **Camouflaged legal-10k-verbose** (n=50): buckets 4% / 4% / 12% /
  **80% beyond 100**; median gold-parent rank 887, median gold-chunk rank
  2,506 of 109,061; passage R@100 = 0.20.
- **The inversion that kills the premise**: the ENGINE's shipped hybrid
  (D1: 0.54 / 0.16 recall@10 on these cells) *beats* the offline
  passage-granularity exact-NN ceiling (0.20 / 0.04). On camouflaged
  content, isolated passage vectors are WORSE than what ships — the
  engine's edge comes from the F-031 single-pass whole-doc vector (context)
  + CE. F-034's 0.855 passage ceiling was a property of the CLERC citation
  task, not of passage granularity per se; it does not transfer to
  camouflaged paraphrase. §E.1 hypothesis (ii) is confirmed: the legal-10k
  floor is representational — the encoder cannot bridge camouflaged
  paraphrase at ANY granularity — and no primacy inversion, cap removal, or
  fusion change can surface passages that rank ~900th in their own space.
- **Corroborating detail**: on the real task the engine already captures
  ~96-98% of its own offline passage ceiling (F-034's note; engine vector
  0.618 vs offline MaxP nDCG 0.632-0.643) — so Stage 3 has no measured
  headroom on the real ground either.
- **The one lever the probe leaves alive for the floor**: representation
  *content*, not architecture — context-enriched passage representations
  (H.4: title/heading prepend free tier, contextual-retrieval LLM tier).
  The engine-beats-offline-chunks inversion is direct evidence that
  document context is the active ingredient on camouflaged content.
- Register duty at lane close: file this as an F-number ("passage
  granularity does not bridge camouflaged paraphrase; engine hybrid exceeds
  offline passage exact-NN ceiling on 767 strata") with the artifact paths
  + signatures above.

### J.6 Derisk conclusion — program re-scoped

- **Stage 3 (passage-primacy inversion): SHELVED** — both grounds
  measurement-rejected (no headroom on real CLERC; representation-bound on
  camouflaged strata). Do not implement absent new evidence (a
  representation change that moves the passage ceiling, or a real-corpus
  ground where the engine sits far below its passage ceiling).
- **Stages 1-2 (chunk-branch hygiene + evidence-coherent judging): remain
  licensed on their own non-nDCG evidence** (771 §E 1b evidence-carriage
  45%, read-amplification, judge-blindness §F.1-5/6, D-005 guarantee
  language) — their acceptance metrics are evidence-carriage / CE
  regression-rate / delivered-span quality, NOT the legal-10k floor.
- **Stage 4's contextual-enrichment tier is PROMOTED to the program's
  next measurement** (the only floor-relevant lever left): the free
  title/heading-prepend A/B, then the resident-LLM contextualization tier
  (777-coupled) if the free tier shows direction.
- **CE-gate contradiction RESOLVED by live probe (2026-07-22, this worktree's
  dev stack, run 96da7851)**: ingested 30 real CLERC docs (mean 46,805 chars);
  worker telemetry `contentLengthAvgChars=40310` ≫ the 16,000 threshold, yet
  a hybrid query's searchTrace showed `cross-encoder` EXECUTED — because the
  Head-side gate cache (`WorkerStatusCache.cachedAvgContentLengthChars`) is
  populated ONLY by `GET /api/knowledge/status`
  (KnowledgeRoutes.java:31 → KnowledgeSearchEngine.status()), which neither
  jseval nor `/api/status` ever hits. One manual `/api/knowledge/status` poll
  later, the SAME query's trace had NO cross-encoder stage — the gate fired.
  **Consequence: every eval/register baseline measured the CE-on (gate-off)
  pipeline, while production sessions whose client polls
  `/api/knowledge/status` silently lose the CE on long-doc corpora — an
  eval-vs-production divergence.** Resolution (Stage 2 item): flip the
  `justsearch.rerank.max_avg_doc_length_chars` default 16000 → 0 (gate off =
  the measured configuration; operator override preserved); the gate/cache
  plumbing teardown is tombstoned for the later default-flip sweep.
- **Confidence (0-10) for implementing the remaining re-scoped work
  (Stages 1-2 + the H.4 free-tier A/B): 8** — mechanisms verified at
  code level, sentinels named, flags per D-004, blast radius bounded
  (scifact untouched; enron the canary); residual risks: the CE-gate
  contradiction (J.2, needs one live probe), CE-input change on chunked
  email (F-002's CE-hurts-enron interacts with Stage 2 — A/B mandatory),
  775 type-boundary coordination. Confidence for Stage 3 as chartered: 1
  (should not be built on current evidence).


Nothing structural — it confirms §E.2's probe-first amendment and adds:
(a) the probe should be read against the G.1 framings (B vs A/E is the real
fork, not "do vs don't"); (b) the free cert-artifact reading (G.5) belongs
before even the probe; (c) the design pass owes explicit positions on
assumptions G.2-3 (chunker as ranking substrate), G.2-4 (aggregation
function), G.2-5 (TEXT carve-out), G.2-6 (parent's role), and the G.4
invariant's ownership boundary with 775.
