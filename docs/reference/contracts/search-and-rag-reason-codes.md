---
title: Search & RAG Reason Codes (Degradation)
type: reference
status: stable
description: 'Degradation signaling contract for gRPC and `rag_meta`.'
---

# Search & RAG reason codes (degradation signaling)

JustSearch surfaces explicit mode + reason metadata so clients can distinguish “keyword-only”, “semantic”, and fallback behavior without log-grepping or guesswork.

Worker-emitted reason codes are treated as a contract: they are allowlisted by `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/GrpcSearchServiceReasonCodeContractTest.java`. Head-side fallback reasons are Head-owned (not emitted by the Worker).

## Interactive search (`SearchService.Search`)

Degradation fields on `SearchResponse` (`modules/ipc-common/src/main/proto/indexing.proto`):

- `effective_mode`: `"TEXT" | "VECTOR" | "HYBRID"`
- `vector_blocked`: `true` when VECTOR search is blocked by embedding compatibility
- `vector_blocked_reason`: stable reason code for why VECTOR is blocked
- `hybrid_fallback`: `true` when HYBRID fell back to TEXT
- `hybrid_fallback_reason`: stable reason code for why HYBRID fell back

### Embedding compatibility reason codes (`EmbeddingCompatibilityController.reasonCode()`)

Used by VECTOR block and HYBRID fallback paths:

- `INITIALIZING`: compatibility state not yet computed (startup)
- `NO_EMBEDDING_MODEL`: no embedding model available on this host
- `NEW_INDEX_NO_FINGERPRINT`: new/empty index; fingerprint will be stamped on first commit
- `LEGACY_INDEX_NO_FINGERPRINT`: index has docs but no fingerprint; vector/hybrid blocked until forced reindex
- `FINGERPRINT_MATCH`: stored fingerprint matches current; vector/hybrid allowed
- `FINGERPRINT_MISMATCH`: stored fingerprint differs; vector/hybrid blocked until forced reindex
- `REBUILD_IN_PROGRESS`: forced rebuild/reindex in progress to realign embeddings
- `REBUILD_COMPLETED`: rebuild completed and fingerprint stamped

### Search degradation reason codes (`GrpcSearchService`)

Used when HYBRID cannot run as requested (may also appear for VECTOR when the compatibility controller is absent):

- `UNKNOWN`: no compatibility controller available to explain the block
- `EMBEDDING_COMPATIBILITY_BLOCKED`: vector queries blocked but controller is unavailable
- `NO_EMBEDDING_SERVICE`: no embedding service available to generate a query vector
- `EMBEDDING_GENERATION_FAILED`: query embedding returned null/empty
- `EMBEDDING_EXCEPTION`: query embedding threw an exception

## RAG retrieval (`SearchService.retrieveContext`)

Degradation fields on `RetrieveContextResponse` (`modules/ipc-common/src/main/proto/indexing.proto`):

- `retrieval_mode`: `"BM25" | "HYBRID" | "CHUNK_HYBRID" | "FULLTEXT_FALLBACK" | ""` (empty string is used for some short-circuit/error cases)
- `retrieval_mode_reason`: stable reason code explaining the chosen mode (or fallback)
- `context_truncated`: `true` when context assembly hit the budget

Allowlisted `retrieval_mode_reason` values:

- `EMPTY_REQUEST`: empty question and/or docIds
- `NO_CHUNKS_FOUND`: chunk search returned no hits; falls back to `FULLTEXT_FALLBACK`
- `BM25_CONFIGURED`: retrieval mode configured to BM25-only
- `HYBRID_AVAILABLE`: embeddings available; hybrid retrieval used
- `NO_EMBEDDING_SERVICE`: embedding service is null
- `EMBEDDING_UNAVAILABLE`: embedding service unavailable or blocked
- `EMBEDDING_EMPTY`: embedding returned an empty vector
- `EMBEDDING_GENERATION_FAILED`: embedding generation failed/errored
- `CHUNK_VECTOR_COVERAGE_INCOMPLETE`: chunk vectors enabled but coverage < 95%; falls back to doc-first hybrid (`HYBRID`)

## Head-side fallback reasons (REST/SSE callers)

The Head may fall back to a full-document fetch when gRPC retrieval fails (`modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteDocumentService.java`):

- `GRPC_FAILED`: gRPC retrieval failed; Head used full-document fallback with a character budget
- `FALLBACK_FAILED`: both gRPC and fallback failed (context is empty)

## SSE: `rag_meta` (UI streaming endpoints)

`SummaryController` emits a `rag_meta` Server-Sent Event before streaming the final text for:

- `POST /api/ask/stream`
- `POST /api/summarize/batch/stream` (and related summarize flows)

Payload shape:

```json
{
  "retrieval_mode": "HYBRID",
  "retrieval_mode_reason": "HYBRID_AVAILABLE",
  "context_truncated": false,
  "chunks_used": 5,
  "chunks_found": 12
}
```

## Reason-code governance

Reason codes are validated by the CI checks `scripts/ci/check-readiness-reason-codes.mjs` (lifecycle / readiness reason codes) and `scripts/ci/check-search-degradation-reason-codes.mjs` (search-degradation reason codes), which cross-check the Java enums (`LifecycleReasonCode.java`, `SearchReasonCode.java`) against their FE consumers.

**Case convention:** Java source uses `UPPER_CASE` IDs; FE/wire equivalents use `lower_snake_case` (`no_embedding_service` ↔ `NO_EMBEDDING_SERVICE`). The mapping is a trivial case-fold. The contract test allowlists in `GrpcSearchServiceReasonCodeContractTest` serve as the compile-time safety net.

**Category design:** `embedding` (5 codes) covers search execution failures from `GrpcSearchService`. `embedding_compat` (8 codes) covers lifecycle states from `EmbeddingCompatibilityController`.
