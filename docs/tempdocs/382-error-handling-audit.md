---
title: "382 — Error-Handling Audit & Faults Infrastructure"
---

# 382 — Error-Handling Audit & Faults Infrastructure

**Status:** Implemented
**Created:** 2026-04-08 (extracted from tempdoc 378 Stream I)
**Implemented:** 2026-04-08 (branch `worktree-382-error-handling`, 3 commits)
**Parent:** tempdoc 378 (workaround inventory)
**Resolves:** W18, W19, W20, W21 + 5 newly discovered sites (10 total) + PMD enforcement + documentation
**Related:** W22 (stream truncation) split out — streaming protocol problem, not error-handling

## Goal

Eliminate silent exception swallowing (`catch (Exception ignored) {}`) as a systemic pattern. Fix 10 high-concern sites, create infrastructure that makes correct error handling as convenient as silent swallowing, and enforce the policy via static analysis to prevent recurrence.

## Scope

**W22 is NOT in scope.** It is a streaming protocol completeness problem (missing `[DONE]` sentinel check in `OnlineModeOps`), not an error-handling problem. Documented in Site Details below for reference.

**10 high-concern sites:**

| Site | File | Line | Category | Why |
|------|------|------|----------|-----|
| W18 | `ConfigManagerBootstrap.java` | 54 | Fault isolation | Config listener failure invisible. No logger in file. |
| W19 | `InfraDiagnosticsService.java` | 94 | Fault isolation | Health component failure invisible. No logger in file. |
| W20 | `LauncherEnvironment.java` | 80 | Fault isolation | YAML parse error invisible. SLF4J available but unused. |
| W20b | `LauncherEnvironment.java` | 142 | Lifecycle cleanup | `close()` failure — shutdown path. |
| W21 | `IndexGenerationManager.java` | 825 | Batch operation | Per-file delete in walk — truly empty catch, hot path. |
| NEW-1 | `WorkerSpawner.java` | 499, 562 | Fault isolation | Process management returning null silently. |
| NEW-2 | `KnowledgeServerMigrationOps.java` | 89 | State degradation | Migration state silently degraded — no logging of cause. |
| NEW-3 | `LuceneLifecycleManager.java` | 797 | Lifecycle cleanup | Broad swallow during lifecycle operation. |
| NEW-4 | `InferenceLifecycleManager.java` | 735 | Lifecycle cleanup | Broad swallow during restart, with state rollback. |
| NEW-5 | `SummaryController.java` | 318 | Lifecycle cleanup | Scheduler shutdown exception dismissed. |

~90+ additional `catch (Exception ignored)` sites exist but are acceptable (parse fallbacks, explicit defensive wrappers like `KnowledgeServerSafeMetrics`).

---

## Design Considerations

**Note:** This design can consider complete rewrites of affected code, not just local fixes.

### 1. The convenience asymmetry (root cause of the pattern)

Writing `catch (Exception ignored) {}` is 1 line. Proper error handling is 5-10 lines. Every developer who wrote these sites made a rational choice — they needed fault isolation and the path of least resistance was silent swallowing. **If the correct pattern isn't equally convenient after we fix these 10 sites, site #11 will appear next week.**

The design must make the right thing as easy as the wrong thing. This means either:
- A utility that reduces correct handling to 1 line
- Static enforcement that makes the wrong thing fail the build
- Architectural changes that eliminate the need for manual exception handling at call sites
- All of the above

### 2. Exception response categories

The 10 sites are NOT one problem. They fall into 4 distinct categories that need different responses:

| Category | Sites | Correct response | Shape |
|----------|-------|-----------------|-------|
| **Fault isolation** | W18, W19, W20, NEW-1 | Log exception + continue (other items still execute) | `logAndContinue(LOG, context, () -> action)` or architectural change |
| **Lifecycle cleanup** | W20b, NEW-3, NEW-4, NEW-5 | Log at DEBUG + continue (shutdown, less critical) | `debugAndContinue(LOG, context, () -> action)` |
| **State degradation** | NEW-2 | Log exception + return degraded value | `logAndFallback(LOG, context, () -> action, fallback)` |
| **Batch operation** | W21 | Count failures + summarize once at end | Counter pattern, not per-item logging |

### 2b. Rewrite-level design options

For some sites, the right fix isn't better error handling — it's eliminating the need for manual exception handling by redesigning the contract.

**W19 — Replace raw supplier registration with typed health sources (DEFERRED):**

The current architecture: `InfraDiagnosticsService` holds 5 `AtomicReference<Supplier<?>>` fields. External components register suppliers that return raw values (`Long`, `Instant`, `Integer`, `boolean`). `currentPayload()` collects values with inconsistent `safeGet()` wrapping.

The ideal rewrite: replace suppliers with a `ComponentHealthSource` interface where each subsystem owns its exception handling internally. However, **the three real-time suppliers are never wired in production** — they default to `() -> null`. The rewrite would need to CREATE real health data sources (Lucene NRT lag reader, translator heartbeat tracker, ANN readiness counter), far beyond this tempdoc's scope.

**W20 — Make `loadYamlRoot()` return `Optional<JsonNode>` (IN SCOPE):**

Current: `loadYamlRoot()` throws `IOException` for both file-not-found and parse errors. All 8 callers wrap in `try/catch (Exception)` and silently continue.

Rewrite: `loadYamlRoot()` returns `Optional<JsonNode>`. File-not-found → empty (logged at DEBUG internally). Parse error → empty (logged at WARN internally). Callers become 1-liners: `.ifPresent(rcBuilder::contributeYaml)`. Eliminates 8 try/catch blocks across 6 files in 4 modules.

**W21 — Extract `FileOps.deleteRecursivelyBestEffort()` (IN SCOPE):**

Current: inline `Files.walk` + per-file catch in `IndexGenerationManager`. Rewrite: utility method with internal counting and summary logging. Reusable wherever recursive delete appears.

**W18 — Local fix is sufficient:**

64-line class. `Faults.logAndContinue` in the loop body + wrapping `fireImmediately`. No architectural change warranted.

### 3. Module logging availability

| Module | SLF4J status | Affected sites |
|--------|-------------|----------------|
| `app-config` | **Not a compile dependency** | W18 |
| `app-observability` | **Test-only dependency** | W19 |
| `app-launcher` | Compile dependency (available, unused) | W20, W20b |
| `worker-core` | Available, extensively used | W21 |
| `app-services` | Available | NEW-1 |
| `indexer-worker` | Available | NEW-2, NEW-3, NEW-4 |
| `ui` | Available | NEW-5 |

**D1 — RESOLVED:** Adding SLF4J to `app-config` and `app-observability` is safe. No circular dep risk. Both already receive SLF4J transitively at runtime; the change makes the compile dependency explicit. `System.Logger` eliminated (zero codebase usage, would create precedent).

### 4. Prevention layer

**D3 — PMD enforcement (RESOLVED):**

PMD `EmptyCatchBlock` is **already configured** in `config/pmd/ruleset.xml` with `allowExceptionNameRegex=^(ignored|expected)$`. This **actively enables** the `catch (Exception ignored) {}` pattern — the `ignored` name is a sanctioned escape hatch.

Fix: remove `ignored` from the regex (narrow to `^expected$`). This forces developers to either add a comment or use the `Faults` utility. Note: PMD only runs in CI or with `-PskipPmd=false`.

**D2 — Faults utility placement (RESOLVED):**

`configuration` is the only module directly depended on by all 7 affected modules. Already has SLF4J. The `Faults` utility lives at `io.justsearch.configuration.Faults` (root package, following `SystemPropertyUtils` precedent).

### 5. Health endpoint contract (W19 specific)

**D5 — RESOLVED. NO consumer breakage.**

Investigation found that `/api/health` and `/infra/health` are **separate endpoints**:
- `/api/health` → `StatusLifecycleHandler` — maps `LifecycleState` (READY/DEGRADED → 200, ERROR/STARTING → 503). This is what consumers check.
- `/infra/health` → `InfraHealthController` — **always returns HTTP 200** with the `InfraHealthPayload` JSON body. This is where `InfraDiagnosticsService` feeds.

W19 only affects `InfraDiagnosticsService` → `/infra/health`. Since `/infra/health` always returns 200 regardless of health status, wrapping suppliers in `safeGet()` changes the JSON body (supplier exception → null → CRITICAL status in body) but NOT the HTTP status code. No CRITICAL→503 mapping needed. No consumer breakage.

### 6. W19 type mismatch

`configValidSupplier` is a `BooleanSupplier`, not `Supplier<T>`. No existing adapter pattern in 24 files using `BooleanSupplier`. Lambda adapter is simplest: `safeGet(() -> configValidSupplier.get().getAsBoolean())` with `false` default.

### 7. Log level strategy

WARN every time for fault isolation. A persistently broken listener should be noisy — the spam forces investigation. DEBUG for lifecycle cleanup (shutdown errors are routine). If WARN spam becomes a problem in practice, add rate-limiting to `Faults` later.

### 8. Testing error paths

| Category | Test approach |
|----------|--------------|
| Fault isolation (W18, W19, W20, NEW-1) | Register throwing listener/supplier. Verify: exception logged, other items still execute, no crash propagates. |
| Lifecycle cleanup (W20b, NEW-3-5) | Verify compilation + existing tests pass. |
| State degradation (NEW-2) | Supply invalid data. Verify: degraded state returned, cause logged. |
| Batch operation (W21) | Verify compilation + existing tests. Counter pattern is simple enough to trust. |

W18 has an existing test (`ConfigManagerBootstrapTest:28-29`) that can be extended.

### 9. Decision status

| # | Decision | Status | Answer |
|---|----------|--------|--------|
| D1 | SLF4J in foundational modules | **RESOLVED** | Option A: add `implementation(libs.slf4j.api)` to `app-config` and `app-observability`. |
| D2 | `Faults` utility placement | **RESOLVED** | `modules/configuration` (`io.justsearch.configuration.Faults`). |
| D3 | PMD enforcement | **DEFERRED** | 176 `ignored` sites in production (157 outside our 10 targets). Scope too large — separate tempdoc. |
| D4 | 5 new sites in scope? | **OPEN** — user decision | All 5 are in modules with SLF4J. Included in plan assuming yes. |
| D5 | Health endpoint contract | **RESOLVED** | No breakage. `/infra/health` always returns 200. `/api/health` is a separate endpoint unaffected by W19. |

---

## Full Design

### Layer 0 — Infrastructure

#### 0a. `Faults` utility (`io.justsearch.configuration.Faults`)

Follows module conventions: `public final class`, private no-arg constructor, `public static` methods.

```java
public final class Faults {
    private Faults() {} // utility class

    /** Execute action with fault isolation. Logs and continues on exception. */
    public static void logAndContinue(Logger log, String context, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("{}: {}", context, e.getMessage(), e);
        }
    }

    /** Execute action with fault isolation at DEBUG level (for shutdown/cleanup paths). */
    public static void debugAndContinue(Logger log, String context, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.debug("{}: {}", context, e.getMessage(), e);
        }
    }

    /** Execute supplier with fallback. Logs and returns fallback on exception. */
    public static <T> T logAndFallback(Logger log, String context, Supplier<T> supplier, T fallback) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("{}: {}", context, e.getMessage(), e);
            return fallback;
        }
    }

}
```

**Design choices:**
- WARN for fault isolation, DEBUG for lifecycle cleanup. Full stack trace as third SLF4J arg.
- No `countFailures` — `FileOps` handles batch counting internally for W21. Can add later if needed.
- No rate-limiting in v1. No checked exception support.

**Test:** `FaultsTest` — verify logAndContinue doesn't propagate, logAndFallback returns fallback.

#### 0b. `FileOps` utility (`io.justsearch.configuration.FileOps`)

```java
public final class FileOps {
    private FileOps() {} // utility class

    public record DeleteResult(int deleted, int failed) {}

    public static DeleteResult deleteRecursivelyBestEffort(Path dir, Logger log) throws IOException {
        if (dir == null || !Files.exists(dir)) return new DeleteResult(0, 0);
        int[] counts = {0, 0};
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                    counts[0]++;
                } catch (IOException e) {
                    counts[1]++;
                }
            });
        }
        if (counts[1] > 0) {
            log.debug("Deleted {}/{} entries in {}; {} failed (likely locked)",
                counts[0], counts[0] + counts[1], dir.getFileName(), counts[1]);
        }
        return new DeleteResult(counts[0], counts[1]);
    }
}
```

#### 0c. SLF4J dependency additions

Add `implementation(libs.slf4j.api)` to `modules/app-config/build.gradle.kts` and `modules/app-observability/build.gradle.kts`.

#### 0d. PMD rule tightening — DEFERRED (scope too large for this tempdoc)

Verified: 176 `catch (... ignored)` sites in production code. Only 19 are in the 10 target files. Removing `ignored` from `allowExceptionNameRegex` would break 157 sites across 60 additional files. This is a codebase-wide migration, not a config edit. Deferred to a separate tempdoc.

#### 0e. Documentation (Phase 4)

Add to `agent-guide.md` §3:
> **Exception handling:** Every `catch` block must either (a) log the exception, (b) rethrow/wrap, or (c) include a `// SILENT: <reason>` comment explaining why silence is correct. Use `Faults.logAndContinue()` or `Faults.logAndFallback()` from `modules/configuration` for fault isolation.

---

### Layer 1 — Architectural Rewrites

#### 1a. W20 — `loadYamlRoot()` returns `Optional<JsonNode>`

**Scope:** Changes `JustSearchConfigurationLoader.loadYamlRoot()` and ALL 8 callers (6 production + 2 test).

```java
public static Optional<JsonNode> loadYamlRoot() {
    Path configFile = resolveConfigPath();
    if (!Files.exists(configFile)) {
        LOG.debug("No YAML config at {}, using defaults", configFile);
        return Optional.empty();
    }
    try {
        return Optional.of(new YAMLMapper().readTree(configFile.toFile()));
    } catch (IOException e) {
        LOG.warn("Failed to parse YAML config at {}: {}", configFile, e.getMessage());
        return Optional.empty();
    }
}
```

**Parse errors logged internally, not thrown:** All 8 callers swallow parse errors. None act differently on parse error vs file-not-found. The loader logs at WARN and returns empty. YAML config is always optional.

**Callers (all become 1-liners):**
| File | Module | Current pattern |
|------|--------|----------------|
| `LauncherEnvironment.java:79` | app-launcher | `catch (Exception ignored)` — W20 |
| `RuntimeContext.java:128` | adapters-lucene | `catch (Exception e)` + DEBUG |
| `SsotCommitMetadataSource.java:207` | adapters-lucene | `catch (Exception e)` + no log |
| `HeadlessApp.java:190` | ui | `catch (Exception e)` + DEBUG |
| `HeadlessApp.java:272` | ui | `catch (Exception e)` + null assign |
| `ConfigStoreRebuilder.java:40` | ui | `catch (Exception e)` + DEBUG |
| `IndexRuntimeFactoryTest.java:26` | adapters-lucene (test) | `catch (Exception ignored)` |
| `GrpcSearchServiceReasonCodeContractTest.java:493` | worker-services (test) | `catch (Exception ignored)` |

**Effect:** Eliminates 8 try/catch blocks across 6 files in 4 modules.

#### 1b. W19 — Add logging + consistent wrapping (NOT a full rewrite)

**Critical finding:** Three real-time suppliers (`nrtLag`, `translatorHandshake`, `annReady`) are **never wired in production**. They default to `() -> null`. `ComponentHealthSource` rewrite deferred — would need to create real data sources.

**Approach:**
1. Add SLF4J logger to the file
2. Add logging to `safeGet()`: `LOG.warn("Health supplier failed for {}: {}", context, e.getMessage())`
3. Wrap all 4 suppliers consistently — add `safeGet()` around `translatorHandshakeSupplier`; for `configValidSupplier` use `Faults.logAndFallback(LOG, "config validity", () -> configValidSupplier.get().getAsBoolean(), false)` to avoid null-unboxing NPE (aggregator takes `boolean` primitive, safeGet returns null → NPE)
4. No HTTP status change needed — `/infra/health` always returns 200

**Deferred:** `ComponentHealthSource` interface + real health data sources. Future health-observability project.

#### 1c. W21 — Replace inline delete with `FileOps`

Replace `IndexGenerationManager.deleteRecursivelyBestEffort()` with `FileOps.deleteRecursivelyBestEffort(dir, log)`.

---

### Layer 2 — Local Fixes (1-line changes using `Faults`)

| Site | Fix |
|------|-----|
| W18 (`ConfigManagerBootstrap:54`) | `Faults.logAndContinue(LOG, "config listener " + listener.getClass().getSimpleName(), () -> consumer.accept(listener))`. Add logger. Wrap `fireImmediately` path (line 38) in same protection. |
| W20b (`LauncherEnvironment:142`) | `Faults.debugAndContinue(LOG, "shutdown", () -> appFacadeBootstrap.close())` |
| NEW-1 (`WorkerSpawner:499,562`) | `return Faults.logAndFallback(LOG, "worker spawn probe", () -> ..., null)` |
| NEW-2 (`KnowledgeServerMigrationOps:89`) | `return Faults.logAndFallback(LOG, "migration state", () -> ..., MigrationState.FAILED)` |
| NEW-3 (`LuceneLifecycleManager:797`) | `Faults.debugAndContinue(LOG, "lifecycle cleanup", () -> ...)` |
| NEW-4 (`InferenceLifecycleManager:735`) | `Faults.debugAndContinue(LOG, "inference restart cleanup", () -> ...)` |
| NEW-5 (`SummaryController:318`) | `Faults.debugAndContinue(LOG, "scheduler shutdown", () -> ...)` |

---

## Implementation Order

```
Phase 1 — Infrastructure (no behavior change, enables everything else)
  ├── 1a. Create Faults + FaultsTest in modules/configuration
  ├── 1b. Create FileOps + FileOpsTest in modules/configuration
  ├── 1c. Add SLF4J to app-config and app-observability
  └── Verify: ./gradlew.bat :modules:configuration:test

Phase 2 — Architectural rewrites (parallel, each independent)
  ├── 2a. W20: loadYamlRoot() → Optional + migrate 8 callers
  │        Verify: ./gradlew.bat build -x test, then full test suite
  ├── 2b. W19: logging in safeGet() + wrap all 4 suppliers + CRITICAL→503
  │        Verify: ./gradlew.bat :modules:app-observability:test
  └── 2c. W21: replace deleteRecursivelyBestEffort with FileOps
           Verify: ./gradlew.bat :modules:worker-core:test

Phase 3 — Local fixes (all use Faults, can be one commit)
  ├── W18 + W20b + NEW-1 through NEW-5 (8 sites across 6 modules)
  └── Verify: ./gradlew.bat build -x test, then affected module tests

Phase 4 — Documentation
  ├── 4a. agent-guide.md update (exception handling policy)
  └── PMD rule tightening DEFERRED (157 sites across 60 files — separate tempdoc)
```

Phases 2a, 2b, 2c are independent and can run in parallel worktrees.
Phase 3 depends on Phase 1 (Faults utility must exist).

---

## Site Details

### W18 — Config listener errors silently swallowed
- **Location:** `ConfigManagerBootstrap.java:52-56` (64-line file)
- **Code context:** `forEachListener()` iterates `CopyOnWriteArrayList<ConfigSnapshotListener>`, calls `consumer.accept(listener)` in try/catch. The catch swallows `Exception ignored` with comment "Listener errors are swallowed to avoid blocking other listeners."
- **No logger exists in the file.** SLF4J not a compile dependency of `app-config` module (resolved: safe to add).
- **Additional finding:** `registerListener(listener, fireImmediately=true)` calls `listener.onConfigSnapshot(snapshot)` directly at line 38 — OUTSIDE `forEachListener()`. A throw propagates to the caller with no handling. No callers catch exceptions from `registerListener`.
- **Existing test:** `ConfigManagerBootstrapTest.java:28-29` registers a throwing listener and verifies the second listener still fires.

### W19 — Diagnostics `safeGet()` swallows all exceptions
- **Location:** `InfraDiagnosticsService.java:91-97`
- **Code context:** `safeGet(Supplier<T>)` catches `Exception`, returns `null`. Called for `nrtLagSupplier` and `annReadySupplier`. `translatorHandshakeSupplier` and `configValidSupplier` are NOT wrapped — inconsistency.
- **Semantics verified:** Aggregator treats null `translatorHandshake` as `CRITICAL`, null `annReady`/`nrtLag` as `DEGRADED`. Wrapping all suppliers is semantically correct.
- **Type mismatch:** `configValidSupplier` is `BooleanSupplier`. Lambda adapter needed.
- **Duplicate:** `EffectiveConfigController.java:640-646` has identical `safeGet()`.
- **Critical finding:** Three real-time suppliers never wired in production — default to `() -> null`.
- **SLF4J not a compile dependency** of `app-observability` (resolved: safe to add).

### W20 — LauncherEnvironment silently ignores YAML config load failure
- **Location:** `LauncherEnvironment.java:78-82` (YAML), `LauncherEnvironment.java:141-143` (close)
- **SLF4J IS available** (compile dependency of `app-launcher`). No logger despite dependency.
- **`loadYamlRoot()` throws `IOException`** for both file-not-found and parse errors.
- **8 callers total** — all silently swallow. None distinguish exception types.
- **Config source order:** EnvRegistry (unconditional) → YAML (best-effort). Failure degrades to env-var-only.

### W21 — IndexGenerationManager silently ignores IOException on cleanup
- **Location:** `IndexGenerationManager.java:825` inside `deleteRecursivelyBestEffort(Path dir)`
- **Truly empty catch** — no comment, no log. Only silent site in a file that extensively uses SLF4J.
- **Hot path:** `Files.walk` over potentially thousands of files. Per-file logging too noisy.
- **Natural retry:** GC runs periodically, retries failed deletes.

### W22 — Stream truncation on clean TCP close (SPLIT OUT)
- **Location:** `OnlineModeOps.java:170,181,308,324`
- **This is a streaming protocol correctness problem**, not an error-handling policy problem.
- **Internal error handling IS present:** `catch (Exception e) { LOG.error(...); onError.accept(e); }`. Errors reach the client via `SseWriter.writeEvent(ctx, "error", ...)`.
- **`@SuppressWarnings` is cosmetic** — about the outer `CompletableFuture`, not about exception swallowing inside the runnable.
- **`[DONE]` sentinel already parsed** (lines 245, 412) but only for skipping, not for tracking receipt.
- **Frontend handles `event: error`:** `streams.ts:496-509` parses structured error events.
- **Critical gap:** Clean TCP close (FIN) → `onComplete` called without `[DONE]` verification → truncated response appears successful.
- **To be addressed as a standalone fix or streaming reliability work stream.**

---

## Investigation Log

- 2026-04-08: Extracted from tempdoc 378 Stream I. All investigation findings, design considerations, research results, and full design carried over.
- 2026-04-08: Investigation history: codebase-wide scan (~100+ catch sites, 10 high-concern), deep read of all 5 primary files, critical analysis of initial plans, research for D1-D5 (SLF4J deps, module graph, PMD config, health consumers, BooleanSupplier patterns), full design with 4-phase implementation order.
- 2026-04-08: Pre-implementation research resolved remaining uncertainties:
  - D5 was a false alarm: `/api/health` and `/infra/health` are separate endpoints. W19 only affects `/infra/health` which always returns 200. No consumer breakage.
  - PMD scope was 3 sites, not 157: `allowCommentedBlocks=true` means only truly empty uncommented catches break. Found 3 (2 in IndexingController, 1 in benchmark). Fixed trivially.
  - Jackson 3.x (`tools.jackson`) removed checked exceptions from `readTree` — `catch (IOException)` changed to `catch (Exception)` in `loadYamlRoot`.
- 2026-04-08: Implementation complete. 3 commits on `worktree-382-error-handling`:
  1. `feat(382)`: Faults + FileOps utilities, SLF4J deps, loadYamlRoot Optional (8 callers), InfraDiagnosticsService consistent wrapping, IndexGenerationManager FileOps, 10 local fixes. 24 files, +311/-97 lines.
  2. `docs(378,382)`: Tempdocs carried into worktree.
  3. `fix(382)`: PMD rule tightened (`ignored` removed from regex), 3 remaining sites fixed, agent-guide.md updated, docs regenerated. 5 files, +11/-6 lines.
  Verification: compiles clean, all affected module unit tests pass. Pre-existing failures on main (SchemaMismatchStatusContractTest, LifecycleContractTest, EmbeddingOnnxModelDiscoveryTest) confirmed unrelated.
