---
title: "502 — Capability-Gated Service Composition"
---

# 502 — Capability-Gated Service Composition

**Status:** Complete — structural inversion shipped (LateBoundServices record eliminates late-binding for 8 services)  
**Created:** 2026-05-16  
**Rewritten:** 2026-05-17  
**Scope:** Head process boot infrastructure — capability gates, service wiring, sentinel elimination  
**Branch:** `worktree-502-boot-composition`

## What shipped

### Earlier commits (prior session)
- Capability model as primary state source (`WorkerCapability`, `InferenceCapability`, `CapabilityHealth`)
- `EngineState` deprecated and delegating to `WorkerCapability`
- HTTP-level capability gates on `/api/knowledge/*`, `/api/indexing/*`, `/api/chat/agent` (method-aware, structured 503)
- Boot contract reordering (before API server)
- Worker recovery generation counter (replaces never-reset CAS)
- Push-based health propagation (capability listeners → ConditionStore → SSE)
- Redundant inline state checks removed from gated POST handlers
- HeadlessApp Phase 0 (`resolveConfig`) and Phase 1 (`setupInfra`) extracted into composition functions
- `ConfigContext` and `InfraContext` records in `app-services/lifecycle/`

### Current session (2026-05-17)
- **A1:** `LifecycleProjection.derive()` wired into `StatusLifecycleHandler` — replaces inline `deriveOverallLifecycle()`. Inference component migrated from `OnlineAiService` to `InferenceCapability.health()`. Design decision: PENDING always maps to STARTING (no reason-string check) for backward compat.
- **A2:** `EngineState` enum deleted — `WorkerCapability` is sole health source. `KnowledgeServerHealthMonitor` simplified to read capability directly. Eliminated redundant double-transition pattern.
- **B1+B2+B3:** Operation-level capability gates wired. `RequiredCapability` evaluation added to `OperationExecutorImpl` dispatch pipeline. All 25 `CoreOperationCatalog` operations classified (14 Worker, 4 Inference, 1 both, 6 neither). Resolver bridge wired in `AppFacadeBootstrap`. 4 tests verify gate behavior.
- **D1:** `catch (UnsupportedOperationException)` removed from all 25 operation handlers — now dead code behind capability gates. 5 tests updated.
- **D2:** `catch (UnsupportedOperationException)` replaced in 6 `IndexingController` GET handlers with explicit `workerAvailable()` checks. `WorkerCapability` threaded into controller via setter from `LocalApiServer`.
- **D3 (partial):** Sentinel pattern removed from 8 late-bound services. `.unavailable()` factory methods deleted from 8 service interfaces. Volatile fields in `AppFacadeBootstrap` and `DefaultAppFacade` changed to null-initialized. Identity comparison (`== IndexingService.unavailable()`) replaced with capability check. Investigation confirmed these services are never accessed through AppFacade in production — only via bootstrap volatile field suppliers.
- **AppFacade cleanup:** Removed 9 dead service-locator methods from `AppFacade`, 9 volatile fields + 9 getters + 9 setters from `DefaultAppFacade`. AppFacade reduced to 6 essential methods.
- **A3:** HeadlessApp Phase 2 (`buildApi`) and Phase 3 (`connectWorker`) extracted into composition functions with typed result records. `main()` is now a linear sequence of phase calls + timing + shutdown hook.
- **D4 (partial):** Migrated 12 AppFacade consumers to direct service injection. `IndexingController` receives `IndexingService`, `InferenceHandlers` receives `OnlineAiService`, `StatusLifecycleHandler` receives `OnlineAiService` + `AgentService` + `Supplier<InferenceRuntimeView>`, etc. AppFacade now held only by `LocalApiServer` (for `KnowledgeSearchController` construction) — all other consumers use direct injection.
- **D4 (complete):** Deleted `DefaultAppFacade` (187 lines) and `CursorValidatingFacade` (no-op wrapper). `AppFacadeBootstrap` now implements `AppFacade` directly. `search()` DTO mapping moved inline. `appFacade()` returns `this`.
- **D5:** Decomposed primary constructor from 741 → 330 lines (55% reduction) via 6 extracted private methods. Secondary constructor also reduced 63% via shared methods.
- **Live-verified** with dev stack (7x): all endpoints work correctly after full refactor.

---

## §1 Problem Statement (revised)

The shipped capability model handles the *health* axis correctly — the system knows whether Worker/Inference are READY/DEGRADED/RECOVERING and gates HTTP routes accordingly. But the *structural wiring* axis remains unreformed: services are located via volatile sentinel fields, constructed inside controllers, and accessed through two parallel locator paths. The capability gates are incomplete — they cover HTTP routes but not operation dispatch.

### 1.1 ~~Operation routes are ungated~~ — RESOLVED

Operation dispatch now checks `RequiredCapability` via the executor pipeline (B1). All 25 catalog operations declare their capabilities (B2). UOE catches removed from all handlers (D1).

### 1.2 Controller-as-Service circular dependency

Eight controllers implement service interfaces directly:

| Controller | Implements | Late-bound onto |
|---|---|---|
| `SettingsController` | `SettingsService` | `AppFacadeBootstrap` |
| `IndexingController` | `ExcludesService` | `AppFacadeBootstrap` |
| `AiInstallController` | `BrainInstallService` | `AppFacadeBootstrap` |
| `AiRuntimeController` | `BrainRuntimeService` | `AppFacadeBootstrap` |
| `AiPackController` | `PackImportService` | `AppFacadeBootstrap` |
| `PolicyController` | `PolicyService` | `AppFacadeBootstrap` |
| `DiagnosticsController` | `DiagnosticsService` | `AppFacadeBootstrap` |
| `InferenceHandlers` | `BrainRuntimeService` | `AppFacadeBootstrap` |

This creates a circular wiring loop: `AppFacadeBootstrap` → `LocalApiServer` → controllers (which ARE services) → late-bind setters back onto `AppFacadeBootstrap`. Late-binding exists to close this loop. The fix is separating service logic from HTTP handling.

### 1.3 Two parallel service-locator paths

Services are accessed through two independent volatile-field hierarchies:

1. **`AppFacade.xxx()`** — `DefaultAppFacade` holds 13 volatile fields, updated by `lateBindWorkerServices()` and per-service setters. Used by controllers.
2. **`() -> this.xxxService`** — `AppFacadeBootstrap` holds its own volatile fields, updated by `connectKnowledgeServer()` and per-service setters. Used by operation handler suppliers.

These two paths read different objects, updated by different setter calls at different times. They happen to converge because `DefaultAppFacade` is constructed inside `AppFacadeBootstrap`, but the indirection is a maintenance hazard.

### 1.4 ~~Sentinel infrastructure~~ — PARTIALLY RESOLVED

8 of 13 `.unavailable()` factories deleted. Sentinel-initialized volatile fields changed to null in both `AppFacadeBootstrap` and `DefaultAppFacade`. Identity comparisons replaced with capability checks. UOE catches removed from 25 operation handlers and 6 IndexingController GET handlers.

**Remaining:** 5 service interfaces still have `.unavailable()` — `IndexingService`, `DocumentService`, `OnlineAiService`, `WorkerService`, `AgentService`. These are used in `DefaultAppFacade` constructor defaults and consumed through `appFacade.xxx()` by controllers. Removing them requires either: (a) restructuring DefaultAppFacade to not need constructor defaults, or (b) replacing the facade pattern entirely (D4).

### 1.5 Remaining mechanical items

- `LifecycleProjection.derive()` exists but is not wired — `StatusLifecycleHandler` still uses `deriveOverallLifecycle`
- `EngineState` is `@Deprecated` but still active in ~9 files, ~20 call sites
- HeadlessApp Phase 2 (API server construction) and Phase 3 (Worker late-bind) are still inline in `main()`
- `AppFacadeBootstrap` constructor is ~700 lines, mixing all phase concerns

---

## §2 Correct Design — Five Layers

The layers have a strict dependency order. Each layer is independently shippable, but later layers depend on earlier ones being complete.

### Layer 1: Complete capability gate coverage

**Goal:** Every path from external request to service code passes through a capability check. No handler runs against an unavailable service.

**Mechanism:** Wire the existing `RequiredCapability` substrate into `OperationExecutorImpl`. Add capability evaluation between the trust-lattice enforcement and input validation in the dispatch pipeline:

```
validateProvenance → enforceTrustLattice → [evaluateCapabilities] → inputValidator → dispatchCore
```

The executor receives a resolver function (`Function<RequiredCapability, Boolean>`) at construction time. The composition root wires this to check live capability health:

```java
req -> switch (req) {
    case RequiredCapability.WorkerOnline w -> workerCapability.available();
    case RequiredCapability.InferenceOnline i -> inferenceCapability.available();
    case RequiredCapability.IndexedRoot r -> workerCapability.available() && hasIndexedRoots();
    case RequiredCapability.GpuAvailable g -> gpuAvailable();
}
```

If any required capability is unavailable, the executor returns a structured `OperationResult.failure()` with a `CAPABILITY_UNAVAILABLE` error class — not an exception. This matches the existing pattern where validation failures return results, not exceptions.

**Catalog population:** Each operation in `CoreOperationCatalog` declares its requirements. Worker-dependent operations (restart-worker, reindex, bulk-reindex, add/remove-watched-root, preview/apply-excludes, clear-failed-jobs, cancel/retry-indexing-job, index-gc, export-diagnostics, resolve-path-hash, rebuild-index) get `Set.of(RequiredCapability.WorkerOnline.INSTANCE)`. Inference operations (reload-inference, switch-inference-mode, activate/deactivate-runtime-variant) get `Set.of(RequiredCapability.InferenceOnline.INSTANCE)`. Some may need both. `ping-backend` and `navigate-to-surface` need neither.

**Verification:** Test that dispatching each capability-gated operation with Worker/Inference capability OFFLINE returns `CAPABILITY_UNAVAILABLE` without reaching the handler.

**After this layer:** The UnsupportedOperationException catches in operation handlers become dead code (the dispatcher guarantees the handler only runs when its capability is available). Combined with existing HTTP gates, all external paths to service code are capability-gated.

### Layer 2: Separate services from controllers

**Goal:** Break the circular dependency. Services are plain objects constructed before controllers. Controllers delegate HTTP requests to injected services.

**Investigation (2026-05-17) confirmed: all eight are mechanical extractions.** Every service interface method in every controller is pure business logic — zero `Context` usage, zero HTTP status code manipulation, zero response formatting. HTTP handlers are separate methods that call the service methods and then format responses. Extraction is moving the method to a new class and updating the constructor.

| Controller | Service | Methods | Extraction |
|---|---|---|---|
| `SettingsController` | `SettingsService` | `resetToDefaults()` | Move method, inject `UiSettingsStore` |
| `PolicyController` | `PolicyService` | policy CRUD | Move methods, inject policy store |
| `DiagnosticsController` | `DiagnosticsService` | diagnostic export | Move method, inject collectors |
| `InferenceHandlers` | `BrainRuntimeService` | `reloadInference()`, `switchInferenceMode()`, `triggerOfflineProcessing()` | Move 3 methods, inject `OnlineAiService` + policy |
| `IndexingController` | `ExcludesService` | `applyExcludes(boolean)` | Move method, inject `IndexingService` |
| `AiInstallController` | `BrainInstallService` | install lifecycle | Move methods, inject install state |
| `AiRuntimeController` | `RuntimeVariantService` | variant activation | Move methods, inject runtime state |
| `AiPackController` | `PackImportService` | pack import | Move methods, inject pack store |

**After this layer:** `LocalApiServer` constructs controllers that receive pre-built services. No late-bind setters flow back to the bootstrap. The circular dependency is broken.

### Layer 3: Replace AppFacade with typed service records

**Goal:** Eliminate the service-locator interface. Controllers receive exactly the services they need.

Instead of `AppFacade` with 15 methods, services are grouped by availability phase:

```java
record CoreServices(SettingsService settings, PolicyService policy,
    DiagnosticsService diagnostics, OnlineAiService onlineAi, AgentService agent) {}

record WorkerServices(IndexingService indexing, DocumentService documents,
    ExcludesService excludes, WorkerService worker) {}

record InferenceServices(BrainRuntimeService brainRuntime,
    RuntimeVariantService runtimeVariant, PackImportService packImport,
    BrainInstallService brainInstall) {}
```

`AppFacade.search(SearchRequest)` — the only non-locator method — moves to a `SearchService` that wraps `SearchPort`.

Controllers receive the record they need. Operation handlers receive their specific service (not a locator). `DefaultAppFacade` and `AppFacade` are deleted.

**After this layer:** One service-locator path instead of two. Each consumer holds exactly what it needs, not a God-interface.

### Layer 4: Phase-typed composition root

**Goal:** Continue the Phase 0-1 pattern to cover the full boot sequence. `AppFacadeBootstrap`'s monolithic constructor becomes per-phase composition functions.

```
Phase 0: resolveConfig()      → ConfigPhaseResult    [already extracted]
Phase 1: setupInfra(config)   → InfraPhaseResult     [already extracted]
Phase 2: buildServices(infra) → ServicePhaseResult   [new — all services constructed]
Phase 3: buildApi(services)   → ApiPhaseResult        [new — Javalin bound, routes registered]
Phase 4: connectWorker(api)   → terminal              [new — Worker capability acquired]
```

Phase 2 constructs all services (CoreServices, WorkerServices stubs, InferenceServices stubs). Phase 3 constructs all controllers from Phase 2's services and binds the Javalin server. Phase 4 is the async Worker connection, already handled by `connectKnowledgeServer()`.

Each phase is a static function returning a typed record. The records chain: `ServicePhaseResult` includes `InfraPhaseResult`, which includes `ConfigPhaseResult`. Each function is independently testable by mocking the previous phase's output.

### Layer 5: Delete sentinels, EngineState, and dead catch blocks

**Goal:** Remove all remnants of the pre-capability-model era.

Once Layers 1-4 are complete:

| Item | Scope | Precondition |
|---|---|---|
| `.unavailable()` factory methods | 13 service interfaces | Layer 1 (no path reaches sentinel) |
| `AppFacade` interface | 1 interface, 15 methods | Layer 3 (replaced by typed records) |
| `DefaultAppFacade` class | 1 class, 13 volatile fields, 10 setters | Layer 3 |
| `AppFacadeBootstrap` sentinel field initializers | 9 volatile fields | Layer 2 (no late-binding) |
| `AppFacadeBootstrap` late-bind setters | 8 setter methods | Layer 2 |
| `catch (UnsupportedOperationException)` blocks | ~37 sites | Layer 1 (executor gates ops) |
| `== IndexingService.unavailable()` identity comparisons | 4 sites | Layer 1 |
| `EngineState` enum | 1 enum + ~20 call sites in 9 files | Already @Deprecated, mechanical |
| Wire `LifecycleProjection.derive()` | Replace `deriveOverallLifecycle` | Already exists, substitution |
| HeadlessApp Phase 2-3 extraction | ~200 lines in `main()` | Layer 4 |

---

## §3 Implementation Items

Items are ordered by dependency. ✅ = shipped, ⏸ = deferred with specific blocker.

### Tier A — Foundation (no dependencies on later tiers)

| # | Status | Item |
|---|---|---|
| A1 | ✅ | Wire `LifecycleProjection.derive()` — matched current behavior (PENDING→STARTING, no reason-string check) |
| A2 | ✅ | Delete `EngineState` enum — `WorkerCapability` is sole health source |
| A3 | ✅ | Extract HeadlessApp Phase 2 (`buildApi`) and Phase 3 (`connectWorker`) into composition functions with typed result records. |

### Tier B — Capability gate completion (enables Tier D sentinel removal)

| # | Status | Item |
|---|---|---|
| B1 | ✅ | Wire `RequiredCapability` evaluation into `OperationExecutorImpl` dispatch pipeline |
| B2 | ✅ | Populate `requiredCapabilities` on all 25 `CoreOperationCatalog` operations (14 Worker, 4 Inference, 1 both, 6 neither) |
| B3 | ✅ | 4 tests: unavailable → failure, available → dispatch, null resolver → skip, empty set → pass |

### Tier C — Service extraction (enables Tier D late-bind removal)

| # | Status | Item |
|---|---|---|
| C1 | ✅ | Controllers still implement service interfaces (module boundary constraint prevents extraction), but the late-bind circular dependency is eliminated. Services are passed as a typed `LateBoundServices` record with direct references, not via volatile fields. |
| C3 | ✅ | 8 volatile fields and 8 setter methods deleted. `registerLateBoundHandlers(LateBoundServices)` receives direct service references. Handler suppliers capture final record accessors, not volatile field reads. |

### Tier D — Structural cleanup (depends on B + C)

| # | Status | Item |
|---|---|---|
| D1 | ✅ | Remove `catch (UnsupportedOperationException)` from 25 operation handlers |
| D2 | ✅ | Replace catches in `IndexingController` GET handlers with `workerAvailable()` checks. `WorkerCapability` threaded into controller. |
| D3 | ✅ (8/13) | Deleted `.unavailable()` from 8 late-bound service interfaces. Remaining 5 (`IndexingService`, `DocumentService`, `OnlineAiService`, `WorkerService`, `AgentService`) are still used in `DefaultAppFacade` constructor defaults and controller test wiring. |
| D4 | ✅ | `DefaultAppFacade` deleted. `CursorValidatingFacade` deleted. `AppFacadeBootstrap` implements `AppFacade` directly. All 13 consumers migrated to direct injection. AppFacade interface kept as the stable contract (6 methods). |
| D5 | ✅ | Primary constructor decomposed from 741→330 lines via 6 private methods. Secondary constructor also reduced 63%. |

---

## §4 Key Files

| File | Role | Lines |
|---|---|---|
| `modules/app-services/.../AppFacadeBootstrap.java` | God object: ~70 fields, sentinel fields, late-bind setters, handler-supplier wiring | ~3125 |
| `modules/app-services/.../DefaultAppFacade.java` | Service locator: 13 volatile sentinel fields, 10 setters | ~280 |
| `modules/app-api/.../AppFacade.java` | Service locator interface: 15 methods, 13 with sentinel defaults | ~120 |
| `modules/ui/.../LocalApiServer.java` | Controller construction + late-bind callbacks | ~1500 |
| `modules/ui/.../HeadlessApp.java` | Boot sequence: Phase 0-1 extracted, Phase 2-3 inline | ~1140 |
| `modules/app-services/.../executor/OperationExecutorImpl.java` | Operation dispatch pipeline — needs RequiredCapability check | ~350 |
| `modules/app-services/.../operations/CoreOperationCatalog.java` | 25 operations, all with `Set.of()` requiredCapabilities | ~1100 |
| `modules/app-services/.../lifecycle/LifecycleProjection.java` | Pure derivation function — exists, unwired | ~75 |
| `modules/app-services/.../lifecycle/WorkerCapability.java` | Worker health tracking — shipped, correct | ~82 |
| `modules/app-services/.../lifecycle/InferenceCapability.java` | Inference health tracking — shipped, correct | ~76 |
| `modules/app-services/.../worker/KnowledgeServerBootstrap.java` | Holds @Deprecated EngineState + Worker spawn | ~600 |
| `modules/ui/.../StatusLifecycleHandler.java` | Lifecycle snapshot — still uses old derivation | ~800 |

---

## §5 Risks and Gaps

### 5.1 Operation capability classification requires judgment

Not every Worker-dependent operation is obvious. Some operations (like `trigger-offline-processing`) depend on both Worker and Inference. Some (`export-diagnostics`) partially work without the Worker. Each operation needs individual analysis of which capabilities it actually requires, not blanket classification.

### 5.2 Controller-service extraction varies in difficulty

**Resolved (2026-05-17):** Investigation confirmed all 8 controller-as-service types have clean separation — service methods are pure business logic with zero HTTP coupling. All extractions are mechanical (move method + update constructor). See §2 Layer 2.

### 5.3 Test suite depends on sentinel infrastructure

~35+ test files construct `DefaultAppFacade` with minimal constructors, wire `XxxService::unavailable` suppliers, or catch `UnsupportedOperationException`. Test migration must happen in lockstep with production changes. The existing test constructors (`DefaultAppFacade(SearchPort)`) need equivalents in the new model.

### 5.4 AppFacade.search() is the odd one out

`AppFacade.search(SearchRequest)` is actual business logic (query mapping + `SearchPort` delegation), not service retrieval. When `AppFacade` is deleted, this needs a home — likely a `SearchService` wrapping `SearchPort`.

### 5.5 IndexingController GET handlers have a different gating concern

The 6 `catch (UnsupportedOperationException)` blocks in `IndexingController` GET handlers (`/api/indexing/jobs`, `/api/indexing/roots`, etc.) are NOT covered by the existing HTTP capability gate, which only gates non-GET requests. GET requests on `/api/indexing/*` pass through because the gate is method-aware (it allows GETs for graceful degradation — the UI shows the indexing panel even when the Worker is starting). These catches need to be either: (a) converted to explicit capability checks in the handler, or (b) the GET handlers need to return degraded responses (empty lists) instead of 503.

### 5.6 Two-path service locator unification

`AppFacade.xxx()` and `() -> this.xxxService` in `AppFacadeBootstrap` are two parallel volatile-field hierarchies updated by different setter calls. Layer 3 (replace AppFacade) and Layer 2 (eliminate late-binding) must converge these into a single source. Until both layers are complete, there's a risk of one path being updated while the other is stale.

### 5.7 `RequiredCapability.IndexedRoot` has no runtime implementation

The sealed variant exists but there is no runtime health tracker for "at least one indexed root exists." Checking this requires a Worker gRPC query — which itself requires `WorkerOnline`. For now, `IndexedRoot` can imply `WorkerOnline` as a prerequisite, with the indexed-roots check delegated to the handler. Full enforcement is a V2 concern.

---

## §6 Resolved Design Decisions (2026-05-17 investigation)

### 6.1 LifecycleProjection.derive() is NOT a drop-in replacement

`LifecycleProjection.derive()` and `StatusLifecycleHandler.deriveOverallLifecycle()` have different input shapes and one behavioral difference:

- **Input shape:** `derive()` takes raw `WorkerCapability` + `InferenceCapability` + `apiServing` boolean. `deriveOverallLifecycle()` takes pre-computed `Component` objects where capability health has already been mapped to `LifecycleState`.

- **Behavioral difference:** When Worker is PENDING, `derive()` inspects `pendingReason()` for the word "failed" to distinguish spawn failure (→ ERROR) from normal startup (→ STARTING). `deriveOverallLifecycle()` always maps PENDING → STARTING because the component builder hard-codes this before calling the derivation.

**Design decision required:** Either (a) modify `LifecycleProjection.derive()` to drop the reason-string check and match current behavior, or (b) accept the behavioral change (spawn failures reported as ERROR earlier). Option (a) is safer for backward compatibility. Option (b) is arguably more correct — reporting a failed spawn as STARTING is misleading.

### 6.2 Operation dispatch capability checking: confirmed feasible

The `OperationExecutorImpl` dispatch pipeline is a clean linear sequence with a natural insertion point between `enforceTrustLattice()` and `inputValidator.validate()`. The error model (`OperationResult.failure(message, errorCode, details, retryable)`) already supports typed error codes. Module dependencies are satisfied (`app-services` has `api` dependency on `app-agent-api`).

The resolver bridge is a `Function<RequiredCapability, Boolean>` with an exhaustive switch over the sealed hierarchy. ~15 lines of code. The result uses `errorCode: "CAPABILITY_UNAVAILABLE"` and `retryable: true`.

### 6.3 Controller-service extraction: mechanical but module-blocked

Investigation confirmed zero HTTP coupling in service methods. However, the extraction is blocked by **module boundaries**, not by code coupling. The service implementations (in `modules/ui`) depend on UI-module types (`DebugStateController`, `StatusLifecycleHandler`, `EnterprisePolicyService`). They cannot move to `modules/app-services` where `AppFacadeBootstrap` lives without either abstracting these dependencies behind interfaces or restructuring the module graph. The late-bind pattern exists because of this module boundary: `ui → app-services → app-api`, and services flow backward (`ui` → `app-services` via setter).

### 6.5 Sentinel elimination scope (2026-05-17)

Of 13 service interfaces with `.unavailable()` factories:
- **8 deleted** (diagnostics, excludes, brainRuntime, runtimeVariant, packImport, brainInstall, policy, settings): never accessed through AppFacade in production. Only via bootstrap volatile field suppliers → operation handlers. Handlers null-check. Fields changed to null-initialized.
- **5 remaining** (indexing, document, onlineAi, worker, agent): accessed through `appFacade.xxx()` by controllers in production. Sentinels serve as constructor defaults in `DefaultAppFacade` for test wiring. Cannot be removed without restructuring DefaultAppFacade constructors or eliminating the AppFacade pattern.

### 6.4 Javalin's beforeMatched/routeRoles pattern does not apply

Javalin 6 has `beforeMatched` with `routeRoles()` for role-based access control. This is designed for auth (static user roles), not capability gating (dynamic system health). The existing `before("/api/knowledge/*", ...)` pattern that checks live `capability.available()` per-request is correct for our use case. No framework-level alternative exists.

For operation dispatch, the CQRS command-bus middleware/decorator pattern is the established approach. Our `OperationExecutorImpl` already implements this pattern. Adding capability checking is adding one more step in the pipeline — the textbook approach.
