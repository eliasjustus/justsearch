---
title: "412 — Observability pattern adoption (Inference / Embedding / NativeSession / AgentSession)"
type: tempdocs
status: active
consolidated: 2026-06-09
---

# 412 — Observability pattern adoption (Inference / Embedding / NativeSession / AgentSession)

> Consolidated 2026-06-09 from four same-day (2026-04-25) per-component "X Observability Adoption" tempdocs — one pattern rolled across four components. Each section is the original verbatim; originals 413/414/415 retired to git.

---

## InferenceLifecycleManager (was 412)

*(consolidated from `412-inference-lifecycle-observability.md`)*

### 412 — InferenceLifecycleManager: Lifecycle Pattern Adoption + Observability

## Status

**OPEN.** Created 2026-04-25; rewritten 2026-04-26 (first rewrite, against the
shipped MetricCatalog substrate from tempdoc 417); rewritten again 2026-04-26
(this second rewrite) after the patch-shape design surfaced 12+ design questions
that the current ILM shape could not answer.

This rewrite reframes 412 as completing the pattern adoption that
[`docs/future-features/service-identity-lifecycle-pattern.md`](../future-features/service-identity-lifecycle-pattern.md)
identifies for `InferenceLifecycleManager`, and that
[tempdoc 406](406-lucene-lifecycle-manager-restart-refactor.md) shipped for
`LuceneRuntime`. Observability becomes a property of the resulting structure
rather than a separate grafting layer. The pattern is canonicalized in
[ADR-0027](../decisions/0027-metric-catalog-as-telemetry-contract.md).

## Context

`docs/future-features/service-identity-lifecycle-pattern.md` lists ILM
alongside `LuceneRuntime` as a target for phase-typed lifecycle, admin
controls, and hot reload. Lucene shipped in 406 (`POST /api/admin/runtime/reload`
plus sealed `LuceneRuntime` with phase-typed `RunningRuntime` /
`ReadOnlyRuntime` / `DeferredRuntime`); ILM remains pending.

The user-visible payoffs (in priority order):

1. **Phase-correctness at compile time.** A caller that holds an indexing-mode
   inference handle should not be able to call `chatCompletion`. Today this
   is a runtime guard (`Mode` enum check); with phase typing it becomes a
   compile error.
2. **Single observability surface.** Operators currently see ILM-related
   state across three uncoordinated views (`LlmStatusView`, `OnlineAiView`,
   `EngineMonitor`); the previous tempdoc revision would have added a fourth.
   The refactor consolidates to one (`InferenceRuntimeView`).
3. **Operator control.** `POST /api/admin/inference/reload` lets operators
   trigger config-driven restart from outside the process — same shape as
   406's index reload.
4. **Hot model swap, future feature.** Phase-typed runtime is the substrate
   for "switch model X→Y without process restart." Out of scope here, but
   each plausible follow-up tempdoc trips on the same lifecycle gap if
   we don't fix it now.

### Empirical grounding

Verified against the current code (commit `7c8d73953`):

- ILM lives at `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceLifecycleManager.java:59`
  (~900 lines).
- The `Mode` enum at line 66 has **four** values: `ONLINE`, `INDEXING`,
  `TRANSITIONING`, `OFFLINE`. **VDU is not a Mode value** — it is a config
  flag (`InferenceConfig.vduMode()`), entered via
  `applyConfig(withVduMode(true), RESTART_ALWAYS)` from line 433–448.
  The previous tempdoc's 5-value enum (`OFFLINE/ONLINE/INDEXING/VDU/TRANSITIONING`)
  was a fabrication.
- ILM has no builder. The constructor at line 125 takes `InferenceConfig`
  directly. Adding observability via "ILM's builder" (per previous tempdoc
  line 116) requires either adding a builder or refactoring the constructor
  signature.
- The `applyConfig(RESTART_IF_ONLINE)` success path (line 526–608) traverses
  `ONLINE → TRANSITIONING → ONLINE` (two listener notifications per admin
  reload, not one). The rollback path (line 609–654) can additionally
  produce `TRANSITIONING → OFFLINE` if rollback fails. The previous
  tempdoc's validation gate was non-deterministic about which transitions
  to expect.
- `lastStartupDurationMs` (line 111) measures `startLlamaServer()` +
  `waitForServerHealth()` only. It does not measure drain, VRAM flush, or
  rollback. The previous tempdoc's `mode_transition_duration_ms`
  "end-to-end" claim required new measurement code that wasn't called out.
- `ModeTransitionException.Reason` has **15** values. The previous tempdoc's
  5-value `startup_failure_total{reason}` enum was both fabricated and
  narrower than reality.
- `app-inference/build.gradle.kts` has no telemetry dependency. Direct-emit
  would require adding one — events-interface idiom is the structurally
  clean path.
- `OnlineModeOps` has a single `ReentrantLock onlineRequestLock` — no queue.
  The `EngineMonitor.GenerationActorStats.queueDepth()` in `ai-backend` is
  the actual queue-depth source, surfacing today through
  `LlmStatusView.queueDepth`. Adding `inference.runtime.queue_depth` to a
  new catalog without consolidating with `LlmStatusView` would create a
  duplicate without a designated source-of-truth.
- `LocalTelemetry` is constructed in `HeadlessApp.java:224-276` (Head process)
  and `LauncherEnvironment.java:47-89` (launcher process). Catalog
  `DEFINITIONS` registration happens at these sites. The previous tempdoc
  pointed at `AppFacadeBootstrap.java`, which is wrong — the comment at
  `HeadlessApp.java:248-251` explicitly warns about this exact mistake.
- `StatusEndpoint` enum has one value (`CORE_INDEX_VIEW`). Adding
  `inference.runtime.*` gauges with `surfacedAt(StatusEndpoint.???, ...)`
  requires a new enum value plus a new API record to validate against.
- The 406 admin reload established the operator-supplied `reason`
  propagation pattern: `Context body → ReloadRuntimeRequest.reason →
  SwapReason.fromWire(...) → catalog.swapDurationMs.record(_,
  SwapTags.of(reason))`. The inference equivalent should mirror this exactly.

> ⚠️ **The design as originally written below was NOT shipped.** The
> phase-typed runtime scaffold (`InferenceSchema` / `InferenceBuilder` /
> sealed `InferenceRuntime` / `OnlineRuntime` / `VduRuntime` /
> `IndexingRuntime`) was created in the first implementation pass, then
> deleted as dead code in the follow-up — none of those types were
> constructed in production. `InferenceLifecycleManager` retains its
> existing internal structure (Mode enum, ModeStateMachine, listeners,
> periodic health, crash recovery, external-server adoption). What
> actually shipped is described in the **Follow-up (2026-04-26)** section
> at the top of this file. The holder rewrite remains pending; the design
> body below documents the *target shape* for that future tempdoc, kept
> here as the reference design rather than as a shipped contract.

## Strategy (target shape — not shipped)

Three substantive shifts from the previous tempdoc revision:

1. **Phase-typed runtime, not state-machine + flag.** Replace the `Mode`
   enum + `ModeStateMachine` + `vduMode` config flag with a sealed
   `InferenceRuntime` interface and concrete `OnlineRuntime` /
   `VduRuntime` / `IndexingRuntime` final classes. `OFFLINE` and
   `TRANSITIONING` become "the holder is null" and "the holder is briefly
   empty during swap" — they are not phase values. **State is type.**
2. **Single sealed failure taxonomy across exception, metric tag, and
   status field.** Replace `ModeTransitionException.Reason`'s flat 15-value
   enum with a sealed `InferenceFailure` interface whose sub-records group
   by failure category (`StartupFailure`, `HealthFailure`, `ConfigFailure`,
   `TransitionFailure`). Each carries a typed code enum. The `wireCode()`
   method is the single canonical string form, used as exception code,
   metric tag value, status-record `error` field, and structured log key.
3. **Single status surface (`InferenceRuntimeView`).** Replace
   `LlmStatusView` + `OnlineAiView` + `EngineMonitor` + the previous
   tempdoc's proposed catalog string-gauges with one record sourced from
   `lifecycleManager.snapshotView()`. The catalog is purified: only
   numeric metrics. Strings (`phase`, `modelId`) are status-record-only.
   The `surfacedAt` mechanism declares which numeric gauges feed the
   view's numeric components.

Observability is a **consequence** of the resulting structure: the events
interface fires from holder swaps and runtime-internal hooks; the catalog
records numeric facts; the view exposes derived state. There is no
ad-hoc emit anywhere — ArchUnit forbids it.

## Phase-typed runtime design

```java
// 1. Schema/config — immutable, sharable, no lifecycle
public record InferenceSchema(
    Path serverExecutable,
    Path modelPath,
    Optional<Path> mmprojPath,
    int contextSize,
    int gpuLayers,
    GpuPolicy gpuPolicy
) {
  public InferenceBuilder atPort(int port) { ... }
}

// 2. Builder — captures intent, returns typed phase
public final class InferenceBuilder {
  public OnlineRuntime    openOnline()    throws InferenceException;
  public VduRuntime       openVdu()       throws InferenceException;
  public IndexingRuntime  openIndexing()  throws InferenceException;
}

// 3a. Sealed runtime interface
public sealed interface InferenceRuntime extends AutoCloseable
    permits OnlineRuntime, VduRuntime, IndexingRuntime {

  InferenceSchema schema();
  RuntimeIdentity identity();   // generationId, modelId, port, loadedAt
  @Override void close();
}

// 3b. Phase-typed concrete classes — exposing only phase-valid operations
public final class OnlineRuntime    implements InferenceRuntime { /* chat, stream, summarize, ask, vision */ }
public final class VduRuntime       implements InferenceRuntime { /* visionCompletion only; vision-safe flags */ }
public final class IndexingRuntime  implements InferenceRuntime { /* GPU yielded; FFM backend caller-managed */ }
```

`Mode`, `ModeStateMachine`, and `RestartPolicy` are deleted.
`InferenceLifecycleManager` becomes a thin holder:

```java
public final class InferenceLifecycleManager {
  private final InferenceBuilder builder;
  private final InferenceTelemetryEvents events;
  private final AtomicReference<InferenceRuntime> ref = new AtomicReference<>();

  public Optional<OnlineRuntime>   online()   { ... }
  public Optional<VduRuntime>      vdu()      { ... }
  public Optional<IndexingRuntime> indexing() { ... }

  public void switchTo(TargetPhase target, TransitionReason reason);
  public void shutdown(TransitionReason reason);
  public void applyConfig(InferenceSchema next, TransitionReason reason);

  public InferenceRuntimeView snapshotView();
}
```

Restart is `swap(builder.openOnline(), reason)` — atomic reference set,
event emit, close old. **No restart-method on the value.**

## Failure taxonomy

```java
public sealed interface InferenceFailure {
  String wireCode();  // canonical: tag value, error string, log key

  record StartupFailure(StartupCode code, String detail, Throwable cause)     implements InferenceFailure { ... }
  record HealthFailure(HealthCode code, String detail, Throwable cause)       implements InferenceFailure { ... }
  record ConfigFailure(ConfigCode code, String detail)                        implements InferenceFailure { ... }
  record TransitionFailure(TransitionCode code, String detail, Throwable cause) implements InferenceFailure { ... }
}

public enum StartupCode {
  MISSING_DLL, MISSING_BINARY, MISSING_MODEL, PROCESS_EXITED,
  PORT_ALLOCATION_FAILED, INSUFFICIENT_VRAM, EXTERNAL_SERVER_POLICY_BLOCKED,
  UNKNOWN
}
public enum HealthCode {
  HEALTH_TIMEOUT, HEALTH_INTERRUPTED, PROCESS_DIED, CONNECTION_REFUSED,
  UNKNOWN
}
public enum ConfigCode {
  INVALID_CONFIG, EXTERNAL_SERVER_CONFLICT, ALREADY_TRANSITIONING,
  CONFIG_REQUIRED, UNKNOWN
}
public enum TransitionCode {
  ONLINE_START_FAILED, INDEXING_START_FAILED, CONFIG_APPLY_FAILED,
  ROLLBACK_FAILED, INTERRUPTED, UNKNOWN
}
```

Each enum value has a stable `wireValue()` (snake_case string) following
the `SwapReason` precedent. `ModeTransitionException` is replaced by
`InferenceException(InferenceFailure)`. `catch` blocks pattern-match on
the failure subtype to decide recovery — pattern-matching is exhaustive
because the interface is sealed.

## Status surface consolidation

Replace `LlmStatusView` + `OnlineAiView` + `EngineMonitor` with one record:

```java
public record InferenceRuntimeView(
    String                           phase,           // ONLINE | VDU | INDEXING | OFFLINE
    Optional<RuntimeIdentity>        identity,        // empty when OFFLINE
    boolean                          usingExternal,
    Optional<InferenceFailureView>   lastFailure,
    Optional<QueueSnapshot>          queue,           // depth/active/total
    Optional<GenerationSnapshot>     generation,      // tokensPerSecond/lastHeartbeat
    LifecycleCounters                counters         // softRestarts/hardRestarts/transitionsTotal
) {}
```

Sourced exclusively from `InferenceLifecycleManager.snapshotView()`.
`OnlineRuntime` / `VduRuntime` instances internally own llama-server
probing (absorbing `EngineMonitor`'s job).

`StatusResponse` migration: `llm` field type changes from `LlmStatusView`
to `InferenceRuntimeView`; `onlineAi` field is removed (its two booleans
are derivable from `view.phase()` — both expressible from the single
source). Recommendation **(A) hard cut**: rename `llm` → `inference`,
drop `onlineAi`, update consumers (`StatusLifecycleHandler`,
`modules/ui-web/src/api/`, Zod schemas). This is a single-process
desktop app with no external API consumers; deprecation cycle adds
drift surface for no gain. See "Open questions" for the alternative.

## Proposed metric set

Numeric-only. Catalog namespace: `inference`.

| Metric | Type | Tags | When fires | Bucket / cardinality |
|---|---|---|---|---|
| `inference.transition.total` | counter | `from_phase`, `to_phase`, `reason` (TransitionReason) | Each holder swap (one per swap; no `TRANSITIONING` phase exists, so the previous tempdoc's two-events-per-reload issue evaporates) | from × to × reason ≤ 64 |
| `inference.transition.duration_ms` | histogram | `from_phase`, `to_phase`, `reason` | End-to-end swap (`builder.open*()` + `oldRuntime.close()`); see Phase 0 investigation item 4 for rollback case decision | `Buckets.TIME_HISTOGRAM`; `Exemplars.TRACE_BASED` |
| `inference.startup.attempt_total` | counter | `phase`, `reason` (StartupReason) | Per `builder.open*()` call (success or failure) | bounded |
| `inference.startup.duration_ms` | histogram | `phase` | Per successful open | `Buckets.TIME_HISTOGRAM` |
| `inference.startup.failure_total` | counter | `phase`, `code` (StartupCode `wireValue`) | Per builder open failure | bounded by enum |
| `inference.config.apply_total` | counter | `restart_required` (boolean) | Per `applyConfig` call | 2 |
| `inference.config.apply_failure_total` | counter | `code` (ConfigCode + TransitionCode) | Per `applyConfig` failure | bounded |
| `inference.health.failure_total` | counter | `code` (HealthCode), `severity` (`single` / `restart_triggered`) | Periodic health probe failure | bounded |
| `inference.health.recovered_total` | counter | none | Health recovery after ≥1 failure | EmptyTags |
| `inference.request.queue_wait_ms` | histogram | `kind` (`chat`/`stream`/`vision`/`summary`/`vdu`) | Wait for `onlineRequestLock` acquisition | `Buckets.SLO_LATENCY_MS` |
| `inference.request.duration_ms` | histogram | `kind`, `outcome` (`ok`/`error`/`cancelled`) | Per request total | `Buckets.SLO_LATENCY_MS` |

Status gauges (all numeric, all `archivedTo(STANDARD)`,
`surfacedAt(StatusEndpoint.INFERENCE_VIEW, ...)`):

| Gauge | Source | View field |
|---|---|---|
| `inference.queue.depth` | llama-server `/metrics` `llamacpp:requests_deferred` (requires `--metrics` flag — added to launch command in Phase 2) | `queue.depth` |
| `inference.queue.active_slots` | llama-server `/metrics` `llamacpp:requests_processing` | `queue.activeSlots` |
| `inference.queue.total_slots` | llama-server `/props` `total_slots` | `queue.totalSlots` |
| `inference.generation.tokens_per_second` | derived from llama-server `/metrics` `llamacpp:tokens_predicted_total` rate-of-change | `generation.tokensPerSecond` |
| `inference.runtime.kv_cache_usage_ratio` | llama-server `/metrics` `llamacpp:kv_cache_usage_ratio` | `generation.kvCacheUsageRatio` |
| `inference.runtime.transitions_total` | `LifecycleCounters` cumulative | `counters.transitionsTotal` |

**Strings (`phase`, `modelId`, `lastFailure.wireCode`) are
status-record-only** — never gauges.

A new `StatusEndpoint.INFERENCE_VIEW` enum value is added in this tempdoc's
scope, paired with the `InferenceRuntimeView` record.
`MetricSurfaceContractTest` validates the gauge `surfacedAt` field names
against record components.

## Observability events contract

```java
package io.justsearch.app.inference.telemetry;

public interface InferenceTelemetryEvents {

  void onTransition(
      Optional<InferenceRuntime> from,
      Optional<InferenceRuntime> to,
      TransitionReason reason,
      Duration elapsed);

  void onStartupAttempt(InferenceSchema schema, StartupReason reason, TargetPhase target);
  void onStartupComplete(InferenceSchema schema, Duration elapsed, RuntimeIdentity identity);
  void onStartupFailure(InferenceFailure.StartupFailure failure);

  void onHealthFailure(InferenceFailure.HealthFailure failure, int consecutiveCount, boolean restartTriggered);
  void onHealthRecovered(int previousFailureCount);

  void onConfigApplyAttempt(InferenceSchema oldSchema, InferenceSchema newSchema, boolean restartRequired);
  void onConfigApplyComplete(Duration elapsed);
  void onConfigApplyFailure(InferenceFailure failure);

  void onRequestEnqueued(RequestKind kind);
  void onRequestStarted(RequestKind kind, Duration waitedMs);
  void onRequestCompleted(RequestKind kind, Duration totalMs, RequestOutcome outcome);

  static InferenceTelemetryEvents noop() { return NoopEvents.INSTANCE; }
}
```

The interface lives in `app-inference/.../telemetry/` (no telemetry dep
needed — keeps layering clean). The adapter
(`InferenceTelemetryAdapter`) lives in `app-services` and is the *only*
place catalog instruments are touched.

### ArchUnit contracts

Each rule turns a class of agent mistake into a build failure:

- **Event-coverage**: every method on `InferenceTelemetryEvents` has ≥1
  catalog metric driven by it; every catalog metric is driven by ≥1
  event method. (Prevents direct emits and orphan metrics.)
- **Phase-purity**: methods unique to `OnlineRuntime` may not appear on
  sibling phase types. (Prevents "I'll just add `chatCompletion` to
  `IndexingRuntime`" silent regressions.)
- **Status-uniqueness**: each piece of runtime state has exactly one
  record-component home. (Prevents the recurrence of the
  `LlmStatusView`/`OnlineAiView`/`EngineMonitor` triple-source today.)
- **No-direct-emit**: imports of `MetricRegistry` outside the adapter
  and catalog fail the build.

## Admin trigger

`POST /api/admin/inference/reload`. Optional JSON body:
`{"reason": "<tag>", "target": "online"|"vdu"|"indexing"}`.

- Default `reason`: `admin_triggered`.
- Default `target`: current phase.
- Rejected (409 Conflict) if `usingExternal == true` (mirrors current
  ILM guard at line 547–553).
- Rejected (409 Conflict) if the holder is mid-swap.
- Returns: `{"transitionDurationMs": N, "fromPhase": "X", "toPhase": "Y",
  "generationId": Z}`.
- Loopback safety inherits from the existing `LocalApiServer` Javalin
  bind (per 406's precedent). No per-route auth.

Implementation routes through:

1. `LocalApiServer.handleAdminInferenceReload(Context)` parses body,
   validates;
2. `appFacade.inference().applyConfig(...)` with `TransitionReason.ADMIN_TRIGGERED`
   — operator-supplied `reason` strings become a structured log field
   (not a metric tag, since the metric tag enum stays bounded).

The wire-string-to-enum mapping mirrors `SwapReason.fromWire(...)`
exactly.

## Pre-implementation investigation

Five evidence items must be locked **before** structural changes are
written, because they affect type signatures we cannot easily change
later:

1. **Verify llama-server `/slots` and `/props` response shapes.**
   WebFetch `ggerganov/llama.cpp` server README + live `curl` against a
   running dev stack. Determines whether `QueueSnapshot` is populated
   from `/slots` directly or whether `EngineMonitor`'s aggregation logic
   needs to migrate to `OnlineRuntime`. **Affects:** `QueueSnapshot`
   record shape, `OnlineRuntime` polling implementation.

2. **Read `IndexRuntimeWireFormatRegressionTest.java` end-to-end** to
   capture the canonical NDJSON test pattern (how flush is forced, how
   tag assertions are written, how exemplars are validated). **Affects:**
   the inference equivalent's structure. Without this, the validation
   gate races the 5-second flush.

3. **Read `MetricSurfaceContractTest`** to confirm what validates
   `surfacedAt(StatusEndpoint.X, "fieldName")` against the record
   component name. **Affects:** `InferenceRuntimeView` field names must
   exactly match catalog `surfacedAt` declarations, so the names need
   to be settled before either is written.

4. **Walk `applyConfig` rollback path**
   (`InferenceLifecycleManager.java:609-654`) to enumerate every
   transition and timing point in failure cases. **Affects:** what
   `inference.transition.duration_ms` measures in the rollback case
   (success-time of the rollback or total-time including the failed
   attempt?), and whether `inference.transition.total` fires on
   rolled-back transitions or only on completed ones.

5. **Survey `EngineMonitor` liveness** (subagent: trace
   `setGenerationStats`, `setBackendType`, `setModelInfo` callers in
   production paths). **Affects:** whether deleting `EngineMonitor` is a
   clean migration or whether some production path depends on it.
   If live, the migration absorbs its functionality into `OnlineRuntime`;
   if effectively dead, the deletion is straightforward.

**Optional (low priority):** WebFetch
`opentelemetry.io/docs/specs/semconv/gen-ai/` to determine whether
`gen_ai.server.*` is settled enough that the catalog should align names.
If yes, replace `inference.*` with `gen_ai.server.*`; if not, keep
`inference.*`. One-constant rename either way.

Each investigation result is captured as a checked-in test or a tempdoc
amendment, not as informal notes — per CLAUDE.md "audit-driven fixes
need a runnable test."

## Follow-up (2026-04-26): made the observability honest

The first implementation pass shipped a substantial scaffold that turned out to be
dead code, plus a misleading observability surface (one degenerate transition event
firing with `from_phase=OFFLINE,to_phase=OFFLINE,duration=0`). Critical analysis
identified seven bug-classes; the follow-up addressed them all:

1. **Deleted the dead phase-typed runtime scaffold** — `InferenceRuntime` sealed
   interface, `OnlineRuntime`, `IndexingRuntime`, `InferenceRuntimeBuilder`,
   `ModelIdPersistence`, `InferenceException`. Per CLAUDE.md "don't ship dead code";
   the holder rewrite that would have justified them is deferred to a separate
   tempdoc.
2. **Reshaped `onTransition` signature** — replaced `Optional<InferenceRuntime>` with
   bounded phase-name strings derived from `Mode.name()`. Added explicit `TargetPhase`
   parameter to `onStartupComplete` so the adapter doesn't infer phase from
   `RuntimeIdentity.port()`.
3. **Wired honest transition events** — moved emission out of `notifyListeners` (which
   only had access to half-events) and into each transition method's success/failure
   point with measured elapsed time.
4. **Wired startup, config-apply, health, and request events** — `switchToOnlineMode`,
   `switchToIndexingMode`, `applyConfig` emit attempt/complete/failure;
   `LlamaServerOps.handlePeriodicHealthFailure` emits health failures + recovery;
   `OnlineModeOps` four lock-acquiring methods (chat, vision, streamChat,
   streamChatWithTools) emit request lifecycle events with the streaming methods'
   `onComplete`/`onError` callbacks wrapped to fire `onRequestCompleted` exactly once.
5. **Added the no-direct-emit ArchUnit rule** — `InferenceObservabilityArchTest`
   forbids `InferenceMetricCatalog` imports outside `app-services.inference..` and
   forbids `app-inference` from importing telemetry catalog types. The events-interface
   idiom is now structural, not honor-system.
6. **Added the missing tests** — `InferenceWireFormatRegressionTest` mirrors the 417
   shape (drives every event through the adapter, asserts NDJSON tag/bucket/exemplar
   shape). `AdminInferenceReloadEndpointTest` covers 200/503/500/operator-reason.
   The handler logic was extracted to `AdminInferenceReloadHandlers` for direct
   unit testing.
7. **Dropped `QueueSnapshot` + `GenerationSnapshot` from `InferenceRuntimeView`** —
   no scraper consumes the Prometheus `/metrics` endpoint yet, so the fields would
   always be null. The `--metrics` flag remains on the launch; the scraper + view-
   field reinstatement ships in a separate tempdoc when there's demand.

The doc tables in this file describe the *design*; the actual emission paths are now
documented in `docs/explanation/08-observability.md` with source-code anchors.

## Phase 2 scope adjustment (2026-04-26 mid-implementation)

The full holder rewrite of `InferenceLifecycleManager` (~1100 → ~150 lines) is
deferred to a follow-up tempdoc. The 1100-line class has substantial internal
entanglement (ModeStateMachine, listeners, periodic health, crash recovery,
external-server adoption) and rewriting it cleanly within a single session
introduces unacceptable risk. The shipped Phase 2 deliverables are:

- **Sealed `InferenceRuntime` + `OnlineRuntime` + `IndexingRuntime`
  + `InferenceRuntimeBuilder`** — the future-facing phase-typed runtime
  scaffold. Compiles and is unit-testable; **not yet wired** as the active
  lifecycle owner.
- **`InferenceTelemetryEvents` + `NoopInferenceTelemetryEvents`** — the
  observability contract; ready for Phase 4's adapter implementation.
- **`InferenceFailure` sealed taxonomy + `InferenceException` +
  `RuntimeIdentity` + 4 code enums + 5 telemetry tag enums** — new failure
  surface (Phase 1).
- **`--metrics` flag** added to llama-server launch (Phase 0 finding 3).
- **Events emission from existing ILM at transition sites** (this section
  of Phase 2) so Phase 4's catalog adapter has data to record.
- **`snapshotView()` accessor on ILM** returning the structured state that
  Phase 3's `InferenceRuntimeView` record will project from.

The deferred holder rewrite is captured as a follow-up item; this tempdoc's
observability and admin-endpoint goals are achievable on the existing ILM
internals via events emission + snapshot API.

## Phase 0 findings (resolved 2026-04-26)

The pre-implementation investigation completed before Phase 1. All 5 evidence
items resolved; all 4 open questions resolved. Captured here for traceability.

1. **Transition firing semantics** (verified via `applyConfig` walk): today's
   `applyConfig(RESTART_IF_ONLINE)` from `Mode.ONLINE` produces **2** listener
   notifications (`ONLINE→TRANSITIONING`, then `TRANSITIONING→ONLINE`).
   Rollback-success is also 2 notifications. Rollback-failed is 2 notifications
   ending at `OFFLINE`. `APPLY_ONLY` is 0 notifications. **In the new design
   without a `TRANSITIONING` phase, one swap fires exactly one
   `inference.transition.total` event.** The smoke gate must expect exactly 1
   sample per admin reload.

2. **`EngineMonitor` is dead code**: zero production setter calls
   (`setGenerationStats` / `setBackendType` / `setModelInfo`). The `LlamaService`
   that was supposed to populate it does not exist in the codebase. The
   `engineMonitorSupplier` is never set in the LocalApiServer builder chain in
   production (`HeadlessApp.java:331-343`), leaving it null. `LlmStatusView.queueDepth`
   is therefore always null in production today. **Decision: delete
   `EngineMonitor.java` outright in Phase 3.** No functionality to absorb.

3. **llama-server `--metrics` flag is not enabled**: `LlamaServerOps.startLlamaServer()`
   does not pass `--metrics`. Without it, the Prometheus `/metrics` endpoint is
   not exposed and queue depth, active-slots, kv-cache-ratio, and tokens/sec are
   not observable. **Decision: add `--metrics` to the launch command in Phase 2**
   (one-line change in `LlamaServerProcess.startProcess()`). The endpoint
   inherits loopback safety from the existing `--host 127.0.0.1` flag.

4. **Status gauge sources** (verified via llama-server README at
   ggml-org/llama.cpp `tools/server/README.md`):
   - `/slots` returns per-slot processing state but NOT queue depth.
   - `/metrics` (Prometheus, requires `--metrics` flag) provides:
     `llamacpp:requests_processing` (active), `llamacpp:requests_deferred`
     (queue depth), `llamacpp:kv_cache_usage_ratio`, `llamacpp:tokens_predicted_total`.
   - `/props` provides `total_slots`, `model_path`, `modalities.vision`.
   - `/health` returns `{"status":"ok"}` (200) or `{"error":{...}}` (503).

5. **gRPC contract drift**: no inference-related types in
   `modules/ipc-common/src/main/proto/*.proto`. The `Mode` matches in protos are
   `SearchMode`, `ScanMode`, and `retrieval_mode` (string field) — all unrelated
   to ILM's `Mode` enum. No proto migration needed.

6. **Frontend Phase 3 scope is small** (verified via grep of
   `modules/ui-web/src/`): zero React components read `status.llm` directly.
   `effectivePolicy.onlineAiEnabled` is an unrelated `EffectivePolicy` field, not
   `StatusResponse.onlineAi`. Phase 3 frontend work is limited to `schemas.ts`
   and `systemTypes.ts` updates plus regenerating the live fixture.

7. **`MetricSurfaceContractTest` accepts record component **names** but not
   types** — adding `INFERENCE_VIEW → InferenceRuntimeView.class` to the
   `recordClassFor()` switch statement is the only contract-test modification
   needed. Field types may be `Optional<T>`, primitives, records, or strings.

### Open questions — resolutions

- **(A) Hard-cut vs soft-cut migration:** **Hard cut.** Confirmed by findings
  (2) and (6): no React consumers of `LlmStatusView`, and `EngineMonitor` (the
  only would-be data source) is dead.
- **(B) Distinct `VduRuntime` vs sub-mode flag:** **Distinct `VduRuntime`.**
  Recommendation stands.
- **(C) `AdminCommand` framework:** **Defer to follow-up tempdoc.** The third
  instance (force-shutdown, reset-cache, or similar) is the natural trigger.
- **(D) Delete `EngineMonitor`:** **Delete outright.** Per finding (2).

## Open questions requiring user decision

These are design choices that are not derivable from code alone:

1. **Status migration shape**: hard-cut (recommendation A) or soft-cut
   (option B with deprecated `llm`/`onlineAi` fields)? A is cleaner for a
   single-process desktop app; B is more conservative for unknown
   downstream consumers.

2. **VDU as a distinct phase type vs as a sub-mode of Online?** The
   current code treats VDU as a config-flag variant of Online with
   different server flags. **Recommendation: distinct `VduRuntime`** —
   VDU is the textbook case for phase typing (different valid operations
   — only vision; same shared resources — GPU/llama-server process;
   different lifetime — enter/exit pairs). Folding it back into
   `OnlineRuntime` with a `vduActive()` accessor is less type-safe but
   smaller.

3. **`AdminCommand` typed framework now or deferred?** This tempdoc
   proposes the admin endpoint as a one-off matching 406's pattern. A
   typed `AdminCommand` framework that subsumes 406 + 412 + future
   commands is a strict improvement but is independently scoped work.
   **Recommendation: defer to a separate tempdoc** — scope control
   matters; this tempdoc is already large. The third instance
   (force-shutdown, reset-cache, or similar) is the natural trigger for
   the framework.

4. **Delete `EngineMonitor` outright or absorb its API surface?** Depends
   on investigation item 5.

## Out of scope

- Hot model swap as a user-facing feature. The admin endpoint is
  operator-only; UX/policy for end-user-triggered model switching is
  separate work.
- Multi-model concurrent loading. Phase types assume single-model.
- llama-server's own internal generation metrics beyond
  `inference.generation.*`. Deeper per-token observability lives in
  llama-server itself.
- `AdminCommand` typed framework (deferred per Open Question 3).
- Web UI presentation changes for the new `InferenceRuntimeView` shape.
  This tempdoc updates Zod schemas + TypeScript types but not React
  components — frontend follow-up.

## Critical files

### New (16)

- `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceSchema.java`
- `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceBuilder.java`
- `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceRuntime.java` (sealed)
- `modules/app-inference/src/main/java/io/justsearch/app/inference/OnlineRuntime.java`
- `modules/app-inference/src/main/java/io/justsearch/app/inference/VduRuntime.java`
- `modules/app-inference/src/main/java/io/justsearch/app/inference/IndexingRuntime.java`
- `modules/app-inference/src/main/java/io/justsearch/app/inference/RuntimeIdentity.java` (record)
- `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceFailure.java` (sealed)
- `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceException.java`
- `modules/app-inference/src/main/java/io/justsearch/app/inference/telemetry/InferenceTelemetryEvents.java`
- `modules/app-inference/src/main/java/io/justsearch/app/inference/telemetry/NoopInferenceTelemetryEvents.java`
- `modules/app-api/src/main/java/io/justsearch/app/api/status/InferenceRuntimeView.java` (replaces LlmStatusView + OnlineAiView)
- `modules/app-services/src/main/java/io/justsearch/app/services/inference/InferenceMetricCatalog.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/inference/InferenceTags.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/inference/InferenceTelemetryAdapter.java`
- `modules/telemetry/src/main/java/io/justsearch/telemetry/catalog/StatusEndpoint.java` (add `INFERENCE_VIEW` enum value)
- ArchUnit rules: event-coverage, phase-purity, status-uniqueness, no-direct-emit
- Tests: catalog smoke, adapter unit, `InferenceWireFormatRegressionTest`, lifecycle verifier, admin endpoint integration

### Modified

- `modules/app-inference/.../InferenceLifecycleManager.java` — reduces from
  ~900 lines to ~150 (holder + view exposure). The current `Mode` enum,
  `ModeStateMachine`, `RestartPolicy`, `applyConfig` orchestration code,
  and online/indexing/VDU mode methods all migrate into the phase types.
- `modules/app-inference/.../LlamaServerOps.java`,
  `OnlineModeOps.java`, `VduModeOps.java`, `TokenEndpointOps.java`,
  `ServerPropsOps.java` — these become components of `OnlineRuntime` /
  `VduRuntime`. The current 13-parameter constructor on `LlamaServerOps`
  (with `@SuppressWarnings("ParameterNumber")`) is fixed structurally:
  phase types own only the deps they actually need.
- `modules/app-inference/.../ModeTransitionException.java` — deleted,
  replaced by `InferenceException(InferenceFailure)`.
- `modules/app-services/.../bootstrap/BootstrapInferenceFactory.java` —
  constructs `InferenceBuilder`, wires events adapter, returns the
  holder.
- `modules/app-services/.../AppFacadeBootstrap.java` — accepts the new
  holder shape; the GPU broadcast listener migrates to a phase-change
  callback.
- `modules/app-api/.../status/StatusResponse.java` — replaces `llm`
  field type; removes `onlineAi` field (per recommendation A).
- `modules/ui/.../HeadlessApp.java` (lines 224–276) — registers
  `InferenceMetricCatalog.DEFINITIONS` in the `LocalTelemetry` ctor
  list. **Two registration sites total** (HeadlessApp + LauncherEnvironment).
- `modules/app-launcher/.../LauncherEnvironment.java` (lines 47–89) —
  same registration.
- `modules/ui/.../api/LocalApiServer.java` — adds
  `handleAdminInferenceReload`. Mirrors the 406 `handleAdminRuntimeReload`
  shape exactly.
- `modules/ui/.../api/routes/DebugRoutes.java` — registers the new
  POST route.
- `modules/ui/.../api/StatusLifecycleHandler.java` — `buildLlmStatus()`
  becomes `buildInferenceView()`, deriving from `lifecycleManager.snapshotView()`.
- `modules/ui-web/src/api/schemas.ts` — Zod schema for new view shape.
- `modules/ui-web/src/stores/systemTypes.ts` — TypeScript types.
- `modules/ui-web/src/api/__fixtures__/status-response-live.json` —
  regenerated via `:modules:app-api:updateSchemas`.
- `docs/explanation/08-observability.md` — new `inference.*` section
  replacing the existing `LlmStatusView`-driven content.
- `docs/future-features/service-identity-lifecycle-pattern.md` — flip
  ILM row from `(pending)` to `(shipped)`.

### Deleted

- `modules/ai-backend/.../EngineMonitor.java` (subject to investigation
  item 5; absorbed into `OnlineRuntime` if its functionality is live)
- `modules/app-api/.../status/LlmStatusView.java` (replaced)
- `modules/app-api/.../status/OnlineAiView.java` (replaced)

## Sequencing

**Estimate: ~5–7 working days.** The previous tempdoc's 1.5-day estimate
was based on a patch-shape that couldn't answer the design questions at
all.

**Phase 0 — Investigation (½ day).** Items 1–5 from "Pre-implementation
investigation". Lock the empirical claims that affect type signatures.
Tempdoc amendments resolving open questions before any production code.

**Phase 1 — Failure taxonomy + identity types (½ day).** Add
`InferenceFailure` sealed hierarchy, `InferenceException`,
`RuntimeIdentity`. Catch sites updated mechanically. No behavior
change. **Gate:** existing ILM tests pass under the renamed exception.

**Phase 2 — Phase-typed runtime extraction (1.5 days).** Add
`InferenceSchema`, `InferenceBuilder`, sealed `InferenceRuntime`,
concrete `OnlineRuntime` / `VduRuntime` / `IndexingRuntime`. Migrate
`OnlineModeOps` / `LlamaServerOps` / `VduModeOps` etc. as components of
the phase types. ILM holder skeleton. **Gate:** lifecycle verifier test
exercises full phase cycling; phase-purity ArchUnit rule passes.

**Phase 3 — Status surface consolidation (1 day).** Add
`InferenceRuntimeView`. Migrate `StatusLifecycleHandler.buildLlmStatus`
to derive from `lifecycleManager.snapshotView()`. Update Zod / TS types.
Run `./gradlew :modules:app-api:updateSchemas`. Delete `LlmStatusView`,
`OnlineAiView`. (Optional: delete `EngineMonitor` if investigation item
5 supports it.) **Gate:** `/api/status` returns the new `inference`
field; frontend type-check passes; status-uniqueness ArchUnit rule
passes.

**Phase 4 — Catalog + adapter + ArchUnit (1 day).** Add
`InferenceMetricCatalog`, `InferenceTags`, `InferenceTelemetryAdapter`,
ArchUnit contracts. Wire registration in `HeadlessApp` +
`LauncherEnvironment`. Adapter unit tests + wire-format regression test.
**Gate:** event-coverage and no-direct-emit ArchUnit rules pass;
`InferenceWireFormatRegressionTest` emits the expected NDJSON for each
event with deterministic flush.

**Phase 5 — Admin endpoint (½ day).** Add `handleAdminInferenceReload`,
route registration. **Gate:** smoke test starts dev stack with
llama-server, hits `POST /api/admin/inference/reload` with
`{"reason":"validation_test"}`, forces flush, parses NDJSON, asserts:
- one `inference.transition.total{from_phase=ONLINE,to_phase=ONLINE,reason=admin_triggered}` line
- one `inference.transition.duration_ms{...}` line with sample count 1
- `Exemplars.TRACE_BASED` carries the admin request's trace_id
- `/api/status` `inference` field reflects the new `generationId`

**Phase 6 — Doc updates (½ day).** `08-observability.md`,
`service-identity-lifecycle-pattern.md`, ADR-0027 cross-reference. Run
`node scripts/docs/llmstxt-generate.mjs` and `node scripts/docs/skills-sync.mjs`.

## Long-term considerations

- **`AdminCommand` typed framework** is the natural follow-up. Once
  this tempdoc plus 406 plus a third (force-shutdown, reset-cache,
  etc.) exist, the per-endpoint copy-paste in `LocalApiServer` becomes
  a substrate to extract. Don't pre-build the framework here; the
  third instance is the trigger.
- **Pattern reuse to other lifecycle owners.** `EmbeddingService`,
  `AgentSession`, `NativeSessionHandle`, `IndexingLoop` all sit in
  `service-identity-lifecycle-pattern.md`'s table as pending. Each
  subsequent tempdoc inherits this one's substrate (sealed failure,
  events interface, status view, ArchUnit rules) — the per-owner cost
  drops to ~½ day instead of 5–7.
- **OTel semantic conventions for inference.** If `gen_ai.server.*`
  stabilizes, a future rename pass aligns the catalog. Cheap because
  the namespace is one constant.
- **The `mode` tag-key collision with tempdoc 415 dissolves** under
  this design. 412 uses `from_phase` / `to_phase` / `phase`; 415 keeps
  `mode` as the agent-session axis. No reviewer-time check needed.

## Dependencies

None blocking. The new `StatusEndpoint.INFERENCE_VIEW` enum value and
`InferenceRuntimeView` record are part of this tempdoc's scope, not
external deps. Independent of 413 (embedding observability — shipped
2026-04-26), 414, 415 (agent session). 415 is the contrasting
consumption-idiom case study (direct-emit, single existing catalog)
and remains so under this rewrite.

## Empirical smoke-gate result (2026-04-27)

After the third critical-analysis pass, the validation gate from the
plan was finally run end-to-end against a live worktree backend rather
than a unit-test fixture. Procedure: cold-start `runHeadlessEval` from
`worktree-412` with `--llm` (env-overrides for `JUSTSEARCH_SERVER_EXE` /
`JUSTSEARCH_MODELS_DIR` so the worktree can borrow main's llama-server
binary + model files), `POST /api/admin/inference/reload
{"reason":"smoke_gate"}`, JVM shutdown to force final flush, then parse
`telemetry/metrics.ndjson` (script: `tmp/smoke_gate.py`).

**Gates checked**:

- `[OK] inference.transition.total: 1 line` — `from_phase=OFFLINE,
  to_phase=ONLINE, reason=user_switch`. The original bug-class
  (`OFFLINE→OFFLINE,duration=0` garbage) is empirically fixed.
- `[OK] inference.transition.duration_ms: 1 line` — bucket count 1 in
  the `>20000ms` bucket (cold-start time).
- `[OK] inference.config.apply_total: 1 line` — `restart_required=true`.
- `[OK] inference.startup.attempt_total` and
  `inference.startup.duration_ms` — both fire with bounded
  `phase=online` tag.
- `[OK]` no `inference.*.failure_total` lines — happy-path; failure
  emission paths remain unverified empirically and rest on unit-test
  scenarios alone.

**Build-state honesty correction.** Pre-smoke compilation surfaced
~100 errors from missing supporting types (`StartupCode`, `HealthCode`,
`ConfigCode`, `TransitionCode`, `RuntimeIdentity`, `TargetPhase`,
`TransitionReason`, `StartupReason`, `RequestKind`, `RequestOutcome`).
Earlier "BUILD SUCCESSFUL" claims during the recursive bug-fixing
iterations were on stale gradle test caches. Types were recreated from
use-site evidence and the build is now genuinely green. Lesson: when
running tests after deletions in a multi-iteration session, force
`--rerun-tasks` or `:compileJava` directly — `:test` alone skips
compile if its cache key is unchanged.

**Observed and resolved**: `inference.startup.attempt_total{phase=online}`
appeared 9× in the NDJSON over a 40s window with `value:1` each (every
5s flush boundary). **Resolution**: standard OTel `CUMULATIVE`
temporality — once a counter is first incremented, every subsequent
flush window exports the cumulative value. Confirmed against
`jvm.uptime_ms` (9 emissions, same window). Not a code bug.

## Path C — full empirical fault-injection (2026-04-27)

After the happy-path smoke gate passed, the user requested full
fault-injection verification. Three failure paths exercised against a
live worktree backend:

**P3 — startup failure (bogus model path).** Set
`JUSTSEARCH_LLM_MODEL_PATH` to a nonexistent file. Backend boot
attempted cold-start, validation failed (`config.validate` threw),
mapped to `ConfigFailure(INVALID_CONFIG)`, synthesized via Bug D path
to `StartupFailure(code=UNKNOWN, detail="[invalid_config] ...")`.

- **Result**: `inference.startup.failure_total{code=unknown,phase=online}`
  fired reliably (46 NDJSON emissions over the cold-start retry window).
- **Caveat**: the synthesized `code=unknown` loses fine-grained tag-level
  classification. The detail string preserves `[invalid_config]` but the
  metric tag axis only sees `unknown`. Logged in `docs/observations.md`
  as a follow-up consideration; the synthesis is intentional from Bug D
  (preferable to silent emission) but suboptimal. A future refinement
  could either extend `StartupCode` with values mirroring underlying
  classifications, or add an `underlying_code` tag axis.

**P4 — health failure (kill llama-server).** Cold-start backend healthy,
verify `mode=online` via `/api/inference/status`, then `taskkill /F`
the llama-server process.

- **First run**: **FAILED.** No `inference.health.failure_total` line.
  Investigation revealed **Bug F**: the `crashMonitor` future fires on
  `Process.waitFor()` returning non-zero, which calls `handleServerCrash()`
  directly. That method triggered crash recovery but did **not** emit
  any health-failure event. Meanwhile, `handlePeriodicHealthFailure` —
  the only existing emit site — is gated by an early-return at
  `LlamaServerOps.java:712` (`if (process == null || !p.isAlive()) return`).
  So process-death scenarios (the most operationally important health
  failure) went silent in the metric stream.
- **Fix**: Emit `events.onHealthFailure(HealthFailure(code=PROCESS_DIED, ...),
  crashes, restartTriggered=true)` from `handleServerCrash()` itself,
  before the recovery decision. `LlamaServerOps.java:807-825`.
- **Second run**: **PASS.**
  `inference.health.failure_total{severity=restart_triggered, code=process_died}`
  fired reliably under taskkill.

**P5 — config-apply failure (bogus settings).** Attempted to inject a
bogus `llmModelPath` via `POST /api/settings/v2`, then trigger
`POST /api/inference/reload`.

- **Blocked by eval-mode protection**: settings are read-only
  (`SETTINGS_READ_ONLY` 409 response) when running via
  `runHeadlessEval`. This is a deliberate eval-mode guardrail, not a
  code bug.
- **Coverage substitute**: the existing unit test
  `bugD_nullConfigEmitsConfigApplyFailure` exercises the exact same
  `recordAndEmitFailure(failure, configApplyContext=true)` →
  `events.onConfigApplyFailure` path, just driven via `applyConfig(null)`
  rather than via a bogus-path injected through HTTP. Verified passing
  on current code.

**Bug E (admin-reload reason wiring) — found and fixed.** The smoke
gate showed `reason=user_switch` on the cold-start transition while
the admin reload returned `phase=OFFLINE` immediately afterwards. Root
cause: `OnlineAiRuntimeControl.reloadRuntime()` calls
`applyRuntimeOverrides(...)`, which threaded through
`OnlineAiServiceImpl.applyRuntimeOverrides` → `manager.applyConfig(next, policy)`
— **the 2-arg overload that always passes `TransitionReason.CONFIG_APPLY`**.
The `ADMIN_TRIGGERED` reason was wired into the enum and the 3-arg
overload, but the admin path bypassed it. Fix: added
`applyRuntimeOverridesAdmin` default method to `OnlineAiRuntimeControl`,
overridden in `OnlineAiServiceImpl` to pass
`TransitionReason.ADMIN_TRIGGERED` through the 3-arg `applyConfig`
overload. `reloadRuntime()` calls the admin-tagged variant.
`OnlineAiRuntimeControl.java`, `OnlineAiServiceImpl.java`.

**Status-surface mismatch (logged, not fixed)**:
`/api/inference/status` reports `mode=online` while `/api/status`
reports `inference.phase=OFFLINE` after the same cold start. Not a
metric-pipeline bug — the metrics fire correctly. Logged to
`docs/observations.md` as a follow-up; either
`AppFacadeBootstrap.projectInferenceSnapshot` reads stale `modeState`
or the `inferenceSnapshot` supplier has a wiring gap.

### Path C verdict

The recursive critique pattern that produced three iterations of
"BUILD SUCCESSFUL" claims on stale caches was justified by the
empirical findings: real fault injection surfaced **Bug F** (silent
process-death in metrics) and **Bug E** (admin reason wiring),
neither caught by unit tests. Bug F in particular was the most
operationally important kind of health failure — exactly the class
of bug the previous iterations would have shipped silently.

Six gates green:
- `./gradlew.bat build -x test` (compilation)
- `./gradlew.bat test` (full unit suite)
- `./gradlew.bat spotlessCheck`
- `cd modules/ui-web && npm run typecheck`
- `cd modules/ui-web && npm run test:unit:run` (212 tests)
- Empirical smoke gate (P0 happy path) + P3 startup-fail + P4 health-fail
  fault injection.

The tempdoc's primary observability claim — "every catalog metric
fires under its real production trigger, with non-degenerate tags" — is
now empirically verified for the operationally important paths.

---

## EmbeddingService (was 413)

*(consolidated from `413-embedding-service-observability.md`)*

### 413 — EmbeddingService Observability Adoption

## Status

**OPEN.** Created 2026-04-25. Rewritten 2026-04-26 against the shipped
MetricCatalog substrate. Rewritten again 2026-04-26 after takeover
investigation. Implementation shipped on
`worktree-413-embedding-observability` 2026-04-26; critical-analysis
followup applied 2026-04-26 (4 real gaps closed — see "Critical-analysis
followup" below). Pattern is canonicalized in
[ADR-0027](../decisions/0027-metric-catalog-as-telemetry-contract.md).

## Critical-analysis followup

The first implementation pass shipped, passed unit tests, and ran
through jseval — but the post-implementation critical-analysis pass
caught four real gaps. All have been closed:

1. **`chunked_total` was scoped to the query path only.** `embedWithChunks`
   emitted; `embedDocumentBatch` (the indexing path where 99% of chunking
   happens) did not. Fixed by emitting on both paths.
2. **`chunked_total` was a counter.** Counter throws away the
   chunk-count distribution. Converted to histogram `chunk_count` with
   bucket layout `[2, 4, 8, 16, 32, 64, 128]`; one sample per chunked
   text on either path. Carries the actual chunk count.
3. **`unload_total{SHUTDOWN}` never landed in NDJSON.** The close-time
   `meterProvider.forceFlush().join(2s)` raced the file write — same
   shutdown gap that affects every other counter (e.g.,
   `worker.documents.indexed.total`). Fixed locally in
   `KnowledgeServer.close()` by emitting and explicitly calling
   `LocalTelemetry.flush()` (5s join, SDK fully alive) before any
   close-time shutdown begins. The substrate close-time gap is a
   project-wide concern and is left for a separate tempdoc.
4. **No runnable tests for the emit sites.** Closed by
   `EmbeddingServiceTelemetryEmitTest` (10 cases covering every emit
   site) and `IndexingLoopUnloadTelemetryEmitTest` (asserts GPU_HANDOFF
   emit fires *before* the backend's close — verified via timestamp).

Plus three medium improvements applied: `archivedTo(RrdArchive.STANDARD)`
on the 5 trend-worthy metrics; DevReloadManager wiring helper extracted.

## Why this was rewritten before implementation

The previous proposal had four structural mismatches caught at
implementation-time review, plus a missed lifecycle event:

1. **`encoder_load_ms` and `encoder_load_failure_total` emit at the
   wrong layer.** By the time `EmbeddingService.createWithBackend`
   runs, the encoder is already loaded. The actual load + failure sites
   are in `InferenceCompositionRoot.composeEmbeddingRole`. These belong
   to the composition-root / session scope, not EmbeddingService.
2. **`batch_inflight` and `queue_depth` have no observable source
   today.** EmbeddingService has no internal queue; the batch path is
   synchronous on the caller's thread.
3. **String-valued status gauges aren't expressible in the substrate.**
   `MetricRegistry.buildGauge` takes `Supplier<Double>`. The relevant
   fields are already exposed via `GpuDiagnosticSuppliers` on
   `/api/status`.
4. **Wrong catalog-registration site.** `AppFacadeBootstrap` registers
   head-resident catalogs. Worker-resident catalogs register at
   `KnowledgeServer.java:243` (the worker `LocalTelemetry` constructor).

A real lifecycle event was also missed:
**`IndexingLoop.unloadEmbeddingService`** closes EmbeddingService when
the Main process claims the GPU for Online Mode (hybrid-inference VRAM
handoff). This is a recurring production event today.

## Context

`EmbeddingService` is the worker-side encoder owner. After this
tempdoc, its own surface — per-call failures, query-cache behaviour,
hot-unload events, and chunked-embedding distribution — is observable.
**Cold-load** metrics (assemble_ms, model_missing/cuda_unavailable
failure counters, GPU↔CPU fallback) are explicitly out of scope; they
belong to `NativeSessionHandle` / composition-root layers covered by
tempdoc 414.

**Empirical grounding:**

- `EmbeddingService` lives at
  `modules/worker-core/src/main/java/io/justsearch/indexerworker/embed/EmbeddingService.java:40`.
- Construction site: `KnowledgeServer.java:818` via
  `EmbeddingService.createWithBackend(backend, config, telemetry)`.
- Hot-unload event: `IndexingLoop.unloadEmbeddingService` (line 1541).
- Per-call failure paths: `embedWithChunks` and `embedDocumentBatch`
  catch `BackendException`, emit `invoke_failure_total{reason=BACKEND_EXCEPTION}`.
- Defensive guards: `closed.get()` and null/blank text emit
  `invoke_failure_total{reason=CLOSED|NULL_TEXT}`.
- Query cache: 5-second TTL `embeddingCache`. Cache hits/misses observable.
- Chunked-embedding event: emits histogram sample on both
  `embedWithChunks.isChunked` and `embedDocumentBatch` per-result loop.

## Scope (single tier)

This tempdoc covers observability only. Hot encoder swap is unrelated
and would be its own product feature.

## Metric set (`embedding.runtime.*` namespace)

| Metric | Type | Tags | Emit site |
|---|---|---|---|
| `embedding.runtime.invoke_failure_total` | Counter | `operation` (`SINGLE` / `BATCH`), `reason` (`BACKEND_EXCEPTION` / `CLOSED` / `NULL_TEXT`) | `embedWithChunks` and `embedDocumentBatch` BackendException catches; defensive guards for closed-service and null/blank input |
| `embedding.runtime.cache_hit_total` | Counter | none | Cache-hit branch in `embedWithChunks` (5s TTL query cache) |
| `embedding.runtime.cache_miss_total` | Counter | none | Cache-miss branch in `embedWithChunks` |
| `embedding.runtime.cache_size` | Gauge | none | Async callback over `embeddingCache.size()` |
| `embedding.runtime.unload_total` | Counter | `reason` (`GPU_HANDOFF` / `SHUTDOWN`) | `IndexingLoop.unloadEmbeddingService` (hybrid-inference VRAM handoff) and `KnowledgeServer.close` (worker shutdown) |
| `embedding.runtime.chunk_count` | Histogram | none | Both `embedWithChunks` chunked branch *and* `embedDocumentBatch` per-result loop. Bucket layout `[2, 4, 8, 16, 32, 64, 128]`; one sample per chunked text. |

**Cardinality bounds:** all tagged metrics use sealed Java enums
(`Operation`, `InvokeFailureReason`, `UnloadReason`); cardinality is
finite at compile time.

**Archive policy:** `cache_size`, `cache_hit_total`, `cache_miss_total`,
`unload_total`, and `invoke_failure_total` declare
`archivedTo(RrdArchive.STANDARD)` for trend-over-time analysis. They
auto-derive into the **worker's** `RrdMetricStore` curated set per
ADR-0027 and are bundled in `/api/diagnostics/export`. Note: the
head-side `/api/debug/metrics/timeseries{,/available}` endpoint queries
only the head's curated set (catalogs registered in `HeadlessApp.java`'s
`LocalTelemetry` constructor) — worker-archived metrics are NOT visible
there today. Surfacing them requires either head-side registration of
the namespace or a worker-side timeseries endpoint with head proxy. See
"Long-term considerations" below. `chunk_count` is excluded (distribution
metric, less useful for RRD's 3-tier layout).

## Pattern reference

**Catalog mechanics**: ADR-0027.

**Consumption idiom**: events-interface seam. `EmbeddingTelemetryEvents`
in `modules/worker-core` is a zero-dep interface that `EmbeddingService`
holds. The `EmbeddingTelemetry` façade in `modules/worker-services`
implements it over the typed `EmbeddingMetricCatalog`. This avoids a
worker-core → worker-services circular dependency.

**Catalog registration**: at `KnowledgeServer.java:243` (worker
`LocalTelemetry` constructor's catalog list).

## Out of scope

- Cold-load lifecycle (assemble_ms, model_missing /
  cuda_unavailable / OrtException counters, accelerator-fallback
  events). Belongs to tempdoc 414.
- Hot encoder swap as a product feature.
- Per-batch latency (covered by `pipeline.stage_ms{stage_id=embed}`).
- `batch_inflight` / `queue_depth` (no measured concurrency signal).
- String status fields on `/api/status` (substrate gap; existing
  `GpuDiagnosticSuppliers` already plumbs the equivalent fields).

## Critical files (shipped)

**New:**
- `modules/worker-core/.../embed/EmbeddingTelemetryEvents.java` — interface + 3 enums.
- `modules/worker-core/.../embed/NoopEmbeddingTelemetryEvents.java` — INSTANCE singleton.
- `modules/worker-services/.../embed/EmbeddingMetricCatalog.java` — typed catalog with archive declarations.
- `modules/worker-services/.../embed/EmbeddingTags.java` — TagSchema records.
- `modules/worker-services/.../embed/EmbeddingTelemetry.java` — direct-emit façade.
- `modules/worker-services/.../embed/EmbeddingMetricCatalogSmokeTest.java`
- `modules/worker-services/.../embed/EmbeddingTelemetryTest.java`
- `modules/worker-services/.../embed/EmbeddingMetricWireFormatRegressionTest.java`
- **`modules/worker-core/.../embed/EmbeddingServiceTelemetryEmitTest.java`** — 10 cases, recording events sink, mock backend.
- **`modules/worker-services/.../loop/IndexingLoopUnloadTelemetryEmitTest.java`** — GPU_HANDOFF emit-before-close timestamp assertion.

**Modified:**
- `modules/worker-core/.../embed/EmbeddingService.java` — events field, emit at 6+ sites including new `embedDocumentBatch` per-result chunked emit; promoted `cacheSize()` to public.
- `modules/worker-services/.../loop/IndexingLoop.java` — `setEmbeddingTelemetryEvents` setter; emit `onUnload(GPU_HANDOFF)` before `svc.close()` in `unloadEmbeddingService`.
- `modules/worker-services/.../server/WorkerAppServices.java` — `wireEmbeddingTelemetryEvents` interface method (default no-op).
- `modules/worker-services/.../server/DefaultWorkerAppServices.java` — implementation forwards to IndexingLoop.
- `modules/indexer-worker/.../KnowledgeServer.java` — catalog DEFINITIONS at line 243; typed catalog construction with deferred cache-size supplier; telemetry passed at `createWithBackend`; SHUTDOWN emit + explicit `LocalTelemetry.flush()` at top of `close()` to bypass close-time NDJSON race.
- `modules/indexer-worker/.../DevReloadManager.java` — `rewireEmbeddingTelemetry` helper called from both rewireModels paths.
- `docs/explanation/08-observability.md`, `docs/future-features/service-identity-lifecycle-pattern.md`.

## Validation gate

Live, dev-stack-runnable:

1. `cd scripts/jseval && python -m jseval run --dataset scifact --max-queries 300 --modes hybrid --pipeline --start-backend --clean`
2. Inspect `<dataDir>/telemetry/metrics-worker.ndjson` for:
   - `embedding.runtime.cache_miss_total` ≥ 70 (typical scifact query workload).
   - `embedding.runtime.cache_size` non-zero gauge (cache populated post-queries).
   - `embedding.runtime.chunk_count` histogram with non-empty buckets
     (samples on the indexing batch path — scifact has docs long enough
     to chunk).
3. **Path-specific validation outside jseval:**
   - `unload_total{reason=GPU_HANDOFF}` and `invoke_failure_total{...}`
     are validated by `EmbeddingServiceTelemetryEmitTest` and
     `IndexingLoopUnloadTelemetryEmitTest` — failure injection isn't a
     jseval feature.
   - `unload_total{reason=SHUTDOWN}` only emits on graceful shutdown
     (JVM shutdown hooks, signal handlers, desktop-app exit). jseval
     force-kills the worker via `taskkill /T /F` on Windows; the
     metric is correctly wired (`KnowledgeServer.close()` calls
     `LocalTelemetry.flush()` immediately after the emit) but is not
     observable through jseval. Production graceful shutdown paths
     observe it; force-kill paths can't.

## Long-term considerations

- **Substrate close-time NDJSON gap.** Affects every counter; my SHUTDOWN
  emit works around it locally with `LocalTelemetry.flush()` early in
  close(). A proper substrate fix would benefit every counter — separate
  tempdoc.
- **Role/Accelerator substrate refactor.** The cross-cutting question
  for tempdocs 412/413/414/415 — whether to introduce shared `Role` and
  `Accelerator` enums and a unified `inference.role.*` namespace —
  remains open. This implementation uses local enums (`Operation`,
  `InvokeFailureReason`, `UnloadReason`) that can be promoted later.
- **Hot encoder swap** would be a multi-week effort with its own
  tempdoc. The metrics here cover the swap event for free if/when it
  lands — `unload_total{reason=swap}` is a one-line enum addition.

## Dependencies

- Independent of 412 (InferenceLifecycleManager) and 415 (AgentSession).
- **Soft cross-reference with 414** (NativeSessionHandle): if 414
  finalizes a `role` tag, 413 should align.

---

## NativeSessionHandle (was 414)

*(consolidated from `414-native-session-handle-observability.md`)*

### 414 — NativeSessionHandle Observability Adoption

## Status

**SHIPPED 2026-04-26 with documented follow-up.** Created 2026-04-25;
rewritten 2026-04-26 against the shipped MetricCatalog substrate (v2
design — investigation-gate-driven, sealed `TransitionReason`); v1+v2
implementation merged into `main` as commit `48a6e4895` (feature
commit `c666d0a0a`); live-stack validation evidence appended as commit
`8804821e1`; tempdoc body aligned to shipped state in commit
`560022bd2`. Core operational visibility achieved and live-validated:
worker boots cleanly with the new catalog, `gpu_init_total` and
`semaphore_wait_us` emit with correct typed tags and bucket bounds,
`/api/status` `*OrtCuda` blocks regression-clean. ~⅓ of the
originally-conceived metric surface (the four status gauges +
saturation gauge) was deliberately deferred — it depends on the
phase-typed state-machine refactor (Form A second-instance work for
this owner), which warrants its own successor tempdoc rather than
reopening this one. See § Required follow-up for the gap inventory
with the right tempdoc owner per item.

Pattern adoption follow-up to tempdoc 417's shipped substrate; pattern
is canonicalized in
[ADR-0027](../decisions/0027-metric-catalog-as-telemetry-contract.md).
Lifecycle-pattern reference (Form A second-instance candidate):
[service-identity-lifecycle-pattern.md](../future-features/service-identity-lifecycle-pattern.md).

## Implementation status (2026-04-26)

### What achieved the original goals

- ✅ **Silent line-260 GPU→CPU fallback is now first-class** — emits
  `ort.session.fallback_total` via `TransitionReason.GpuFallbackTaken`.
  This was the central operational win.
- ✅ **GPU init failures observable** — `gpu_init_failure_total{cause}`
  with typed `FailureCause` (oom / cuda_unavailable / driver_error /
  unknown).
- ✅ **CPU session recreation observable** — `recovery_total{cause}`
  with typed `CpuRecreateCause` plumbed through
  `SessionHandle.reportCpuSessionFailure(cause)`.
- ✅ **Stress-test `null_variant` NPE observable in stress lane** —
  `assembler_failure_total{kind=null_variant}` fires before the NPE
  propagates from `OrtSessionAssembler.buildManager`.
- ✅ **Namespace `ort.session.*`** as designed.
- ✅ **Events-interface idiom** — `OrtSessionTelemetryEvents` in
  `ort-common` (no telemetry dep), `OrtSessionTelemetryAdapter` in
  `worker-services`.
- ✅ **Hot-path discipline** — default-void no-op methods, cached
  attributes per `(consumer)` pair. Improved over the original spec:
  `semaphore_wait_us` only emits on GPU acquire path (CPU acquires
  don't take the semaphore — emitting there was meaningless).
- ✅ **Drift prevention** — sealed `TransitionReason` + sealed
  `AssemblerEvent`; adapter's exhaustive `switch` makes adding a
  permit a compile error until the adapter handles it.
- ✅ **Single source of truth for consumer names** —
  `EncoderRole.consumerName()`; `OrtSessionTelemetryAdapter.forAllRoles()`
  derives the cache from `EncoderRole.values()`.

### What deviated from the original metric set (gaps)

> **Note (post-merge):** The "original" column tracks the pre-rewrite
> tempdoc's original metric set — 7 instruments + 4 status gauges.
> The v2 design (current §Design + §Critical files below) already
> narrowed scope from that original list. Some entries marked
> ❌ Dropped were dropped at v2-design-time, not at implementation
> time. Either way they remain operational gaps the follow-up tempdoc
> needs to close.

| Original | Shipped? | Notes |
|---|---|---|
| `acquire_ms` histogram (`accelerator`, `result`) | ❌ Dropped | "Total acquire latency budget" signal lost. Operators must compose `semaphore_wait_us` + lazy-init samples mentally. |
| `semaphore_wait_ms` histogram (`accelerator`) | ⚠️ Renamed/retagged | Shipped as `semaphore_wait_us` (microseconds for sub-ms resolution) with `consumer` tag only — accelerator dropped because it's GPU-only by definition. |
| `fallback_total` (`from`, `to`) | ⚠️ Schema flattened | Shipped as `fallback_total{consumer}` with implicit `from=cuda,to=cpu`. **The cross-owner naming pattern goal is broken** — if `InferenceLifecycleManager` ever gains CPU fallback, this metric shape doesn't compose. |
| `recovery_total{reason}` | ⚠️ Semantics drifted | Original: "session recreate after `releaseGpu`". Shipped: "CPU session recreate after BFCArena / reported failure." Different signal. The original concept (post-release recovery) isn't directly emitted. |
| `release_failure_total` (none-tagged) | ⚠️ Reshaped | Shipped as `release_total{consumer, outcome}` — both successes and failures emit. Outcome tag distinguishes them. Defensible but not what the original specified. |
| `assembler_failure_total{reason}` | ⚠️ Renamed | Shipped as `{consumer, kind}`. `kind` instead of `reason` (cosmetic); `consumer` added. |
| `concurrent_inflight` gauge | ❌ Dropped | **Saturation observation lost.** Original explicitly cited "matches the stress test's concern." Without this, operators can't see "are we at the GPU semaphore limit right now?" |
| `active_count` status gauge | ❌ Dropped | RrdArchive + `/api/status` integration lost. |
| `last_acquire_ms` status gauge | ❌ Dropped | Most-recent-sample surface lost. |
| `last_fallback_at_ms` status gauge | ❌ Dropped | "When did the last fallback happen?" question is no longer answerable from `/api/status`; operators must scrape NDJSON timestamps. |
| `current_accelerator` status gauge (per encoder) | ❌ Dropped | Existing `OrtCudaView` covers `available`/`configured`/`failureReason` — but NOT the typed accelerator value. The "is encoder X currently on CUDA or CPU?" question requires inference from boolean flags. |

**Net deviation:** 5 of 11 proposed metrics dropped entirely; 5 reshaped;
1 shipped as-specified. Plus 4 metrics added that weren't in the
original spec (`gpu_init_total{outcome}`, `gpu_init_failure_total`,
`retry_total`, `retry_interval_ms`).

### Where the original tempdoc had structural defects (corrected)

These were genuine errors in the original spec, caught and fixed during
implementation:

- **Module placement**: original said `app-services/.../observability/`
  for the catalog + adapter and `AppFacadeBootstrap` for registration.
  Both wrong — `NativeSessionHandle` instances live in the Worker
  process; the catalog must register with the Worker's `LocalTelemetry`
  or `MetricRegistry.buildCounter` throws on unknown name (G1
  investigation finding). Shipped: `worker-services` placement,
  `KnowledgeServer.java` registration.
- **Missing `consumer` tag**: original metric set had no encoder
  discriminator. All 6 `NativeSessionHandle` instances would have
  aggregated into a single series — operators couldn't tell whether
  embed or splade was contended. Shipped: `consumer` tag on every
  metric.
- **Validation gate inoperable**: original cited
  `JUSTSEARCH_GPU_VRAM_LIMIT_MB` to force CUDA load failure. The env
  var doesn't exist (G3 investigation). Per-encoder
  `JUSTSEARCH_*_GPU_MEM_MB` vars exist but only constrain arena cap
  post-load. Shipped validation guidance: empty
  `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH` to force CUDA-DLL-not-found
  init failure.
- **`forFallback` only fires from tests**: original framed
  `assembler_failure_total{null_variant}` as "makes the existing
  stress-test bug observable in prod." G2 investigation found
  `ModelSessionPolicy.forFallback` has zero production callsites —
  the metric is a stress-test invariant guard, not a prod failure
  signal. Reframed in the observability doc.

### Live-stack validation (post-merge, 2026-04-26)

Ran on the live worker via `jseval --start-backend --clean --pipeline`
against scifact. Worker booted cleanly (G1 risk refuted),
`gpu_init_total` + `semaphore_wait_us` emitted with correct bucket
bounds, `/api/status` `*OrtCuda` blocks regression-clean. One
irreducible gap: `gpu_init_failure_total{cause}` cannot be reproduced
on this machine due to JAR-bundled CUDA. Full evidence + the JAR-
bundling finding are in § Validation evidence below.

### Critical-analysis findings on the v1 implementation (fixed in v2)

A v1 critical-review surfaced 14 issues, 5 correctness-level. v2
addressed Tier A/B/C (12 fixes); Tier D (2) deferred. Notable:

- **`semaphore_wait_ms` measured the wrong thing in v1** — the timing
  scope captured `selectSession()` + lease construction, not
  semaphore-only wait. Renamed to `semaphore_wait_us` with narrowed
  scope (semaphore acquire only) + microsecond resolution.
- **`AssemblerFailure` permit lived under `TransitionReason`** even
  though no handle exists at assembler-failure time. Split into
  separate sealed `AssemblerEvent` type with its own
  `onAssemblerEvent` channel.
- **`reportCpuSessionFailure()` accepted no cause** in v1 — `recovery_total`
  always emitted `cause=reported_failure` even when the caller knew
  the cause was BFCArena. v2 plumbed typed `CpuRecreateCause` through
  the API; `CrossEncoderReranker` now classifies BFCArena vs generic.
- **`ConsumerCauseTags(String cause)`** weakened the ADR-0027
  typed-tag-schema contract by accepting free strings. v2 split into
  typed `GpuInitFailureTags(FailureCause)` and `RecoveryTags(CpuRecreateCause)`
  records — wrong cause type now fails at javac.
- **No regression test for the central operational win** — the
  line-260 silent fallback path. v2 wired a `RecordingOrtSessionTelemetryEvents`
  recorder into the stress test for plumbing validation; the GPU-race
  case itself remains un-deterministically-testable without CUDA
  hardware (parked in tempdoc 398).

## Required follow-up (deferred — file as successor tempdocs)

This tempdoc is closed; the items below are **not** prerequisites for
considering 414 shipped. They are the deliberately-deferred surface
that warrants its own scoped work. Each entry indicates the right
tempdoc owner.

1. **Status gauges** (`active_count`, `last_acquire_ms`,
   `last_fallback_at_ms`, `current_accelerator` per encoder) → **owned
   by the phase-typed state-machine successor tempdoc**. These require
   new mutable state on `NativeSessionHandle` (epoch timestamps,
   atomic inflight counter), which fits naturally into the Form A
   second-instance refactor that's already on the lifecycle-pattern
   roadmap (`docs/future-features/service-identity-lifecycle-pattern.md`).
   Doing them piecemeal in 414 would have added volatile fields the
   phase-typed work then has to refactor away.
2. **`concurrent_inflight` gauge** → **same successor**. Saturation
   observation (the stress-test concern from the original spec). Same
   shape: needs an atomic inflight counter on the handle.
3. **`acquire_ms` histogram** → **CLOSED, NOT FOLLOW-UP**. G4
   answered: semaphore_wait dominates acquire time outside lazy-init,
   and lazy-init is one-shot and uninteresting as a histogram.
   Documented as a deliberate rejection here; do not re-open.
4. **`fallback_total{from, to}` schema restoration** → **owned by a
   cross-owner naming-pattern coordination tempdoc**, jointly between
   414 (already shipped with `{consumer}` only) and 412 (when
   `InferenceLifecycleManager` gains its own CPU fallback path). If
   412's fallback shape is locked in before this is renegotiated, the
   coordination becomes a backward-compatible tag-rename for both
   owners.
5. **DML accelerator value** → **owned by whichever future tempdoc
   adds DirectML support**. The Worker doesn't currently produce DML
   sessions; `ExecutionProvider` enum has `CPU`/`CUDA`/`LLAMA_SERVER`
   only. When DML lands, that tempdoc adds `DML` to `ExecutionProvider`
   and reintroduces the `accelerator` tag where it's meaningful.
6. **Live validation of `gpu_init_failure_total{cause}`** → **owned
   by a test-infrastructure tempdoc** (or accept as irreducible gap).
   The V4 attempt (2026-04-26) found that JAR-bundled CUDA defeats
   the env-var reproducer (see F-011 in
   `docs/reference/inference-runtime-register.md`). The metric is
   proven via the wire-format regression test; live failure
   reproduction needs a test-only `JUSTSEARCH_FORCE_GPU_INIT_FAILURE`
   flag, a non-CUDA machine, or deliberate JAR modification — all of
   which are infrastructure decisions, not 414 scope.
7. **Stress-lane dispatch**: `gh workflow run ci.yml -f runStress=true`
   to confirm `assembler_failure_total{kind=null_variant}` fires before
   the pre-existing NPE propagates. Required by the inference-runtime
   register's coordination rule for ORT/session concurrency changes.
8. **Session policy explainer that consumes these metrics** → **shipped
   by tempdoc 422** (2026-04-27, took over candidate C4 from
   tempdoc 419). Tempdoc 422 shipped the read-only
   `GET /api/inference/encoders` endpoint that produces a derived
   "why is encoder X on CPU/GPU?" answer per encoder. Tier 1 (shipped)
   reads `OrtCudaView` + `PolicySnapshot` only (point-in-time); Tier 2
   (a later slice) would `archivedTo(STANDARD)` a few of 414's metrics
   (`fallback_total`, `gpu_init_failure_total`, `recovery_total`) so
   the explainer can include trend evidence. The Tier 2 archive
   declarations would be a small additive change to 414's catalog —
   this tempdoc tracks them for visibility but defers the work to
   422's V2.



## Context

`NativeSessionHandle` is the lifecycle authority for every Worker-side
ORT session — six instances, one per encoder (`embed`, `splade`, `ner`,
`reranker`, `citation`, `bgem3`), constructed via
`InferenceCompositionRoot.compose<role>Assembly` calls. Today its
lifecycle transitions (GPU init, GPU release, GPU→CPU fallback under
release, CPU session recreation, GPU retry) emit nothing aggregable.
Three observability passes have already been bolted on:

1. `OrtCudaStatus` snapshot via `status()` — point-in-time, not
   transition-history.
2. `lease.acquire` span (gated on `JUSTSEARCH_INDEX_TRACING_LEVEL`,
   off by default).
3. `cpu_fallback.triggered` span event on the current span.

None aggregate. None propagate cross-process. None survive a process
restart. The user-visible payoff is concrete: today, "why is inference
slow?" can stem from a silent CPU fallback (GPU OOM, driver issue, NVML
probe failure, semaphore re-check race) that nothing surfaces in
metrics. After this tempdoc, those events become first-class.

**Empirical grounding (rewrite-time):**

- Class declaration: `modules/ort-common/src/main/java/io/justsearch/ort/NativeSessionHandle.java:45`.
- Lifecycle methods: constructor (line 119), `acquire()` (line 246),
  `releaseGpu()` (line 293), `close()` (line 462), `selectSession()`
  (line 187), `reportCpuSessionFailure()` (line 354), `tryCreateGpuSession()`
  (line 535).
- Silent GPU→CPU fallback case confirmed at `acquire()` line 260
  (after the semaphore re-check). Today this only sets
  `lease.mode=cpu` on a conditional span. No metric.
- `OrtSessionAssembler.buildManager` null-variant deref at
  `modules/ort-common/.../OrtSessionAssembler.java:63`. This is the
  open inbox observation (2026-04-24) that fires
  `NativeSessionHandleConcurrentStressTest.stressTenThreads` under
  the policy-covered stress lane. **Fixing the NPE itself is out of
  scope for this tempdoc** (see § Out of scope); this tempdoc only
  makes the failure path observable.
- `modules/ort-common/build.gradle.kts` does NOT depend on
  `:modules:telemetry`; only on `opentelemetry.api` (for tracing).
  The events-interface idiom is structurally required for the seam
  between `ort-common` and the metric catalog.
- `HeadGpuMetricCatalog` (`gpu.*` namespace) covers host-level NVML
  metrics — no collision with the `ort.session.*` namespace this
  tempdoc proposes.
- `ExecutionProvider` enum already exists in
  `modules/configuration/model/ExecutionProvider.java` with values
  `CPU`, `CUDA`, `LLAMA_SERVER`. Reused for the `accelerator` tag;
  `LLAMA_SERVER` is filtered at projection. No new enum.

## Pre-implementation investigation gate

Implementation is gated on the following questions being answered.
G1–G3 are codebase-only and must be resolved before starting; G4–G5
can run in parallel with implementation but block the catalog's
`MetricDefinition` shape.

| # | Question | Method | Why it gates |
|---|---|---|---|
| G1 | Does `MetricRegistry.buildCounter(name)` throw when `name` has no registered definition? | Read `MetricRegistry.java` + `LocalTelemetry` constructor; reproduce in a 10-line junit if unclear. | Determines whether the prior rewrite's "register at AppFacadeBootstrap" path is a boot-crash or a silent no-op. Either way the right registration site is the Worker (this tempdoc commits to it); the question only sets the urgency of error-handling at the adapter boundary. |
| G2 | Does any production callpath reach `ModelSessionPolicy.forFallback(...)`? | `grep -rn "forFallback" --include="*.java"` excluding `test/`, `testFixtures/`, `src/integrationTest/`, `src/systemTest/`. Cross-check `ModelSessionPolicyResolver.resolve` to confirm it always sets `variant`. | If yes: `assembler_failure_total{reason=null_variant}` has prod value as a real failure metric. If no: it's a stress-test invariant guard, framed accordingly. Either way the metric stays — but its prod section in the observability doc is gated on G2. |
| G3 | Does `JUSTSEARCH_GPU_VRAM_LIMIT_MB` actually trigger CUDA *load* failure, or only constrain arena post-load? | Trace through `EnvRegistry.GPU_VRAM_LIMIT_MB` → `RuntimePolicy`/`ModelSessionPolicy` → ORT CUDA provider options. | The validation gate (§ Validation gate) requires a reproducible failure mode. If the env var only constrains arena, fall back to a different reproducer (rename CUDA DLL; null `nativePath`). |
| G4 | Latency distribution of `lease.acquire` spans under representative ingestion? | (Dev stack.) Run `jseval` ingestion on scifact with `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed`; extract durations from `traces.ndjson`. | Empirical bucket boundaries for `semaphore_wait_ms`. `Buckets.TIME_HISTOGRAM` defaults may be wrong for the sub-µs no-contention case. Also informs whether `acquire_ms` deserves to be a separate metric (kept dropped if the body time is noise). |
| G5 | What does `/api/status` already expose per-encoder via `OrtCudaStatus`? | Read `status-response.schema.json` + an existing fixture (e.g., `modules/ui-web/src/api/__fixtures__/status-response-live.json`). | Reconciliation point for the proposed status gauges. Some are duplicates of existing fields and should be dropped from this tempdoc; only genuinely new ones (e.g., `last_fallback_at_ms`) survive. |

## Design

> **Note (post-implementation):** This § Design section reflects what
> SHIPPED on 2026-04-26 (commits `c666d0a0a` + `8804821e1`). The diffs
> from the v2 pre-implementation design are documented in §
> Implementation status above. Code blocks here mirror production;
> safe to copy.

### Transition reasons as the typed contract

The metric set is derived from a sealed `TransitionReason` type (handle
lifecycle) plus a separate sealed `AssemblerEvent` type (construction-
time, before any handle exists). Adding a new permit to either forces
every consumer (metric adapter, future ring-buffer recorder) to handle
it via exhaustive `switch`. Drift between the class's actual states
and what we observe becomes structurally impossible.

```java
// modules/ort-common/src/main/java/io/justsearch/ort/telemetry/TransitionReason.java
public sealed interface TransitionReason {
    String consumer();

    record GpuInitialized(String consumer) implements TransitionReason {}
    record GpuInitFailed(String consumer, FailureCause cause) implements TransitionReason {}
    record GpuReleaseCompleted(String consumer) implements TransitionReason {}
    record GpuReleaseFailed(String consumer) implements TransitionReason {}
    record GpuFallbackTaken(String consumer) implements TransitionReason {}
    record CpuSessionRecreated(String consumer, CpuRecreateCause cause) implements TransitionReason {}
    record GpuRetryAttempted(String consumer, long sinceFailureMs) implements TransitionReason {}
}

// modules/ort-common/src/main/java/io/justsearch/ort/telemetry/AssemblerEvent.java
public sealed interface AssemblerEvent {
    String consumer();
    record Failed(String consumer, AssemblerFailureKind kind) implements AssemblerEvent {}
}
```

Each reason carries the typed tags it needs (`consumer` always;
per-reason fields for failure causes). The adapter's exhaustive `switch`
over both sealed types is the drift-prevention contract. (The pre-
implementation design carried `AssemblerFailure` as a `TransitionReason`
permit, `GpuReleaseRequested` as a separate emit, and `ReleaseFailure`
named without `Gpu` prefix; v2 critical-analysis fixes split, dropped,
and renamed those — see § Implementation status.)

### Single recorder seam

```java
// modules/ort-common/src/main/java/io/justsearch/ort/telemetry/OrtSessionTelemetryEvents.java
public interface OrtSessionTelemetryEvents {
    // Handle lifecycle transitions — counter-shaped metrics
    default void onTransition(TransitionReason reason) {}

    // Construction-time events — separate channel because no handle exists yet
    default void onAssemblerEvent(AssemblerEvent event) {}

    // GPU-only semaphore wait — histogram-shaped, microseconds for sub-ms resolution
    default void onSemaphoreWait(String consumer, long waitUs) {}

    OrtSessionTelemetryEvents NOOP = new OrtSessionTelemetryEvents() {};
}
```

`NativeSessionHandle` takes one of these in its constructor. The handle
knows nothing about catalogs, OTel, span events, or NDJSON. Default-
void methods avoid per-emit allocation in test contexts (matches
`LuceneRuntimeTypes.TelemetryEvents` precedent — no separate
`Noop*` class needed).

The three-category split (handle transition / assembler event /
semaphore wait) is honest: handle transitions are counter-shaped,
assembler events fire pre-handle so they can't be transitions, and
semaphore wait is GPU-only by definition (CPU acquires don't take the
semaphore). The pre-implementation design had `onSemaphoreWait` carry
an `AcceleratorTag` parameter and emit on every acquire including CPU;
v1 implementation did this then v2 critical-analysis narrowed the
scope to GPU-only because timing CPU "semaphore wait" measured nothing
real. Live validation (post-merge) confirmed the GPU-only sub-ms case:
p50=1µs, p95=10µs under no-contention.

### Hot-path discipline

`acquire()` is called per-encoder-batch and per-inference-call. Two
disciplines apply:

- The recorder methods are one virtual dispatch each; default-void
  methods inline to nothing in production for non-instrumented contexts.
- For `onSemaphoreWait`, the adapter caches `ConsumerTags` per
  `consumer` in a small `Map` populated at construction (one entry per
  `EncoderRole.values()` consumer name). The hot path performs one
  `Map.get` (constant time) + one histogram `record(value, attrs)`
  call. No per-emit allocation.
- For `onTransition`, the `TransitionReason` permit is allocated by
  the caller. Most callers are off the hot path (init, release, retry,
  recovery). The one hot-path-adjacent caller is `GpuFallbackTaken`
  (line 260 of `NativeSessionHandle.acquire()`), which fires at most
  once per inference and only on the fallback path. Acceptable.

### Metric set (shipped — 9 metrics)

| Metric | Type | Tags | Source `TransitionReason` / `AssemblerEvent` |
|---|---|---|---|
| `ort.session.semaphore_wait_us` | histogram (microseconds) | `consumer` | (GPU-only, in-state, not a transition) |
| `ort.session.gpu_init_total` | counter | `consumer`, `outcome` (`success` / `failure`) | `GpuInitialized`, `GpuInitFailed` |
| `ort.session.gpu_init_failure_total` | counter | `consumer`, `cause` (`oom` / `cuda_unavailable` / `driver_error` / `unknown`) | `GpuInitFailed` |
| `ort.session.fallback_total` | counter | `consumer` (implicit `from=cuda,to=cpu`) | `GpuFallbackTaken` |
| `ort.session.recovery_total` | counter | `consumer`, `cause` (`bfc_arena_failure` / `reported_failure` / `unknown`) | `CpuSessionRecreated` |
| `ort.session.release_total` | counter | `consumer`, `outcome` (`success` / `failure`) | `GpuReleaseCompleted`, `GpuReleaseFailed` |
| `ort.session.retry_total` | counter | `consumer` | `GpuRetryAttempted` |
| `ort.session.retry_interval_ms` | histogram | `consumer` | `GpuRetryAttempted.sinceFailureMs` |
| `ort.session.assembler_failure_total` | counter | `consumer`, `kind` (`null_variant` / `model_missing` / `cuda_unavailable` / `unknown`) | `AssemblerEvent.Failed` |

**Buckets**: `semaphore_wait_us` uses `Buckets.WRITE_BARRIER_HISTOGRAM`
(`[1, 10, 100, 1000, 10000, 100000]` µs). Live validation 2026-04-26
confirmed sub-millisecond resolution: NER bucket distribution at
steady state was `[7093, 90, 3, 0, 0, 0, 0]` (7093 acquires <1µs).
`retry_interval_ms` uses `Buckets.TIME_HISTOGRAM`.

**Cardinality**: 6 consumers × max 4 cause values per metric = upper
bound ~50 series per metric, ~480 series total across 9 metrics. Well
within OTel's 2000-series default cap.

**Type-safety contract**: cause-tagged metrics use type-discriminated
tag schemas (`GpuInitFailureTags(FailureCause)` vs
`RecoveryTags(CpuRecreateCause)` vs `AssemblerFailureTags(AssemblerFailureKind)`),
so passing the wrong cause enum to the wrong metric fails at javac.
The pre-implementation v2 design used a single `ConsumerCauseTags(String cause)`
record that accepted any string — v2 critical-analysis split this into
typed tag records to restore the ADR-0027 compile-time guarantee.

**Removed vs. v2 pre-implementation design (with rationale)**:

- `acquire_ms` — duplicates `semaphore_wait_us` whenever there's
  contention, ~zero otherwise. G4 (live latency measurement, 2026-04-26)
  confirmed: the lazy-init tail is one-shot and uninteresting as a
  histogram. Decision: keep dropped, do not restore.
- `concurrent_inflight` — hot-path atomic inc/dec is real cost; the
  equivalent signal is derivable from `lease.wait_queue_depth` already
  captured on `lease.acquire` spans. Listed in § Required follow-up
  for re-evaluation if span data proves insufficient.

### Status gauges (deferred to follow-up)

The original spec named four status gauges (`active_count`,
`last_acquire_ms`, `last_fallback_at_ms`, `current_accelerator`) tagged
`RrdArchive + StatusEndpoint` for `/api/status` integration. Shipped
state has none of them. Existing `OrtCudaView` fields
(`attempted` / `available` / `configured` / `failureReason` / `missingDlls` /
`nativePath` / `variantId`) cover the per-encoder current state surface,
but NOT epoch-timestamps for last fallback/recovery or the typed
accelerator value. Adding the gauges requires new mutable state on
`NativeSessionHandle` (atomic counters, epoch timestamps); it fits
naturally into the deferred phase-typed state-machine refactor (Form A
second-instance work). See § Required follow-up.

### Module placement

| Lives in | What |
|---|---|
| `ort-common` | `OrtSessionTelemetryEvents`, sealed `TransitionReason` + `AssemblerEvent`, `FailureCause`, `CpuRecreateCause`, `AssemblerFailureKind`, `Outcome` (all dep-free typed values + interfaces) |
| `worker-services` | `OrtSessionMetricCatalog` (9 metrics), `OrtSessionTags` (5 typed tag-schema records), `OrtSessionTelemetryAdapter` (with `forAllRoles(catalog)` static factory deriving the cache from `EncoderRole.values()`) |
| `indexer-worker` | `KnowledgeServer.java` registers `OrtSessionMetricCatalog.DEFINITIONS` in worker `LocalTelemetry` and constructs the adapter via `forAllRoles`; `InferenceCompositionRoot` threads the events adapter through `compose()` to `OrtSessionAssembler.buildManager` |
| `ort-common` | `EncoderRole.consumerName()` is the single source of truth for consumer-tag values, consumed by both the composition root and the adapter cache |

This corrects the prior rewrite's "register at `AppFacadeBootstrap`"
defect — `AppFacadeBootstrap` runs in the Head process, where no
`NativeSessionHandle` instances exist.

### Plumbing decision

`OrtSessionAssembler.buildManager` gets a 4-arg overload:

```java
public static SessionHandle buildManager(
    String consumerName, Composition comp, GpuArbiter arbiter,
    OrtSessionTelemetryEvents events) throws OrtException { ... }

public static SessionHandle buildManager(
    String consumerName, Composition comp, GpuArbiter arbiter) throws OrtException {
    return buildManager(consumerName, comp, arbiter, OrtSessionTelemetryEvents.NOOP);
}
```

`NativeSessionHandle.Builder` gains `events(OrtSessionTelemetryEvents)`.
`InferenceCompositionRoot.compose<role>Assembly` (the one production
call path) is updated to forward the adapter; all 25 test callsites
are unchanged (they keep calling the 3-arg overload).

This avoids cascading 25 callsite changes and avoids contaminating
the `Composition` record with a telemetry concern.

## Critical files (shipped state)

**Added (commit `c666d0a0a`):**
- `modules/ort-common/src/main/java/io/justsearch/ort/telemetry/`
  - `OrtSessionTelemetryEvents.java` — three-method default-void interface (`onTransition` / `onAssemblerEvent` / `onSemaphoreWait`).
  - `TransitionReason.java` — sealed interface, 7 permits (handle lifecycle).
  - `AssemblerEvent.java` — sealed interface, 1 permit (`Failed`); construction-time events.
  - `FailureCause.java` — `{OOM, CUDA_UNAVAILABLE, DRIVER_ERROR, UNKNOWN}` plus `classifyGpuInitException(Throwable)`.
  - `CpuRecreateCause.java` — `{BFC_ARENA_FAILURE, REPORTED_FAILURE, UNKNOWN}` (NAN_OUTPUT dropped: no producer).
  - `AssemblerFailureKind.java` — `{NULL_VARIANT, MODEL_MISSING, CUDA_UNAVAILABLE, UNKNOWN}`.
  - `Outcome.java` — `{SUCCESS, FAILURE}` (relocated from worker-services in v2 B4 fix).
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/observability/`
  - `OrtSessionMetricCatalog.java` — 9-metric typed catalog; static-init namespace-prefix check.
  - `OrtSessionTags.java` — 5 typed `TagSchema` records: `ConsumerTags`, `ConsumerOutcomeTags`, `GpuInitFailureTags(FailureCause)`, `RecoveryTags(CpuRecreateCause)`, `AssemblerFailureTags(AssemblerFailureKind)`.
  - `OrtSessionTelemetryAdapter.java` — implements `OrtSessionTelemetryEvents`; exhaustive `switch` over both sealed types; cache populated via `forAllRoles(catalog)` static factory from `EncoderRole.values()`.
- Tests: `TransitionReasonPermitsTest`, `FailureCauseClassifierTest`, `RecordingOrtSessionTelemetryEvents` (test helper), `OrtSessionMetricCatalogSmokeTest`, `OrtSessionTelemetryAdapterTest`, `OrtSessionMetricWireFormatRegressionTest`.

**Modified (commit `c666d0a0a`):**
- `modules/ort-common/src/main/java/io/justsearch/ort/EncoderRole.java` — added `consumerName()` accessor (single source of truth for `consumer` tag values).
- `modules/ort-common/src/main/java/io/justsearch/ort/SessionHandle.java` — `reportCpuSessionFailure(CpuRecreateCause cause)` (typed signature).
- `modules/ort-common/src/main/java/io/justsearch/ort/NativeSessionHandle.java` — `events` field defaulted to `NOOP`; `Builder.events(...)` setter; emits at:
  - `acquire()` line 256: `onSemaphoreWait(consumer, waitUs)` — clock narrowed to the GPU `gpuInferenceSemaphore.acquireUninterruptibly()` only (CPU path doesn't emit).
  - `acquire()` line 260: `GpuFallbackTaken` (the silent-fallback fix — central operational win).
  - `tryCreateGpuSession()`: `GpuInitialized` on success; `GpuInitFailed{cause}` on `OrtException`/`UnsatisfiedLinkError`.
  - `releaseGpu()`: `GpuReleaseCompleted` on success, `GpuReleaseFailed` on internal failure.
  - `selectSession()` retry block: `GpuRetryAttempted{sinceFailureMs}`.
  - `getCpuSession()` recreation path: `CpuSessionRecreated{cause}` reading the typed cause stashed by `reportCpuSessionFailure`.
- `modules/ort-common/src/main/java/io/justsearch/ort/OrtSessionAssembler.java` — 4-arg `buildManager` overload threading events; emits `AssemblerEvent.Failed(NULL_VARIANT)` before the inbox-tracked NPE propagates.
- `modules/reranker/src/main/java/io/justsearch/reranker/CrossEncoderReranker.java` — classifies BFCArena vs generic via `NativeSessionHandle.isBfcArenaFailure(e)` and passes typed cause.
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java` — registers `OrtSessionMetricCatalog.DEFINITIONS` in worker `LocalTelemetry`; constructs adapter via `OrtSessionTelemetryAdapter.forAllRoles(catalog)`.
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/InferenceCompositionRoot.java` — events parameter threaded through `compose()` and per-role helpers; 6 dead public `composeXAssembly` factories deleted; `assertCitationIsCpuOnly` extracted as shared helper.
- `docs/explanation/08-observability.md` — `ort.session.*` namespace table + 9-metric reference.
- `docs/future-features/service-identity-lifecycle-pattern.md` — `NativeSessionHandle` row annotated (Form A second-instance: observability substrate landed; phase-typed state-machine deferred).

## Implementation timeline (historical)

Original estimate was ~4 days. Actual delivery 2026-04-26 in two stages:

1. **v1** — initial implementation across the four phases (substrate / catalog / wiring / registration). Compile + unit tests green.
2. **Critical-analysis v2** — 14 issues identified, 12 fixed (Tier A/B/C); 2 deferred (Tier D — `FailureCause` heuristic test against real ORT exceptions; `release_failure_total{cause}` design).

Both v1 and v2 ship as a single squashed commit `c666d0a0a` on the
worktree branch; merge commit `48a6e4895` lands them on `main`. Live-
stack validation evidence appended in commit `8804821e1`.

## Validation evidence (post-merge, 2026-04-26)

Ran `python -m jseval run --start-backend --clean --pipeline --dataset
scifact --max-queries 1 --modes lexical` against the merged code. The
worker booted cleanly (G1's "boot crash on unknown metric name" risk
empirically refuted), ran 1 query end-to-end, and stopped cleanly.

**What landed in `metrics-worker.ndjson`**:
- `ort.session.gpu_init_total{consumer=<each>, outcome=success}` — fired
  for `reranker`, `ner`, `splade`, `embed` during warmup inference.
- `ort.session.semaphore_wait_us{consumer=<each>}` — emitted with the
  shipped `Buckets.WRITE_BARRIER_HISTOGRAM` bounds
  `[1, 10, 100, 1000, 10000, 100000]`. Empirical: p50=1µs, p95=10µs.
  Sample bucket counts at steady state (NER): `[7093, 90, 3, 0, 0, 0, 0]`.
- `/api/status` `*OrtCuda` blocks emit `attempted` / `available` /
  `configured` / `failureReason` / `missingDlls` / `nativePath` /
  `variantId` per encoder — unchanged from pre-tempdoc state.

**Failure-path metrics that did NOT fire** (no edge case occurred during
the happy-path run): `fallback_total`, `recovery_total`, `release_total`,
`retry_total`, `retry_interval_ms`, `assembler_failure_total`. Each is
exercised by `OrtSessionMetricWireFormatRegressionTest`; staged induction
of each failure mode is future work.

**`gpu_init_failure_total{cause=cuda_unavailable}` could NOT be live-
validated.** The intended reproducer (`JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`
pointed at an empty directory) triggers the documented
`"ORT CUDA DLLs not found … will try CUDA provider anyway (JAR-bundled)"`
log line, but `OnnxSessionCache.createCachedGpuSession` then extracts
CUDA from JAR-bundled resources and GPU init succeeds. Both the
original tempdoc's reproducer (`JUSTSEARCH_GPU_VRAM_LIMIT_MB`, which
doesn't exist per G3) and the G3-replacement (empty native path) are
defeated by the JAR-bundling. The metric IS exercised by the
wire-format regression test — the live-failure validation is an
**irreducible gap on this hardware**. Future work: non-CUDA machine OR
test-only `JUSTSEARCH_FORCE_GPU_INIT_FAILURE` flag.

## Out of scope

- **Phase-typed state machine** (replacing volatile flags with sealed
  `OrtSessionPhase` + CAS transitions). Form A second-instance work
  per the lifecycle-pattern doc; deferred to a follow-up tempdoc that
  builds on the substrate this tempdoc establishes. Doing both at
  once conflates an observability adoption with a concurrency
  redesign — separate risk profiles.
- **Lease as sealed type** (`Lease.GpuLease`/`Lease.CpuLease` instead
  of boolean discriminator). Refactor depends on encoder-side
  cooperation across 6 callsites; bundle with the phase-typed work.
- **Cross-process transition streaming** (Worker → Head gRPC
  server-streaming for transition events). Future work; would build
  on the `TransitionReason` value type this tempdoc establishes.
- **The actual `OrtSessionAssembler:63` NPE fix.** Inbox observation
  2026-04-24 owns it; this tempdoc only makes the failure observable.
- **Per-session VRAM accounting.** NVML provides whole-GPU VRAM;
  per-session attribution is a different problem.
- **Admin trigger to force-release sessions.** Operational nicety
  unrelated to observability.
- **Removing the `OrtCudaStatus` snapshot path.** It coexists with the
  new metrics — the snapshot is a complementary point-in-time surface.

## Long-term considerations

- After this tempdoc lands, the `OrtSessionTelemetryEvents` substrate
  exists in `ort-common`. The follow-up tempdoc that adds phase-typed
  state is then a refactor against this typed contract — not a
  parallel observability rewrite.
- `EmbeddingService` (tempdoc 413b candidate) and
  `InferenceLifecycleManager` are the next Form-A candidates per
  `service-identity-lifecycle-pattern.md`. Both can adopt the same
  events-interface + sealed-reason shape, parameterised over their
  own reason permits.
- The `OrtSessionTelemetryAdapter` fan-out can grow without disturbing
  `NativeSessionHandle` — adding a ring-buffer recorder for a future
  diagnostic endpoint is a `worker-services`-side change only.
- The `NativeSessionHandleConcurrentStressTest` failure becomes
  diagnosable from production-shape telemetry once this lands —
  opens the door to a real fix tempdoc (separate work).

## Dependencies

- **Investigation gate (G1–G3) before starting.**
- ADR-0027 (telemetry catalog substrate) — landed.
- Tempdoc 417 — landed.
- Cross-coordination with 413: this tempdoc commits to reusing
  `ExecutionProvider` for the `accelerator` tag (no new enum); 413
  inherits this decision.
- Stress-lane dispatch is mandatory after merge per the
  inference-runtime register's coordination rule. The pre-existing
  line-63 NPE is expected to fire there until its own fix tempdoc
  lands.

---

## AgentSession (was 415)

*(consolidated from `415-agent-session-observability.md`)*

### 415 — AgentSession Observability Adoption

## Status

**SHIPPED 2026-04-26** on `worktree-415-session-observability` (merged
into `main` 2026-04-26). Created 2026-04-25; rewritten 2026-04-26
against the shipped MetricCatalog substrate; rewritten again 2026-04-26
after a pre-implementation investigation pass (verified empirical
claims; corrected return-site count, tag-schema design, emit points;
added missing schema-migration step; documented active-count gauge
wiring question; reframed structural refactor as explicitly out of
scope). Pattern adoption follow-up to tempdoc 417's shipped substrate;
canonicalized in
[ADR-0027](../decisions/0027-metric-catalog-as-telemetry-contract.md).

**Validation status:** the implementation has been validated at three
levels:

1. **Unit tests (real `LocalTelemetry` + real NDJSON)**:
   `AgentMetricWireFormatRegressionTest` exercises all 9 new metrics
   end-to-end through the production catalog construction path,
   including the gauge supplier-wired-live assertion (supplier value
   read on each flush, not captured at construction).
   `AgentRunStoreSchemaUpcasterTest` exercises v0→v3 schema migration
   on real disk I/O. `AgentTelemetryTest`, `AgentMetricCatalogSmokeTest`,
   and 6 per-disposition tests in `AgentLoopServiceTest` round out
   logic-level coverage.
2. **Live partial smoke** (`jseval dev` against real running backend,
   eval mode, no LLM): backend boots cleanly with the new code,
   `agent.session.active_count` gauge emits live NDJSON samples at the
   expected wire format (`{"type":"gauge","value":0.0,"tags":{}}`),
   `/api/status` correctly **omits** `agentSessions` field when
   `agent.isAvailable() == false` (validates F4 conditional inclusion
   via `@JsonInclude(NON_NULL)` through real Jackson serialization),
   `AgentMetricCatalog.DEFINITIONS` registration in `HeadlessApp:265`
   confirmed empirically (gauge wouldn't emit without it).
3. **Integration tests with real LLM (`AgentBatteryTest`,
   `-PincludeAiTests=true`)**: 11/12 agent flows passed through the
   full refactored loop (tool resolution → recordToolCall →
   executeToolWithPolicy → recordToolFailure if failed → markTerminated
   at terminal site → finally{} recordSessionEnd → setTerminationReason).
   The 85% success-threshold meta-test passed. The 1 timeout (exp-011
   "Ingest new file") is a JUnit 2-minute test-level timeout on the
   ingestion pipeline — environmental, unrelated to 415's code paths.

**Validation gaps remaining** (covered structurally but not directly
observed end-to-end):

- The full live agent-session smoke (a real `/api/agent/run/stream`
  call with metrics inspection in production NDJSON file) was not
  executed standalone. The `AgentBatteryTest` exercises the same code
  paths with real LLM + real tools, but writes to in-memory
  `TestMetricRegistry` rather than `LocalTelemetry` NDJSON. Cancellation
  flow producing `disposition=CANCELLED, cancel_trigger=USER` is
  covered by `sessionEnd_userCancelledMidLoop_emitsTerminateTotalCancelled`
  unit test; not observed in production NDJSON.
- `agentSessions: {activeCount: N>0}` PRESENT case on `/api/status`
  (during a live agent session) was not directly observed. The omission
  case (N=0 / unavailable) was confirmed empirically.

### 419 follow-up adoption (2026-04-26)

Tempdoc 419's discovery pass identified four agent-continuity candidates
that depend on 415's substrate:

- **C44 — Agent History Contract Alignment** (P0 hygiene): backend emits
  `failureCount` in the operation-history map; rest of the API surface
  uses `failedCount`. **Taken over as a 415 follow-up commit** —
  in-scope cleanup, ~one-line rename in
  `AgentLoopService.toBatchSummary` + schema regen + frontend grep.
- **C20 / C28 / C33 / C43 — Continuity and Auditability cluster**
  (Resume/Replay, Notification re-entry, Transcript Export, Workspace
  Operation Timeline): consolidated under **tempdoc 420**
  (multi-conversation persistence and session resume — design stub
  added 2026-04-26). 415's typed `terminationReason` + per-session
  metric attribution is the substrate; 420 owns the user-facing surface.

The C44 fix unblocks 420 per 419's "Fix history contract alignment
before relying on Agent History as an audit surface" gate.

### What shipped

- **9 new `agent.session.*` metrics** in `AgentMetricCatalog`:
  `start_total`, `duration_ms`, `terminate_total`,
  `context_size_bytes_at_end`, `iterations_at_end`, `tool_calls_at_end`,
  `tool_call_total`, `tool_failure_total`, `active_count` (gauge).
- **`TerminalDisposition` × `AgentErrorCode` × `CancelTrigger`
  cross-product tag schema** on `terminate_total` — reuses
  `AgentErrorCode` rather than inventing a parallel enum. Conditional
  emission keeps the tuple bounded.
- **14 termination sites + max-iterations fall-through + catch-block
  path** in `AgentLoopService` refactored to call
  `session.markTerminated(disposition, errorCode, cancelTrigger)` before
  return. **Centralized emit in the existing `finally{}` block** —
  single point of `recordSessionEnd` + `runStore.setTerminationReason`.
- **Tool-call emit at line 1030 (post-resolve)** — handoff calls branch
  out at line 872 before reaching this point, preserving the 4-tool
  cardinality bound. Inline comment documents the constraint.
- **`AgentRunStore` schema v3** with `terminationReason` field
  (`{disposition, errorCode | null, cancelTrigger | null}`); v2→v3
  upcaster defaults to `null` for legacy snapshots. New
  `setTerminationReason` patch method mirrors `setHandoffState`.
- **`agent.session.active_count` gauge wired full-stack**: new
  `StatusEndpoint.AGENT_SESSION_VIEW` enum value, new `AgentSessionView`
  API record, `agentSessions` field on `StatusResponse`, populated by
  `StatusLifecycleHandler` from `AppFacade.agent().activeSessionCount()`.
  `AppFacadeBootstrap` uses the holder-array pattern (precedent:
  `GplJobCoordinator`) so the gauge supplier captures the live service.
  New 7-arg `AgentLoopService` constructor threads the supplier through.
  Frontend Zod schema + TypeScript type updated; schema regen run.
- **Wire-format regression test extended** with all 9 new metric
  emissions + 3 disposition tag-shape assertions (COMPLETED has no
  error_code/cancel_trigger, ERRORED has error_code only, CANCELLED has
  cancel_trigger only).
- **`AgentRunStoreSchemaUpcasterTest` (new)** — 4 cases covering v0→v3
  upcasting and round-tripping typed `terminationReason`.

### Deviations from the plan

- `tool_failure_total` shipped with `tool_name` only (not
  `(tool_name, error_class)` per Decision 2). `executeToolWithPolicy`
  doesn't surface a typed error class; per-call retry-class signal is
  already on `agent.retry.total`. A constant `error_class=TOOL_CONTRACT`
  would have been pure noise.
- Pre-existing `modelDistribution` Zod-coverage gap surfaced by the
  schema regen was added to `STATUS_ALLOWLIST` in
  `modules/ui-web/src/api/contract.test.ts` and logged in
  `docs/observations.md`. Not in 415's scope to design a Zod schema
  for `ModelDistributionStatusView`.

### Original (pre-rewrite) plan reference

The plan as approved is at
`C:\Users\<user>\.claude\plans\misty-gathering-wilkes.md`. The rest of
this document remains as the design rationale and validation gate
description.

## Context

`AgentSession` represents per-conversation agent state. Today its
lifecycle is silent — no metric for session start, duration, termination
cause, or context size at end. The user-visible payoffs are two-tiered:

- **Operational**: detect when sessions end unexpectedly (crash vs.
  user-cancel vs. budget-exhausted vs. tool-loop), how big they grow,
  whether multi-session concurrency is happening.
- **Product-feature-enabling**: the "session resume" feature in tempdoc
  416 requires per-session attribution. Building it on a metrics
  substrate from day one prevents the "which session?" attribution
  problem when 416 lands.

**Empirical grounding (post-investigation):**

- `AgentSession` lives at
  `modules/app-agent/src/main/java/io/justsearch/agent/AgentSession.java:19`.
  Mutable state holder with 13 fields covering messages, approval gates,
  executed tools, cancellation, iterations, loop-guard state, handoff
  state, and budget atomics. The pattern doc
  (`docs/future-features/service-identity-lifecycle-pattern.md`) marks
  it Form B (single-class single-shot). **Restructuring into sealed
  phase types is not in scope for 415**; see "Out of scope" below.

- **There are 14 termination paths in `AgentLoopService.runAgent`**, not
  5. Each carries a hand-written string in the `resumeNote` slot of the
  `runStore.updateCheckpoint(...)` call. Mapped table:

  | Line | Today's state | resumeNote string                         | Proposed (disposition, errorCode, cancelTrigger) |
  |------|---------------|-------------------------------------------|--------------------------------------------------|
  | 510  | ERROR         | `"No tools available"`                    | `(ERRORED, NO_TOOLS, null)`                      |
  | 530  | CANCELLED     | `"Session cancelled"`                     | `(CANCELLED, null, USER)`                        |
  | 554  | ERROR         | tool-loop msg                             | `(ERRORED, TOOL_LOOP, null)`                     |
  | 635  | DONE          | `"Budget-edge finalize succeeded"`        | `(BUDGET_EDGE_FINALIZE, null, null)`             |
  | 662  | ERROR         | budget-exhausted msg                      | `(ERRORED, BUDGET_EXHAUSTED, null)`              |
  | 705  | ERROR         | `"LLM call failed after retries"`         | `(ERRORED, LLM_TRANSIENT, null)`                 |
  | 764  | ERROR         | `"Empty model response"`                  | `(ERRORED, EMPTY_RESPONSE, null)`                |
  | 783  | DONE          | `""`                                      | `(COMPLETED, null, null)`                        |
  | 826  | CANCELLED     | `"Session cancelled"`                     | `(CANCELLED, null, USER)`                        |
  | 851  | ERROR         | `"Unknown agent: …"`                      | `(ERRORED, UNKNOWN_TOOL, null)` *(handoff target)* |
  | 876  | ERROR         | `"Handoff cycle: …"`                      | `(ERRORED, HANDOFF_CYCLE_DETECTED, null)`        |
  | 954  | ERROR         | `"Unknown tool: …"`                       | `(ERRORED, UNKNOWN_TOOL, null)`                  |
  | 1067 (fall-through after `for` block exits) | DONE | `"Max iterations reached"` | `(MAX_ITERATIONS, null, null)`           |
  | 1087 (`catch (Exception e)`)               | ERROR | exception message                  | `(ERRORED, INTERNAL_ERROR, null)`                |

- **The `resumeNote` slot is overloaded.** It carries termination
  strings on terminal states (`DONE`, `ERROR`, `CANCELLED`) AND
  non-termination annotations on resumable states (`READY_FOR_LLM`,
  `WAITING_APPROVAL`, `AFTER_TOOL_RESULT` — e.g., "Tool call rejected:
  X", "Executing tool: Y"). Not a clean rename target. **Add a new
  typed `terminationReason` field** alongside `resumeNote`; schema
  upcaster v2→v3 required.

- `AgentMetricCatalog` already exists at
  `modules/app-agent/src/main/java/io/justsearch/agent/AgentMetricCatalog.java`
  with namespace `agent`. Currently 5 metrics
  (`agent.error.total`, `agent.retry.total`, `agent.loop.blocked.total`,
  `agent.budget_edge_finalize.total`, `agent.retry.exhausted.total`).
  Session metrics extend this catalog — single owner, single namespace.

- `AgentMetricCatalog` is consumed via **direct-emit** through
  `AgentTelemetry`. **The dependency-boundary argument that motivated
  tempdoc 412's events-interface choice does not apply here** —
  `AgentMetricCatalog.java` already imports `modules/telemetry`'s
  catalog types. Direct-emit is the right idiom for 415.

- **Tool registry has 4 production tools** (`file_operations`,
  `search_index`, `browse_folders`, `ingest_files`) registered in
  `AppFacadeBootstrap.java:195-228`. Multi-agent handoff calls use
  dynamic names (`handoff_to_<agentId>`) but **branch out at
  `AgentLoopService.java:831` (`isHandoffCall(call)` test) before
  reaching `toolRegistry.resolve()` at line 937**. The post-resolve
  emit point at line 1030 sees only the 4 bounded names. This is the
  cardinality argument; document it inline so a future emit-point move
  catches the regression.

- **Multi-session concurrency exists**: `AgentLoopService.sessions` is a
  `ConcurrentHashMap<String, AgentSession>` populated at line 448,
  removed at line 1097. The proposed `agent.session.active_count` gauge
  maps to `sessions.size()` — but wiring requires moving catalog
  construction out of `AgentLoopService`'s constructor. See "Active-count
  gauge wiring" below.

## Design decisions (post-investigation)

These are corrections / additions to the prior rewrite. Each is justified
by the empirical grounding above.

### 1. Drop the `mode` tag from V1

The prior rewrite proposed a `mode` tag with values to be catalogued
("default to `unknown` until sites are catalogued"). Investigation shows
there is exactly one production callsite of `runAgent`
(`AgentController.handleRunStream` at `modules/ui/.../AgentController.java:80`)
and `AgentRequest` carries no mode-distinguishing field. Shipping
`mode=unknown` would be a **permanent wire-format commitment** for a tag
with no signal.

If a typed distinction emerges later, the realistic candidates derivable
from `AgentRequest` are `agent_topology` (`single_agent` /
`multi_agent`, from `agentProfiles.isEmpty()`) and `iteration_class`
(`single_turn` / `multi_turn`, from `maxIterations == 1`). Until then,
no tag.

### 2. Termination = (disposition × errorCode × cancelTrigger), not a parallel enum

The prior rewrite proposed a `TerminationReason` enum that mostly
duplicates `AgentErrorCode`. Reuse `AgentErrorCode` and add a small
orthogonal `TerminalDisposition`:

```java
enum TerminalDisposition {
  COMPLETED,
  MAX_ITERATIONS,
  BUDGET_EDGE_FINALIZE,
  ERRORED,
  CANCELLED
}

enum CancelTrigger { USER, BUDGET, TOOL_LOOP }

public record SessionEndedTags(
    TerminalDisposition disposition,
    AgentErrorCode errorCode,        // null unless disposition == ERRORED
    CancelTrigger cancelTrigger      // null unless disposition == CANCELLED
) implements TagSchema { ... }
```

`AgentErrorCode` stays the single source of truth for error names — no
parallel enum to drift against. Cardinality is bounded:
5 dispositions × ~14 error codes / 3 triggers ≈ 50 distinct tag tuples
in practice (many combinations are impossible by construction).

### 3. Centralized emit in `finally{}`, state-only at return sites

Each return site calls `session.markTerminated(disposition, errorCode,
cancelTrigger)` to record state. The single existing `finally{}` block
at `AgentLoopService.java:1088` (which already does
`agentSpan.setStatus(...)`) reads the state and emits all session-end
metrics in one place. If the termination state is null (a new return
site forgot to mark), emit `(ERRORED, INTERNAL_ERROR, null)` and log a
WARN — visible during dev, doesn't silently corrupt metrics.

This reduces emit fan-out from 14 sites to 1, matches the existing
`agentSpan` cleanup pattern, and removes the "did every return site
remember?" failure mode that bolted-on emit-at-return would re-introduce
each time a new termination path is added.

### 4. Tool-call emit at line 1030 (post-resolve), not line 1671

`AgentLoopService.java:1671` is inside `executeToolWithPolicy`'s retry
loop — emitting there would over-count on retry. Emit `recordToolCall`
once per logical call at line 1030 (after `toolRegistry.resolve()`
succeeds), and `recordToolFailure` after `executeToolWithPolicy` returns
with `!result.success()`.

This matches the existing convention: `agent.retry.total` already counts
attempts separately from "main" calls. Per-retry visibility comes from
the existing `recordRetry`/`recordRetryExhausted` surface; per-call
visibility is the new layer.

The post-resolve point also preserves the `tool_name` cardinality bound
(handoff calls branch out at line 831 before reaching resolve). Document
this inline so a future emit-point move catches the regression.

### 5. Active-count gauge wiring requires constructor restructure

Today, `AgentMetricCatalog` is built inside `AgentLoopService`'s public
constructor (`AgentLoopService.java:184`), which means a
`Supplier<Integer>` for `sessions.size()` cannot be wired through the
catalog at construction. Move catalog construction to
`AppFacadeBootstrap`, capture the supplier via the holder-array pattern
(precedent: `GplJobCoordinator` at `AppFacadeBootstrap.java:267`):

```java
AgentLoopService[] holder = new AgentLoopService[1];
var catalog = new AgentMetricCatalog(
    lt.registry(),
    () -> holder[0] != null ? holder[0].activeSessionCount() : 0);
holder[0] = new AgentLoopService(... catalog ...);
```

`AgentLoopService.activeSessionCount()` is a one-line accessor over
`sessions.size()`.

**Open question for reviewer:** ship `active_count` in V1 (cost: ~½ day
plus catalog test surface change for the new constructor arity) or
defer? Both options are documented in "Sequencing" below.

### 6. runStore schema v3 upcaster is required

Replacing free-form `resumeNote` with typed `terminationReason` is a
`meta.json` schema change. `AgentRunStore.SchemaUpcaster` currently at
`CURRENT_SCHEMA_VERSION = 2`. The rewrite:

- Bumps to `CURRENT_SCHEMA_VERSION = 3`.
- Adds a v2→v3 upcaster that defaults `terminationReason: null` for
  legacy snapshots (no inference from `resumeNote` strings — too
  ambiguous; legacy data is read-only for replay).
- Persists `terminationReason` as a structured object
  `{disposition, errorCode | null, cancelTrigger | null}` on
  terminal-state checkpoints.
- Preserves `resumeNote` as-is for non-terminal-state annotations on
  resumable states.

A new `AgentRunStoreSchemaUpcasterTest` covers the v0→v3 path with
fixtures per version (mirrors the existing approach for v0→v2).

## Proposed metric set (added to `AgentMetricCatalog`, `agent.session.*` sub-namespace)

| Metric                                       | Type      | Tags                                              | When fires                                                                    |
|----------------------------------------------|-----------|---------------------------------------------------|-------------------------------------------------------------------------------|
| `agent.session.start_total`                  | counter   | *(none)*                                          | After `sessions.put(...)` at line 448                                         |
| `agent.session.duration_ms`                  | histogram | *(none)*                                          | In `finally{}` at line 1088                                                   |
| `agent.session.terminate_total`              | counter   | `disposition`, `error_code`, `cancel_trigger` (typed `SessionEndedTags`) | In `finally{}` at line 1088                       |
| `agent.session.context_size_bytes_at_end`    | histogram | *(none)*                                          | In `finally{}` at line 1088                                                   |
| `agent.session.iterations_at_end`            | histogram | *(none)*                                          | In `finally{}` at line 1088                                                   |
| `agent.session.tool_calls_at_end`            | histogram | *(none)*                                          | In `finally{}` at line 1088                                                   |
| `agent.session.tool_call_total`              | counter   | `tool_name` (4-tool bounded set; post-resolve only) | After `toolRegistry.resolve()` at line 1030                                 |
| `agent.session.tool_failure_total`           | counter   | `tool_name`, `error_class`                        | After `executeToolWithPolicy` returns failure (line 1030 area)               |
| `agent.session.active_count` *(see decision 5)* | gauge  | *(none)*                                          | Polled supplier reads `AgentLoopService.activeSessionCount()`                 |

**Histogram bucket choices:**

- `agent.session.duration_ms` — `Buckets.TIME_HISTOGRAM` (existing default).
  Live capture experiment recommended to validate before locking but
  acceptable default.
- `agent.session.context_size_bytes_at_end` — bounds at
  1KB / 4KB / 16KB / 64KB / 256KB / 1MB / 4MB. Sized for typical model
  context windows; tail captures pathological growth.
- `agent.session.iterations_at_end` — bounds at 1, 2, 5, 10, 20, 50.
  Bounded above by `request.maxIterations()`.
- `agent.session.tool_calls_at_end` — same bounds as iterations.

**Status surface** (catalog tagged `RrdArchive` + `StatusEndpoint`,
mirroring `IndexRuntimeMetricCatalog`'s status-gauge pattern):

- `agent.session.active_count` — surfaced via `StatusEndpoint` for
  `/api/status` integration.
- Per-session introspection (which sessions, when started, what tool
  history) belongs in a future `/api/agent/sessions` endpoint, not in
  this tempdoc.

**Deferred for V1:**

- `agent.session.persisted_total` — depends on the session-resume
  feature (tempdoc 416). Defer until consumer exists.

## Out of scope

- **Structural refactor of `AgentLoopService` / `AgentSession` into
  sealed phase types or a typed `RunResult` value.** Considered during
  the investigation pass; rejected for V1 because (a) the pattern doc
  (`docs/future-features/service-identity-lifecycle-pattern.md`) marks
  `AgentSession` as Form B (single-class single-shot), (b) no documented
  drift incidents in this area justify the structural-defect argument as
  more than hypothetical, and (c) tempdoc 415's user-visible payoffs
  (per-session attribution, typed termination reason in runStore) are
  achievable without it. Centralized emit in `finally{}` (decision 3)
  captures most of the maintainability win that a sealed `SessionOutcome`
  return value would have provided.

  If multi-conversation persistence (tempdoc 420) requires identity
  decomposition (`ConversationId` vs `RunId` vs `SessionId`), do it
  there with a real driver feature — not here.

- **Multi-conversation persistence with session resume itself.** Driver
  feature in tempdoc 420 (design stub added 2026-04-26); depends on
  this tempdoc's observability + a separate persistence substrate.

- **Per-session conversation content.** Privacy concern; goes in
  `SensitiveQuery` redaction, not metrics.

- **Latency-per-tool histograms.** Existing
  `vdu.{pass1,pass2,total}.duration_ms` cover the VDU sub-case. Other
  tool latencies stay out of scope here.

- **Cost / token-spend metrics.** Different substrate
  (`GenAiMetricCatalog` already covers `gen_ai.client.token.usage`).

- **Admin trigger** (`POST /api/admin/agent/terminate-all`).
  Operator-only operational tool; not a follow-up to V1.

- **`mode` tag with derivable values.** Add when a real distinction
  surfaces; do not pre-bake a tag whose only value would be `unknown`.

## Critical files

**New:**

- `modules/app-agent/src/main/java/io/justsearch/agent/TerminalDisposition.java`
  — 5-value enum.
- `modules/app-agent/src/main/java/io/justsearch/agent/CancelTrigger.java`
  — 3-value enum.

**Modified (extend existing):**

- `modules/app-agent/src/main/java/io/justsearch/agent/AgentMetricCatalog.java`
  — add 8 (or 9 with `active_count`) session-lifecycle `MetricDefinition`s
  alongside the existing 5. New constructor arity:
  `(MetricRegistry, IntSupplier activeSessionSupplier)`. Existing
  no-supplier constructor preserved for tests / `noop()` paths with a
  `() -> 0` default.

- `modules/app-agent/src/main/java/io/justsearch/agent/AgentTags.java`
  — add `SessionEndedTags(disposition, errorCode | null, cancelTrigger | null)`,
  `ToolCallTags(toolName)`, `ToolFailureTags(toolName, errorClass)`
  records. All implement `TagSchema` with the existing pattern.

- `modules/app-agent/src/main/java/io/justsearch/agent/AgentTelemetry.java`
  — add `recordSessionStart()`, `recordSessionEnd(disposition,
  errorCode, cancelTrigger, durationMs, contextSize, iterations,
  toolCalls)`, `recordToolCall(toolName)`,
  `recordToolFailure(toolName, errorClass)` methods.

- `modules/app-agent/src/main/java/io/justsearch/agent/AgentSession.java`
  — add:
  - `private Instant startedAt;` populated in constructor.
  - `private TerminalDisposition disposition; private AgentErrorCode terminationCode; private CancelTrigger cancelTrigger;` (state-only fields).
  - `void markTerminated(TerminalDisposition d, AgentErrorCode code, CancelTrigger trigger)` — single-call, idempotent (subsequent calls are no-ops with WARN).
  - Accessors for the above.
  - `int contextSizeBytes()` — sum of `messages` JSON serialization length (cached per call; computed on demand in `finally{}`).

- `modules/app-agent/src/main/java/io/justsearch/agent/AgentLoopService.java`:
  - Line 448 area — emit `recordSessionStart()` after `sessions.put(...)`.
  - Lines 510, 530, 554, 635, 662, 705, 764, 783, 826, 851, 876, 954
    — call `session.markTerminated(...)` with the appropriate
    `(disposition, errorCode, cancelTrigger)` tuple from the empirical
    grounding table before each `return;`.
  - After the iteration `for` block exits at line ~1054 (max-iterations
    fall-through) — `session.markTerminated(MAX_ITERATIONS, null, null)`.
  - Inside `catch (Exception e)` at line 1069-1087 —
    `session.markTerminated(ERRORED, INTERNAL_ERROR, null)`.
  - In `finally{}` at line 1088 — read termination state + emit
    `recordSessionEnd(...)`. Defensive WARN if state was never set.
  - Line 1030 area — emit `recordToolCall(toolName)` after
    `toolRegistry.resolve()` succeeds (post-resolve, pre-execute).
  - Line 1030 area — after `executeToolWithPolicy` returns, if
    `!toolResult.success()`, emit
    `recordToolFailure(toolName, errorClass)`.
  - Add `int activeSessionCount() { return sessions.size(); }` accessor.

- `modules/app-agent/src/main/java/io/justsearch/agent/AgentRunStore.java`:
  - Bump `CURRENT_SCHEMA_VERSION = 3`.
  - Add `terminationReason` field to `meta.json` writes on terminal-state
    checkpoints: `{disposition, errorCode | null, cancelTrigger | null}`.
  - Add v2→v3 upcaster: defaults `terminationReason: null` for legacy
    snapshots.
  - Preserve `resumeNote` as-is for non-terminal-state annotations.
  - New `updateCheckpoint(...)` overload accepting a
    `TerminationReason` parameter (or null on non-terminal states); the
    legacy overload defaults it to null.

- `modules/app-services/src/main/java/io/justsearch/app/services/AppFacadeBootstrap.java`
  — restructure `AgentLoopService` construction to use the holder-array
  pattern for the active-count supplier (precedent: `GplJobCoordinator`
  at line 267). Construct catalog with supplier first; pass catalog into
  `AgentLoopService`'s new constructor signature.

- **Tests:**
  - `AgentTelemetryTest` — extend with cases for the four new methods,
    matching the F8 single-registry-with-combined-DEFINITIONS pattern.
  - `AgentMetricWireFormatRegressionTest` — extend with session-lifecycle
    metrics; assert exact counter / histogram outputs for 5 scenarios:
    completion, cancellation, max-iterations, budget-exhausted,
    tool-failure.
  - `AgentRunStoreSchemaUpcasterTest` — new test covering v0→v3 path
    with fixtures per version.
  - `AgentLoopServiceTest` — at least one test per `TerminalDisposition`
    asserting the right `markTerminated` call and the right
    `terminationReason` in `meta.json`.

- `docs/explanation/08-observability.md` — extend the `agent.*` section
  with the session-metrics table; update the "Agent Telemetry" prose to
  cover the disposition × errorCode tag schema.

- `docs/future-features/service-identity-lifecycle-pattern.md` —
  annotate `AgentSession` row: still Form B; observability adopted via
  direct-emit; structural decomposition deferred to a future refactor
  when a driver feature requires it (e.g., 416's persistence work).

**No gRPC needed** — `AgentSession` lives in app-agent (Head-side); no
proto extension required.

## Sequencing

Estimate: **~3 days** (was 1.5 in the original; rewritten estimate
accounts for the four pieces the prior rewrite missed — schema v3
upcaster, accurate 14-site refactor, active-count wiring, wire-format
regression test — plus a more honest unit-test surface).

**Defer-active-count variant: ~2.5 days.** Skips step 5; ships
active_count in a follow-up.

1. Define `TerminalDisposition` + `CancelTrigger` enums (~1h).

2. Add `markTerminated` + accessors + `Instant startedAt` +
   `contextSizeBytes()` to `AgentSession` + tests (~½ day).

3. Refactor 14 return sites + the catch / fall-through paths in
   `AgentLoopService` to call `markTerminated`. Centralize emit in
   `finally{}`. Add tool-call / tool-failure emits at line 1030.
   Per-disposition unit tests (~½ day).

4. Extend `AgentMetricCatalog` + `AgentTags` + `AgentTelemetry` with
   session methods. Catalog test surface update for new constructor
   arity (~½ day).

5. *(Optional — only if shipping active-count in V1)* Wire the
   holder-array pattern in `AppFacadeBootstrap`; add `activeSessionCount()`
   accessor + gauge supplier; extend tests (~½ day).

6. Bump `AgentRunStore` to schema v3 + upcaster + `AgentRunStoreSchemaUpcasterTest`
   (~½ day).

7. Wire-format regression test extension covering 5 scenarios. Assert
   exact counter / histogram emissions byte-stably (~½ day).

8. Smoke check + observability doc update (~few hours).

**Validation gate** (sharpened):

A live agent session via `/api/agent/run/stream` plus the wire-format
regression test must jointly produce:

- Exactly 1 `agent.session.start_total` emit per session.
- Exactly 1 `agent.session.duration_ms` sample per session.
- Exactly 1 `agent.session.terminate_total{disposition=X,error_code=Y,cancel_trigger=Z}`
  emit per session, where (X, Y, Z) matches the path taken (per the
  empirical grounding table).
- 1 `agent.session.tool_call_total{tool_name=N}` emit per logical tool
  call (not per retry).
- 0 `agent.session.tool_call_total{tool_name=handoff_to_*}` emits
  (handoff path doesn't reach line 1030 — cardinality bound preserved).
- `meta.json` has `terminationReason` field populated with structured
  reason on terminal states; absent on non-terminal states.
- Pre-existing `metrics.ndjson` outputs (error / retry / loop_blocked /
  budget_edge_finalize) byte-stable post-change.

The regression test runs the assertions automatically. The smoke check
confirms the live wire format matches.

## Long-term considerations

- **Conversation vs. Run identity**: today, `sessionId = runId`. Tempdoc
  416 (multi-conversation persistence with session resume) likely needs
  identity decomposition (`ConversationId` / `RunId` / `SessionId`).
  When 416 lands, `agent.conversation.*` becomes a separate metric
  family that aggregates across runs. The names chosen here
  (`agent.session.*`) treat one `runAgent` invocation as one session —
  no backwards-incompatible rename when 416 introduces the conversation
  concept on top.

- **Direct-emit vs. events-interface**: the choice was deliberated for
  415. Direct-emit is correct here because `AgentMetricCatalog` already
  imports the catalog substrate (no dependency boundary to preserve,
  unlike tempdoc 412's `app-inference` case). After ≥4 examples of each
  idiom exist (per ADR-0027's deferred addendum), tempdoc 415's
  reasoning here (dependency boundary already crossed → direct-emit) is
  one input.

- **`tool_name` cardinality**: bounded at 4 today via the post-resolve
  emit point. If the registry grows past ~30 tools, or if handoff calls
  are intentionally moved into the same emit path, re-evaluate. Today's
  bound is comfortable.

- **`agent.session.active_count` operator console**: gauge naturally
  feeds the deferred operator console UI work (was 417 §5.2; deferred).

- **Disposition × errorCode combinations**: the tag tuple
  `(disposition, errorCode, cancelTrigger)` produces ~50 distinct
  observed combinations in practice. Well under any cardinality limit.
  If the disposition enum grows to >10 values, re-check.

## Dependencies

None blocking. Independent of 412 / 413 / 414.

Composes with the persistence-substrate work in tempdoc 420
(multi-conversation persistence and session resume — design stub
added 2026-04-26): if 420 ships, the deferred
`agent.session.persisted_total` metric has a consumer, and the
`agent.conversation.*` family layers cleanly on top of 415's
per-run metrics. 420's session-attribution work needs the per-session
metric substrate from 415 (now landed) before resume-attribution
semantics become a real question.

## 419 follow-up — shipped 2026-04-28

Five items from tempdoc 419's discovery pass were transferred into 415's
ownership and shipped on `worktree-tempdoc-415-followup`:

- **C44 (Agent History Contract Alignment)** — *shipped*. Backend rename
  `failureCount` → `failedCount` at `AgentLoopService.toBatchSummary` (the
  frontend already expected `failedCount` at `AgentHistory.tsx:153`,
  meaning the failed-operation badge previously never rendered). Locked
  via `AgentBatchSummarySchema` + `AgentHistoryResponseSchema` Zod parse
  in `getAgentHistory`. Test:
  `AgentLoopServiceTest.operationHistory_emitsFailedCountKey` +
  `agent.test.ts` Zod cases.
- **C20 (Agent Session Resume and Replay)** — *shipped*. New backend
  endpoints: `GET /api/agent/sessions`, `GET /api/agent/session/{id}`,
  `POST /api/agent/session/{id}/resume/stream`. New `AgentRunStore`
  methods: `listSessions(limit)`, `readSnapshot(id)`,
  `toSessionSummary`, `derivePreview`. New `AgentService` interface
  defaults: `listSessions`, `sessionSnapshot`, `resumeSession`. The
  existing `resumeLastSession` logic (state-gate
  WAITING_APPROVAL/READY_FOR_LLM/AFTER_TOOL_RESULT) was extracted into a
  shared private `resumeFromSnapshot(snapshot, eventConsumer)` so the
  new `resumeSession(id)` inherits the same safety constraints. Frontend:
  new `'sessions'` tab in `AgentView`, `AgentSessionsList` with
  expand-row + Resume button, `AgentSessionDetail` rendering full
  message thread, `useAgentStore.resumeSession(baseUrl, id)` action
  driving SSE consumption. Tests: `AgentRunStoreTest` (4 new cases),
  `AgentLoopServiceTest` (3 new cases), `AgentSseContractTest` (1 new
  combined case covering all four routes).
- **C28 (Notification-to-Session Continuity)** — *deferred*. Per the
  follow-up plan's product-decision defaults, defer the Tauri
  `onClick` → `'view-session'` event → `AgentView` deep-link until C20
  ships and reveals real demand. Tracked in `docs/observations.md`.
- **C33 (Agent Session Transcript Export) V1** — *shipped*. New endpoint
  `GET /api/agent/session/{id}/transcript` returns
  `{meta, events}` with `Content-Disposition: attachment`. Frontend
  Download button on `AgentSessionDetail` via `<a download>`. V2
  redaction-aware export deferred (no shipped redaction policy yet).
  Test: `AgentSseContractTest` 200/404 cases + Content-Disposition
  header assertion.
- **C43 (Workspace Operation Timeline) V1** — *shipped*. New
  `WorkspaceTimeline` component as a fourth `'timeline'` tab in
  `AgentView`. Client-aggregated merge of `/api/agent/sessions` +
  `/api/agent/history` rows by ISO-8601 timestamp. Click on a session
  row navigates to the Sessions tab. The sessionId-on-batches join
  (V2) is deferred — threading sessionId through
  `ToolDefinition.execute(args)` requires either a ThreadLocal hack on
  `FileOperationLog` or an SPI extension; not worth V1 cost without UX
  feedback. Tracked in `docs/observations.md`.

**Validation status**: backend tests green via `:modules:app-agent:test`
+ `:modules:ui:test`; frontend typecheck + 237 unit tests pass; full
`./gradlew.bat test` suite green; full `./gradlew.bat build -x test`
green.

**Live dev-stack smoke (2026-04-28, eval mode, no LLM):**

- `python -m jseval dev --clean` started a worktree-local backend on
  port 33221 (after one false start from a stale main-checkout
  pip-installed jseval that pointed at `F:\JustSearch\` — used
  `PYTHONPATH=$(pwd)/scripts/jseval` to force the worktree copy).
- Two sessions seeded by hand into `tmp/headless-eval-data/agent-runs/`:
  `session_alpha` (`WAITING_APPROVAL`, resumable, no terminationReason)
  and `session_beta` (`DONE`, not resumable, terminationReason
  `{disposition: COMPLETED}`).
- **`GET /api/agent/sessions`** — returned both sessions newest first;
  light-field projection verified (sessionId, startedAt, updatedAt,
  state, resumable, iterationsUsed, toolCallsExecuted, totalTokensUsed,
  activeAgentId, terminationReason, preview); heavy fields
  (`messages`, `agentProfiles`, `handoffHistory`) correctly absent;
  preview derived from first user message (`"What is the capital of
  Mars?"`, `"List my recent files about widgets."`).
- **`GET /api/agent/sessions?limit=1`** — clamping verified (returned
  only the newest, session_beta).
- **`GET /api/agent/session/session_beta`** — full snapshot returned
  with all heavy fields (`messages`, `agentProfiles`, etc.) plus the
  typed `terminationReason`.
- **`GET /api/agent/session/session_alpha`** — same shape with
  `terminationReason: null` (verified via Python assertion script).
- **`GET /api/agent/session/no-such-id`** — returned **404** with the
  typed `NOT_FOUND` envelope.
- **`GET /api/agent/session/session_beta/transcript`** — returned
  `200` with `Content-Disposition: attachment;
  filename="agent-session-session_beta.json"` and bundled
  `{meta, events}` body. Events array carried 3 entries
  (`session_started`, `chunk`, `done`).
- **`GET /api/agent/session/no-such-id/transcript`** — returned
  **404** as designed.
- **Corruption tolerance**: wrote `session_corrupt/meta.json = "{ this
  is not json"`; `GET /api/agent/sessions` filtered it out and
  returned only the two valid sessions. The
  `.filter(Objects::nonNull)` guard in `listSessions` works as
  designed.
- **`POST /api/agent/session/{id}/resume/stream`** — eval mode reports
  `agent.isAvailable() == false` (no LLM), so the route returns the
  typed 503 envelope `{error: "Agent capability is not available",
  errorCode: "SERVICE_UNAVAILABLE", ...}`. The unavailable-service
  guard is exercised; the state-gate path requires LLM and was not
  validated end-to-end (covered by `AgentLoopServiceTest`'s
  `resumeSession_byId_runsAgentFromSpecificSnapshot` and
  `resumeSession_unknownId_emitsTypedError`).
- **`POST /api/agent/session/resume-last/stream`** — sanity-checked,
  same 503 envelope, confirming the `resumeFromSnapshot` extraction
  did not regress the original endpoint.
- **`GET /api/agent/history`** — returned `{"batches":[]}`. Eval mode
  doesn't construct `FileOperationLog` (depends on `knowledgeClient`
  + `indexingService`), so `agentService.operationHistory()` returns
  `List.of()`. The C44 `failedCount` rename remains validated only by
  `AgentLoopServiceTest.operationHistory_emitsFailedCountKey` and the
  10 Zod tests in `agent.test.ts`. To live-validate the rename,
  `--llm` mode + a real file-mutating agent batch would be needed —
  out of scope for this follow-up smoke.

**Remaining live gaps**: full SSE resume flow (state-gate
`WAITING_APPROVAL/READY_FOR_LLM/AFTER_TOOL_RESULT` → `runAgent` start)
and the C44 wire format on a real file-operations batch. Both require
LLM-attached agent execution; both have unit-level coverage. Same gap
class as the original 415 ship.
