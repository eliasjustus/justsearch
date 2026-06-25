---
title: "Merge hollow app-search module into app-services"
status: shipped
created: 2026-03-20
---

# 328 — Merge hollow app-search module into app-services

## Motivation

Tempdoc 325 (dead code cleanup) deleted the entire ANN hybrid search client stack from
`app-search`: 10 classes/interfaces, 6 test files, ~850 LOC. What remains is 4 classes
(~240 LOC), 1 test, and a `build.gradle.kts` that pulls in 7 dependencies to support them.
The module is hollow — it exists as a separate Gradle module but only serves `app-services`.

Merging `app-search` into `app-services` eliminates one module, one lockfile, and simplifies
the dependency graph without any functional change.

## Current state

| Class | Visibility | LOC | Used by |
|-------|-----------|-----|---------|
| `PagingCursorManager` | public | ~110 | `AppFacadeBootstrap` (app-services) |
| `TranslatorContextCodec` | public | ~65 | `DefaultAppFacade` (app-services) |
| `IndexSearcherProvider` | package-private | ~35 | `PagingCursorManager` only |
| `LuceneSearchClient` | package-private | ~32 | `IndexSearcherProvider` only |

**Only consumer:** `app-services` (sole module declaring `implementation(project(":modules:app-search"))`)

**Test:** `PagingCursorManagerTest` (1 file)

**Dependencies pulled by app-search:** configuration, core, app-api, telemetry,
lucene-core, adapters-lucene (runtime), jackson.databind (runtime), lucene-analysis-common
(runtime), lucene-analysis-icu (runtime)

## Analysis

`IndexSearcherProvider` is used by `PagingCursorManager` only for `currentTimeMillis()`
(a `Clock` wrapper). The `LuceneSearchClient` inside it is never opened for reading — just
constructed and closed. This suggests the Lucene scaffolding was built for the ANN search
clients (now deleted) and `PagingCursorManager` should use a simple `Clock` directly.

## Execution plan

### Phase 1: Simplify PagingCursorManager

- [ ] Replace `IndexSearcherProvider` parameter with `Clock` in `ensureCursor`/`createCursor`
- [ ] Delete `IndexSearcherProvider` and `LuceneSearchClient` (no longer needed)
- [ ] Update `PagingCursorManagerTest`
- [ ] This removes the `lucene-core` dependency from the module

### Phase 2: Move classes to app-services

- [ ] Move `PagingCursorManager` to `io.justsearch.app.services.search` (or similar package)
- [ ] Move `TranslatorContextCodec` to `io.justsearch.app.services.search`
- [ ] Move `PagingCursorManagerTest` to app-services test sources
- [ ] Update imports in `AppFacadeBootstrap` and `DefaultAppFacade`

### Phase 3: Delete app-search module

- [ ] Remove `modules/app-search/` directory
- [ ] Remove `include(":modules:app-search")` from `settings.gradle.kts`
- [ ] Remove `implementation(project(":modules:app-search"))` from app-services `build.gradle.kts`
- [ ] Merge any needed dependencies (telemetry, core, app-api are likely already in app-services)
- [ ] Refresh lockfiles: `./gradlew.bat --no-configuration-cache resolveAndLockAll --write-locks`

### Phase 4: Verify

- [ ] `./gradlew.bat build -x test` — compilation
- [ ] `./gradlew.bat test -x :modules:system-tests:test` — unit tests
- [ ] `./gradlew.bat --no-configuration-cache buildHealth --quiet` — DAGP clean

## Expected outcome

| Metric | Before | After |
|--------|--------|-------|
| Gradle modules | N | N-1 |
| app-search classes | 4 | 0 (module deleted) |
| app-services classes | existing | +2 (PagingCursorManager, TranslatorContextCodec) |
| lucene-core in app-search | yes | eliminated (Clock replaces IndexSearcherProvider) |
| Lockfiles | N | N-1 |

---

## Staleness review (2026-05-18)

Marked `shipped` after per-doc triage in the Shape-2 staleness audit.

Refactor actually shipped: "app-search classes 4 → 0 (module deleted)" per the body's before/after table. This is `shipped`, not `done` — code + canonical-doc changes landed on main.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

