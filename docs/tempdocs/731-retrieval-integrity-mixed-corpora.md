---
title: "Retrieval integrity on mixed corpora: two-retrievals-one-truth (pack-vs-search fusion disagreement), hybrid ranking instability across rebuilds, and 1_hop label correctness"
type: tempdocs
status: "open — planned (725 remediation program), awaiting orchestrator review"
created: 2026-07-14
author: "agent (area agent, tempdoc 725 remediation program)"
related: [725, 707, 708, 712, 713]
---

# 731 — Retrieval integrity on mixed corpora

> **Area authority** for tempdoc 725's consolidated-inventory issues **2, 4, 14** (the 725
> §"Issue-remediation program" map assigns them here). Five phases in order —
> THEORIZE → RESEARCH → DESIGN → DERISK → PLAN — then STOP at plan. No implementation,
> no git, no dev-stack in this pass; live probes are recorded as owner/orchestrator "asks".
>
> **Orchestrator flag (record and act on before implementation):** all three issues touch
> search/retrieval orchestration and fusion. Per project policy the `/search-quality` register
> (`.claude/skills/search-quality/SKILL.md`) MUST be loaded before the implementation work
> **and updated before the tempdoc closes** — a step only the main session can perform (the
> injected skill list does not carry the register-update obligation to a subagent). Any fusion
> or ranking change also lands an F-0xx finding in that register.
>
> **Hard invariant that binds every option below:** search analysis is **locale-invariant**
> (ADR-0043 / tempdoc 581) — no per-language analyzer, field, stopword list, spelling
> dictionary, or curated synonym artifact. Every correction in this tempdoc is
> language-agnostic by construction; the informative-term filtering discussed for issue 2 uses
> corpus-statistical (document-frequency) signals, never a language stopword list.

## The three issues (verbatim scope from 725's inventory)

- **Issue 2 — evidence-pack curation is weak on legal text, and pack selection disagrees with
  search ranking.** Live: for the same verbatim query, `justsearch_search` ranked the relevant
  doc #1 while `justsearch_answer`'s evidence pack (retrieveContext, `CHUNK_HYBRID`) did not
  include it at all; CLERC packs are led by irrelevant legal opinions (bestChunkScore ~0.03,
  coverage ~0.60). Design question: *why do the two retrieval paths disagree, and what makes
  pack curation honest/better on corpora where dense underperforms* (the F-029 dense-on-legal
  concern)?
- **Issue 4 — hybrid ranking instability across index rebuilds.** Same corpus re-ingested → a
  doc moved from rank 1 to outside top-10 for the same query (the druker7 case, observed twice
  during 725 level-2 live validation). Classify: expected ANN/approximation variance vs a
  fixable nondeterminism source; design determinism improvements only where the code shows a
  fixable source.
- **Issue 14 — `question_type: "1_hop"` labels on behaviorally-2-hop queries** in the generated
  `datasets/mixed/en-legal-clerc-1k-verbose/queries.json`. Every successful 725 trace required
  two retrievals (facility→engineer, engineer→value), yet the committed queries carry `1_hop`.
  Determine whether the taxonomy is wrong, the builder mislabels, or "hop" means something
  different there — *verify the builder's own definition before calling it a bug* — and design
  the correction plus its regeneration / commitment-digest implications.

---

# Phase 1 — THEORIZE (framings before design)

## Framing A — "two retrievals, one truth" (issue 2 is a governance question, not just a bug)

The single most clarifying fact from the `execution-surfaces.v1.json` register: JustSearch
already models these as **two sibling canonical records that share no field and never co-occur**
— `SearchTrace` (the doc-level search "why") and `ContextCitation` (the RAG-retrieval evidence
behind an answer). The register's own note (559 Authority IV) states the RAG ask flow *emits no
trace*. So the doc-level search ranking and the chunk-level evidence pack are **not two views of
one ranking that drifted** — they are two *different retrievals* over two *different units*
(whole documents vs chunks) that the architecture deliberately keeps separate.

That reframes issue 2. The user-visible symptom ("`answer` disagrees with `search`") is real and
harmful, but the honest question is not "make them the same" — a chunk pack and a doc ranking
*should* differ (a chunk from doc X can be the best evidence even if doc X isn't the top whole-doc
hit, and vice-versa). The design question is narrower and answerable:

1. **Is the disagreement a legitimate unit-mismatch (chunk vs doc) or an unjustified
   fusion-implementation fork** — i.e. do the two paths use materially different fusion weights /
   normalization / leg composition for no principled reason? The projection-vs-fork discipline
   (CLAUDE.md "explore before authoring a new representation") applies directly: if the two
   fusions are independent code with independently-drifting weights, that is a fork worth
   naming even if we keep two records.
2. **Where dense is dead (F-029: on CLERC, dense R@10 ~0.03, SPLADE ~0.005 — hybrid is
   effectively BM25-only), does the chunk pack inherit or fight that reality?** If the pack's
   `CHUNK_HYBRID` still spends weight on a near-zero dense leg while doc-search de facto rides
   BM25, the two paths will *systematically* disagree on exactly the legal corpus where the
   symptom was seen. The pack could be strictly worse than search precisely because it trusts
   a leg search has already learned to distrust.

## Framing B — pack curation is a recall-vs-precision choice on low-signal corpora

An evidence pack and a search result page optimize different objectives. Search wants **precision
at rank 1** (the agent reads the top hit). A pack wants **recall of answer-bearing spans within a
token budget** (the agent, or an LLM, synthesizes across passages). On a *high-signal* corpus
these coincide; on a *low-signal* corpus (dense dead, many near-tied BM25 chunks) they diverge
sharply — a recall-biased pack scoops up low-scoring, topically-adjacent chunks (the "led by
irrelevant legal opinions at bestChunkScore ~0.03" symptom) precisely because it is trying to
fill the budget. The theorization consequence: the pack's failure on CLERC may be *correct
behavior for a badly-conditioned objective*, and the honest product move is (a) **honesty about
pack confidence** (surface bestChunkScore/coverage as a low-confidence signal — 725 D2 already
proposed this) and (b) **objective alignment** — a pack whose selection floor / leg weights match
the corpus's actual signal profile, rather than a fixed recall target that low-signal corpora
can only satisfy with noise. This is the model-agnostic, language-agnostic lever: change *how
much low-scoring material the pack admits*, not *which language it is in*.

## Framing C — determinism as a reproducibility budget (issue 4), not a guarantee

Issue 4 must not over-promise. ANN (HNSW) retrieval is **approximate by construction**; two
graph builds over the same vectors in a different insertion order can return different neighbors,
and no honest design "makes ANN deterministic" without either exact-NN (cost) or a pinned build
(fragile). The useful framing is a **reproducibility budget**: enumerate every nondeterminism
source, then split them into (i) *gratuitous* nondeterminism that is cheap to remove (unstable
tie-breaks on Lucene internal docIds, unordered ingestion, non-stable sorts) and (ii) *inherent*
approximation variance (HNSW graph, segment-merge-dependent collection statistics) that we
**bound and disclose** rather than eliminate. The druker7 rank-1→>10 swing is a large move; a
pure ANN-approximation story usually produces *small* reshuffles, so a large swing is a signal
that a *gratuitous* source (a tie-break or an effectively-BM25-only ranking with many exact ties
whose order flips with docId assignment) is the dominant cause — which would be fixable. This is
a hypothesis the DERISK phase must test against the code and a controlled re-ingest, not assert.

The determinism framing connects to issue 2's F-029 fact: **when hybrid collapses to BM25-only
(legal corpora), ranking is dominated by lexical ties**, and lexical ties are exactly where a
docId-dependent secondary sort makes rebuilds non-reproducible. Issues 2 and 4 may share one
root on legal text: a near-degenerate score distribution whose *order* is decided by an unstable
tie-break. If so, one determinism fix improves both the stability symptom (issue 4) and the
pack/search agreement symptom (issue 2), because a stable order is a prerequisite for the two
paths to agree at all.

## Framing D — label correctness is a measurement-integrity issue (issue 14), taxonomy first

Issue 14 is not a retrieval defect; it is a **measurement-substrate** defect (704's program):
a mislabeled stratum silently corrupts any per-`question_type` slice a future campaign reports.
The theorization guard is **taxonomy-before-bug**: "hop" is overloaded. It can mean (a) number
of *retrieval* hops an agent must perform, (b) number of *documents* the answer synthesizes
across, (c) a *planted-fact depth* in corpus generation, or (d) a difficulty tier. The 725
forensics used sense (a) — behaviorally two retrievals. If the 707 builder uses sense (c) or (d)
and legitimately calls a single planted-fact-per-document chain "1_hop", there is no bug in the
builder, only a *collision of vocabularies* between the builder and the campaign analysis — and
the correction is to **document the definition and stop cross-reading it**, not to relabel.
Only if the builder intends sense (a)/(b) and mis-assigns it is relabeling warranted. The DESIGN
phase commits to whichever the RESEARCH/evidence shows; THEORIZE only fixes the discipline.

## Cross-issue synthesis

- Issues 2 and 4 plausibly **share a root on legal corpora** (degenerate BM25-dominated score
  distribution → unstable order + noisy pack). Fixing tie-break determinism is a candidate
  common cause; fixing pack objective/honesty is issue-2-specific.
- Issue 14 is independent and cheapest; it is a builder/definition correction with a
  regeneration cost governed by the corpus commitment-digest.
- Nothing here may add a per-language lever (ADR-0043). Informative-term signals must be
  document-frequency / corpus-statistical, and pack-confidence signals are numeric
  (bestChunkScore/coverage), both language-agnostic.

---

# Phase 2 — RESEARCH (is an external pass warranted? bounded, cited)

**Verdict: a *small, targeted* external pass is warranted for issue 2 (fusion-of-heterogeneous
retrievers on out-of-domain corpora) and issue 4 (ANN/rebuild determinism); issue 14 needs no
external research — it is answered entirely by the builder's own source.** The 725 research
pass #2 already covered response-shape/tool-result literature and is not re-covered; tempdoc 708
already closed the encoder-domain question (no model swap) and its findings are inherited, not
re-litigated. Summaries only below; no external code or text is copied into the repo.

## R1 — Rank fusion of heterogeneous retrievers (issue 2)

- **Reciprocal Rank Fusion (RRF)** (Cormack, Clarke & Büttcher, SIGIR 2009) combines ranked
  lists by summing `1/(k + rank)` per document, deliberately **using ranks, not raw scores**,
  which sidesteps the cross-leg score-scale problem (BM25 scores and cosine/euclidean scores are
  not comparable). Relevance to us: JustSearch fuses on *scores* with fixed weights; on a corpus
  where one leg is near-dead (dense on CLERC) a score-weighted sum still injects that leg's noise,
  whereas a rank-based fusion naturally down-weights a leg that ranks everything flatly. RRF is
  the standard, well-understood baseline to *consider* — but adopting it is a search-quality
  decision with its own eval, not something this tempdoc ships unilaterally (it routes to the
  register + an owner call).
- **Out-of-domain dense retrieval underperforms BM25** is a robust, replicated result — the
  **BEIR benchmark** (Thakur et al., NeurIPS 2021 Datasets & Benchmarks) showed dense retrievers
  frequently lose to BM25 on domain-shifted corpora; **CLERC** (Hou et al., 2024) is itself a
  legal-retrieval benchmark where lexical methods dominate. This is external corroboration of
  F-029 and of Framing A's point #2: a pack that spends fixed weight on the dense leg on legal
  text is fighting a known regime. *No new claim* — it confirms the incumbent register finding.
- **Score normalization before weighted fusion** (min-max / z-score / theoretical-min-max, e.g.
  the normalization discussion in the hybrid-search literature and Elasticsearch/OpenSearch RRF
  and normalization processors) is the other standard remedy: normalize each leg's scores to a
  comparable range before the weighted sum so a leg with a compressed score range (dense on
  CLERC, all ~0.03) cannot dominate or pollute by scale artifact. Relevant to the bestChunkScore
  ~0.03 pack symptom.

## R2 — Determinism in approximate NN / hybrid search (issue 4)

- **HNSW graph construction is insertion-order dependent** and thus not bit-reproducible across
  rebuilds unless the insertion order and any RNG seed are pinned (this is a documented property
  of HNSW / Lucene's `KnnVectorField` — the graph's neighbor lists depend on the order documents
  are added). External guidance is consistent: exact reproducibility of ANN requires either
  exact search or a fully pinned build; otherwise variance is expected and *should be disclosed*,
  not hidden. This directly supports Framing C's "budget, not guarantee" stance.
- **Lucene ranking ties and internal docids**: Lucene's default score ties are broken by
  internal docId, which is assigned by insertion order and reassigned on every rebuild/merge.
  This is well-documented Lucene behavior; the standard remedy is an explicit **stable secondary
  sort on a persistent tie-break key** (a stored document id) rather than relying on internal
  docId. This is the concrete, fixable determinism lever the DESIGN phase evaluates.
- **BM25 collection statistics vary with segment structure**: term/collection statistics used by
  BM25 can differ across index builds when segment merging differs, subtly shifting scores. The
  effect is usually small; it matters here only because legal ranking is BM25-dominated and
  near-tied, so small score shifts can cross tie boundaries. Bounded-and-disclosed territory.

## R3 — Multi-hop QA taxonomy (issue 14, light)

- The multi-hop QA literature (e.g. **HotpotQA**, Yang et al. 2018; **2WikiMultiHopQA**, Ho et al.
  2020) defines "hop" behaviorally as the number of *reasoning/retrieval* steps needed to bridge
  entities — sense (a) of Framing D. This is the *campaign-analysis* meaning the 725 forensics
  used. It does **not** bind the 707 corpus builder, which may define its own strata; the
  research value here is only to establish that the behavioral sense is the field-standard one,
  so if the builder means something else the correction is a *definition/vocabulary* fix, and if
  the builder means the behavioral sense then `1_hop` on a facility→engineer→value chain is a
  genuine mislabel.

## What research does NOT warrant

- No new encoder / model research (708 closed it: no swap).
- No response-shape research (725 pass #2 covered it).
- No general "how to build a legal search engine" survey — out of scope; the levers here are
  fusion-composition, determinism, and label-correctness, each narrow.

---

# Phase 3 — DESIGN

> Evidence discipline: every load-bearing claim below was verified against source in this
> session (orchestrating agent re-read each cited line; subagent findings were independently
> re-verified per `audit-without-test` — one subagent claim was **refuted** and is recorded
> as such).

## 3.1 Issue 2 — the fork is found, and it is at the pack's *first stage*

### The verified mechanism (file:line chain)

1. `justsearch_answer` always calls `retrieveContext` with `docIds = Set.of()`
   (`modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java:439`).
2. On empty docIds the head **pre-searches** to discover the pack's document universe:
   `RemoteDocumentService.retrieveContext` → `preSearchForDocIds(params, topK*2)`
   (`modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteDocumentService.java:278-290`).
3. **The pre-search sends a bare `SearchRequest`** — query + limit only, no `pipeline`, no
   `mode` (`RemoteDocumentService.java:315-317`).
4. In the worker, a request without a pipeline hits the **deprecated mode fallback** with a
   WARN (`reason_code: deprecated_mode_fallback`,
   `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/plan/SearchPlanner.java:52-59`),
   and the unset mode enum resolves to the `default` branch of `modeToDefaultPipeline`:
   **sparse-only + expansion + LambdaMART — no dense leg, no SPLADE leg**
   (`SearchPlanner.java:310-315`).
5. Meanwhile `justsearch_search` defaults to `mode="hybrid"` (`McpToolSurface.java:658`),
   which expands to **sparse + dense fused by RRF** (`SearchPipelinePresets.java:53-54`;
   `LegSet.Bm25Dense` at `SearchPlanner.java:219-220`).
6. The pre-search then **discards rank order** — results collected into an unordered
   `HashSet` keyed on the `path` field (`RemoteDocumentService.java:371-377`) — and the
   pack's universe is capped at `min(topK*2, 20)` docs (`:317`), i.e. **10 docs** for the
   default `top_k=5`.

**Consequence:** the two tools' *doc-level* rankings are produced by different leg sets
(sparse-only+expansion vs sparse+dense RRF). A doc that hybrid ranks #1 (lifted by the dense
leg, as in the 725 probe) can be absent from the sparse-only top-10 and is then
*structurally excluded* from the evidence pack — exactly the observed symptom. The WARN log
marks this as an unintentionally-deprecated path, not a design choice.

### Fork-vs-projection verdict (the register question)

The fusion **primitives are shared** — both paths use the same `HybridFusionUtils.fuseWithRRF`
and the same `HybridSearchOps.computeLowSignalGating` (doc path: `HybridSearchOps.java:400-485`;
chunk path: `ChunkSearchOps.java:615-628`) — so the chunk-level retrieval is a *projection* of
the same fusion machinery at a different granularity, which is legitimate (Framing A: different
units, different objective). **The fork is at entry configuration**: the pre-search's
implicitly-deprecated pipeline vs the MCP preset. That is drift, not design, and it is the
correction target. The chunk stage's own objective differences (rerank + diversification,
`RagContextOps.java:530-533`) are declared and stay.

Corrective note for the 725 inventory text: the "cc weights sparse 0.6 / dense 0.2 / splade 0.2"
surfaced at `/api/status` apply only to the **3-way CC path** (`ResolvedConfigBuilder.java:467-469`,
`:1547-1549`; consumed at `SearchExecutor.java:437-441`), which default MCP hybrid does **not**
take (it is 2-leg RRF). They are not the disagreement mechanism.

### Secondary defect — empty-docIds contract asymmetry (kept honest)

A subagent audit claimed the empty-docIds short-circuit in
`ChunkSearchOps.searchChunksHybrid` (`ChunkSearchOps.java:504`, `:571` — both overloads return
an empty result on empty docIds) fires on every unfiltered `justsearch_answer` call. **Refuted**:
the head's pre-search populates docIds first (`RemoteDocumentService.java:278-290`), so the
short-circuit is not the production-path cause. It remains a real *contract asymmetry*: the BM25
chunk path treats empty docIds as **unscoped** (`ChunkSearchOps.java:156` checks only queryText;
scope applied only when non-empty at `:187`, and `RagContextOps.java:512` explicitly routes
empty-docIds BM25 calls there), while both hybrid overloads silently return nothing. The residual
live path: if the pre-search itself returns zero docs, `effectiveParams` keeps empty docIds
(`RemoteDocumentService.java:280` guard) → worker hybrid short-circuits → `NO_CHUNKS_FOUND` →
`FULLTEXT_FALLBACK` over an empty scope (`RagContextOps.java:297-311`). Design: align the hybrid
overloads with the BM25 semantics (empty = unscoped) *or* make the contract explicit at the seam;
alignment is preferred (one semantics, no caller-side special case), gated by a test and a live
check of `buildFallbackWithVirtualChunks` behavior on empty scope (derisk ask A3).

### Pack curation on low-signal corpora (the F-029 half)

With the universe fork fixed, the pack on CLERC-shaped corpora still faces Framing B's
objective problem: hybrid is effectively BM25-only there (F-029: dense R@10 0.10→0.03, SPLADE
0.15→0.005 — search-quality register), and a budget-filling pack admits noise
(bestChunkScore ~0.03, computed at `RagContextOps.java:636-659`, wired to the wire response at
`:325-331`). Two levers, both language-agnostic:

- **Honesty (ship on evidence, cheap):** a descriptive low-confidence line in the answer
  tool derived from the quality block (bestChunkScore/scoreGap/coverage) — proposed by 725
  derisk U3, *not* included in the shipped W2 header; this tempdoc owns it. Pure projection of
  data already on the wire; no retrieval change; no register concern beyond the existing
  `mcp-evidence-projection` entry that already carries quality signals.
- **Objective alignment (eval-gated, owner-visible):** an admission floor / budget-fill policy
  so low-signal corpora yield *smaller, honest* packs instead of noise-padded ones. This is a
  ranking-behavior change → pre-registered local eval + `/search-quality` register entry +
  owner call. RRF-vs-score-fusion questions (Research R1) route to the register as a recorded
  option, not shipped here.

### Principle (promoted, with retirement condition)

**One universe, two objectives:** the evidence pack's *candidate document universe* must be a
projection of the same doc-ranking surface `justsearch_search` exposes (same pipeline
configuration source); the pack may then diverge by *declared objective* (chunk granularity,
rerank, diversity, budget) but never by configuration drift. Earns its keep when the
pack-vs-search agreement metric (Phase 5) stops regressing across releases; retires if pack
retrieval is deliberately redesigned as an independent surface with its own eval-proven config
(that design would then register the divergence explicitly).

## 3.2 Issue 4 — determinism: two fixable items, one inherent, classified

Verified classification (subagent findings; load-bearing lines re-checked where they drive a fix):

| Mechanism | Verdict | Evidence |
|---|---|---|
| Chunk-boundary nondeterminism | **NOT a factor** — `ChunkSplitter.split()` is a pure function of content+config | `modules/indexing/.../chunking/ChunkSplitter.java:224-298` |
| BM25 collection statistics | **NOT a factor** for a settled rebuild — stats are content-determined sums at query time; similarity constants fixed | `ComponentsFactory.java:241-245` |
| Ranking tie-breaks | **NOT a factor** — already guarded: relevance sort always carries a stable external-id secondary (`LuceneRuntimeUtils.java:339,346-355`); fusion tie-breaks compare stored docId strings, not internal Lucene ids (`HybridFusionUtils.java:258-283`, `:724-727`) — this **refutes Framing C's tie-break hypothesis** (recorded as theory corrected by evidence) |
| Filesystem walk order | **Already fixed** (tempdoc 391): sorted enumeration with an explanatory comment documenting exactly this bug class | `SyncDirectoryOps.java:242-260`, sort at `:336-337` |
| Job-queue claim order | **FIXABLE gap**: `SqliteJobQueue.enqueue` stamps one `System.currentTimeMillis()` for the whole batch (`SqliteJobQueue.java:278`, reused `:286`); `pollPending` orders by `last_updated ASC` with **no secondary tie-break** (`:305-326`) — tie order rests on SQLite implementation detail, the same reliance class the walk-order fix eliminated | fix: explicit `, path ASC` secondary |
| HNSW graph construction | **INHERENT** — insertion/merge-order-dependent topology under the default concurrent merge scheduler (`ComponentsFactory.java:193-232`; codec `JustSearchCodec.java:31-35,60-87`); per-segment scalar-quantization intervals add second-order score shifts. Bound and disclose, do not chase to zero |

**New hypothesis this design adds (H4-B, the amplifier):** a rank-1→outside-top-10 swing is
large for pure neighbor jitter. The low-signal gating (`HybridSearchOps.computeLowSignalGating`,
`HybridSearchOps.java:157-206`; weights 0.75 vs 0.3 at `:42,:50` — shared by both doc and chunk
paths) is a **threshold-keyed discontinuity**: if HNSW rebuild variance moves the dense top
score across the low-signal threshold, the fusion weight flips 0.75↔0.3 for the whole query —
converting small inherent variance into a large rank swing. This is the profile the druker7 case
matches. Status: hypothesis; the derisk experiment (A2) logs gating decisions across two clean
re-ingests to confirm or kill it. If confirmed, the *candidate* remedy (hysteresis or a
continuous weight ramp) is a ranking change → its own pre-registered eval + register entry +
owner call; it does NOT ship from this tempdoc by default.

**Deliverable framing:** a *reproducibility budget* — fix the gratuitous sources (job-queue
tie-break; keep the walk-order fix pinned by a test if none exists), disclose the inherent one
(a short canonical-doc note: rankings are stable modulo declared ANN variance), and add a
regression instrument (Phase 5) so future rebuild-instability reports are measurable instead of
anecdotal. Explicitly out: promising deterministic ANN.

## 3.3 Issue 14 — verdict: vocabulary collision, not a builder bug; correct by definition + derivation, not relabel

### Evidence

- The builder sets `question_type: f"{len(ents)-1}_hop"` — **"hop" = relation *edges* in the
  planted entity chain** (`scripts/jseval/jseval/corpus_generate.py:354`, same formula at
  `:388`, `:419`). A `hops=1` chain has 2 entities and plants **2 gold docs** (one per entity;
  the relation doc and the value doc — doc emission at `corpus_generate.py:330-346`), and its
  query references the head entity (possibly only by synonym descriptor) asking for the tail's
  value — so answering behaviorally requires **two retrievals**. Tempdoc 707 uses the same
  edge-sense consistently ("12 gold chains (hops=1) … n_chains×2 = 24 injected gold docs",
  `docs/tempdocs/707-pillar1-inband-utility-corpus.md:322`).
- The 725 campaign analysis used the field-standard *behavioral* sense (retrieval hops,
  HotpotQA-style — Research R3), i.e. edges+1. Both vocabularies are internally consistent;
  the collision is at the reader.
- The behavioral count is **already derivable** from the committed artifact:
  `len(evidence_ids)` (emitted alongside the label at `corpus_generate.py:354`) equals the
  entity count = behavioral retrieval hops.

### Why NOT relabel the committed artifact

`queries.json` bytes are commitment-bound: `corpus_certify.py:107` computes
`query_gold_sha256 = _sha256(root/"queries.json")` and policy cells key on that digest
(`corpus_certify.py:274,292`) — a relabel invalidates every committed signature and re-opens
the CRLF build-byte nuance (tempdoc 725 §Known caveats #2: digests hash CRLF build-time bytes;
LF checkouts must regenerate via `corpus-query-stratum-build` to reproduce). Churning the
certified corpus family to rename a self-consistent label is cost without measurement benefit.

### The correction (three parts, zero digest churn now)

1. **Definition at the source:** make the edge-count semantics explicit in the builder
   (docstring at the `question_type` emit sites) and in 707's corpus documentation — one
   sentence: `question_type: N_hop` counts chain *edges*; behavioral retrieval hops =
   `len(evidence_ids)` = N+1.
2. **Consumer-side derivation:** any campaign analysis slicing by question type derives
   `retrieval_hops = len(evidence_ids)` instead of parsing the label (the 624-adjacent analysis
   scripts; coordinate with area tempdoc 729, which owns instrument integrity).
3. **Future-generation option (owner call, folded into the next regeneration that happens for
   other reasons):** emit an explicit `retrieval_hops` field alongside `question_type` so the
   artifact self-describes both senses. Additive but digest-changing — never a standalone
   regeneration.

## 3.4 Orphans (named, owned by this design)

1. **The deprecated `modeToDefaultPipeline` fallback path** (`SearchPlanner.java:52-59,298-317`)
   — after D-2.1 the pre-search stops hitting it; audit remaining bare-request callers; if the
   pre-search was the last production caller, the WARN becomes dead-in-production and the
   fallback's deprecation can finally be enforced (follow-up decision, not silently deleted).
2. **The rank-discarding `HashSet` at `RemoteDocumentService.java:371`** — superseded by
   order-preserving collection (D-2.2); an instance of 725's named "dropped at the boundary"
   shape (data computed upstream, silently discarded at a projection seam).
3. **Framing C's tie-break hypothesis** (this tempdoc, Phase 1) — corrected by evidence in
   §3.2; kept as dated history per append-only convention.
4. **The subagent short-circuit-fires-in-production claim** — refuted in §3.1; the underlying
   asymmetry survives as the secondary defect.
5. **725 inventory issue-2 text's cc-weights attribution** — corrected in §3.1 (2-leg RRF, not
   3-way CC, is the default MCP path).

---

# Phase 4 — DERISK

## Statically retired this session (code reads; no live stack needed)

- **U-A (fork mechanism real?)** — RETIRED CONFIRMED. Full file:line chain in §3.1, every link
  re-read by the orchestrating agent (`McpToolSurface.java:439` → `RemoteDocumentService.java:278-290,315-317,371`
  → `SearchPlanner.java:52-59,310-315` vs `McpToolSurface.java:658`/`SearchPipelinePresets.java:53-54`).
- **U-B (short-circuit asymmetry)** — RETIRED CONFIRMED as contract asymmetry
  (`ChunkSearchOps.java:504,571` vs `:156,:187`); refuted as the production-path cause.
- **U-C (issue-14 semantics)** — RETIRED CONFIRMED (builder emit sites + 707's own usage +
  digest coupling, §3.3). No external ambiguity remains; the correction is documentation +
  derivation.
- **U-D (determinism classification)** — mostly retired: pure-function chunking, guarded
  tie-breaks, sorted walk verified; job-queue gap verified fixable.

## Live asks (require the orchestrator's dev-stack lease; this agent ran none)

- **A1 — fork live confirmation + fix validation.** Same verbatim query through
  `justsearch_search` and `justsearch_answer` on the CLERC member; capture the worker WARN
  (`deprecated_mode_fallback`) and both doc universes pre-fix; re-run post-D-2.1 and confirm
  the WARN is gone and the search top-K ⊆ pre-search universe. Cheap (minutes under lease).
- **A2 — re-ingest determinism experiment (needs a lease window: two clean ingests of the same
  corpus + fixed query set).** Log per-query gating decisions (low-signal flip) + top-10
  overlap across builds. Decides H4-B (amplifier) vs pure-ANN-jitter; also the baseline for the
  regression instrument. Regression queries: druker7 + 725's q8/q14.
- **A3 — `buildFallbackWithVirtualChunks` on empty scope** (`RagContextOps.java:310-311`):
  confirm behavior when the pre-search finds nothing, before changing the hybrid empty-docIds
  contract.

## Confidence (0-10, per issue)

- **Issue 14: 9/10.** Fully evidenced; correction is documentation + consumer derivation; the
  only residual is coordination with 729 on where analysis slices live.
- **Issue 2: 7/10.** The fork is verified statically end-to-end and the fix is small and
  well-located (send the MCP preset's pipeline in `preSearchForDocIds`); held back because the
  druker7-probe attribution is not yet live-confirmed (A1) and because pack-curation *quality*
  improvements on F-029 corpora are inherently eval-bound (could measure flat).
- **Issue 4: 6/10.** The fixable item (job-queue tie-break) is trivial and safe; the headline
  attribution (H4-B gating flip vs inherent HNSW) is a hypothesis until A2 runs — an honest
  "inherent variance, disclosed + instrumented" close is a live possibility and must be
  acceptable to the owner.

## Recommended staffing

Sonnet (medium effort) implementation workers on the bounded increments below, briefs carrying
the file:line fixtures from this tempdoc; one opus refute-first reviewer before any commit
(reviewer ≠ implementer); pre-search-fix wording of the pipeline config, eval pre-registration,
and all register updates stay main-loop. **Orchestrator-only step (flagged): load
`/search-quality` before implementation and update the register before closing this tempdoc**
— any fusion/curation/ranking change lands an F-0xx entry; the gating-flip finding (if A2
confirms) is register-worthy on its own.

---

# Phase 5 — PLAN

## Increments (order = dependency + risk)

**I1 — Pre-search alignment (issue 2 primary; smallest correct fix).**
`preSearchForDocIds` sends the same pipeline configuration the MCP search default uses
(explicit `PipelineConfig` = hybrid preset; single-source it from `SearchPipelinePresets`
rather than hand-building — projection, not a second copy), preserves rank order
(`LinkedHashSet`), keeps the 20-doc cap for now (universe *composition* is the defect;
resizing is I6's eval question). Tests: a wire-shape regression test pinning that the
pre-search request carries an explicit pipeline (fails if anyone reverts to the bare request);
existing `deprecated_mode_fallback` WARN becomes the canary. Files:
`RemoteDocumentService.java:311-380` (+ test module). Verify: build + `:modules:app-services:test`
+ A1 live smoke under lease.

**I2 — Job-queue claim-order tie-break (issue 4 fixable half).**
Explicit secondary sort (`, path ASC`) in `pollPending` (`SqliteJobQueue.java:305-326`);
unit test seeding a same-timestamp batch and asserting claim order. No ranking semantics
change; no register entry needed.

**I3 — Hybrid chunk-search empty-scope contract (issue 2 secondary).**
After A3: align both `searchChunksHybrid` overloads with the BM25 path's empty-=-unscoped
semantics (`ChunkSearchOps.java:504,571`), or (if A3 shows the fallback is load-bearing)
document the contract at the seam and add the explicit test either way. Small, worker-local.

**I4 — Issue-14 correction.**
Builder docstrings at the three emit sites (`corpus_generate.py:354,388,419`); a definition
paragraph in 707's corpus docs; consumer-side `retrieval_hops = len(evidence_ids)` derivation
in the campaign-analysis path (coordinate with 729 — if 729's instrument work is in flight,
hand them the derivation as a one-line spec instead of double-editing). No regeneration, no
digest churn; the additive `retrieval_hops` field rides the next owner-approved regeneration.

**I5 — Determinism instrument + classification run (issue 4 headline).**
A jseval recipe: ingest the same corpus twice (clean), run the fixed query set, emit per-query
top-10 overlap + gating-decision log; run once under the orchestrator lease (A2). Output
decides H4-B. If confirmed → write the hysteresis/ramp option as a *separate eval-gated
proposal* (register + owner call); if refuted → close issue 4 as "gratuitous sources fixed
(I2), inherent ANN variance disclosed", with the instrument kept as the regression guard.

**I6 — Pack honesty + curation eval (issue 2 quality half; strictly $0 measurement).**
(a) The quality-block-derived low-confidence line in `justsearch_answer` (descriptive,
725-grammar, projection of fields already on the wire — `RagContextOps.java:325-331`).
(b) Pre-registered local eval for any admission-floor/budget-fill change: **no paid cells** —
the measurement is entirely the free local paths: (i) *agreement metric*: for the committed
query sets, top-1/top-K containment of `justsearch_search` results in the answer pack
(`search_query` + `retrieveContext`, local HTTP/MCP, $0); (ii) *gold-in-context rate* on the
CLERC member via the existing jseval RAG-surface machinery (the F-030 probe pattern), $0.
Ship the honesty line on evidence; the curation change only on eval + owner call + register
entry.

## Verification matrix

Every increment: `./gradlew.bat build -x test` + affected module tests; jseval pytest for
I4/I5/I6 script changes; full `gradlew test` before the branch is declared done; A1-A3 live
smokes under the orchestrator's lease only. I1 and I6a additionally re-run the
pack-vs-search agreement metric as the acceptance check (the metric that made issue 2 visible
becomes the metric that proves it fixed).

## Subagent split (per repo model routing)

- I1, I2, I3: three bounded sonnet implementation briefs (each self-contained with the
  file:line fixtures above; acceptance = named tests green).
- I4: one sonnet brief (docs + derivation), 729-coordination note included.
- I5: sonnet builds the instrument; the *run* is main-loop-supervised under the lease.
- I6a: sonnet; I6b eval design + pre-registration wording: main loop.
- One opus refute-first review across the branch before any commit lands.
- Main loop only: `/search-quality` register load + update, lease management, owner asks
  (regeneration decision, curation-change ship call, hysteresis proposal if H4-B confirms).

## STOP

This tempdoc ends at plan per the 725 remediation program protocol. No implementation, no git
operations, no dev-stack calls were made by this area agent. Awaiting orchestrator review
(reviewer ≠ author) before any implementation wave.
