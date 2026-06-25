---
title: "287: Test Execution Performance"
type: tempdoc
status: done
created: 2026-03-13
updated: 2026-03-13
---

## Before / After

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| `./gradlew.bat test` wall clock | 3m 52s | **1m 24s** | **64%** |
| `maxParallelUsages` | 1 | 2 | 2 concurrent test JVMs |
| Test JVM heap | ~512MB (default) | 384MB (explicit) | Bounded for safe parallelism |
| ArchUnit classpath resolution | full transitive | disabled | ~2.6s saved on app-launcher |
| Object headers | 96/128-bit | 64-bit (JEP 519) | ~15% less GC |

## Problem

Test execution is the wall-clock bottleneck during `./gradlew.bat build`.
Tests were serialized via `TestGateService` (`maxParallelUsages=1`) and totaled
**~210s** of execution time across 28 modules. Wall clock for `./gradlew.bat test`
was **3m 52s** (includes compilation).

## Completed

### 1. Gradle-level test parallelism (`maxParallelUsages=2`)

**File:** `build-logic/.../JvmBaseConventionsPlugin.kt`

- Added `testParallelism` Gradle property (default: 2 locally, 1 in CI)
- `TestGateService` registration uses the property: `maxParallelUsages.set(testParallelism)`
- Added `maxHeapSize = "384m"` to all Test tasks to bound memory (2 × 384MB + 1GB daemon = ~1.8GB)
- system-tests already overrides to 1GB/2GB for heavier tiers — unaffected
- Override: `-PtestParallelism=N`

**Result:** Wall clock dropped from 3m 52s to 1m 28s (62% reduction). The two
largest modules (indexer-worker 35s, ui 19s) now overlap with other modules instead
of blocking the pipeline.

### 2. ArchUnit `resolveMissingDependenciesFromClassPath=false`

ArchUnit by default resolves all transitive class dependencies from the classpath,
which is the dominant cost of `@AnalyzeClasses` scans. Disabling this is safe because
all 13 ArchUnit tests in the project check package membership / dependency direction,
not external class metadata. Added `archunit.properties` to all 9 modules with
ArchUnit tests.

**Result:** app-launcher test time dropped from 10.2s to 7.6s (25% faster). The
broad-scope `@AnalyzeClasses(packages = "io.justsearch")` scans benefit most.

### 3. Compact Object Headers (`-XX:+UseCompactObjectHeaders`)

JDK 25 product feature (JEP 519). Reduces every object header from 96/128 bits to
64 bits. Oracle benchmarks: 22% less heap, ~15% less GC. Added to test JVM `jvmArgs`
and `org.gradle.jvmargs` for the daemon.

**Result:** Combined with item 2, wall clock dropped from 1m 28s to **1m 24s**.

### Post-optimization per-module test times

| Module | Before | After |
|--------|--------|-------|
| indexer-worker | 46.0s | 33.2s |
| ui | 25.8s | 16.3s |
| adapters-lucene | 16.1s | 13.9s |
| app-services | 27.9s | 12.2s |
| app-launcher | 17.2s | 7.6s |
| ai-worker | 9.2s | 7.1s |
| app-inference | 8.3s | 7.7s |

Per-module improvements come from parallelism overlap, ArchUnit resolution skip,
and compact object headers reducing GC pressure.

## Investigated and dismissed

### ArchUnit shared class cache (~5s potential)

`BoundaryRulesTest` and `IndexWriterOwnershipTest` both scan `packages = "io.justsearch"`,
but with different `importOptions`: `BoundaryRulesTest` includes test classes (needed
because the only classes matching its `io.justsearch.app.launcher..` rule are test
classes — production code is in `io.justsearch.applauncher`), while `IndexWriterOwnershipTest`
uses `DoNotIncludeTests` (needed because `IndexWriterOwnershipTest` itself imports
`org.apache.lucene.index.IndexWriter`, which would violate its own Lucene ownership rule).

ArchUnit caches by annotation parameters, so different `importOptions` mean separate scans.
Unifying requires either a programmatic shared `ClassFileImporter` or merging the classes,
both of which add complexity for ~5s savings that's already partially absorbed by
cross-module parallelism.

### Shared Lucene fixtures (indexer-worker gRPC tests)

Investigated all 15 `GrpcIngestService*`/`GrpcSearchService*` test classes. Every class
mutates its fixtures: tests write different documents, delete entries, drop SQL tables,
re-create queues mid-test, and make IDF-sensitive assertions requiring precise corpus
composition. No class can safely share a `@BeforeAll` fixture without breaking test isolation.

### LambdaMart model caching (app-services)

`LambdaMartRerankerTest` calls `buildStubModel(10)` per test (6 of 9 tests). The model
could theoretically be cached, but some tests call `model.close()` on the instance,
invalidating a shared reference. Fixing requires caching the model *string* and
deserializing per test — possible but adds complexity for ~1-2s savings on a class that
already benefits from cross-module parallelism.

### Tag slow tests

Not a real optimization — just skips tests, reducing coverage. Unit tests >3s are still
unit tests that should run. The `evidence`/`experiment` tags work because those tests
are fundamentally optional.

## Potential optimizations (from internet research, Mar 2026)

### High confidence

- [x] **ArchUnit `resolveMissingDependenciesFromClassPath=false`:** Completed — see item 2 above.
- [x] **Compact Object Headers (`-XX:+UseCompactObjectHeaders`):** Completed — see item 3 above.

### Medium confidence

- [ ] **JUnit Jupiter parallel class execution:** Run test classes within a single module
  concurrently (`junit.jupiter.execution.parallel.mode.classes.default=concurrent`).
  Orthogonal to Gradle-level `maxParallelUsages`. 30-60% per-module test time reduction
  for modules with many independent test classes. Requires test isolation audit.
  `@ResourceLock(target=CHILDREN)` (JUnit 5.14.2+) enables fine-grained synchronization.
  Source: [JUnit 5.14 parallel docs](https://docs.junit.org/5.14.2/writing-tests/parallel-execution)

### Low confidence / investigate further

- [ ] **JEP 515 AOT Method Profiling:** Persist JIT profiles across test JVM launches.
  15-55% startup reduction. Requires per-module `.aot` cache files with exact classpath
  match — invalidated by any dependency or code change. Most viable in CI with stable
  lockfiles. High setup complexity.
  Source: [JEP 515](https://openjdk.org/jeps/515)

- [ ] **Spotless configuration overhead:** Feb 2026 Gradle Newsletter flagged that broad
  Spotless target patterns (`src/**/*.java`) across multi-module builds dramatically slow
  configuration time. The project uses this pattern in all 28 modules. Profile to confirm.
  Source: [Gradle Newsletter Feb 2026](https://newsletter.gradle.org/2026/02)

### Already captured (no action needed)

- Gradle 9.2 work graph rebuild optimization (on 9.4.0)
- Gradle 9.2 memory reduction 7-12% (on 9.4.0)
- Config cache parallel load/store (on 9.4.0)
- JDK 25 interpreter startup improvement (12% per fork, automatic)
- G1GC remembered set merge + collection set pruning (automatic)
- String hash constant folding (automatic)
- JVM shutdown 1ms sleep removal (automatic)

### Not applicable

- Develocity Predictive Test Selection — requires paid Develocity server
- Develocity Setup Cache / Artifact Cache — requires self-hosted Develocity server 2025.4+
- Gradle Isolated Projects — pre-alpha, breaks third-party plugins
- JUnit 6 ForkJoinPool replacement — milestone release only
- Error Prone 2.43-2.48 — no performance changes in this range

## Profiled test execution (2026-03-13, no build cache)

### Per-module test time (top 10, pre-optimization baseline)

| Module | Tests | Duration | Slowest class |
|--------|-------|----------|--------------|
| indexer-worker | 765 | 46.0s | `GrpcIngestServiceVduHardeningTest` (6.3s) |
| app-services | 271 | 27.9s | `LambdaMartRerankerTest` (5.4s) |
| ui | 406 | 25.8s | `TimeSeriesControllerTest` (2.3s) |
| app-launcher | 51 | 17.2s | `BoundaryRulesTest` (5.6s) |
| adapters-lucene | 384 | 16.1s | `LuceneIndexRuntimeTest` (2.5s) |
| ai-worker | — | 9.2s | — |
| app-inference | — | 8.3s | — |
| ai-bridge | — | 6.5s | — |
| app-agent | — | 6.1s | — |
| app-ai | — | 6.0s | — |
| (18 other modules) | — | 37.5s | all <5s each |
| **Total** | **~2000+** | **~210s** | |

### ArchUnit classpath scanning

| Test class | Module | Duration | Scan scope |
|-----------|--------|----------|------------|
| `BoundaryRulesTest` | app-launcher | 5.6s | `io.justsearch` (entire codebase) |
| `IndexWriterOwnershipTest` | app-launcher | 4.6s | `io.justsearch` (entire codebase) |
| `IndexerWorkerGuardrailsTest` | indexer-worker | 2.4s | `io.justsearch.indexerworker` |
| `AppServicesWorkerGuardrailsTest` | app-services | 1.8s | scoped |
| `UiApiGuardrailsTest` | ui | 1.7s | scoped |
| `AdaptersLuceneGuardrailsTest` | adapters-lucene | 1.3s | scoped |
| `LegacyEndpointGuardTest` | ui | 1.7s | likely scoped |
| **Total** | | **~19s** | |

## Test infrastructure

### Serialization
- `TestGateService` (`build-logic/.../TestGateService.kt`): `maxParallelUsages` controlled by `testParallelism` property
- All Test tasks call `usesService(testGate)` via `JvmBaseConventionsPlugin.kt`
- `maxParallelForks=1` explicit in: app-services (test), ui (integrationTest)

### Heap and memory
- Gradle daemon: 1GB (`gradle.properties`)
- Test JVMs: 384MB (`maxHeapSize` in JvmBaseConventionsPlugin)
- system-tests: systemTest=1GB, soakTest=2GB (module-level overrides)
- System RAM: 32GB total

## Key files

| Purpose | File |
|---------|------|
| Test gate + heap cap + parallelism + compact headers | `build-logic/.../JvmBaseConventionsPlugin.kt` |
| Daemon JVM args (compact headers) | `gradle.properties` |
| ArchUnit config (app-launcher, has freeze config) | `modules/app-launcher/src/test/resources/archunit.properties` |
| ArchUnit config (8 other modules) | `modules/*/src/test/resources/archunit.properties` |
| Test gate service | `build-logic/.../TestGateService.kt` |
