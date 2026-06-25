---
title: "284: Local Build Performance"
type: tempdoc
status: done
created: 2026-03-13
updated: 2026-03-13
---

## Before / After

| Command | Before | After | Improvement |
|---------|--------|-------|-------------|
| `build -x test` (clean, warm config cache) | ~64s | ~59s | 8% wall clock |
| `build -x test` (incremental, warm build cache) | — | ~10s | Build cache handles it |
| `indexer-worker:test` | 63 min | ~49s | 99% (tag exclusion) |
| Test compilation (clean, task-time sum) | ~287s | ~121s | 58% (Error Prone skip) |
| distZip (clean, task-time sum) | ~68s | 0s | 100% (skipped locally) |
| JaCoCo reports (task-time sum) | ~48s | 0s | 100% (skipped locally) |

**Note on wall clock vs task time:** The `build -x test` wall clock only dropped 8%
because compilation (irreducible) dominates and runs in parallel. The ~330s of
task-time savings (Error Prone, distZip, JaCoCo, SpotBugs, PMD) compress into
~5s of wall-clock improvement because they were already parallelized with
compilation. The real win is smoother incremental builds and fewer wasted CPU cycles.

## Problem

`./gradlew.bat build -x test` took ~64s on a clean build, and
`./gradlew.bat :modules:indexer-worker:test` took 63+ minutes due to
unfiltered evidence/experiment tests. Local developer iteration is
unnecessarily slow.

## Completed

### 1. Exclude `evidence` and `experiment` test tags from default runs
- **File:** `build-logic/.../JvmBaseConventionsPlugin.kt`
- `useJUnitPlatform { excludeTags("evidence", "experiment") }`
- indexer-worker tests: 63 min → ~49s

### 2. Sync web assets instead of accumulating
- **File:** `modules/ui/build.gradle.kts` line 176
- Changed `Copy::class` to `Sync::class` on `copyWebResources`
- Removes stale Vite hashed files (was 1,567 files / 85MB, now ~26)
- `ui:processResources`: 37s → <1s

### 3. Skip SpotBugs locally
- **File:** `build-logic/.../SpotBugsConventionsPlugin.kt`
- Added `skipSpotBugs` property: defaults to true locally, false in CI (`CI=true`)
- Override: `-PskipSpotBugs=false`
- Saves ~47s task time across all modules

### 4. Disable distTar
- **File:** `build-logic/.../JvmBaseConventionsPlugin.kt`
- `pluginManager.withPlugin("application") { tasks.named("distTar") { enabled = false } }`
- Only distZip is used (Windows project); saves ~10s across 3 modules

### 5. Skip PMD locally
- **File:** `build-logic/.../JvmBaseConventionsPlugin.kt`
- Added `skipPmd` property: defaults to true locally, false in CI (`CI=true`)
- Override: `-PskipPmd=false`
- Combined with existing `pmd.includeTests` gate into single `onlyIf`

### 6. Fix `enforceDependencyVersions` filesystem walk
- **File:** `build-logic/.../DependencyHygienePlugin.kt`
- Replaced project-wide `asFileTree` glob with explicit file inputs
- Previous glob walked `tmp/` (1M+ files) and `.claude/` (92K+ files)
- Config cache cold: 50s → same (config phase dominates); warm cache: **<1s**
- Up-to-date checking works correctly with explicit inputs

### ~~7. Verify configuration cache is actually hitting~~ (confirmed working)

### 8. Skip JaCoCo reports locally
- **File:** `build-logic/.../CoverageConventionsPlugin.kt`
- Added `skipJacoco` property: defaults to true locally, false in CI (`CI=true`)
- Override: `-PskipJacoco=false`
- `finalizedBy` wiring stays — `onlyIf` skips the task body without removing the relationship
- Saves ~48s task time across 25 modules during test runs
- Coverage verification in CI (`-Pcoverage.enforce=true`) still works

### 9. Disable Error Prone on test compilation locally
- **File:** `build-logic/.../ErrorProneConventionsPlugin.kt`
- Added `skipErrorProneTests` property: defaults to true locally, false in CI (`CI=true`)
- Override: `-PskipErrorProneTests=false`
- Uses `options.errorprone.isEnabled.set(false)` on non-`compileJava` tasks
- `--should-stop=ifError=FLOW` kept unconditionally (benign without Error Prone)
- Test compile task-time total: **~287s → ~121s** (58% reduction)

### 10. Skip distZip locally
- **File:** `build-logic/.../JvmBaseConventionsPlugin.kt`
- Added `skipDist` property: defaults to true locally, false in CI (`CI=true`)
- Override: `-PskipDist=false`
- Saves ~68s task time across 4 modules on clean builds (UP-TO-DATE on incremental)
- `assembleDesktopDist` needs `-PskipDist=false` when explicitly invoked

## Deferred

### Increase test parallelism
- `TestGateService` limits `maxParallelUsages=1` to prevent OOM
- System has 32GB RAM; test JVMs use ~512MB each by default
- Increasing to `maxParallelUsages=2` is theoretically safe with explicit heap caps
- **Risk:** Port conflicts in integration tests, race conditions in tests sharing state
- **Estimated savings:** 30-50% of test execution wall-clock on clean builds (~50-75s)
- **Recommendation if revisited:** Set `maxHeapSize = "384m"`, add `testParallelism` property

### PMD incremental analysis
- Gradle docs warn of "possible side effects with build cache or incremental builds"
- PMD is skipped locally anyway, so only CI would benefit

---

## Deep Investigation Results (2026-03-13)

### Clean build profile (`build -x test`, warm config cache, no build cache)

Wall clock: **59s** | Total task execution: **6m 14s** (parallel)

**Top 10 tasks by duration:**

| Task | Duration |
|------|----------|
| `indexer-worker:compileJava` | 17.5s |
| `ipc-common:compileJava` | 17.1s |
| `ai-bridge:compileJava` | 14.2s |
| `adapters-lucene:compileJava` | 14.2s |
| `app-agent:compileJava` | 12.4s |
| `ui:compileJava` | 10.7s |
| `adapters-lucene:compileTestJava` | 10.0s |
| `ipc-common:jar` | 9.7s |
| `configuration:compileJava` | 9.0s |
| `system-tests:compileJava` | 8.9s |

Compilation is the irreducible floor. All skippable tasks (SpotBugs, PMD, JaCoCo,
distZip) are confirmed SKIPPED (195 tasks).

### Error Prone overhead on test compilation

| Module | With EP | Without EP | Reduction |
|--------|---------|------------|-----------|
| adapters-lucene | 24.4s | 12.5s | 49% |
| indexer-worker | 19.2s | 6.1s | 68% |
| configuration | 16.9s | 4.8s | 72% |
| app-services | 11.2s | 3.5s | 69% |
| test-support | 11.1s | 3.7s | 67% |
| **Total (all modules)** | **~287s** | **~121s** | **58%** |

### Distribution packaging (clean build)

| Module | Duration |
|--------|----------|
| app-launcher:distZip | 28.6s |
| indexer-worker:distZip | 26.2s |
| ui:distZip | 9.1s |
| ai-worker:distZip | 4.5s |
| **Total** | **68.4s** |

On warm builds: all UP-TO-DATE (<0.01s). Skipped locally.

---

## Research Findings (2026-03-13)

### Investigated and dismissed

| Idea | Verdict | Reason |
|------|---------|--------|
| **Bazel/Buck2 migration** | Not viable | Gradle outperforms Bazel on JVM: 2-4x faster incremental builds. Migration cost massive for 33 subprojects. |
| **GraalVM native-image** | Not applicable | Speeds app startup, not javac. Gradle daemon already eliminates JVM cold start. |
| **JPMS / jlink** | Low ROI | Runtime benefit only, not compilation. Massive effort across 33 projects. |
| **Configure-on-demand** | Superseded | Configuration cache provides this benefit and more. |
| **Gradle Isolated Projects** | Not ready | Pre-alpha. Promising (34-42% config time reduction) but unstable. Revisit with Gradle 9.x. |
| **JDK 25 compilation speed** | Already using | javac speed not meaningfully different from JDK 21. |
| **Remote build cache** | Overkill | Solo/small team. Local build cache sufficient. |

---

## Local skip flags summary

All default to skip locally, auto-enable in CI (`CI=true`). Verified working together.

| Flag | What it skips | Override |
|------|--------------|----------|
| `skipSpotBugs` | SpotBugs static analysis | `-PskipSpotBugs=false` |
| `skipPmd` | PMD static analysis | `-PskipPmd=false` |
| `skipJacoco` | JaCoCo coverage reports | `-PskipJacoco=false` |
| `skipErrorProneTests` | Error Prone on test sources | `-PskipErrorProneTests=false` |
| `skipDist` | distZip archives | `-PskipDist=false` |

## Gradle performance features enabled

- [x] Daemon (`org.gradle.daemon=true`, 30-min idle)
- [x] Parallel builds (`org.gradle.parallel=true`)
- [x] Configuration cache (`org.gradle.configuration-cache=true`)
- [x] Build cache (`org.gradle.caching=true`)
- [x] Reduced heap (1GB, was 2GB — no perf difference per tempdoc 275)
- [x] G1GC
- [x] File system watching (Gradle default)
- [x] Compilation avoidance (Gradle default for `java-library` plugin)
