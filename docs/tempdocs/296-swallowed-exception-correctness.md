---
title: "Tempdoc 296 - Swallowed Exception Correctness Fixes"
---

# Tempdoc 296 - Swallowed Exception Correctness Fixes

**Status:** Complete
**Created:** 2026-03-14
**Updated:** 2026-03-14
**Goal:** Fix 7 critical swallowed-exception patterns surfaced by the logging audit (tempdoc 289 F12/A6). These are correctness bugs, not logging bugs — each requires per-item investigation of the component's behavioral contract.

## Context

Tempdoc 289's logging audit identified 430 catch blocks that log at warn/error without rethrowing. After triage, 7 patterns were flagged as critical — not because the logging is wrong, but because the *behavior* after the catch is wrong (silent data loss, permanent degradation, contract violations).

## Investigation Results (2026-03-14)

All 7 items investigated in depth. For each: current behavior documented, root cause identified, fix proposed with risk assessment.

### Item 1: Commit failure and `drainPendingMarkDone()` — INTENTIONAL, NO FIX NEEDED

**Investigation found the current behavior is correct by design.** `drainPendingMarkDone()` is deliberately inside the `try` block, so it only runs when `indexRuntime.commit()` succeeds. When commit fails, jobs remain in PROCESSING state. On the next Worker startup, `KnowledgeServer.recoverStuckJobs()` re-queues all PROCESSING jobs. Lucene writes are idempotent, so re-indexing is safe.

The `pendingMarkDone` list accumulates across failed commits but drains on the first successful commit — this is harmless. The design is documented in the Javadoc at the `drainPendingMarkDone` method.

**Status:** Drop — the original audit mischaracterized this as a bug.

### Item 2: Permanent degradation from transient init failure — FIX

**EmbeddingService:** `initialize()` catches `BackendException`, sets `available=false`, then `initialized.set(true)` in `finally`. The `initialized` flag is never reset, so the service is permanently unavailable for the session. No retry, no recovery path. User impact: vector search silently degrades to BM25-only for the entire Worker session.

**AiWorkerServer:** Init catches `TranslatorException` and installs a `StubLocalLlmTranslator`. The stub reports `ready()=true`, so health checks show healthy. The `TranslatorReloadWatcher` can hot-swap via a `reload.json` file, but this is not automatic. User impact: summaries are stub truncations, intent translation degrades to single-keyword clauses.

**Proposed fix:**
- **EmbeddingService**: Reset `initialized` to `false` in the catch block so a subsequent `initialize()` call retries. Add a retry-count guard (max 3 retries) to prevent infinite retry loops. Log the retry count.
- **AiWorkerServer**: After installing the stub, set a flag that the `TranslatorReloadWatcher` checks on each cycle — if the flag is set and the model file exists, attempt re-initialization automatically rather than waiting for external `reload.json`.

**Risk:** Medium. EmbeddingService retry could cause startup delays if the model file is genuinely missing (each retry attempts model load). Mitigate with a backoff (e.g., only retry if >30s since last attempt).

**Investigation update (2026-03-14):** Read the full `initialize()` method. The `AtomicBoolean initialized` is set in a `finally` block unconditionally. The `synchronized (initLock)` with double-checked locking prevents concurrent init. Two callers: `KnowledgeServer` startup (stores the permanently-degraded instance) and `IndexingLoop.reloadEmbeddingService()` (creates a fresh instance per GPU transition — not affected by the latch). The fix for EmbeddingService is clear: reset `initialized` to `false` in the catch block. Need to distinguish transient failures (ORT not ready, GPU busy) from permanent ones (model file missing) — the early-return for missing model file should keep `initialized=true`.

**AiWorkerServer correction:** `TranslatorReloadWatcher` is in a different process (Brain) and handles LLM prompt pipeline reload, NOT embedding model. The original proposal to hook auto-retry into the reload watcher was incorrect. The Brain-side stub translator degradation is a separate issue from the Worker-side embedding latch. Deferring AiWorkerServer to a future investigation.

### Item 3: Double-fallback to empty RAG context — PARTIAL FIX

**Investigation found the hallucination risk is lower than originally assessed.** The `RagStreamingHandler` has a final `isBlank()` guard that catches empty context and aborts with an error SSE event. The LLM never receives an empty context string.

The actual issues are:
1. **Silent quality degradation**: When chunk retrieval fails, the system falls back to full-doc fetch silently. The `rag_meta` SSE event reports `FULLTEXT_FALLBACK` mode, but there's no user-visible warning.
2. **Redundant failing gRPC call**: When both `retrieveContext` and `fetchDocuments` fail inside `RemoteDocumentService`, `RagStreamingHandler` calls `fetchBatchFallback()` which calls `fetchDocuments` again — a guaranteed-to-fail retry.

**Proposed fix:**
- When `ContextResult.retrievalMode` is `"FALLBACK_FAILED"`, skip the redundant `fetchBatchFallback()` call and go directly to the error event. This avoids one unnecessary network round-trip.
- No other change needed — the empty-context guard is working correctly.

**Risk:** Low. Only eliminates a redundant call that's already failing.

### Item 4: gRPC fire-and-forget with void return — FIX

All 6 `MigrationOps` methods swallow exceptions and return `void`. The callers (`IndexingController` HTTP handlers) always respond with 202 Accepted regardless of whether the Worker received or rejected the RPC. The `StatusRuntimeException` catch in the controller is dead code.

**Proposed fix:**
- Change all 6 methods from `void` to `boolean` (true = accepted, false = rejected/failed).
- Let `StatusRuntimeException` propagate for transport errors (remove the catch in `MigrationOps`).
- Update `IndexingController` handlers to return 503 on `false` and 500 on exception.
- Keep circuit breaker path as `log.debug` (transient, don't propagate).

**Risk:** Medium. This changes the HTTP API contract — 202 can now become 503/500. Existing UI code may not handle this. However, the current behavior (always 202 even on failure) is incorrect and misleading.

**Investigation update (2026-03-14):** Read all 3 layers. The interface is `IndexingService` in `app-api` with `default void` methods + a stub class. `RemoteKnowledgeClient` is a pure delegator. All 6 `MigrationOps` methods already inspect `resp.getAccepted()` and log — they just don't return it. The controller catch for `StatusRuntimeException` is dead code. Change is mechanical: void→boolean in `IndexingService` (interface + stub), `MigrationOps` (6 methods), `RemoteKnowledgeClient` (6 delegators), `IndexingController` (6 handlers).

### Item 5: gRPC contract violation in `submitBatch` — FIX

The catch `(RuntimeException e)` in `submitBatch` replies with `onNext(batchErrorResponse(e.getMessage()))` + `onCompleted()` instead of `onError(Status.INTERNAL)`. The client sees gRPC OK status. The circuit breaker records a success. `RootLifecycleOps.submitBatchFn` silently drops the error.

**Proposed fix:**
- Change to `responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asException())`.
- This makes `RootLifecycleOps.submitBatchSafe` see the exception and retry (up to 3 times), which is the correct behavior for a transient Worker failure.
- The circuit breaker still won't trip (INTERNAL != UNAVAILABLE), which is correct.
- `KnowledgeHttpApiAdapter.ingest()` will throw, resulting in HTTP 500 instead of 200 with `accepted=0` — which is the correct response for a server-side processing error.

**Risk:** Medium. The behavioral change (HTTP 200 → 500 for RuntimeException in Worker) is visible to API consumers. But the current behavior (silent success for a failure) is a bug, not a feature.

### Item 6: No disk-full circuit breaker in GPL coordinator — FIX

When disk is full, `GplJobCoordinator.collectFeaturesAndNegatives()` catches `IOException` on every triple write and continues. For a corpus of N documents × Q queries × 6 writes, this produces up to N×Q×6 `log.error` lines with no abort.

**Proposed fix:**
- Add an `AtomicInteger writeFailures` counter. Increment on each `IOException`. When count reaches 3, set an `abortDueToWriteFailure` flag.
- Check the flag at the top of `collectFeaturesAndNegatives()` — if set, return immediately without attempting writes.
- At the end of `runJob()`, if the flag is set, set job status to `FAILED` with `lastError = "Disk write failures exceeded threshold"`.

**Risk:** Low. The GPL job already has a `FAILED` status path. The change only adds an earlier exit condition. No data corruption risk — the triple store is append-only and `clear()` resets on the next job start.

### Item 7: Silent root de-indexing — FIX

When `Files.walk()` fails for a watched root, `walkAndSubmit` calls `markNeverIndexed(root)` which sets `lastIndexedAt = null`. The UI displays this identically to "never indexed yet" — the user has no way to know the walk failed. The walk is not retried automatically.

**Proposed fix:**
- Add a `WALK_FAILED` state to `WatchedRootsStore` (or a separate `walkError` field on the `WatchedRoot` record) that carries the failure reason.
- In `walkAndSubmit`, set `markWalkFailed(root, e.getMessage())` instead of `markNeverIndexed(root)`.
- Expose the error state via the existing `/api/status` or `/api/indexing/roots` endpoint so the UI can display a warning badge.
- On the next scheduled `reindexWatchedRoots()` cycle, retry roots in `WALK_FAILED` state rather than skipping them.

**Risk:** Medium-High. This introduces a new state to `WatchedRootsStore`, which affects serialization (JSON persistence), the API contract, and potentially the UI. The serialization change needs backward compatibility (old state files without the new field should parse correctly).

## Implementation Priority

| Item | Severity | Confidence | Recommendation |
|------|----------|------------|----------------|
| 1 | N/A | N/A | **Dropped** — intentional behavior |
| 5 | High | High | **Implement now** — gRPC contract violation, most clear-cut bug |
| 6 | Medium | High | **Implement now** — simple counter, no API change |
| 3 | Low | High | **Implement now** — single if guard, eliminates redundant failing call |
| 4 | Medium | High | **Implement now** — void→boolean across 4 files, mechanical |
| 2 | Medium | Medium | **Defer** — EmbeddingService latch fix is clear but needs transient-vs-permanent failure distinction. AiWorkerServer is a separate process issue, original proposal was incorrect. |
| 7 | Medium | High | **Done** — walkError field added, persisted, exposed via API. Backward compatible. |

## Items

- [x] 1. ~~Commit failure skips `drainPendingMarkDone()`~~ — **Dropped.** Investigation confirmed the behavior is intentional and correct by design. `recoverStuckJobs()` at startup handles the recovery path.
- [x] 2. **Permanent degradation from transient init failure** — EmbeddingService: on `BackendException`, do NOT set `initialized=true`. Allows retry on next `initialize()` call. After 3 consecutive failures, sets `initialized=true` to stop retrying. Permanent failures (model file missing) still latch on first attempt. AiWorkerServer stub degradation is a separate Brain-process issue (deferred).
- [x] 3. **Double-fallback to empty RAG context** — Added guard in `RagStreamingHandler.fetchRagContext()`: when `retrievalMode = "FALLBACK_FAILED"`, skip the redundant `fetchBatchFallback()` call and send error SSE event directly.
- [x] 4. **gRPC fire-and-forget with void return** — Changed 6 migration methods from `void` to `boolean` across 4 files: `IndexingService` (interface + stub), `MigrationOps` (return `resp.getAccepted()`), `RemoteKnowledgeClient` (delegators), `IndexingController` (return 409 on rejection instead of always 202).
- [x] 5. **gRPC contract violation** — Changed `submitBatch` RuntimeException handler from `onNext(batchErrorResponse)` + `onCompleted()` to `onError(Status.INTERNAL)`. Client now sees proper gRPC error status; `submitBatchSafe` retries correctly.
- [x] 6. **No disk-full circuit breaker** — Added `consecutiveWriteFailures` counter to `GplJobCoordinator`. Trips at 3 consecutive failures, skips all subsequent writes, and sets job status to FAILED. Counter resets at job start and on each successful write.
- [x] 7. **Silent root de-indexing** — Added `walkError` field to `WatchedRootsState` (in-memory + persisted). `walkAndSubmit()` catch blocks now call `markWalkFailed(root, error)` instead of `markNeverIndexed(root)`. Error is cleared on successful walk via `markIndexed()`. Exposed via `WatchedRoot.walkError` in `/api/indexing/roots`. Retry already works — failed roots have `NEVER_INDEXED` timestamp, which `reindexWatchedRoots()` always re-walks. Backward compatible: old JSON files without `walkError` parse correctly.
