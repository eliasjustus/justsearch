---
title: "Tempdoc 290 — Upgrade Migration Safety"
---

# Tempdoc 290 — Upgrade Migration Safety

**Status:** Complete
**Created:** 2026-03-14
**Updated:** 2026-03-14
**Goal:** Ensure that configuration defaults and behavioral changes introduced by new features do not silently alter search behavior for upgrading users.

## Context

Recent feature work (CC fusion default, chunk-aware merge, branch fusion, SPLADE integration) introduced new config keys with programmatic defaults that change search behavior relative to prior releases. Users upgrading with no explicit config get different ranking without any warning, log, or migration guard.

This tempdoc covers the general problem of upgrade-safe defaults — not just the current batch, but a repeatable pattern for future feature additions.

## Investigation Findings (2026-03-14)

### How defaults are resolved

All fusion config lives in `ResolvedConfig.HybridSearch` (23 fields), built by `ResolvedConfigBuilder.buildHybridSearch()` (lines 1038–1067). Each field uses `resolveStringLower`/`resolveDouble`/`resolveBoolean` with a hardcoded Java-level default. The resolution chain is:

```
JVM arg (500) > Worker snapshot (450) > Env var (400) > CI profile (350) >
settings.json (300) > YAML (200) > auto-detect (150) > programmatic default (100)
```

**Critical: the Java-level defaults are NOT recorded in the resolution trace.** `resolveString(key, defaultValue)` returns `defaultValue` when `resolve(key).value() == null`, but this `defaultValue` is never stored as a `SourceCandidate`. This means:

- `logResolutions()` at line 1111–1118 logs unresolved keys at DEBUG (not INFO)
- `/api/debug/effective-config`'s `resolvedConfig` section shows `value=null` for these keys
- Operators see "unset" when the runtime is actually using `"cc"`

### The default change: RRF → CC

- **Commit `5f2bd361`** (2026-03-02): Introduced `fusionStrategy` as configurable. Default: `"rrf"`.
- **Commit `4cb756e4`** (2026-03-11): Changed default from `"rrf"` to `"cc"`.

No YAML file in the repo (`config/application.yaml`, bundled headless-config) sets these keys. A stock deployment resolves everything from programmatic defaults.

### `chunkAwareEnabled` path

Lives in `RuntimeConfig.SearchConfig`, resolved by `RuntimeSearchConfigFactory.resolveChunkAwareEnabled()`. YAML key: `search.chunk_aware.enabled`, default `true`. No env var override. Read by `SearchOrchestrator` at line 172.

### Existing startup logging

`KnowledgeServer.logConfiguration()` (lines 722–747) logs a box-drawing banner at INFO with paths and SSOT info. **No search/fusion config is logged.** `ConfigStore.globalOrNull()` is already used in this method (line 735) and `ResolvedConfig.HybridSearch` is accessible via `cs.get().hybridSearch()`.

Best insertion point: inside `logConfiguration()`, after SSOT/repo-root lines (line 744), before the closing box (line 746).

### Existing config-section logging pattern

`AppFacadeBootstrap.logAiServicesConfiguration()` (lines 311–336) uses a simpler `=== Section Name ===` style with `log.info("  key: {}", value)`.

## Gaps (all resolved)

| Gap | Description | Fix |
|-----|-------------|-----|
| **G1** | Fusion strategy silently changes from RRF to CC | A1 (startup banner) + A2 (`putDefault` makes it visible in logs and API) |
| **G2** | Chunk-aware merge activates by default with no log | A1 (startup banner) + A2 (`putDefault`) |
| **G3** | Programmatic defaults invisible to all API endpoints | A2 — root fix: `putDefault()` registers defaults at ordinal 100, making them appear in `logResolutions()` at INFO and in `/api/debug/effective-config` |
| **G4** | No convention for upgrade-safe defaults | A3 — documented in `development-philosophy.md` |

## Action Items

### A1: Add fusion config to Worker startup banner

- [x] Added fusion strategy, chunk-aware, and branch fusion to `KnowledgeServer.logConfiguration()` using the existing box-drawing format with `padRight()`. CC weights shown conditionally when `fusionStrategy=cc`.

### A2: Register programmatic defaults in the resolution trace

- [x] Added 8 `putDefault()` calls in `contributeYamlHybridSearch()` for fusion_strategy, cc_weight_sparse/dense/splade, branch_fusion_strategy, branch_cc_weight_whole/chunk, branch_chunk_min_weight_multiplier.
- [x] Added 1 `putDefault()` call in `contributeYamlSearch()` for `search.chunk_aware.enabled`.
- These register at ordinal 100 (lowest priority), making them visible in `logResolutions()` at INFO and in `/api/debug/effective-config`.

### A3: Document the convention

- [x] Added "Upgrade-safe defaults" section to `docs/reference/contributing/development-philosophy.md`.

## Verification (2026-03-14)

All three surfaces verified against a live Worker process started from the worktree build:

1. **Worker startup banner** (`worker.log`): Fusion strategy, CC weights, branch fusion, chunk-aware merge all visible in the box-drawing banner at INFO.
2. **Resolution trace** (`logResolutions()`): All 9 keys log at INFO with `(default, ordinal=100)` — previously logged at DEBUG as `<unset>`.
3. **`/api/debug/effective-config`**: All 9 keys return `"source": "default", "ordinal": 100` with correct values. Previously returned `"value": null, "source": "none"`.

## Files Changed

| File | Change |
|------|--------|
| `modules/configuration/.../ResolvedConfigBuilder.java` | 9 `putDefault()` calls in `contributeYamlHybridSearch()` and `contributeYamlSearch()` |
| `modules/indexer-worker/.../KnowledgeServer.java` | Search config section in startup banner (+2 imports) |
| `docs/reference/contributing/development-philosophy.md` | "Upgrade-safe defaults" convention section |
