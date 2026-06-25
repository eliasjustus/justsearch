---
title: "238: Long-Term Development Issues Audit"
---

# 238: Long-Term Development Issues Audit

**Status:** Complete — findings reviewed, remediation tracked in child tempdocs
**Goal:** Systematically identify and prioritize the biggest long-term development issues in JustSearch, then fix or mitigate them.

---

## Phase 1: Design the Approach

_Retained from design phase — see git history for full methodology writeup._

**Methodology:** Adapted three-pass investigation (structural + test quality + qualitative) using relative signals rather than absolute git churn (since the entire repo is rapidly evolving). Prioritization uses a Pain x Frequency matrix.

---

## Phase 2: Investigation

_All investigation steps completed._

- [x] Step 1: Structural dependency analysis
- [x] Step 2: Test infrastructure quality
- [x] Step 3: Build system health (skipped — existing infrastructure is strong, see §4c dependency governance)
- [x] Step 4: Dependency health
- [x] Step 5: Architecture invariant enforcement
- [x] Step 6: Agent telemetry friction analysis
- [x] Step 7: Code organization & missing abstractions

---

## Phase 3: Findings & Prioritization

### FIX NOW — High pain, actively touched

#### F1. Large files create multiplicative agent/developer cost

**Cross-ref:** `class-size-standard.md` (completed decompositions), `backend-tech-debt.md` BKD-006 (remaining targets).

**Evidence:** Telemetry shows these files consumed the most agent effort across 258 sessions:

| File | Actual LOC | Total Reads | Total Edits | Prior Decomposition? |
|------|-----------|-------------|-------------|---------------------|
| `LuceneIndexRuntime.java` | 1,950 | 387 | 117 | Yes — 4,500→1,950, 11 collaborators. BKD-006 exception: further extraction adds indirection without reducing complexity. |
| `AgentLoopService.java` | 1,959 | 373 | 121 | **No — not tracked anywhere.** |
| `RemoteKnowledgeClient.java` | 1,048 | 193 | — | Yes — 2,171→1,048, 12 collaborators. Just above ceiling. |
| `InferenceLifecycleManager.java` | 1,051 | 247 | 78 | Yes — 2,333→1,051, 4 collaborators. Just above ceiling (was 915 at decomposition time, grew since). |
| `BrowseView.tsx` | 913 | 296 | 135 | **No — not tracked. TSX file, outside Java class-size standard.** |
| `SummaryController.java` | 900 | 181 | 90 | Yes — 3,542→900, 6 collaborators. Under ceiling. |
| `GrpcSearchService.java` | 634 | 223 | 61 | Yes — 2,341→634, 4 collaborators. Well under ceiling. |

**Key correction from initial analysis:** Most files have already been through first-wave decomposition. The telemetry friction persists despite decomposition — these files are pain points because they're *touched constantly*, not just because they're large. Files like `GrpcSearchService` (634 LOC) and `SummaryController` (900 LOC) are well under the ceiling but still generate massive read/edit counts.

**What's genuinely actionable:**
- `AgentLoopService.java` (1,959 LOC) — the only large file not tracked in BKD-006 or class-size-standard. Needs decomposition.
- `InferenceLifecycleManager.java` and `RemoteKnowledgeClient.java` — grew back above 1,000 LOC since their decompositions. May need second-wave extraction.
- `BrowseView.tsx` (913 LOC) — high friction but outside the Java standard's scope. Separate evaluation needed.
- The remaining files (LuceneIndexRuntime, SummaryController, GrpcSearchService) are already tracked or under ceiling. Telemetry friction from these files is not solvable by further decomposition — see critical evaluation below.

**Impact:** See critical evaluation at end of this section.

---

#### F2. `RuntimeConfig` lives in the wrong module (highest blast-radius misplacement)

**Evidence:** `RuntimeConfig` — the system-wide configuration loader — lives in `modules/adapters-lucene/src/main/java/.../runtime/RuntimeConfig.java`. This forces **13 modules** to take a compile dependency on the Lucene adapter module even though they never use Lucene.

**Impact:** Changes to `adapters-lucene` trigger recompilation of 13 dependents. Module boundary semantics are muddied — "why does `telemetry` depend on the Lucene adapter?"

**Fix:** Extract `RuntimeConfig` and its `Runtime*ConfigFactory` classes to the `configuration` module (or a new `app-config-runtime` module). ~8 modules could then drop their `adapters-lucene` dependency entirely.

---

#### F3. Telemetry test sleep-for-flush pattern (13 flake-risk tests)

**Evidence:** 13 of 19 flake-risk `Thread.sleep()` calls are in the `telemetry` module, all following the same pattern: `LocalTelemetry` has a 1000ms flush interval and tests sleep 1500ms to wait for it. Total: 97 sleep calls across the test suite, of which 19 are high flake risk (unit test, >= 500ms).

Additional cluster: `GplJobCoordinatorTest.java` has 14 identical `Thread.sleep(50)` calls after async work submission.

**Impact:** Flaky tests erode trust in the test suite. The telemetry tests add 19.5s of wall-clock time just from sleeps.

**Fix:** Add a `LocalTelemetry.flushForTesting()` method or `CountDownLatch` callback on flush. This single change would fix 13 of 19 flake-risk sleeps. For `GplJobCoordinatorTest`, replace sleeps with `awaitTermination` or `CountDownLatch`.

---

#### F4. Head→Body boundary erosion via GPL types

**Evidence:** 5 files in `modules/ui/` directly import concrete GPL implementation types (`GplJobCoordinator`, `GplEvalSnapshot`, `LambdaMartReranker`) from `app-services.gpl`. The Head process is making reranking decisions using Body internals.

Additionally, a `gpl ↔ worker` package cycle exists within `app-services`: GPL imports `RemoteKnowledgeClient`, and `KnowledgeHttpApiAdapter` imports `LambdaMartReranker`.

**Impact:** Head-Body separation — the primary architectural invariant — is softening. Adding reranking features requires changes in both Head and Body code simultaneously, which the telemetry confirms causes the highest build-cycle rates.

**Fix:** Define contract interfaces (`RerankerService`, `GplStatusProvider`) in `app-api`. The `gpl ↔ worker` cycle can be broken by having `KnowledgeHttpApiAdapter` depend on a `Reranker` interface rather than the concrete `LambdaMartReranker`.

---

#### F5. 69 direct `System.getProperty()` calls bypass `EnvRegistry` — **COMPLETE** (tempdoc 243)

**Evidence:** Despite a well-designed `EnvRegistry` enum (80 entries, type-safe, dual-source resolution), 69 direct `System.getProperty()` calls exist in production code across 20 files. Many use `justsearch.*` keys that have corresponding `EnvRegistry` entries (e.g., `System.getProperty("justsearch.data.dir")` in 4 files vs. `EnvRegistry.DATA_DIR`).

**Impact:** These calls bypass the EnvRegistry resolution order (sysprop > env > default), bypass `SystemAccess` null-guarding, and scatter defaults across call sites. A config value can have different defaults depending on which code path reads it.

**Fix:** All 34 production literal calls replaced with `EnvRegistry` equivalents across 16 files. 8 new entries added to `EnvRegistry`. Telemetry module (4 calls) kept as-is due to `LayeringEnforcementTest` constraint. 4 legacy `"justsearch.data_dir"` underscore calls kept with `// SYS-PROP-LEGACY-COMPAT`. Gradle task `checkNoDirectJustsearchSysProp` added to `:check` — zero violations confirmed.

---

### DEFER — High pain but lower change frequency

#### F6. "Head never touches Lucene" invariant is only partially enforced

**Evidence:** ArchUnit's `IndexWriterOwnershipTest` blocks only `IndexWriter` constructor calls. But Lucene is on the UI compile classpath (via `adapters-lucene` dependency), and there is **no ArchUnit rule** preventing `io.justsearch.ui..` from importing `org.apache.lucene..` read-path classes (`IndexReader`, `IndexSearcher`, `Query`). The invariant holds today by convention.

**Fix:** Add an ArchUnit rule:
```java
noClasses().that().resideInAnyPackage("io.justsearch.ui..")
    .should().dependOnClassesThat().resideInAnyPackage("org.apache.lucene..");
```
Quick win — single test file addition. Blocked on F2 (RuntimeConfig extraction would clean up the dependency graph first).

---

#### F7. No legacy endpoint re-addition prevention at runtime

**Evidence:** `docsApiDriftCheck` prevents documentation from referencing `/api/search` or `/api/settings`, but there is no test that these routes return 404 at the Javalin level. A developer could re-add the routes and the build would pass.

**Fix:** Add a negative integration test asserting `POST /api/search` and `GET /api/settings` return 404. Quick win — single test.

---

#### F8. IPC error types live in the wrong module

**Cross-ref:** tempdoc 217 (unified error type system — residual item from that cleanup).

**Evidence:** `CircuitBreakerOpenException` and `KnowledgeServerNotConnectedException` live in `app-services.worker` but are caught by the Head process's `ApiErrorHandler`. These represent wire-level failure modes that belong in `ipc-common` or `app-api`.

**Fix:** Move to `ipc-common`. Part of the broader F4 boundary cleanup.

---

#### F9. Protoc/Protobuf version mismatch

**Cross-ref:** tempdoc 236 (dependency update audit — missed this cross-component mismatch).

**Evidence:** Version catalog declares `protoc = "3.25.3"` (compiler) vs `protobuf-java = "4.33.5"` (runtime) — 8 minor versions apart. Works due to backward compatibility, but is fragile.

**Fix:** Align `protoc` to the version corresponding to `protobuf-java 4.33.5`. Quick version bump.

---

#### F10. No staleness detection tool for Java dependencies

**Evidence:** The `gradle-versions-plugin` (ben-manes) is not configured. Despite strong governance (DAGP, locking, hygiene plugin), there's no automated way to surface outdated dependencies. Current deps are healthy, but drift will go unnoticed without periodic checking.

**Fix:** Add `gradle-versions-plugin` to the build. Run `dependencyUpdates` periodically.

---

### WATCH — Lower pain but frequently touched

#### F11. Duplicated `mapGrpcToHttp()` in controllers

**Cross-ref:** tempdoc 217 (unified error type system — missed during that cleanup).

**Evidence:** `KnowledgeSearchController` and `IndexingController` have byte-identical private copies of `mapGrpcToHttp(Status.Code)`. Minor but indicates a missing utility in `ApiErrorHandler`.

**Fix:** Move to `ApiErrorHandler` as a public static method.

---

#### F12. No global Javalin exception handler

**Evidence:** Every controller endpoint wraps its body in try-catch and manually calls `ApiErrorHandler`. If any controller forgets the catch, the response format falls back to Javalin's default (not the standardized shape).

**Fix:** Register `app.exception(Exception.class, handler)` that applies `ApiErrorHandler` as a global fallback. Doesn't remove per-controller error handling (which maps to specific `ApiErrorCode` values), just catches anything that slips through.

---

#### F13. `ConfigWiringTest` leaks temp dirs and is not parallel-safe

**Evidence:** 14 manual `createTempDirectory()` calls with no cleanup. Also mutates system properties (`System.setProperty`), which is not parallel-safe. Similar issue in `LuceneIndexRuntimeTest`.

**Fix:** Convert to `@TempDir` and extract system property manipulation to a JUnit extension.

---

### IGNORE — Low pain or stable

| Finding | Why Ignore |
|---------|-----------|
| `indexerworker` root ↔ `server` package cycle | Classic entry-point cycle, benign |
| `ui` root ↔ `ui.api` package cycle | Star topology with entry point at root, benign |
| `commons-codec` version catalog/force mismatch (1.17 vs 1.19) | Cosmetic — actual version is correct |
| Redundant frontend deps (`@juggle/resize-observer`, `autoprefixer`) | Tiny, not causing issues |
| Minor patch lag (Logback, Netty, SQLite JDBC) | Normal for a maintained project |

---

## Phase 4: Remediation

_Ordered by priority. Each item references the finding above._

### Priority 1 (tackle first — highest compound impact)

- [ ] **F1-a.** Decompose `AgentLoopService.java` (1,959 LOC) → **tempdoc 240**
- [x] **F2.** Extract `RuntimeConfig` from `adapters-lucene` to `configuration` module — Moved 14 source files (`RuntimeConfig`, `ConfigParsingUtils`, and 12 `Runtime*ConfigFactory` classes) from `modules/adapters-lucene/src/main/java/.../runtime/` to `modules/configuration/src/main/java/io/justsearch/configuration/runtime/`; package renamed throughout (`io.justsearch.adapters.lucene.runtime` → `io.justsearch.configuration.runtime`). Moved 2 test files to `configuration/src/test/` and `TestConfigHelper` to `configuration/src/testFixtures/` (made `public`); `ConfigWiringTest` stays in `adapters-lucene` (directly instantiates Lucene types — moving it would be circular). Replaced `Lucene99HnswVectorsFormat.DEFAULT_MAX_CONN` with literal `16`. Five modules swapped their `adapters-lucene` dependency for `configuration`: `app-config` (`api`), `app-observability` (`implementation`), `app-launcher` (`implementation`), `app-services` (dropped `api(adapters-lucene)`; explicit `implementation(configuration)` added), `app-ai` (`api`). Import updates across 36 files in 11 modules; `adapters-lucene` retains `api(configuration)` since its remaining classes expose `RuntimeConfig` in method signatures. Added `java-test-fixtures` plugin and promoted `jackson.dataformat.yaml` from `runtimeOnly` → `implementation` in `configuration/build.gradle.kts`.
- [x] **F3/F3-b.** Fix telemetry test sleep-for-flush + GplJobCoordinatorTest polling loops — Added `flush()` to `LocalTelemetry` and `TracingBootstrap` (5s join, exception-handled, health-state-recorded); replaced 11 metric sleeps across 5 files and 2 tracing sleeps across 2 files (13 total). `CollectorSmokeIT`'s 2 sleeps left intact — they wait for an external `otelcol` process. Added `awaitCompletion(long, TimeUnit)` to `GplJobCoordinator` backed by a `CountDownLatch` that fires on both `COMPLETED` and `FAILED` (the existing `onJobCompleted` callback fires only on `COMPLETED` and was not suitable). Replaced 13 deadline/polling loops in `GplJobCoordinatorTest`. Both `telemetry:test` and `app-services:test` verified BUILD SUCCESSFUL.

### Priority 2 (do when touching these areas)

- [x] **F4/F8.** Head→Body boundary cleanup (contract interfaces + IPC exception types) — Defined `GplStatusProvider`, `RerankerService`, `GplEvalData`, `GplJobStatus`, `LambdaMartTrainingStatus` in `app-api.gpl`; `GplJobCoordinator`, `LambdaMartReranker`, `GplEvalSnapshot` implement their respective interfaces; `KnowledgeHttpApiAdapter` now accepts `RerankerService` (breaking `worker→gpl` cycle); `CircuitBreakerOpenException` and `KnowledgeServerNotConnectedException` both moved to `ipc` top-level (`io.justsearch.ipc`), 7 import sites updated; all `ui` module consumers updated to interface types; ArchUnit rule `uiMustNotDependOnGplImplementations` added to `LayeringEnforcementTest`; `UiApiGuardrailsTest` narrowed to exclude exception types. Post-review API quality fixes applied: `RerankerService` `extends AutoCloseable` removed (lifecycle owned by `AppFacadeBootstrap` concrete field, not the consumer interface); `GplJobStatus.processedDocs` widened `int`→`long`; `LambdaMartTrainingStatus.status` promoted `String`→`Phase` enum (`PENDING`, `LOADED_FROM_DISK`, `TRAINING`, `SUCCEEDED`, `FAILED`); `GplEvalData.evaluatedAtRaw()` renamed `evaluatedAt()` returning `Instant`; `AppFacadeBootstrap.gplEvalSnapshotSupplier()` returns `() -> null` instead of `null` when unconfigured. All tests verified BUILD SUCCESSFUL.
- [x] **F5.** Consolidate System.getProperty calls to EnvRegistry — 8 new entries added to `EnvRegistry` (`ONNXRUNTIME_VARIANT_ID`, `SEARCH_PIPELINE`, `TRANSLATOR_REPO_ROOT`, `UI_AUTOMATION_ENABLED`, `UI_AUTOMATION_REQUIRE_TRANSLATOR`, `UI_AUTOMATION_FORCE_DIAGNOSTICS`, `UI_SETTINGS_MODE`, `SERVER_EXE_SOURCE`); 34 production call sites replaced across 16 files (`LlamaServerOps`, `InferenceLifecycleManager`, `WorkerSpawner`, `AppFacadeBootstrap`, `SmokeDriver`, `LauncherEnvironment`, `LauncherBootstrap`, `Launcher`, `TranslatorAssets`, `HeadlessApp`, `UiSettingsStore`, `RuntimeActivationService`, `BudgetProfiles`, `PlatformPaths`, `OnnxModelDiscovery`); now-unused `chooseFlag` and `chooseFirstNonBlank` private helpers removed. Special cases: `LauncherEnvironment` uses `System.getProperty(EnvRegistry.CONFIG_PATH.sysProp())` (save/restore safety — avoids env-var leakage on `close()`); `HeadlessApp.harmonizeDataDirProperties` uses `System.getProperty(EnvRegistry.DATA_DIR.sysProp())` (logback propagation safety — `.get()` would skip `setProperty` when only env var is set); 4 legacy `"justsearch.data_dir"` underscore calls tagged `// SYS-PROP-LEGACY-COMPAT`. Telemetry module (4 calls in `CrashReporter`, `LocalTelemetry`, `NdjsonMetricExporter`) exempt due to `LayeringEnforcementTest` constraint. Gradle task `checkNoDirectJustsearchSysProp` added to `build.gradle.kts`, scans `modules/**/src/main/**/*.java`, excludes telemetry and `SYS-PROP-LEGACY-COMPAT` lines, wired into `:check` — zero violations confirmed.
- [x] **F6/F7.** Strengthen architecture invariant enforcement — Added `onlyLuceneOwnersMayDependOnLuceneClasses` ArchUnit rule to `IndexWriterOwnershipTest` blocking any `org.apache.lucene` dependency from non-owner modules (3 exceptions: `adapters-lucene`, `indexer-worker`, `app-search`); exception list extracted to `LUCENE_OWNER_PACKAGES` constant with size-guard test; both rules annotated with `.because()` for distinct intents. Created `LegacyEndpointGuardTest` asserting `POST /api/search`, `GET /api/settings`, `POST /api/settings` are absent from the real Javalin route set via internal route inspection API (no HTTP server); positive sanity check caught `KnowledgeRoutes`/`AgentRoutes` null guards silently skipping registration — fixed with mocks.

### Priority 3 (backlog)

- [x] **F9.** Align protoc version with protobuf-java — `protoc` bumped from 3.25.3 to 4.33.5 in `libs.versions.toml`
- [x] **F10.** Add `gradle-versions-plugin` — ben-manes 0.53.0 added with `rejectVersionIf` for unstable versions
- [x] **F11.** Extract `mapGrpcToHttp()` to `ApiErrorHandler` — canonical method in `ApiErrorHandler`, controller delegates removed, all call sites inlined
- [x] **F12.** Register global Javalin exception handler — `app.exception(Exception.class, ...)` in `LocalApiServer`; `httpStatusFor(ApiErrorCode)` added to `ApiErrorHandler` so HTTP status agrees with response body
- [x] **F13.** Fix `ConfigWiringTest` temp dir leaks and system property mutation — `@TempDir` + `SystemPropertyExtension` in both test files

---

## Critical Evaluation: Should we decompose the "god files"?

### The telemetry data doesn't say what we thought it said

The initial F1 finding assumed these files were large *and* untreated. The reality:

| File | Current LOC | Already Decomposed? | Over Ceiling? |
|------|------------|---------------------|---------------|
| LuceneIndexRuntime | 1,950 | Yes (11 collaborators, exception in BKD-006) | Yes, but exempted |
| AgentLoopService | 1,959 | **No** | **Yes — untreated** |
| BrowseView.tsx | 913 | No (TSX, outside Java standard) | N/A |
| RemoteKnowledgeClient | 1,048 | Yes (12 collaborators) | Marginally (grew back) |
| InferenceLifecycleManager | 1,051 | Yes (4 collaborators) | Marginally (grew back) |
| SummaryController | 900 | Yes (6 collaborators) | No |
| GrpcSearchService | 634 | Yes (4 collaborators) | No |

**5 of 7 files have already been decomposed.** SummaryController (900 LOC) and GrpcSearchService (634 LOC) are well under the ceiling. The telemetry friction persists *despite* decomposition.

### Why decomposition didn't eliminate friction for these files

The telemetry measures agent reads/edits. A file generates high read counts when:
1. It's **large** and agents must re-read it to regain context — decomposition helps here
2. It's **central** and every feature in the module touches it — decomposition doesn't help, because the facade still must be read to understand the module
3. It was **actively being developed** during the telemetry period — decomposition is irrelevant; the file would be read heavily regardless

For files like `GrpcSearchService` (634 LOC, 223 reads), the reads aren't caused by size — the file is a reasonable length. The reads are caused by it being the central search orchestration point that every search-related feature must understand. No amount of splitting changes this.

For `LuceneIndexRuntime` (1,950 LOC, 387 reads), it's already been decomposed from 4,500 lines with 11 extracted collaborators, and BKD-006 explicitly exempts it from further extraction because "further extraction adds indirection without reducing complexity." The remaining 1,950 lines are the facade — the routing layer that delegates to collaborators. Splitting the facade further would just mean agents need to read *two files* instead of one.

### What's actually worth doing

| File | Action | Rationale |
|------|--------|-----------|
| `AgentLoopService.java` (1,959) | **Decompose** | Genuinely too large. Only file in the list with no prior decomposition and no BKD-006 tracking. This is the one real find. |
| `InferenceLifecycleManager` (1,051) | **Watch** | Grew back above ceiling since decomposition. If it keeps growing, second-wave extraction is warranted. Not urgent at 1,051. |
| `RemoteKnowledgeClient` (1,048) | **Watch** | Same as above — marginally over, already has 12 collaborators. |
| `BrowseView.tsx` (913) | **Evaluate separately** | Outside Java standard. TSX components have different decomposition patterns (component extraction, custom hooks). Worth a separate look but not a "class decomposition" task. |
| LuceneIndexRuntime (1,950) | **Leave alone** | Already exempted in BKD-006. 11 collaborators. Further splitting adds indirection. |
| SummaryController (900) | **Leave alone** | Under ceiling. Friction is from being central, not from being large. |
| GrpcSearchService (634) | **Leave alone** | Well under ceiling. Same as above. |

### Bottom line

**Only `AgentLoopService.java` warrants decomposition as a 238 remediation item.** The other files are either already decomposed, explicitly exempted, or under the ceiling. The telemetry signal was real (these files *are* pain points) but the diagnosis was wrong (the pain isn't caused by size for most of them — it's caused by centrality). Decomposition fixes the size problem but not the centrality problem.
