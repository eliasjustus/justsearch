---
title: Telemetry-as-Contract — MetricCatalog Refactor (supersedes 406 follow-ups)
type: tempdocs
status: shipped
---

# 417 — Telemetry-as-Contract: MetricCatalog Refactor

## Status

**SHIPPED through Phase 4** (2026-04-25). Phase 5 (optional — exemplar
correlation projection) and Phase 6 (deferred — UI surface) remain
explicitly out of scope per their tempdoc markers; revisit when concrete
demand surfaces. Created 2026-04-25; rewritten 2026-04-25 after
critical-design review; investigation pass completed 2026-04-25; design
grounded in verified SDK behavior and codebase audit.

**Shipped on `worktree-417`:**

- **Phases 0–1** (commit `19c12b40f`, 2026-04-25): catalog substrate +
  `WorkerLuceneTelemetryAdapter` proof-case migration.
- **Post-Phase-1 fixes F1/F2/F5/F6/F7/F8** (commit `2266bb5ac`, 2026-04-25):
  six structural fixes from a critical-analysis pass — per-metric tag-key
  ordering reaches NDJSON (F1), final-field catalogs with constructor
  injection (F2), unwired `archivedTo`/`surfacedAt` API removed (F5),
  `AutoCloseable` cast replaced (F6), `Meter` cached per namespace (F7),
  `TestMetricRegistry` testFixture with typed query API (F8).
- **Phase 2 (breadth migration)** (2026-04-25): 12 emission sites across
  6 modules migrated to typed catalogs; legacy `ALLOWED_TAG_KEYS` and 6
  hardcoded Views deleted; `Telemetry.histogram(name, tags, config, value)`
  deprecated for removal in Phase 3. See "Phase 2 shipped" section below.
- **Phase 2 critical-analysis fixes (F1–F10)** (2026-04-25): post-Phase-2
  critical analysis identified one severe regression and a batch of
  structural / coverage gaps. Ten fixes shipped:
  - **F1** — Head catalogs (HeadApi/HeadGpu/HeadHttpInflight + tags +
    HttpMethod/HttpStatusClass/StreamTransport) relocated from `modules/ui`
    to `modules/app-services/observability` so `LauncherEnvironment` can
    register their DEFINITIONS without violating the
    `LayeringEnforcementTest.onlyAppLauncherMayDependOnUi` rule.
    Resolves the desktop-launcher metric drop (api.request_ms /
    api.stream.ttft_ms / head.http.inflight_requests / gpu.* lost emission
    in Phase 2c due to noop-fallback workaround).
  - **F2** — Restored constant `component` tags on
    `rag.retrieval_total` ({@code component=rag_retrieval}),
    `vdu.outcome_total` ({@code component=vdu_batch}),
    `vdu.timeout_total` ({@code component=vdu}),
    `extraction.timeout_total` ({@code component=content_extractor}). New
    `VduTimeoutTags` and `ExtractionTimeoutTags` records replace
    {@code EmptyTags} on those metrics. Wire-format byte-stable.
  - **F3** — Five wire-format regression tests covering 13 catalogs:
    `IndexingPipelineWireFormatRegressionTest`,
    `OcrAndWatcherWireFormatRegressionTest`,
    `AppServicesMetricWireFormatRegressionTest` (covers IPC + RAG + VDU +
    Head Api/Gpu/Inflight),
    `SearchPagingMetricWireFormatRegressionTest`,
    `AgentMetricWireFormatRegressionTest`. Real `LocalTelemetry` + NDJSON
    parse + structural assertions.
  - **F4** — Per-catalog smoke tests using `TestMetricRegistry` for all
    13 new catalogs.
  - **F5** — `AgentLoopService` test-only constructor converted to a
    static factory `forTesting(...)` that internally uses a marker-based
    private constructor. Eliminates `(Telemetry) null` cast ambiguity.
  - **F6** — `IpcTelemetry.noop()` / `AgentTelemetry.noop()` and all 13
    `*MetricCatalog.noop()` factories cached as static final singletons.
    Avoids constructing fresh catalogs + 13 instruments per call.
  - **F7** — Removed tight `cardinalityLimit(128)` on `api.request_ms`
    and `cardinalityLimit(64)` on `api.stream.ttft_ms` (rolled into the
    relocated catalogs from F1). Falls back to OTel's 2000 default.
  - **F8** — `AgentTelemetryTest` now uses one `TestMetricRegistry` with
    both `AgentMetricCatalog` + `GenAiMetricCatalog` DEFINITIONS combined,
    matching production single-registry wiring.
  - **F9** — Removed dead `inflightRequestsGauge` field from
    `LocalApiServer`.
  - **F10** — Removed unused `String reason` parameter from
    `VduBatchProcessor.recordFailed`. Reason continues to flow into slf4j
    logging at the same callsite.

**Phases shipped 2026-04-25 (this session):**

- **Phase 3a** — `RrdArchive` + `StatusEndpoint` reintroduced on
  `MetricDefinition` with `archivedTo(...)` / `surfacedAt(...)` builders.
- **Phase 3b** — 4 `index.runtime.*` status gauges (`writer_queue_depth`,
  `writer_pending_docs`, `commit_count`, `refresh_lag_ms`) routed through
  `IndexRuntimeMetricCatalog` with `archivedTo(STANDARD)` +
  `surfacedAt(CORE_INDEX_VIEW, ...)`. `RrdMetricStore` derives the curated
  set from catalog declarations + a residual `LEGACY_CURATED_METRICS`
  list. `MetricSurfaceContractTest` (ArchUnit-style reflective rule) fails
  CI when a `surfacedAt` field name diverges from the API record's
  components.
- **Phase 3c** — `WorkerOpsMetricCatalog` (35 metrics: 16 observable
  counters + 4 long gauges + 3 double gauges + 9 queue/buffer gauges + 1
  switch-buffer-write-failures observable). The legacy
  `KnowledgeServer.registerOtelObservableCallbacks` (`meter(scope)`
  callsite) is gone. `Telemetry.meter(String)` deleted from the interface.
  `SqliteJobQueue`'s `Telemetry.Counter` parameter replaced with a
  `Runnable` callback to keep that layer decoupled.
- **Phase 3d** — `JvmMetricCatalog` (prefix-parameterized; 10 JVM gauges;
  threads.live + memory.heap.used_bytes declare `archivedTo(STANDARD)`).
  Wired in `HeadlessApp` (`head`), `KnowledgeServer` (`worker`), and
  `LauncherEnvironment` (`launcher` — new). `VduPassTags` + 3 histogram
  definitions on `VduMetricCatalog` (`vdu.pass1.duration_ms`,
  `vdu.pass2.duration_ms`, `vdu.total.duration_ms`); `VduProcessor`
  emits via the typed catalog. `Telemetry.histogram(...)` default
  retired.
- **Phase 3e** — `ApiErrorHandler.recordError` routes through
  `HeadApiMetricCatalog.errorTotal` via `LocalTelemetry` cast (one-line
  change avoiding the 17-controller migration the original plan called
  for). `Telemetry` collapsed to a marker interface (`extends
  AutoCloseable`); `Counter`/`Timer`/`Gauge` nested types and the
  `counter`/`timer`/`gauge` abstract methods deleted along with their
  `LocalTelemetry` overrides. Test fakes (`TestTelemetry`, three inline
  `NoopTelemetry`s, `FakeTelemetry`) collapsed to marker stubs.
- **Phase 4** — ADR-0027 (~5 KB) +
  `docs/explanation/08-observability.md` "Metric catalog ownership"
  section. `docs/llms.txt` regenerated; skills synced.
- **Critical-analysis follow-up** (post-Phase-3 audit, this session) —
  6 fixes: A1 (4 missing `archivedTo` declarations), A2
  (`RrdMetricStoreCatalogDeriveTest` regression test), B1 (VduProcessor
  exact byte-stability), B2 (`ApiErrorHandler` cache + missing-catalog
  WARN), B3 (dead-import cleanup), C1 (tempdoc accuracy).

### What shipped (Phases 0–1)

**Phase 0 — Catalog substrate** (`modules/telemetry/src/main/java/io/justsearch/telemetry/catalog/`,
post-F5):
- 14 production files (down from 16 after F5 dropped `RrdArchive` and
  `StatusEndpoint`): foundation types (Unit, Buckets, Exemplars, TagSchema,
  EmptyTags, MetricDefinition, InstrumentKind), sealed Metric hierarchy
  (Counter/Histogram/Gauge/ObservableCounter), MetricCatalog/MetricRegistry
  interfaces.
- `LocalTelemetry` constructor accepts `List<MetricCatalog>`; per-metric Views
  with `setAttributeFilter` + `Aggregation.explicitBucketHistogram`. Uses
  `setExemplarFilter(ExemplarFilter.traceBased())` via the **public stable
  API on `SdkMeterProviderBuilder`** (resolved Open Question 5 — no internal
  package dependency needed; spike confirmed).
- `NdjsonMetricExporter` honors per-metric `Exemplars.OFF` at export time
  AND per-metric tag-key emission order (post-F1).
- `MetricCatalog.of(namespace, definitions)` static factory (post-F2) lets
  bootstrap register Views without a fully-bound catalog instance.
- `TestMetricRegistry` testFixture (post-F8): in-memory OTel SDK + capturing
  exporter; typed query API (`counterValue`, `histogramCount`,
  `histogramBucketCounts`, `emittedNames`).
- Tests: `MetricCatalogSmokeTest` (5 cases),
  `CatalogTypeSafetyCompileFailTest` (3 compile-fail cases via JavaCompiler
  API), `NamespacePrefixRuleTest`, `TagOrderTest` (post-F1, verifies per-metric
  tag-key ordering in NDJSON), `AttributesUseRuleTest` (ArchUnit, in
  `app-launcher`, exempts `TagSchema` implementations and 3 span-instrumentation
  files).

**Phase 1 — `WorkerLuceneTelemetryAdapter` proof case**:
- Sealed-style enums in `adapters-lucene`: `CommitReason` (21 values),
  `SwapReason` (5), `ValidationReason` (5). Each enum's `wireValue()` returns
  the existing NDJSON tag string verbatim, so wire format is byte-stable
  across the migration (Q3 default honored).
- `IndexRuntimeMetricCatalog` (9 metrics) + `IndexRuntimeTags` with
  `CommitTags` / `SwapTags` / `ValidationTags`.
- `LuceneRuntimeTypes.TelemetryEvents` interface signature: typed reasons
  replace `String`. Adapter rewritten as thin façade over the catalog.
- ~22 callsites migrated: `CommitOps`, `RunningRuntime`, `IndexingCoordinator`,
  `IndexingLoop` (including 2 reasons missed in the original audit:
  `INDEXING_LOOP_TIME`, `INDEXING_LOOP_BUFFER`), `GrpcIngestService`,
  `KnowledgeServer.swapRuntime`/`runtimeReloadTrigger`, `PruneOps`,
  `SyncDirectoryOps`, 6 backfill ops.
- `WorkerLuceneTelemetryAdapterTest` migrated (14 cases). Post-F8: uses
  `TestMetricRegistry` from telemetry's testFixtures (typed counter/
  histogram queries; no NDJSON parsing).
- `IndexRuntimeWireFormatRegressionTest` (kept as wire-format guarantee):
  verifies bucket bounds reach wire format, reason tags preserved
  (path-style and snake_case both byte-stable), `Exemplars.OFF` on
  `write_barrier_wait_us` actually suppresses wire-format exemplars.
- `CatalogImmutabilityTest` (post-F2): reflective check that every
  `Metric`-typed field on `IndexRuntimeMetricCatalog` is `final`.
- `IndexRuntimeMetricCatalog` (post-F2): `public final` instrument fields,
  single registry-arg constructor, static block validates namespace prefix
  at class load.
- Verification: `:modules:{adapters-lucene,worker-services,indexer-worker,telemetry}:test`
  all pass; full project `./gradlew build -x test` passes.

### Phase 2 shipped (2026-04-25)

**Sub-phases (a–f):**

- **2a — Substrate** (`modules/telemetry`):
  - `MetricDefinition.cardinalityLimit(int)` builder method; `LocalTelemetry`
    applies `viewBuilder.setCardinalityLimit(...)` per metric.
  - `NoopMetricRegistry` (production class): backed by `MeterProvider.noop()`
    so bridge holders construct catalogs without `LocalTelemetry`.

- **2b — `worker-services` + `app-indexing`**:
  - `IndexingPipelineMetricCatalog` (`pipeline.stage_ms`) replacing legacy
    inline `HistogramConfig` in `IndexingLoop.recordStageMs`.
  - `ExtractionMetricCatalog` (`extraction.timeout_total`) replacing
    `Telemetry.counter` in `TimeboxedContentExtractor`.
  - `WatcherMetricCatalog` (`index.watcher.events_total`) reusing existing
    `FileWatcherStrategy.Kind` enum as the tag schema. `MethvinWatcherStrategy`
    now takes the catalog directly (no per-kind cached counters).
  - `OcrMetricCatalog` (`ocr.succeeded_total`, `ocr.time_ms`,
    `ocr.failed_total`, `ocr.skipped_total`) plus `OcrSkipReason` sealed-style
    enum.
  - Bootstrap wiring: `KnowledgeServer.start()` registers the worker-side
    catalog DEFINITIONS at `LocalTelemetry` construction; the catalog
    instance is passed via `MetricRegistry` on `InfraContext`.

- **2c — `ui` module** (Head HTTP):
  - `HeadApiMetricCatalog` (`api.request_ms`, `api.stream.ttft_ms`,
    `api.error.total`) plus `HttpMethod` / `HttpStatusClass` /
    `StreamTransport` sealed-style enums.
  - `HeadHttpInflightMetricCatalog` (`head.http.inflight_requests` gauge).
  - `HeadGpuMetricCatalog` (`gpu.utilization.percent`,
    `gpu.memory.utilization.percent` gauges; preserves
    `LocalApiServer.getCachedGpuSnapshot()` 5-second cache).
  - `KnowledgeSearchController` / `PreviewController` / `SseWriter` migrated
    to typed catalog. `ApiErrorHandler.recordError` left on the legacy emit
    path; the catalog View applies View-level filtering by name regardless.
  - **Architectural-rule limitation**: the
    `LayeringEnforcementTest.onlyAppLauncherMayDependOnUi` ArchUnit rule
    blocks `applauncher → ui` imports because the rule's matcher is
    `io.justsearch.app.launcher..` while the actual main package is
    `io.justsearch.applauncher` (no dot). LauncherEnvironment therefore
    cannot register UI catalog DEFINITIONS; `LocalApiServer` falls back to
    `HeadApiMetricCatalog.noop()` in the desktop-launcher process. Catalog
    Views are live in the `HeadlessApp` path. A follow-up tempdoc should
    fix the rule or rename the package to enable symmetric wiring.

- **2d — `app-search` + `app-agent`**:
  - `SearchPagingMetricCatalog` (`search.paging_faults_total`,
    `search.pit_acquire_ms`) plus `PagingFaultReason` sealed enum.
    `PagingCursorManager` now takes the catalog directly.
  - `AgentMetricCatalog` (`agent.error.total`, `agent.retry.total`,
    `agent.loop.blocked.total`, `agent.budget_edge_finalize.total`,
    `agent.retry.exhausted.total`) reusing `AgentErrorCode`/`AgentErrorClass`.
  - `GenAiMetricCatalog` (`gen_ai.client.operation.duration`,
    `gen_ai.client.token.usage`).
  - `AgentTelemetry` rewritten as thin façade over both catalogs;
    `AgentLoopService` builds catalogs from `LocalTelemetry.registry()` when
    available, otherwise `AgentTelemetry.noop()`. Added a package-private
    constructor on `AgentLoopService` so tests inject `AgentTelemetry`
    directly via a `TestMetricRegistry`-backed catalog.

- **2e — `app-services`** (largest sub-phase):
  - `IpcMetricCatalog` (14 metrics: 13 counters + 1 histogram) plus
    `CircuitBreakerState` and `WorkerRestartOutcome` sealed-style enums.
  - `IpcTelemetry` rewritten as thin façade over `IpcMetricCatalog`;
    `IpcTelemetry.noop()` builds against `NoopMetricRegistry`. The internal
    `NoopTelemetry` legacy inner class is gone. `recordCircuitBreakerStateChange`
    signature changed from `(String, String)` to typed enums; the single
    `GrpcCircuitBreaker` callsite uses `CircuitBreakerState.fromWire`.
  - `RagMetricCatalog` (`rag.retrieval_total`) plus `RagRetrievalMode`.
    `RemoteDocumentService` collapsed 3 cached counter handles into 1
    catalog instrument with 3 tag combinations. The constant
    `component=rag_retrieval` tag was initially dropped during Phase 2e;
    **F2 (post-Phase-2 critical-analysis fix) restored it for wire-format
    byte-stability**. `RagRetrievalTags` now carries `(component, mode)`.
  - `VduMetricCatalog` (`vdu.timeout_total`, `vdu.outcome_total`) plus
    `VduOutcome` sealed enum. **Dropped the unbounded `reason`
    exception-message tag** from `VduBatchProcessor.recordFailed` (Open
    Question 2 default — exception details continue to be logged via
    slf4j at the same callsite). `VduProcessor`/`VduBatchProcessor` take
    the catalog; `AppFacadeBootstrap` constructs it from the registry.
    **F2 also restored** `component=vdu_batch` on `vdu.outcome_total` and
    `component=vdu` on `vdu.timeout_total` (`VduOutcomeTags` /
    `VduTimeoutTags`); the unbounded `reason` tag stays dropped.
  - **Bridge holders unchanged**: `WorkerSpawner` /
    `GrpcCircuitBreaker` / `RemoteKnowledgeClient` continue to receive
    `IpcTelemetry`. The catalog is internal to `IpcTelemetry`; no fan-out
    to holders required.

- **2f — Cleanup**:
  - Deleted 6 hardcoded Views from `LocalTelemetry` constructor:
    `pipeline.stage_ms`, `api.request_ms`, `api.stream.ttft_ms` (now
    catalog-driven), and the dead `llm.latency_ms`,
    `plugins.stage.load_ms`, `index.runtime.refresh_lag_ms`.
  - Deleted `NdjsonMetricExporter.ALLOWED_TAG_KEYS` (28-entry list) and
    its fallback iteration path; un-registered metrics now emit `{}` for
    tags rather than relying on a global allowlist. The SDK's per-View
    `setAttributeFilter` carries the filtering responsibility.
  - `@Deprecated(forRemoval = true)` on
    `Telemetry.histogram(name, tags, config, value)` default method,
    pointing to `MetricCatalog`. Removed in Phase 3 alongside `meter()`.
  - `LocalTelemetry`'s override has `@SuppressWarnings("removal")` to
    bridge until Phase 3 deletion.

**Open Questions resolved (defaults applied):**

1. `collection`/`shard` tags on watcher + OCR: dropped (preserves wire
   format; restoring as `cardinalityLimit(64)` deferred to a follow-up
   when multi-collection becomes a real requirement).
2. `VduBatchProcessor.recordFailed(reason)` exception-message tag: dropped
   (slf4j logging continues at same callsite). F10 follow-up removed the
   now-unused `String reason` parameter from the method signature.
3. `extraction.timeout_total` namespace: separate `ExtractionMetricCatalog`
   (namespace `extraction`), wire format byte-stable.
4. `agent.retry.total` `attempt` tag: bounded `String` with
   `cardinalityLimit(16)` (no enum needed).
5. PR shape: single PR with logical commit groups (matches F1–F8 fixes).

**Constant `component` tags (post-Phase-2 F2 fix):** the initial Phase 2e
implementation dropped single-value `component` tags ("rag_retrieval",
"vdu_batch", "vdu", "content_extractor") on the basis that single-value
tags add no information. This was a wire-format byte-stability violation
not authorized by the Open Questions. F2 restored them — the metric name
disambiguates the subsystem, but external NDJSON consumers may have been
matching on `component`, and the plan defaulted to byte-stable.

**Verification (Phase 2):**

- `./gradlew.bat build -x test` passes.
- `./gradlew.bat test` passes except for two pre-existing flakes (per
  `docs/observations.md:188`): `NastyCorpusTest$BatchProcessing` and
  `NastyCorpusTest$ArchiveBinaryGuardrails` (missing `nasty-archive.zip`
  fixture, locally flaky, CI skips via `CI=true`).
- ArchUnit `LayeringEnforcementTest` passes.
- Lockfiles regenerated (`./gradlew.bat --no-configuration-cache
  resolveAndLockAll --write-locks`).

### Deferred Phase 2 follow-ups (out-of-scope, future work)

The post-Phase-2 critical-analysis pass identified four items that are
deferred rather than included in F1–F10:

- **`JvmRuntimeGauges` migration to a `JvmMetricCatalog`.** 10
  `head.jvm.*` / `worker.jvm.*` gauges still emit via legacy
  `telemetry.gauge(...)` (this set was not in the original Phase 2
  audit). Legacy emit path continues to function; deserves its own
  Phase 2.5 follow-up.
- **`ApiErrorHandler` legacy emit path migration.** Phase 2c / 2f left
  `ApiErrorHandler.recordError(...)` on `telemetry.counter(...)`. The
  catalog View for `api.error.total` is registered in both Head
  bootstraps (HeadlessApp + LauncherEnvironment post-F1), so the legacy
  emit works correctly — the View applies by metric name. Migrating the
  ~20 controller callsites to take an `HeadApiMetricCatalog` is Phase 3
  cleanup alongside `Telemetry.histogram(...)` removal.
- **`AttributesUseRuleTest` exemption documentation.** The test exempts
  3 span-instrumentation files; the comment-only docs update is trivial
  but skipped here. Inline with the next telemetry PR.
- **`LayeringEnforcementTest` package-name fix.** The rule's matcher is
  `io.justsearch.app.launcher..` (with a dot) while the actual package
  is `io.justsearch.applauncher` (no dot). F1 routed around this by
  relocating UI catalogs to `app-services/observability`. Fixing the
  rule itself (so `applauncher → ui` is allowed legitimately) is a
  separate architectural decision for the project owner — F1's
  relocation is a clean alternative regardless.

### What remains (Phases 3–6)

**Phases 3–6**: per the original phase plan in §Phases below. None were
attempted in the Phase 2 implementation pass.

### Post-shipment fixes (2026-04-25, post-Phase-1 critical analysis)

After Phases 0–1 landed, a critical-analysis pass identified design defects
and bugs-in-waiting that erode the structural guarantees the catalog refactor
was meant to provide. Six fixes shipped before any further migration:

- **F1 — Per-metric tag-key ordering reaches NDJSON exporter.** The exporter
  now plumbs `Map<String, List<String>> tagKeyOrderByMetric` from the
  catalogs and iterates per-metric ordered keys in `tagsJson(metricName,
  attrs)`. Falls back to the legacy `ALLOWED_TAG_KEYS` list for
  un-registered metrics. Eliminates a bug-in-waiting that surfaces the
  moment Phase 2 deletes `ALLOWED_TAG_KEYS`. Verified by new
  `TagOrderTest` (two histograms with reversed key declaration orders;
  asserts each emits in its own declared order).

- **F2 — Final-field catalogs with constructor injection.** Replaced the
  mutable-public-fields + `bind()` pattern with `public final` instrument
  fields populated at construction. Bootstrap order changed from "catalog
  pre-LocalTelemetry, bind post-LocalTelemetry" to "definitions
  pre-LocalTelemetry (via `MetricCatalog.of(namespace, DEFINITIONS)`),
  catalog instance post-LocalTelemetry against `tel.registry()`". Static
  block in `IndexRuntimeMetricCatalog` validates namespace prefix at class
  load. New `CatalogImmutabilityTest` reflectively asserts every
  `Metric`-typed field is `final`.

- **F5 — Drop unwired `archivedTo`/`surfacedAt` API.** Removed
  `RrdArchive.java` and `StatusEndpoint.java`; removed
  `archivedTo`/`surfacedAt` from `MetricDefinition.Builder`; removed
  corresponding fields from the record. Phase 3 reintroduces these when
  RRD/status wiring lands.

- **F6 — Replace unchecked `AutoCloseable` cast.** `GaugeMetric` and
  `ObservableCounterMetric` now store the OTel handle as `Object`; close
  uses `if (handle instanceof AutoCloseable c)`. The cast in
  `LocalTelemetry.CatalogRegistry`'s `buildGauge`/`buildObservableCounter`
  is gone.

- **F7 — Cache `Meter` per namespace.** `CatalogRegistry` now holds a
  `Map<String, Meter> meterByScope` populated via `computeIfAbsent`.
  Multiple metrics sharing a namespace share one `Meter` instead of
  rebuilding per metric.

- **F8 — `TestMetricRegistry` testFixture.** Added `java-test-fixtures`
  plugin to `modules/telemetry`. New `TestMetricRegistry` implements
  `MetricRegistry` backed by an in-memory OTel SDK + capturing exporter;
  exposes typed query API (`counterValue`, `histogramCount`,
  `histogramBucketCounts`, `emittedNames`). `WorkerLuceneTelemetryAdapterTest`
  migrated from real `LocalTelemetry` + NDJSON parsing to this fixture
  (14 cases). Wire-format guarantee continues via
  `IndexRuntimeWireFormatRegressionTest` which uses real `LocalTelemetry`.

#### Test-failure investigation (F3)

The three test failures reported during Phase 1 verification are all
pre-existing flakes unrelated to the catalog refactor:

- `LifecycleContractTest.healthReturnsSchemaV1AndGates*`: each `@Test` calls
  `LocalApiServer.builder(...).build()` which starts a Javalin instance.
  The "Server already started — Javalin instances cannot be reused" error
  is a per-test isolation issue in the Javalin builder, not a telemetry
  side-effect. Confirmed by reading
  `LifecycleContractTest.java:78-96` — no telemetry interaction.

- `NastyCorpusTest.nastyFilesDoNotStopBatch`: `IOException: File does not
  exist: ...nasty-archive.zip`. Pre-existing per
  `docs/observations.md:188` — locally flaky, CI skips via `CI=true`.

- `LambdaMartBenchmarkTest`: `p99 latency 68ms exceeded 10ms threshold`.
  Pre-existing per `docs/observations.md:189` — environmentally sensitive
  benchmark assertion.

#### Legacy/catalog View interaction (F9)

OTel applies all matching Views per instrument; if both a catalog View and
a legacy `LocalTelemetry`-constructor-registered View match the same
instrument name, both produce metric streams (potentially conflicting). In
the Phase 1 ship there is no conflict because the catalog only registers
Views for `index.runtime.*` metrics (9 names) while the legacy Views in
`LocalTelemetry`'s constructor target `pipeline.stage_ms`,
`llm.latency_ms`, `api.request_ms`, `api.stream.ttft_ms`,
`plugins.stage.load_ms`, and `index.runtime.refresh_lag_ms` (the latter
is currently unemitted dead config). The legacy and catalog View sets are
disjoint.

Phase 2 must delete each legacy View as it migrates the corresponding
metric to a catalog. If a Phase 2 catalog declares one of the
legacy-View-targeted metrics without first removing the legacy View,
the SDK will produce two streams per emit, and NDJSON output will contain
duplicate lines for the same metric name with different bucket bounds.

The original 417 framed six follow-up items from 406's observability shipment as
independent landings (Tier 4.1–5.3, ~9 person-days as a punch list). That
framing missed the underlying structural defect: the items are symptoms of one
disease, and shipping them as a punch list leaves the disease intact while
permitting new instances of it (412–415 reproduce the same defects). This
tempdoc reframes 417 as a single structural refactor — a typed metric catalog
that makes silent-drop emission impossible by construction.

The original investigation framing is preserved in git history at the prior
revision of this file. The filename slug
(`tier4-tier5-followups-from-406`) is now stale; rename to
`metric-catalog-refactor` is recommended but deferred to a user decision.

## Diagnosis

The current `Telemetry` interface treats metric identity, schema, configuration,
and routing as **arguments to every emit call** rather than properties of a
metric. This is fire-and-pray emission: callers declare intent (bucket bounds,
tags, reasons), the emit pipeline silently drops anything it doesn't recognize,
and the producer has no way to know its data reached the exporter intact. Every
follow-up the original 417 enumerated instantiates this defect:

| Symptom | What's silently lost | Underlying cause |
|---|---|---|
| `HistogramConfig` ignored | Bucket bounds + percentile config | Histogram config is an emit-time arg, not an instrument property |
| Histogram instrument rebuilt per call | (Performance, not correctness) | No registry mapping name → instrument |
| Tag allowlist strips | Tag keys (circuit-breaker `from`/`to`, `outcome`, `component`, `collection`) | Allowlist is global static; emitter has no schema declaration |
| Stringly-typed reasons | Compile-time typo prevention | Reasons are free-form strings on a `Map<String,String>` |
| Exemplars opt-in via env var | Trace correlation as default | Exemplar policy is per-MeterProvider, not per-metric |
| Gauges shipped, no UI/RRD wiring | Producer/consumer schema linkage | Status fields, RRD entries, UI gauges are unrelated codepaths |
| Pattern undocumented | Structural enforceability | Pattern is prose, not type signatures |

Shipping the seven items as proposed fixes each symptom but does not fix the
disease. New telemetry added by 412–415 (and any future adopter) will reproduce
the same defects, because the interface still permits them. The audit found
~17 distinct `commit_ms` reason strings emitted as free text across 18
callsites — that is what unconstrained emission produces in 18 months. There is
no reason the next 18 months will be different.

## Principle

**A metric is a contract, not a string.** Every emitted metric has a registered,
typed contract; emission outside the contract is a compile error, not a silent
strip.

This is the design philosophy 406 just shipped for lifecycle, applied to
telemetry:

| 406 (lifecycle) | 417 (telemetry) |
|---|---|
| Sealed `LuceneRuntime` permits {Running, ReadOnly, Deferred} | Sealed `Metric` permits {CounterMetric, HistogramMetric, GaugeMetric} |
| Phase types prevent `pruneOps()` on `DeferredRuntime` at compile time | Typed instruments prevent `commitMs.record(..., "admin_trigger")` at compile time |
| Single composition site: `RuntimeSession` constructor | Single composition site: `MetricCatalog.register()` |
| Compile-fail tests prove invariants (`DeferredRuntimeCompileFailTest`) | Compile-fail tests prove invariants (same JavaCompiler harness) |

The argument that justified 406's lifecycle work is identical here. If
lifecycle deserves type-level structure, telemetry does too.

## Pre-implementation investigation findings

Before committing to phases, every architectural assumption was verified against
either the OTel Java SDK source, OTel spec, or the codebase. Findings below are
grounded; design choices in the next section follow from them.

### F1 — OTel SDK 1.60.1 ViewBuilder public API

The stable public `ViewBuilder` exposes:
`setName`, `setDescription`, `setAggregation(Aggregation)`,
`setAttributeFilter(Set<String>)`, `setAttributeFilter(Predicate<String>)`,
`setCardinalityLimit(int)` (since 1.44).
**There is no public `setExemplarReservoirSupplier`.** Per-metric exemplar
reservoirs are spec-required but unstable in the Java SDK.

### F2 — Bucket bounds via Advice API (per-instrument)

`setExplicitBucketBoundariesAdvice(List<Double>)` on histogram builder is
stable since OTel 1.32 (we are on 1.60.1). Views take precedence; absent a
View, advice applies. Per-instrument advice is the cleanest way for the
catalog to declare bucket bounds without polluting `SdkMeterProvider`'s View
registry with one entry per histogram.

### F3 — Tag schema enforcement via per-View `setAttributeFilter`

The public `ViewBuilder.setAttributeFilter(Set<String>)` is the structural
replacement for `NdjsonMetricExporter.ALLOWED_TAG_KEYS`. It applies before
aggregation; non-allowed keys are stripped at the SDK layer, not at
NDJSON-export time. Per-metric (per-View) filtering is built in.

### F4 — Exemplar control is split: global filter + export-time post-filter

**Updated 2026-04-25 by spike: `setExemplarFilter` is PUBLIC stable API in OTel
1.60.1**, available directly on `SdkMeterProviderBuilder`. The `ExemplarFilter`
interface lives in `io.opentelemetry.sdk.metrics` (public package), not
`internal.exemplar`. The `internal.SdkMeterProviderUtil` no longer exposes
`setExemplarFilter` in 1.60.1 — earlier OTel versions had it there, the API was
promoted. Available filters: `alwaysOn`, `alwaysOff`, `traceBased`. This
invalidates **Open Question 5**; no internal-API dependency is needed.

**Per-View exemplar suppression is still not in the public API.** Per-metric
"no exemplars" remains an export-time filter.

Practical consequence: per-metric "no exemplars" (e.g., for hot-path
`write_barrier_wait_us`) is implemented as **export-time filtering in
`NdjsonMetricExporter`**: the catalog declares `Exemplars.OFF` for a metric;
the exporter reads the catalog's policy at export time and drops exemplars for
that metric name. The OTel SDK still records them; the wire-format simply
omits them. Storage cost is bounded since OTel uses a fixed-size reservoir
per histogram point.

`Exemplars.SAMPLED(rate)` is **not feasible** without a custom
`ExemplarFilter` implementation, and even then `setExemplarFilter` only sets
**one** filter for the whole MeterProvider. Drop this option from the design.

### F5 — Startup ordering: two-phase boot via DI

`LocalTelemetry` is instantiated in 3 bootstrap sites (`LauncherEnvironment`
for Head, `KnowledgeServer` for Worker, `HeadlessApp` for Headless). Each
process has exactly one `LocalTelemetry` instance, constructed before module
wiring. The natural pattern for the catalog refactor:

1. Each module exposes a `MetricCatalog` as a static value (or builder) — a
   pure data declaration, no SDK calls.
2. The bootstrap code calls `new LocalTelemetry(dataDir, flushMs, ...,
   List.of(IndexRuntimeMetricCatalog.INSTANCE, IpcCatalog.INSTANCE, ...))`.
3. `LocalTelemetry`'s constructor builds `SdkMeterProvider` with all views
   from all catalogs in one shot.
4. Modules then receive (a) a typed reference to their catalog (compile-time
   typed instruments) and (b) the runtime `Telemetry`.

No reflection, no class-load magic, no deferred boot. Explicit DI; matches the
existing bootstrap shape.

### F6 — `meter(scope)` escape hatch is a single callsite

`Telemetry.meter(scope)` is used in exactly **one** place:
`KnowledgeServer.registerOtelObservableCallbacks` (lines 1556–1613) bridges
~25 `OperationalMetrics` LongAdder counters to OTel observable callbacks via
the raw `Meter`. Migrate as part of Phase 3 (it is a producer/consumer wiring
change), then delete `meter(scope)` from the `Telemetry` interface entirely.
No deprecation period needed.

### F7 — Test scaffolding migration is smaller than feared

15 test files reference `Telemetry`. The canonical capture pattern is the
existing `TestTelemetry` in `modules/app-indexing/src/testFixtures` (~100
lines). Migration: write `TestMetricCatalog` (~150 lines, same shape with
typed lookup), update the testFixtures to use it, then ~6 test-local fakes
(`RecordingTelemetry`, `FakeTelemetry`, `CapturingTelemetry`, `NoopTelemetry`,
inline implementations) become mechanical updates. The 14 sites in
`WorkerLuceneTelemetryAdapterTest` are part of Phase 1 anyway.

Realistic: ~1–1.5 days for the test scaffolding migration.

### F8 — ArchUnit can ban `Attributes.of(...)` outside `modules/telemetry`

Direct precedent: `EnvRegistryDirectReadTest` already bans
`EnvRegistry.get(...)` calls outside `io.justsearch.configuration` via
`JavaMethodCall.getTargetOwner().isEquivalentTo(...)` and method-name
matching. The proposed Attributes ban is a near-mechanical clone, including
the exemption mechanism for bootstrap code.

### F9 — Wire-format regression test: semantic equivalence, not byte-identity

Each NDJSON line embeds an ISO timestamp from `getEpochNanos()`, so
byte-identity across runs is impossible. Tag-key iteration order today is
`ALLOWED_TAG_KEYS` list-declaration order (deterministic). Bucket order from
histogram point data is deterministic. **The right test is parse-each-line +
strip `t` field + assert structural equivalence.** Use a
`LinkedHashSet<String>` (or list-backed allow-set) when wiring per-metric
`setAttributeFilter` so tag-key emission order is preserved across the
refactor.

### F10 — Cross-process boundary already clean

Worker writes `metrics-worker.ndjson`; Head writes `metrics.ndjson`. Distinct
`serviceName` per process (`justsearch-worker`, `justsearch-headless`,
`justsearch-launcher`). Namespace prefixes by convention (`worker.*`,
`head.*`, `index.runtime.*`). Catalog refactor preserves this naturally —
each process has its own `LocalTelemetry`+catalog set. Add a namespace-prefix
declaration on `MetricCatalog` and an ArchUnit check that each metric's name
starts with its catalog's prefix.

## Design

The refactor introduces three layers, separating identity, schema, and routing:

- **Domain layer** (unchanged): components emit sealed domain events
  (`TelemetryEvents.onCommit(latency, reason)`).
- **Adapter layer** (thinner): translates events to catalog calls. The
  WorkerLuceneTelemetryAdapter pattern stays, but its body shrinks because the
  catalog enforces type-checking, bucket config, and tag-key filtering.
- **Catalog layer** (new): registers `MetricDefinition` values; wires the OTel
  SDK once at boot via concrete primitives (per F1–F4); routes emissions to
  NDJSON, RRD, and status surfaces according to declared consumer hints.

### MetricCatalog

A per-module immutable catalog assembled at boot, before the OTel
`SdkMeterProvider` is built. Each entry is a `MetricDefinition`:

```
MetricDefinition
├── fully-qualified name (compile-time constant, namespace-scoped)
├── unit (typed: Milliseconds, Microseconds, Bytes, Count, Ratio)
├── instrument kind (Counter | Histogram | Gauge | ObservableCounter)
├── tag schema (TagKey → TagValueSet)
├── histogram config (bucket bounds; only valid on Histogram)
├── exemplar policy (TraceBased | Off)         ← see F4
├── consumer hints (RrdArchive? StatusEndpoint? AlertTarget?)
└── namespace prefix (declared on the catalog, inherited by metrics)
```

The catalog wires the SDK once at startup using verified primitives:

- **Bucket bounds** → per-instrument `setExplicitBucketBoundariesAdvice(...)`
  on the histogram builder (F2). No global Views needed for bucket config.
- **Tag schemas** → per-View `setAttributeFilter(LinkedHashSet<String>)`
  registered for each metric name (F3, F9). Replaces
  `NdjsonMetricExporter.ALLOWED_TAG_KEYS` entirely.
- **Global exemplar filter** → `SdkMeterProviderBuilder.setExemplarFilter(...)`
  (public stable API in 1.60.1, per spike). Default: `traceBased()`.
- **Per-metric exemplar suppression** → catalog-driven export-time filter in
  `NdjsonMetricExporter` (F4). Drops exemplars from the wire format for
  metrics declared `Exemplars.OFF`.
- **Cardinality guards** → per-View `setCardinalityLimit(int)` (since 1.44),
  defensive against `Bounded<String>` schemas with runaway tag values.

### Type-parameterized instruments

```java
HistogramMetric<CommitTags> commitMs = catalog
    .histogram("index.runtime.commit_ms", CommitTags.class)
    .unit(Unit.MILLISECONDS)
    .buckets(Buckets.TIME_HISTOGRAM)
    .exemplars(Exemplars.TRACE_BASED)
    .register();

commitMs.record(42L, CommitTags.of(CommitReason.DRAIN));     // ✅
commitMs.record(42L, CommitTags.of("drain"));                // ❌ doesn't compile
commitMs.record(42L, OtherTags.of(...));                     // ❌ doesn't compile
```

The instrument carries its tag schema as a type parameter. Emission is
type-checked at every callsite. Three of the original symptoms collapse out as
side effects: bucket config can't be ignored (it's not a parameter), tag keys
can't be stripped at NDJSON layer (the SDK applies the View filter — F3),
reasons can't be typo'd (they're sealed types).

### Sealed tag schemas with wire-format metadata

```java
sealed interface CommitReason permits Drain, Timer, IndexingLoopIdle,
        IndexingLoopRebuildStamp, IndexingLoopShutdown, GrpcDeleteByPath,
        GrpcDeleteById, GrpcUpdatePaths, BackfillSplade, BackfillNer,
        BackfillEmbedding, BackfillEmbeddingChunk, BackfillCombined,
        BackfillCombinedFinal, BackfillBgeM3, SyncPrune, Prune, Reset {
    String wireValue();
    default Attributes toAttributes() {
        return Attributes.of(AttributeKey.stringKey("reason"), wireValue());
    }
}
record Drain() implements CommitReason {
    public String wireValue() { return "drain"; }
}
```

The wire format is metadata on the type, not free text. Renaming `Drain`
doesn't change `wireValue()`. The REST/gRPC boundary parses the wire string
back to a sealed type — an unknown wire value becomes a typed
`UnknownReason`, distinct from a typo'd metric series.

### Per-metric exemplar policy

`Exemplars.TRACE_BASED` is the catalog default and matches the OTel spec
default. `Exemplars.OFF` for hot-path metrics where exemplar overhead
matters (`write_barrier_wait_us`).

Implementation per F4: catalog declarations are read by `NdjsonMetricExporter`
at export time. The exporter checks each metric's declared policy and drops
exemplars from wire format when policy is `OFF`. The current global env-var
toggle (`JUSTSEARCH_TELEMETRY_METRICS_EXEMPLARS`) is removed; default is
trace-correlated.

This is less elegant than the spec's per-View exemplar reservoir model, but
the public Java SDK does not expose the latter (F1, F4). When OTel surfaces
`setExemplarReservoirSupplier` on `ViewBuilder`, the export-time filter
becomes redundant and can be removed.

### Producer/consumer wiring is part of the catalog

```java
GaugeMetric<EmptyTags> writerPendingDocs = catalog
    .gauge("index.runtime.writer_pending_docs", EmptyTags.class,
           () -> session.pendingDocs.get())
    .unit(Unit.COUNT)
    .archivedTo(RrdArchive.MINUTE_RESOLUTION)
    .surfacedAt(StatusEndpoint.CORE_INDEX_VIEW, "writerPendingDocs")
    .register();
```

`archivedTo` auto-registers the gauge in the RRD curated set. `surfacedAt` is
checked at boot (and by an ArchUnit-style test, F8) against the corresponding
API record's fields. The hidden dependency the original 5.2 missed — the 4
gauges are inline-computed in `/api/status`, not OTel-emitted, so adding them
to `RrdMetricStore.CURATED_METRICS` populates nothing — becomes structurally
impossible: declaring a status surface implies emission, and the catalog
enforces the field-name match.

### TelemetryEmitter as a structural type

```java
sealed interface IndexRuntimeEvent
    permits CommitEvent, SwapStartEvent, SwapCompleteEvent,
            DrainTimeoutEvent, WriteBarrierContentionEvent,
            ValidationFailureEvent, HardDeleteEvent, SoftDeleteEvent;

interface TelemetryEmitter<E extends DomainEvent> {
    void emit(E event);
}
```

The value package owns the sealed event hierarchy. Adapters pattern-match
exhaustively — adding a new event breaks every adapter at compile time.
ADR-0027 documents this as the type signature, not a 5-element prose recipe.
Adopters (412–415) can't accidentally diverge; the type system is the spec.

## Phases

### Phase 0 — Catalog scaffolding (no migrations)

Land `MetricCatalog`, `MetricDefinition`, `TagSchema`, the sealed instrument
types, and the SDK wiring under `modules/telemetry`. No production callsites
change yet. Per F2/F3/F4: SDK wiring uses
`setExplicitBucketBoundariesAdvice` for bucket bounds,
`setAttributeFilter(LinkedHashSet)` per-View for tag schemas, and
`SdkMeterProviderUtil.setExemplarFilter` (internal/experimental) for the
global exemplar filter. Per F5: `LocalTelemetry` constructor takes
`List<MetricCatalog>` and assembles the `SdkMeterProvider` in one shot.

Includes:
- compile-fail tests via the same JavaCompiler harness used by
  `DeferredRuntimeCompileFailTest`: emission with the wrong tag schema, the
  wrong instrument type, or an unregistered metric name does not compile.
- structural test: every instrument registered on the catalog has a non-null
  wire-format mapping for every tag value.
- ArchUnit rule (F8): production code outside `modules/telemetry` cannot
  call `Attributes.of(...)` or `Attributes.builder()` — must go through
  `TagSchema`. Models on `EnvRegistryDirectReadTest`.
- ArchUnit rule (F10): every metric registered on a catalog has a name
  starting with the catalog's declared namespace prefix.
- `TestMetricCatalog` testFixtures (~150 lines, F7).

### Phase 1 — Migrate `WorkerLuceneTelemetryAdapter` (proof case)

Migrate every metric in `index.runtime.*` to the catalog. This is the
ground-truth migration: it proves the design carries the existing semantics
(bucket bounds applied, tags reach NDJSON, exemplar policy honored).

Includes:
- `IndexRuntimeMetricCatalog` registers all 8 metrics with their schemas,
  bucket configs, and exemplar policies (`write_barrier_wait_us` declares
  `Exemplars.OFF`).
- `WorkerLuceneTelemetryAdapter` becomes a thin façade over the catalog.
- The 14 tests in `WorkerLuceneTelemetryAdapterTest` migrate to assert
  against the catalog's typed API (uses `TestMetricCatalog`).
- Wire-format regression test (F9): a property test that emits a fixed
  metric sequence pre-refactor and post-refactor, parses each NDJSON line,
  strips the `t` field, asserts structural equivalence. Catches regressions
  in tag-key ordering, bucket count, exemplar suppression.

### Phase 2 — Migrate the rest of the bridges

Migrate the 6 callsites identified in the original 4.2 audit (`IpcTelemetry`,
`TimeboxedContentExtractor`, `VduProcessor`, `VduBatchProcessor`,
`RemoteDocumentService`, `WatcherBootstrap`) plus
`IndexingLoop.recordStageMs` and the LLM/RAG metrics. Each gets its own
per-module `*MetricCatalog` registered on boot. Existing dropped tags
(circuit-breaker `from`/`to`, `outcome`, `component`, `collection`/`shard`)
become declared schema entries — they reach NDJSON because the per-View
attribute filter (F3) now allows them.

After Phase 2: `NdjsonMetricExporter.ALLOWED_TAG_KEYS` is deleted. The SDK's
per-View `setAttributeFilter` carries the filtering responsibility.

### Phase 3 — Producer/consumer wiring

The 4 `index.runtime.*` gauges currently inline-computed in `/api/status`
(`writerQueueDepth`, `writerPendingDocs`, `commitCount`, `refreshLagMs`)
are migrated to OTel async gauges via the catalog, with
`archivedTo(RrdArchive.MINUTE_RESOLUTION)` and
`surfacedAt(StatusEndpoint.CORE_INDEX_VIEW, ...)` declarations.
`RrdMetricStore.CURATED_METRICS` becomes a derived view of the catalog
rather than a hand-maintained list. ArchUnit test fails CI if a `surfacedAt`
declaration doesn't match the API record's fields.

Also folds in F6: migrate
`KnowledgeServer.registerOtelObservableCallbacks` (the single `meter(scope)`
callsite, ~25 OperationalMetrics bridges) into a `WorkerOpsMetricCatalog`,
then delete `Telemetry.meter(scope)` from the interface.

### Phase 4 shipped (2026-04-25)

- `docs/decisions/0027-metric-catalog-as-telemetry-contract.md` — ADR
  codifying the catalog as the runtime telemetry contract: declaration
  → boot → wireup → emission lifecycle, wire-format guarantees
  (per-View tag-key filter, bucket bounds, cardinality limits, exemplar
  policy), producer/consumer wiring (`archivedTo` / `surfacedAt`),
  test strategy (`TestMetricRegistry` / `NoopMetricRegistry` /
  per-catalog smoke), 4 alternatives considered, consequences. ~5 KB.
- `docs/explanation/08-observability.md` — replaced "strict tag
  allowlist" prose with a "Metric catalog ownership" table mapping
  every metric namespace to its owning catalog, citing ADR-0027.
  Stale `NdjsonMetricExporter.ALLOWED_TAG_KEYS` reference replaced
  with `MetricDefinition.tagKeys(...)`.
- `docs/llms.txt` regenerated (`node scripts/docs/llmstxt-generate.mjs`,
  108 docs indexed).
- Skills synced (`node scripts/docs/skills-sync.mjs`, 6 skills, 10
  sources synced).

### Phase 3 critical-analysis follow-up shipped (2026-04-25)

Post-implementation critical-analysis pass surfaced 7 issues across
Phases 3b/3d/3e. Two were silent regressions caught by inspection;
the rest are robustness gaps and cleanups. Fixes applied as a single
follow-up commit group (A1/A2/B1/B2/B3/C1):

- **A1 (correctness, P0)**: 4 metrics dropped from RRD curated set during
  Phase 3b's `LEGACY_CURATED_METRICS` refactor. The catalogs owning those
  metrics (`HeadHttpInflightMetricCatalog`, `HeadGpuMetricCatalog`,
  `IpcMetricCatalog`) didn't declare `archivedTo(STANDARD)`, so the
  curated-set derive path didn't pick them up. Restored by adding
  `.archivedTo(RrdArchive.STANDARD)` to: `head.http.inflight_requests`,
  `gpu.utilization.percent`, `gpu.memory.utilization.percent`,
  `ipc.grpc.reconnect`. Per-catalog smoke tests now assert the archive
  flag is present.
- **A2 (test coverage, P0)**: Added
  `modules/telemetry/src/test/java/.../RrdMetricStoreCatalogDeriveTest.java`
  with 6 cases covering the catalog → curated-set derive logic plus a
  pinned snapshot of `LEGACY_CURATED_METRICS`'s expected residual shape.
  Plus added `RrdMetricStore.curatedMetricsForTest()` package-private
  accessor. The original Phase 3b plan called for this test; it was
  missed and allowed A1's regression.
- **B1 (robustness)**: `VduProcessor.totalDurationMs` now records only
  if `PdfImageRenderer` was successfully constructed — restores exact
  legacy `Timer.Sample` close-on-exit semantics (no record on
  resource-construction failure). `Long totalStartNanos = null` sentinel
  outside the try-with-resources, set inside the body, checked in finally.
- **B2 (robustness + performance)**:
  `ApiErrorHandler.recordError` now caches `CounterMetric<ApiErrorTags>`
  per `LocalTelemetry` instance via `ConcurrentHashMap`, eliminating
  per-call wrapper allocation. A one-shot WARN logs when a
  `LocalTelemetry` lacks the catalog DEFINITIONS (previously silent
  failure). New `ApiErrorHandlerCachingTest` covers caching + missing-
  catalog handling. `ApiErrorHandler.clearCachesForTest()` is the
  package-private test isolation helper.
- **B3 (cleanup)**: Deleted `java.util.Objects` and
  `java.util.concurrent.ConcurrentHashMap` dead imports in
  `AgentLoopServiceTest`, left over from Phase 3e's `FakeTelemetry`
  deletion.
- **C1 (docs accuracy)**: This section + the F6 note below.

**Breaking change note (F6, retroactive Phase 3e)**:
`JvmRuntimeGauges.register(Telemetry, String)` return type changed from
`List<Telemetry.Gauge>` to `void`. The legacy returned list was never
read in production after Phase 3d (callers stored it in unused fields
that B1's KnowledgeServer/LocalApiServer cleanup deleted). Internal-only;
no external API consumers.

### Phase 3e shipped (2026-04-25)

- `ApiErrorHandler.recordError(Telemetry, ApiErrorCode, String)` rewritten to
  emit through `HeadApiMetricCatalog.errorTotal` (pattern-matched
  `LocalTelemetry` cast → `registry().buildCounter(ERROR_TOTAL)` →
  `increment(new ApiErrorTags(...))`). Single-line change keeps every
  controller's existing `ApiErrorHandler.toResponse(Exception, Telemetry,
  String)` callsite unchanged — the catalog is the only emit path under the hood.
- `Telemetry` interface collapsed to a marker `extends AutoCloseable` —
  deleted `Counter counter(...)`, `Timer timer(...)`, `Gauge gauge(...)`
  abstract methods plus the `Counter` / `Timer` / `Gauge` / `Tags` /
  `HistogramConfig` nested types and `Timer.Sample`. ADR-0027 documents
  the marker shape (Phase 4).
- `LocalTelemetry`: deleted the `counter/timer/gauge` overrides and the
  `toAttributes(Tags)` helper. The always-on `jvm.uptime_ms` gauge is
  now built directly via `meterProvider.gaugeBuilder(...)` (preserves
  legacy unprefixed metric name for tooling parity).
- `JvmRuntimeGauges.register(...)` return type changed from
  `List<Telemetry.Gauge>` to `void`. `KnowledgeServer` and `LocalApiServer`
  drop their unused `jvmBaselineGauges` fields; `JvmRuntimeGaugesTest`
  rewrites the gauge-count assertion to count entries in the wire format
  instead of the (now-removed) returned list.
- Test fakes pruned: `TestTelemetry` (testFixture in app-indexing),
  `KnowledgeServerBootstrap.NoopTelemetry`, `AppFacadeBootstrapTest.NoopTelemetry`,
  and `AgentLoopServiceTest.FakeTelemetry` all collapse to marker
  `implements Telemetry` with just `close()`. `FakeTelemetry`'s in-memory
  counter store + `CounterKey` record were dead code (counter assertions
  already migrated to `TestMetricRegistry` in Phase 2d).
- `LocalTelemetryTest`, `LocalMetricsExporterTest` rewritten to drive metric
  emission through the catalog registry instead of `t.counter/timer/histogram`.
  Inline `MapTags` schema satisfies tag-key allowlists without a typed
  per-test record.
- Verification: `:modules:{telemetry,app-services,worker-services,
  app-launcher,app-agent,app-indexing,app-search,ui}:test` all pass.
  Full project `./gradlew build -x test` passes; only the 2 pre-existing
  `NastyCorpusTest` flakes remain unrelated.

### Phase 3d shipped (2026-04-25)

- `JvmMetricCatalog` (`modules/telemetry`): prefix-parameterized catalog
  carrying the 10 JVM baseline gauges (threads.live/daemon, memory.heap.{used,
  committed,max}_bytes, memory.nonheap.{used,committed}_bytes,
  memory.process.virtual_bytes, gc.collection_{count,time_ms}). Static factory
  `definitionsFor(prefix)` returns the prefix-baked `MetricDefinition`s.
  `threads.live` and `memory.heap.used_bytes` declare
  `archivedTo(RrdArchive.STANDARD)`.
- `JvmRuntimeGauges.register(Telemetry, prefix)` now constructs
  `JvmMetricCatalog(lt.registry(), prefix)` when telemetry is `LocalTelemetry`;
  the legacy 1-arg fallback returns an empty list. Async-gauge handles are
  retained on `LocalTelemetry`'s `gaugeHandles` list (drained on shutdown).
- Bootstrap wireup: `KnowledgeServer` registers `catalogFor("worker")`,
  `HeadlessApp` registers `catalogFor("head")`, and `LauncherEnvironment`
  registers `catalogFor("launcher")` (new — previously the desktop-launcher
  process emitted no JVM metrics).
- `VduPassTags` + 3 histogram definitions on `VduMetricCatalog`
  (`vdu.pass1.duration_ms`, `vdu.pass2.duration_ms`, `vdu.total.duration_ms`).
  `VduProcessor` now emits via `catalog.passXDurationMs.record(...)` with
  manual `System.nanoTime()` timing; legacy `Telemetry.Timer.Sample` deleted.
  `total.duration_ms` records in the outer finally to match legacy
  close-on-exit semantics on both success and failure paths.
  (See critical-analysis fix B1 below: `totalStartNanos` is captured
  inside the try-with-resources so failure to construct
  `PdfImageRenderer` doesn't trigger a spurious record — exact byte-
  stability with the legacy `Timer.Sample`.)
- `Telemetry.histogram(...)` default method deleted; `LocalTelemetry`
  override deleted; `TelemetryDefaultMethodsTest` deleted; testFixture
  `TestTelemetry` and inline `KnowledgeServerBootstrap.NoopTelemetry`
  (alongside `AppFacadeBootstrapTest` Telemetry stubs) updated.
- Tests rewritten: `LocalMetricsExporterTest`'s `t.histogram(...)`
  callsites moved to `t.registry().buildHistogram(name)` with an inline
  `MapTags` schema; `HistogramBucketsTest` migrated to
  `EmptyTags`-typed buildHistogram; `JvmRuntimeGaugesTest` updated to
  pass `JvmMetricCatalog.catalogFor(prefix)` at LocalTelemetry construction.
  New `JvmMetricCatalogSmokeTest` validates definitions, archive flags,
  and end-to-end wire-format.
- `UnreferencedCodeTest.KNOWN_UNREFERENCED` allowlists
  `AgentLoopService.forTesting` (test-only static factory).
- Verification: `:modules:{telemetry,app-services,worker-services,
  app-launcher,indexer-worker,app-indexing,app-agent,ui}:test` all pass.
  Full project `./gradlew build -x test` passes; only the 2 pre-existing
  `NastyCorpusTest` flakes remain unrelated.

### Phase 4 — ADR-0027 + canonical doc

ADR-0027 codifies the catalog as the project's telemetry contract. Pairs
with a canonical doc under `docs/explanation/` describing the catalog model,
sealed-event pattern, and the migration story. The doc is the citation
target for future adopters; ADR-0027 documents the type signatures.

### Design-property alignment analysis (post-Phase-4, theoretical retrospective)

Written 2026-04-25 after Phase 4 shipped. This section grades the shipped
implementation against the design properties the tempdoc declared in
**Diagnosis**, **Principle**, and **Design** — purely theoretically, asking
"did the structural intent land?" not "is the code correct?".

Phases 0–4 only; Phases 5 (optional) and 6 (deferred) are not graded here.

**Core thesis** (lines 442–445, line 480): every metric is a registered,
typed contract; emission outside the contract is a compile error, not a
silent strip; the disease (fire-and-pray emission) is cured by construction.

**Verdict**: the core thesis is achieved. 12 of 15 sub-properties land as
written; 2 are realized via a different mechanism with equivalent
structural strength; 1 is partial (the ADR shape); 1 documented exception
sits outside the catalog discipline.

| # | Design property | Status | Reasoning |
|---|---|---|---|
| 1 | A metric is a contract, not a string | ✅ Achieved | Every emit path goes through a registered `MetricDefinition`. The legacy `Telemetry.counter/timer/gauge/histogram/meter` surface is deleted; the only emit shape is the catalog's typed instrument fields. |
| 2 | Bucket config can't be ignored | ✅ Achieved | `MetricDefinition.bucketBoundaries()` is the single source. SDK View applies before any reader. The original audit's "every histogram uses OTel defaults regardless of caller intent" defect is structurally impossible. |
| 3 | Tag keys can't be stripped at NDJSON layer | ✅ Achieved | `NdjsonMetricExporter.ALLOWED_TAG_KEYS` deleted (Phase 2f). Per-View `setAttributeFilter(LinkedHashSet)` enforces the schema at the SDK layer. |
| 4 | Reasons can't be typo'd (sealed types with `wireValue()`) | ✅ Achieved (mechanism divergence) | The Design used `sealed interface Reason permits Drain, ...` + records. Shipped uses Java enums with `wireValue()`. Both deliver: typo → compile error, exhaustive switches, byte-stable wire format. Equivalent structural strength. |
| 5 | Producer/consumer-coupling defect becomes structurally impossible | ✅ Achieved (with a layer of value-equivalence convention) | `surfacedAt` → `MetricSurfaceContractTest` validates field name match; gauge MUST be wired since the catalog constructor demands a supplier. `archivedTo(STANDARD)` → RRD picks up automatically. The original 5.2 hidden defect (gauges in CURATED_METRICS but not OTel-emitted) is structurally impossible. *Caveat*: value-equivalence between the gauge's supplier and the `/api/status` computation source is convention (both read from `RuntimeSession.runtimeGaugesSnapshot()`), not type-enforced. |
| 6 | Per-metric exemplar policy | ✅ Achieved | `MetricDefinition.exemplarPolicy()` per-metric; `NdjsonMetricExporter` reads policy at export. Global env-var `JUSTSEARCH_TELEMETRY_METRICS_EXEMPLARS` removed. |
| 7 | Compile-fail tests prove invariants | ✅ Achieved | `CatalogTypeSafetyCompileFailTest` (3 cases via JavaCompiler harness) ships in Phase 0, mirroring `DeferredRuntimeCompileFailTest`. |
| 8 | ArchUnit ban on `Attributes.of(...)` outside `modules/telemetry` | ✅ Achieved | `AttributesUseRuleTest` in `app-launcher`, exempts `TagSchema` impls + 3 span-instrumentation files. |
| 9 | Namespace prefix declared on catalog, enforced at class load + ArchUnit | ✅ Achieved | `NamespacePrefixRuleTest` (ArchUnit) + static block in each catalog (class-load validation). |
| 10 | `TelemetryEmitter<E extends DomainEvent>` as structural type (sealed events + exhaustive pattern matching) | ⚠ Diverged | Design: `sealed interface IndexRuntimeEvent permits CommitEvent, ...` + `TelemetryEmitter<E>` with pattern matching. Shipped: `LuceneRuntimeTypes.TelemetryEvents` interface with typed methods (`onCommit(reason)`, `onSwapStarted(reason)`, etc.). Both deliver "adding a new event breaks every adapter at compile time" — interface method change vs. non-exhaustive switch. Equivalent enforcement, different shape. The sealed-event pattern is documented in ADR-0027 prose but not realized in code. |
| 11 | ADR-0027 documents type signatures, not 5-element prose recipe | ⚠ Partial | Shipped ADR includes type-signature code blocks (catalog example, MetricDefinition example, emit example) but is meaningfully prose-weighted (alternatives, consequences, lifecycle narrative). The contrast with the original 5.3 instructional recipe is delivered, but the ADR is not as type-signature-pure as the tempdoc envisioned. A stricter spec would lead with the public API surface as a code block. |
| 12 | Single composition site (`MetricCatalog.register()`) | ⚠ Diverged | Tempdoc paralleled 406's "RuntimeSession constructor" as one site. Shipped: 13+ per-module `*MetricCatalog` classes, each with static `DEFINITIONS`, composed at three bootstrap callsites. Per-module catalogs deliver modular composition (the SPIRIT — no scattered emission, single typed entry per module) but not the literal single site. Acceptable engineering trade-off; the Phase 2 plan structure already shipped per-module catalogs. |
| 13 | Wire-format byte-stable migration | ✅ Achieved | Phase 2 F2 fix restored constant `component` tags; `setAttributeFilter(LinkedHashSet)` preserves tag-key emission order; bucket bounds match per-metric. 5 wire-format regression tests cover 13 catalogs (Phase 2 critical-analysis F3). |
| 14 | 412–415 cannot reproduce the disease | ✅ Achieved structurally | Legacy `Telemetry.X(...)` surface deleted → won't compile. The only emit path is via a `MetricCatalog`. Type-parameterized instruments enforce tag schemas. ArchUnit bans direct `Attributes` use. Namespace prefix is class-load-enforced. Adopters cannot accidentally diverge. |
| 15 | Adapter layer is "thinner" — catalog enforces type/bucket/tag-filter | ✅ Achieved | `WorkerLuceneTelemetryAdapter`, `IpcTelemetry`, `AgentTelemetry` are now thin façades over typed catalogs. The bridge holders shrink because the structural responsibilities moved into the catalog. |

**Documented intentional exception (CLOSED 2026-04-26)**:
~~`LocalTelemetry`'s `jvm.uptime_ms` always-on gauge is built directly via
`meterProvider.gaugeBuilder(...)` — NOT through a catalog.~~ Closed in
follow-up Step 3a (2026-04-26): introduced `BaselineMetricCatalog`
(`modules/telemetry/src/main/java/io/justsearch/telemetry/BaselineMetricCatalog.java`)
which `LocalTelemetry`'s constructor automatically appends to every
catalog list. The metric routes through the catalog substrate while
preserving legacy wire format (prefix-less name `jvm.uptime_ms`). Every
metric in the codebase is now a registered, typed catalog contract — no
exceptions.

**What's NOT a divergence (worth noting for future readers)**:

- The "compile error for unregistered metric name" claim has a string-name
  escape hatch — `registry.buildCounter("typo.metric.name")` compiles and
  throws at runtime. In practice every callsite uses
  `MetricCatalog.METRIC_NAME` constants, so a typo is a compile error in
  practice. Convention-enforced, not type-system-enforced; consistent with
  the tempdoc's framing (the tempdoc assumes constant references).
- Per-module catalogs (#12 above) is actually MORE scalable than a single
  composition site as the codebase grows. The "single site" framing was
  arguably aspirational from 406's parallel; per-module is the correct
  realization for the cardinality of metrics this codebase ships.

**What would close the remaining nuances**:

- A future tempdoc could refactor `LuceneRuntimeTypes.TelemetryEvents` from
  an interface-with-methods into a sealed event hierarchy + pattern-matching
  emitter (#10). Would primarily benefit the value-package separation
  story; runtime behavior identical.
- ADR-0027 could be tightened toward type-signature-driven spec shape
  (#11). Would improve "future adopters can read the type signature alone"
  but requires the prose to live in the canonical doc instead.
- ~~The `jvm.uptime_ms` exception could be folded into a baseline-metrics
  catalog. Trivial change; deferred for lack of forcing function.~~ **Done
  2026-04-26** via `BaselineMetricCatalog` (Step 3a follow-up).

None of these block the tempdoc's core thesis. They are second-order
alignment improvements; the current implementation already delivers the
disease cure the tempdoc demanded.

### Phase 5 (optional) — Exemplar correlation projection

After Phases 0–3, `commit_ms` and `swap_duration_ms` exemplars carry trace
IDs whenever the call originates from a tracked span. Add
`scripts/jseval/jseval/projections/metric_trace_correlation.py` to join
NDJSON exemplars with `traces.ndjson`. Backfill spans around the
`commitAndTrack` callsites that aren't currently inside `indexing.batch`
(gRPC handlers, backfill ops, drain, prune, reset) so their exemplars
become useful. Without this backfill ~16 of 17 `commit_ms` callsites emit
null exemplars regardless of policy — the original 5.1 oversimplified by
claiming "zero code change."

### Phase 6 (deferred) — UI surface

`HealthView` extension (the original 5.2 Path A) lands when there's
evidence of demand. The structural backend wiring from Phase 3 means the UI
work is now fully decoupled: `/api/status` fields are guaranteed to match
the catalog, and `RrdMetricStore` has the gauges archived. Path B
(dedicated `SubstrateView` with charts) remains a future decision.

## What this absorbs from the original 417

| Original item | Absorbed by | Notes |
|---|---|---|
| 4.1 — Histogram instrument type | Phase 0 + Phase 1 | `HistogramMetric<T>` carries bucket bounds via `setExplicitBucketBoundariesAdvice` (F2); per-call rebuild eliminated |
| 4.2 Step A — allowlist additions | Phase 2 | Schemas declare keys per-View (F3); `ALLOWED_TAG_KEYS` deleted |
| 4.2 Step B — per-namespace registration | Phase 0 + Phase 2 | Per-metric schema (stronger than per-namespace) via SDK's `setAttributeFilter` |
| 4.3 — reason enums | Phase 0 + Phase 1 + Phase 2 | Sealed types per metric; wire format is type metadata; ~17 commit reasons enumerated |
| 5.1 — trace-id exemplars | Phase 0 (default policy + global filter via F4) + Phase 5 (correlation projection + span backfill) | Per-metric `OFF` via export-time filter (F4 constraint); env-var flag removed |
| 5.2 — operator console UI | Phase 3 (backend wiring) + Phase 6 (UI) | Structural separation: backend wired in 3, UI deferred |
| 5.3 — ADR-0027 | Phase 4 | ADR documents the type signature, not a recipe |

Every original item is achieved as a side effect of the structural refactor.
Nothing is dropped. The new framing changes *how* and *in what order*, not
*whether*.

## Cost (refined estimates)

Estimates revised after F1–F10. Confidence higher than the previous draft:

- **Phase 0 (scaffolding)**: ~3 days. SDK wiring is now concrete (F2/F3/F4
  give specific primitives). Compile-fail harness re-uses
  `DeferredRuntimeCompileFailTest`. ArchUnit rules clone
  `EnvRegistryDirectReadTest`. The `TestMetricCatalog` is straightforward.
- **Phase 1 (proof case)**: ~2 days. 8 metrics, 14 test sites; mechanical once
  Phase 0 is in place. Wire-format regression test is a single property test.
- **Phase 2 (breadth migration)**: ~3 days. ~8 callsites; each is mechanical.
- **Phase 3 (producer/consumer wiring + meter() retirement)**: ~2 days.
  4 status gauges + ArchUnit + ~25 OperationalMetrics bridges (F6).
- **Phase 4 (ADR + doc)**: ~1 day.
- **Phase 5 (optional exemplar projection + span backfill)**: ~2.5 days.
- **Phase 6 (deferred UI)**: ~2 days when shipped.

Total: **~11 person-days for Phases 0–4. ~15.5 with 5+6.**

Risk reserves to budget on top:
- +1 day if `SdkMeterProviderUtil.setExemplarFilter` API breaks under a
  future OTel upgrade and we need to wrap it. Internal/experimental APIs
  can churn.
- +0.5 day if the wire-format regression test surfaces unexpected ordering
  differences requiring re-tuning.

## Open questions for the user

1. **Migration model**: single-shot (Phases 0+1 ship together as one PR, then
   Phase 2 in parallel for each adapter) or strangler-fig (catalog and
   existing `Telemetry` interface coexist for a window, callsites migrate
   opportunistically)? Strangler-fig costs more in the middle but avoids a
   ~5-day blocker; single-shot keeps the disease in production for less
   calendar time.

2. **Wire-format compatibility scope**: F9 settles the *test* shape (parse +
   strip-`t` + assert equivalence). The remaining decision is whether to
   hold *tag-value strings* byte-stable (e.g., keep the
   `path/style-with-hyphens` vs `snake_case` inconsistency in commit reasons)
   or use the migration as a chance to normalize. Holding stable constrains
   sealed-type wire-format mappings; normalizing breaks any external NDJSON
   consumer.

3. **Schema-strictness mode**: tag schemas sealed types only, or also
   support `Bounded<String>(maxCardinality=N)` for genuinely open value
   spaces (file extensions, HTTP error codes from external services)? F3
   gives us per-View `setAttributeFilter(Predicate<String>)` for runtime
   bounds and `setCardinalityLimit(int)` as a defensive guard, so both modes
   are technically achievable. The question is whether to encourage the
   open-cardinality mode or restrict catalogs to sealed-type schemas only.

4. **412–415 sequencing**: those tempdocs ship before the catalog (and
   migrate later) or wait for at least Phase 0? Waiting blocks them by
   ~3 days; not waiting means three more reproductions of the disease that
   have to be migrated.

5. **Internal-API dependency tolerance**: the global exemplar filter
   (F4) requires `SdkMeterProviderUtil` from the
   `io.opentelemetry.sdk.metrics.internal` package — explicitly marked
   unstable. Three options: (a) depend directly, accept churn risk on
   OTel upgrades, file a tracking issue when the public API stabilizes;
   (b) wrap behind an internal `JustSearchExemplarConfig` interface so
   future migration is a one-line change; (c) defer Phase 0's exemplar
   wiring entirely until OTel stabilizes (drops `Exemplars.TRACE_BASED`
   default → exemplars stay env-var-gated until OTel publishes the
   public API).

## What has been verified vs what still needs spike work

**Verified (high confidence)**:
- SDK primitives for bucket bounds, tag schemas, cardinality limits exist
  and are stable (F2, F3).
- ArchUnit can ban `Attributes.*` static calls (F8, with literal precedent).
- Startup ordering pattern is straightforward DI (F5).
- `meter(scope)` escape hatch is one site (F6).
- Test scaffolding is bounded (F7).
- Wire-format test shape is workable (F9).
- Cross-process disambiguation is clean (F10).

**Still needs a small spike before Phase 0 starts**:
- Confirm `SdkMeterProviderUtil.setExemplarFilter` works against OTel 1.60.1
  in this codebase (write a 20-line throwaway test). Cost: 30 min.
- Confirm `setAttributeFilter(LinkedHashSet)` preserves emission order
  through to NDJSON output (parse output, check key order). Cost: 30 min.
- Verify `setCardinalityLimit` plays well with our existing histogram views
  (check no warnings, no dropped metrics under stress). Cost: 1 hr.

**Total spike cost before Phase 0**: ~2 hours. Outcome determines whether
Phase 0 starts as-described or needs a small redesign.

## Out of scope

- Trace exporter (NDJSON spans) refactor. The same fire-and-pray pattern
  exists there (`NdjsonSpanExporter.ALLOWED_ATTRS`, observation
  2026-04-24); this tempdoc covers metrics only. A follow-up applies the
  same catalog idea to spans.
- Migration of LLM/inference metrics owned by tempdocs 412–415. Those
  tempdocs become "implement the inference catalog using 417's substrate"
  rather than independent observability shipments — but their rewrites
  are theirs.
- Removing `LocalTelemetry`'s heartbeat write. Pre-existing concern; not
  blocked by or blocking this refactor.
- The pre-existing cutover crash (`AlreadyClosedException` mentioned in
  406's deferrals). Separate bug.

## Cross-references

- Parent: tempdoc 406 (closed). The lifecycle pattern this design parallels
  is documented at
  `docs/future-features/service-identity-lifecycle-pattern.md`.
- Inbox entries this tempdoc supersedes (move to "addressed by 417" when
  Phase 1 ships): the two `LocalTelemetry` entries dated 2026-04-25 in
  `docs/observations.md` (lines 200–201). The four "410 worktree
  coordination" entries (lines 202–204) describe the same pattern issues
  but for the 410 branch — they continue to apply until 410 merges and
  Phase 2 migrates the affected files.
- Sibling tempdocs 412 (InferenceLifecycleManager), 413 (EmbeddingService),
  414 (NativeSessionHandle), 415 (AgentSession): they will adopt the
  catalog instead of reproducing the adapter pattern. Their tempdocs need
  a sequencing decision per open question 4.
- Original 417 framing (six independent items, ~9-day punch list)
  preserved in git history at the prior revision of this file.
  Cross-references in `docs/observations.md` that point at
  `tempdoc 417 §4.2` etc. are now stale — they still resolve to this
  file but the section anchors no longer exist. Plan to refresh them
  when Phase 1 ships.
- F1–F10 above are pre-implementation findings, not phases or scope. They
  are the empirical grounding for the design choices and should be
  treated as documentation of *why* the design takes the shape it does,
  not as work items.
