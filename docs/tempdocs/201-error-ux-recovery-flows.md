---
title: "Error UX & Recovery Flows"
status: done
updated: 2026-02-18
---

# Error UX & Recovery Flows

## Problem

No systematic error boundary strategy exists. When things go wrong (Worker crash, inference failure, disk full, corrupt index), the user experience is undefined. Tempdoc 137 covers edge-case resilience from a backend perspective, but the user-facing error UX is unaddressed.

## Investigation Summary

Codebase investigation reveals the error UX is **much more developed than initially assumed**. A substantial infrastructure exists: React error boundaries, centralized status polling with backoff, localized error messages for 40+ error codes, circuit breakers, retry mechanisms, and a dedicated HealthView. The gaps are real but narrower than the original doc suggested.

---

## Current State by Layer

### Frontend Error Handling

**Error Boundary** (`ErrorBoundary.tsx`):
- Top-level `<ErrorBoundary>` wraps the entire app in `App.tsx:436`
- Class component with `getDerivedStateFromError` + `componentDidCatch`
- Renders user-friendly error dialog with expandable technical details and retry button
- Sub-components: `ConnectionError` (retry button), `LoadingState` (BrandMark animation)

**Error Localization** (`errorMessages.ts`):
- 40+ error codes mapped to user-friendly messages
- Covers search errors, AI runtime/install errors, worker errors, streaming errors
- `localizeError()` function provides structured `{message, localized}` output

**Retry Classification** (`aiErrors.ts`):
- `isRetryableError()` identifies transient errors: `AI_STARTING`, `TIMEOUT`, `LLM_OVERLOADED`, `FETCH_FAILED`, `INTERRUPTED`, `INDEX_UNAVAILABLE`
- InspectorAnswer shows retry button only for retryable errors

**API Layer** (`http.ts`):
- Centralized `ApiError` with code + HTTP status
- Retry with exponential backoff (max 3, 1s base delay)
- 4xx errors: no retry (client error)
- AbortError: no retry (intentional cancellation)
- Port auto-discovery with parallel probing and timeout

**Streaming** (`streams.ts`):
- SSE-based streaming with proper error event dispatch
- Cancellation detection (`CANCELLED` code)
- Demo error simulation via URL params for testing

**Logging** (`logger.ts`):
- Structured logging with levels (debug/info/warn/error) and context tags
- 100-entry buffer for debugging
- Pre-created loggers: `appLog`, `searchLog`, `apiLog`, `uiLog`, `indexLog`

**Not present:**
- ~~No `window.onerror` or `unhandledrejection` global handler~~ — Added in Phase 1 (Gap 3)
- No error tracking service (Sentry, etc.)
- No toast/notification system — errors are inline or full-page

### Connection & Status Polling

**Centralized Store** (`useSystemStore.ts`):
- Zustand store polling `/api/status` + `/api/inference/status` concurrently
- Default interval: 5s, with exponential backoff on failure (1s→2s→4s→...→30s max)
- `Promise.allSettled` for fault tolerance
- Connection states: `connected | connecting | disconnected`

**Connection Management** (`useApiConnection.ts`):
- Initial connection: resolve API endpoint, verify with `/api/status` (5s timeout)
- Reconnection: 5s interval, max 10 attempts, shows "Connection attempt N/10..."
- Health-triggered reconnection: when `consecutiveFailures >= 3`

**StatusDeck** (`StatusDeck.tsx`):
- Bottom bar showing: connection pill, result count, index stats, pending jobs, NER progress, AI mode
- 10s polling interval
- `deriveStatusDeckNextActionHint` for contextual hints (e.g. "Reindex recommended")

**HealthView** (`HealthView.tsx`):
- Dedicated health dashboard with auto-refresh (5s)
- Stats: indexed files, index size, memory (color-coded by pressure), queue status
- Connection details: endpoint, status, index state, uptime
- Queue DB health: backup status, integrity check
- Critical issue callout banner
- Event list (max 8) from `deriveHealthEvents`
- Actions: trigger reindex, restart worker (with error display)

### Backend Error Communication

**Status API** (`StatusLifecycleHandler.java`):
- `/api/status` (200 always): Full lifecycle snapshot — worker, inference, index, memory, jobs, embeddings, schema compat, chunk vectors, LLM engine status
- `/api/health` (200 or 503): Schema v1 with component states (Head/Worker/Inference) and reason codes
- `/api/debug/state`: Complete debug snapshot with PID, memory, config, worker details

**Lifecycle States**: `STARTING`, `READY`, `DEGRADED`, `ERROR`, `STOPPING`, `STOPPED`

**Component State Logic**:
- Head: Always `READY` (this process)
- Worker: `ERROR` if spawn failed, `DEGRADED` if not configured, `STARTING` if bootstrapping, `READY` if connected
- Inference: `READY` if available, `STARTING` if starting, `DEGRADED` if offline

**Key priority rule**: Worker errors → system `ERROR` (hard fail). Inference offline → system `DEGRADED` (search still works).

**Error Response Format** (`ApiErrorHandler.java`):
```json
{ "error": "Sanitized message", "errorCode": "MACHINE_READABLE_CODE" }
```
Error codes: `INDEX_ERROR`, `SERVICE_UNAVAILABLE`, `INVALID_REQUEST`, `TIMEOUT`, `IO_ERROR`, `NOT_FOUND`, `INTERNAL_ERROR`, etc.

**Index-Specific Reasons** (`IndexRuntimeIOException.java`):
`DISK_IO`, `DISK_FULL`, `LOCKED`, `SCHEMA_MISMATCH`, `CORRUPT_INDEX`, `CONFIGURATION`, `BACKPRESSURE`

### Backend Recovery Mechanisms

**Worker Auto-Restart** (`WorkerSpawner.java`):
- Health check every 1s via process monitor
- Max 3 restart attempts, exponential backoff (1s→2s→4s)
- Stability window resets counter after sustained uptime
- Heartbeat via MMF signal bus every 1s

**gRPC Circuit Breaker** (`GrpcCircuitBreaker.java`):
- CLOSED→OPEN after 3 consecutive UNAVAILABLE/DEADLINE_EXCEEDED failures
- 10s cooldown, then HALF_OPEN single-probe test
- Blocks requests while OPEN to prevent cascade

**gRPC Timeouts** (`KnowledgeServerConfig.java`):
- STANDARD: 5s (search, health)
- CONTENT_FETCH: 10s (document fetch, RAG context)
- INDEX_GC: 30s (garbage collection)
- LONG_RUNNING: 300s (sync/prune)
- Port discovery: 60s

**Inference Watchdog** (`EngineWatchdog.java`):
- Health check every 5s, silence timeout 30s
- Soft restarts: up to 3, then escalate to hard restart (up to 2)
- All attempts exhausted: transition to OFFLINE (graceful degradation)
- Stability reset after 2x timeout of healthy operation

**Job Queue Retry** (`SqliteJobQueue.java`):
- Per-file retry with exponential backoff (1s→2s→4s), max 3 attempts
- Stuck jobs (PROCESSING at startup) reset to PENDING
- Queue DB integrity checks + incremental vacuum

---

## Gap Analysis

### Gap 1: No Proactive Disk Space Warning
**Severity: High | Frequency: Medium**

- **Current**: Disk full detected reactively when Lucene write fails (`LuceneRuntimeUtils.java:157-160`). IOException message parsed for "no space" / "disk full".
- **What user sees**: Search/indexing errors with `DISK_FULL` reason code. `errorMessages.ts` maps `IO_ERROR` generically — no specific disk full message.
- **Missing**: No proactive disk space check. No warning at 90% or 95% capacity. User discovers the problem only when operations fail.
- **Recommended fix**: Periodic disk space check in Worker (e.g. during health monitoring). Surface in `/api/status` as a warning field. Add `DISK_SPACE_LOW` to `deriveHealthEvents`. Add specific `DISK_FULL` user message to `errorMessages.ts`.

### Gap 2: No User-Initiated Index Rebuild from UI
**Severity: Medium | Frequency: Low**

- **Current**: Index corruption is fail-closed (`LuceneIndexRuntime.java:414-428`). User told via log to "Stop the app and remove/rename the index directory." No auto-recovery.
- **What user sees**: If Worker fails to start due to corruption, HealthView shows `indexState: ERROR` or `UNAVAILABLE`. `knowledgeServerStartError` is surfaced. But the user has no action except manually deleting the index directory.
- **Missing**: A "Rebuild Index" button accessible from HealthView for corruption recovery. The reindex action exists but only handles re-indexing existing files — not recovering from a corrupted index that can't be opened.
- **Recommended fix**: Add a "Reset & Rebuild Index" action in HealthView that: (1) stops Worker, (2) deletes/renames the corrupted index directory, (3) restarts Worker, (4) triggers full re-index. This is a destructive operation — requires confirmation dialog.

### Gap 3: No Global Unhandled Error Capture
**Severity: Low | Frequency: Low (but blind spot)**

- **Current**: The React `<ErrorBoundary>` catches render errors. But there is no `window.onerror` or `unhandledrejection` handler. Errors in async code outside React's render cycle (timers, event handlers, promise chains) can fail silently.
- **What user sees**: Nothing — the error is invisible. Functionality may degrade without any indication.
- **Missing**: Global error handlers that log unhandled errors to the structured logger and optionally surface a notification.
- **Recommended fix**: Add `window.addEventListener('unhandledrejection', ...)` and `window.onerror` that pipe through `appLog.error()`. Consider a minimal toast/notification for critical unhandled errors.

### Gap 4: Worker Restart Failure Has Unclear UX
**Severity: Medium | Frequency: Low**

- **Current**: After 3 failed restart attempts, `WorkerSpawner` gives up. The status endpoint reports `knowledgeServerStartError` and `indexAvailable: false`.
- **What user sees**: HealthView shows error state with the start error message. StatusDeck shows "Disconnected". But no clear "Worker failed to start after 3 attempts — try restarting the application" message. The restart-worker button in HealthView may silently fail.
- **Missing**: Clear terminal-state messaging when auto-recovery is exhausted. The user needs to know the system has given up and what they should do next.
- **Recommended fix**: Surface a specific health event when restart attempts are exhausted. Message should include the action: "Restart JustSearch to try again" or "Check disk space and permissions."

### Gap 5: IPC Timeout UX is Generic
**Severity: Low | Frequency: Medium**

- **Current**: gRPC timeouts produce `DEADLINE_EXCEEDED` → circuit breaker may open → `SERVICE_UNAVAILABLE` error code.
- **What user sees**: Generic "Service unavailable" message from `errorMessages.ts`. No indication that it's a timeout vs. crash vs. overload.
- **Missing**: Differentiated messaging. A timeout on a search (5s) has different implications than a timeout on a long-running sync (300s). Circuit breaker opening is invisible to the user.
- **Recommended fix**: Map `TIMEOUT` error code to a timeout-specific message. Consider surfacing circuit breaker state in `/api/status` (already in inference status but not in worker status at the UI level).

### Gap 6: Startup Loading Has No Timeout/Failure Path
**Severity: Medium | Frequency: Low**

- **Current**: `LoadingState` shows "Connecting to local index..." with a BrandMark animation indefinitely while `connecting && !apiBase`.
- **What user sees**: If Backend never starts, the user sees the loading animation forever. After `useApiConnection` exhausts 10 attempts (50s), `ConnectionError` is shown with a retry button.
- **Actually acceptable**: The 10-attempt limit does eventually show `ConnectionError`. But 50 seconds of silent loading feels broken.
- **Recommended fix**: Show a progress indicator or attempt counter during loading. After ~15s, show a secondary message: "Taking longer than expected — checking backend..." After 10 failed attempts, the existing `ConnectionError` with retry is fine.

### Gap 7: Inference Degradation Communication is Subtle
**Severity: Low | Frequency: Medium**

- **Current**: When inference transitions to OFFLINE, `ModeIndicator` shows "AI idle" (gray brain icon). `BrainAlerts.tsx` shows red alert for runtime errors. StatusDeck shows AI mode indicator.
- **What user sees**: The mode indicator changes. Keyword search still works. But there's no proactive notification saying "AI features are temporarily unavailable because..."
- **Actually acceptable for MVP**: The mode indicator does communicate the state. Advanced users see HealthView. The main gap is that casual users might not notice the subtle indicator change.
- **Recommended fix (low priority)**: Consider a one-time dismissible banner when AI transitions from ONLINE to OFFLINE unexpectedly (not user-initiated). "AI features are temporarily unavailable. Search results may be less relevant."

### Gap 8: Error Code Architecture — Structural Problems
**Severity: Medium (systemic) | Frequency: Every error | Effort: Medium**

See dedicated section below.

---

## Critical Analysis: Error Code Architecture

The error code system has five structural problems. Individually none is catastrophic, but together they create a fragile, inconsistent contract between backend and frontend.

### Problem 1: Two Parallel Code Resolution Systems

There are two independent exception-to-code resolvers that use different logic for the same exception types:

**`ApiErrorHandler.resolveErrorCode()`** (used by REST endpoints):
```
IOException              → IO_ERROR
TimeoutException         → TIMEOUT
IndexRuntimeIOException  → INDEX_ERROR
IllegalArgumentException → INVALID_REQUEST
```

**`SummaryErrorUtils.resolveErrorCode()`** (used by streaming endpoints):
```
TimeoutException                      → TIMEOUT        (same)
DocumentService.UnavailableException  → INDEX_UNAVAILABLE
UnsupportedOperationException         → TRANSLATOR_UNAVAILABLE
message contains "status 400"         → CONTEXT_TOO_LARGE
message contains "status 503"         → LLM_OVERLOADED
(default)                             → SUMMARIZE_FAILED
```

The same `UnsupportedOperationException` produces `NOT_SUPPORTED` through ApiErrorHandler but `TRANSLATOR_UNAVAILABLE` through SummaryErrorUtils. An `IOException` thrown during a streaming summary call would hit `SummaryErrorUtils` and fall through to `SUMMARIZE_FAILED` — not `IO_ERROR`. The frontend user sees different messages for the same root cause depending on which endpoint was called.

This is not a bug — the streaming endpoints genuinely need domain-specific codes. But the two systems share no code, no common taxonomy, and no documentation of which system handles what. Adding a new exception type means updating two places with no compile-time reminder if you forget one.

### Problem 2: IndexRuntimeIOException.Reason Is Invisible to the Frontend

`IndexRuntimeIOException` carries a rich `Reason` enum with 7 values:
```
DISK_IO, DISK_FULL, LOCKED, SCHEMA_MISMATCH, CORRUPT_INDEX, CONFIGURATION, BACKPRESSURE
```

But `ApiErrorHandler.resolveErrorCode()` at line 64-65 flattens ALL of them to a single string:
```java
if (e instanceof IndexRuntimeIOException) {
    return "INDEX_ERROR";
}
```

The `Reason` is never inspected. A disk-full error, an index corruption error, and a lock contention error all produce the same `INDEX_ERROR` code. The frontend shows the same message — "Index error. The search index may be corrupted." — whether the disk is full or the index is locked. That message is actively misleading for 5 of the 7 reason values.

The `Reason` enum exists specifically to distinguish these cases, but the API boundary throws away the information before the frontend ever sees it.

### Problem 3: String-Matching Error Classification

`SummaryErrorUtils` classifies errors by parsing exception message text:
```java
if (msg.contains("status 400")) return "CONTEXT_TOO_LARGE";
if (msg.contains("status 503")) return "LLM_OVERLOADED";
```

And `ApiErrorHandler` does the same:
```java
if (lower.contains("unavailable") || lower.contains("not ready"))
    return "SERVICE_UNAVAILABLE";
if (lower.contains("not found"))
    return "NOT_FOUND";
```

This is brittle. An exception message like "The document was not found in the unavailable index" would match both `NOT_FOUND` and `SERVICE_UNAVAILABLE` — whichever `contains()` check runs first wins. The message text is an implementation detail that could change in any dependency without warning. If `llama-server` changes its error response from `503` to a JSON body with a status field, `CONTEXT_TOO_LARGE` and `LLM_OVERLOADED` detection silently stops working.

### Problem 4: 20+ Backend Codes Have No Frontend Localization

The backend emits at least 21 error codes that `errorMessages.ts` doesn't recognize. When any of these arrive, `localizeError()` falls through to the raw `error.message` from the server — which has been sanitized by `ApiErrorHandler.sanitizeMessage()` (paths replaced with `[path]`, class names replaced with `[internal]`). The user may see messages like: `"[internal]: [path] failed"`.

Codes emitted but not localized (grouped by severity of user impact):

**User-facing actions that will show raw messages:**
| Code | Source | When triggered |
|------|--------|----------------|
| `HIERARCHICAL_FAILED` | SummaryController:518 | Multi-section summarization fails |
| `NO_DOC_ID` | SummaryController:356, PreviewController:66 | Missing document parameter |
| `PACK_IMPORT_FAILED` | AiPackImportService:381 | AI model pack import fails |
| `PACK_PATH_INVALID` | AiPackImportService:465 + 4 more | Invalid path during pack import |
| `RUNTIME_ACTIVATION_FAILED` | RuntimeActivationService:415 | AI runtime can't activate |
| `NO_QUESTION` | RagStreamingHandler:94 | Q&A with empty question |

**Admin/policy actions:**
| Code | Source | When triggered |
|------|--------|----------------|
| `POLICY_DOWNLOADS_DISABLED` | AiInstallService:133,198 | Backend emits this, frontend expects `DOWNLOADS_DISABLED` |
| `POLICY_EXTERNAL_SERVER_DISALLOWED` | InferenceHandlers:219 | External LLM server blocked by policy |
| `POLICY_VALIDATE_FAILED` | PolicyController:79 | Policy validation error |
| `POLICY_EFFECTIVE_FAILED` | PolicyController:89 | Can't compute effective policy |
| `USER_POLICY_CREATE_FAILED` | PolicyController:140 | User policy creation fails |

**Internal/rare:**
| Code | Source |
|------|--------|
| `SETTINGS_UNAVAILABLE` | InferenceHandlers:256 |
| `INFERENCE_DETACH_FAILED` | InferenceHandlers:346 |
| `DIAGNOSTICS_EXPORT_FAILED` | DiagnosticsController:120 |
| `INVALID_JSON` | UiReadyController:60 |
| `INVALID_SCHEMA` | UiReadyController:71 |
| `INVALID_PATH` | IndexingController:83 |
| `BAD_REQUEST` | AgentController:60 |
| `RESUME_FAILED` | AgentController:123 |
| `VARIANT_ID_REQUIRED` | AiRuntimeController:43 |
| `PACK_NOT_ALLOWLISTED` | PackAllowlistService:100 |
| `UI_TOKEN_REQUIRED` | LocalApiServer:392 |

Note `POLICY_DOWNLOADS_DISABLED` vs `DOWNLOADS_DISABLED` — the backend and frontend use different code strings for the same concept. The backend emits `POLICY_DOWNLOADS_DISABLED`; the frontend localizes `DOWNLOADS_DISABLED`. These will never match. The user sees the raw sanitized message instead of the localized one.

### Problem 5: Duplicate Localization in useSummary.ts

`useSummary.ts:73-93` has a hand-rolled `if/else` chain that re-localizes error codes that `errorMessages.ts` already handles:

```typescript
if (code === "NO_CONTENT" || message.toLowerCase().includes("no content")) {
    setSummaryError("No content available to summarize");
} else if (code === "TRANSLATOR_UNAVAILABLE") {
    setSummaryError("Summarization temporarily unavailable");
} else if (code === "TIMEOUT") {
    setSummaryError("Summarization timed out; please retry");
}
```

These are hardcoded English strings that bypass the i18n system entirely. `errorMessages.ts` has localized versions for all four codes (`NO_CONTENT`, `TRANSLATOR_UNAVAILABLE`, `TIMEOUT`, `INDEX_UNAVAILABLE`). The `useSummary` hook ignores them and substitutes its own un-translatable messages. This means:
1. Translations for these codes will never display in the summary context
2. The messages differ from what the same codes produce elsewhere (e.g. `TIMEOUT` in search says "The request timed out. Please try again." but in summary says "Summarization timed out; please retry")
3. The `message.toLowerCase().includes("no content")` fallback is the same string-matching fragility as the backend

Additionally, `FETCH_FAILED` is in the `isRetryableError()` list but has no entry in `errorMessages.ts`. If the backend sends `FETCH_FAILED`, the retry button appears but the error message falls through to the raw server text.

### Problem 6: No Shared Error Code Registry

There is no single source of truth for the set of valid error codes. They are defined in at least 5 independent locations:

1. `errorMessages.ts` — 43 codes (frontend localization)
2. `ApiErrorHandler.resolveErrorCode()` — 9 codes (REST exception mapping)
3. `SummaryErrorUtils.resolveErrorCode()` — 6 codes (streaming exception mapping)
4. `IndexRuntimeIOException.Reason` — 7 reasons (not surfaced as codes)
5. `AgentErrorCode` — 13 codes (agent-specific enum)
6. ~30+ hardcoded string literals across controllers (manually set codes)

Nothing enforces that a code emitted by the backend is recognized by the frontend, or that a code the frontend localizes is actually emitted by any backend path. The `POLICY_DOWNLOADS_DISABLED` / `DOWNLOADS_DISABLED` mismatch is a direct consequence — no shared contract caught the drift.

---

## What's Already Good

These areas were flagged as concerns in the original doc but are already well-handled:

| Concern | Current State | Verdict |
|---------|---------------|---------|
| React error boundaries | Top-level `<ErrorBoundary>` with retry + expandable details | Adequate |
| Worker crash → UI state | StatusDeck shows "Disconnected", HealthView shows error details, auto-restart runs | Adequate |
| Inference failure → search | Keyword search continues, ModeIndicator shows "AI idle", BrainAlerts shows errors | Adequate |
| Actionable error messages | 43 error codes mapped to user-friendly messages with context | Good (with caveats — see error code analysis) |
| Connection retry | Exponential backoff, max 10 attempts, then ConnectionError with retry button | Good |
| IPC timeouts | Per-category gRPC deadlines (5s–300s), circuit breaker, auto-reconnect | Good |
| Startup failure | 50s loading → ConnectionError with retry button | Acceptable (minor polish needed) |

---

## Priority Matrix

| # | Gap | Severity | Frequency | Effort | Priority | Status |
|---|-----|----------|-----------|--------|----------|--------|
| 8 | Error code architecture (structural) | Medium | Every error | Medium | **P1** | **Done** (Wave 1 P1 + Wave 2 P2) |
| 1 | Proactive disk space warning | High | Medium | Medium | **P1** | **Done** (Wave 1 frontend). Proto fields deferred. |
| 4 | Worker restart exhaustion messaging | Medium | Low | Small | **P2** | **Done** (Wave 1 — restart button covers user action) |
| 6 | Startup loading progress feedback | Medium | Low | Small | **P2** | **Done** (Wave 1 Phase 1 + 2) |
| 2 | User-initiated index rebuild | Medium | Low | Large | **P3** | **Done** (Post-Wave 2 — "Rebuild index" button via `StartMigration` gRPC) |
| 5 | Differentiated timeout messaging | Low | Medium | Small | **P3** | **Done** (Wave 2 — StatusRuntimeException handling) |
| 3 | Global unhandled error capture | Low | Low | Small | **P4** | **Done** (Wave 1 Phase 1) |
| 7 | Inference degradation banner | Low | Medium | Small | **P4** | **Done** (Wave 2) |

### Recommended Actions for Gap 8 (Error Code Architecture)

**Phase 1 — Fix the active bugs (small effort):**
- Add the 20+ missing codes to `errorMessages.ts` with user-friendly messages
- Fix the `POLICY_DOWNLOADS_DISABLED` / `DOWNLOADS_DISABLED` mismatch (backend or frontend, pick one)
- Add `FETCH_FAILED` to `errorMessages.ts`
- Replace the hardcoded English strings in `useSummary.ts:73-93` with `localizeError()` calls

**Phase 2 — Surface IndexRuntimeIOException.Reason (medium effort):**
- Change `ApiErrorHandler.resolveErrorCode()` to inspect `IndexRuntimeIOException.reason()` and emit specific codes: `INDEX_DISK_FULL`, `INDEX_CORRUPT`, `INDEX_LOCKED`, etc.
- Add corresponding entries to `errorMessages.ts` with actionable messages (e.g. "Disk full — free space and retry" vs "Index may be corrupted — rebuild from Health view")

**Phase 3 — Shared registry (medium effort, prevents future drift):**
- Create a shared `ErrorCodes` constants class in a common API module
- Frontend `errorMessages.ts` imports or validates against this list at build time
- Consider generating `errorMessages.ts` code-to-key mapping from the Java constants (or at minimum a CI check that all emitted codes have frontend mappings)

---

## Long-Term Improvements (Theoretical)

The sections above cover near-term gaps. This section identifies structural improvements that would raise the ceiling on error UX quality. These are not bugs — they're architectural investments that compound over time.

### LT-1: Unify the Error Type System Around the Agent Model

The agent system (`AgentErrorCode` + `AgentErrorClass` + `RetryAction`) is the best-designed error model in the codebase. Every agent error carries:
- A machine-readable code (enum, not a string literal)
- A classification (`TRANSIENT`, `PERMANENT`, `BUDGET`, `POLICY`, `TOOL_CONTRACT`, `CANCELLED`)
- A retry action (`RETRY`, `ABORT`, `FALLBACK`)
- Telemetry recording (`agent.error.total` with code + class tags)
- A contract test (`AgentSseContractTest.java`)

The main API error system has none of this. It has string literals scattered across 30+ controllers, two parallel resolution systems, no classification, no structured retry semantics, and no contract tests for error payloads.

**Long-term direction**: Adopt the agent model for all API errors.

Concretely, this would mean:
1. A single `ApiErrorCode` enum (or sealed interface) replacing all hardcoded strings
2. Every code carries an `ErrorClass` — is this transient (retry), permanent (stop), or policy (escalate)?
3. The frontend's `isRetryableError()` becomes data-driven: check `errorClass === 'TRANSIENT'` instead of maintaining a hardcoded list of 6 code strings
4. Error responses include `errorClass` and `retryAction` fields. The frontend no longer needs to know which specific codes are retryable — the backend tells it.

This is the single highest-leverage improvement because it makes every other improvement cheaper. Adding a new error code becomes: add an enum value with its class, and everything downstream (localization, retry UI, telemetry) works automatically.

**Why it's hard today**: The agent system is self-contained (one loop, one SSE stream). The API layer touches 15+ controllers with different exception handling patterns. Migration would need to be incremental — new controllers use the enum, old controllers migrate one at a time.

### LT-2: Surface Background Failure Visibility

End-to-end error tracing reveals a class of errors that are completely invisible to the user: **asynchronous background failures**.

The worst case is file indexing. When a file fails to index (locked, corrupted, too large, extraction timeout), the Worker logs the error and marks the job as failed in SQLite. The Head process never learns about it. The frontend never sees it. The user's file silently doesn't appear in search results, with no indication of why.

Today's visibility: only via Worker logs (not exposed via API) or by noticing `failedJobs > 0` in `/api/status` (which shows a count but not which files or why).

**Long-term direction**: A "failed files" surface in the UI.

Components needed:
1. **Backend**: Expose failed job details via a new endpoint (e.g. `GET /api/knowledge/failed-jobs?limit=20`) returning file path, error reason, timestamp, retry eligibility
2. **Frontend**: A section in HealthView (or a dedicated panel) showing recently failed files with reasons: "File locked by another process", "File too large (>100MB)", "Extraction timed out", "Disk full during write"
3. **User actions**: "Retry now" button per file, "Retry all" for transient failures, "Dismiss" for permanent failures (e.g. encrypted files the user doesn't want to index)

This also requires the Worker to surface richer failure reasons through gRPC. Currently `SqliteJobQueue.markFailed()` stores a free-text reason string. These should become structured reason codes (analogous to `IndexRuntimeIOException.Reason`) so the frontend can localize them.

### LT-3: Error Telemetry for the API Layer

The agent system records `agent.error.total` with structured tags. The telemetry module has full OpenTelemetry infrastructure: NDJSON metric export, RRD time-series store, cardinality-controlled tag allowlists (which already include `error_code` in the allowlist at `NdjsonMetricExporter.java:46-72`).

But the API layer doesn't emit error counters. When a search fails or a summarization times out, no metric is recorded. There's no way to answer "how often do users hit INDEX_ERROR?" or "what's the error rate for the summarize endpoint?" without grepping logs.

**Long-term direction**: Add `api.error.total` counters to API error paths.

The infrastructure already exists — the `error_code` tag is already allowlisted in the metric exporter. What's missing is the instrumentation: a call to `telemetry.counter("api.error.total").increment(Tags.of("error_code", code, "endpoint", path))` in `ApiErrorHandler.toResponse()` and in SSE error event emission.

This would also enable:
- Error rate alerting (if/when an alerting layer is added)
- Error budgets per endpoint
- Regression detection: "error rate on `/api/knowledge/search` jumped 5x after this deploy"

The RRD store (`RrdMetricStore.java`) already curates high-value metrics for time-series trending. `api.error.total` would be a natural addition to the curated set.

### LT-4: Error Correlation Across Process Boundaries

The codebase has request ID propagation (`MdcContext.java`, `RequestIdClientInterceptor.java`) and OpenTelemetry tracing with W3C context propagation. But these are used for pipeline/LLM tracing, not for correlating user-facing errors back to root causes.

When a user sees "Search service not available", there's no way to connect that to the specific gRPC failure, circuit breaker state, or Worker crash that caused it. The error response contains a sanitized message and a code — but no correlation ID.

**Long-term direction**: Include a `requestId` or `traceId` in error responses.

The request ID already exists in MDC context. Including it in the error response payload (alongside `error` and `errorCode`) would let:
- Support workflows: "What's the request ID from your error?" → grep NDJSON traces
- Debug tooling: A future "Error Details" expandable in the UI could show the trace
- The `EventBuffer` (ring buffer of 50 recent events) could be keyed by request ID for quick lookup via `/api/debug/events`

This is cheap to implement — add `"requestId": MdcContext.currentRequestId()` to `ApiErrorHandler.toResponse()` — but the value compounds when combined with the trace export pipeline.

### LT-5: Error UX Testing Infrastructure

Chaos testing exists for process-level resilience (`ChaosSuiteTest`, `WindowsTortureTest`, `SoakSuiteTest`), but there are no tests that verify the **user experience** of errors. The existing tests check "does the Worker survive a crash?" — not "does the user see a meaningful error when the Worker crashes?"

Gaps identified:
- No E2E test verifies error rendering for any error code
- The `?demo_error=` URL parameter enables frontend error simulation but no Playwright test exercises it
- No test verifies that `errorMessages.ts` codes match what the backend actually emits
- No test verifies that `isRetryableError()` codes are a subset of what the backend emits
- The SSE parser has tests for malformed JSON but not for connection drops or partial events

**Long-term direction**: Three layers of error testing.

**Layer 1 — Contract tests (CI, fast)**:
- A build-time check that every error code string emitted by any Java controller exists in `errorMessages.ts`. This could be a Gradle task that parses Java files for `"errorCode", "..."` patterns and diffs against the frontend catalog. Catches the `POLICY_DOWNLOADS_DISABLED` / `DOWNLOADS_DISABLED` mismatch at build time.
- A unit test that every code in `isRetryableError()` exists in `errorMessages.ts`.

**~~Layer 2 — Demo mode E2E tests~~ — DROPPED.** The error display code is simple conditional JSX (`{aiError && <div>...</div>}`). The bidirectional contract test (Layer 1) already ensures every error code has a frontend message. E2E tests for error rendering would be high-maintenance relative to the trivial risk they mitigate. The `?demo_error=` infrastructure remains available if ever needed.

**~~Layer 3 — Chaos E2E tests~~ — DROPPED.** Platform-specific process killing is inherently flaky on Windows. The `ChaosSuiteTest` already covers chaos scenarios at the JUnit level (in-process). Browser-level chaos testing adds no meaningful coverage beyond what unit tests and manual testing provide.

### LT-6: Agent Error Localization

Agent errors are currently displayed as raw code names: `Error (LLM_TRANSIENT)`, `Error (BUDGET_EXHAUSTED)`. The 13 `AgentErrorCode` values have no entries in `errorMessages.ts` and no localization.

This is acceptable while the agent is developer-facing, but becomes a problem as it moves toward end-user use. `BUDGET_EXHAUSTED` means nothing to a non-technical user.

**Long-term direction**: Add agent codes to the localization catalog.

Suggested messages:
| Code | User message |
|------|-------------|
| `UNAVAILABLE` | "AI assistant is not available. Check that AI models are installed." |
| `NO_TOOLS` | "No tools available for the assistant." |
| `CANCELLED` | "Cancelled." |
| `UNKNOWN_TOOL` | "The assistant tried to use an unavailable tool." |
| `EMPTY_RESPONSE` | "The AI model returned an empty response. Try rephrasing." |
| `INTERNAL_ERROR` | "Something went wrong. Please try again." |
| `LLM_TRANSIENT` | "AI model temporarily unavailable. Please try again." |
| `TOOL_TRANSIENT_READ_ONLY` | "A search operation failed. Please try again." |
| `BUDGET_EXHAUSTED` | "Response limit reached. Start a new conversation." |
| `POLICY_DENIED` | "This action is blocked by administrator policy." |
| `TOOL_CONTRACT` | "The assistant encountered an internal error." |
| `TOOL_LOOP` | "The assistant got stuck in a loop and stopped." |
| `UNSUPPORTED_RESUME_STATE` | "Cannot resume this conversation. Start a new one." |

The `errorClass` field could also drive UI behavior: `TRANSIENT` → show retry, `CANCELLED` → show nothing, `BUDGET` → show "start new conversation" link.

### LT-7: Progressive Error Disclosure

Currently, errors are either full-page (ConnectionError, ErrorBoundary) or inline (search banner, AI error box). There's no middle ground and no way for users to get more detail when they need it.

**Long-term direction**: Three-tier progressive disclosure.

**Tier 1 — Inline summary** (what exists today): Short message in context. "Search failed: The search service is not available."

**Tier 2 — Expandable detail** (partially exists in ErrorBoundary): Click to see the error code, timestamp, and a one-sentence explanation of what to do. The ErrorBoundary already has an expandable "Technical Details" section — extend this pattern to inline errors.

**Tier 3 — Debug panel** (new): For power users and support. Link to HealthView with pre-filtered events. Include the request ID (from LT-4) so the user or support staff can find the exact trace. Could reuse the existing `/api/debug/events` endpoint filtered by request ID.

This doesn't require new infrastructure — `EventBuffer`, `HealthView`, and the expandable pattern in `ErrorBoundary` already exist. It's a UI design task to connect them.

---

## Long-Term Priority

| # | Improvement | Value | Effort | Dependencies | Status |
|---|-------------|-------|--------|--------------|--------|
| LT-1 | Unified error type system | Very high (foundational) | Large | None | **Phase 1 done** (tempdoc 212). Phase 2 (controller migration) remaining. |
| LT-5 | Error UX testing (Layer 1 contract tests) | High (prevents regression) | Small | None | **Done** (tempdoc 212 Phase 1 — `ApiErrorCodeContractTest`) |
| LT-3 | API error telemetry | High (observability) | Small | None | **Done** (Wave 2) |
| LT-2 | Background failure visibility | High (user-facing gap) | Medium | New gRPC endpoint | **Done** — `ListFailedJobs`/`ClearFailedJobs` gRPC RPCs, REST endpoints (`/api/indexing/failed-jobs`), HealthView "Failed Files" panel with lazy-load + confirm-to-clear. |
| LT-4 | Error correlation (request IDs in responses) | Medium (debugging) | Small | None | **Done** (Wave 2) |
| LT-6 | Agent error localization | Medium (growing area) | Small | None | **Done** (Wave 1) |
| LT-7 | Progressive error disclosure | Medium (UX polish) | Small | Parse 3 fields in `http.ts` | **Done** — `ApiError` extended with `requestId`/`errorClass`/`retryable`, `<details>` disclosure added to Stage.tsx (both surfaces) and InspectorAnswer.tsx |
| LT-5 | Error UX testing (Layer 2 demo E2E) | — | — | — | **Dropped** — simple conditional JSX not worth E2E maintenance cost. Contract test (Layer 1) sufficient. |
| LT-5 | Error UX testing (Layer 3 chaos E2E) | — | — | — | **Dropped** — flaky on Windows, covered by JUnit-level `ChaosSuiteTest`. |

**Done:** LT-1 Phase 1, LT-2, LT-3, LT-4, LT-5 Layer 1, LT-6, LT-7, Gap 2.
**Remaining:** None — all actionable items complete.
**Tracked elsewhere:** LT-1 Phase 2 (tempdoc 212), Gap 1 (tempdoc 199).

---

## Implementation Confidence Assessment (historical — all items resolved)

Pre-implementation assessment. All items have since been completed, dropped, or handed off. Preserved as reference for the reasoning behind implementation decisions.

### Near-Term Gaps

**Gap 1: Proactive Disk Space Warning — Confidence: HIGH**

Verified: Proto3 field addition is backward-compatible. `StatusResponse` already has `index_size_bytes` as a precedent. Worker already has disk space code in `NdjsonMetricExporter.hasSufficientDiskSpace()` using `FileStore.getUsableSpace()`. `WorkerStatusMapper` is a flat field-mapping function. `deriveHealthEvents` follows an established pattern.

The implementation path is clear: add 2 proto fields → compute in `IndexStatusOps` → map in `WorkerStatusMapper` → add warning threshold to `deriveHealthEvents` → add `DISK_SPACE_LOW` message to `errorMessages.ts`. Every link in this chain has been read and verified.

Risk: Threshold selection (what constitutes "low"?). The telemetry module uses 200MB critical / 1GB warning, but those are for telemetry files, not index data. The index could be 10GB+. Need disk-relative thresholds (e.g. <5% free) or absolute thresholds tuned to actual index write patterns.

**Gap 2: User-Initiated Index Rebuild — Confidence: LOW**

My original recommendation was naive. Verification revealed a structural problem:

1. The Head CANNOT delete/rename the index directory — the "Head never touches Lucene" invariant explicitly prohibits it, and `IndexRootLock` enforces exclusive Worker access.
2. If the index is corrupted, the Worker may not start at all — `LuceneIndexRuntime` crashes on `DirectoryReader.open()` during initialization.
3. So we need the Worker to handle the reset, but the Worker can't start when the index is corrupted.

This requires one of:
- **Option A**: A Worker "safe mode" that skips Lucene initialization and only performs cleanup, then restarts in normal mode. This is new Worker startup logic — non-trivial.
- **Option B**: The Head deletes the index directory *while the Worker is stopped* (explicitly violating the invariant with appropriate safety). But on Windows, stopped processes may still hold file handles, and the generation-based layout (`state.json`, `indices/<gen>/`) adds complexity.
- **Option C**: A separate utility/script outside the normal runtime. Simpler but not a UI-triggered action.

The tempdoc's "recommended fix" (stop Worker → delete index → restart Worker → trigger reindex) glosses over these problems. I would not implement this without deeper design discussion.

**Gap 3: Global Unhandled Error Capture — Confidence: VERY HIGH**

This is ~10 lines of code. Two event listeners in App.tsx or a dedicated init module. `appLog.error()` is already imported in multiple files. Zero architecture impact.

Only design question: surface a visible notification or just log silently? The codebase has no toast/notification system, so adding one for this alone would be over-engineering. Logging only is the pragmatic choice.

**Gap 4: Worker Restart Failure Messaging — Confidence: HIGH**

Verified: `WorkerSpawner` already tracks restart attempts and `IpcTelemetry` already has `ipc.worker.restart_limit_exceeded` counter. The gap is a status field and a health event rule.

Implementation: When `WorkerSpawner` exhausts restarts, set a boolean/string on the status object. In `deriveHealthEvents`, add a rule for this new field that produces an actionable error-level event.

Risk: Need to verify exactly how `WorkerSpawner` state is surfaced after exhaustion — does `knowledgeServerStartError` capture this already, or does it only capture the last specific error? If the latter, the user sees the symptom ("port not found") but not the meta-problem ("gave up after 3 attempts").

**Gap 5: IPC Timeout UX — Confidence: MEDIUM**

My tempdoc analysis contains an error I need to correct. `ApiErrorHandler.resolveErrorCode()` does map `TimeoutException` → `TIMEOUT` (line 76-78), so timeouts already get a specific code. The actual issue is narrower than I described:

1. gRPC `DEADLINE_EXCEEDED` produces a `StatusRuntimeException`, not a `TimeoutException`. `ApiErrorHandler` doesn't handle `StatusRuntimeException` specifically — it falls to the message-sniffing logic or `INTERNAL_ERROR`.
2. `CircuitBreakerOpenException` (when the breaker blocks a request) also falls through to `INTERNAL_ERROR`, producing a confusing generic message.

So the fix is more targeted: add `StatusRuntimeException` handling to `ApiErrorHandler` (check for `DEADLINE_EXCEEDED` status → emit `TIMEOUT`), and add `CircuitBreakerOpenException` handling → emit `SERVICE_UNAVAILABLE`. But I haven't verified the exact exception propagation path from `RemoteKnowledgeClient` through to the controller catch blocks — there may be wrapping.

**Gap 6: Startup Loading Progress — Confidence: VERY HIGH**

Verified: `useApiConnection` already tracks attempt count (line 92-98) and connection error string. `useStartupProgress` hook already exists with progress calculation but isn't wired into the UI. `LoadingState` accepts a status message prop.

Implementation: Pass attempt count to `LoadingState`, show "Connection attempt 3/10..." after the first failure. Optionally wire `useStartupProgress` for a progress bar. Entirely frontend, no backend changes.

**Gap 7: Inference Degradation Banner — Confidence: HIGH**

Verified: `useInferenceMode` tracks the current mode. `BrainAlerts.tsx` already shows runtime error alerts with the right styling. `ModeIndicator.tsx` already detects transitions.

Implementation: Store previous mode in a ref, detect ONLINE→OFFLINE transition, show a dismissible banner. Pattern exists.

Risk: Need a "was this user-initiated?" signal to avoid showing the banner when the user deliberately deactivates AI. The mode switch API presumably has a way to distinguish — but I haven't verified this. If the inference status doesn't distinguish user-initiated vs. crash-triggered transitions, the banner would fire on both, which is annoying.

**Gap 8 Phase 1 — Fix Error Code Mismatches: Confidence: VERY HIGH**

Verified down to the line:
- **1a**: `POLICY_DOWNLOADS_DISABLED` → `DOWNLOADS_DISABLED`: 2 string literals in `AiInstallService.java:133,198`. Simple rename.
- **1b**: 20+ missing codes to `errorMessages.ts`: Bulk addition. Every missing code has been cataloged with its source.
- **1c**: Add `FETCH_FAILED` to `errorMessages.ts`: 1 line.
- **1d**: Replace hardcoded strings in `useSummary.ts:73-93`: Feasible, but has a **design decision** — the hardcoded messages are more context-specific than the generic localized versions. For example, `TIMEOUT` says "Summarization timed out; please retry" (context-specific) vs. "The request timed out. Please try again." (generic). Replacing blindly loses specificity. The right approach may be to add summary-specific locale keys (e.g. `TIMEOUT_SUMMARY`) or to accept the generic messages.

**Gap 8 Phase 2 — Surface IndexRuntimeIOException.Reason: Confidence: HIGH**

Verified: Only `ApiErrorHandler.resolveErrorCode()` needs to change. No other catch blocks handle `IndexRuntimeIOException` directly — it's always caught as `Exception` and delegated. The change is a single `instanceof` check that inspects `.reason()` instead of returning flat `INDEX_ERROR`.

Risk: Naming. Do we use `INDEX_DISK_FULL` or `DISK_FULL`? The former is explicit but verbose. The latter collides with the general concept. Need to decide a naming convention.

**Gap 8 Phase 3 — Shared Registry: Confidence: MEDIUM**

The Java side is straightforward — an enum in `modules/app-api` (verified: this module is a dependency of both `modules/ui` and `modules/app-agent`).

The frontend side is where confidence drops. "CI check that all emitted codes have frontend mappings" has two approaches:
- **Source parsing**: Regex Java files for `"errorCode", "..."` patterns. Fragile — misses computed codes, catches false positives in comments/tests.
- **Runtime approach**: A test that instantiates every controller, triggers every error path, and collects emitted codes. Comprehensive but complex — essentially requires a test harness for every error scenario.

A pragmatic middle ground: a manually maintained `ErrorCodes.java` enum that developers are expected to update. A CI test checks that every value in the enum has a corresponding `errorMessages.ts` entry. This doesn't catch "someone added a string literal without adding to the enum," but it does catch drift between the enum and the frontend.

### Long-Term Improvements

**LT-1: Unified Error Type System — Confidence: LOW**

The tempdoc correctly identifies this as "foundational" but understates the difficulty. The agent error model works because it was designed clean. Retrofitting it onto the API layer means:
- 17+ controllers with catch blocks that currently use `ApiErrorHandler.toResponse(e)` — each would need migration to emit typed codes
- 30+ manually-set code strings scattered across controllers — each needs an enum value
- `SummaryErrorUtils` is a parallel system with different semantics — merging it means reconciling two classification approaches
- Streaming endpoints emit SSE error events with a different shape than REST errors — unifying means changing the wire format or maintaining two serializations

The migration cannot be atomic. It would need to be incremental (new controllers use enums, old controllers migrate one at a time) with a compatibility layer. I estimate this is 3-5 sessions of focused work across 20+ files, with risk of regressing error behavior at each step.

I would not start this without first shipping Phase 1-2 of Gap 8 (which are prerequisites anyway) and then carefully scoping a migration plan that preserves exact current behavior at each step.

**LT-2: Background Failure Visibility — DONE (was HIGH confidence)**

Full call chain traced from SQLite → gRPC → Head → frontend. Every layer had established
patterns to follow:
- *Worker:* `GrpcIngestService extends IngestServiceGrpc.IngestServiceImplBase` — add `listFailedJobs()` override. SQL query: `SELECT path, error_message, attempts, last_updated, collection FROM jobs WHERE state='FAILED' ORDER BY last_updated DESC LIMIT ?`.
- *Head:* `RemoteKnowledgeClient.executeIngestRpc()` handles circuit breaker, deadline, reconnect. Add ops helper (like `MigrationOps`) or inline. `IndexingController` maps gRPC→REST.
- *Frontend:* `listFolders`/`listFolderFiles` pattern — unary call, no pagination, `limit` param.
- *Proto compilation:* Automatic via `protobuf-gradle-plugin` in `modules/ipc-common`.

Risks:
- `cleanupOldJobs()` is dead code (zero callers) — need to wire it to a timer and extend to FAILED rows.
- FAILED jobs permanently set Worker state to "ERROR" in `/api/status` — this is a UX issue that should be addressed (either dismiss mechanism or separate "has failures" indicator).

**LT-3: API Error Telemetry — Confidence: VERY HIGH**

Verified: `error_code` tag already in the metric exporter allowlist. `ApiErrorHandler` is the central point. Need: (1) inject `Telemetry` into the handler (currently a static utility class — would need to become injectable or accept telemetry as a parameter), (2) one `counter.increment()` call.

Correction to the tempdoc: `ApiErrorHandler` is currently a static utility class with static methods. You can't inject a Telemetry instance without changing the class design. Either pass `Telemetry` as a parameter to `toResponse()` (changes every call site) or convert to an injectable singleton. The former is noisy but safe; the latter is cleaner but a bigger refactor. I underestimated this by calling it "5 lines of code."

**LT-4: Error Correlation — Confidence: MEDIUM-HIGH**

Verified: `MdcContext.request(traceId, requestId)` exists. Adding `requestId` to the error response map is trivial.

Unknown: Is MDC context always populated when API errors occur? The MDC context is set by request interceptors — but if the error happens before the interceptor runs (e.g. during request parsing) or outside a request context (e.g. a background health check), `MdcContext.currentRequestId()` returns null. The error response would have `requestId: null`, which is fine but worth knowing.

**LT-5: Error UX Testing — Layer 1 DONE, Layers 2-3 DROPPED**
- Layer 1 (contract tests): **Done** via `ApiErrorCodeContractTest` (tempdoc 212). Bidirectional — every enum has a frontend entry and vice versa.
- Layer 2 (demo E2E): **Dropped.** Error display is simple conditional JSX. Contract test coverage is sufficient. E2E maintenance cost outweighs the trivial regression risk.
- Layer 3 (chaos E2E): **Dropped.** Platform-specific process killing is flaky on Windows. JUnit-level `ChaosSuiteTest` already covers chaos scenarios.

**LT-6: Agent Error Localization — Confidence: VERY HIGH**

13 entries in `errorMessages.ts`. Wire `AgentView.tsx` to call `localizeError()` instead of displaying raw `entry.errorCode`. The error object shape matches what `localizeError` expects.

One consideration: `AgentErrorCode` includes `INTERNAL_ERROR` which is also in the main error catalog. The messages are different ("Something went wrong. Please try again." vs. "An internal error occurred. Please try again."). Not a collision since `localizeError` does a flat string lookup, but worth noting for consistency.

**LT-7: Progressive Error Disclosure — Confidence: MEDIUM**

The components exist but the UX design doesn't. Where does Tier 2 go in the search error banner? How does Tier 3 link to HealthView? These are design questions I can't answer from code alone. I could implement any specific design, but proposing the right design requires understanding user workflows and visual hierarchy — which is outside what a code investigation can determine.

### Corrections to the Tempdoc

The investigation surfaced three things the tempdoc gets wrong or oversimplifies:

1. **Gap 2 recommendation is broken.** "Stop Worker → delete index → restart Worker" violates the "Head never touches Lucene" invariant and doesn't handle the case where the Worker can't start at all due to corruption. Needs a different design (Worker safe mode or similar).

2. **Gap 5 analysis conflates two issues.** `TimeoutException` → `TIMEOUT` already works. The real issue is `StatusRuntimeException(DEADLINE_EXCEEDED)` and `CircuitBreakerOpenException` falling through to `INTERNAL_ERROR`. More targeted than described.

3. **LT-3 is not "5 lines."** `ApiErrorHandler` is a static utility class. Injecting telemetry requires either changing every call site or restructuring the class.

---

## Implementation Roadmap

### Wave 1 — Foundation: COMPLETE

Shipped in branch `error-ux-recovery` (3 phases, merged to main). See Implementation Phase 1-3 sections below for details.

**Planned items — all done:**
- Gap 8 P1: Error code mismatches (28 codes added, POLICY_DOWNLOADS_DISABLED fixed, useSummary localized, FETCH_FAILED added)
- LT-6: Agent error localization (12 agent codes, AgentView wired to localizeError)
- Gap 3: Global unhandled error capture (window.onerror + unhandledrejection in main.jsx)
- Gap 6: Startup loading feedback (attempt counter + 15s slow hint)

**Bonus items shipped beyond plan:**
- Disk pressure frontend warnings (Gap 1 partial — `diskPressure` field surfaced from existing `TelemetryHealthState` through `/api/status` → StatusDeck hints)
- Worker restart button (Gap 4 partial — StatusDeck shows "Search service is down — Restart" with action button)
- Startup diagnostic panel (Gap 6 extension — ConnectionError shows per-component health states)
- Per-zone error boundaries (new — 5 zones wrapped in ErrorBoundary with ZoneFallback)

**Design decisions resolved:**
- 1d: Accepted generic localized messages (lost context-specific wording — acceptable tradeoff)
- 2: `INTERNAL_ERROR` shared between agent and main catalog (same key, main message wins — no collision in practice)
- 3: Log-only, no toast (pragmatic — no notification system exists)

### Wave 2 — Error Specificity & Observability: COMPLETE

Shipped directly on `main`. All items touch `ApiErrorHandler.java` — batched into a single refactor pass.

**Backend (ApiErrorHandler.java refactored):**

| # | Item | Status |
|---|------|--------|
| 5 | Gap 8 P2: Surface `IndexRuntimeIOException.Reason` | DONE — switch on `.reason()` emits `INDEX_DISK_FULL`, `INDEX_CORRUPT`, `INDEX_LOCKED`, `INDEX_SCHEMA_MISMATCH`, `INDEX_BACKPRESSURE` |
| 7 | Gap 5: IPC timeout UX | DONE — `StatusRuntimeException` mapped by gRPC status code (`DEADLINE_EXCEEDED`→`TIMEOUT`, `UNAVAILABLE`→`SERVICE_UNAVAILABLE`, etc.), `CircuitBreakerOpenException`→`SERVICE_UNAVAILABLE` |
| 8 | LT-3: API error telemetry | DONE — `recordError(Telemetry, errorCode, endpoint)` static method, wired in KnowledgeSearchController (search + suggest) and PreviewController |
| 9 | LT-4: Error correlation | DONE — `buildResponse()` reads `MDC.get("request_id")` and includes `requestId` in JSON when present |

**Frontend:**

| # | Item | Status |
|---|------|--------|
| 5f | New error messages for index reasons | DONE — 5 new codes in `errorMessages.ts` with German translations |
| 10 | Gap 7: Inference degradation banner | DONE — `deriveStatusDeckNextActionHint` detects `inferenceMode=offline` + `inferenceError` (crash vs user-initiated). Warn-level hint: "AI features unavailable — keyword search still works" |

**Design decisions:**
- Kept `ApiErrorHandler` as static utility (not injectable class). `recordError()` accepts `Telemetry` as parameter — zero call-site changes for existing consumers, new telemetry is opt-in at wiring sites.
- `requestId` from MDC (thread-local) — no constructor change needed, just `MDC.get()` in `buildResponse()`.
- Inference degradation uses `inferenceError` field as heuristic (set on fetch failure, cleared on success). No backend signal needed.

**Dropped from Wave 2:**
- Gap 4 exhaustion messaging — the restart button (shipped in Wave 1 bonus) covers the user action.

### Handoff to Other Tempdocs

| Item | Destination | What's left |
|------|-------------|-------------|
| LT-1 Phase 2: Controller migration | **tempdoc 212** | Migrate ~17 controllers from inline strings to `ApiErrorCode` enum. Also covers: LT-3 telemetry wiring at remaining controllers, SSE wire format, data-driven retry, `SummaryErrorUtils` migration. |
| Gap 1: Disk space proto fields | Low priority, no tempdoc | Worker-side `disk_usable_bytes`/`disk_total_bytes` proto fields. Frontend warnings already work via `TelemetryHealthState`. Droppable. |
| Gap 1: i18n sweep for raw error sites | **tempdoc 199** | LibraryView, PolicyHelperPanel still show unlocalized error messages. (Tempdoc 199 marks these as "skipped" with justifications — may be droppable.) |

### Residual Findings (post-completion audit, 2026-02-18)

Systematic audit after all items shipped. Four genuinely untracked items found:

| # | Item | Severity | Action |
|---|------|----------|--------|
| 1 | **`cleanupOldJobs()` dead code** — method exists in `SqliteJobQueue` but has zero callers. DONE and FAILED rows accumulate indefinitely in SQLite. Need to wire to periodic timer and extend WHERE clause to include FAILED rows. | Medium (resource leak) | **Fix now** — wire to `IndexingLoop` tick or Worker startup. |
| 2 | **LT-3 telemetry incomplete** — `api.error.total` only wired at 3 endpoints (search, suggest, preview). ~14 other controllers don't emit error metrics. | Low | Fold into tempdoc 212 Wave 3 controller migration. |
| 3 | **`ai-bridge` typed exceptions** — `SummaryErrorUtils` and `ApiErrorHandler` still message-sniff (`msg.contains("status 400")`). | Low | Note in tempdoc 212 as post-P2b improvement. |
| 4 | **FAILED jobs drive Worker ERROR state** — `failedCount > 0` in `IndexStatusOps` sets `state = "ERROR"` permanently until jobs cleared. | Low | Revisit when failed-files UX is iterated on. |

12 additional items audited and **dropped**: Gap 1 proto fields, Gap 3 toast system, Gap 4 exhaustion messaging, Gap 5 circuit breaker in UI, Gap 6 progress bar, Gap 7 dismissible banner, LT-5 `isRetryableError()` test (moot after tempdoc 212 P2c), LT-6 namespace collision, LT-7 Tier 3 debug panel, `INTERNAL_ERROR` collision, RRD curated set, `useStartupProgress` hook. All either resolved by what shipped, made moot by tempdoc 212 work, or too low-value to track.

### Implementation Notes

- **All items from this tempdoc are done or explicitly dropped.**
- Gap 2 (index rebuild), LT-2 (failed files), LT-7 (progressive disclosure) — all shipped.
- LT-5 Layers 2-3 — dropped (not worth the cost).
- Residual #1 (`cleanupOldJobs`) fixed in post-audit pass (2026-02-18).

### Investigation Notes (historical, all items resolved)

Pre-implementation investigation was conducted on 2026-02-18 for all deferred items.
Key findings that influenced implementation:

- **LT-2:** Full call chain traced (SQLite → gRPC → Head → frontend). `cleanupOldJobs()` is dead code (zero callers) — DONE and FAILED rows accumulate indefinitely. FAILED jobs permanently drive Worker state to ERROR in `IndexStatusOps`. Shipped as `ListFailedJobs`/`ClearFailedJobs` RPCs + HealthView panel.
- **Gap 2:** Original "stop Worker → delete index" design was wrong (violates Head-never-touches-Lucene). Resolution: `StartMigration` gRPC already provides blue/green rebuild. `indexAutoRecovery` handles corrupt-index-at-startup. Shipped as "Rebuild index" button in HealthView.
- **LT-7:** `ErrorBoundary.tsx` already had `<details>`/`<summary>` pattern. Extended to Stage.tsx and InspectorAnswer.tsx with `requestId`/`errorClass`/`retryable` parsed from `ApiError`.
- **LT-5 Layers 2-3:** Dropped — error display is trivial conditional JSX; contract test (Layer 1) sufficient; chaos E2E flaky on Windows.
- **Gap 1 proto fields:** Low priority. `TelemetryHealthState` thresholds already surfaced via `/api/status`.

---

## Key Files Reference

| Area | File | Notes |
|------|------|-------|
| Error boundary | `modules/ui-web/src/components/ErrorBoundary.tsx` | Top-level + ConnectionError + LoadingState |
| Error messages | `modules/ui-web/src/utils/errorMessages.ts` | 40+ error code mappings |
| Retry classification | `modules/ui-web/src/utils/aiErrors.ts` | `isRetryableError()` |
| API layer | `modules/ui-web/src/api/http.ts` | Centralized fetch with retry |
| Streaming | `modules/ui-web/src/api/streams.ts` | SSE error handling |
| System store | `modules/ui-web/src/stores/useSystemStore.ts` | Polling + backoff + connection state |
| Connection hook | `modules/ui-web/src/hooks/useApiConnection.ts` | Reconnection logic |
| StatusDeck | `modules/ui-web/src/components/zones/StatusDeck.tsx` | Bottom status bar |
| HealthView | `modules/ui-web/src/components/views/HealthView.tsx` | Health dashboard |
| Health events | `modules/ui-web/src/components/views/health/deriveHealthEvents.ts` | Event derivation |
| Mode indicator | `modules/ui-web/src/components/ui/ModeIndicator.tsx` | AI mode display |
| Brain alerts | `modules/ui-web/src/components/views/brain/BrainAlerts.tsx` | Runtime/policy alerts |
| Search errors | `modules/ui-web/src/components/zones/Stage.tsx:326-382` | Error display + retry |
| AI answer errors | `modules/ui-web/src/components/HUD/InspectorAnswer.tsx:114-142` | Contextual error + retry |
| Status API | `modules/ui/src/main/java/io/justsearch/ui/api/StatusLifecycleHandler.java` | `/api/status` + `/api/health` |
| Error format | `modules/ui/src/main/java/io/justsearch/ui/api/ApiErrorHandler.java` | Standardized error response |
| Worker spawner | `modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerSpawner.java` | Crash detection + restart |
| Circuit breaker | `modules/app-services/src/main/java/io/justsearch/app/services/worker/GrpcCircuitBreaker.java` | Failure isolation |
| gRPC timeouts | `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerConfig.java` | Deadline categories |
| Inference lifecycle | `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceLifecycleManager.java` | Mode transitions + failure |
| Engine watchdog | `modules/ai-bridge/src/main/java/io/justsearch/aibridge/llama/EngineWatchdog.java` | Inference health + restart |
| Index IO errors | `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/IndexRuntimeIOException.java` | Disk full, corruption reasons |
| Disk full classify | `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/LuceneRuntimeUtils.java:142-165` | Reactive detection |
| Job retry | `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/SqliteJobQueue.java:368-434` | Exponential backoff |
| Agent error codes | `modules/app-agent-api/src/main/java/io/justsearch/agent/api/AgentErrorCode.java` | 13 agent-specific codes |
| Agent error class | `modules/app-agent-api/src/main/java/io/justsearch/agent/api/AgentErrorClass.java` | TRANSIENT/PERMANENT/BUDGET/POLICY/TOOL_CONTRACT/CANCELLED |
| Agent telemetry | `modules/app-agent/src/main/java/io/justsearch/agent/AgentTelemetry.java` | Error + retry counters with structured tags |
| Agent retry policy | `modules/app-agent/src/main/java/io/justsearch/agent/AgentRetryPolicy.java` | Per-code retry decisions |
| Agent loop | `modules/app-agent/src/main/java/io/justsearch/agent/AgentLoopService.java` | All 9 error emission points |
| Agent SSE contract | `modules/ui/src/test/java/io/justsearch/ui/api/AgentSseContractTest.java` | Error payload stability test |
| Summary error utils | `modules/ui/src/main/java/io/justsearch/ui/api/SummaryErrorUtils.java` | Parallel error code resolution for streaming |
| SSE writer | `modules/ui/src/main/java/io/justsearch/ui/api/SseWriter.java` | SSE event serialization |
| Telemetry core | `modules/telemetry/src/main/java/io/justsearch/telemetry/LocalTelemetry.java` | OpenTelemetry setup, metric/span export |
| Metric exporter | `modules/telemetry/src/main/java/io/justsearch/telemetry/NdjsonMetricExporter.java` | Tag allowlist includes `error_code` |
| Health state | `modules/telemetry/src/main/java/io/justsearch/telemetry/TelemetryHealthState.java` | Export failure counters |
| Crash reporter | `modules/telemetry/src/main/java/io/justsearch/telemetry/CrashReporter.java` | Structured crash JSON, no-dep safe |
| RRD store | `modules/telemetry/src/main/java/io/justsearch/telemetry/RrdMetricStore.java` | Curated time-series (5min/1hr/1d archives) |
| MDC context | `modules/app-observability/src/main/java/io/justsearch/app/observability/MdcContext.java` | Request/trace ID propagation |
| IPC telemetry | `modules/app-services/src/main/java/io/justsearch/app/services/worker/IpcTelemetry.java` | Worker restart/circuit breaker counters |
| Event buffer | `modules/ui/src/main/java/io/justsearch/ui/api/EventBuffer.java` | Ring buffer, 50 recent events |
| Chaos tests | `modules/system-tests/src/systemTest/java/io/justsearch/systemtests/ChaosSuiteTest.java` | MMF fuzzing, crash recovery, disconnector |
| Windows torture | `modules/system-tests/src/systemTest/java/io/justsearch/systemtests/torture/WindowsTortureTest.java` | File lock contention simulation |
| E2E edge cases | `modules/ui-web/e2e/edge-cases.spec.ts` | Error recovery, console monitoring |
| Demo error sim | `modules/ui-web/src/api/streams.ts:240-299` | `?demo_error=` URL param injection |

---

## Implementation — Phase 1 (2026-02-17, branch `error-ux-recovery`)

Four items implemented. All additive/safe — no behavioral regressions in existing tests.

### Done: Actionable errors — error code mismatches (Gap 8 Phase 1)

**Backend fix:** Renamed `POLICY_DOWNLOADS_DISABLED` → `DOWNLOADS_DISABLED` in
`AiInstallService.java` (lines 133, 198) to match the frontend error catalog.

**Error catalog expansion:** Added 28 error codes to `errorMessages.ts`:
- 12 install-flow codes: `TERMS_REQUIRED`, `INSTALL_ALREADY_RUNNING`, `INSTALL_IO_ERROR`,
  `MANIFEST_INVALID`, `MANIFEST_NOT_CONFIGURED`, `POLICY_MODEL_NOT_ALLOWLISTED`,
  `RUNTIME_MISSING`, `DOWNLOAD_FAILED`, `VERIFY_FAILED`, `INSTALL_MOVE_FAILED`,
  `APPLY_FAILED`, `SMOKE_TEST_FAILED`
- 11 agent codes: `UNAVAILABLE`, `NO_TOOLS`, `CANCELLED`, `UNKNOWN_TOOL`, `EMPTY_RESPONSE`,
  `LLM_TRANSIENT`, `TOOL_TRANSIENT_READ_ONLY`, `BUDGET_EXHAUSTED`, `POLICY_DENIED`,
  `TOOL_CONTRACT`, `UNSUPPORTED_RESUME_STATE`
- 1 network code: `FETCH_FAILED` (was in `isRetryableError()` with no localized message)

**useSummary.ts localized:** Replaced hardcoded English if/else chain with `getErrorMessage()`
calls. Loses some context-specific wording (e.g., INDEX_UNAVAILABLE no longer says "update the
index path in Settings and restart") — accepted tradeoff per design decision to use generic
catalog messages.

Total error catalog: ~63 codes (up from ~35 after Phase 2b-full).

### Done: Agent error localization (LT-6)

All 12 `AgentErrorCode` enum values now have localized messages. `INTERNAL_ERROR` is shared with
the main catalog (intentional).

`AgentView.tsx` error bubbles now use `localizeError()` for the message body. The raw error code
is still shown in the header label (`Error (BUDGET_EXHAUSTED)`) for diagnostic value.

**Known tradeoff:** `localizeError()` replaces `entry.content` entirely when the code is known.
If the backend sends richer context (e.g., "Budget exhausted after 15 iterations"), that detail
is lost in favor of the generic message. The raw code in the header partially mitigates this.

**Namespace risk:** Agent codes (`UNAVAILABLE`, `CANCELLED`, etc.) are generic names in a flat
catalog. If a non-agent subsystem ever emits a bare `UNAVAILABLE` code, it would display the
agent-specific message. No immediate collision exists, but a prefix convention
(`AGENT_UNAVAILABLE`) would be safer long-term.

### Done: Global unhandled error capture (Gap 3)

Added `window.addEventListener('unhandledrejection')` and `window.onerror` handlers in
`main.jsx`. Both pipe through `appLog.error()` from the structured logger.

Log-only — no toast, no notification, no UI disruption. The codebase has no notification system
and adding one for this would be over-engineering.

Browser's default `console.error` still fires (no `event.preventDefault()`), so errors appear
in both the structured log buffer and DevTools console. Intentional — preserves default
debugging experience.

### Done: Startup loading feedback (Gap 6)

`useApiConnection.ts` now exposes `reconnectAttempt` (1-based count, 0 when not reconnecting)
and exports `MAX_RECONNECT_ATTEMPTS` as a named constant.

`LoadingState` component accepts `attempt` and `maxAttempts` props:
- After the first failure: shows "Connection attempt N/10..." below the spinner
- After 15 seconds: shows "Taking longer than expected. Make sure the backend is running."

The 15s threshold is a `SLOW_CONNECT_THRESHOLD_MS` constant. Timer starts on component mount
(= when the user first sees the loading screen), not on first failure.

### Files Changed (9)

| File | Changes |
|------|---------|
| `AiInstallService.java` | Renamed error code (2 sites) |
| `errorMessages.ts` | +28 error codes |
| `useSummary.ts` | Replaced hardcoded English with `getErrorMessage()` |
| `AgentView.tsx` | Agent errors use `localizeError()` |
| `main.jsx` | Global error handlers |
| `ErrorBoundary.tsx` | LoadingState: attempt count + slow hint |
| `useApiConnection.ts` | Expose `reconnectAttempt`, export constant |
| `hooks/index.ts` | Re-export `MAX_RECONNECT_ATTEMPTS` |
| `App.tsx` | Pass attempt props to LoadingState |

### German Translations: COMPLETE

31 new strings translated (28 error codes + 3 UI strings). `lingui extract` → 688/688,
0 missing. Compiled catalogs updated via `lingui compile --typescript`.

---

## Implementation — Phase 2 (2026-02-17, branch `error-ux-recovery`)

Three items implemented. All additive — no behavioral regressions.

### Done: Disk full frontend warnings (Gap 4)

**Backend:** Added `diskPressure` field to `/api/status` response. `StatusLifecycleHandler`
accepts a `Supplier<String>` that reads `TelemetryHealthState.getDiskPressureLevel().name()`.
Wired in `LocalApiServer` using the existing `LocalTelemetry` pattern.

**Frontend:** `SystemStatus` type extended with `diskPressure?: 'OK' | 'WARNING' | 'CRITICAL'`.
Extracted in `useSystemStore`, exposed via `useStatus`. `deriveStatusDeckNextActionHint()` now
checks disk pressure before all other hints:
- CRITICAL → warn-level hint: "Disk critically low — indexing may fail"
- WARNING → warn-level hint: "Disk space is running low"

### Done: Worker crash guided recovery (Gap 2)

**Hint system extended with actions:** `HeadlineHint` type now supports optional `actionLabel`
and `actionId` fields. When `workerState` is ERROR or STOPPED, the hint shows "Search service
is down" with a "Restart" button.

**StatusDeck renders the action:** Clicking "Restart" calls `restartWorker()` from
`api/domains/inference.ts` (POST /api/worker/restart). Loading spinner shown during the
async call.

**Priority order:** disk CRITICAL > disk WARNING > worker down > reindex recommended.

### Done: Startup diagnostic info (Gap 7)

**New utility:** `utils/healthDiagnostic.ts` — `fetchHealthDiagnostic(baseUrl)` makes a single
3s-timeout fetch to `/api/health` and returns per-component states (head/worker/inference).
Returns null if backend is completely unreachable.

**Connection flow:** After max reconnect attempts are exhausted, `useApiConnection` attempts
one diagnostic fetch. The result is passed through as `diagnosticInfo`.

**ConnectionError enhanced:** When `diagnosticInfo` is available, a "Component Status" panel
shows colored dots (green=READY, red=ERROR, yellow=other) for Head, Worker, and Inference
with reason codes. Users can immediately see which subprocess failed.

### Files Changed (Phase 2)

| File | Changes |
|------|---------|
| `StatusLifecycleHandler.java` | Added diskPressure supplier + output in buildStatusMap() |
| `LocalApiServer.java` | Wired disk pressure supplier from LocalTelemetry |
| `systemTypes.ts` | Added diskPressure field to SystemStatus |
| `useSystemStore.ts` | Extract diskPressure from /api/status response |
| `useStatus.ts` | Expose diskPressure in status mapping |
| `deriveHeadlineStatus.ts` | Disk + worker checks, HeadlineHint action fields |
| `StatusDeck.tsx` | Pass disk/worker to hints, render action button |
| `healthDiagnostic.ts` | New: fetchHealthDiagnostic utility |
| `useApiConnection.ts` | Diagnostic fetch on max-retry failure |
| `ErrorBoundary.tsx` | ConnectionError diagnostic panel (DiagnosticRow) |
| `App.tsx` | Pass diagnosticInfo to ConnectionError |

### German Translations: COMPLETE (Phase 2)

8 new strings translated. `lingui extract` → 696/696, 0 missing. Compiled via
`lingui compile --typescript`.

---

## Implementation — Phase 3 (2026-02-17, branch `error-ux-recovery`)

One item implemented. Additive — no behavioral regressions.

### Done: Per-zone error boundaries (Gap 1)

**`ZoneFallback` component:** Added to `ErrorBoundary.tsx`. Compact inline placeholder with
AlertTriangle icon, "Something went wrong in this panel." text, and a "Reload" button
(`window.location.reload()`). Styled to fill the grid cell.

**Zone wrapping:** All 5 zones in `App.tsx` wrapped in `<ErrorBoundary fallback={<ZoneFallback />}>`:
GlobalCommand, ActivityRail, Stage, InspectionPane, StatusDeck. The top-level `<ErrorBoundary>`
remains as a final catch-all.

**Effect:** A crash in one zone (e.g., InspectionPane) is isolated — the other zones keep working.
Previously, any zone crash took down the entire app to a full-page error screen.

### Test coverage added (Phase 2 + 3)

`deriveHeadlineStatus.test.ts` rewritten with 11 test cases covering:
- Disconnected state (null — no duplicate hint)
- Reindex recommended (info level)
- Demo mode suppression
- Disk CRITICAL/WARNING (warn level, correct text)
- Disk CRITICAL overrides reindexRequired
- Worker ERROR/STOPPED (warn level, restart action)
- Priority: disk CRITICAL > worker ERROR, disk WARNING > worker ERROR, worker ERROR > reindex

Requires `i18n.activate('en')` in `beforeAll` — `t` macro needs an active locale at runtime.

### Files Changed (Phase 3)

| File | Changes |
|------|---------|
| `ErrorBoundary.tsx` | Added `ZoneFallback` component |
| `App.tsx` | Wrapped 5 zone props in `<ErrorBoundary>` |
| `deriveHeadlineStatus.test.ts` | Rewritten: 11 test cases with i18n setup |

### German Translations: COMPLETE (Phase 3)

2 new strings translated. `lingui extract` → 698/698, 0 missing. Compiled via
`lingui compile --typescript`.

---

## Implementation — Wave 2 (2026-02-17, direct on main)

Six items implemented in a single pass. All additive — no behavioral regressions.

### Done: ApiErrorHandler refactor (Gap 8 P2 + Gap 5 + LT-3 + LT-4)

**IndexRuntimeIOException.Reason surfaced:** `resolveErrorCode()` now inspects `.reason()` via
switch expression: `DISK_FULL`→`INDEX_DISK_FULL`, `CORRUPT_INDEX`→`INDEX_CORRUPT`,
`LOCKED`→`INDEX_LOCKED`, `SCHEMA_MISMATCH`→`INDEX_SCHEMA_MISMATCH`,
`BACKPRESSURE`→`INDEX_BACKPRESSURE`. Previously all flattened to `INDEX_ERROR`.

**StatusRuntimeException handling:** gRPC exceptions mapped by status code:
`DEADLINE_EXCEEDED`→`TIMEOUT`, `UNAVAILABLE`→`SERVICE_UNAVAILABLE`, `NOT_FOUND`→`NOT_FOUND`,
`INVALID_ARGUMENT`→`INVALID_REQUEST`, `RESOURCE_EXHAUSTED`→`SERVICE_UNAVAILABLE`.
Also handles `CircuitBreakerOpenException`→`SERVICE_UNAVAILABLE`.

**Error telemetry:** New `recordError(Telemetry, errorCode, endpoint)` method. Emits
`api.error.total` counter with `error_code` + `endpoint` tags. Wired in
KnowledgeSearchController (search + suggest catch blocks) and PreviewController.

**Error correlation:** `buildResponse()` reads `MDC.get("request_id")` and includes `requestId`
in the JSON response when present. No constructor or call-site changes needed.

### Done: Frontend error messages (Gap 8 P2 frontend)

5 new codes in `errorMessages.ts`: `INDEX_DISK_FULL`, `INDEX_CORRUPT`, `INDEX_LOCKED`,
`INDEX_SCHEMA_MISMATCH`, `INDEX_BACKPRESSURE`.

### Done: Inference degradation hint (Gap 7)

`deriveStatusDeckNextActionHint()` extended with `inferenceError` input. When inference is
offline with an error (crash), shows warn-level hint: "AI features unavailable — keyword search
still works". User-initiated offline (no error) returns null (ModeIndicator handles it).

Priority: disk CRITICAL > disk WARNING > worker down > reindex > inference degradation.

3 new test cases in `deriveHeadlineStatus.test.ts`. Total: 14 tests, all passing.

### Files Changed (Wave 2)

| File | Changes |
|------|---------|
| `ApiErrorHandler.java` | IndexRuntimeIOException.Reason switch, StatusRuntimeException mapping, CircuitBreakerOpenException, buildResponse with requestId, recordError method |
| `KnowledgeSearchController.java` | recordError calls in search + suggest catch blocks |
| `PreviewController.java` | recordError call in handlePreviewFailure |
| `errorMessages.ts` | 5 new INDEX_* error codes |
| `deriveHeadlineStatus.ts` | inferenceError input + offline crash detection |
| `deriveHeadlineStatus.test.ts` | 3 new tests (inference degradation) |
| `StatusDeck.tsx` | Pass inferenceError to hint derivation |

### German Translations: COMPLETE (Wave 2)

6 new strings translated. `lingui extract` → 707 total, 3 missing (pre-existing Desktop
settings strings, not from this work). Compiled via `lingui compile`.

---

## Summary

Total files changed across all waves: ~45 (including LT-2 full-stack: proto, 6 Java modules, 4 frontend files).
Total German translations: 61 strings (31 Wave 1 P1 + 8 Wave 1 P2 + 2 Wave 1 P3 + 6 Wave 2 + 5 LT-7/Gap 2 + 9 LT-2).
Frontend tests: 16 files, 204 tests — all pass. All affected Java modules compile clean.

## Final Gap Status

| Gap | Status | Notes |
|-----|--------|-------|
| Error boundaries | **Done** (Wave 1) | Global `ErrorBoundary` + `window.onerror`/`unhandledrejection`. Per-zone boundaries wrap all 5 zones with `ZoneFallback`. |
| Worker crash recovery | **Done** (Wave 1) | StatusDeck shows "Search service is down — Restart" with inline action button. |
| Inference failure | **Done** (Wave 2) | StatusDeck hint: "AI features unavailable — keyword search still works" when inference crashes. User-initiated offline shows nothing (correct). |
| Disk full | **Done** (Wave 1) | StatusDeck shows disk pressure warnings (WARNING/CRITICAL). Backend emits `INDEX_DISK_FULL` error code (Wave 2). |
| Corrupt index | **Done** (Wave 2 + LT-7/Gap 2) | Backend emits `INDEX_CORRUPT` error code. HealthView has reindex button + "Rebuild index" (blue/green migration). Frontend shows localized "index is corrupted" message with `<details>` disclosure. |
| Network timeout (IPC) | **Done** (Wave 2) | `StatusRuntimeException(DEADLINE_EXCEEDED)` → `TIMEOUT`, `CircuitBreakerOpenException` → `SERVICE_UNAVAILABLE`. Both localized in frontend. |
| Startup failures | **Done** (Wave 1) | LoadingState shows attempt count + slow hint. ConnectionError shows per-component diagnostic panel. |
| Actionable errors | **Done** (Wave 1 + 2) | Error catalog at 82 codes (`ApiErrorCode` enum, tempdoc 212). `IndexRuntimeIOException.Reason` surfaced. `requestId`/`errorClass`/`retryable` in error responses. Progressive disclosure via `<details>` in Stage.tsx and InspectorAnswer.tsx. Telemetry wired. Remaining raw-message display sites belong in i18n sweep (tempdoc 199). |

### Future Work (handed off)

All remaining work is tracked in other tempdocs. See "Handoff to Other Tempdocs" table above.

### Completed in this tempdoc

| Item | Wave/Phase | Summary |
|------|-----------|---------|
| Gap 1-8 (priority matrix) | Wave 1 + 2 | All 8 original gaps closed |
| LT-1 Phase 1 | Tempdoc 212 | `ApiErrorCode` enum (82 codes), `ErrorClass`, contract tests |
| LT-3 | Wave 2 | Error codes in metrics via `MetricsHelper` |
| LT-4 | Wave 2 | `requestId` in all error responses via MDC |
| LT-5 Layer 1 | Tempdoc 212 | `ApiErrorCodeContractTest` (bidirectional) |
| LT-6 | Wave 1 | 13 agent error codes localized |
| LT-7 | Post-Wave 2 | `ApiError` extended, `<details>` disclosure in Stage.tsx + InspectorAnswer.tsx |
| Gap 2 | Post-Wave 2 | "Rebuild index" button in HealthView (triggers `StartMigration` gRPC) |
| LT-2 | Post-Wave 2 | `ListFailedJobs`/`ClearFailedJobs` gRPC RPCs, REST endpoints, HealthView "Failed Files" panel |
| LT-5 Layers 2-3 | Dropped | Error display is simple conditional JSX; contract test (L1) sufficient. Chaos E2E flaky on Windows; JUnit `ChaosSuiteTest` covers it. |
