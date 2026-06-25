---
title: "Unified Error Type System"
type: tempdoc
status: done
created: 2026-02-18
updated: 2026-02-18
---

> NOTE: Noncanonical doc (implementation log). May drift. Verify against code before acting.

# 217: Unified Error Type System

## Origin

Self-review of the error telemetry and typed exception system identified structural
issues after the initial implementation (Fixes 1-8 from the plan). A critical analysis
then found 3 additional structural issues. All have been resolved.

---

## What was done

### Phase 1: Initial fixes (Fixes 1-8)

| Fix | Severity | Summary |
|-----|----------|---------|
| 1 | HIGH | Eliminated message-sniffing in `InferenceLifecycleManager` by throwing `ModeTransitionException` directly from `LlamaServerOps.adoptExistingServerIfPresent()` |
| 2 | MEDIUM | Added null-safety guard in `ApiErrorHandler.toResponse(Exception, Telemetry, String)` |
| 3 | LOW | Widened `LlmServerException` mapping: added 429 -> `LLM_OVERLOADED` |
| 4 | LOW | Restored defensive message fallback in `SummaryErrorUtils.resolveErrorMessage()` for non-LlmServerException with HTTP status hints |
| 5 | LOW | Added `ApiErrorHandler.routeOf(Context)` helper; replaced hardcoded route strings across all 15 controllers (~100 call sites) |
| 6 | LOW | Documented VALIDATION telemetry design choice (comment at `recordError()`) |
| 7 | — | `IndexingController` catch narrowing — no change needed (behavior identical) |
| 8 | HIGH | Added test coverage: `ApiErrorHandlerResolveTest` (17 tests), `SummaryErrorUtilsTest` |

### Phase 2: Structural issues (Issues 1-3)

| Issue | Summary |
|-------|---------|
| 1 — Parallel resolvers | Refactored `SummaryErrorUtils.resolveErrorCode()` to delegate to `ApiErrorHandler.resolve()` with domain-specific overrides (`DocumentService.UnavailableException` -> `INDEX_UNAVAILABLE`, `UnsupportedOperationException` -> `TRANSLATOR_UNAVAILABLE`) and generic fallback remap (`INTERNAL_ERROR` -> `SUMMARIZE_FAILED`) |
| 2 — resolveByName string bridge | Changed 3 custom exceptions from `String errorCode` to `ApiErrorCode errorCode`: `AiPackPreflightException`, `UserPolicyWriteException` (inner class of `UserPolicyWriter`), `AiInstallException` (inner class of `AiInstallService`). Changed `AiPackValidator.ValidationResult` record similarly. Added 27 new `ApiErrorCode` enum values (17 pack validation, 10 policy). Added 27 matching `errorMessages.ts` entries. Updated 5 catch sites to remove `resolveByName()` indirection. |
| 3 — Message-sniffing | Removed remaining message-sniffing block from `ApiErrorHandler.resolve()` (was checking `e.getMessage()` for "unavailable" / "not found" strings). Tests updated to verify no-sniffing behavior. |

---

## Files changed

### Core error infrastructure
- `modules/app-api/.../ApiErrorCode.java` — 27 new enum values
- `modules/ui/.../api/ApiErrorHandler.java` — null-safety, routeOf(), message-sniffing removal, LlmServerException 429 mapping
- `modules/ui/.../api/SummaryErrorUtils.java` — delegated to ApiErrorHandler.resolve(), defensive message fallback

### Custom exceptions (String -> ApiErrorCode)
- `modules/ui/.../ai/pack/AiPackPreflightException.java`
- `modules/ui/.../ai/pack/AiPackValidator.java` — ValidationResult record + ~30 return sites
- `modules/ui/.../ai/pack/AiPackImportService.java` — throw sites
- `modules/ui/.../ai/pack/PackStagingOps.java` — boundary bridge (`.name()`)
- `modules/ui/.../ai/install/AiInstallService.java` — AiInstallException + throw sites
- `modules/ui/.../policy/UserPolicyWriter.java` — UserPolicyWriteException + 22 throw sites

### Controllers (routeOf + resolveByName removal)
- `AgentController`, `AiInstallController`, `AiPackController`, `AiRuntimeController`
- `DebugStateController`, `DiagnosticsController`, `IndexingController`, `InferenceHandlers`
- `KnowledgeSearchController`, `LogLevelController`, `PolicyController`, `PreviewController`
- `SettingsController`, `SummaryController`, `UiReadyController`

### Inference (message-sniffing elimination)
- `modules/app-inference/.../LlamaServerOps.java` — throws ModeTransitionException directly
- `modules/app-inference/.../InferenceLifecycleManager.java` — removed message-sniffing catch

### Frontend
- `modules/ui-web/src/utils/errorMessages.ts` — 27 new entries

### Tests
- `modules/ui/src/test/.../api/ApiErrorHandlerResolveTest.java` — NEW (17 tests)
- `modules/ui/src/test/.../api/SummaryErrorUtilsTest.java` — NEW
- `modules/ui/src/test/.../ai/install/AiInstallServiceTest.java` — String -> ApiErrorCode assertions
- `modules/ui/src/test/.../ai/pack/AiPackValidatorTest.java` — String -> ApiErrorCode assertions
- `modules/ui/src/test/.../policy/UserPolicyWriterTest.java` — String -> ApiErrorCode assertions

---

## Design decisions

1. **`PackStagingOps.ValidationResult` keeps `String errorCode`**: This is a separate package-private record at a boundary. Bridged with `.name()` where it consumes `AiPackValidator.ValidationResult.errorCode()`. Changing it would cascade into areas unrelated to this work.

2. **`resolveByName()` method kept**: No production callers remain, but the method is tested and may be useful for deserialization from external sources (e.g., stored error codes). Low maintenance cost.

3. **VALIDATION errors recorded in telemetry**: All error classes including VALIDATION are recorded in `api.error.total`. The `error_class` tag enables dashboard filtering (`error_class != "VALIDATION"` for operational alerts). A separate counter would add complexity for no gain.

4. **`SummaryErrorUtils` remaps `INTERNAL_ERROR` -> `SUMMARIZE_FAILED`**: Domain-appropriate default for the summary/RAG context. Other specific codes (TIMEOUT, LLM_OVERLOADED, etc.) pass through from `ApiErrorHandler.resolve()`.

---

## Verification

- `spotlessApply`: PASS
- `:modules:ui:test`: PASS (383 tests, 0 failures)
- `:modules:ui:compileJava`: PASS
- `:modules:app-inference:compileJava`: PASS
- `ApiErrorCodeContractTest`: PASS (bidirectional enum <-> errorMessages.ts parity)

## Remaining work

None. All fixes applied, tests passing. Status: **done**.
