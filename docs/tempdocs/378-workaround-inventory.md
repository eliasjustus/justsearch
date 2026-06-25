---
title: "378 — Workaround Inventory & Target-State Analysis"
---

# 378 — Workaround Inventory & Target-State Analysis

**Status:** In progress
**Created:** 2026-04-08
**Goal:** Identify all current workarounds in the codebase, describe ideal target states, and organize into parallelizable work streams.

## Method

1. Tempdoc survey — round 1 (done — 20 most recent tempdocs)
2. Code investigation (done — marker search, env-var gates, silent exception swallowing, suppress annotations)
3. Cross-reference and deduplication (done)
4. Tempdoc survey — round 2 (done — 10 additional tempdocs: 334, 336, 337, 340, 346, 348, 349, 352, 356, 359)
5. Code verification of all items (done)
6. Cross-cutting analysis (done — dependency chains, systemic causes, fragility, detection, compounding, blast radius)
7. Target-state analysis (done)
8. Work stream organization (done)

**Active workarounds:** 17 (of 29 cataloged; W10, W15, W16, W18, W19, W20, W21, W22, W27, W29 resolved)

---

## Work Streams

Streams A-F and I are fully parallel (zero file overlap). G→H are sequential (share `ort-common`). S4 waits for F and I.

```
A (frontend types) ─────────────────────┐
B (jseval) ──────────────────────────────┤
C (prod flag) ───────────────────────────┤
D (build hygiene) ───────────────────────┼──▶ all merge ──▶ then S4
E (cleanup + test gate) ─────────────────┤
F (eval-in-prod) ────────────────────────┤
G (model dist) ──▶ then H (ORT lifecycle)┤
I (error-handling audit) ────────────────┘
```

---

## Stream A — Frontend-Backend Type Contract [S3]

**RESOLVED** — [tempdoc 380](380-frontend-backend-type-contract.md) implemented and merged (2026-04-08).

**Resolved:** W10 (excerpt_regions casing), W15 (settings type duplication), W16 (@JsonUnwrapped SearchConfigView)
**Modules:** `modules/ui-web`, `modules/app-api`, `scripts/jseval`

---

## Stream B — jseval Backend Lifecycle [DX]

**Systemic gap:** jseval's `start_backend()` doesn't manage Gradle daemon lifecycle or check port availability.
**Resolves:** W12, W13 (2 workarounds)
**Modules:** `scripts/jseval/` only
**Parallel:** Yes — no overlap with any other stream.

### W12 — Stale Gradle daemon respawns backend
- **Source:** tempdoc 353; verified 2026-04-08
- **Current state:** `backend.py:67-72` launches `gradlew :modules:ui:runHeadlessEval` via `subprocess.Popen`. No `gradlew --stop` call precedes it. No `--no-daemon` flag.
- **Root cause:** Gradle daemons persist across invocations by design. jseval doesn't manage daemon lifecycle.
- **Target state:** `start_backend()` calls `gradlew --stop` before launching, or uses `--no-daemon` for eval runs.
- **Detection:** Developer-visible (confusing daemon behavior).

### W13 — No port conflict detection in jseval start_backend
- **Source:** tempdoc 353; verified 2026-04-08
- **Current state:** `backend.py:19` sets `_DEFAULT_PORT = 33221`. No socket probe before launching. If port is occupied, health check times out after 120s with generic `RuntimeError`.
- **Root cause:** No pre-flight check implemented.
- **Target state:** Pre-launch socket probe. If occupied: check if it's a JustSearch backend (`/api/health`), reuse if healthy, fail with clear error if not.
- **Detection:** Developer-visible (120s silent timeout).

---

## Stream C — Tauri Production Flag [Security]

**Resolves:** W5 (1 workaround)
**Modules:** `modules/shell/` only (one line in `lib.rs`)
**Parallel:** Yes — no overlap with any other stream.
**Note:** Independently correct regardless of W8 (signing). Don't block on cert acquisition.

### W5 — `prod=false` hardcoded in Tauri launcher
- **Source:** tempdoc 375; verified 2026-04-08
- **Current state:** `lib.rs:579` passes `-Djustsearch.prod=false`. Disables CORS origin restriction and session-token validation. CI installer test uses `prod=true`, but distributed binary does not.
- **Root cause:** Left as `false` for alpha browser-testing convenience.
- **Target state:** `-Djustsearch.prod=true` unconditionally. Developer overrides use a `--dev` CLI flag.
- **Detection:** Invisible to users (security exposure). Visible to security audit.
- **Blast radius:** All end users.

---

## Stream D — Build Artifact Hygiene [DX]

**Systemic gap:** No pre-build cleanup or post-build validation for installer artifacts.
**Resolves:** W6, W7 (2 workarounds)
**Modules:** `scripts/ci/*.ps1`, `modules/ui/build.gradle.kts` (build config only)
**Parallel:** Yes — no overlap with any other stream.

### W6 — NSIS stale temp files require manual cleanup
- **Source:** tempdoc 374 (G19); verified 2026-04-08
- **Current state:** `package-installer-win.ps1:131-133` picks newest `*-setup.exe` by `LastWriteTime`. No pre-build cleanup.
- **Target state:** Pre-build step deletes NSIS output directory. Post-build asserts exactly one EXE exists.

### W7 — Stale CUDA artifacts in Gradle stage dirs
- **Source:** tempdoc 374 (G20); verified 2026-04-08
- **Current state:** `stageLlamaCudaVariant` (Sync task) merges into `variantDir` without deleting first. `preserve { include("variants/**") }` prevents stale DLL cleanup.
- **Target state:** Each CUDA variant has independent clean-Sync into its own directory. Stale artifacts cannot persist.

---

## Stream E — Trivial Cleanup + Test Gate [Quality]

**Resolves:** W17, W25 (2 workarounds)
**Modules:** `modules/core` (SearchPort), `modules/system-tests` (AgentBatteryTest), `modules/ui` (delete 2 empty files)
**Parallel:** Yes — no overlap with any other stream.

### W17 — Vestigial naming and empty files
- **Source:** tempdoc 377, 367; verified 2026-04-08
- **Current state:** `SearchPort.java:12` parameter named `intent` (should be `query`). Two empty test files: `EffectiveConfigIntegrationTest.java`, `EffectiveConfigRuntimeIntegrationTest.java`.
- **Target state:** Rename parameter. Delete empty files.
- **Note:** MIME check and trace interceptors are NOT dead code (false positive from 377).

### W25 — AgentBatteryTest quality gate disabled
- **Source:** code investigation; verified 2026-04-08
- **Current state:** `AgentBatteryTest.java:429-431` — 85% success-rate assertion commented out. Line 579-583: path-validation criterion is a stub that always passes.
- **Target state:** Measure current agent performance. Set realistic threshold. Enable assertion. Replace path-validation stub with actual JSON parsing.

---

## Stream F — Eval-in-Production Consolidation [Hygiene]

**Systemic gap:** Eval-specific behavior leaks into production code paths via ad-hoc env vars.
**Resolves:** W23, W24 (2 workarounds)
**Modules:** `modules/app-services` (AppFacadeBootstrap), `modules/indexer-worker` (MmfWorkerSignalBus)
**Parallel:** Yes — no overlap with any other stream.
**Constraint:** S4 (inference scheduler) should wait for F to merge, since both touch `app-services`.

### W23 — `APP_API_FAKE_CAPABILITIES` escape hatch in production bootstrap
- **Source:** code investigation; verified 2026-04-08
- **Current state:** `AppFacadeBootstrap.java:479-480` reads `app.api.fake_capabilities` / `APP_API_FAKE_CAPABILITIES`. Not prefixed with `JUSTSEARCH_`. Available in production mode.
- **Target state:** Renamed to `JUSTSEARCH_EVAL_FAKE_CAPABILITIES`. Gated behind `justsearch.eval.mode=true`. WARN log if set in production mode.

### W24 — Eval breath-holding kill switch in production indexer
- **Source:** code investigation; verified 2026-04-08
- **Current state:** `MmfWorkerSignalBus.java:170-175` checks `justsearch.eval.disable_breath_holding`. Production throttling bypassed when set.
- **Target state:** Eval mode (`justsearch.eval.mode=true`) automatically disables breath-holding. The separate property is removed. Alternatively, eval-mode API calls are recognized as non-user activity.

---

## Stream G — Model Distribution Lifecycle [S2]

**Systemic gap:** No CI validation of registry URLs, no per-variant install selection, fragile filename-based workarounds.
**Resolves:** W1, W2, W3 (3 workarounds)
**Modules:** `modules/ui` (registry, install service), `modules/ort-common` (OnnxSessionCache), external asset upload
**Parallel:** Yes with A-F, I. Sequential before H (both touch `ort-common`).
**Dependency chain:** W2 → W3 (missing asset triggers download cascade). W1 is independent but same domain.

### W1 — FP16 embedding model served to CPU-only installs
- **Source:** tempdoc 376, 375 (G29); refined by 340, 349, 352
- **Current state:** Registry ships only FP16 embedding asset. `model_manifest.json` maps `cpu → model_int8.onnx` but INT8 variant not in registry. `OnnxSessionCache.java:74-80` (in `ort-common`) filename-sniffs `"fp16"` to select `BASIC_OPT` instead of `EXTENDED_OPT`.
- **Root cause:** Only FP16 artifact in registry. INT8 CPU variant never uploaded.
- **Target state:** Both `model_int8.onnx` (CPU) and `model_fp16.onnx` (GPU) in registry. Manifest selects by hardware. Filename-sniff removed — optimization level determined by execution provider.
- **Detection:** Visible on first use — 30+ min hang for CPU installs.
- **Fragility:** FRAGILE — filename-sniff breaks on model rename.

### W2 — FP16 reranker asset missing from GitHub Releases
- **Source:** tempdoc 375 (G30); verified 2026-04-08
- **Current state:** `model-registry.v1.json:277-289` defines `onnx-reranker-gte-fp16` pointing to a 404 URL.
- **Root cause:** Registry entry added speculatively; artifact never built/uploaded.
- **Target state:** FP16 reranker built, validated, uploaded. Dual-variant manifest in reranker directory.
- **Detection:** Visible — 404 during install.

### W3 — Download pipeline partial-failure handling
- **Source:** tempdoc 375; verified 2026-04-08
- **Current state:** Per-download 404s `continue`. But `validateAssetConfigured()` at line 244 aborts the entire pipeline on misconfigured entries.
- **Root cause:** Validation and download failure modes not consistently resilient.
- **Target state:** Separate validation and download phases. Per-asset isolation in both. Re-run retries only failed assets.

---

## Stream H — ORT Session Lifecycle [S7]

**Systemic gap:** No workload-aware session management. Sessions live for the worker's lifetime regardless of workload phase.
**Resolves:** W14, W28 (2 workarounds)
**Modules:** `modules/ort-common`, `modules/worker-core`
**Parallel:** After Stream G merges (both touch `ort-common`). Parallel with A-F, I.

### W14 — CitationScorer unconditionally CPU-only
- **Source:** tempdoc 358, 360; confirmed by 340, 349, 352, 359 (D8)
- **Current state:** `CitationScorer.java:60-62` creates sessions via `OnnxSessionCache.createCachedSession()` — no `OrtSessionFactory`, no GPU. CPU-only confirmed architectural by 340 scope exclusion and 359 D8 deferral. But 359's assessment predates the L-6 model upgrade.
- **Root cause:** Written for L-2 (~5M params). L-6 upgrade (~23M, 3x layers) made CPU slower. GPU path never added.
- **Target state:** `CitationScorer` uses `OrtSessionFactory` with dual-session pattern. `model_manifest.json` selects FP16 for GPU, FP32/INT8 for CPU.

### W28 — Worker 20+ GB post-backfill memory retention
- **Source:** tempdoc 348; verified 2026-04-08
- **Current state:** `OrtSessionManager` holds one GPU + one CPU session per encoder for worker lifetime. `OrtSessionFactory.java:84` sets `arena_extend_strategy=kSameAsRequested`. No session close/recreate after backfill. `reportCpuSessionFailure()` exists but is failure-triggered and CPU-only.
- **Root cause:** BFCArena retains peak allocation. No mechanism to distinguish backfill from steady-state.
- **Target state:** Post-backfill session close/recreate with smaller arena. Or separate "bulk" session pool released when backfill finishes.
- **Detection:** Invisible — memory only. Visible in Task Manager or on OOM.
- **Compounding:** Scales with model size and corpus size.

---

## Stream I — Error-Handling Audit [S1] → **tempdoc 382**

**RESOLVED** — tempdoc 382 implemented (2026-04-08), tempdoc 383 implemented (2026-04-09). Both merged to main.

**Resolved:** W18, W19, W20, W21 (tempdoc 382: Faults utility, loadYamlRoot Optional, 10 silent-catch fixes). W22 (tempdoc 383: streaming completeness contract — sentinel tracking, terminal guards, frontend detection).
**Modules:** `app-config`, `app-observability`, `app-launcher`, `worker-core`, `app-services`, `indexer-worker`, `ui`
**Parallel:** Yes with A-F. Minor file-level risk with H on `worker-core` (different files).

**Design:** 4-phase plan — Infrastructure (`Faults` utility + `FileOps` + SLF4J deps) → Architectural rewrites (W19, W20 `Optional<JsonNode>`, W21) → Local fixes (8 sites, 1-line `Faults` calls) → Enforcement (PMD rule + docs). See tempdoc 382 for full details.

**10 high-concern sites in scope** (5 primary + 5 discovered). W22 split out (streaming protocol problem):

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

## Deferred — Inference Request Scheduler [S4]

**Systemic gap:** Single llama-server slot with no prioritization. Each new LLM consumer requires another env-var gate.
**Resolves:** W9, W11 (2 workarounds)
**Modules:** `modules/app-services`, `modules/app-inference`, `modules/configuration`
**Parallel:** After Stream F and Stream I merge (F touches `app-services`, I touches `app-inference`).
**Compounding:** Gets worse with every new LLM feature.

### W9 — QU feature gated off (LLM single-slot contention)
- **Source:** tempdoc 366, 363; refined by 346
- **Current state:** `EnvRegistry.java:79` defines `QU_ENABLED`, default `false`. Deadline raised from 2000ms to 8000ms. Per 346: single-slot constraint now scoped to VDU mode; chat retains multi-slot. But no scheduling mechanism coordinates this.
- **Target state:** Priority-aware admission control. VDU-aware scheduling. QU enabled by default with graceful degradation.

### W11 — Filter normalization gated off
- **Source:** tempdoc 366; verified 2026-04-08
- **Current state:** `EnvRegistry.java:82` defines `FILTER_NORM_ENABLED`, default `false`. LLM tier-2 fallback disabled. Deterministic matcher always on. 8000ms deadline wraps async future.
- **Target state:** Same scheduler as W9. Filter norm self-regulates based on LLM capacity. Gate removed.

---

## Standalone Items (No Stream — Opportunistic)

### W4 — Tauri extended-length path (`\\?\`) strip
- **Location:** `modules/shell/src-tauri/src/lib.rs:227-237`
- **Status:** Functional workaround. Rust-side strip prevents Java `Files.isDirectory()` failure.
- **Target state:** Java-side `PathUtil.normalize()` at system boundary. Rust strip removed.
- **Fragility:** Moderate — depends on Tauri's path format.

### W8 — Unsigned builds blocked by Smart App Control
- **Status:** Operational blocker, not a code fix. Requires purchasing a code-signing certificate (~$300-500/yr).
- **Target state:** Cert acquired. CI release pipeline signs all builds. Dev builds labeled `-unsigned`.

### W26 — Deferred commits during backfill (every 5 batches)
- **Location:** `CombinedEnrichmentBackfillOps.java:395-405`, `IndexingLoop.java:559,577`
- **Status:** Largely resolved (upgraded from per-batch; NRT suspended during backfill). Remaining gap: fixed `5` constant is an unvalidated tuning assumption.
- **Target state:** Dynamic commit cadence based on observed native memory pressure.
- **Severity:** Low.

### ~~W27~~ RESOLVED — SPLADE batch=16 live with 4096 MB arena
### ~~W29~~ RESOLVED — Structured API path fully implemented

---

## Cross-Cutting Analysis

### Dependency Chains

**Chain 1 — Public distribution blocked by three independent gates:**
```
W8 (unsigned) ──┐
W5 (prod=false) ├──▶ No viable public distribution
W1 (CPU broken) ┘
```
All three must be resolved for a CPU user to successfully install and run.

**Chain 2 — Download failure cascade:**
```
W2 (reranker 404) ──▶ W3 (validation abort) ──▶ Entire install pipeline fails
```

**Chain 3 — LLM consumers share one fix:**
```
W9 (QU gated) ──┐
                 ├──▶ No inference scheduler
W11 (filter norm) ┘
```

**Chain 4 — ORT session lifecycle:**
```
W14 (CitationScorer CPU-only) ──┐
                                 ├──▶ No workload-aware session management
W28 (post-backfill retention) ──┘
```

**Chain 5 — Frontend type contract:**
```
W16 (@JsonUnwrapped) ──▶ W15 (type duplication) ──▶ W10 (casing mismatch)
```

### Systemic Root Causes

| Systemic gap | Workarounds | Stream |
|---|---|---|
| No model distribution lifecycle | W1, W2, W3 | G |
| No inference request scheduler | W9, W11 | S4 (deferred) |
| No frontend-backend type contract | W10, W15, W16 | A |
| No error-handling policy | W18, W19, W20, W21, W22 | I |
| No build artifact lifecycle | W6, W7 | D |
| Eval concerns leak into production | W23, W24 | F |
| No ORT session lifecycle management | W14, W28 | H |
| jseval backend lifecycle gaps | W12, W13 | B |

### Risk Profile

| Risk | Workarounds | Detail |
|------|-------------|--------|
| **Fragile — silent breakage** | W1 | Filename-sniff breaks on model rename → 30-min hang |
| **Fragile — env-dependent** | W4 | Tauri path format change breaks strip |
| **Invisible failures** | W18, W19, W20, W21, W10, W28 | No log, no metric, no UI signal |
| **Compounding** | W9/W11 (new LLM features), W21 (disk), W28 (memory), W18-W20 (pattern copies) | Gets worse over time |
| **All-user blast radius** | W5, W10, W28 | Every installation affected |
| **First-install blockers** | W1, W2, W3, W8 | Worst possible first impression |

---

## Investigation Log

- 2026-04-08: Tempdoc survey round 1 complete (20 most recent), 17 workarounds cataloged.
- 2026-04-08: Code investigation complete. 7 parallel searches across model distribution, Tauri/sandbox, QU/search, code quality, build/CI, dead code, and undocumented markers. 8 additional workarounds discovered (W18-W25). W17 scope corrected (MIME check and interceptors are live code, not dead). All target states written.
- 2026-04-08: Tempdoc survey round 2 complete (10 additional: 334, 336, 337, 340, 346, 348, 349, 352, 356, 359). Corrections: W1 CPU model is INT8 not FP32 (340); W14 canonical class is OrtSessionFactory (349/352); W9 single-slot scoped to VDU (346). 4 new workarounds: W26-W29.
- 2026-04-08: Code verification of W26-W29, W6, W7, W12, W13. W27 RESOLVED (batch=16 live). W29 RESOLVED (structured API). W26 downgraded (every-5-batch, NRT suspended). W28 confirmed. W6/W7/W12/W13 confirmed.
- 2026-04-08: Cross-cutting analysis complete. 5 dependency chains, 8 systemic root causes, risk profile assessed. Revised priority: S1 error-handling (5 fixes), S2 model distribution (3 fixes), S3 type contract (3 fixes), S4 inference scheduler (2 fixes, compounding).
- 2026-04-08: Restructured into 9 parallelizable work streams (A-I) plus S4 deferred and standalone items.
- 2026-04-08: Deep investigation of Stream A (frontend types). Read all primary files and audited the full frontend type contract. Key findings:
  - **W16 detail**: `@JsonUnwrapped` is not unique to `SearchConfigView` — there are 11 unwrapped fields total across `StatusResponse → WorkerOperationalView → 10 sub-records`, plus a second-order unwrap (`EnrichmentProgressView → ChunkCoverageView`). The `SearchConfigView` fields appear in 4 TypeScript locations: `SearchConfigFields` interface, `SystemStatus` interface (duplicated, not composed via `&`), `SystemStatusSchema` Zod schema, and `useSystemStore.ts` mapping. The undocumented `ChunkCoverageView` unwrap lands 6 `chunk*` fields flat at the top level but they are absent from both `SystemStatus` and the Zod schema.
  - **W10 detail**: The casing path is actually a double rename: `excerptRegions` (JSON wire) → `excerpt_regions` (SearchHit, snake_case) → `excerptRegions` (HUDResultItem, camelCase). The `as any` casts at `search.ts:214,215,221,262,279` are structurally unnecessary — `matchedFields`, `matchSpans`, `excerptRegions`, and `provenance` are all declared on the `KnowledgeSearchResponse.results[]` item type and could be accessed directly without casting. The `SearchHitSchema` Zod schema has no coverage for `excerpt_regions`, `collection`, all 4 metadata fields, or `provenance` (7 fields unvalidated).
  - **W15 detail**: Settings re-export is clean (comment acknowledges it). The `as AppSettings` cast at `useSettings.ts:111` is structurally safe — both paths resolve to the same definition. The real issue is the precedent: `InferenceMode` is independently defined 3 times (`systemTypes.ts:272`, `useInferenceMode.ts:8`, `useAiCapabilities.ts:12`) with identical string literals but no shared source.
  - **Additional findings beyond W10/W15/W16:**
    - `SearchHit` uses snake_case for 14 fields (matching Lucene field names) but `HUDResultItem` converts to camelCase. `meta_published_at` is mapped into `SearchHit` but lost during `HUDResultItem` conversion — the field exists nowhere in `HUDResultItem` or `resultMapper.ts`.
    - `SearchResponseSchema` is missing `correctionApplied`, `correctedQuery`, and `entityFacetVariants` (runtime-unvalidated).
    - `SystemStatus` is missing 20+ flat fields from `WorkerOperationalView` sub-records (`MigrationGenerationView` 10 fields, `VectorFormatView` 5 fields, `TelemetryMetricsView` 3 fields, `FailureTrackingView.searchesZeroResultCount`, `CoreIndexView.pendingVduCount`, `WorkerOperationalView.buildStamp`, `GpuDiagnosticsView` 3 fields).
    - `ExcerptRegion` is defined twice independently: `api/domains/search.ts:57-63` (interface) and `models/types.ts:10-16` (type alias). Both are structurally identical but not linked. Same for `MatchSpan`/`MatchSpanLike`.
    - 17 `as any` casts total in frontend; 5 in `search.ts` mapping are structurally unnecessary; others are for Tauri/Vite globals, debug hooks, and non-standard DOM APIs.
    - 11 `as unknown` casts; most are double-casts for runtime config injection or SSE payload typing.
    - Only 1 `@ts-ignore` in production code (`useDragDrop.ts:108` for `entry.fullPath`).
- 2026-04-08: Deep investigation of Stream I (error-handling audit). Read all 5 primary files in full. Key findings:
  - W18: 64-line file, no logger at all. `registerListener(fireImmediately=true)` has inconsistent error handling (no protection).
  - W19: `safeGet()` wraps 2 of 4 suppliers — inconsistency means other 2 can crash `currentPayload()`. Duplicate exists in `EffectiveConfigController`. No typed Result/Try in codebase.
  - W20: No logger. `resolveProfilePath` distinguishes file-not-found but YAML catch doesn't. Two catch sites (YAML + close).
  - W21: Truly empty catch in `deleteRecursivelyBestEffort`. File already uses SLF4J extensively. Caller logs at debug. Target state revised: debug-level log (not WARN), no retry mechanism needed (GC retries naturally).
  - W22: Architecture more nuanced than expected. Internal exception handling IS present (onError callback). @SuppressWarnings is about outer future, not inner. Real gap: clean TCP close (FIN) triggers `onComplete` without `[DONE]` sentinel check — truncated responses appear successful. Target state revised: sentinel check is the real fix.
  - Codebase-wide scan: ~100+ catch sites in production. Most acceptable. 5 new medium-severity sites identified (WorkerSpawner, KnowledgeServerMigrationOps, LuceneLifecycleManager, InferenceLifecycleManager, SummaryController).
- 2026-04-08: Critical analysis of implementation plans. Key blockers found:
  - SLF4J not a compile dependency in `app-config` or `app-observability` (test-only). Adding logging requires a dependency graph change.
  - W19: `configValidSupplier` is `BooleanSupplier` (not `Supplier<T>`), can't use `safeGet()` directly. Wrapping all 4 suppliers confirmed semantically correct by aggregator null-handling analysis.
  - W20: `loadYamlRoot()` throws `IOException` for both file-not-found and parse errors. Distinction must be by exception subclass.
  - W21: Hot path concern — per-file logging could be noisy with thousands of files. Counter-based summary may be better.
  - W22: `[DONE]` sentinel already parsed (lines 245, 412) but only for skipping. Frontend `streams.ts:496-509` confirmed to handle `event: error`. Real gap is clean TCP close calling onComplete without sentinel verification.
  Implementation plans removed pending design decisions.
- 2026-04-08: Research completed for D1, D2, D3, D5. Results:
  - D1 RESOLVED: Adding SLF4J to `app-config` and `app-observability` is safe. No circular dep risk. Both already receive SLF4J transitively. System.Logger eliminated (zero existing usage, would create precedent).
  - D2 RESOLVED: `configuration` is the only module directly depended on by all 7 affected modules. `Faults` utility lives there.
  - D3 RESOLVED: PMD `EmptyCatchBlock` already configured but `allowExceptionNameRegex=^(ignored|expected)$` actively enables the pattern. The existing rule is part of the problem — `ignored` is a sanctioned escape hatch. Fix: remove `ignored` from the regex.
  - D5 RESOLVED: Health endpoint change breaks 3 script consumers (`qu_v3_eval.py`, `agent-battery-core.mjs`, `run-agent-resume-replay-matrix.mjs`) and 1 test (`LifecycleContractTest`). All check HTTP status, not JSON body. Must be coordinated.
  - BooleanSupplier: No existing adapter pattern in 24 files using it. Lambda adapter is simplest.
  Remaining open: D4 (scope of 5 new sites — user decision).
- 2026-04-08: Full design completed. Key findings from research:
  - W19: Three real-time health suppliers (NRT lag, translator, ANN) are NEVER WIRED IN PRODUCTION — they default to `() -> null`. ComponentHealthSource rewrite deferred (would need to create real data sources, not just refactor). Approach: add logging + consistent wrapping + CRITICAL→503 mapping.
  - W20: `loadYamlRoot()` → `Optional<JsonNode>` eliminates ALL 8 try/catch blocks across 6 files in 4 modules (not just the W20 site). Parse errors logged internally at WARN, not thrown.
  - Faults API: 4 methods (logAndContinue, debugAndContinue, logAndFallback, countFailures). Lives in `io.justsearch.configuration.Faults`. FileOps utility for W21.
  - Implementation order: 4 phases. Infrastructure → architectural rewrites (parallel) → local fixes → enforcement (PMD + docs). PMD rule MUST go last.
- 2026-04-08: Full architecture design written. W22 split out (streaming protocol problem, not error-handling). Scope expanded to 10 sites. 4 exception response categories identified (fault isolation, lifecycle cleanup, state degradation, batch operation). 9 design considerations documented: convenience asymmetry, exception categories, module logging availability, prevention layer (PMD + utility + docs), health endpoint contract, type mismatch, log level strategy, testing approach. 5 open decisions identified (D1-D5).
- 2026-04-08: Stream A full architecture designed. 5 targeted investigations (jseval consumption, SearchHit callsites, updateSchemas mechanism, grouped object pattern, models/types.ts scope) resolved all 7 design considerations into 4 architecture decisions (AD1-AD4). 6-phase implementation plan with per-phase file manifests. Key decisions: hard cut for SearchConfigView (AD1), camelCase SearchHit rewrite (AD2), two-layer type system with canonical homes (AD3), Zod completeness + coverage enforcement (AD4). Prevention: 3-layer safety net (Zod coverage test, contract test with generated fixtures, single-definition convention).
- 2026-04-08: Stream A extracted to tempdoc 380 (380-frontend-backend-type-contract.md). 378 retains cross-reference.
- 2026-04-08: Stream I extracted to tempdoc 382 (382-error-handling-audit.md). Full design, 4-phase implementation plan, 10 site details, all research results (D1-D5) carried over. 378 retains summary + site table as cross-reference.
