---
title: "Frontend Dead Code Cleanup — Knip Triage"
status: done
created: 2026-03-19
completed: 2026-03-19
---

# 326 — Frontend Dead Code Cleanup — Knip Triage

## Motivation

[Knip](https://knip.dev) discovery run on `modules/ui-web` found significant dead code:
53 unused files, 46 unused exports, 80+ unused types, 31 duplicate exports, 1 unused dep,
3 unused devDeps. No frontend dead code tooling currently exists — ESLint `no-unused-vars`
is warn-only and doesn't detect unused exports at all.

## Outcome

Knip installed, configured, and wired into CI. All unused files, exports, dependencies,
and duplicate exports resolved. All pre-existing TypeScript errors fixed (37→0).
`noUnusedLocals` enabled. Zod 4 deprecations fixed.

| Metric | Before | After |
|--------|--------|-------|
| Unused files | 9 (after config) | 0 |
| Unused exports | 115 | 0 |
| Unused types | 133 | 0 |
| Duplicate exports | 31 | 6 (React.lazy views, ignored in config) |
| Unused dependencies | 1 | 0 |
| TypeScript errors | 37 | 0 |
| Zod deprecation warnings | 39 | 0 |
| `noUnusedLocals` | `false` | `true` |
| Dead code gate | None | `npm run knip` in CI |
| ESLint warnings | 104 | 41 (unused-vars resolved; remaining are hooks/FSD/fetch rules) |
| `@typescript-eslint/no-unused-vars` | `warn` | `error` |

### Phase 1: Install Knip + configure — DONE

- [x] Added `knip` as devDependency
- [x] Created `knip.config.ts` with entry patterns for CLI scripts, evidence pipeline, e2e
- [x] `ignoreDependencies`: `@lingui/cli`, `tailwindcss`, `playwright`
- [x] `ignore`: React.lazy view files (intentional duplicate default exports)
- [x] Added `"knip": "knip"` npm script (types included — all resolved)

### Phase 2: Delete dead files — DONE (14 files)

Dead source files deleted:
- [x] `src/App.css` — unused CRA leftover CSS
- [x] `src/app/index.ts` — empty barrel
- [x] `src/models/index.ts` — barrel with no consumers
- [x] `src/components/search/index.ts` — barrel with no consumers
- [x] `src/components/views/index.ts` — barrel with no consumers
- [x] `src/components/HUD/ResultList.tsx` — old HUD component
- [x] `src/components/HUD/SearchInput.tsx` — old HUD component
- [x] `src/components/HUD/Tooltip.jsx` — old HUD component
- [x] `src/components/zones/ContextInspector.tsx` — dead component (0 importers)
- [x] `src/components/ui/AdaptiveButton.tsx` — dead component after un-exporting

Dead scripts deleted:
- [x] `screenshot-scroll-split.mjs` — standalone manual test script
- [x] `scripts/test-suggest.mjs` — standalone diagnostic script
- [x] `scripts/spikes/cdp-pseudo-state.mjs` — spike/experiment

Tempdoc triage corrections:
- `scripts/lib/determinism-budget.cjs` — **kept** (imported by capture-evidence-bundle.mjs, dev-all.cjs)
- `scripts/lib/perf-report.mjs` — **kept** (imported by capture-evidence-bundle.mjs)
- `scripts/evidence/testdata/SearchComponent.tsx` — **kept** (test fixture for evidence pipeline)

### Phase 3: Remove dead exports — DONE

**Dead API functions removed (7):**
- `inference.ts`: `listModels`, `loadModel`, `unloadModel`, `setInferenceQualityMode`
- `settings.ts`: `getSettingsV2`
- `status.ts`: `getStatus`, `getInferenceStatus`

**Dead HTTP constants/helpers removed (7):**
- `http.ts`: `BASE_PORT`, `AUTO_DISCOVER_RANGE`, `DISCOVERY_TIMEOUT_MS`, `COMMON_PORTS`,
  `sleep` (inlined), `resolvePortFromTauri`, `autoDiscoverBackend`

**Constants/functions un-exported (kept as internal, 3):**
- `http.ts`: `DEFAULT_HOST`, `MAX_RETRIES`, `RETRY_DELAY` (used internally by `request`)

**Interfaces un-exported (2):**
- `http.ts`: `ApiError`, `RequestOptions` (internal to request helper)

**Dead Zod schemas un-exported (16):**
- `schemas.ts`: All 16 sub-schemas that are only composed into parent schemas.
  3 stream schemas (`StreamChunkEventSchema`, `StreamErrorEventSchema`, `StreamMetaEventSchema`)
  fully deleted (no dependents).

**Dead schema type aliases un-exported (16):**
- All `*Validated` types that were only used as schema inference outputs.

**Dead default exports removed (25 components/hooks/stores):**
- ActionPanel, ContextMenuLayer, DragOverlay, IndexingOverlayLayer, GlassSelect,
  CitationHoverCard, ModeIndicator, IndexingOverlay, KeyHint, SchemaCompatibilityCard,
  useSystemStore, useInferenceMode, useAiCapabilities, useApiConnection,
  useUiReadyHandshake, useBrainInstall, useBrainPolicyAndPacks, useBrainRuntimeVariants,
  BrainAlerts, EmbeddingCompatibilityCard, ChunkVectorStatusCard, BrainHeader,
  BrainNoConnection, PolicyHelperPanel, BrainSimplePanel
- 6 view files (LibraryView, BrowseView, BrainView, HealthView, HelpView, SettingsView)
  **kept** — React.lazy() requires default export

**Dead default export removed from ViewSkeleton.tsx** (object map, not needed)

**Dead utility exports un-exported/removed:**
- `logger.ts`: `getRecentLogs`, `clearLogs`, `setLogLevel`, `setDebugMode`, `apiLog`,
  `indexLog`, `uiLog`
- `persistence/contract.ts`: `KEY_REGISTRY`, `removeKey`, `listStoredKeys`, `clearAllKeys`,
  `migrateIfNeeded`, `writeString`, `StorageKey`
- `resultMapper.ts`: `highlightSnippet`, `highlightText`, `withSummarySeed` (un-exported),
  `stripHtmlAndTrim` (deleted)
- `motion.ts`: `REDUCED_TRANSITION` (un-exported)
- `i18n.ts`: `activateLocale` (un-exported)
- `agentProfiles.ts`: `PRIMARY_PROFILE`, `ORGANIZER_PROFILE`, `RESEARCHER_PROFILE` (un-exported)
- `useKeyboardNavigation.ts`: `useModifierKey` (un-exported)

**Barrel re-exports cleaned:**
- `hooks/index.ts`: removed 6 dead type re-exports, 4 dead value re-exports
- `stores/index.ts`: removed dead type re-exports (`ActiveZone`, `InputMode`, `ToolCallStatus`,
  `ConversationEntryType`, system store types), `useBrowseTreeStore`
- `useSystemStore.ts`: removed backward-compat type re-export line (7 types)
- `components/ui/index.ts`: removed `ConfirmDialog`, `useConfirmDialog`, 7 skeleton re-exports,
  `AdaptiveButton`, `SelectOption`
- `components/views/brain/index.ts`: removed 12 dead re-exports
- `components/views/agent/index.ts`: removed `ToolCallCard`
- `components/zones/index.ts`: removed `ContextInspector`

**Incidental fix:** `useStatus.ts` — added missing `SystemStatus` import (latent type error).

**E2E test harness exports (`e2e/ai-harness.ts`):** Not touched — intentionally retained for
upcoming accessibility E2E tests.

### Phase 4: Remove dead dependency — DONE

- [x] `tailwind-merge` removed from `dependencies` (never imported)

### Phase 5: Verify — DONE

- [x] TypeScript: 0 errors (37 pre-existing errors fixed in Phase 7)
- [x] Unit tests: 15 files, 184 tests, all passing
- [x] Knip: clean (0 unused files, 0 unused exports, 0 unused deps, 0 duplicate exports)
- [x] 0 unused type exports (all 47 resolved in Phase 8)

### Phase 6: Add Knip to CI — DONE

- [x] Added `npm run knip` step to both `fast_build` and `full_build` jobs in `ci.yml`
- [x] Step runs full Knip (including types — all 47 resolved)
- [x] Conditional on `ui_web` changes (same guard as other UI gates)

### Phase 7: Fix pre-existing TS errors + Zod deprecations + enable noUnusedLocals — DONE

- [x] Replaced 39 `.passthrough()` → `.loose()` in `schemas.ts` (Zod 4 deprecation)
- [x] Fixed 2 `z.record()` single-arg calls (Zod 4 API change)
- [x] Added `!` to 35 array index accesses in `search.test.ts` and `useAppAI.test.ts`
- [x] Deleted 45 unused locals across 17 files (imports, destructured vars, type aliases, dead code)
- [x] Enabled `noUnusedLocals: true` in `tsconfig.json`
- [x] Result: 0 TypeScript errors (down from 37), 0 Zod deprecation warnings

### Phase 8: Un-export all unused types — DONE

- [x] 47 unused exported types un-exported across 30+ files
- [x] `--exclude types` removed from Knip npm script — full type coverage active
- [x] Knip now reports 0 issues across all categories (files, exports, types, deps, duplicates)

## Issues discovered during cleanup

### Issue 1: Pre-existing TypeScript errors (37) — DONE

All TS2532 (`Object is possibly 'undefined'`) from `noUncheckedIndexedAccess`. Array index
access (`arr[N]`) returns `T | undefined`, and tests don't apply `!` to the index result.

| File | Lines | Fix |
|------|-------|-----|
| `search.test.ts` | 13 lines (15 errors, 2 lines double-fire on nested `[0]`) | Add `!` to array index: `regions![0]!.text` |
| `useAppAI.test.ts` | 20 lines (22 errors) | Same pattern: `enriched[0]!.excerpt` |

Plus 2 TS2554 errors in `schemas.ts` — `z.record()` API change (see Issue 2).

All tests pass at runtime (Vitest uses esbuild, skips tsc). Fixes are mechanical `!` additions.

- [x] Investigated — 33 unique error locations, all one pattern
- [x] Fixed: added `!` to 33 array index accesses in 2 test files
- [x] Fixed: 2 `z.record()` calls (see Issue 2)

### Issue 2: Zod 4 deprecation (39 calls + 2 errors) — DONE

**Zod version: 4.3.6.** The file uses `.passthrough()` (39 calls), which Zod 4 deprecated
in favor of `.loose()` or `z.looseObject()`. Both produce identical `ZodObject<Shape, $loose>`.

The file header comment documents the intent: *"These schemas are intentionally permissive
(.passthrough()) to avoid breaking on additive backend changes"*.

**TS2554 root cause:** Not `validateWithFallback` — it's `z.record(valueType)`. Zod 4 requires
`z.record(keyType, valueType)` (2 arguments). Two call sites pass only 1:

| Line | Current | Fix |
|------|---------|-----|
| 108 | `z.record(z.unknown())` | `z.record(z.string(), z.unknown())` |
| 238 | `z.record(z.number())` | `z.record(z.string(), z.number())` |

- [x] Investigated — 39 `.passthrough()` calls, 2 `z.record()` errors
- [x] Replaced 39 `.passthrough()` → `.loose()`
- [x] Fixed 2 `z.record()` calls (added `z.string()` first argument)

### Issue 3: 47 unused exported types + duplicate GroupBy — DONE

All 47 un-exported across 3 batches. `--exclude types` removed from Knip gate — full
coverage now active.

Additionally consolidated duplicate `GroupBy` type: removed local copy from `groupResults.ts`,
now imports from canonical `useLayoutStore.tsx` via the stores barrel.

### Issue 4: Unused locals — 45 violations — DONE

Investigation ran `tsc --noEmit --noUnusedLocals` — **45 errors** across **17 files**.

**Breakdown by fix pattern:**

| Pattern | Count | Fix |
|---------|-------|-----|
| Unused imports (lingui `t`/`Trans`, icons, types) | ~12 | Delete import line |
| Unused destructured variables | ~10 | Remove from destructure or prefix `_` |
| Unused `*Validated` type aliases in `schemas.ts` | 15 | Already un-exported; can delete |
| Intentional dead code (`RESEARCHER_PROFILE`, `uiLog`, etc.) | ~5 | Delete or prefix `_` |
| Logic gap: `streamCompleted` in `useSummary.ts` set but never read | 1 | Investigate before deleting |

Notable files: `App.tsx` (3: `cursorFileId`, `setCursorFileId`, `density`),
`ZoneGrid.tsx` (2: `DEFAULT_INSPECTOR_WIDTH`, `activeZone`),
`Stage.tsx` (2: `isInspectorOpen`, `isCompactMode`).

- [x] Investigated — 45 violations, all straightforward except `streamCompleted`
- [x] Fixed all 45 violations (deleted unused imports, destructured vars, type aliases, dead code)
- [x] Enabled `noUnusedLocals: true` in `tsconfig.json`
- [x] `streamCompleted` in `useSummary.ts` — deleted (set but never read, no logic gap)

### Issue 5: ESLint `@typescript-eslint/no-unused-vars` is warn (104 warnings) — DONE

Promoted `@typescript-eslint/no-unused-vars` from `warn` to `error` in `eslint.config.js`.

Pre-work:
- [x] Fixed 13 unused-vars violations across 7 files (5 `err` → bare `catch {}`, 3 unused
      destructured params removed, 2 prefixed `_`, 1 unused assignment removed,
      2 unused destructure args removed)
- [x] Auto-fixed 29 stale `eslint-disable` directives via `--fix`
- [x] Removed stale `/* eslint-env node */` from `vite.config.js`
- [x] Promoted rule to `error`

Result: ESLint warnings 104 → 15. Zero `no-unused-vars` violations.

### Issue 6: Further frontend issues discovered — DONE

Investigation and fixes for 6 additional issues found during cleanup:

**6a. React Compiler ESLint errors (3→0):**
- [x] `App.tsx`: forward-reference — moved `handleCommand` useCallback above `handleSubmit`,
      added `handleCommand` to deps
- [x] `Gate.test.tsx`: variable reassignment from component — changed to object ref pattern
      (`{ current: false }`)
- [x] `useAiCapabilities.ts`: memoization can't be preserved — destructured `inference`
      fields at top of function, changed `hasGpuSupport({ tier, gpu })` to use destructured values

**6b. React hooks real bugs (4 fixed):**
- [x] `SearchFiltersBar.tsx`: added `groupBy`, `setGroupBy` to `chips` useMemo deps
- [x] `BrainSimplePanel.tsx`: memoized `assets ?? []` to prevent new-ref-per-render
- [x] `useSearch.ts`: stabilized `retrySearch` via `performSearchRef` pattern (ref captures
      current `performSearch` without it being a dep)
- [x] `BrowseView.tsx`: added 5 missing Zustand setter deps across 5 hooks; added
      eslint-disable for intentional initial-focus-only useEffect

**6c. Intentional hook dep omissions (documented with eslint-disable):**
- [x] `useBrainTauriOps.ts`: 5 hooks — `isTauriRuntime` is static for session lifetime
- [x] `useBrainInstall.ts`: poll effect — `installStatus?.state` intentional field-level narrowing
- [x] `useBrainPolicyAndPacks.ts`: 2 poll effects — same field-level narrowing pattern
- [x] `HealthView.tsx`: `formatRelativeTime` closes over `nowMs` already in deps

**6d. Stable setter deps added (no functional change):**
- [x] `BrainPackImportSection.tsx`: 6 useCallback hooks — added state-setter props to deps

**6e. Deprecated z.ZodSchema → z.ZodType:**
- [x] `schemas.ts`: replaced `z.ZodSchema<T>` with `z.ZodType<T>` in `validateWithFallback`
      and `validate` function signatures

**6f. noUnusedParameters enabled:**
- [x] `tsconfig.json`: `"noUnusedParameters": true` — zero violations, free win

**6g. usePreviewState dead computation removed (~80 LOC):**
- [x] Removed 3 dead input params (`aiLoading`, `aiResponse`, `summaryText`) from interface
      and call site
- [x] Removed 6 dead output fields (`previewItem`, `matchedFields`, `matchedTerms`,
      `matchFieldLabel`, `vduStatus`, `vduProcessed`) + their useMemo/useCallback/state
- [x] Removed `results` param (only consumer was the deleted `previewItem` useMemo)

**Final state:**

| Gate | Before | After |
|------|--------|-------|
| TypeScript errors | 37 | 0 |
| ESLint errors | 3 | 0 |
| ESLint warnings | 104 | 15 |
| Knip issues | 115+ | 0 |
| Unit tests | 184 passing | 184 passing |
| `noUnusedLocals` | `false` | `true` |
| `noUnusedParameters` | `false` | `true` |
| `@typescript-eslint/no-unused-vars` | `warn` | `error` |

The 15 remaining ESLint warnings are: 8 `no-restricted-globals` (intentional raw `fetch` in
pre-auth bootstrap, keepalive beacons, SSE streaming, and polling) + 7
`react-refresh/only-export-components` (files mixing hooks/stores with components — HMR
degrades to full reload, not a bug).
