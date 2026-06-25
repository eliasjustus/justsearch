---
title: "275: Gradle Cold Start Optimization"
type: tempdoc
status: done
created: 2026-03-11
updated: 2026-03-12
---

## Problem

Gradle cold start takes 60-70 seconds for a no-op `build -x test`, causing
workflow friction for agents running eval scripts, dev-runner, and
`--manage-backend` flows. The eval agent (tempdoc 274) wasted ~30 minutes of
context repeatedly killing and restarting builds that hadn't finished.

## Root Cause

Two compounding factors:

1. **Daemon disabled** — `gradle.properties` sets `org.gradle.daemon=false`.
   Every invocation spawns a fresh 2GB JVM, paying full classloading and
   configuration cost across 24 subprojects.

2. **Dev scripts also disable configuration cache** — `dev-runner.cjs` passes
   `--no-configuration-cache` explicitly (line 549), defeating the config
   cache even when the daemon is re-enabled.

### Why Was Daemon Disabled?

Commit `a1df9705` (Nov 3, 2025, "Phase 6") added `org.gradle.daemon=false`
alongside network-binding hardening (`preferIPv4Stack`, `wildcardAddress`).
**No rationale was documented.** Likely a stability workaround for historical
Gradle daemon issues on Windows (file locking, stale daemons), but this is
inference — the commit message is just "Phase 6".

## Measured Timings (This Machine)

| Scenario | Time | Notes |
|----------|------|-------|
| `help` — no daemon | 12.4s | Configuration phase only |
| `help` — warm daemon | 0.8s | **15x faster** |
| `build -x test` — no daemon | 68s | No-op, everything up-to-date |
| `build -x test` — cold daemon (2GB) | 60s | First build spawns daemon |
| `build -x test` — warm daemon (2GB) | 33s | **2x faster** |
| `build -x test` — cold daemon (1GB) | 46s | Smaller heap, same result |
| `build -x test` — warm daemon (1GB) | 32s | Equivalent to 2GB |

**Key takeaway:** Warm daemon cuts no-op build time in half (68s → 32s) and
config-only tasks by 15x (12.4s → 0.8s). Reducing heap from 2GB to 1GB has
no measurable impact on build times.

## Memory Impact Analysis

### Daemon Memory Footprint

| Heap Setting | Working Set (MB) | Private (MB) |
|-------------|------------------|--------------|
| `-Xmx2g` | 1,455 | 1,504 |
| `-Xmx1g` | 1,145 | 1,195 |

**Savings from 1GB heap: ~300 MB** with identical build performance.

### Total System Memory Budget (Concurrent Runtime)

| Process | Typical RAM | Notes |
|---------|------------|-------|
| Head (UI) | 128-256 MB | Lean orchestrator |
| Worker (Body) | 512 MB | Default; configurable via `JUSTSEARCH_WORKER_HEAP` |
| Inference (Brain) | VRAM-only | llama-server uses GPU, minimal system RAM |
| Gradle daemon (1GB) | ~1.1 GB | Only when actively developing |
| **Total** | ~2.0 GB | Well within 16-32GB dev machines |

The daemon is only alive during development. It doesn't compete with the
production runtime. On a 16GB machine with all three JustSearch processes
running, a 1GB daemon leaves ~12 GB for OS and other tools.

### ONNX Runtimes (GPU, Not System RAM)

- Reranker: 512 MB GPU arena
- SPLADE: 256 MB GPU arena

These use VRAM, not system RAM, so they don't factor into daemon memory pressure.

## Script Invocation Audit

All dev scripts explicitly pass `--no-daemon`, making the `gradle.properties`
setting redundant for these paths:

| Script | Command | Flags |
|--------|---------|-------|
| `dev-runner.cjs:549` | `gradlew.bat :modules:ui:runHeadless` | `--no-daemon --no-configuration-cache -PskipWebBuild=true` |
| `run-headless-api.ps1:73` | `gradlew.bat resolveAndLockAll --write-locks` | `--no-daemon` |
| `run-headless-api.ps1:86` | `gradlew.bat :modules:ui:classes` | `--no-daemon` |
| `run-headless-api.ps1:116` | `gradlew.bat :modules:ui:runHeadless` | `--no-daemon` |

**Note:** `--no-daemon` on `runHeadless` is correct — the Gradle process *is*
the backend, so daemonizing it would detach stdio. But `--no-configuration-cache`
on the same command is wasteful; config cache works fine with `runHeadless`.

Test and CI scripts (`run-matrix.sh`, `force-rerank-skip.sh`) do **not** pass
`--no-daemon`, so they'd benefit from daemon re-enablement.

## Investigation Checklist

- [x] Why was daemon disabled? → Commit `a1df9705`, no documented rationale
- [x] Memory impact of long-lived daemon → ~1.1 GB at 1GB heap, fits in budget
- [x] Config cache + daemon interaction → Config phase drops from 12.4s to 0.8s
- [x] Whether scripts could reuse daemon → Scripts pass `--no-daemon` explicitly;
      `runHeadless` legitimately needs `--no-daemon`, but other scripts don't
- [x] Impact of reducing daemon heap → 1GB is sufficient, saves ~300 MB

### Not Yet Investigated

- [ ] Gradle 9.4.0 daemon stability on Windows — requires sustained testing
      (run daemon for a full day of dev work, watch for stale daemon / file lock issues)

## Recommendations

### Tier 1: Re-enable daemon (DONE)

Changes to `gradle.properties`:
- `org.gradle.daemon=true`
- `org.gradle.daemon.idletimeout=600000` (10 min idle timeout)
- `-Xmx2g` → `-Xmx1g` in `org.gradle.jvmargs`

### Tier 2: Remove `--no-configuration-cache` from dev-runner (DONE)

Removed `--no-configuration-cache` from `dev-runner.cjs:549`. Configuration
cache works with `runHeadless`.

### Tier 3: Remove `--no-daemon` from non-`runHeadless` scripts (DONE)

Removed `--no-daemon` from `run-headless-api.ps1` lines 73 and 86
(`resolveAndLockAll` and `classes` invocations). Kept `--no-daemon` on line
116 (`runHeadless`) since that's a long-running server process.

### Not Recommended

- **Reducing subproject count**: 24 modules is reasonable. Config cache
  already eliminates repeated configuration cost on cache hits.
- **Switching to `--no-build-cache`**: Build cache is beneficial and low-cost.

## Post-Implementation Measurements

### Phase 1: Daemon Re-enablement

| Scenario | Before | After | Improvement |
|----------|--------|-------|-------------|
| `help` — cold daemon | 12.4s | 3.6s | **3.4x** |
| `help` — warm daemon | 12.4s | 0.8s | **15x** |
| `build -x test` — no-op (first warm) | 68s | 40s | **1.7x** |
| `build -x test` — no-op (steady state) | 68s | 31s | **2.2x** |

### Phase 2: Config Cache + Incremental Task Fixes

| Scenario | Phase 1 | Phase 2 | Improvement |
|----------|---------|---------|-------------|
| `help` — warm | 0.8s | 2.0s | (same order) |
| `assemble` — warm | 0.8s | 2.2s | (same order) |
| `build -x test` — warm | **FAILS** | **3.4s** | Fixed + **9x vs Phase 1** |
| `check -x test` — warm | 31s | **6.5s** | **4.8x** |

**End-to-end improvement: 68s → 3.4s (20x faster).**

Daemon memory (idle, 1GB heap): **1,255 MB working set / 1,308 MB private** —
~200 MB less than the old 2GB config.

## Deep-Dive: Remaining Gradle Issues

After implementing the daemon fix, a full `--profile` run and targeted testing
revealed **five distinct issues** responsible for the remaining build slowness.

### Issue 1: `build -x test` Is Broken (BUG) — RESOLVED

**Severity: HIGH** — `./gradlew.bat build -x test` fails every time.

`:reports:testAggregateTestReport` is wired into `:reports:check` → `:check` →
`:build`, but `-x test` only excludes `test` tasks, not the report aggregation.
The report task tries to read binary test results that don't exist (tests were
skipped), and throws `Could not write test report`.

**Root cause:** `reports/build.gradle.kts:89-92` — `check` depends on both
`testAggregateTestReport` and `testCodeCoverageReport` unconditionally.

**Workaround:** `./gradlew.bat build -x test -x :reports:testAggregateTestReport -x :reports:testCodeCoverageReport`

**Fix:** Make report tasks `shouldRunAfter` test tasks (not `dependsOn` via check),
or guard the check dependency with `onlyIf { testResultsExist }`.

### Issue 2: Configuration Cache Discarded Every Build (BUG) — RESOLVED

**Severity: HIGH** — Config cache is never reused for `build` or `check` tasks.

`:spotlessKotlinGradle` is marked `notCompatibleWithConfigurationCache` due to
a [Spotless Windows file-locking bug](https://github.com/diffplug/spotless/issues/2025)
with `lineEndingsPolicy` serialization (`build.gradle.kts:580-585`). This causes
Gradle to **discard the entire config cache entry** after every build, forcing
full re-configuration on the next run.

**Impact:** The `assemble` task (which doesn't include Spotless check) reuses
config cache and completes in **0.8s**. The `build` task never reuses it.

**Current Spotless version:** 8.3.0. The upstream bug is unresolved as of
Spotless 8.3.0 on Windows. Tested on Spotless 8.1.0 + Gradle 9.1.0 — same failure.

**Potential fix:** Move `:spotlessKotlinGradle` out of the `check` dependency
chain (run it separately in CI), or exclude `.kts` files from Spotless root-level
format and handle them per-module where config cache works.

### Issue 3: `:spotlessKotlinGradle` Takes 67s (PERF) — RESOLVED

**Severity: HIGH** — Single slowest task, even when UP-TO-DATE.

The root-level `spotlessKotlinGradle` task (formatting `*.gradle.kts` files) takes
**1m7s** in a profiled run, even marked UP-TO-DATE. This appears to be JVM/Spotless
startup overhead for the root-level KotlinGradle formatter.

Combined with Issue 2, this creates a 67s tax on every `check`/`build` invocation.

### Issue 4: `:checkNoDirectJustsearchSysProp` Takes 16s (PERF) — RESOLVED

**Severity: MEDIUM** — Walks entire source tree every build.

This custom verification task (`build.gradle.kts:249-281`) does
`sourceRoot.walkTopDown()` scanning every `.java` file for disallowed
`System.getProperty("justsearch.")` calls. It runs in **16s** on every build
with no up-to-date checking (it always re-executes).

**Fix:** Add proper `@InputFiles` / `@OutputFile` annotations so Gradle can
skip the task when source files haven't changed. Or convert it to use
`@SkipWhenEmpty` with a `FileTree` input.

### Issue 5: `:enforceDependencyVersions` Takes 8s (PERF) — RESOLVED

**Severity: LOW** — Runs every build, no up-to-date checking.

This custom task resolves dependency configurations to verify version convergence.
It always re-executes because it has no declared outputs.

**Fix:** Add an output marker file so Gradle can track up-to-date state.

### Task-Level Budget (Profiled, `check -x test`)

| Task | Time | Status | Issue |
|------|------|--------|-------|
| `:spotlessKotlinGradle` | 67.4s | UP-TO-DATE | #3 — kills config cache |
| `:checkNoDirectJustsearchSysProp` | 16.1s | always runs | #4 — no caching |
| `:modules:ui:spotbugsMain` | 9.3s | executed | Normal |
| `:enforceDependencyVersions` | 8.4s | always runs | #5 — no caching |
| `:modules:ui:integrationTest` | 7.7s | executed | Runs even with `-x test` |
| `:modules:ui:pmdMain` | 4.5s | executed | Normal |
| `:ssotGoldensValidate` | 3.9s | executed | Normal |
| `:modules:app-launcher:simulate*` | 1.3s | executed | Normal |
| Everything else | < 1s | UP-TO-DATE | — |

### Theoretical Best-Case Build Time

| Scenario | Current | Achievable | How |
|----------|---------|------------|-----|
| `assemble` (warm) | 0.8s | 0.8s | Already optimal |
| `check -x test` (warm) | 31s | ~5s | Fix issues #2-5 |
| `build -x test` | FAILS | ~5s | Fix issue #1, then #2-5 |
| `build` (full, warm) | ~2m+ | ~30s | Tests dominate |

### Known Gradle 9.4 Windows Risks

Research found these daemon-related issues on Windows (not yet observed here):
- [Daemon file locking](https://github.com/gradle/gradle/issues/937) — daemon
  holds locks on intermediate files, preventing overwrite on subsequent builds
- [Stale daemon at IDLE](https://discuss.gradle.org/t/gradle-daemon-stuck-at-idle-and-never-ends-on-windows/51385)
  — daemon gets stuck, never terminates, wastes memory
- [Lock manager infinite loop](https://discuss.gradle.org/t/gradle-daemon-keeps-locking-intermediate-files-preventing-builds/38004)
  — `DefaultFileLockManager` loops on corrupted lock files

**Mitigation:** The 10-minute idle timeout limits exposure. If issues surface,
`./gradlew.bat --stop` clears them, and reverting `org.gradle.daemon=false`
is a single-line change.

## Research: Resolution Strategies

### Issue 1 Fix: `testAggregateTestReport` Crash on `-x test`

**Recommended approach:** Remove report tasks from `check` dependency, invoke
them explicitly in CI only.

In `reports/build.gradle.kts`, change:
```kotlin
// BEFORE (lines 89-92)
tasks.named("check") {
  dependsOn(tasks.named<TestReport>("testAggregateTestReport"))
  dependsOn(tasks.named<JacocoReport>("testCodeCoverageReport"))
}

// AFTER — only run reports when explicitly requested
// CI script invokes: ./gradlew.bat test testAggregateTestReport testCodeCoverageReport
```

Alternatively, guard with `onlyIf`:
```kotlin
tasks.named<TestReport>("testAggregateTestReport") {
  onlyIf {
    // Only run if at least one module has test results
    testResults.any { it.asFileTree.files.isNotEmpty() }
  }
}
```

**Research source:** [Gradle forums discussion](https://discuss.gradle.org/t/test-report-aggregation-aggregation-only-if-report-s-exist/45804)
confirms the recommended pattern is two-step: run tests first, then aggregate
with `-x test` to suppress re-running. Removing report tasks from `check` is
the cleaner long-term fix since `check` should succeed without test results.

### Issue 2+3 Fix: Spotless Config Cache + 67s Task

**Upstream status:**
- [Spotless #2025](https://github.com/diffplug/spotless/issues/2025) (lineEndingsPolicy
  cache stale) — **CLOSED** (Feb 2024), fixed via multi-JVM config cache support.
- [Spotless #2404](https://github.com/diffplug/spotless/issues/2404) (Windows line
  ending git-diff in 7.0+) — **CLOSED**, fixed via PR #2514 in a later release.
- Latest Spotless version: **8.3.0** (current). Both fixes should be included.

**The `notCompatibleWithConfigurationCache` annotation in `build.gradle.kts:580-585`
was added as a workaround for these bugs.** Since both upstream issues are now closed,
the workaround may be removable — but it was last tested on Spotless 8.1.0 + Gradle
9.1.0 and still failed, so it needs re-testing on the current Spotless 8.3.0 + Gradle
9.4.0 combination.

**Recommended approach (two options):**

1. **Try removing the workaround** — delete the `notCompatibleWithConfigurationCache`
   block and test whether `spotlessKotlinGradle` now works with config cache. If it
   does, this eliminates both Issue 2 (cache discard) and Issue 3 (67s task overhead,
   because config cache means the task graph is restored from cache, not re-evaluated).

2. **If still broken: separate root KotlinGradle formatting from `check`** — move
   `.kts` formatting to a standalone `spotlessKts` task that CI runs independently,
   removing it from the `check` → `build` chain. Per-module Java formatting already
   works with config cache (no issues observed).

**Fundamental consideration:** Spotless has persistent Windows issues. If problems
continue, consider switching root-level `.kts` formatting to the standalone
[ktfmt-gradle](https://github.com/nickallendev/ktfmt-gradle) plugin, which has
fewer moving parts. Per-module Java Spotless (google-java-format) works fine and
should stay.

### Issue 4 Fix: `checkNoDirectJustsearchSysProp` (16s)

**Root cause:** The task uses `doLast` with `sourceRoot.walkTopDown()` — a
runtime file walk with no declared inputs/outputs. Gradle cannot determine
up-to-date status, so it always re-executes.

**Recommended fix:** Convert to a proper typed task with incremental inputs.

```kotlin
// Replace the ad-hoc task with a typed abstract task:
abstract class CheckNoDirectSysPropTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:SkipWhenEmpty
    abstract val sourceFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun check() {
        // Same violation-scanning logic, but now Gradle can skip
        // this task when sourceFiles haven't changed.
        reportFile.get().asFile.writeText("OK")
    }
}

tasks.register<CheckNoDirectSysPropTask>("checkNoDirectJustsearchSysProp") {
    sourceFiles.from(fileTree("modules") { include("**/src/main/**/*.java") })
    reportFile.set(layout.buildDirectory.file("reports/sysprop-check.txt"))
}
```

With `@InputFiles` + `@OutputFile`, Gradle hashes the source files once and
skips the task on subsequent builds if nothing changed. Expected improvement:
16s → 0s on no-change builds (UP-TO-DATE).

**Gradle docs reference:** [Incremental Build](https://docs.gradle.org/current/userguide/incremental_build.html)

### Issue 5 Fix: `enforceDependencyVersions` (8s)

**Same pattern as Issue 4** — the task resolves dependency configurations but
has no declared outputs, so Gradle always re-runs it.

**Fix:** Add a marker output file:
```kotlin
val reportFile = layout.buildDirectory.file("reports/dep-versions-ok.txt")
outputs.file(reportFile)
doLast {
    // ... existing enforcement logic ...
    reportFile.get().asFile.writeText("OK")
}
```

This gives Gradle an output to track. If inputs (dependency configurations)
and the output file haven't changed, the task becomes UP-TO-DATE.

Expected improvement: 8s → 0s on no-change builds.

## Fundamental / Long-Term Considerations

### Should We Restructure the `check` Lifecycle?

**Yes — the current `check` task is overloaded.** It runs static analysis (PMD,
SpotBugs, Spotless), custom verification tasks, test report aggregation, and
SSOT validation all in one shot. This means `build` (which depends on `check`)
always pays the full analysis cost, even for a one-line change.

**Industry best practice** (per [Gradle docs](https://docs.gradle.org/current/userguide/organizing_tasks.html))
is to create purpose-specific lifecycle tasks:

| Lifecycle Task | Contents | When to Run |
|---------------|----------|-------------|
| `assemble` | Compile + package | Every build (already 0.8s) |
| `quickCheck` | Spotless format check + compile errors | Developer inner loop |
| `check` | PMD + SpotBugs + custom verification | Pre-commit / CI |
| `fullCheck` | `check` + test + report aggregation | CI gate only |

This lets developers run `assemble` (0.8s) or `quickCheck` (~2s) for fast
feedback, while CI runs `fullCheck` for the complete quality gate.

### Should We Replace Spotless?

**No — but isolate the problematic part.** Per-module Java formatting via
Spotless + google-java-format works perfectly (fast, config-cache compatible,
no Windows issues). The problem is exclusively the **root-level KotlinGradle
formatter** which hits Windows-specific file-locking and line-ending bugs.

Options:
1. **Re-test without the workaround** on current versions (cheapest)
2. **Move `.kts` formatting to a pre-commit hook** (removes it from build)
3. **Switch `.kts` formatting to ktfmt-gradle** (standalone, simpler)

### Should We Replace PMD / SpotBugs?

**Not yet.** Both have proper Gradle integration:
- PMD has [incremental analysis](https://docs.pmd-code.org/latest/pmd_userdocs_incremental_analysis.html)
  enabled by default since Gradle 6.4, using a cache file to skip unchanged files
- SpotBugs has `@CacheableTask` support since version 4.2.1

The 9.3s `spotbugsMain` and 4.5s `pmdMain` on `:modules:ui` are real analysis
costs that are properly cached. If they become bottlenecks, the fix is to run
them only in CI (via the lifecycle restructure above), not to replace the tools.

### Memory & Daemon Long-Term

The daemon at 1GB heap is working well. **No fundamental change needed.**
The 10-minute idle timeout auto-reclaims memory. If the project grows
significantly (50+ modules), consider increasing to 1.5GB, but 1GB handles
the current 24-module build without issue.

## Implementation Priority

All P0, P1, P1.5, and P2 issues have been resolved. Only the P3 lifecycle
restructure remains as future work.

| Priority | Issue | Status |
|----------|-------|--------|
| **P0** | #1: Fix `build -x test` crash | **DONE** |
| **P0** | #2+3: Spotless config cache | **DONE** (removed kotlinGradle block) |
| **P1** | #4: Make `checkNoDirectSysProp` incremental | **DONE** |
| **P1** | #5: Add output to `enforceDependencyVersions` | **DONE** |
| **P1.5** | B1-B3: Remove `afterEvaluate` in 3 modules | **DONE** |
| **P1.5** | E: Replace `projectsEvaluated` with lazy wiring | **DONE** |
| **P1.5** | F: Add missing input hash to CUDA download | **DONE** |
| **P2** | C: Remove `redactionLint` `doLast` copy | **DONE** |
| **P2** | D: Remove dead `collectLauncherEvidence` task | **DONE** |
| **P2** | G: Remove orphaned `revapi` infrastructure | **DONE** |
| **P2** | H: Remove dead `writeClasspath` task | **DONE** |
| **P2** | I: Split AOT cache output directories | **DONE** |
| **P3** | Lifecycle restructure (`quickCheck`) | Future work |

## Changes Made

### Phase 1–2 (Original 5 Issues)

| File | Change |
|------|--------|
| `gradle.properties` | Daemon enabled, heap 2G→1G, 10-min idle timeout |
| `scripts/dev/dev-runner.cjs` | Removed `--no-configuration-cache` |
| `scripts/dev/run-headless-api.ps1` | Removed `--no-daemon` from non-runHeadless invocations |
| `reports/build.gradle.kts` | Removed report tasks from `check` dependency |
| `.github/workflows/ci.yml` | Added explicit report task invocation |
| `build.gradle.kts` | Removed Spotless `kotlinGradle` block (CC-breaking); replaced ad-hoc sysprop task with typed task |
| `build-logic/.../CheckNoDirectSysPropTask.kt` | NEW — typed task with `@InputFiles`/`@OutputFile` |
| `build-logic/.../EnforceDependencyVersionsTask.kt` | Added `@OutputFile` for UP-TO-DATE tracking |
| `build-logic/.../DependencyHygienePlugin.kt` | Set `reportFile` on task registration |

### Phase 3 (Build Debt Cleanup)

| File | Change |
|------|--------|
| `modules/app-launcher/build.gradle.kts` | `afterEvaluate` → direct `configureEach` for JaCoCo excludes |
| `modules/ipc-common/build.gradle.kts` | `afterEvaluate` → `withType().configureEach` for JaCoCo report excludes |
| `modules/system-tests/build.gradle.kts` | `afterEvaluate` → eager `configureEach` + direct PMD config |
| `build.gradle.kts` | `gradle.projectsEvaluated` → `subprojects { pluginManager.withPlugin("base") }` |
| `modules/ui/build.gradle.kts` | Added `inputs.property("url", url)` to CUDA download task |

## Phase 3: Early-Phase Build Debt Cleanup

An audit beyond the original 5 issues revealed a pattern of pre-CC build idioms
surviving from Phases 4a–13 (Nov–Dec 2025). These don't affect `build -x test`
timings (already 3.4s) but degrade configuration cache compatibility and
correctness of UP-TO-DATE checking.

### Findings Triaged

| ID | Issue | Path | Impact | Status |
|----|-------|------|--------|--------|
| B1 | `afterEvaluate` for JaCoCo excludes | `modules/app-launcher/build.gradle.kts` | CC-hostile | **DONE** |
| B2 | `afterEvaluate` for JaCoCo report excludes | `modules/ipc-common/build.gradle.kts` | CC-hostile | **DONE** |
| B3 | `afterEvaluate` for JaCoCo/PMD disabling | `modules/system-tests/build.gradle.kts` | CC-hostile | **DONE** |
| E | `gradle.projectsEvaluated` for check wiring | `build.gradle.kts` | CC-hostile | **DONE** |
| F | Missing `inputs.property` on CUDA download | `modules/ui/build.gradle.kts` | Stale UP-TO-DATE | **DONE** |
| C | `redactionLint` dual-output (undeclared repo copy) | `build.gradle.kts` | Broken caching | **DONE** |
| D | `collectLauncherEvidence` — dead task | `modules/app-launcher/build.gradle.kts` | Dead code, CC poison | **DONE** |
| G | `revapi` — orphaned infrastructure | `modules/app-api/build.gradle.kts` | Config-time overhead | **DONE** |
| H | `writeClasspath` — dead task | `modules/indexer-worker/build.gradle.kts` | Dead code | **DONE** |
| I | AOT tasks share output directory | `modules/ui/build.gradle.kts` | Correctness hazard | **DONE** |

### Changes Made (Phase 3)

| File | Change |
|------|--------|
| `modules/app-launcher/build.gradle.kts` | `afterEvaluate` → direct `configureEach` for JaCoCo coverage excludes |
| `modules/ipc-common/build.gradle.kts` | `afterEvaluate` inside `withType<JacocoReport>` → `withType().configureEach` |
| `modules/system-tests/build.gradle.kts` | `afterEvaluate` → eager `configureEach` + direct PMD config |
| `build.gradle.kts` | `gradle.projectsEvaluated` → `subprojects { pluginManager.withPlugin("base") }` |
| `modules/ui/build.gradle.kts` | Added `inputs.property("url", url)` to `downloadLlamaCudaPrebuilt` |

### Phase 3b (Deferred Item Cleanup)

| File | Change |
|------|--------|
| `modules/app-launcher/build.gradle.kts` | Removed `collectLauncherEvidence` task (~92 lines) |
| `modules/app-api/build.gradle.kts` | Removed `revapi` task, configs, and version pins (~68 lines) |
| `modules/indexer-worker/build.gradle.kts` | Removed `writeClasspath` task (~14 lines) |
| `build.gradle.kts` | Removed `redactionLint` `doLast` copy and unused `repoRoot` val |
| `modules/ui/build.gradle.kts` | Split AOT cache `outputs.dir` to `aot-cache/head/` and `aot-cache/worker/` |

### Genealogy

All debt traces to the same accumulation pattern:

1. **Phases 4a–13** (Nov–Dec 2025) — build tasks added as `doLast` blocks
   using `afterEvaluate`, `gradle.projectsEvaluated`, eager classpath capture.
2. **CC enablement** (a5a4c2b6, Jan 2026) — extracted some tasks to typed
   classes in `build-logic/` but missed JaCoCo `afterEvaluate` blocks and
   evidence-collection tasks.
3. **Later features** (tempdocs 243, 268, 269) — added tasks without checking
   whether `build-logic` conventions applied.
4. **Nobody audited the full task graph** after incremental additions. Each
   task was correct in isolation; aggregate CC/caching cost was never measured.

### Deferred Items — Deep Investigation (2026-03-11)

Further investigation of each deferred item with actual usage analysis:

#### Recommended Removals

- **D: `collectLauncherEvidence`** (`modules/app-launcher/build.gradle.kts:150-230`)
  — Phase 9 evidence task. 80 lines. CC-hostile (`rootProject.projectDir`,
  eager classpath capture, `notCompatibleWithConfigurationCache`). Output dir
  `reports/phase9/launcher/` is **not git-tracked** (`.gitignore` blocks it).
  **Not referenced by any CI workflow, script, or other task.** The evidence
  files that exist on disk are local-only artifacts that were never committed.
  `smokeSidecarBundle` already covers launcher smoke testing at the bundle level.
  **Verdict: dead code, safe to delete.**

- **`revapi` task** (`modules/app-api/build.gradle.kts:72-137`) — Revapi API
  compatibility checker using Ant builder. Brings 2 custom configurations
  (`revapiAnt`, `revapiOld`), 5 dependency declarations, and 5 version convergence
  pins that are evaluated at **configuration time** on every build even though the
  task never runs. Not referenced in any CI workflow (zero hits in `.yml`/`.sh`).
  The baseline resolution (`findProperty("revapiBaseline") ?: project.version`)
  tries to resolve the current version as a Maven artifact, which fails unless the
  project has been published. **Verdict: orphaned infrastructure. The configuration-
  time dependency resolution adds overhead to every build. Remove the task, both
  configurations, and all version pins.**

- **`writeClasspath`** (`modules/indexer-worker/build.gradle.kts:127-140`) —
  Writes runtime classpath to a file. Has `outputs.file` but no `inputs.*`.
  **Not referenced by any script, CI workflow, or other task.** Was likely used
  during early development for manual launching; `installDist` now handles
  distribution. **Verdict: dead code, safe to delete.**

#### Recommended Cleanups

- **C: `redactionLint` dual output** (`build.gradle.kts:441-468`) — The `doLast`
  block copies the report a second time to `reports/phase13/privacy/redaction-lint.html`,
  a git-tracked repo-root path. The Phase 13 evidence directory contains 60+
  committed files from a one-time evidence collection effort. The second copy is
  undeclared as a Gradle output, so UP-TO-DATE checking doesn't account for it.
  **Verdict: remove the `doLast` copy.** Phase 13 evidence is committed and done;
  the `build/reports/analysis/` copy (Gradle-tracked) is sufficient. If evidence
  refresh is needed, copy manually.

- **AOT shared output directory** (`modules/ui/build.gradle.kts:1011,1059`) —
  Both `generateHeadAotCache` and `generateWorkerAotCache` declare
  `outputs.dir(aotCacheDir)` where `aotCacheDir = layout.buildDirectory.dir("aot-cache")`
  — the **same directory**. Gradle cannot correctly track two tasks claiming the
  same output. If run in parallel, they clobber each other. Works by accident due
  to transitive ordering. **Verdict: split to `aot-cache/head/` and
  `aot-cache/worker/` and update `bundleSidecarResources` `from()` accordingly.**

#### Acceptable As-Is

- **`upToDateWhen { false }`** on `stageLlamaCudaVariant` and
  `stageLlamaServerFromSource` — distribution-path tasks. `stageLlamaCudaVariant`
  justified: the `doLast` writes NOTICE file outside Sync's source set, and the
  parent task's `preserve { }` block means output must always be fresh.
  `stageLlamaServerFromSource` is theoretically fixable with proper `@InputDirectory`
  but only runs when building llama-server from source (not the default prebuilt
  path). **Leave as-is.**

- **AOT cache tasks** (`generateHeadAotCache`, `generateWorkerAotCache`) —
  CC-incompatible due to `ProcessBuilder` for two-step JVM AOT training.
  `ExecOperations` injection would not fix this: the tasks run two sequential
  commands where the second depends on the first's output, and the classpath is
  built dynamically from `listFiles()` at execution time. These are inherently
  execution-time operations. Correctly marked `notCompatibleWithConfigurationCache`.
  In the distribution path only (via `bundleSidecarResources`), not `check`.
  **Leave as-is** (aside from the shared output dir fix above).

- **Duplicated `sha256Hex`** — 3 identical copies in `modules/ui/build.gradle.kts`
  (lines 341, 508, 584). Inside `doLast` blocks of download tasks. The real fix
  is extracting a `DownloadWithSha256Task` typed class in `build-logic/`, which
  would also give proper CC compatibility. **Low priority** — ~150 lines saved
  but no functional impact.

## Phase 4: Downstream Timeout Recalibration

With `build -x test` dropping from 68s to 3.4s, several timeouts and
documentation references were calibrated for the old timings.

### Measured Startup Breakdown (dev-runner, `--no-daemon`)

| Phase | Time | Cumulative |
|-------|------|------------|
| Gradle single-use daemon (compile) | ~9s | 9s |
| App startup (Head) | ~4.2s | 13s |
| API port emitted | — | **13s** |
| Worker index initialization | ~25s | **38s** |

### Changes Made (Phase 4)

| File | Change |
|------|--------|
| `scripts/dev/dev-runner.cjs` | Port timeout 30s→20s, ready timeout 30s→60s; updated comment |
| `scripts/dev/justsearch-dev-mcp/server.mjs` | Tool descriptions: "warm: ~30s" → "~15s HTTP / ~40s worker"; `waitTimeoutMs` default 30s→60s |
| `scripts/dev/justsearch-dev-mcp/schemas.mjs` | Updated `waitTimeoutMs` description to reflect 60s default |
| `scripts/lib/bench/backend-launcher.mjs` | Fixed stale help text: "default 120" → "default 30" |
| `.claude/rules/agent-lessons.md` | Updated cold-start timing and MCP timeout reference |

### Rationale

- **Port emit timeout 30s→20s**: Port emits at ~13s. 20s gives 50% headroom.
- **Ready timeout 30s→60s**: Worker ready at ~38s. The old 30s was causing
  `waitReadyTimeout: true` on every `ready_worker` start. 60s gives headroom
  for slower machines.
- **CI timeouts unchanged**: 300s (5 min) CI timeouts are upper bounds and
  remain appropriate for CI cold starts.

## Phase 5: Application Startup Analysis

### Measured Startup Breakdown (Detailed)

After Gradle launches the JVM, HeadlessApp reports per-phase timing:

```
Startup phases (ms): settings=137, telemetry=81, policy=28, worker=3409, facade=210, api=296, ready=0, total=4165
```

The `worker=3409ms` breaks down further:

| Component | Time | Notes |
|-----------|------|-------|
| **Windows process spawn** | ~2.0s | `ProcessBuilder.start()` → JVM bootstrap |
| Worker JVM classloading | ~0.3s | Config load, resolved config builder |
| RRD metrics open | 27ms | `RrdMetricStore.open()` |
| SQLite jobs.db open | 160ms | Includes native lib extraction + migration |
| Index lock + SSOT load | 230ms | `IndexRootLock` + field/analyzer catalogs |
| Lucene index open | ~50ms | Schema validation, `LuceneIndexRuntime.start()` |
| Embedding fingerprint | 105ms | SHA-256 of embedding model (cached per process) |
| Disambiguation store | 324ms | SQLite `entity-clusters.db` open |
| gRPC bind + port emit | 175ms | Netty server bind → MMF write |
| **Head poll latency** | ~100ms | 100ms poll interval in `MainSignalBus.awaitPort()` |

**Total Worker init to port: ~1.3s.** The remaining ~2.1s is Windows process
spawn overhead (CreateProcess → JVM bootstrap → first bytecode).

### Post-Port Initialization (Background, Not Blocking Head)

After the Worker writes its port, the Head unblocks and starts its facade/API.
Meanwhile the Worker continues:
- Indexing loop starts (embedding model reload attempt)
- Background watcher initialization
- Help file auto-ingest (first run only)
- Periodic sync scheduler

This background work takes ~25s but doesn't block the Head's API from serving.
The `ready_worker` readiness probe (gRPC health check from dev-runner) waits for
the Worker's `/IndexStatus` RPC to return successfully, which requires the
indexing loop to be running.

### Existing Infrastructure Audit

Before proposing fixes, an audit of what the codebase already has for each issue:

| Issue | Existing Infrastructure | Gap |
|-------|------------------------|-----|
| **S1: AOT Cache** | Fully implemented for production: `AotTraining.java` (Head + Worker), Gradle tasks `generateHeadAotCache`/`generateWorkerAotCache`, runtime flags in `WorkerSpawner.java:286-290` and `lib.rs:538-546` | **Dev mode has no AOT** — cache invalidated on every recompile |
| **S2: Parallel Startup** | None. Head blocks sequentially in `HeadlessApp.java:241-244`. Worker writes port after full init (`KnowledgeServer.java:481-485`). Head polls `MainSignalBus.awaitPort()` at 100ms intervals | **Entire pattern needs redesign** |
| **S3+S4: Fingerprints** | `SpladeFingerprint.java` and `EmbeddingFingerprint.java` compute full SHA-256 with 8MB buffer. Cached in `AtomicReference<CachedResult>` (per-process only, lost on restart) | **No sidecar/persistent cache** |
| **S5: Native Libs** | ONNX: `OrtCudaHelper.resolveOrtNativePath()` supports `-Donnxruntime.native.path` and `JUSTSEARCH_NATIVE_PATH`. CUDA DLLs pre-loaded in order. SQLite: no pre-extraction | **SQLite has no pre-extract**. No pre-optimized ONNX models (no `setOptimizedModelFilePath` usage) |
| **S6: Analyzer Registry** | `SsotAnalyzerRegistry` is a singleton via ComponentsFactory — already loaded once per runtime. Log messages likely from multiple callers referencing same cached instance | **Not an issue** — already deduplicated |
| **S7: Bypass Gradle** | `dev-runner.cjs:546-608` always uses `gradlew.bat --no-daemon :modules:ui:runHeadless`. `installDist` output exists at `build/install/` and is wired into `assemble` | **No direct-launch path** exists in dev-runner |
| **S8: Worker Timing** | HeadlessApp has per-phase timing instrumentation. KnowledgeServer now instrumented (S4 done) | **DONE** |
| **JVM Flags** | Worker gets `-Xms`, `-Xmx`, crash diagnostics, module opens. No startup-optimizing flags (`TieredStopAtLevel`, `SerialGC`, `UsePerfData`) | **None applied** |

#### Key Existing Code Paths

- **AOT training**: `modules/ui/src/main/java/.../AotTraining.java` (Head), `modules/indexer-worker/src/main/java/.../AotTraining.java` (Worker)
- **AOT Gradle tasks**: `modules/ui/build.gradle.kts:999-1105` (two-step record + create)
- **AOT bundling**: `modules/ui/build.gradle.kts:1107-1144` (`bundleSidecarResources` includes `aot/`)
- **Worker JVM flags**: `WorkerSpawner.buildCommand()` at `modules/app-services/.../WorkerSpawner.java:277-393`
- **Head startup**: `HeadlessApp.java:79-341` — sequential phases with timing
- **Worker startup**: `KnowledgeServer.java:176-502` — 15+ sequential init steps, no timing
- **Port discovery**: `MainSignalBus.java:210-262` — MMF poll with early crash detection
- **Health check**: Custom proto `HealthService.Check` in `indexing.proto:857-881` — not standard `grpc.health.v1`
- **ONNX native path**: `OrtCudaHelper.java:104-121` — env/property-based resolution
- **CUDA DLL preload**: `OrtCudaHelper.java:145-159` — ordered System.load()
- **Fingerprints**: `SpladeFingerprint.java`, `EmbeddingFingerprint.java` — full SHA-256, per-process cache

### Identified Startup Issues

#### S1: Windows Process Spawn Dominates Worker Phase (~2s of 3.4s)

`ProcessBuilder.start()` on Windows is inherently slow (~2s for a JVM) due to
CreateProcess API overhead, JVM bootstrap, and classloading. This is the single
largest component of the `worker=3409ms` phase.

**Best practice (JDK 25):** JEP 483 (AOT class loading/linking), JEP 514
(single-command cache generation), and JEP 515 (method profiling) collectively
enable up to **42% startup reduction** (Oracle benchmarks). The codebase
already has `AotTraining.java` for both Head and Worker, and
`WorkerSpawner.buildCommand()` (line 287) applies `-XX:AOTCache` in production
mode. But **dev mode does not use AOT** because the cache would be invalidated
on every recompile.

JDK 25's JEP 514 simplifies cache generation to a single command:
`java -XX:AOTCacheOutput=app.aot -cp app.jar com.example.App`. JEP 515 adds
method profiling so the JIT compiles hot methods immediately at boot (~19%
additional improvement on top of JEP 483).

**Potential fix:** Generate a dev-mode AOT cache as a Gradle task that runs
after `installDist`. JDK 25 auto-detects classpath mismatches, so the cache
self-invalidates when jars change. Windows Defender exclusions on the JDK and
build directories can save an additional 0.5-2s (Defender scans every new
process and loaded DLLs on Windows).

**Realistic improvement:** 2.0s → 0.5-1.0s (1-1.5s saved). Additional JVM
flags for the Head process (`-XX:TieredStopAtLevel=1 -XX:+UseSerialGC`) can
save ~0.3s more on the Head side (acceptable for small-heap orchestrator;
Worker should keep G1GC for throughput).

#### S2: Head Blocks Sequentially on Worker Port (~3.4s)

HeadlessApp.main() runs all phases **strictly sequentially**: settings →
telemetry → policy → **worker (blocking)** → facade → api. The Head cannot
serve HTTP until the Worker has written its gRPC port.

**Best practice:** Start the Worker process early (as soon as config is ready)
and poll for the port in the background while the Head sets up facade/API.
The gRPC client connection can be established lazily — first search request
blocks until the Worker is connected, but the API server itself starts
immediately.

**Pattern:** The gRPC Health Checking Protocol (`grpc/grpc` `doc/health-checking.md`)
explicitly supports this via `ServingStatus`:
- `NOT_SERVING` — server is up, TCP accepted, but not ready for RPCs
- `SERVING` — accepting requests

In Java: `io.grpc.services.HealthStatusManager` manages per-service status.
The Worker would bind gRPC immediately, set `NOT_SERVING`, load Lucene/models,
then transition to `SERVING`. The Head connects eagerly via `HealthStatusManager`
and polls. First actual search blocks until `SERVING`.

This is the standard Kubernetes readiness probe pattern. `startupProbe` gates
liveness/readiness until init completes; `readinessProbe` controls traffic.

**Realistic improvement:** 3.4s worker phase → ~0s blocking (facade + API
start in parallel with Worker spawn). API port emitted ~0.5s after settings
phase. Total Head startup: ~0.5s instead of 4.2s.

**Tradeoff:** More complex startup orchestration. First search request may
be slower if issued before Worker is ready. Health endpoint would report
`DEGRADED` until Worker connects. All Head-side callers must handle
`UNAVAILABLE` status (gRPC Wait-for-Ready semantics handle this natively).

#### S3: SPLADE Fingerprint on Critical Path (373ms When Present)

When SPLADE model is present (production), `SpladeFingerprint.get()` computes
a SHA-256 of the entire `model.onnx` file (typically ~100-400ms depending on
model size). This runs synchronously before gRPC bind.

**Best practice:** Compute fingerprints in a background thread. Cache the
fingerprint alongside the model file (e.g., `model.onnx.sha256` sidecar file).
Only recompute if the model file's mtime/size changed.

**Realistic improvement:** 373ms → ~1ms (file stat check).

#### S4: Embedding Fingerprint Similarly on Critical Path (105ms)

`EmbeddingFingerprint` computes SHA-256 of the embedding model (105ms). Same
pattern as S3.

**Fix:** Same sidecar cache approach.

#### S5: Native Library Extraction Penalty (SQLite + ONNX)

Both SQLite-JDBC and ONNX Runtime extract native DLLs from JARs on first use:
- **SQLite-JDBC:** extracts `sqlite-jdbc.dll` to `java.io.tmpdir`. Pre-extract
  with `-Dorg.sqlite.lib.path=/path`. The 324ms `EntityClusterStore` open
  includes this extraction + schema validation.
- **ONNX Runtime:** extracts `onnxruntime.dll` + `onnxruntime4j_jni.dll`. Pre-extract
  with `-Donnxruntime.native.path=/path`. The first `OrtEnvironment.getEnvironment()`
  call is the expensive one (hundreds of ms); the C++ init itself is single-digit ms.

Additionally, ONNX `env.createSession()` performs graph optimization eagerly
(1-5s for SPLADE-class transformers at `ORT_ENABLE_ALL`). This can be avoided
by pre-optimizing the model offline:
- First run: `SessionOptions.setOptimizedModelFilePath("model_opt.onnx")`
- Subsequent runs: load `model_opt.onnx` with `ORT_DISABLE_ALL`
- Or convert to `.ort` format (ORT's pre-optimized format) via Python tooling

**Realistic improvement:** 324ms → ~50ms for SQLite (pre-extract). ONNX model
load 1-5s → 200-800ms (pre-optimized model, skip graph optimization).
Combined: ~0.5-1s if both models are deferred off critical path.

#### S6: SsotAnalyzerRegistry Loaded 4+ Times Per Startup — NOT AN ISSUE

~~The worker log shows `Loaded analyzers catalog` appearing multiple times.~~

**Audit finding:** `SsotAnalyzerRegistry` is already a singleton via
`ComponentsFactory`. Log messages are from multiple callers referencing the same
cached instance, not repeated I/O. **No fix needed.**

#### S7: Gradle in the Launch Path (~9s Overhead)

The dev-runner launches via `gradlew.bat --no-daemon :modules:ui:runHeadless`,
paying ~9s for a single-use Gradle daemon even when all tasks are UP-TO-DATE.
This is the build system configuring 24 subprojects just to run a `JavaExec` task.

**Best practice:** Launch from `installDist` output directly, bypassing Gradle
entirely at runtime. The `installDist` task produces a complete distribution
with start scripts at `modules/ui/build/install/ui-headless/bin/`. Dev-runner
could invoke this directly after ensuring `assemble` has run.

**Realistic improvement:** 9s → ~0s Gradle overhead. The JVM process starts
immediately. Combined with S2, total dev-runner start-to-HTTP: ~0.5s (was 13s).
Start-to-search: ~1.5s (was 38s).

**Tradeoff:** Requires a separate `assemble` step before first `start`. The
current approach auto-compiles on `runHeadless`. Could be mitigated by having
dev-runner check if dist is stale and run `assemble` only when needed.

#### S8: No Startup Timing Instrumentation in Worker

HeadlessApp has detailed per-phase timing (`settings=137, telemetry=81, ...`),
but `KnowledgeServer.start()` in the Worker has **no timing instrumentation**.
The only way to measure Worker startup phases is by parsing log timestamps.

**Fix:** Add `System.nanoTime()` milestones and a summary log line at the end
of `KnowledgeServer.start()`, matching HeadlessApp's pattern.

### Startup Optimization Priority

| Priority | Issue | Savings | Complexity |
|----------|-------|---------|------------|
| **P0** | S7: Launch from installDist, bypass Gradle | ~9s | **DONE** |
| **P0** | S2: Parallel Worker spawn + Head API start | ~3.4s | **DONE** |
| **P1** | S1: Dev-mode AOT cache (JEP 514) | 1-1.5s | **DONE** |
| **P1** | S3+S4: Sidecar fingerprint caching | ~0.5s | **DONE** |
| **P1** | S5: Pre-optimize ONNX models (OnnxSessionCache) | 0.5-1s | **DONE** |
| **P2** | S8: Worker timing instrumentation | 0s (observability) | **DONE** |
| ~~P2~~ | ~~S6: Deduplicate analyzer registry loads~~ | ~~~50ms~~ | Already singleton — not an issue |
| **P2** | S1 addendum: Head JVM flags (TieredStop, SerialGC) | ~0.3s | **DONE** |

### Theoretical Best-Case Startup

With S7 (bypass Gradle) + S2 (parallel Worker) + S1 (AOT cache):

| Scenario | Current | Achievable | How |
|----------|---------|------------|-----|
| Dev-runner start-to-HTTP | 13s | **~0.5s** | Direct launch + parallel Worker |
| Dev-runner start-to-search | 38s | **~2s** | AOT Worker + early gRPC bind |
| Dev-runner start-to-HTTP (cold, no dist) | 13s | **~4s** | assemble (3s warm) + direct launch |

**Combined potential:** The 38s start-to-search drops to ~2s — a **19x improvement**
on top of the Gradle build improvement. The 13s start-to-HTTP drops to ~0.5s.

### Research Sources

- JDK 25 AOT: [JEP 483](https://openjdk.org/jeps/483), [JEP 514](https://openjdk.org/jeps/514), [JEP 515](https://openjdk.org/jeps/515)
- gRPC health: [Health Checking Protocol](https://github.com/grpc/grpc/blob/master/doc/health-checking.md), [grpc-java #5519](https://github.com/grpc/grpc-java/issues/5519) (Netty classloading)
- ONNX Runtime: [Graph optimizations](https://onnxruntime.ai/docs/performance/model-optimizations/graph-optimizations.html), [ORT format](https://onnxruntime.ai/docs/performance/model-optimizations/ort-format-models.html)
- SQLite-JDBC: `-Dorg.sqlite.lib.path` for pre-extraction
- Windows process spawn: CreateProcess is >20x slower than Linux fork+exec; Defender exclusions save 0.5-2s

### Tradeoff Analysis (Internet Research)

Detailed tradeoff research for each issue, based on internet research and codebase audit.

#### S7 Tradeoffs: Bypass Gradle for Dev Launch

| Approach | Gradle overhead | Staleness risk | Notes |
|----------|----------------|----------------|-------|
| `gradlew runHeadless` (current) | ~9s always | None | Auto-compiles |
| `gradlew installDist` + exec script | ~1-9s (UP-TO-DATE) | Medium | Still invokes Gradle |
| Wrapper staleness check + exec script | 0s if no changes | Low | Dev-runner checks src mtime vs dist jars |
| Config cache + `installDist` | <1s if cache valid | Low | Best balance |

**Key findings:**
- Spring Boot, Quarkus, and Micronaut all bypass the build tool at runtime in dev mode.
  Quarkus uses in-JVM ECJ recompilation triggered by HTTP requests. Micronaut uses
  `gradle -t classes` (continuous compilation in a separate terminal).
- Gradle `--continuous` is incompatible with long-running `JavaExec` tasks (the server
  never returns, so the file watcher never starts — [gradle#1128](https://github.com/gradle/gradle/issues/1128)).
- **System property replication risk:** `runHeadless` sets properties via `systemProperty()`
  in `build.gradle.kts` that the generated start script does NOT inherit. These must be
  replicated in `applicationDefaultJvmArgs` or passed by the wrapper script.
- **Recommended approach:** Dev-runner checks if any source file is newer than the youngest
  JAR in `build/install/ui-headless/lib/`. If stale, run `./gradlew.bat installDist` (with
  config cache, ~1s). Then exec the start script directly. 9s → 0-1s.

**Implementation audit (system property gap):**

`runHeadless` sets only 1 system property: `-Djustsearch.api.port` (from `JUSTSEARCH_API_PORT`
env var, default 33221). `applicationDefaultJvmArgs` has 2 items (`--sun-misc-unsafe-memory-access`,
`--enable-native-access`). The 8 AI env vars (`JUSTSEARCH_SERVER_EXE`, `JUSTSEARCH_MODELS_DIR`,
etc.) are pass-through from the parent process — dev-runner already sets these.

**Gap is minimal:** Dev-runner already passes `JUSTSEARCH_API_PORT` as an env var, and
HeadlessApp reads it via `System.getenv()`. The system property is redundant. The start
script inherits env vars from the parent, so all variables dev-runner sets will be
available. **No system property migration needed** — env vars cover everything.

**Confidence: HIGH.** The gap analysis shows this is a clean change with no hidden
property dependencies.

#### S2 Tradeoffs: Parallel Worker Spawn + Early gRPC Bind

**Risks (all low for this use case):**
- **TCP backlog overflow:** Negligible — single client, loopback network.
- **HTTP/2 SETTINGS timeout:** Sub-millisecond on loopback. Not a concern.
- **RPCs during initialization:** Worker should return `UNAVAILABLE` (status 14)
  until ready. This is the semantically correct retryable gRPC status.
- **Wait-for-Ready limitation:** Only helps when channel is in `CONNECTING` or
  `TRANSIENT_FAILURE`. Once HTTP/2 handshake completes (channel `READY`), RPCs
  dispatch immediately — if the handler returns `UNAVAILABLE`, the client gets it.
  Wait-for-Ready does NOT queue RPCs when the server returns `UNAVAILABLE`.

**User-facing impact:**
- Head serves HTTP before Worker is ready → search endpoints return 503.
- UI must show a loading/warming-up state until Worker health transitions.
- This is the standard desktop app pattern (splash screen / loading overlay).

**gRPC health protocol choice:**
- Standard `grpc.health.v1` gives push-based `Watch()` streaming (instant notification
  of status transitions), tooling compatibility (`grpc_health_probe`, k8s probes), and
  canonical `NOT_SERVING`/`SERVING` semantics.
- Custom `bool serving` in `indexing.proto` is functionally equivalent for single-client
  but lacks `Watch()` push (requires polling) and external tool support.
- **Recommendation:** Migrate to standard `grpc.health.v1` for `Watch()` push semantics.
  The `Watch` method eliminates the 100ms poll interval in `MainSignalBus.awaitPort()`.

**Pattern for JustSearch:**
1. Worker calls `Server.start()` immediately → writes port to MMF → handlers return
   `UNAVAILABLE` until init complete
2. Head reads port, creates channel with Wait-for-Ready, starts HTTP API immediately
3. Head HTTP returns 503 for Worker-dependent endpoints until Worker reports `SERVING`
4. Head uses `Watch()` for push-based readiness notification (no polling)

**Implementation audit (dependency chain):**

Critical finding — **AppFacadeBootstrap has a hard constructor dependency on
`KnowledgeServerBootstrap.start()` completing:**

```
HeadlessApp.tryStartKnowledgeServer()  [BLOCKING 3.4s]
  └─ KnowledgeServerBootstrap.start()
     ├─ spawner.start() → Worker spawned, port returned
     ├─ client.connect(port) → gRPC channel established
     └─ client.isHealthy() → first health check

AppFacadeBootstrap(telemetry, configManager, knowledgeServer)
  └─ constructor calls knowledgeServer.client()  ← THROWS if not started
     └─ client is used to create SearchPort, IndexingService, etc.

LocalApiServer.builder(facade, ...)
  └─ Takes already-constructed AppFacade — NO remote calls during construction
  └─ Controllers call facade lazily on HTTP request arrival
```

The naive "start API in parallel" fails because `AppFacadeBootstrap` constructor
dereferences `knowledgeServer.client()`. **Two refactoring approaches:**

**Option A (minimal change):** Make `AppFacadeBootstrap` accept a `Supplier<RemoteKnowledgeClient>`
instead of `KnowledgeServerBootstrap`. The supplier blocks on first call (when the first
HTTP request arrives that needs the Worker). Facade construction proceeds with a
`NoopSearchPort` until the Worker connects.

**Option B (deeper change):** Split `KnowledgeServerBootstrap.start()` into `spawn()` (async,
returns `CompletableFuture<RemoteKnowledgeClient>`) and `awaitReady()`. The Head spawns the
Worker, creates the facade with a not-yet-connected client proxy, starts the API, then
awaits readiness in the background.

**Worker-side gRPC services (only 3):**
- `GrpcHealthService` — already queries backends; needs `if (!ready)` guard
- `GrpcIngestService` — depends on jobQueue, indexingLoop, Lucene runtimes
- `GrpcSearchService` — depends on indexRuntime, embeddingService

All 3 take their dependencies as constructor parameters in `KnowledgeServerGrpcWiring`.
For early gRPC bind, these services would need either (a) a shared `AtomicBoolean ready`
that guards all RPC methods, or (b) to be registered after initialization via
`ServerBuilder.addService()` (gRPC-java supports adding services to a running server
via `HandlerRegistry`).

**Confidence: MEDIUM.** The refactoring is well-scoped (AppFacadeBootstrap constructor +
3 gRPC services + KnowledgeServer startup reordering) but has enough surface area to
harbor surprises. Option A is lower risk.

#### S1 Tradeoffs: Dev-Mode AOT Cache

**AOT already exists for production** (AotTraining.java, Gradle tasks, runtime flags).
Gap is dev mode only.

**Key finding: `TieredStopAtLevel=1` conflicts with AOT cache.** The AOT cache is
trained with C2 (TieredStopAtLevel=4). Running with TieredStopAtLevel=1 bypasses all
cached C2-compiled methods — the cache is wasted. These two optimizations are
**mutually exclusive** on JDK 25. Since AOT saves 1-1.5s vs TieredStopAtLevel=1
saving ~0.3s, AOT is the clear winner.

**Dev-mode invalidation concern:** JDK 25's AOT cache auto-detects classpath mismatches
and self-invalidates. But regenerating the cache takes a training run (~5s). The cache
would go stale on every recompile, requiring either:
- A background regeneration after `installDist` (acceptable latency)
- Or accepting the first-run-after-recompile pays full startup cost

**JDK version confirmed: JDK 25** (`gradle/libs.versions.toml` line 2: `jdk = "25"`,
CI sets `JAVA_VERSION: "25"`). JEP 483/514/515 are fully available. JDK 21 is installed
only as a fallback for Error Prone test compilation.

**Confidence: MEDIUM-HIGH.** The existing `generateHeadAotCache`/`generateWorkerAotCache`
tasks provide the template. Dev-mode variant needs to auto-trigger after `installDist`
and handle the classpath-change invalidation path.

#### S3+S4 Tradeoffs: Fingerprint Sidecar Caching

**Staleness detection — mtime+size is industry standard:**
- ccache, sccache both use mtime+size as the fast pre-filter. npm/Maven use pure hash.
- NTFS stores mtime at 100ns resolution. `Files.getLastModifiedTime()` (NIO) preserves
  millisecond precision. Do NOT use `File.lastModified()` (had truncation bug pre-JDK 10).
- For 100-400MB model files, two sequential writes cannot produce identical mtime.
- **Use both mtime AND size** — negligible overhead, catches edge cases.

**Sidecar format:**
```
sha256:<hex> mtime:<epoch-millis> size:<bytes>
```

**Atomicity:**
- Write to temp file, then `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`.
- Crash during write leaves old sidecar intact (temp file is the partial write).
- On Windows, `ATOMIC_MOVE` may throw `AtomicMoveNotSupportedException` — fall back
  to `REPLACE_EXISTING` without `ATOMIC_MOVE`. Still safe: old sidecar survives.

**Risk:** Essentially zero for model integrity checking. Worst case on corruption/crash:
sidecar is missing → full SHA-256 recomputed on next start (105-373ms, one-time cost).

#### S5 Tradeoffs: Native Library Pre-Extraction + ONNX Pre-Optimization

**SQLite-JDBC:**
- Extracts a NEW DLL to `%TEMP%` on every JVM launch ([xerial/sqlite-jdbc#161](https://github.com/xerial/sqlite-jdbc/issues/161)).
  No cross-restart caching. DLL filenames include UUID.
- Pre-extract with `-Dorg.sqlite.lib.path=<dir> -Dorg.sqlite.lib.name=sqlitejdbc.dll`.
- **Version mismatch risk:** No runtime version check. DLL must match JAR version.
  If sqlite-jdbc is upgraded, pre-extracted DLL must be re-extracted.
- Estimated savings: 5-100ms (unquantified publicly; depends on disk + antivirus).

**ONNX pre-optimized models:**
- `setOptimizedModelFilePath("model_opt.onnx")` on first run serializes the optimized
  graph. Subsequent runs load with `ORT_DISABLE_ALL` to skip optimization (1-5s saved).
- **Pre-optimized models are NOT portable across execution providers** (CPU vs CUDA)
  or hardware (AVX2 vs non-AVX2). Must optimize per-machine.
- **ORT format (.ort) breaks across major ORT versions** (v4→v5 at ORT 1.13).
  Must regenerate on ORT upgrades.
- **Recommended for JustSearch:** Per-machine first-run optimization with local cache.
  Ship raw `.onnx`, optimize on first launch, cache the result. Use mtime+size sidecar
  (from S3/S4) on the source `.onnx` to detect model changes. Store ORT version in
  sidecar to invalidate on ORT upgrades.
- `ORT_ENABLE_EXTENDED` (not `ORT_ENABLE_ALL`) is safer for cross-hardware caching —
  layout optimizations in `ORT_ENABLE_ALL` are CPU-architecture-specific.

**ONNX native lib pre-extraction:**
- Already supported via `-Donnxruntime.native.path` (and `OrtCudaHelper` resolves it).
- The Worker already passes this for CUDA. For CPU-only mode, the DLLs still extract
  from the JAR. Could pre-extract alongside `installDist`.

**Implementation audit (ONNX session patterns):**

All 5 ONNX components use **identical default SessionOptions** with no explicit
optimization level or `setOptimizedModelFilePath`:

| Component | CPU Session | GPU Session | Pattern |
|-----------|------------|-------------|---------|
| CrossEncoderReranker | `env.createSession(path)` | Lazy, CUDA opts | Default opts |
| CitationScorer | `env.createSession(path)` | None (CPU-only) | Default opts |
| SpladeEncoder | `env.createSession(path)` | Lazy, CUDA opts | Default opts |
| BertNerInference | `env.createSession(path)` | None (CPU-only) | Default opts |
| OnnxEmbeddingEncoder | `env.createSession(path)` | Lazy, CUDA opts | Default opts |

The uniform pattern means a shared helper can handle all 5:
```java
// Potential shared helper in a utility class:
static OrtSession createCachedSession(OrtEnvironment env, Path modelPath, SessionOptions opts) {
    Path optimized = modelPath.resolveSibling(modelPath.getFileName() + ".optimized");
    if (Files.exists(optimized) && isOptimizedCacheValid(modelPath, optimized)) {
        opts.setOptimizationLevel(OptLevel.ORT_DISABLE_ALL);
        return env.createSession(optimized.toString(), opts);
    }
    opts.setOptimizationLevel(OptLevel.ORT_ENABLE_EXTENDED);
    opts.setOptimizedModelFilePath(optimized.toString());
    return env.createSession(modelPath.toString(), opts);
}
```

GPU sessions use CUDA provider options (memory limit, arena strategy) which are
orthogonal to graph optimization — both can be applied simultaneously.

SQLite-JDBC version: **3.51.2.0** (`gradle/libs.versions.toml`). Pre-extract via
`-Dorg.sqlite.lib.path=<dir> -Dorg.sqlite.lib.name=sqlitejdbc.dll`. The DLL must
match the JAR version (no runtime version check). sqlite-jdbc re-extracts a NEW DLL
with UUID filename on every JVM launch — pre-extraction eliminates this entirely.

**Confidence: MEDIUM.** The uniform session creation pattern is encouraging, but each
component has its own constructor and lifecycle. The shared helper needs to handle
both CPU-only and CPU+GPU cases. Integration testing with actual models required.

#### S1 Addendum Tradeoffs: Head JVM Flags

| Flag | Saving | Long-term cost | Verdict |
|------|--------|----------------|---------|
| `-XX:TieredStopAtLevel=1` | ~0.5-2s startup | 30%+ throughput penalty on hot paths; **incompatible with AOT cache** | **Skip** — use AOT instead |
| `-XX:+UseSerialGC` | ~20-80ms startup | No GC concurrency; acceptable for 128-256MB Head | **Use** for Head only |
| `-XX:-UsePerfData` | ~2-5ms | Loses `jstat` monitoring | **Use** — desktop app doesn't need jstat |
| `-Xms` = `-Xmx` | Avoids GC resizing | None for fixed-size process | **Use** for both Head and Worker |

**Key insight:** TieredStopAtLevel=1 is designed for short-lived processes (Lambda, CLI
tools). For a process running hours, C2 optimizations matter — and the flag conflicts
with AOT cache which is the better optimization. **Drop TieredStopAtLevel=1 from
consideration. Use AOT + SerialGC + UsePerfData instead.**

#### S8 Tradeoffs: Worker Timing Instrumentation

No tradeoffs. Pure observability improvement. Add `System.nanoTime()` milestones to
`KnowledgeServer.start()` matching HeadlessApp's pattern. Trivial implementation.

### Updated Startup Optimization Priority

Incorporating tradeoff research findings:

| Priority | Issue | Savings | Status | Key Tradeoff |
|----------|-------|---------|--------|--------------|
| **P0** | S7: Launch from installDist | ~9s | **DONE** | Direct launch + assemble pre-step; classpath collapsing for Windows |
| **P0** | S2: Parallel Worker spawn | ~3.4s | **DONE** | Late-bind pattern: volatile fields + setter methods on controllers/facade |
| **P1** | S1: Dev-mode AOT cache | 1-1.5s | **DONE** | Gradle tasks + WorkerSpawner detection + dev-runner JAVA_OPTS |
| **P1** | S3+S4: Sidecar fingerprint caching | ~0.5s | **DONE** | Sha256SidecarCache with mtime+size validation |
| **P1** | S5: ONNX pre-optimization | 1-5s | **DONE** | OnnxSessionCache in reranker module; all 5 CPU sessions updated |
| **P2** | S8: Worker timing instrumentation | 0s | **DONE** | Verified on live dev stack |
| **P2** | Head: SerialGC + UsePerfData | ~0.1s | **DONE** | Dev-runner JAVA_OPTS + Tauri lib.rs |
| ~~P2~~ | ~~S1 addendum: TieredStopAtLevel=1~~ | | **Dropped** | Conflicts with AOT cache; 30%+ throughput penalty |
| ~~P2~~ | ~~S6: Analyzer registry dedup~~ | | **Dropped** | Already singleton |

## Status Log

- 2026-03-11: Created. Identified `daemon=false` as primary bottleneck
  during analysis of tempdoc 274 agent's workflow issues.
- 2026-03-11: Investigation complete. Measured actual timings, traced git
  history, audited script invocations, analyzed memory budget.
- 2026-03-11: Daemon re-enabled (Phase 1). Steady-state builds improved
  from 68s to 31s (2.2x). Config-only tasks from 12.4s to 0.8s (15x).
- 2026-03-11: Deep-dive profiling revealed 5 additional issues.
- 2026-03-11: Comprehensive internet research completed. Upstream Spotless
  bugs still broken on 8.3.0/9.4.0 despite being marked CLOSED.
- 2026-03-11: All 5 issues resolved (Phase 2). Removed Spotless kotlinGradle
  block to unblock config cache. Converted checkNoDirectSysProp to typed task
  with proper I/O. Added @OutputFile to enforceDependencyVersions. Removed
  report tasks from check chain. Final result: **68s → 3.4s (20x faster)**.
- 2026-03-11: Phase 3 — early-phase build debt cleanup. Removed 3 `afterEvaluate`
  blocks (JaCoCo/PMD in app-launcher, ipc-common, system-tests), replaced
  `gradle.projectsEvaluated` with CC-compatible `subprojects` wiring, added
  missing `inputs.property` to CUDA download task. Deferred evidence-collection
  tasks (verify/manual-only paths).
- 2026-03-11: Phase 4 — downstream timeout recalibration. Measured dev-runner
  startup (38s to worker ready). Updated port emit timeout (30s→20s), ready
  timeout (30s→60s), MCP tool descriptions, backend-launcher help text, and
  agent-lessons.md. Fixed stale timing references across 5 files.
- 2026-03-11: Phase 5 — application startup analysis. Measured detailed Worker
  startup breakdown (1.3s init + 2.0s Windows process spawn = 3.4s blocking).
  Identified 7 startup issues: parallel Worker spawn (P0, ~3.4s savings),
  dev-mode AOT cache (P1, 1-1.5s), fingerprint sidecar caching (P1, 0.5s),
  Worker timing instrumentation (P2), analyzer dedup (P2), SQLite pre-extract (P2).
  Theoretical best-case: Head HTTP in ~0.5s after JVM launch.
- 2026-03-11: Deep investigation of deferred items. `collectLauncherEvidence`
  confirmed dead (output not git-tracked, no callers). `revapi` confirmed orphaned
  (no CI refs, baseline resolution broken, config-time overhead from version pins).
  `writeClasspath` confirmed dead (no callers). `redactionLint` dual-output serves
  no active purpose (Phase 13 evidence committed and done). Found new issue: AOT
  cache tasks share `outputs.dir(aotCacheDir)` — correctness hazard if run in
  parallel. Updated deferred items with verdicts and removal recommendations.
- 2026-03-11: Existing infrastructure audit. AOT fully implemented (prod only),
  gRPC health is custom proto (not standard grpc-health-v1), ONNX native path
  supported but SQLite has no pre-extraction, no Worker timing, no startup JVM flags.
  S6 (analyzer registry) confirmed already singleton — dropped from list.
- 2026-03-11: Tradeoff research (4 parallel internet research agents). Key findings:
  TieredStopAtLevel=1 conflicts with AOT cache (dropped). Sidecar fingerprint caching
  has essentially zero risk (mtime+size is industry standard). ONNX pre-optimized models
  not portable across EPs — use per-machine first-run optimization. SQLite-JDBC extracts
  NEW DLL every launch (no caching). Gradle bypass best done via staleness check +
  installDist. gRPC parallel startup low-risk for single-client loopback; recommend
  standard grpc-health-v1 for Watch() push semantics. Updated priority table.
- 2026-03-11: Implementation uncertainty investigation. S7: system property gap is
  minimal (1 property, already covered by env var) — confidence HIGH. S2: AppFacadeBootstrap
  constructor has hard dependency on connected KnowledgeServerBootstrap — requires refactoring
  (Option A: lazy Supplier, Option B: async split) — confidence MEDIUM. S1: JDK 25 confirmed,
  AOT fully applicable — confidence MEDIUM-HIGH. S5: all 5 ONNX components use identical
  default SessionOptions, uniform pattern enables shared helper — confidence MEDIUM.
  Worker has exactly 3 gRPC services to guard for early-bind pattern.
- 2026-03-11: Phase 3b — deferred item cleanup. Removed `collectLauncherEvidence`
  (~92 lines), `revapi` infrastructure (~68 lines), `writeClasspath` (~14 lines),
  `redactionLint` `doLast` copy. Split AOT cache output dirs to `aot-cache/head/`
  and `aot-cache/worker/` with path flattening in `bundleSidecarResources`. All P2
  items resolved. Build + unit tests pass.
- 2026-03-11: S4 — Worker startup timing instrumentation. Added phase-level
  `System.nanoTime()` instrumentation to `KnowledgeServer.start()` matching
  Head's pattern. 8 phases: telemetry, signalBus, jobQueue, lucene, embedding,
  aiModels, grpc, loop. Verified on live dev stack. First measurement:
  total=3.8s cold (lucene=1.5s, aiModels=1.6s, grpc=0.4s, jobQueue=0.1s).
  Warm restart: total=2.4s (lucene=0.7s, aiModels=1.2s).
- 2026-03-11: S7 — bypass Gradle for dev launch. Dev-runner now runs `assemble`
  (synchronous, ~3s warm) then launches directly from `installDist` start script.
  Added classpath collapsing to ui module's `CreateStartScripts` (prerequisite:
  generated `ui.bat` was 8243 bytes, over Windows 8191 char limit). Added
  `--skip-build` flag for instant launches from known-good dist. Port emission
  timeout tightened 20s→15s. MCP `skipBuild` parameter wired through schemas/server/cli.
  **Measured:** warm assemble=0.85s (config cache reused), Head total=5.1s,
  Worker total=3.3s. Start-to-port: ~6s (was ~13s). With `--skip-build`: ~5.1s.
  **Note:** Head `worker` phase still 4.4s blocking — S2 parallel spawn may not
  be effective yet (see investigation below).
- 2026-03-11: S3+S4 — sidecar fingerprint caching. `Sha256SidecarCache` added to
  `SpladeFingerprint` and `EmbeddingFingerprint` with mtime+size sidecar validation.
  Avoids re-hashing ONNX models on every boot (~0.5s saved).
- 2026-03-11: S2 — parallel Worker startup. HeadlessApp now spawns Worker in
  `CompletableFuture.supplyAsync()`, builds AppFacadeBootstrap with null KS,
  starts API server immediately (degraded mode), emits port, then late-binds
  Worker via `connectKnowledgeServer()` + `lateBindKnowledgeServer()`. Controllers
  (DebugState, Inference, StatusLifecycle) made volatile with setter methods.
- 2026-03-11: S1 — dev-mode AOT cache. Gradle tasks `generateDevHeadAotCache` and
  `generateDevWorkerAotCache` added to `ui/build.gradle.kts`. WorkerSpawner detects
  dev AOT cache at `build/aot-dev/worker/worker.aot`. Dev-runner passes Head AOT
  cache via `JAVA_OPTS`.
- 2026-03-11: S5 — ONNX pre-optimization. `OnnxSessionCache` utility in `reranker`
  module provides per-machine graph optimization caching (EXTENDED_OPT on first run,
  NO_OPT on subsequent). All 5 CPU ONNX sessions updated (CrossEncoderReranker,
  CitationScorer, SpladeEncoder, BertNerInference, OnnxEmbeddingEncoder). GPU sessions
  left unchanged (CUDA opts are orthogonal). Sidecar invalidates on mtime+size+ORT version.
- 2026-03-11: JVM flags addendum — Head process startup flags. Added `-XX:+UseSerialGC
  -XX:TieredStopAtLevel=1 -XX:-UsePerfData` to both dev-runner (JAVA_OPTS) and Tauri
  `lib.rs`. Note: TieredStopAtLevel=1 conflicts with AOT cache (when AOT is active, JVM
  ignores TieredStopAtLevel=1); kept for the non-AOT case.
- 2026-03-11: **S2 investigation — NOT on main.** Items S2, S1, S3+S4, S5, and JVM
  flags were implemented by another agent session in worktree
  `.claude/worktrees/275-startup-optimization/` but **never committed or merged**.
  The worktree has 19 modified files and 2 new files as uncommitted changes.
  Main branch `HeadlessApp.java:241-244` still blocks synchronously on Worker
  startup (`worker` phase = 4.4s). The `AppFacadeBootstrap.connectKnowledgeServer()`
  late-bind method, `LocalApiServer.lateBindKnowledgeServer()`, volatile controller
  fields, `Sha256SidecarCache`, and `OnnxSessionCache` all exist only in the
  worktree. Priority table updated to reflect actual status.
- 2026-03-11: Reviewed all worktree changes (4 parallel review agents: S5/ONNX,
  S3+S4/sidecar, S2/parallel startup, S1+JVM/dev-runner). Verified null-guards on
  all 3 controllers (DebugState, Inference, StatusLifecycle). Reconciled dev-runner
  S1/JVM flags with S7 on main (added JAVA_OPTS to existing direct-launch path
  instead of worktree's separate --no-direct-launch fallback). Kept main's richer
  S4 instrumentation (8 phases) over worktree's simpler version (7 phases).
  Committed all items to main: S2, S1, S3+S4, S5, JVM flags (18 modified + 2 new files).
- 2026-03-11: **Closed.** Worktree `275-startup-optimization` removed, branch deleted.
  All items implemented on main. Remaining observation item (Gradle daemon stability)
  is long-term monitoring, not implementation work.
- 2026-03-12: Investigated `configuration-cache.parallel=true` — blocked by Spotless
  #2391 (random NoSuchFileException), `subprojects`/`allprojects` thread-safety,
  and incubating status. Background profiling agents confirmed 19s cold breakdown.
  No further improvements justified. **Closed.**
- 2026-03-11: **Reopened.** Cold-cache `build -x test` still takes ~20s (was reported as
  47s but that was an anomalous measurement under load). Verified current timings:

  | Scenario | Time | Notes |
  |----------|------|-------|
  | Cold daemon + cold cache | **20s** | Full cold start |
  | Warm daemon + cold cache | **10s** | Daemon already running |
  | Warm daemon + warm cache | **4.2s** | Steady state |

  Profiled the 20s cold start breakdown:
  - Startup (daemon JVM spawn): 3.0s (15%)
  - Settings + build-logic: 2.2s (11%)
  - Configuring 32 projects: 3.7s (19%)
  - Task execution (UP-TO-DATE checks + config cache serialization): 12.1s (61%)

  The configuration phase (3.7s) is already fast. The bottleneck is task execution
  on cold cache — Gradle must check UP-TO-DATE status for all 294 actionable tasks
  and serialize the config cache entry. The config cache serialization overhead
  accounts for the 12s→4s difference between cold and warm cache runs.

  Further profiling revealed the check-task overhead:

  | Scenario | `assemble` | `build -x test` | Check overhead |
  |----------|-----------|-----------------|----------------|
  | Cold daemon + cold cache | 11.5s | 21s | **+9.5s (82%)** |
  | Warm daemon + warm cache | 2.6s | 4.4s | +1.8s |

  The check tasks (spotbugs ×3, PMD ×3, spotless ×2, per 31 modules = ~220 tasks)
  double cold-start time. 722 total tasks in `build -x test` vs ~300 for `assemble`.

  Daemon idle timeout is 10 minutes — easily exceeded between agent prompts,
  causing frequent cold daemon starts.

  ### Actionable improvements identified

  **I1. Increase daemon idle timeout** (trivial, immediate)
  Change `org.gradle.daemon.idletimeout` from 600000 (10 min) to 1800000 (30 min).
  Agent sessions routinely last 1-4 hours. 30-min timeout reduces cold daemon starts.
  Trade-off: 1GB idle memory for 30 min instead of 10 min.

  **I2. Create `quickBuild` lifecycle task** (P3, medium effort)
  For iterative agent development, agents don't need spotbugs/PMD/spotless on every
  build. Create a `quickBuild` task = `assemble` + `compileTestJava` (no static
  analysis). Cold start: 11.5s instead of 21s. Agents use `quickBuild` during
  iteration, `build -x test` for final verification.

  **I3. Reduce config cache serialization overhead** (research, unknown effort)
  The 12s task execution on cold cache (vs 4s warm) includes config cache
  serialization of 722 tasks. Gradle 9.4 may have improvements. Investigate
  whether `--no-build-cache` or selective task disabling helps.

  ### Implementation results (2026-03-11)

  **I1 DONE:** Daemon idle timeout increased to 30 min (was 10 min).

  **I2 DONE:** `quickBuild` task created — compiles all sources + test sources,
  skips static analysis (93 tasks vs 722). Final verified timings:

  | Task | Cold (daemon+cache) | Warm cache | Tasks |
  |------|-------------------|------------|-------|
  | `quickBuild` | 18s | **1.4s** | 93 |
  | `build -x test` | 21-25s | 4.4s | 722 |

  For iterative development, agents should use `quickBuild` (1.4s warm) and
  reserve `build -x test` for final verification before committing.

  **I3:** Not pursued — cold-start time is dominated by daemon spawn (3s) and
  build-logic compilation (2.2s), not config cache serialization.

  **I4: `configuration-cache.parallel=true`** — Investigated (2026-03-12).
  Parallel config cache storing was introduced in Gradle 8.11 (incubating).
  Expected 15-30% cold-cache improvement. **Not enabled — three blockers:**

  1. **Spotless incompatibility** ([diffplug/spotless#2391](https://github.com/diffplug/spotless/issues/2391))
     — random `NoSuchFileException` due to shared mutable state between Spotless
     tasks under parallel CC. Open, no fix available.
  2. **`subprojects`/`allprojects` thread-safety** — root `build.gradle.kts` uses
     both with 16 `eachDependency` resolution rules. These are fundamentally
     incompatible with parallel CC (risk of `ConcurrentModificationException`).
  3. **Still incubating** as of Gradle 9.4. Parallel loading is default, but
     parallel storing remains opt-in with minimal integrity checks, increasing
     cache corruption surface area ([gradle/gradle#23802](https://github.com/gradle/gradle/issues/23802)).

  Prerequisites to revisit: Spotless fixes #2391, migrate `allprojects`/`subprojects`
  to convention plugins, feature graduates from incubating.

  ### Additional profiling findings (background agents, 2026-03-12)

  Cold-cache 19s breakdown confirmed:
  - Startup (JVM bootstrap): 3.0s (16%)
  - Settings + build-logic compilation: 2.1s (11%)
  - Configuring 32 projects: 3.6s (19%) — root project 1.8s (50% of config)
  - Task execution (UP-TO-DATE checks + CC serialization): 11.6s (60%)

  Remaining minor opportunities (diminishing returns):
  - `:modules:ipc-common` 0.44s config (protobuf/gRPC plugin overhead)
  - Root `allprojects` resolution strategy: 16 rules × all configurations
  - PMD auxiliary classpath resolution: ~0.5-1s for unused test source sets

  **Conclusion:** 20s cold / 4s warm / 1.4s quickBuild is the practical floor
  with current Gradle 9.4 + Spotless constraints. No further improvements
  justified until upstream blockers resolve.
