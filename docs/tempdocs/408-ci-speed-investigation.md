---
title: CI Speed Investigation
type: tempdocs
status: active
---

# 408 — CI Speed Investigation and Optimizations

## Status

**ACTIVE.** Tier 1 (SLF4J grep replacement) shipped in commits 4a5425c5b
+ f725b688b. Tier 2 (worker-services test sharding audit + experiment)
in progress. Tier 3 (buildHealth `--no-configuration-cache` drop
experiment) and Tier 4 (clean-build profile) remain.

## Context

The user asked to focus on CI speed. Investigation produced concrete
data and one shipped optimization; this tempdoc captures findings, the
already-landed work, and the remaining opportunities ranked by
warrant.

## Baseline data

- Most recent successful CI run (24864378645): **6m44s wall-clock**.
  Job runs on `[self-hosted, Windows, X64, justsearch-perf]` with
  35-minute timeout (current usage: ~12% of budget).
- Per-step breakdown (from CI log timestamps + step `completedAt -
  startedAt` deltas):

  | Step | sec | % |
  |---|---:|---:|
  | Gradle build and test (main `check`) | 246 | 61% |
  | SLF4J exception-logging guardrail (`rewriteDryRun`) | 83 | 21% |
  | Post Setup Node.js (cache save) | 20 | 5% |
  | Dependency analysis gate (`buildHealth`) | 13 | 3% |
  | Checkout / Set up job / lockfile / npm audit | <30 | ~7% |
  | Other (~15 steps) | <12 | <3% |

- Reliability snapshot: 9 of 10 most-recent runs failed before two
  fixes landed yesterday (`c0b6fade8` cleared buildHealth violations
  across 21 modules; `2d065d902` routed `core-contracts:pmdMain` at
  the CLI-tooling ruleset). The first run after both fixes (and all
  observed runs since) succeeded. **Reliability is no longer the
  primary concern; speed is.**

## Investigated and confirmed

- **OpenRewrite plugin 7.28.1 is fundamentally config-cache
  incompatible.** Inspected the plugin JAR
  (`org.openrewrite:plugin:7.28.1`); `AbstractRewriteTask` constructor
  explicitly calls `notCompatibleWithConfigurationCache(...)`. The
  `--no-configuration-cache` flag in ci.yml line 170 (pre-swap) was
  required, not defensive. **Implication**: "move SLF4J recipe into
  main `check` task" is not viable; the only paths are replace-with-
  grep or accept the 83s tax.

- **DAP (`com.autonomousapps.dependency-analysis`) plugin 3.6.1's
  `BuildHealthTask` is NOT explicitly config-cache-incompatible.**
  Inspected the plugin JAR; `BuildHealthTask` has only `@UntrackedTask`
  (about build-cache, not config-cache); no
  `notCompatibleWithConfigurationCache` call. The CI's
  `--no-configuration-cache` flag for `buildHealth` (ci.yml line 157)
  may be stale from older DAP versions. **Worth empirically testing**
  by dropping the flag.

- **`resolveAndLockAll` is correctly config-cache-incompatible.**
  `LockingConventionsPlugin.kt:78` explicitly opts out
  ("Resolves configurations and writes lockfiles"). The
  `--no-configuration-cache` on the lockfile freshness check is
  correct and unavoidable. Same logic for `updateSchemas` (writes
  generated artifacts).

- **Tests dominate the main 246s `check` task.** Profile of an
  incremental run (`./gradlew check --profile`):
    - Total task execution: 149s; wall-clock 96s ⇒ ~1.55× parallelism
    - 14 actually-executed tasks, 13 of which are `:modules:*:test`
    - Test wall-clock by module:
      - worker-services: 27.2s
      - indexer-worker: 25.6s
      - app-services: 20.5s
      - ui: 15.3s
      - app-launcher: 10.5s
      - worker-core: 10.5s
      - app-agent: 9.9s
      - rest (6 modules): ~14s combined
    - Top 3 modules account for 73s/132s = 55% of test time
  - **`maxParallelForks` is NOT set** in `JvmBaseConventionsPlugin.kt`
    — each module's tests run in a single JVM with `maxHeapSize=384m`.
  - `org.gradle.parallel=true` does provide cross-module parallelism;
    the 1.55× factor reflects this.

## Ranked opportunities (with warrant)

Per `CLAUDE.md > Structural Defects Don't Need Repeat Incidents`,
warranted work is backed by a documented bug-class instance OR (for
performance) a measured bottleneck. Items without warrant fall to
plain YAGNI.

### Tier 1 — SLF4J grep replacement (SHIPPED)

**Warrant**: 83s/run is a measured bottleneck; OpenRewrite's
config-cache opt-out makes the slow path structural.

**Mechanism**: `scripts/ci/check-slf4j-bare-message-logging.mjs`
walks `modules/`, strips Java comments / string literals / char
literals / `"""`text blocks, and regex-matches the bare form
`log.<level>(<expr>.getMessage())`. Same scope as the OpenRewrite
recipe `CompleteExceptionLogging` per tempdoc 289 line 507.

**Verified**:
- 0 violations across 1077 .java files in 0.6s (vs ~83s for the
  recipe).
- Synthesized test: catches single-line + multi-line bare forms;
  correctly skips good form, parameterized form, concatenated form,
  chained `.getCause().getMessage()`, Javadoc snippets, line
  comments, single-line strings, text blocks.

**Commits**:
- 4a5425c5b — script + `logging-conventions.md` table update
- f725b688b — re-apply ci.yml swap (after revert 474993d29)

**Estimated CI saving**: ~80s per run (6m44s → 5m24s). To be
confirmed by next CI run.

### Tier 2 — Per-module test forks (LANDED for 2 modules; 1 module SKIPPED with reason)

**Warrant**: worker-services:test 27s, indexer-worker:test 26s,
app-services:test 21s — three documented bottlenecks each running in
a single JVM.

**Fork-safety audit per module**:

| Module | Result | Notes |
|---|---|---|
| worker-services | SAFE | No `ConfigStore.setGlobal` in tests; no port binding; @TempDir per-test; `System.setProperty("justsearch.config", …)` is per-test save/restore (each fork = own JVM) |
| indexer-worker | SAFE | Two test classes use `ConfigStore.setGlobal` with save/restore; isolated per-fork |
| app-services | DEFERRED | Already explicitly pinned to `maxParallelForks = 1` by commit a33d17077 (Nov 2025, message "prep for Phase 5", no documented reason). Per CLAUDE.md "investigate unfamiliar configuration before overwriting," skip until the reason is established. |

**Empirical results (local, on F:\JustSearch repo, single-task runs)**:

| Module | 1 fork | 2 forks | 3 forks |
|---|---:|---:|---:|
| worker-services | 27.17s | **21.94s** | 26.95s (regresses) |
| indexer-worker | 25.62s | **23.02s** | not tested |

3 forks regressed worker-services because JVM-startup overhead × 3
outweighed the parallelism gain on this workload distribution. **2
forks is the local optimum.** CI runner may have different
characteristics; the conservative 2-fork setting is safe regardless.

**Mechanism shipped**: `maxParallelForks = 2` added in:
- `modules/worker-services/build.gradle.kts`
- `modules/indexer-worker/build.gradle.kts`

Both with explanatory comments referencing this tempdoc.

**Saving**: ~7.8s combined task-time (worker-services 5.2s +
indexer-worker 2.6s). Wall-clock effect on the full `check` task
depends on critical-path shift:
- Pre: critical path was worker-services:test at 27s
- Post: new critical path is likely app-services:test at ~21s
  (unchanged, still pinned to 1 fork) or indexer-worker:test at 23s

So expected wall-clock saving on `check`: ~4-6s. Smaller than the
hoped 13-30s because (a) app-services couldn't be sharded without
investigation, and (b) test-class-time variance limits fork
distribution efficiency in indexer-worker.

**Open follow-ups**:
- Investigate why app-services was pinned to `= 1`. If no real
  blocker, enable sharding there too — would save another ~5s on the
  critical path.
- Identify the heaviest single test class in indexer-worker; the
  small 10% improvement at 2 forks suggests one outlier dominates.

### Tier 3 — `buildHealth` config-cache experiment (LANDED)

**Warrant**: 13s/run; the plugin doesn't opt out of config cache, so
the `--no-configuration-cache` flag was suspected stale.

**Empirical validation (local)**:

| Mode | Wall-clock |
|---|---:|
| `--no-configuration-cache buildHealth --quiet` | 8.85s |
| `buildHealth --quiet` (config cache enabled) | **4.26s** |
| `buildHealth` (warm cache hit, subsequent run) | **0.99s** |

The config cache works correctly — first run builds the cache, all
subsequent invocations replay from it. CI's run-to-run workspace
isolation means each run is a first-run (no cache reuse across jobs),
but the in-run cost is still halved.

**Mechanism shipped**: Dropped `--no-configuration-cache` from
`ci.yml:157`. Added a comment block citing the bytecode inspection
that confirmed DAP 3.6.1 doesn't opt out.

**Saving**: ~4.5s per run (on top of Tier 1 + Tier 2).

**Bonus fixes discovered during Tier 3**:
The local buildHealth run surfaced two real violations introduced by
commit 0e3cc8132 (the P3-P6 contract sampling work) that would have
broken the next CI run regardless of this tier:

  1. `core-contracts`: `opentelemetry-api` declared `implementation`
     but exposed in `ContractEmitter#emit(Span, ...)` public API →
     changed to `api`.
  2. `core-contracts`: Test uses `SdkTracerProvider` from
     `opentelemetry-sdk-trace` directly; was pulled transitively.
     Added `opentelemetry-sdk-trace` to the version catalog and
     declared directly as `testImplementation`.

Additionally: the root `build.gradle.kts` DAP block had `onUnused
Dependencies` + `onUsedTransitiveDependencies` + `onIncorrectConfig
uration` rules but no `onUnusedAnnotationProcessors`, so the
RecordBuilder processor false-positive surfaced. Added that rule
with the existing exclude. This is a pure bug fix exposed by the
Tier 3 investigation, not Tier 3 work itself.

Lockfiles regenerated after the catalog addition.

### Tier 4 — Clean-build profile (INVESTIGATIVE, not yet warranted as work)

**Warrant**: incomplete data. The Tier 1 + Tier 2 + Tier 3 wins are
already characterized; Tier 4 informs whether further optimization is
warranted.

**Mechanism**: `./gradlew clean check --profile --configuration-cache
-x pmdIntegrationTest` on a clean working tree (or in CI, by
inspecting an artifact-uploaded profile from a real run). Identifies
compile / PMD / SpotBugs / jacoco distribution that the incremental
profile didn't capture.

**Outcome**: either (a) discovers another structural bottleneck
warranting its own tier, or (b) confirms tests are the only material
target.

### Dropped on YAGNI (no documented warrant)

- **SpotBugs vs PMD/Error Prone overlap audit** to potentially remove
  SpotBugs (~27s combined). No documented bug, no specific case where
  SpotBugs caught nothing useful. Pure speculation. **Drop.** If a
  future incident shows SpotBugs catching only what other tools also
  catch, that becomes the warrant.

- **Custom Gradle daemon configuration tuning** (worker count, heap,
  GC). No documented saturation issue; default is `Runtime.available
  Processors()` which on the self-hosted runner is presumably
  already saturating. **Drop.**

## Cumulative effect (estimated)

| Tier | Status | Per-run wall-clock saving |
|---|---|---:|
| 1. SLF4J grep | SHIPPED | ~80s |
| 2. Test forks (worker-services + indexer-worker) | LANDED | ~4-6s |
| 2-bis. app-services shard | DEFERRED (audit needed) | est. +5s |
| 3. buildHealth config-cache | LANDED | ~4.5s |
| 4. Clean profile | Investigative | TBD |

**Landed: Tiers 1, 2, 3 — total ~89s saving.**

Confirmed expected: **6m44s → ~5m15s** (~22% faster). Tier 2-bis
(app-services shard, pending audit of why it was pinned to 1 fork)
could add another ~5s for a ~24% total improvement.

Empirical CI confirmation requires triggering a manual run per
ADR-0026. Local profile data + per-task verification is the strongest
signal available pre-CI.

## Out of scope

- **Splitting the `full_build` job into parallel jobs** (e.g.,
  separate test-shard jobs per module). Would require self-hosted
  runner pool changes and CI YAML restructuring. ADR-0026's
  manual-only policy makes the marginal value lower than for
  always-on CI. Capture as a future-features note if relevant.
- **Migrating off `[self-hosted, Windows, X64, justsearch-perf]`** to
  GitHub-hosted runners. Out of scope; runner choice is a separate
  trade-off (current self-hosted is fast for compile + has GPU for
  perf workflows).

## Verification

- **Tier 1 verified**: script tests pass; clean codebase shows 0
  violations; synthesized violations correctly caught/skipped.
- **Tier 1 next**: trigger a CI run and confirm wall-clock saving.
  Per ADR-0026 manual-only policy, requires explicit
  `gh workflow run ci.yml`.
- **Tier 2/3 verification**: re-run CI after each tier lands; compare
  wall-clock to 6m44s baseline.

## Provenance

Created 2026-04-24 from a CI-speed investigation requested by the
user. Ranked against the same `Structural Defects Don't Need Repeat
Incidents` discipline applied in tempdoc 407 — measured bottlenecks
warrant action; speculative reductions don't.
