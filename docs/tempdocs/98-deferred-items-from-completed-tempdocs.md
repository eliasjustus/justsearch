---
title: "Deferred Items from Completed Tempdocs"
status: done
created: 2026-01-30
---

# 98 — Deferred Items from Completed Tempdocs

Collection point for low-priority deferred items extracted from tempdocs before deletion. Items are grouped by area, not by source tempdoc. None are blocking bugs — implement opportunistically when touching the relevant component.

---

## Frontend — Search UX

*Source: tempdoc 87*

| # | Item | Component | Notes |
|---|------|-----------|-------|
| 1 | No keyboard navigation in history dropdown | `GlobalCommand.tsx` | Arrow key selection would improve keyboard-first UX. |
| 2 | No "Clear All" button for search history | `GlobalCommand.tsx` | `useSearchHistory` exposes `clearHistory()` but dropdown doesn't surface it. |
| 3 | Search history timestamps not displayed | `GlobalCommand.tsx` | Timestamps stored in localStorage but UI only shows query text. |
| 4 | No "search instead for original query" link in correction banner | `Stage.tsx` | Banner says "Showing results for X instead of Y" but Y isn't clickable. |
| 5 | `loadMore` doesn't clear/update `correctedQuery` | `useSearch.ts` | Minor — corrections typically return all results. |
| 6 | Empty results could suggest which filters to relax | `Stage.tsx` | Current "No results" message is adequate but could be smarter. |
| 7 | Facet truncation warning text is very small (10px) | `SearchFiltersBar.tsx` | Correctly warns but visibility is marginal. |

## Frontend — Brain / AI UX

*Source: tempdoc 89*

| # | Item | Component | Notes |
|---|------|-----------|-------|
| 8 | GPU details shown in raw bytes (e.g., "1099511627776") | `BrainView.tsx` | Advanced mode only; power users can interpret. |
| 9 | VRAM delta shown as raw bytes with unexplained delta symbol | `BrainView.tsx` | Advanced mode only. |
| 10 | Policy-blocking messages use internal field names | `BrainView.tsx` | Enterprise edge case (`gpuAccelerationEnabled=false`). |
| 11 | No progress feedback during repair | `BrainSimplePanel.tsx` | Short operation; Advanced mode has full diagnostics. |
| 12 | Poll failures show stale "importing" state indefinitely | `useBrainPolicyAndPacks` | Edge case: backend crash during import. User can close/reopen. |

## Frontend — General UX

*Source: tempdocs 89, 95*

| # | Item | Component | Notes |
|---|------|-----------|-------|
| 13 | Long paths truncated silently (no tooltip) | `ResultRow.tsx` | Standard CSS behavior; inspector shows full path. |
| 14 | File type icons missing for less common types (zip, json, yaml) | `ResultRow.tsx` | Generic File icon fallback is adequate. |
| 15 | No keyboard arrow navigation in context menu | `ContextMenu.tsx` | Mouse-driven; keyboard users use ActionPanel (Cmd+Shift+P). |
| 28 | Library folder card file type breakdown | `LibraryView.tsx` | Cards show path + count + time but no type distribution. Requires backend API changes to expose per-folder type data. |

## Frontend — Dead Code

*Source: tempdoc 89*

| # | Item | Component | Notes |
|---|------|-----------|-------|
| 16 | `useKeyboardNavigation` hook exported but never imported | `useKeyboardNavigation.ts` | Only `useZoneNavigation` and `useModifierKey` from the same file are used. Low-priority cleanup. |

## MCP Dev Tools

*Source: tempdocs 87, 88*

| # | Item | Component | Notes |
|---|------|-----------|-------|
| 17 | No query parameter support in `api_call` tool | `server.mjs` | Endpoints like `/api/preview?docId=...` require embedding params in path string. |
| 18 | `z.any()` for request body allows non-object values | `schemas.mjs` | Could restrict to `z.record()` or `z.object()`. |

## Frontend — Architecture

*Source: tempdoc 94*

| # | Item | Component | Notes |
|---|------|-----------|-------|
| 25 | WCAG contrast CI validation | CI pipeline | axe-core scans run locally via `--a11y-scan` but not in CI. CI infrastructure needs investigation. |
| 26 | useSystemStore decomposition into 4 Zustand slices | `useSystemStore.ts` | Split into `useConnectionStore`, `useInferenceStore`, `useInstallStore`, `useHealthStore`. Works as-is (778→525 lines already); migration risk outweighs benefit. |
| 27 | InspectionPane store-direct AI capability reads | `InspectionPane.tsx` | `useAiCapabilities()` requires hook chain args (`inference`, `apiBase`). Revisit when AI capabilities move to a Zustand store. |

## Evidence Bundle / Verification

*Source: tempdocs 88, 93*

| # | Item | Notes |
|---|------|-------|
| 19 | System tests not run with opt-in flag | `./gradlew.bat :modules:system-tests:test -PincludeSystemTests=true` — multi-process integration, chaos tests. Never run during agent sessions. |
| 20 | Playwright E2E not run | `npm run test:gate` in `modules/ui-web`. Requires dev stack + browser binaries (`npx playwright install`). |
| 21 | SSE streaming verification not possible | `/api/summarize` and `/api/ask/stream` require a loaded AI model. No MCP tool supports streaming responses. |
| 22 | No interactive state capture (hover/focus/active) | Requires per-element CDP state forcing + screenshot. Medium effort (~100 lines). Use `CSS.forcePseudoState` via CDP; clear with `page.mouse.move(0,0)` between states. |
| 23 | 2x DPI capture blocked by Claude API constraint | `deviceScaleFactor: 2` produces 2560x1440 which exceeds Claude API's 2000px multi-image limit. Reverted to 1x. |
| 24 | Glassmorphism/backdrop-filter not testable | Playwright renders without desktop compositor. Documented limitation — no fix possible. |

## Backend — Legacy Code & Tech Debt

*Source: tempdoc 55*

| # | Item | Notes |
|---|------|-------|
| 29 | `IndexingAiService` (was `AiTranslatorService`) deprecation migration blocked | 4 `forRemoval=true` summary methods. UI layer fully migrated to `OnlineAiService`. Removal blocked by gRPC `Summarize` RPC in `ai.proto` + 50+ test files. Need to replace/remove the RPC, update implementations (`LocalAiTranslatorService`, `GrpcAiTranslatorService`), and update tests. ~3-5 days focused work. |
| 30 | Consolidate EnvRegistry bypasses in `ai-worker` | 15+ direct `System.getenv()`/`System.getProperty()` calls bypass `EnvRegistry`. Add entries for `AI_WORKER_HOST/PORT/VERSION`, `EMBED_DIM`, `LLM_TEMPLATE_ROOT`, summary settings. |
| 31 | Deprecation telemetry | Track legacy API/field usage to inform removal. Need `DeprecationMetrics` class + instrumentation in `GrpcIngestService`, `LuceneIndexRuntime`. |
| 32 | Mark permanent compat bridges in code | `ChunkIds`, `MmfWorkerSignalHeaderV1`, `IndexGenerationManager`, `ParityDiagnostics`, `computeEffectiveOutcome()` — add `// PERMANENT COMPAT - DO NOT REMOVE` comments. |

## Backend — GPU / Inference

*Source: tempdoc 81*

| # | Item | Component | Notes |
|---|------|-----------|-------|
| 33 | Full PATH scanning for CUDA runtime | `InferenceLifecycleManager.java` | `warnIfCudaRuntimeMissing()` checks server dir and System32 but not full PATH directories. |
| 34 | Linux/macOS CUDA detection | `InferenceLifecycleManager.java`, `HeadlessApp.java` | Auto-selection and CUDA warning are Windows-only (`ggml-cuda.dll`, `cudart64_*.dll`). |
| 35 | Configurable DLL size threshold for static/dynamic CUDA detection | `HeadlessApp.java` | `hasStaticCuda()` uses hardcoded 400MB threshold; `hasDynamicCudaWithRuntime()` uses 200MB. |

## Backend — RAG / Retrieval Quality

*Source: tempdoc 37*

| # | Item | Component | Notes |
|---|------|-----------|-------|
| 36 | ORT CUDA runtime pack assembly | `tools/build-gpu-booster-pack.ps1` | Self-check and `/api/status` wiring done. Actual pack with ORT + cuDNN DLLs not yet built/validated. ~2.2 GiB installed. Legal posture for cuDNN redistribution undecided. |
| 37 | Token estimation heuristics hardening | `ChunkSplitter.estimateTokens()` | Dense text (URLs, code, JSON) stresses word-count heuristics. Add calibration harness + targeted unit tests. |
| 38 | Perf matrix expansion to 1M+ vectors + cross-machine | `scripts/bench/run-track-g-suite-win.ps1` | Tools built (diff, promote, baseline). 1M+ runs and cross-machine evidence still pending. |
| 39 | Quantization rollout policy | `RuntimeConfig`, `JustSearchCodec` | Flag + codec wiring implemented (default off). Need matrix evidence across machines/storage before enabling. |
| 40 | Separator lint guard | `SeparatorConstantDriftTest` | Drift tests exist but no lint preventing new `\n\n---\n\n` literals outside the canonical constant. Optional. |

## Benchmarking

*Source: tempdoc 60*

| # | Item | Component | Notes |
|---|------|-----------|-------|
| 41 | Lane T query-latency methodology for process-based tools | `scripts/bench/competitors/recoll.ps1` | Recoll runs via WSL + per-query CLI invocation; latency is not comparable to in-process tools. |
| 42 | Lane V end-to-end adapter (text→embed→vector-store) | `scripts/bench/competitors/` | Isolates vector store performance today. End-to-end pipeline adapter (chunking + embedding + ingestion) not yet built. |
| 43 | Lane F (filename indexing) adapter | `scripts/bench/competitors/` | Optional Everything adapter for filename-only indexing lane. |

## Backend — Model Evaluation

*Source: tempdoc 67*

| # | Item | Component | Notes |
|---|------|-----------|-------|
| 50 | Eval lane before model swaps (VLM, reranker, embedding) | `scripts/` | Repeatable eval harness for VDU accuracy, latency (TTFT, time-to-summary), VRAM headroom. Prerequisite for any default model change. |

## Build & Repository Hygiene

*Source: tempdoc 61*

| # | Item | Component | Notes |
|---|------|-----------|-------|
| 44 | Remove empty placeholder modules | `modules/infra-index`, `modules/events` | No source code; still compiled in build. Dead weight. |
| 45 | Consolidate benchmark script duplication | `scripts/bench/` | 4x `promote-*-baseline-win.ps1`, 4x `run-*-suite-win.ps1`, 3x `diff-*-suite.mjs` — nearly identical. Could be parameterized. |
| 46 | Add README files to script directories | `scripts/` | 13 of 16 script directories lack README files. |
| 47 | HdrHistogram for latency benchmarks | `FilteredKnnBench`, `RerankerDeadlineBench` | More robust latency measurement with coordinated omission correction. Low effort (~4h). |
| 48 | Cross-platform benchmark suite runners | `scripts/bench/*.ps1` | 52+ PowerShell scripts with no Unix equivalent. Would need Python or shell rewrites. |
| 49 | Unified benchmark result schema v2 | `modules/benchmarks/` | 5 different JSON output schemas; unifying requires updating all diff tools. |

---

## Staleness review (2026-05-18)

Open with no closure activity in >60 days. Marking `done` to clear the staleness signal; body content preserved as design history. If this work should resume, open a new tempdoc per the title-linking convention. Classification: ABANDONED. Stale for 68 days at audit time.

