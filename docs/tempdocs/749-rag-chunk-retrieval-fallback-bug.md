---
title: RAG chunk-retrieval fallback bug — /api/chat/ask misses the top HYBRID hit on some queries
status: "open — ROOT CAUSE NARROWED 2026-07-17 (session a6d2af56, live repro on fully-enriched scifact): the failure is at CHUNK retrieval — affected docs have a doc-level entry+embedding (found by /api/knowledge/search) but NO searchable chunk entries, so RAG chunk search returns bm25Hits=0/knnHits=0 and falls back to full-text, missing them. NOT an enrichment-timing artifact (enrichment 100%). Two earlier hypotheses REFUTED live (empty-docIds short-circuit; coverage-incomplete). One sub-question open before a fix: WHY do some fully-enriched docs lack chunk entries (chunking skip vs ≥95% ready-threshold gap vs writer bug). See §Live investigation for evidence + repro recipe. Spawned from tempdoc 734 round 6."
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

### Root cause — NARROWED to: affected documents have a doc-level entry+embedding but NO searchable chunk entries

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

## Suggested fix shape (not yet investigated in code)

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
