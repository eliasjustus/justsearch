---
title: RAG chunk-retrieval fallback bug — /api/chat/ask misses the top HYBRID hit on some queries
status: "open — ROOT CAUSE CONFIRMED 2026-07-17 (session a6d2af56, live repro + source). `ChunkDocumentWriter` writes ZERO chunk documents for docs < 2000 chars (`CHUNK_THRESHOLD_CHARS`) or that split into ≤1 chunk; RAG chunk retrieval filters `IS_CHUNK:true` so it only sees chunk documents → sub-2000-char docs (85.2% of the scifact corpus) are invisible to RAG chunk retrieval and fall back to whole-doc BM25, which unscoped misses the best short doc. The 'query-dependence' is really: a RAG ask retrieves chunks only when its ~10-doc scope happens to include one of the ~15% ≥2000-char chunked docs. Doc-level search is unaffected (finds them). DESIGN SETTLED 2026-07-17 (§Design): option A — `ChunkDocumentWriter` writes ONE whole-doc chunk for short/single-chunk docs instead of dropping them (completes the `IS_CHUNK:true` retrieval contract; matches universal splitter convention; fixes both failure sub-cases). B (merge a doc-level retrieval leg) kept only as the no-re-index fallback; C (hybrid-ise the fallback) ruled out. In-scope alongside the writer change: fusion parent-doc dedup, EXTEND 718's chunk-completeness guard to the per-doc-class gap, reranker length-calibration check, and a MANDATORY /search-quality eval before/after with a falsifier. Names a retrieval-completeness invariant (conform to 717/718, no parallel structure). DERISK 2026-07-17 (§Derisk, 4 probes) RE-OPENED the A-vs-B call: A is more invasive than the design assumed (ChunkSplitter returns 2 chunks not 1 for short docs — a real splitter bug; flips isShortCorpus/chunk_merge; needs 718-guard rework; needs a re-index existing users can't self-serve via the UI — though a FRESH v0.2.0 install gets A for free). B works with the short-corpus machinery (no writer/index/guard change, no re-index). Updated lean: B for alignment+no-migration, A only attractive for the fresh-install path. R1/R8 favourable (dedup+citation already safe); R5/R6 fine. Confidence: bug+fix achievable 8/10, A-as-designed 4/10. Difficulty HIGH → opus-tier design-revision+implementation. FOUNDER CALL: re-decide A vs B with these findings. Spawned from tempdoc 734 round 6."
created: 2026-07-16
updated: 2026-07-16
---

# RAG chunk-retrieval fallback bug — `/api/chat/ask` misses the top HYBRID hit on some queries

## Problem statement

`/api/chat/ask` (the RAG "Ask" escalation rung) occasionally falls back to
`retrieval_mode: FULLTEXT_FALLBACK, reason: NO_CHUNKS_FOUND` and misses the single most
relevant document for the question asked — even though the plain `/api/knowledge/search`
endpoint (HYBRID mode) reliably finds that same document as the #1 hit for the identical
query text.

## Repro

Found during Sandbox round 6 (tempdoc 734), while investigating retrieval quality via the
"Ask" rung as part of that round's general coverage pass (not a targeted hunt for this bug —
it surfaced incidentally).

- **Query:** "What does the SciFact corpus say about yeast as a model organism?"
- **`/api/chat/ask` result:** `retrieval_mode: FULLTEXT_FALLBACK`, `reason: NO_CHUNKS_FOUND`,
  `retrieval_coverage: 0.0`. The answer does not cite `1631583.txt` (the SciFact yeast
  abstract) and the LLM honestly states the documents do not contain an answer — it does
  **not** hallucinate. `shape:core.rag-ask`'s "label honest" requirement holds; its
  "grounded" requirement does not, for this query.
- **Control:** the same query text against `/api/knowledge/search` (HYBRID, limit 10)
  reliably returns `1631583.txt` as the **#1 hit**, score 0.92.
- **Reproduced twice, deterministically** — not a flake. Both runs hit the same
  `FULLTEXT_FALLBACK`/`NO_CHUNKS_FOUND` path and missed the same document.
- **Differential control:** a differently-worded query ("Lyme disease tick surveillance")
  took the working `CHUNK_HYBRID` code path on the same running instance and correctly
  retrieved its target document. So the failure is **query-dependent, not universal** — the
  chunk-retrieval path works for some queries and silently falls back for others on the
  identical corpus/index/build.

Raw evidence (Sandbox round 6, `tmp/sandbox/share/evidence/`, referenced from tempdoc 734):
`chat-ask-yeast-raw-sse.txt`, `chat-ask-yeast-repro2-sse.txt` (the two reproductions),
`chat-ask-lyme-raw-sse.txt` (the differential control that worked), `yeast-full-question-search.json`
and `yeast-hybrid-search.json` (the `/api/knowledge/search` control showing the #1 hit).

## What this is and isn't

- **Not the same root cause as tempdoc 734's finding 5** (the golden-parity regression
  measured via `/api/knowledge/search`) — this is a distinct code path
  (`/api/chat/ask`'s chunk-retrieval-then-RAG pipeline vs. plain document-level HYBRID
  search), even though both surfaced from the same Sandbox round and both point at some
  kind of retrieval-quality gap on this build. Do not conflate the two findings or assume
  fixing one fixes the other — they need independent root-causing.
- **Not a hallucination risk.** The LLM's honesty behavior is correct throughout: when
  chunk retrieval fails, it says so rather than fabricating an answer from parametric
  knowledge. The severity here is "misses a real, available answer," not "gives a wrong
  answer confidently."
- **Query-dependent**, confirmed by the Lyme-disease differential control on the same
  instance/build/corpus. Whatever determines `FULLTEXT_FALLBACK` vs `CHUNK_HYBRID` is
  sensitive to the specific query, not a systemic always-broken path.

## Live investigation (2026-07-17, session a6d2af56) — root cause NARROWED, one sub-question open

Reproduced on a live dev stack (worktree `749-rag-fallback`, this worktree's own dist),
scifact corpus (all 5184 docs incl. the yeast doc `1631583.txt`), **fully enriched**
(`embeddingCoveragePercent: 100`, `chunkEmbeddingReady: true` — so NOT an enrichment-timing
artifact). Qwen3.5-9B chat model, cuda12. All evidence below is from the live run.

### Confirmed (decisive)

1. **The bug reproduces and is doc-dependent, not a flake.** Unscoped `/api/chat/ask`:
   - "yeast as a model organism" → `retrieval_mode: FULLTEXT_FALLBACK`, `reason: NO_CHUNKS_FOUND`,
     `retrieval_coverage: 0.0`; the answer does NOT cite `1631583.txt` and pulls scattershot
     docs (even an unrelated homelessness paper).
   - "Lyme disease tick surveillance" → `retrieval_mode: CHUNK_HYBRID`, `reason: HYBRID_AVAILABLE`
     — works. Same index, same empty docIds, same enrichment state. (Evidence:
     `tmp/ask-yeast-unscoped.sse`; live curl transcripts in the session record.)
2. **`/api/knowledge/search` HYBRID finds `1631583.txt` as a top hit (score ~10)** for the same
   query — the document-level index has the doc and its embedding; document-level retrieval is
   fine.
3. **The failure is at CHUNK retrieval.** Worker debug logs (`.dev-data/logs/worker.log`,
   `ChunkSearchOps` / `HybridSearchOps`) for the yeast query show
   `searchChunksHybrid (Phase 6): bm25Hits=0 knnHits=0` — **both** lexical BM25 **and** vector
   kNN chunk search return zero — with gating `lowSignal=true, bm25Top=0.0, vectorTop=0.0`.
   The Lyme query on the same path shows `bm25Hits=4 knnHits=5 bm25Top=5.06 vectorTop=0.61`.
   So for the affected doc/query, there are **no matching chunks at all** (not a threshold
   trimming good chunks — there is nothing to trim).
4. **Scoping to the exact doc does not help.** A `/api/chat/ask` scoped to `docIds:[1631583.txt]`
   ALSO returns `NO_CHUNKS_FOUND` (log: `searchChunksHybrid (Phase 6) ... scope=1, bm25Hits=0
   knnHits=0`). A doc that literally opens *"Getting started with yeast. … the yeast
   Saccharomyces cerevisiae is now recognized as a model system"* yields **zero** BM25 chunk
   hits when scoped to itself → its chunk-content is not searchable in the chunk index.

### Hypotheses REFUTED during the investigation (recorded so they aren't re-chased)

- **REFUTED: empty-docIds short-circuit.** Initial static reading flagged
  `ChunkSearchOps.searchChunksHybrid` returning `(List.of(),0,0)` on empty `docIds` (lines ~504
  & ~571) vs `searchChunksFiltered` treating empty as "search all chunks" (line ~186). The
  scoped control (finding 4) and the Lyme control (finding 1, empty docIds yet CHUNK_HYBRID)
  both refute this as the cause — the live worker logs show `scope=1` and `scope=10`, never
  the empty path. (A textbook `verify-don't-guess` / `audit-without-test` save: the confident
  source hypothesis was wrong; the live control caught it.) *Note the inconsistent empty-docIds
  semantics between the two methods is still a latent smell worth a separate look, but it is
  NOT this bug.*
- **REFUTED: enrichment-timing / chunk-coverage-incomplete.** Enrichment is 100% and
  `chunkEmbeddingReady: true` at repro time; the doc still has no searchable chunks.

### ROOT CAUSE — CONFIRMED (source + data decisive, 2026-07-17)

`ChunkDocumentWriter.regenerateChunks` writes **zero chunk documents** for a doc when either
guard fires:
- `modules/worker-services/.../rag/ChunkDocumentWriter.java:92` —
  `if (content.length() < CHUNK_THRESHOLD_CHARS) return 0;` with **`CHUNK_THRESHOLD_CHARS = 2000`**
  (line 28).
- `:102` — `if (chunks.size() <= 1) return 0;` (a doc that splits into a single chunk).

Every chunk-search query in `ChunkSearchOps` filters `IS_CHUNK:true` (lines 80/128/183/246/298/424)
— it searches **only** chunk documents, never whole-doc entries. So a doc with no chunk documents
is **completely invisible to RAG chunk retrieval**, even though it has a document-level entry +
embedding (which is why `/api/knowledge/search` still finds it).

**Measured on the live corpus: 4,419 of 5,184 scifact docs (85.2%) are < 2000 chars → no chunk
entries at all.** Only the ~15% of docs ≥ 2000 chars are chunk-searchable. The yeast doc
(1500 chars) and even the Lyme *top* doc (1781 chars) are both below the threshold and both
chunk-less — the Lyme ASK nonetheless succeeded because its ~10-doc retrieval scope happened to
include a ≥2000-char doc with a matching chunk, so chunks came from a *neighbour*, not Lyme's own
best doc. That is the true nature of the "query-dependence": **a RAG ask surfaces chunks only when
its doc-scope happens to contain one of the minority long docs; when the best answer lives in a
short doc, RAG chunk retrieval returns nothing and falls back to whole-doc BM25, which — unscoped —
misses it.**

The design intent of the guards is sound for *document* search (a short doc is one unit; no
sub-document chunks needed). The defect is that **RAG chunk retrieval excludes non-chunk docs
(`IS_CHUNK:true`) with no whole-doc union**, so the "small docs don't need chunks" optimization
silently removes 85% of a short-abstract corpus from the RAG candidate set.

### Fix decision (design call — approach not yet chosen; touches retrieval behaviour + search-quality baselines)

Three shapes, with tradeoffs a founder/design pass should weigh (this is `/search-quality`
territory and any change needs eval-baseline re-validation, not just a unit test):
- **(A) Write short/single-chunk docs as one whole-doc chunk** (drop/relax the two guards so every
  doc has ≥1 chunk entry). Most direct; makes all docs RAG-retrievable. Cost: chunk index grows
  substantially (~7× more chunk-bearing docs on this corpus) and it **requires a re-index** to take
  effect; must re-check retrieval-quality baselines.
- **(B) Union whole-doc entries into chunk retrieval** for docs lacking chunks (query-time; no
  re-index) — when chunk search under-fills, also search `IS_CHUNK:false` whole-doc entries and
  treat them as single virtual chunks. Less index churn; changes query-path semantics.
- **(C) Change the RAG fallback from whole-doc BM25 to document-level HYBRID** so the fallback
  itself finds short docs via their doc-level embedding. Narrowest, but leaves the chunk path blind
  and only improves the fallback ranking.
- **Regression home (any option):** a host-tier test asserting a RAG `/api/chat/ask` for content
  that lives in a **sub-2000-char** doc retrieves that doc via the chunk path (not FULLTEXT_FALLBACK)
  when `/api/knowledge/search` HYBRID ranks it top — the exact yeast/scifact shape, now a runnable
  repro (recipe below).

**Recommendation:** (A) is the most correct (RAG should be able to retrieve any indexed doc), but
because it changes index size + requires re-index + shifts search-quality baselines, the approach
is a founder/design decision, not a unilateral edit. Root cause is confirmed and a fix is
unblocked; the *approach* is the open call.

### Research + theorization (2026-07-17, two refute-first lanes; tiered) — the fix approach

Two bounded external-evidence lanes (full reports in the session record). Both **converge on the
diagnosis and rule out option C; they split A vs B**, which sharpens the design call rather than
settling it.

**Lane 1 — production chunking practice (T1-heavy, decisive):** every mainstream splitter emits
**≥1 node per document**, treating "shorter than chunk_size" as the trivial one-chunk case, never
as exclusion. Verified in source: LlamaIndex `SentenceSplitter._split` early-returns a single
`_Split` when `token_size <= chunk_size` (T1). LangChain `RecursiveCharacterTextSplitter` returns
one document for sub-chunk text; Haystack passes a short doc through as one `Document`; Pinecone's
"small docs may not need chunking" means *index the whole doc as one record*, not *drop it* (T2).
The repo's writer **inverts this universal convention** — there is no precedent anywhere for
"too short to chunk" meaning "absent from the chunk index." Refute-first on "write every short
doc as one chunk": index cost is bounded (one chunk per currently-missing doc, not the
multiplicative blow-up of finer granularity — Dense X Retrieval arXiv:2312.06648), and dense
retrieval's documented **brevity bias** (BEIR arXiv:2104.08663; arXiv:2503.05037) means short
whole-abstract chunks are model-*favored* once visible, not buried. One real caution: cross-
encoder rerankers can score inconsistently across very-short vs long passages → a length-
calibration eval check post-fix.

**Lane 2 — fallback quality + retrieval architecture (T1/T2):** (1) BM25-only fallback is an
evidenced weak point for exactly this shape — on SciFact, BM25 ≈ dense on nDCG@10 (0.65 vs 0.65)
but **loses on Recall@100 (0.873 vs 0.925)**: it structurally *excludes* semantically-relevant,
lexically-distant abstracts from the candidate pool (vocabulary mismatch between a NL question and
an abstract). (2) A single **whole-document dense embedding is the field-standard retrieval unit
for abstract-length docs** — BEIR/MTEB SciFact embeds each ~1,400–1,700-char abstract as one
unbroken unit; below ~1,000 tokens chunking overhead buys nothing (Late Chunking arXiv:2409.04701).
(3) **C is insufficient (strongly refuted):** "a reranker can't fix what retrieval missed" — recall
is fixed at pool-construction time (arXiv:2605.01664); and a fallback fires only when chunk
retrieval returns *zero* overall, runs *outside* the primary RRF-fusion + cross-encoder rerank, and
yields different citation granularity — so even a hybrid fallback leaves the doc out of the primary
candidate set. (4) Established architectures (LlamaIndex auto-merging, LangChain parent-document,
Haystack hybrid) **never** special-case short docs onto a degraded lexical-only path: every doc gets
a uniform embedded representation competing in the *same* fused+reranked pipeline; hierarchy governs
what is *returned*, not which retrieval signal a doc is limited to.

**The one thing both lanes agree on (load-bearing):** the fix must put short docs into the
**primary hybrid + rerank candidate set** — not a separate degraded path. That **eliminates C**
outright (it only touches the fallback) and reframes A vs B as *where* to inject the short doc:

- **A — write short/single-chunk docs as one whole-doc chunk** (index-time; matches the universal
  splitter convention; makes the `IS_CHUNK:true` contract *correct/complete*; uniform pipeline).
  Cost: **requires a re-index**, ~+1 chunk per short doc (~85% of this corpus), duplication with the
  existing whole-doc entry (neutralized by a source-doc-id dedup at fusion — which overlapping-chunk
  stacks already need), plus the reranker length-calibration check.
- **B — union the existing doc-level dense entries into RAG retrieval for docs lacking chunks**
  (query-time; **no re-index** — reuses the field-standard whole-doc embedding that already exists;
  Lane 2's point that this embedding is *not* a stopgap but the standard unit for abstracts).
  Cost: a per-doc conditional in the query path (union `IS_CHUNK:false` whole-doc entries only for
  docs without chunks, else long docs double-count), different citation granularity for those
  results, and it leaves the chunk index *incomplete* (papers over the writer rather than fixing it).

**Recommended direction (theorization, not a decision): A is the more architecturally-honest fix**
— it makes the chunk index *complete* so `IS_CHUNK:true` stops silently excluding 85% of the corpus,
it is precisely what every production splitter does, and its costs are bounded and enumerated. **B
is the correct choice if a re-index is unacceptable** or index growth is a hard constraint — it is
cheaper and reuses standard-unit embeddings, at the price of a query-path special-case. **C is
ruled out by the evidence.** Either A or B is a retrieval-behaviour change that **must be
eval-validated against the search-quality baselines** (this is `/search-quality` territory), not
merely unit-tested — the regression home in §Fix decision is necessary but not sufficient; a
before/after nDCG/recall run on the utility corpora is required, plus the reranker length-calibration
spot-check Lane 1 flagged.

- **Falsifier for the recommendation:** if an A/B eval run shows the short-doc fix *degrades*
  aggregate nDCG@10 or recall on the existing utility baselines (e.g. brevity bias over-favouring
  short chunks past the point of net benefit), the "complete the chunk index" thesis is wrong for
  this corpus and the fix must be gated/tuned (length-aware fusion weight) rather than shipped flat.

**Open founder call:** A vs B (driven by *is a re-index acceptable / is chunk-index size a hard
constraint*), and confirmation that this goes through a `/search-quality` design+eval pass before
merge. Root cause is confirmed; the fix is unblocked; the approach is the decision.

### (superseded) Earlier narrowing — affected documents have a doc-level entry+embedding but NO searchable chunk entries

The document-level index has `1631583.txt` (searchable, embedded), but its **chunk_content
(BM25) and chunk_vector (kNN) entries are absent or unsearchable**, so RAG chunk retrieval finds
nothing and falls back to whole-doc BM25 — which, unscoped, misses the doc. The apparent
"query-dependence" is really **doc-dependence**: queries whose best-matching docs happen to
lack chunk entries fall back; queries whose docs have chunks succeed.

### OPEN sub-question (the fix hinges on this — hand off with the stack recipe below)

**Why do some fully-enriched docs lack searchable chunk entries?** Candidates not yet
distinguished: (a) chunk creation is skipped/failed for certain docs (short scientific
abstracts? a parse/normalization quirk?); (b) `chunkEmbeddingReady`/`chunkVectorsReady` is a
≥95% threshold (`CHUNK_VECTOR_COVERAGE_THRESHOLD = 0.95` in `RagContextOps`), so up to ~5% of
docs can lack chunk vectors while the "ready" flag is true — and the missing set may be
correlated with content shape; (c) a chunk-writer gap. Next step: inspect chunk creation
(`modules/indexing/src/main/java/io/justsearch/indexing/chunking/ChunkSplitter.java`,
`modules/worker-services/.../rag/ChunkDocumentWriter.java`) and directly count chunk documents
for `1631583.txt` vs a working doc (the `/api/debug/chunks` endpoint returned an unreliable
`hasChunks=false` for BOTH the broken and a working doc, so a lower-level chunk-count probe is
needed — e.g. a Lucene `parent_doc_id:<id> AND is_chunk:true` count).

### Reproduction recipe (for the next session)

Worktree dist built; stack: `justsearch_dev_start distFrom=<this worktree> skipBuild=true`;
ingest `.claude/worktrees/749-rag-fallback/tmp/scifact-repro` (5184 scifact docs, copied from
`scripts/jseval/tmp/eval-corpora/scifact`); wait for `embeddingCoveragePercent:100`; set chat
model `Qwen_Qwen3.5-9B-Q4_K_M.gguf` + `chatEnabled:true`, `ai_activate`. Then curl
`POST /api/chat/ask {"question":"What does the SciFact corpus say about yeast as a model
organism?"}` → FULLTEXT_FALLBACK (bug); Lyme query → CHUNK_HYBRID (control). Watch
`.dev-data/logs/worker.log` for `ChunkSearchOps` `bm25Hits/knnHits`. (cuda12 variant must be
staged into the worktree's `modules/ui/native-bin/llama-server/variants/` first — construction-
time resolution, restart after staging.)

## Design (2026-07-17, session a6d2af56) — settled: complete the chunk index (option A), conforming to the 717/718 chunk-completeness seam

Codebase facts that decide the design (verified this session):
- **Doc and chunk vectors are SEPARATE index fields** — `SchemaFields.VECTOR` (doc-level) vs
  `SchemaFields.CHUNK_VECTOR` (chunk-level); `ChunkSearchOps.searchChunkVector` issues a
  `KnnFloatVectorQuery(CHUNK_VECTOR, …)`. A short doc's existing doc-level `VECTOR` is therefore
  **invisible to the chunk kNN**. So option B ("union the doc-level entries") is *not* a trivial
  union — it is a **second retrieval leg** (doc-level BM25+`VECTOR` kNN) merged into the RAG
  candidate set. This materially narrows B's "cheaper" appeal.
- The system already has **virtual-chunk machinery** (`RagContextOps.buildFallbackWithVirtualChunks`,
  RAG-005): it splits whole docs into chunks on the fly — but only in the fallback, over
  **BM25-selected** docs. This is why the current fallback misses semantically-matched short docs.
- The bug is two per-doc exclusion guards in `ChunkDocumentWriter.regenerateChunks`
  (`content.length() < CHUNK_THRESHOLD_CHARS(2000) → return 0`; `chunks.size() <= 1 → return 0`).
  `ChunkSplitter.splitWithMetadata` already returns exactly one chunk for sub-threshold text — the
  writer then **drops it**.
- Adjacent prior art (read this session): **717** (fresh-build "chunk-death": a corpus mis-classified
  "short" skips the `chunk_merge` leg → index ships with no chunks) and **718** (an *eval-time
  chunk-completeness guard* that catches a chunk-death). This bug is the **same family — a silent
  chunk-completeness gap — at per-document-class granularity** (short docs), which 718's whole-index
  guard does not catch.

### The settled design: A — every servable document gets ≥1 chunk entry

Replace the two exclusion guards with a **single-whole-doc-chunk path**: when a document splits into
≤1 chunk (short docs, and any doc that produces one chunk), write **exactly one chunk covering the
whole document** (offsets `0..len`, `chunkTotal=1`) rather than writing nothing. Keep only a minimal
garbage floor (genuinely empty / sub-~100-char noise, consistent with `TextQualityAnalyzer`'s
"< 100 chars = garbage") so true noise still gets no chunk. This makes the `IS_CHUNK:true` retrieval
contract **complete** — RAG chunk retrieval can now reach every document document-level search can —
and it is exactly what every production splitter does (LlamaIndex/LangChain/Haystack, per the
research lanes). Both failure sub-cases are fixed uniformly: the all-short-scope case (yeast) and the
mixed-scope case (best answer is a short doc while long docs also match), because short docs now
compete in the *same* RRF-fused + cross-encoder-reranked pipeline as every other chunk.

**Why A over B/C (design judgement, not just cost):**
- **C (hybrid-ise the fallback) is ruled out** — it only fires when chunk retrieval returns *zero*
  overall, so it cannot fix the mixed-scope case; and it keeps short docs on a structurally separate,
  inconsistently-scored path (research Lane 2, arXiv:2605.01664 "reranker can't fix what retrieval
  missed").
- **B (merge a doc-level retrieval leg) is the correct fallback IF a re-index is a hard constraint**,
  but the separate-vector-field finding makes it a genuine second retrieval path with its own
  scoring, and it leaves the chunk index permanently incomplete (papering over the writer). It does
  not restore the invariant; it works around it.
- **A restores the invariant and conforms to the single existing chunk seam** (chunk writer → chunk
  search), adds no parallel retrieval path, and matches universal convention. Its costs are bounded
  and enumerated: a one-time **re-index / chunk-embedding backfill** for the ~85%-of-corpus short
  docs that currently lack chunks, ~+1 chunk row per such doc, and duplication between a short doc's
  single chunk and its whole-doc entry.

### What A requires alongside the writer change (in-scope for this tempdoc, not a later sweep)

1. **Fusion de-dup by parent-doc-id** so a short doc's single whole-doc chunk and its whole-doc entry
   do not both surface as separate hits in one answer. Verify whether the existing fusion already
   dedups on source-doc-id (overlapping-chunk stacks usually need this); if not, add it. (Do not
   assume — check `SearchExecutor`/RRF fusion.)
2. **Extend 718's chunk-completeness guard to the per-doc-class gap** — the invariant a guard should
   enforce is "a document that is document-searchable but has zero chunk entries is a completeness
   defect," not just "the whole index has a chunk leg." This is the conform-to-existing-seam move:
   grow the 718 guard, do not add a parallel check.
3. **Reranker length-calibration spot-check** (research Lane 1 caution): confirm the cross-encoder
   scores a whole-abstract single chunk sensibly against multi-chunk long-doc passages.
4. **Eval-baseline validation is mandatory, not optional** — this is a retrieval-behaviour change in
   `/search-quality` territory. Run a before/after nDCG@10 + Recall on the utility corpora
   (`scripts/jseval` utility-comparison / the 334/343 quality baselines) and record the delta in this
   tempdoc. **Falsifier:** if the fix degrades aggregate nDCG/recall (brevity bias over-favouring
   short chunks past net benefit), A is wrong-as-shipped-flat and must be gated/length-weighted at
   fusion rather than merged unconditionally.

### Orphaned / changed by this design (teardown rides with the fix, per the skill)

- `ChunkDocumentWriter`'s two exclusion guards (`CHUNK_THRESHOLD_CHARS` length-skip at :92 and
  `chunks.size() <= 1` skip at :102) — their *exclusion* semantics are removed; they become the
  write-one-whole-doc-chunk path. `CHUNK_THRESHOLD_CHARS` may be retired entirely or repurposed as
  the garbage floor — decide at implementation.
- The RAG-005 virtual-chunk fallback (`buildFallbackWithVirtualChunks`) is **NOT orphaned** — it stays
  as the safety net for genuine zero-match queries — but its firing rate should drop sharply once
  short docs have real chunks; note this in its comment so a future reader doesn't think it's dead.

### Reach judgement — the retrieval-completeness invariant (conform to 717/718, don't fork)

This design is an instance of a shape the repo already fights in the 717/718 lineage and the 743
"green-masked gap" family: **a query-time or index-time filter that silently excludes a
sub-population with no error.** Named plainly as an invariant:

> **Retrieval-completeness invariant** — every document reachable by document-level search must be
> reachable by the RAG chunk path; any `IS_CHUNK`/status/coverage filter that removes a document
> sub-population from a retrieval path must be either impossible-by-construction or **eval-guarded**,
> never a silent optimisation.

- **Where else it applies (candidate scope, not to be built now):** the `isChunkVectorCoverageReady`
  ≥95% gate (a doc in the missing ≤5% is silently vector-invisible); the SPLADE/embedding-status
  coverage filters; 717's `isShortCorpus` classification; any future retrieval leg that filters on an
  enrichment-status field. Some of these may already violate it — worth a follow-up audit, not a
  rewrite here.
- **Earns its keep if:** the extended 718 guard (item 2) catches a document-searchable-but-RAG-invisible
  class in CI/eval before it ships (as 718's guard caught chunk-death). If it never fires across many
  builds, the invariant is either already impossible-by-construction or the guard is mis-placed.
- **Retire when:** RAG and document search are unified into a single retrieval unit (no chunk/doc
  representational split) — at which point the invariant is subsumed and the guard is dead weight.

Recording the invariant here (per the skill) without building generalised structure: this tempdoc
builds only the specific fix (A) + the one guard extension the present problem requires; the wider
audit of other status-filtered retrieval legs is named, not undertaken.

**Remaining open founder call (narrowed):** A vs B is now really just *"is a one-time re-index /
chunk-embedding backfill acceptable?"* — if yes (recommended), A; if a re-index is a hard no, B as the
work-around. Plus confirmation that the fix goes through the `/search-quality` eval gate (item 4)
before merge.

## Derisk (2026-07-17, session a6d2af56, 4 read-only probes) — A is MORE invasive than the design assumed; re-open A vs B

The derisk found three surprises that materially change option A's cost/shape and one that was
favourable. **The design's "A is clearly better, flip two guards, write one clean chunk" is not
implementable as written** — corrected below. Per-risk verdicts:

- **R1 (document-search double-surface) — RESOLVED, favourable.** Chunk-aware document search already
  collapses chunk hits to one-per-parent (`SearchExecutor.collapseChunkHitsToParents`, :899-921) and
  fuses by `docId` (`HybridFusionUtils.fuseWith{CC,RRF}Named`), so a short doc's whole-doc entry and
  its new chunk merge into one hit. **No dedup work needed.**
- **R8 (whole-doc-chunk citation semantics) — RESOLVED, favourable.** A `chunkIndex=0,chunkTotal=1,
  startChar=0,endChar=len` chunk is already a live, exercised shape (the RAG-005 virtual-chunk
  fallback produces exactly it); nothing assumes `chunkTotal>1` or a strict sub-span. **No work.**
- **R4 (ChunkSplitter output) — REFUTED the design's load-bearing claim.** `splitWithMetadata` does
  NOT return one chunk for a 1500-char doc. A loop-continuation bug (`position < text.length()` after
  the first chunk already consumed the whole string) makes any text in the ~384–1923-char band return
  **TWO** chunks — the whole text + a redundant overlap-tail fragment — and the `chunks.size() <= 1`
  guard (size==2) would not catch it. So "remove the 2000-char guard → one clean whole-doc chunk" is
  false; as written it produces parent + whole-doc chunk + near-duplicate tail. **Option A must also
  fix the splitter's single-chunk case (or bypass the splitter for sub-target docs).** (This splitter
  overlap-tail bug is arguably a real defect worth its own fix regardless.)
- **R3 (short-corpus / chunk_merge / coverage interaction) — HELD, with real ripple effects.** A fights
  a deliberate design: `CorpusProfile.isShortCorpus` (`chunkRate()<0.05` fallback, :62-70) FLIPS once
  ~85% of docs gain chunks → activates the `chunk_merge` leg (`CorpusCapabilities.corpusSupportsChunks`
  → `SearchPlanner.planChunkMerge` `SKIPPED_SHORT_CORPUS`). Intended for 749's target corpus, but
  **spillover** for any short corpus crossing the 0.05 line. Coverage is a ratio so it doesn't flip
  mathematically, BUT there is a **transient `isChunkVectorCoverageReady=false` window** while the burst
  of new chunks' embeddings backfill (totalChunks jumps instantly, vectorsPresent lags). R4's 2-chunk
  bug compounds this by inflating `chunkDocCount`.
- **R7 (718 chunk-completeness guard) — NEEDS WORK, not mechanical.** The guard
  (`scripts/jseval/jseval/chunk_completeness.py` + `ratchet_kernel.py`) is a whole-index AGGREGATE whose
  `expected_chunk_docs` is itself keyed to `CHUNK_THRESHOLD_CHARS(2000)` — so it **must be reworked just
  to stay correct** after the writer stops honouring that threshold. Extending it to the per-doc-class
  gap (the design's aspiration) needs NEW server-side per-doc aggregation (enumerate `PARENT_DOC_ID`
  over chunk docs), a NEW `/api/status` surface, and a list-valued verdict — a different mechanism on
  the same seam, not a parameter tweak.
- **R2 (re-index) — NEEDS DECISION, and worse than expected.** Guard-removal does NOT backfill existing
  indices: the enrichment backfill only enriches chunk docs that already exist (by
  `CHUNK_EMBEDDING_STATUS=PENDING`); a short doc with zero chunks has no marker to find. Re-chunking an
  existing doc requires it to re-flow through `JobBatchWriter.indexChunks()`, gated by
  `isUnmodified()`. Only a true `--clean` rebuild (empty-index bypass) or an explicit
  `submitBatch(force_reindex=true)` API call re-chunks. **Critically, the Head's "Reindex Now (force)"
  UI button does NOT reach the bypass** (`RemoteKnowledgeClient` hardcodes `SCAN_MODE_INITIAL`, never
  `markForced`), so existing-index users have no self-serve path to the fix short of a full clean
  rebuild. **Nuance that rehabilitates A for the release context: a FRESH install (empty index → full
  chunk write) gets the fix for free** — so for the v0.2.0 fresh-install sandbox blocker, A works out
  of the box; the migration gap is an upgrade-in-place concern.
- **R5 (enrichment cost) — RESOLVED, manageable.** New chunk embeddings share the batched GPU call
  (slot cap 50 unchanged → no per-batch VRAM increase); total PENDING volume rises ~85% → proportionally
  more backfill cycles/wall-clock, no new pipeline type.
- **R6 (eval harness) — RESOLVED.** `scripts/jseval` measures nDCG@10/R@10 with a `343-scifact-baseline`,
  `corpus-fidelity`, and `agent_retrieval_eval`/`agent_utility_run` for the RAG path — the mandatory
  before/after validation is runnable.
- **R9 (acceptance bar) — stands:** post-fix, the unscoped yeast ask must return `CHUNK_HYBRID` and cite
  `1631583.txt` (live-verify at implementation).

### Net effect on the A-vs-B decision (the derisk's main outcome)

The design pass under-counted A and over-counted B. **A** now = writer change **+ ChunkSplitter
single-chunk fix + short-corpus/coverage-race handling + 718-guard rework + a re-index existing users
can't self-serve** — i.e. it *reverses a deliberate short-corpus optimisation* and ripples through the
corpus-classification / chunk_merge / guard machinery. **B** (union doc-level entries into the RAG
candidate set, virtual-chunked, reusing the existing doc-level hybrid search + RAG-005 machinery)
**works WITH that machinery**: no writer/splitter/classification/guard change, no re-index (doc-level
entries already exist), and the research already established a whole-doc embedding IS the field-standard
retrieval unit for abstracts. B's one real cost — a second retrieval leg merged pre-rerank into RAG —
now looks *smaller* than A's surfaced ripple set. **Recommendation: RE-OPEN A vs B as a founder
decision informed by these findings.** My updated lean is **B for architectural alignment + no
migration**, with the exception that **A is attractive specifically for the v0.2.0 fresh-install path**
(free on a clean index) *if* the upgrade-in-place migration gap and the four extra work items are
accepted. This is a genuine judgement call, not a mechanical pick.

### Confidence + difficulty (derisk deliverable)

- **Confidence the bug is understood and a correct fix (A or B) is achievable: 8/10.** Root cause is
  source+live confirmed; both fix paths are viable; the eval harness exists.
- **Confidence in A *as currently designed* (two-guard flip): 4/10** — refuted by R4/R3/R7/R2; needs a
  design revision before it's implementable.
- **Difficulty: HIGH, not mechanical.** Either path is retrieval-architecture work (writer/splitter/
  corpus-classification/guard for A; RAG query-path candidate-generation for B) plus mandatory
  `/search-quality` eval validation. **Model/effort: opus for the design-revision + implementation**
  (this is not sonnet-mechanical); a sonnet worker could execute a *fully-specified* sub-slice (e.g. the
  isolated ChunkSplitter single-chunk fix + its test) under an opus-authored brief, but the fix's core —
  choosing and wiring A-revised vs B and validating retrieval quality — is opus-tier judgement.

## Suggested fix shape (SUPERSEDED by §Design + §Derisk above — retained as the round-6 originating note)

This needs an investigation pass into the RAG chunk-retrieval threshold/fallback logic —
whatever decides `NO_CHUNKS_FOUND` (likely a chunk-relevance-score cutoff, or a chunk-index
coverage check distinct from the document-level HYBRID index) is producing a false negative
for at least this one query shape, while the document-level HYBRID path over the same
corpus finds a strong match. Candidate starting points for a host-side investigator:

- The chunk-retrieval code path invoked by `/api/chat/ask` before it falls back to
  full-text (search for `NO_CHUNKS_FOUND`, `FULLTEXT_FALLBACK`, `retrieval_coverage` in the
  RAG/chat-serving code).
- Whether chunk-level embeddings/SPLADE coverage for the specific missed document
  (`1631583.txt`) was actually complete at query time (chunk enrichment can lag document
  enrichment — cross-check `chunkVectorCoveragePercent` for that document's chunks
  specifically, not just the aggregate).
- Whether a chunk-relevance threshold is too strict for short/abstract-style documents
  (the missed document is a short scientific abstract) compared to the document-level
  HYBRID scorer's own thresholds.

## Regression home

A host-tier regression test on the RAG chunk-retrieval threshold/fallback logic, once the
root cause is identified — asserting `/api/chat/ask` does not silently fall back to
full-text when the equivalent `/api/knowledge/search` HYBRID query finds a strong (e.g.
score > some floor) top hit for the same text. Not yet written; this tempdoc exists to give
the finding a durable home separate from the Sandbox round's own evidence bundle, per the
release's "known issues at release" policy (`docs/how-to/cut-a-release.md`).

## Severity and release impact

Rated **HIGH** by the round that found it: reproduced twice, plus a differential control
that isolates it as query-dependent rather than a flake or a misconfiguration of that one
round's environment. Not yet a formal "known issue at release" decision (that requires an
explicit, dated owner decision per `cut-a-release.md`'s policy) — recorded here so it has a
tracking home while that decision is pending. This is a candidate for that classification
given v0.2.0 is already blocked on tempdoc 734's finding 5, but the owner should make that
call explicitly rather than have it happen by default.
