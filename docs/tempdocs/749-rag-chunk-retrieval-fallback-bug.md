---
title: RAG chunk-retrieval fallback bug — /api/chat/ask misses the top HYBRID hit on some queries
status: "open — found and reproduced twice (deterministic) in Sandbox round 6 (2026-07-16), not yet root-caused or fixed. Spawned from tempdoc 734 round 6; full round context and evidence live there."
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
