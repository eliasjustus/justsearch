---
title: "Unified Error Type System"
status: done
created: 2026-02-17
origin: tempdoc 201 (LT-1)
---

# Unified Error Type System

## Problem

The API error system uses string literals scattered across 30+ controllers, two parallel resolution systems (`ApiErrorHandler` and `SummaryErrorUtils`), no classification, no structured retry semantics, and no contract tests. The agent subsystem has a well-designed model (`AgentErrorCode` + `AgentErrorClass` + `RetryAction`) that solves all of these — but it's self-contained and not used by the rest of the API layer.

This is the single highest-leverage error infrastructure improvement: adding a new error code should be "add an enum value with its class, and everything downstream (localization, retry UI, telemetry) works automatically."

## Current State

### What the agent model does right

The agent system (`AgentErrorCode` + `AgentErrorClass` + `RetryAction`) gives every error:
- A machine-readable code (enum, not a string literal)
- A classification (`TRANSIENT`, `PERMANENT`, `BUDGET`, `POLICY`, `TOOL_CONTRACT`, `CANCELLED`)
- A retry action (`RETRY`, `ABORT`, `FALLBACK`)
- Telemetry recording (`agent.error.total` with code + class tags)
- A contract test (`AgentSseContractTest.java`)

### What the API layer lacks

- **Two parallel code resolvers** with no shared taxonomy: `ApiErrorHandler.resolveErrorCode()` (REST) and `SummaryErrorUtils.resolveErrorCode()` (streaming). Same exception → different codes depending on endpoint.
- **String-matching classification**: `ApiErrorHandler` sniffs exception messages for substrings like "unavailable" or "not found". Brittle — message text is an implementation detail.
- **~30 hardcoded string literals** across controllers (manually set `"errorCode"` values).
- **No shared registry**: Error codes defined in 5+ independent locations with no compile-time contract.
- **Frontend maintains a parallel retryable-code list**: `isRetryableError()` hardcodes 6 code strings. Backend doesn't tell the frontend whether an error is retryable.

### What Wave 1-2 of tempdoc 201 already improved

- `ApiErrorHandler.resolveErrorCode()` now inspects `IndexRuntimeIOException.Reason` (5 specific codes instead of flat `INDEX_ERROR`)
- `StatusRuntimeException` mapped by gRPC status code (no more message-sniffing for these)
- `CircuitBreakerOpenException` handled specifically
- `requestId` included in error responses
- `recordError()` telemetry wired at key endpoints
- Error catalog at ~68 frontend codes with German translations

These improvements reduce the urgency but don't address the structural problem: adding a new error still means touching string literals in Java + adding to `errorMessages.ts` + maybe updating `isRetryableError()` + hoping nothing drifts.

## Target State

1. A single `ApiErrorCode` enum (or sealed interface) in `modules/app-api` replacing all hardcoded strings
2. Every code carries an `ErrorClass` — transient (retry), permanent (stop), or policy (escalate)
3. Error responses include `errorClass` and `retryAction` fields
4. Frontend `isRetryableError()` becomes data-driven: check `errorClass === 'TRANSIENT'` instead of a hardcoded list
5. A CI contract test verifies every enum value has a frontend `errorMessages.ts` entry

## Structural Challenges

### Challenge 1: Two parallel resolution systems

`ApiErrorHandler` (REST) and `SummaryErrorUtils` (streaming/SSE) use different logic for the same exception types. Unifying means either:
- **Option A**: Merge into one resolver, with a "context" parameter for domain-specific codes (e.g., `TIMEOUT` vs `SUMMARIZE_TIMEOUT`)
- **Option B**: Keep two resolvers but both emit from the same enum. The enum carries the domain classification.

### Challenge 2: Streaming wire format differs from REST

REST errors: `{ "error": "...", "errorCode": "..." }`
SSE errors: `event: error\ndata: { "code": "...", "message": "..." }`

Unifying the enum doesn't require unifying the wire format — but the enum needs to cover codes from both systems.

### Challenge 3: Incremental migration

17+ controllers with catch blocks using `ApiErrorHandler.toResponse(e)`. Migration cannot be atomic. Needs a compatibility layer where:
- Old controllers continue using string-based `toResponse(Exception)` (unchanged)
- New/migrated controllers use `toResponse(ApiErrorCode, Exception)` (typed)
- Both produce the same wire format

### Challenge 4: ~10 manually-set code strings in controllers

Investigation found ~10 hardcoded code strings bypassing `ApiErrorHandler`:

| Controller | Codes |
|------------|-------|
| `SummaryController.java` | `AI_STARTING`, `AI_OFFLINE`, `AI_UNAVAILABLE` |
| `RagStreamingHandler.java` | `AI_STARTING`, `AI_OFFLINE` |
| `InferenceHandlers.java` | `INFERENCE_RELOAD_FAILED`, `INFERENCE_DETACH_FAILED` |
| `AgentController.java` | `BAD_REQUEST`, `RESUME_FAILED` |
| `SummaryErrorUtils.java` | `CONTEXT_TOO_LARGE`, `LLM_OVERLOADED`, `SUMMARIZE_FAILED` |

Plus ~13 codes derived from `ApiErrorHandler`'s exception-mapping logic. Fewer than the "30+" originally estimated — many controllers route through `ApiErrorHandler` cleanly. The enum would need ~25-30 values total (not 70+).

### Challenge 5: SummaryErrorUtils message-sniffing

```java
if (msg.contains("status 400")) return "CONTEXT_TOO_LARGE";
if (msg.contains("status 503")) return "LLM_OVERLOADED";
```
These classify by parsing exception message text from `llama-server` HTTP responses. The enum migration needs to either:
- Replace message-sniffing with typed exceptions thrown upstream (preferred but requires changes in `ai-bridge` module)
- Keep message-sniffing as a transitional adapter inside the enum's resolver

## Key Files

| File | Role |
|------|------|
| `modules/app-api/.../ErrorClass.java` | 4-value classification enum (`TRANSIENT`, `PERMANENT`, `POLICY`, `VALIDATION`) |
| `modules/app-api/.../ApiErrorCode.java` | 92-code unified registry with `ErrorClass` |
| `modules/ui/.../ApiErrorHandler.java` | REST error resolution — returns `ApiErrorCode` via `resolve()` |
| `modules/ui/.../SummaryErrorUtils.java` | Streaming error resolution — returns `ApiErrorCode` via `resolveErrorCode()`; `buildSseErrorPayload()` helper |
| `modules/ui/test/.../ApiErrorCodeContractTest.java` | Bidirectional contract test (enum ↔ frontend) |
| `modules/ui-web/src/utils/errorMessages.ts` | Frontend error catalog (synced with enum) |
| `modules/ui-web/src/utils/aiErrors.ts` | `isRetryableError()` — data-driven via `errorClass` with legacy fallback |
| `modules/app-agent-api/.../AgentErrorCode.java` | Agent-domain reference model (13 codes with class + retry) |

## Estimated Scope

- 3-5 focused sessions across 20+ files
- Risk of regressing error behavior at each migration step
- Each controller migration is independently testable

## Prerequisites (all met)

- Wave 1-2 of tempdoc 201 (shipped): `ApiErrorHandler` now has structured exception handling, telemetry, and requestId. The resolver is in a clean state for enum migration.

## Phase 1: Foundation (SHIPPED)

### What shipped

| File | Change |
|------|--------|
| `modules/app-api/.../ErrorClass.java` | New enum: `TRANSIENT`, `PERMANENT`, `POLICY`, `VALIDATION` |
| `modules/app-api/.../ApiErrorCode.java` | New enum: 82 codes, each carrying `ErrorClass` |
| `modules/ui/.../ApiErrorHandler.java` | `resolve(Exception)` returns `ApiErrorCode`; REST responses now include `errorClass` and `retryable` fields |
| `modules/ui/test/.../ApiErrorCodeContractTest.java` | Bidirectional contract: every enum value has a frontend entry, every frontend key (non-agent) exists in enum |
| `modules/ui-web/src/utils/errorMessages.ts` | Added 24 missing codes to reach full parity with `ApiErrorCode` (now 106 entries total) |

### Design decisions made

- **ErrorClass**: New 4-value enum in `app-api`. `AgentErrorClass` (6 values) stays in `app-agent-api` unchanged — the agent domain has finer-grained classifications (`BUDGET`, `TOOL_CONTRACT`, `CANCELLED`) that don't belong in the API foundation.
- **Enum granularity**: Flat enum with 91 values (after P2a Wave 2). One flat enum is simpler than a hierarchy and 91 values is well within manageable range.
- **Wire format**: Additive — `errorClass` and `retryable` added to REST responses alongside existing fields. No breaking change.
- **Message sniffing**: Kept as transitional adapter inside `ApiErrorHandler.resolve()`. Typed exceptions in `ai-bridge` deferred.
- **Two resolvers**: `ApiErrorHandler` and `SummaryErrorUtils` remain separate. Both backed by enum in Phase 2.

### Wire format (REST)

```json
{
  "error": "The request timed out",
  "errorCode": "TIMEOUT",
  "errorClass": "TRANSIENT",
  "retryable": true,
  "requestId": "abc-123"
}
```

### Backward compatibility

- `resolveErrorCode(Exception)` still returns `String` (delegates to `resolve().name()`)
- `toResponse(Exception)` signature unchanged — now includes `errorClass`/`retryable` in output
- New typed overloads: `toResponse(ApiErrorCode, Exception)`, `toResponse(ApiErrorCode, String)`
- `recordError(Telemetry, ApiErrorCode, String)` overload added alongside string-based version

### Side effect: all REST error responses now include new fields

Because the existing `toResponse(Exception)` now delegates through `resolve()` → `buildTypedResponse()`, **every controller** that calls `ApiErrorHandler.toResponse(e)` emits `errorClass` and `retryable` fields in its JSON response. This is additive (no fields removed, no values changed) and the frontend ignores unknown fields. External consumers would see new fields.

### Post-review fixes (same session)

After critical analysis of the initial implementation, the following fixes were applied:

| Fix | Detail |
|-----|--------|
| **Dynamic contract exclusion** | `AGENT_DOMAIN_CODES` in `ApiErrorCodeContractTest` now reads `AgentErrorCode.values()` dynamically instead of a hardcoded `Set.of(...)`. Adding a new `AgentErrorCode` value no longer risks a confusing contract test failure. |
| **`INTERRUPTED` reclassified** | Changed from `TRANSIENT` to `PERMANENT`. User-initiated cancellation should not prompt retry. |
| **Section reorganization** | `BAD_REQUEST` and `RESUME_FAILED` moved from Agent/Validation sections to Worker/infrastructure in `errorMessages.ts`. These are `ApiErrorCode` values used by `AgentController` for REST errors, not `AgentErrorCode` values. |
| **Distinct `BAD_REQUEST` message** | Changed from "Invalid request." (near-identical to `INVALID_REQUEST`) to "The request could not be processed." |
| **i18n catalog regeneration** | Ran `lingui extract` + `lingui compile`. 737 total messages, 28 new codes pending German translation (fall back to English). |

### Known design tensions

- **`retryable` (boolean) vs `retryAction` (enum)**: REST uses `retryable: true/false`; agent SSE uses `retryAction: "RETRY"/"ABORT"`. Phase 2 SSE work needs to reconcile.
- **`INTERNAL_ERROR` name collision**: Exists in both `ApiErrorCode` and `AgentErrorCode`. Same classification (PERMANENT) in both, so no behavioral difference, but the frontend can't distinguish source from the code alone.

## Phase 2: Remaining Work

### P2a: Controller migration (incremental)

Migrate ~17 controllers from inline string literals to `ApiErrorCode` enum values. Each is an independent PR.

**Wave 1 (SHIPPED)** — 5 simplest controllers:

| Controller | Changes |
|------------|---------|
| `PreviewController` | 5 inline codes migrated (`NO_DOC_ID`, `NOT_FOUND`, `TIMEOUT`, `INDEX_UNAVAILABLE`×2); `handlePreviewFailure` upgraded from string switch → enum switch; `INDEX_UNAVAILABLE` added to HTTP status mapping |
| `KnowledgeSearchController` | `CURSOR_INVALID` migrated; `handleSearch`/`handleSuggest` catch blocks upgraded to typed `resolve()` + `toResponse(code, e)` + `recordError(telemetry, code, ...)`; 5 "not ready" paths upgraded to typed `SERVICE_UNAVAILABLE` with `state` field; remaining legacy `toResponse(e)` calls in `handleStatus`/`handleIngest`/`handleListFolders`/`handleListFolderFiles` upgraded to typed |
| `UiReadyController` | 2 codes: `INVALID_JSON`, `INVALID_SCHEMA` |
| `DiagnosticsController` | 1 code: `DIAGNOSTICS_EXPORT_FAILED` |
| `SettingsController` | Path validation uses `INVALID_PATH` (not `INDEX_UNAVAILABLE`); generic catch uses `INVALID_REQUEST` (not `INVALID_JSON`) |

New enum value added: `INVALID_SCHEMA(ErrorClass.VALIDATION)` — needed by `UiReadyController` schema validation. Frontend entry added to `errorMessages.ts`.

Post-review fixes applied to Wave 1:
- `SettingsController`: `INDEX_UNAVAILABLE` → `INVALID_PATH` (validation error, not transient)
- `SettingsController`: `INVALID_JSON` → `INVALID_REQUEST` (broad catch covers non-JSON failures)
- `KnowledgeSearchController`: Upgraded all legacy `toResponse(e)` calls for consistency
- `PreviewController`: `HashMap` → `LinkedHashMap` for deterministic key ordering
- `KnowledgeSearchController`: 5 "not ready" paths now emit typed `errorCode`/`errorClass`/`retryable`
- `PreviewController`: `INDEX_UNAVAILABLE → 503` added to `handlePreviewFailure` switch

**Wave 2 (SHIPPED)** — medium controllers with custom exception pass-through:

| Controller | Changes |
|------------|---------|
| `PolicyController` | 6 inline codes migrated; 2 `UserPolicyWriteException` catch blocks upgraded via `resolveByName()` |
| `AiRuntimeController` | 7 inline codes migrated (`VARIANT_ID_REQUIRED`, `POLICY_ONLINE_AI_DISABLED`, `POLICY_GPU_DISABLED`, `RUNTIME_ACTIVATION_RUNNING`×2, `RUNTIME_ACTIVATION_START_FAILED`, `RUNTIME_DEACTIVATION_START_FAILED`) |
| `AiPackController` | 6 inline codes migrated; 1 `AiPackPreflightException` catch block upgraded via `resolveByName()` |
| `AiInstallController` | 6 inline codes migrated; 2 `AiInstallException` catch blocks upgraded via `resolveByName()` |
| `IndexingController` | ~25 error sites migrated; 2 missing errorCode paths added (`INVALID_REQUEST`); all `SERVICE_UNAVAILABLE` and legacy `toResponse(e)` calls upgraded |
| `KnowledgeSearchController` | 5 remaining validation sites (`Map.of("error", ...)`) upgraded to typed `INVALID_REQUEST` |

New infrastructure: `ApiErrorHandler.resolveByName(String)` — safely converts string error codes from custom exceptions (`UserPolicyWriteException`, `AiPackPreflightException`, `AiInstallException`) to `ApiErrorCode` with `INTERNAL_ERROR` fallback + warning log.

New enum values added: `MANIFEST_SHA_REQUIRED`, `PACK_MANIFEST_SHA_INVALID`, `USER_POLICY_UPDATE_FAILED`, `USER_POLICY_WRITE_FAILED`.

Post-review fix: `resolveByName()` logs unknown code names at WARN level for debuggability.

**Wave 3 (SHIPPED)** — complex controllers (streaming/SSE + REST):

| Controller | Changes |
|------------|---------|
| `InferenceHandlers` | 14 inline codes migrated; 4 bare sites got codes (`INVALID_JSON`, `INVALID_REQUEST`, `SERVICE_UNAVAILABLE`, `INTERNAL_ERROR`); sites with extra fields (`mode`, `causes`) use `toResponse()` + `put()` |
| `AgentController` | 9 REST bare sites migrated (`SERVICE_UNAVAILABLE`, `INVALID_REQUEST`, `NOT_FOUND`, `INTERNAL_ERROR`); 2 SSE sites (`BAD_REQUEST`, `RESUME_FAILED`) upgraded to include `errorClass`/`retryable` |
| `LogLevelController` | 5 bare sites migrated (`NOT_SUPPORTED`×2, `INVALID_JSON`, `INVALID_REQUEST`×2); extra `valid` field preserved |
| `DebugStateController` | 3 bare sites migrated (`NOT_FOUND`×2 with `details`/`path`, `IO_ERROR`) |
| `SummaryController` | Full migration — records changed from `String errorCode` to `ApiErrorCode`; `summarizeErrorPayload` delegates to `buildSseErrorPayload`; all ~18 SSE sites upgraded; bare REST error → `INVALID_REQUEST`; fixed `streamStandardSummary` bug (hardcoded `STREAM_FAILED` → `resolveErrorCode()`) |
| `RagStreamingHandler` | 9 SSE sites migrated to `buildSseErrorPayload`; fixed 2 `STREAM_FAILED` bugs → `resolveErrorCode()` (TIMEOUT now propagates correctly) |
| `FullCoverageSummarizer` | 6 SSE sites migrated; `CONFIRM_REQUIRED` event preserves `fileCount`/`maxFilesWithoutConfirm`; routing gate uses enum comparison |
| `SectionProcessingOps` | 5 SSE sites migrated; routing gate uses enum comparison |

New enum value added: `CONFIRM_REQUIRED(ErrorClass.VALIDATION)` — needed by `FullCoverageSummarizer` file-count confirmation event.

Bug fixes in Wave 3:
- `RagStreamingHandler` lines 262, 459 hardcoded `STREAM_FAILED` instead of `resolveErrorCode()` — TIMEOUT exceptions now produce `TIMEOUT` code
- `SummaryController.streamStandardSummary` same bug — fixed
- `SummaryErrorUtils` mismatch: `resolveErrorMessage` caught `"Service Unavailable"` but `resolveErrorCode` didn't — added

Post-review fixes (critical analysis pass):
- `SummaryController.handleHierarchicalSummaryStream` — catch block used bare `Map.of("errorCode", "HIERARCHICAL_FAILED")` string; migrated to `buildSseErrorPayload(msg, ApiErrorCode.HIERARCHICAL_FAILED)`
- `RagStreamingHandler.buildRagContext` — `NO_CONTENT` path used bare `Map.of`; migrated to `buildSseErrorPayload` with `docIds` extra field
- `RagStreamingHandler.fetchBatchFallback` — `FETCH_FAILED` path used bare `Map.of`; migrated to `buildSseErrorPayload` with `docIds` extra field
- `SummaryController.handleGetChunkInfo` — catch block had no `errorCode`/`errorClass`/`retryable` at all; migrated to `ApiErrorHandler.toResponse(INTERNAL_ERROR, ...)` with `docId` extra field

### P2b: SummaryErrorUtils migration (SHIPPED)

`SummaryErrorUtils.resolveErrorCode()` now returns `ApiErrorCode` instead of `String`. Message-sniffing retained as transitional adapter. New `buildSseErrorPayload(String, ApiErrorCode)` helper added for SSE error events — returns `LinkedHashMap` with `error`, `errorCode`, `errorClass`, `retryable` fields. All callers updated: `SummaryController`, `RagStreamingHandler`, `FullCoverageSummarizer`, `SectionProcessingOps`, `MapReducePipeline`.

### P2c: Frontend data-driven retry (SHIPPED)

`isRetryableError()` now accepts optional `errorClass` parameter. When present, checks `errorClass === 'TRANSIENT'` instead of hardcoded list. Fallback to legacy list for SSE events that don't carry `errorClass` yet (`INTERRUPTED` removed from fallback list — it is `PERMANENT` and should not prompt retry). `aiErrorClass` threaded through: `useAppAI` → `App.tsx` → `InspectionPane` → `InspectorAnswer`. `StreamErrorEvent` interface extended with `errorClass?` and `retryable?` fields; SSE parser extracts them from error event data.

Post-review fixes (critical analysis pass):
- `StreamHandlers.onError` declared type updated to include `errorClass?` and `retryable?` — was lying about what the SSE parser actually attaches to the error object
- Both demo error simulation paths (`?demo_error=` and `demoOverrides.demoError`) now set `errorClass` and `retryable` on simulated errors, using a lookup table that mirrors `ApiErrorCode.errorClass()`. Unknown codes default to `'PERMANENT'`. This enables `errorClass`-driven retry to be tested in demo mode.
- `handleCancelAi` now clears all four error state fields (`aiError`, `aiErrorCode`, `aiErrorClass`, `aiErrorRequestId`) — previously left stale error UI visible after cancel when no new operation was started
- `INTERRUPTED` removed from the `isRetryableError` legacy fallback list — was classified `PERMANENT` in the enum since Phase 1 post-review but the fallback list was never updated, creating a contradiction where old events without `errorClass` would incorrectly show retry for user-initiated cancellations

### P2d: SSE wire format (SHIPPED)

All SSE error events now include `errorClass` and `retryable` alongside existing `error` and `errorCode` fields. Implemented within Wave 3 controller migrations — every `writeEvent(ctx, "error", ...)` call uses `buildSseErrorPayload()` or manual `LinkedHashMap` with all 4 fields.

### P2e: Telemetry alignment

`recordError()` uses `ApiErrorCode` tags consistently. Error dashboards show `errorClass` dimension. Deferred — lower priority, can be done incrementally.

### P2f: German translations (SHIPPED)

33 error message strings translated in `modules/ui-web/src/locales/de/messages.po`. Lingui catalog extracted and compiled — 751 total messages, 0 missing.

## Status

All phases complete except P2e (telemetry alignment, deferred). The unified error type system is fully operational:

- **92 enum values** in `ApiErrorCode` with `ErrorClass` classification
- **All 17+ controllers** migrated from string literals to typed enum values
- **Both wire formats** (REST and SSE) include `errorClass` and `retryable`
- **Frontend retry** is data-driven via `errorClass === 'TRANSIENT'`
- **Contract test** verifies enum ↔ frontend parity
- **German translations** complete (751/751)
- **7 bugs fixed** during and after migration (3× hardcoded STREAM_FAILED, 4 missed migration sites found by critical analysis pass)
