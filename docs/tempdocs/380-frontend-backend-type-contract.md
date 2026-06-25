---
title: "380 — Frontend-Backend Type Contract"
---

# 380 — Frontend-Backend Type Contract

**Status:** Complete (merged to main 2026-04-08). Deferred items documented in tempdoc 384.
**Created:** 2026-04-08
**Parent:** tempdoc 378 (Stream A)
**Goal:** Eliminate casing mismatches, type duplication, and `as any` casts between backend Java records and frontend TypeScript types. Establish prevention mechanisms so these gaps cannot recur.

**Systemic gap:** No shared schema enforces casing or structure between backend Java records and frontend TypeScript types.
**Resolves:** W10, W15, W16 (3 workarounds from tempdoc 378)
**Modules:** `modules/ui-web`, `modules/app-api`, `scripts/jseval`
**Parallel:** Yes — no file overlap with any other 378 work stream.

---

## Workarounds

### W10 — excerpt_regions casing mismatch
- **Source:** tempdoc 366; verified 2026-04-08
- **Current state:** Backend emits `excerptRegions` (camelCase, `KnowledgeSearchResponse.java:65`). Frontend `SearchHit` type declares `excerpt_regions` (snake_case, `search.ts:29`). Bridge at `search.ts:279`: `excerpt_regions: mapExcerptRegions((res as any)?.excerptRegions)` — an `as any` cast reads the camelCase field and stores it under the snake_case key.
- **Root cause:** Frontend type written with snake_case convention before API contract was formalized. Bridge cast added to avoid a breaking type rename.
- **Target state:** Frontend uses camelCase matching the API wire format. No `as any` casts in the mapping layer. API types are validated against the backend schema at build time.
- **Detection:** Invisible — excerpt regions silently dropped in UI. No error, just missing highlighting.
- **Fragility:** Stable — ugly but functional.

### W15 — Settings type duplication
- **Source:** tempdoc 370, 368; verified 2026-04-08
- **Current state:** `AppSettings`/`UISettings` defined in `api/domains/settings.ts` (authoritative). `stores/systemTypes.ts:295-298` re-exports as type aliases, creating a second import path. `useSettings.ts:111` casts `storeSettings as AppSettings` without structural validation.
- **Root cause:** Store types and API domain types evolved independently.
- **Target state:** Each API type has one definition and one import path (`api/domains/`). No re-exports, no aliases. The `as AppSettings` cast is replaced by the store being properly typed.
- **Detection:** Developer-visible (confusing dual import paths).
- **Fragility:** Stable.

### W16 — @JsonUnwrapped on SearchConfigView
- **Source:** tempdoc 368; verified 2026-04-08
- **Current state:** `WorkerOperationalView.java:28` annotates `@JsonUnwrapped SearchConfigView searchConfig`, flattening all search config fields into the top-level status response. Frontend `systemTypes.ts:113-114, 255-265` duplicates all 10 fields into `SystemStatus` and a separate `SearchConfigFields` interface — fields appear in three places in one file.
- **Root cause:** Accidental `@JsonUnwrapped` (per tempdoc 368 RC2). Frontend adapted to flat shape.
- **Target state:** `SearchConfigView` serialized as nested `searchConfig` key. Frontend defines `SearchConfigView` once. Three duplicate field lists collapse to one.
- **Detection:** Developer-visible (maintenance burden).
- **Fragility:** Stable.
- **Dependency:** W16 is the root of Chain 5 → W15 → W10. Fixing W16 first (remove unwrap, nest the object) sets up W15 (single type definition) and W10 (casing normalization).

---

## Design Considerations

### D1 — Wire format stability (affects W16)

Removing `@JsonUnwrapped` from `SearchConfigView` changes the `/api/status` JSON shape — `chunkAwareEnabled` moves from top-level to `searchConfig.chunkAwareEnabled`. Known consumers:

- **React frontend** — consumes via `useSystemStore.ts`, validated through `SystemStatusSchema`
- **jseval** — snapshots search config fields from `/api/status` for eval provenance
- **MCP dev tools** — may read `/api/status` fields

Options:
- **(a) Dual-emit** — add a `searchConfig` grouped object alongside existing flat fields (matches the 330 §4 migration pattern already used for `embedding`, `schema`, `chunkCoverage`, `queueHealth`, `migration`). Deprecate flat fields. Remove in a future release.
- **(b) Hard cut** — remove `@JsonUnwrapped`, update all consumers in one commit. Viable if the consumer set is small and fully controlled.

Note: the 330 §4 dual-emit pattern is already the established approach for other `@JsonUnwrapped` fields in `WorkerOperationalView`. SearchConfigView is the only sub-record without a grouped counterpart.

### D2 — Scope of @JsonUnwrapped removal (affects W16)

W16 targets `SearchConfigView` only, but there are 11 unwrapped sub-records total in `WorkerOperationalView`. Considerations:

- **SearchConfigView** is the only sub-record where the frontend explicitly duplicates the field list (3 separate locations in `systemTypes.ts`). Other sub-records' flat fields either have grouped counterparts (330 §4) or are absent from frontend types entirely.
- **20+ flat fields** from other sub-records (`MigrationGenerationView` 10 fields, `VectorFormatView` 5 fields, `TelemetryMetricsView` 3 fields, `FailureTrackingView.searchesZeroResultCount`, `CoreIndexView.pendingVduCount`, `GpuDiagnosticsView` 3 fields) are emitted on the wire but absent from `SystemStatus` and the Zod schema — phantom fields with no frontend coverage.
- **Second-order unwrap**: `EnrichmentProgressView → ChunkCoverageView` lands 6 `chunk*` fields flat at the top level, absent from both `SystemStatus` and `SystemStatusSchema`.
- Every new field added to any sub-record silently appears at the top level with no namespace protection — potential for key collisions.

Recommendation: fix SearchConfigView now (concrete duplication). Don't remove other unwraps — they already have grouped counterparts the frontend can migrate to. Document the phantom field gap.

### D3 — Casing convention for SearchHit (affects W10)

Current state shows mixed casing with a structural reason:

```
Lucene stored fields:     snake_case (doc_id, file_kind, content_preview)
Java Hit.fields map:      snake_case keys (passthrough from Lucene)
Java Hit record fields:   camelCase (excerptRegions, matchSpans)
JSON wire:                mixed — fields map is snake_case, record fields are camelCase
SearchHit (TS):           snake_case (excerpt_regions, matched_fields)
HUDResultItem (TS):       camelCase (excerptRegions, matchedFields)
```

The snake_case in `SearchHit` matches the Lucene stored field names in `fields` map. But `excerptRegions`, `matchSpans`, `matchedFields`, and `provenance` are *computed* by the search pipeline, not Lucene fields — they're top-level record fields (camelCase on the wire). `SearchHit` forces them into snake_case, requiring the `as any` bridge.

Options:
- **(a) Keep snake_case in SearchHit, rename computed fields at backend:** Add `@JsonProperty("excerpt_regions")` on `Hit` record fields. Frontend becomes consistent snake_case. Rename to camelCase happens once in `resultMapper.ts → HUDResultItem`.
- **(b) SearchHit goes camelCase, rename Lucene keys at boundary:** `mapKnowledgeSearchResponse` renames `fields.doc_id → docId` etc. `SearchHit` becomes fully camelCase. Eliminates the double-rename for computed fields and all 5 `as any` casts.
- **(c) Split into wire type + domain type:** An internal raw type (matches JSON exactly, mixed casing) stays inside the mapper. Only a normalized domain type (all camelCase) is exported.

Trade-offs: (b) is cleanest but has the largest callsite blast radius — every component using `hit.doc_id`, `hit.content_preview`, `hit.file_kind` etc. needs renaming. (a) pushes the rename to Java, which is lower-risk but cements snake_case as the API convention. (c) is the most principled (separates wire format from domain model) but adds a type layer.

### D4 — Contract enforcement: Zod schemas + contract tests

The systemic gap is "no shared schema enforces casing or structure." Two mechanisms serve different purposes:

**Contract tests with fixtures** (partially exists):
- `app-api` has `updateSchemas` gradle task generating JSON schemas and cross-language fixtures
- `api/contract.test.ts` validates fixtures against Zod schemas
- Catches *structural drift* (field added in Java, not in TS) at test time
- Doesn't catch *casing* issues unless the fixture reflects the actual Jackson output

**Zod schema completeness** (the real gap):
- `SearchHitSchema` is missing 7 fields: `excerpt_regions`/`excerptRegions`, `collection`, 4 metadata fields (`meta_source`, `meta_author`, `meta_category`, `meta_published_at`), `provenance`
- `SearchResponseSchema` is missing 3 fields: `correctionApplied`, `correctedQuery`, `entityFacetVariants`
- `SystemStatusSchema` has no coverage for the 10 flat `SearchConfigView` fields (they're in the schema only if `searchConfig` becomes a grouped object)
- All schemas use `.loose()` — unknown fields pass through without error

**Zod completeness policy:** Declare every field in the Zod schema, keep `.loose()` mode. This gives validation coverage without breaking on additive backend changes. Combined with contract tests that feed real backend fixtures through the schemas, casing mismatches are caught at build time.

**Prevention rule:** Any new field added to a Java API record must also be added to the corresponding Zod schema. The `updateSchemas` task should fail if the generated JSON Schema has fields not covered by the Zod definition.

### D5 — Single source of truth for shared types

Three duplication patterns need resolving:

| Pattern | Current state | Canonical home |
|---------|--------------|----------------|
| `ExcerptRegion` | `api/domains/search.ts` (interface) AND `models/types.ts` (type alias) — structurally identical, not linked | `api/domains/search.ts` |
| `MatchSpan` / `MatchSpanLike` | Same dual definition | `api/domains/search.ts` |
| `InferenceMode` | Defined 3x independently (`systemTypes.ts:272`, `useInferenceMode.ts:8`, `useAiCapabilities.ts:12`) | `stores/systemTypes.ts` |
| `AppSettings` / `UISettings` / `LLMSettings` | Canonical in `api/domains/settings.ts`, re-exported from `systemTypes.ts:295-298` | `api/domains/settings.ts` |

Convention: `api/domains/` owns all types that cross the network boundary. `models/types.ts` imports from there (no independent redefinitions). `stores/systemTypes.ts` may re-export for convenience but must import from canonical source (the settings pattern is acceptable if commented). Independent redefinitions are forbidden — one definition, one import path.

### D6 — HUDResultItem completeness (affects W10)

`SearchHit → HUDResultItem` mapping in `resultMapper.ts` has a concrete bug: `meta_published_at` is mapped into `SearchHit` at `search.ts:260,283` but `HUDResultItem` has no `metaPublishedAt` field and `resultMapper.ts:212-233` never maps it. Data is extracted at the API layer then silently dropped at the presentation layer.

The two-layer split (SearchHit = API shape, HUDResultItem = UI display shape with derived fields like `type`, `size`, `approxFirstMatchLine`, `summarySeed`) remains justified — but the mapper must be audited for completeness. If `SearchHit` moves to camelCase (D3), the casing difference disappears and the mapper becomes a pure enrichment step (add derived fields) rather than a rename+enrich step.

### D7 — Redundant `as any` casts in search mapper

5 `as any` casts in `search.ts:214,215,221,262,279` are structurally unnecessary — `matchedFields`, `matchSpans`, `excerptRegions`, and `provenance` are all declared on the `KnowledgeSearchResponse.results[]` element type. The casts exist because the mapper was written before the `KnowledgeSearchResponse` interface was fully typed, and never updated. Removing them is safe and requires no behavioral change — pure type cleanup.

---

## Resolved Architecture

Investigation findings that informed decisions:
- **jseval**: `run.py:75-87` accesses all 10 search config fields as flat top-level keys via `status.get("chunkAwareEnabled")`. One function to update.
- **SearchHit callsites**: Only 2 files import `SearchHit` (only `resultMapper.ts` accesses snake_case fields). 15 files import `HUDResultItem` (already camelCase). Blast radius for casing rename is minimal.
- **updateSchemas**: Generates JSON fixtures from Java records into `modules/ui-web/src/api/__fixtures__/`. `contract.test.ts` validates fixtures against Zod schemas. No Java-to-TS type generation exists. JSON Schema `.schema.json` files exist on the Java side but are not consumed by the frontend.
- **Grouped object pattern**: Static `from(WorkerOperationalView)` factory methods. For SearchConfigView, removing `@JsonUnwrapped` from `WorkerOperationalView.java:28` is sufficient — the field naturally nests as `searchConfig` because `WorkerOperationalView` itself is still unwrapped into `StatusResponse`.
- **models/types.ts scope**: 20 importers, but 15 are `HUDResultItem` only. `SearchHit` imported by 2 files. `ExcerptRegion` by 1. Zero files import these from `api/domains/search.ts`.

### AD1 — Hard cut for SearchConfigView (resolves D1)

All consumers are internal (React frontend, jseval, MCP dev tools). No dual-emit needed. Remove `@JsonUnwrapped` from `SearchConfigView` in `WorkerOperationalView.java` and update all consumers in one commit.

The other 9 `@JsonUnwrapped` sub-records in `WorkerOperationalView` keep their current dual-emit pattern (5 have grouped counterparts via 330 §4, 5 have phantom flat fields with no frontend coverage). Scope limited to SearchConfigView — it's the only one causing frontend field triplication.

### AD2 — camelCase SearchHit via full rewrite (resolves D3)

Rewrite `SearchHit` to all-camelCase. The mapper (`mapKnowledgeSearchResponse`) renames Lucene field names at the boundary: `fields.doc_id -> docId`, `fields.file_kind -> fileKind`, etc. Computed fields (`excerptRegions`, `matchSpans`, `matchedFields`, `provenance`) are accessed directly on the typed `KnowledgeSearchResponse.results[]` element — no `as any` casts.

This eliminates the double-rename (`excerptRegions` -> `excerpt_regions` -> `excerptRegions`) and all 5 structurally unnecessary `as any` casts in the mapper. Defensive type guards (`typeof x === 'string'`) remain — they protect the system boundary against backend type drift.

Blast radius: `search.ts` (mapper rewrite), `models/types.ts` (type rewrite), `resultMapper.ts` (field rename, ~14 properties), `contract.test.ts` (field name updates). All confined to this tempdoc's scope.

### AD3 — Two-layer type system with single definitions (resolves D5, D6)

**Layer 1 — API types (`api/domains/`)**: Types that cross the network boundary. Canonical home for `SearchHit`, `ExcerptRegion`, `MatchSpan`, `HitProvenance`, `SearchResponse`, `SearchFilters`. All camelCase, matching the wire format.

**Layer 2 — UI types (`models/types.ts`)**: Presentation types for React components. `HUDResultItem` stays here. Imports `ExcerptRegion` and `MatchSpan` from `api/domains/search.ts` — no independent redefinitions. `MatchSpanLike` type alias removed (identical to `MatchSpan`). `HighlightMap` stays (UI-only concept).

**Type deduplication:**
| Type | Canonical home | Current duplicates | Action |
|------|---------------|-------------------|--------|
| `SearchHit` | `api/domains/search.ts` | `models/types.ts` (independent defn) | Remove from `models/types.ts`. 2 importers switch path. |
| `ExcerptRegion` | `api/domains/search.ts` | `models/types.ts` (independent defn) | Remove from `models/types.ts`, re-export from `api/domains/search.ts`. 1 importer (`ResultRow.tsx`) switches path or uses re-export. |
| `MatchSpan` / `MatchSpanLike` | `api/domains/search.ts` (as `MatchSpan`) | `models/types.ts` (as `MatchSpanLike`) | Remove `MatchSpanLike`. `HUDResultItem.matchSpans` uses `MatchSpan[]`. |
| `InferenceMode` | `stores/systemTypes.ts` | `useInferenceMode.ts`, `useAiCapabilities.ts` | 2 files import from canonical location. |
| `AppSettings` / `UISettings` / `LLMSettings` | `api/domains/settings.ts` | `stores/systemTypes.ts` (re-export) | Remove re-exports. `useSystemStore.ts` imports from `api/domains/settings.ts` directly. `useSettings.ts:111` `as AppSettings` cast becomes unnecessary. |

**HUDResultItem completeness fix:** Add `metaPublishedAt?: string | undefined` to `HUDResultItem`. Map in `resultMapper.ts:233`.

### AD4 — Complete Zod schemas with contract coverage enforcement (resolves D4)

**Zod completeness:** Declare every API field in the corresponding Zod schema. Keep `.loose()` mode (survives additive backend changes).

Fields to add to `SearchHitSchema`:
- `excerptRegions` (nested `ExcerptRegionSchema` array)
- `collection`
- `metaSource`, `metaAuthor`, `metaCategory`, `metaPublishedAt`
- `provenance` (nested `HitProvenanceSchema`)

Fields to add to `SearchResponseSchema`:
- `correctionApplied`
- `correctedQuery`
- `entityFacetVariants`

Fields to add to `SystemStatusSchema`:
- `searchConfig` (nested object with 10 fields — replaces 10 flat fields after AD1)

**Contract coverage enforcement — four automated tests, zero manual steps:**

1. **Schema staleness test** (Java-side, runs in `./gradlew test`): **Already exists.** `StatusRecordSchemaTest.CrossLanguageContract.assertFixtureMatchesCurrentJson()` already diffs committed fixtures against generated output and fails with: *"Regenerate with: ./gradlew.bat :modules:app-api:updateSchemas"*. `SchemaDrift` tests do the same for JSON Schema baselines. No new infrastructure needed for this layer.

2. **Zod coverage test** (frontend, runs in `npm run test:unit:run`): Loads the committed JSON Schema, extracts property names, asserts each is declared in the Zod schema or on an explicit allowlist. Because test #1 guarantees the JSON Schema is fresh, this transitively catches any Java field addition that lacks Zod coverage.

3. **SSOT field completeness test** (Java-side, runs in `./gradlew test`): Loads `SSOT/catalogs/fields.v1.json`, extracts field names marked as stored/returned, asserts each appears in `sampleSearchResponse()`'s fixture `fields` map. This closes the blind spot around dynamic `Map<String, Object>` keys that JSON Schema cannot enumerate — the SSOT catalog is the source of truth for which Lucene fields exist. Note: `sampleSearchResponse()` currently includes 12 Lucene fields but is missing `meta_category` and `meta_published_at` — the fixture must be updated.

4. **Mapper propagation test** (frontend, runs in `npm run test:unit:run`): Given a fixture with all fields populated, maps it through `mapKnowledgeSearchResponse`, and asserts that every `fields` map entry from the raw input appears as a non-`undefined` camelCase property on the mapped `SearchHit`, and that every top-level result property (`excerptRegions`, `matchSpans`, `matchedFields`, `provenance`) is propagated when present. This catches mapper omissions (the `metaPublishedAt` class of bug) that optional Zod validation cannot. Requires a field-name mapping table (`content_preview → contentPreview`, etc.) since input and output use different casing.

**Detection chain:** Add a Java field → schema staleness test fails (already built) → developer runs `updateSchemas` → committed schema/fixture updated → Zod coverage test fails if field not in Zod → mapper propagation test fails if field not mapped. Add a Lucene field → SSOT completeness test fails if not in fixture → same downstream chain. Every step is a failing test. The only manual action is fixing what the test told you about.

---

## Implementation Plan

Seven phases in two parallel tracks. Modules touched: `app-api`, `ui-web`, `scripts/jseval`.

```
Track 1: Phase 1 → Phase 2
Track 2: Phase 3 → Phase 4 → Phase 5
(join)  → Phase 6 → Phase 7
```

Phases 1/2 and 3/4/5 have no file overlap and can run in parallel. Phase 6 (Java-side test infrastructure) and Phase 7 (frontend test infrastructure) run after both tracks complete.

### Phase 1 — Backend: Nest SearchConfigView (W16 backend half)

**What:** Remove `@JsonUnwrapped` from `SearchConfigView searchConfig` in `WorkerOperationalView.java`. This single annotation removal causes the 10 search config fields to serialize as a nested `"searchConfig": { ... }` object in `/api/status`, because `WorkerOperationalView` itself remains `@JsonUnwrapped` in `StatusResponse`.

**Files:**
| File | Change |
|------|--------|
| `modules/app-api/.../WorkerOperationalView.java:28` | Remove `@JsonUnwrapped` annotation |

**Verify:** `./gradlew.bat :modules:app-api:compileJava` (compilation), then `./gradlew.bat :modules:app-api:test` (any schema drift tests will flag the changed shape — regenerate with `updateSchemas`).

### Phase 2 — Frontend + jseval: Consume nested searchConfig (W16 frontend half)

**What:** Update all consumers of the flat search config fields to read from the nested `searchConfig` object.

**Files:**
| File | Change |
|------|--------|
| `modules/ui-web/src/stores/systemTypes.ts:115-126, 255-265` | Keep `SearchConfigFields` interface. Remove 10 flat fields from `SystemStatus`. Add `searchConfig?: SearchConfigFields \| undefined` as nested field on `SystemStatus`. |
| `modules/ui-web/src/api/schemas.ts` (SystemStatusSchema) | Replace 10 flat Zod fields with nested `searchConfig: z.object({...}).loose().optional()` |
| `modules/ui-web/src/stores/useSystemStore.ts:261-271` | Replace 10 individual field mappings with `searchConfig: validated.searchConfig ?? undefined` |
| `modules/ui-web/src/mocks/fixtures.mjs:81-90` | Move 10 flat search config fields into nested `searchConfig` object |
| `modules/ui-web/src/mocks/fixtures.test.ts:49-56` | Update shape assertions to match nested structure |
| `scripts/jseval/jseval/run.py:75-87` | Change `status.get("chunkAwareEnabled")` to `status.get("searchConfig", {}).get("chunkAwareEnabled")` (all 10 fields) |
| `modules/ui-web/src/api/__fixtures__/status-response-live.json` | Regenerated by `updateSchemas` (search config fields move into nested object) |

**Verify:** `./gradlew.bat :modules:app-api:updateSchemas`, then `cd modules/ui-web && npm run typecheck && npm run test:unit:run`.

**Downstream impact:** Verified — no components or hooks read these flat fields from the store. The only consumers are the store mapping, schema, type definitions, and mock fixtures (all listed above).

### Phase 3 — SearchHit casing rewrite (W10)

**What:** Rewrite `SearchHit` to all-camelCase. Rewrite `mapKnowledgeSearchResponse` to rename Lucene field names at the boundary and access typed record fields directly (no `as any`). Update Zod schema field names. Update `resultMapper.ts` to use camelCase field names.

**Files:**
| File | Change |
|------|--------|
| `modules/ui-web/src/api/domains/search.ts:12-37` | Rewrite `SearchHit` interface: `doc_id -> docId`, `mime_base -> mimeBase`, `file_kind -> fileKind`, `modified_at -> modifiedAt`, `size_bytes -> sizeBytes`, `content_preview -> contentPreview`, `matched_fields -> matchedFields`, `match_spans -> matchSpans`, `excerpt_regions -> excerptRegions`, `meta_source -> metaSource`, `meta_author -> metaAuthor`, `meta_category -> metaCategory`, `meta_published_at -> metaPublishedAt` |
| `modules/ui-web/src/api/domains/search.ts:210-286` | Rewrite `mapKnowledgeSearchResponse`: output camelCase keys. Remove all `as any` casts — access `res.excerptRegions`, `res.matchedFields`, `res.matchSpans`, `res.provenance` directly on the typed element. Keep defensive type guards for fields read from `res.fields` map. |
| `modules/ui-web/src/api/schemas.ts:310-339` | Rename existing `SearchHitSchema` fields from snake_case to camelCase: `doc_id -> docId`, `mime_base -> mimeBase`, `file_kind -> fileKind`, `modified_at -> modifiedAt`, `size_bytes -> sizeBytes`, `content_preview -> contentPreview`, `matched_fields -> matchedFields`, `match_spans -> matchSpans`. |
| `modules/ui-web/src/utils/resultMapper.ts:163-236` | Update all `hit.doc_id -> hit.docId`, `hit.content_preview -> hit.contentPreview`, etc. Add `metaPublishedAt: hit.metaPublishedAt \|\| undefined` to HUDResultItem construction. |
| `modules/ui-web/src/api/contract.test.ts` | Update field name assertions from snake_case to camelCase (e.g., `hit[0].matched_fields -> hit[0].matchedFields`, `hit[0].meta_source -> hit[0].metaSource`). |

**Verify:** `cd modules/ui-web && npm run typecheck && npm run test:unit:run`

### Phase 4 — Type consolidation (W15 + AD3)

**What:** Establish single source of truth for all shared types. Remove duplicate definitions. Clean up import paths.

**Files:**
| File | Change |
|------|--------|
| `modules/ui-web/src/models/types.ts` | Remove `SearchHit`, `ExcerptRegion`, `MatchSpanLike` definitions. Import `ExcerptRegion`, `MatchSpan` from `../api/domains/search`. Update `HUDResultItem.matchSpans` type from `MatchSpanLike[]` to `MatchSpan[]`. Add `metaPublishedAt?: string \| undefined` to `HUDResultItem`. Keep `HighlightMap`. Re-export `SearchHit`, `ExcerptRegion`, `MatchSpan` from `api/domains/search` for backward-compatible imports. |
| `modules/ui-web/src/hooks/useSearch.ts` | Import `SearchHit` from `../api/domains/search` (or via re-export from `../models/types`) |
| `modules/ui-web/src/components/.../ResultRow.tsx` | Import `ExcerptRegion` from re-export or `api/domains/search` |
| `modules/ui-web/src/stores/systemTypes.ts:272` | Keep `InferenceMode` here as canonical |
| `modules/ui-web/src/hooks/useInferenceMode.ts:8` | Remove local definition, import from `../stores/systemTypes` |
| `modules/ui-web/src/hooks/useAiCapabilities.ts:12` | Remove local definition, import from `../stores/systemTypes` |
| `modules/ui-web/src/stores/systemTypes.ts:292-298` | Remove `UISettings`/`LLMSettings`/`AppSettings` re-exports |
| `modules/ui-web/src/stores/useSystemStore.ts` | Import `AppSettings` from `../api/domains/settings` instead of `./systemTypes` |
| `modules/ui-web/src/hooks/useSettings.ts:111` | Remove `as AppSettings` cast (store is now properly typed — same import path) |

**Verify:** `cd modules/ui-web && npm run typecheck && npm run test:unit:run`

### Phase 5 — Zod schema completeness (AD4)

**What:** Add all missing fields to Zod schemas. Create new sub-schemas for complex nested types. Keep `.loose()` mode.

**Files:**
| File | Change |
|------|--------|
| `modules/ui-web/src/api/schemas.ts` | **New sub-schemas:** `ExcerptRegionSchema` (text, startChar, endChar, approxLine, matchSpans array using existing inline MatchSpan shape). `HitProvenanceSchema` (bm25, splade, dense, fusion, chunkMerge, branchFusion, crossEncoder — all optional nested objects with score/rank fields). `EntityVariantBreakdownSchema` (canonicalForm string, totalCount number, variants record). **Add to `SearchHitSchema`:** `excerptRegions: z.array(ExcerptRegionSchema).optional()`, `collection: z.string().optional()`, `metaSource`, `metaAuthor`, `metaCategory`, `metaPublishedAt` (all `z.string().optional()`), `provenance: HitProvenanceSchema.optional()`. **Add to `SearchResponseSchema`:** `correctionApplied: z.boolean().optional()`, `correctedQuery: z.string().nullable().optional()`, `entityFacetVariants: z.record(z.string(), z.array(EntityVariantBreakdownSchema)).optional()`. |
| `modules/app-api/.../StatusRecordSchemaTest.java` | Update `sampleSearchResponse()` to populate `meta_category` and `meta_published_at` in the fields map (currently missing — only `meta_source` and `meta_author` are populated). Then run `updateSchemas` to regenerate fixture. |

**Verify:** `./gradlew.bat :modules:app-api:updateSchemas`, then `cd modules/ui-web && npm run test:unit:run` (contract tests validate fixtures against updated schemas)

### Phase 6 — SSOT field completeness test (AD4 prevention)

**What:** Add test that verifies the search fixture includes all stored Lucene fields. The schema staleness verify mode already exists (`StatusRecordSchemaTest.CrossLanguageContract.assertFixtureMatchesCurrentJson()` — already diffs committed fixtures against generated output, fails with "Regenerate with: ./gradlew.bat :modules:app-api:updateSchemas"). No new infrastructure needed for that layer.

**Files:**
| File | Change |
|------|--------|
| `modules/app-api/.../StatusRecordSchemaTest.java` (new nested class) | `SsotFieldCompleteness`: Load `SSOT/catalogs/fields.v1.json`, extract field names marked as stored/returned. Call `sampleSearchResponse()`, extract keys from the `fields` map of the rich hit (doc-1). Assert every SSOT stored field appears in the fixture. Catches new Lucene fields that the fixture doesn't include. |

**Verify:** `./gradlew.bat :modules:app-api:test`

### Phase 7 — Frontend automated verification (AD4 prevention)

**What:** Add Zod coverage test and mapper propagation test. These run automatically in `npm run test:unit:run` — no manual step needed for detection.

**Files:**
| File | Change |
|------|--------|
| `modules/ui-web/src/api/contract.test.ts` | **New test block: Zod coverage** — load `status-response.schema.json` (path: `modules/app-api/src/main/resources/schemas/`), extract property names, assert each is declared in `SystemStatusSchema` or on an explicit allowlist. Same for search response. **Allowlist rationale:** flat `@JsonUnwrapped` fields legitimately irrelevant to the frontend (e.g., `MigrationGenerationView` internals) go on the allowlist, which documents intentional omissions. |
| `modules/ui-web/src/api/contract.test.ts` | **New test block: Mapper propagation** — load `search-response-live.json` (rich hit has all fields populated after Phase 5 fixture update). Run through `mapKnowledgeSearchResponse`. For `fields` map entries: use a `FIELD_RENAME_MAP` (e.g., `content_preview → contentPreview`, `file_kind → fileKind`) to assert each Lucene field appears as its camelCase counterpart on the mapped hit. For top-level result properties (`excerptRegions`, `matchSpans`, `matchedFields`, `provenance`): assert non-`undefined` when present in input. The rename map is the single source of truth for the snake→camel mapping — if a new field is added to the fixture but missing from the map, the test explicitly fails. |

**Verify:** `cd modules/ui-web && npm run test:unit:run`

---

## Prevention Mechanisms

After all phases complete, four automated tests and one convention prevent recurrence. Every test runs in the normal build — no manual triggers required for detection.

**Automated (run in `./gradlew test` and `npm run test:unit:run`):**

1. **Schema staleness test** (Phase 6, Java): Regenerates schemas/fixtures in temp, diffs against committed. Catches any Java record change that wasn't followed by `updateSchemas`. Developer sees a clear failure message with the exact fix command.
2. **SSOT field completeness test** (Phase 6, Java): Compares `fields.v1.json` stored fields against `sampleSearchResponse()` fixture. Catches new Lucene fields missing from the fixture — the blind spot that JSON Schema cannot cover because `Map<String, Object>` keys are dynamic.
3. **Zod coverage test** (Phase 7, frontend): Compares JSON Schema properties against Zod shape keys. Catches Java fields that lack frontend validation. Allowlist documents intentionally ignored wire fields.
4. **Mapper propagation test** (Phase 7, frontend): Asserts every populated input field appears in the mapped output. Catches mapper omissions (the `metaPublishedAt` class of bug) that optional Zod validation misses.

**Convention (enforced by code review):**

5. **Single type definition**: Each type has one canonical location in `api/domains/`. No independent redefinitions. `models/types.ts` may re-export but never redefine.

**Detection chain for adding a field:**

```
Add Java record field
  → schema staleness test fails (./gradlew test)
  → run updateSchemas
  → Zod coverage test fails (npm test) if not in Zod
  → mapper propagation test fails (npm test) if not mapped

Add Lucene stored field
  → SSOT completeness test fails (./gradlew test) if not in fixture
  → same downstream chain
```

Every step is a failing test. The only manual action is fixing what the test told you about.

---

## Investigation Log

- 2026-04-08: Deep investigation of Stream A (frontend types). Read all primary files and audited the full frontend type contract. Key findings:
  - **W16 detail**: `@JsonUnwrapped` is not unique to `SearchConfigView` — there are 11 unwrapped fields total across `StatusResponse -> WorkerOperationalView -> 10 sub-records`, plus a second-order unwrap (`EnrichmentProgressView -> ChunkCoverageView`). The `SearchConfigView` fields appear in 4 TypeScript locations: `SearchConfigFields` interface, `SystemStatus` interface (duplicated, not composed via `&`), `SystemStatusSchema` Zod schema, and `useSystemStore.ts` mapping. The undocumented `ChunkCoverageView` unwrap lands 6 `chunk*` fields flat at the top level but they are absent from both `SystemStatus` and the Zod schema.
  - **W10 detail**: The casing path is actually a double rename: `excerptRegions` (JSON wire) -> `excerpt_regions` (SearchHit, snake_case) -> `excerptRegions` (HUDResultItem, camelCase). The `as any` casts at `search.ts:214,215,221,262,279` are structurally unnecessary — `matchedFields`, `matchSpans`, `excerptRegions`, and `provenance` are all declared on the `KnowledgeSearchResponse.results[]` item type and could be accessed directly without casting. The `SearchHitSchema` Zod schema has no coverage for `excerpt_regions`, `collection`, all 4 metadata fields, or `provenance` (7 fields unvalidated).
  - **W15 detail**: Settings re-export is clean (comment acknowledges it). The `as AppSettings` cast at `useSettings.ts:111` is structurally safe — both paths resolve to the same definition. The real issue is the precedent: `InferenceMode` is independently defined 3 times (`systemTypes.ts:272`, `useInferenceMode.ts:8`, `useAiCapabilities.ts:12`) with identical string literals but no shared source.
  - **Additional findings beyond W10/W15/W16:**
    - `SearchHit` uses snake_case for 14 fields (matching Lucene field names) but `HUDResultItem` converts to camelCase. `meta_published_at` is mapped into `SearchHit` but lost during `HUDResultItem` conversion — the field exists nowhere in `HUDResultItem` or `resultMapper.ts`.
    - `SearchResponseSchema` is missing `correctionApplied`, `correctedQuery`, and `entityFacetVariants` (runtime-unvalidated).
    - `SystemStatus` is missing 20+ flat fields from `WorkerOperationalView` sub-records (`MigrationGenerationView` 10 fields, `VectorFormatView` 5 fields, `TelemetryMetricsView` 3 fields, `FailureTrackingView.searchesZeroResultCount`, `CoreIndexView.pendingVduCount`, `WorkerOperationalView.buildStamp`, `GpuDiagnosticsView` 3 fields).
    - `ExcerptRegion` is defined twice independently: `api/domains/search.ts:57-63` (interface) and `models/types.ts:10-16` (type alias). Both are structurally identical but not linked. Same for `MatchSpan`/`MatchSpanLike`.
    - 17 `as any` casts total in frontend; 5 in `search.ts` mapping are structurally unnecessary; others are for Tauri/Vite globals, debug hooks, and non-standard DOM APIs.
    - 11 `as unknown` casts; most are double-casts for runtime config injection or SSE payload typing.
    - Only 1 `@ts-ignore` in production code (`useDragDrop.ts:108` for `entry.fullPath`).
- 2026-04-08: 5 targeted investigations resolved all design considerations into architecture decisions. jseval confirmed flat-key access (hard cut viable). SearchHit blast radius confirmed minimal (2 importers). updateSchemas mechanism understood (fixture-based, no type generation). Grouped object pattern confirmed (annotation removal sufficient). models/types.ts scope mapped (20 importers, 15 HUDResultItem-only). Full 6-phase implementation plan written with per-phase file manifests and verification steps.
- 2026-04-08: Critical analysis identified 7 high-level design weaknesses. Prevention mechanisms depended on manual `updateSchemas` run. Zod coverage test allowlist would be large. Dynamic Lucene fields invisible to JSON Schema. Optional Zod fields don't catch mapper omissions. Phase sequencing overly strict. Design revised with automated detection chain and parallel tracks.
- 2026-04-08: Resolution of remaining uncertainties. 5 parallel investigations:
  - **schemas.ts verified:** `SearchHitSchema` uses snake_case (`doc_id`, `matched_fields`) — Phase 3 must rename. No `ExcerptRegionSchema`, `HitProvenanceSchema`, or `EntityVariantBreakdownSchema` exist — Phase 5 must create them. `EntityVariantBreakdown` exists as TS interface only.
  - **contract.test.ts verified:** 3 test blocks (status, settings, search). Search block maps through `mapKnowledgeSearchResponse` before Zod validation. Tests reference `hit[0].matched_fields` (snake_case) — Phase 3 must update. Fixture `results[0].fields` map has 12 Lucene fields; `doc_id` is top-level `id`, not in fields map. `meta_category` and `meta_published_at` missing from fixture.
  - **Java tests verified:** `CrossLanguageContract` is nested inside `StatusRecordSchemaTest.java` (not separate file). **Verify mode already exists** — `assertFixtureMatchesCurrentJson()` diffs and fails with regeneration instructions. Phase 6 reduced to SSOT field completeness test only. `sampleSearchResponse()` populates 12 realistic Lucene fields.
  - **Downstream searchConfig:** Zero components read flat search config fields from store. Phase 2 blast radius confirmed minimal (store mapping + schema + types + mocks only).
  - **MCP tools:** No MCP tool directly reads search config field names. `justsearch-dev-mcp-harness.mjs` fetches `/api/status` but doesn't parse individual search config keys. Hard cut confirmed safe.
  - Plan corrections: Phase 2 adds `fixtures.mjs`, `fixtures.test.ts`. Phase 3 adds Zod field rename. Phase 5 notes new sub-schemas and missing fixture fields. Phase 6 reduced (staleness test already exists). Phase 7 mapper test refined with `FIELD_RENAME_MAP`.
- 2026-04-08: Critical analysis of design. Five high-level issues identified: (1) prevention mechanisms depend on manual `updateSchemas` run — soft, not automated; (2) Phase 6 coverage test allowlist would be 30+ entries due to @JsonUnwrapped field explosion — mostly allowlist; (3) dynamic Lucene field names invisible to JSON Schema — blind spot for half of SearchHit's fields; (4) optional Zod fields don't catch mapper omissions (the metaPublishedAt class of bug); (5) strict phase sequencing overly conservative — two independent tracks could parallelize. Design revised: manual `updateSchemas` replaced with verify-on-build (schema staleness test runs in normal `./gradlew test`). SSOT field completeness test addresses Lucene field blind spot. Mapper propagation test addresses optional-field omission blind spot. Phase sequencing relaxed to two parallel tracks. Plan expanded from 6 to 7 phases.
- 2026-04-08: **Phase 1+2 implemented** (W16 — SearchConfigView nesting). Worktree `380-type-contract`.
  - Phase 1: Removed `@JsonUnwrapped` from `SearchConfigView searchConfig` in `WorkerOperationalView.java:28`. Ran `updateSchemas` — fixture now has nested `"searchConfig": { ... }`. Confirmed flat fields no longer appear at top level.
  - Phase 2: Updated 6 files. `systemTypes.ts`: replaced 10 flat fields with `searchConfig?: SearchConfigFields`. `schemas.ts`: replaced 10 flat Zod fields with nested `searchConfig` object schema. `useSystemStore.ts`: replaced 10 individual mappings with single passthrough. `fixtures.mjs`: nested the mock data. `fixtures.test.ts`: updated shape assertions and required keys list. `run.py` (jseval): reads from `status.get("searchConfig", {})`.
  - Verification: Java compilation passes. `updateSchemas` succeeds. Frontend typecheck clean. All 200 frontend unit tests pass.
- 2026-04-08: **Phases 3-7 implemented.**
  - Phase 3: Rewrote `SearchHit` to camelCase (13 field renames). Rewrote `mapKnowledgeSearchResponse` — removed all 5 `as any` casts, access typed record fields directly. Added `provenance` to `KnowledgeSearchResponse.results[]` element type. Renamed Zod `SearchHitSchema` fields to camelCase. Updated `resultMapper.ts` (14 property renames + added `metaPublishedAt` mapping). Updated `contract.test.ts` assertions. Updated `search.test.ts` assertions.
  - Phase 4: Removed independent `SearchHit`, `ExcerptRegion`, `MatchSpanLike` definitions from `models/types.ts` — now re-exports from `api/domains/search.ts`. `MatchSpanLike` eliminated (replaced by `MatchSpan`). Added `metaPublishedAt` to `HUDResultItem`. Deduplicated `InferenceMode` — canonical in `systemTypes.ts`, `useInferenceMode.ts` and `useAiCapabilities.ts` import from there. Removed settings type re-exports from `systemTypes.ts` — `useSystemStore.ts` imports `AppSettings` directly from `api/domains/settings`. Removed `as AppSettings` cast from `useSettings.ts`.
  - Phase 5: Created 4 new Zod sub-schemas (`MatchSpanSchema`, `ExcerptRegionSchema`, `HitProvenanceSchema`, `EntityVariantBreakdownSchema`). Added 7 missing fields to `SearchHitSchema` (`collection`, `excerptRegions`, `metaSource`, `metaAuthor`, `metaCategory`, `metaPublishedAt`, `provenance`). Added 3 missing fields to `SearchResponseSchema` (`correctionApplied`, `correctedQuery`, `entityFacetVariants`). Updated Java `sampleSearchResponse()` to include `collection`, `meta_category`, `meta_published_at` in fields map. Regenerated fixture.
  - Phase 6: Added field completeness test in `StatusRecordSchemaTest.java` (hardcoded expected set). Schema staleness verify mode was already built (no new work needed). **Post-implementation finding:** the hardcoded expected set does NOT fulfill Phase 6's design intent — adding a new `stored: true` field to the SSOT catalog does not automatically fail the test. See research below for revised approach.
  - Phase 7: Added mapper propagation test with `FIELD_RENAME_MAP` — verifies every populated fields-map entry and top-level property propagates through the mapper. 2 new tests (202 total).
  - Final verification: Frontend typecheck clean. 202/202 frontend tests pass (2 new). Java `app-api:test` passes. `updateSchemas` succeeds.
- 2026-04-08: Critical analysis of Phases 3-7 implementation. Key findings:
  - Phase 3: Solid. 5 `as any` casts eliminated. Provenance typed but not validated until Zod (acceptable — Zod validates downstream).
  - Phase 4: Re-export from `models/types.ts` contradicts W15 fix rationale (removed settings re-exports but added search re-exports). Pragmatic but inconsistent.
  - Phase 5: HitProvenance Zod sub-fields are sparse (only validates `score` on each sub-object; `.loose()` passes the rest). Acceptable for tolerating additive changes.
  - Phase 6: **Does not fulfill design intent.** Hardcoded `expectedFields` set does not read SSOT catalog. Adding a new `stored: true` field to `fields.v1.json` has zero effect on the test. Needs replacement.
  - Phase 7: `FIELD_RENAME_MAP` is manually maintained (same "update two places" problem). `filename → title` mapping is conceptually wrong (filename is consumed internally, not a direct field mapping). Zod coverage test (JSON Schema vs Zod shape keys) was designed but not implemented.
- 2026-04-08: Research on industry approaches to field drift prevention. Three patterns found:
  1. **Schema-first / OpenAPI** — single spec generates both backend and frontend types. Gold standard but requires infrastructure. Tools: `openapi-typescript`, `openapi-zod-client`, `hey-api/zod`. Not applicable without adopting OpenAPI for the search API.
  2. **Consumer-driven contracts (Pact)** — frontend declares expectations, backend CI verifies against them. Catches drift bidirectionally. Requires broker service. Overkill for single-consumer desktop app.
  3. **Snapshot/golden-file testing** — backend serializes sample response, frontend validates shape. Already what JustSearch does with `updateSchemas`. Doesn't auto-discover new dynamic fields.
  - **Key finding:** None of these solve the dynamic `Map<String, Object>` problem natively. OpenAPI represents it as `additionalProperties: true`. Pact can assert specific fields but doesn't auto-discover new ones.
  - **Critical codebase finding:** Backend returns ALL stored fields by default (confirmed via `SearchResultFormatter.java` and `ReadPathOps.java` — `storedAllowlist` is `null` when no projection requested, `StoredFieldVisitor` returns `Status.YES` for every stored field except `content`). This means **`stored: true` in `fields.v1.json` IS equivalent to "returned in search results"** for this codebase. The earlier objection ("stored ≠ returned") was wrong.
  - **Revised Phase 6 approach:** Catalog-derived with exclusion list. Read `fields.v1.json`, filter to `stored: true`, subtract explicit exclusion set of internal fields (~38 entries), assert remainder appears in fixture. Adding a new `stored: true` field automatically fails the test — developer must route to fixture or exclusion list.
  - **Alternative considered:** Snapshot the real response shape by building a test index, running a search, and comparing field keys against the fixture. Eliminates both manual expected set and exclusion list. Requires integration-test infrastructure (Lucene index in test).
- 2026-04-08: **Snapshot alternative ruled out.** Cannot live in `app-api` (no Lucene dependency). Catalog-derived approach confirmed as right fit.
- 2026-04-08: **5 post-analysis fixes implemented:**
  - Fix 1: Replaced hardcoded `expectedFields` in Phase 6 with catalog-derived logic. Test reads `SSOT/catalogs/fields.v1.json`, filters `stored: true`, subtracts 37-entry `INTERNAL_FIELDS` exclusion set. Adding a new stored field to the catalog now automatically fails the test. Wrapped in `@Nested @DisplayName("SSOT field completeness")`.
  - Fix 2: Removed `filename` from `FIELD_RENAME_MAP` (not a direct field mapping). Removed `title` skip condition — `title` now verified like any other field.
  - Fix 3: Added sparse hit test — maps fixture hit[1] (minimal fields), verifies optional fields are `undefined` without crashing.
  - Fix 4: Removed `SearchHit`/`ExcerptRegion`/`MatchSpan` re-exports from `models/types.ts`. Updated 3 importers (`resultMapper.ts`, `useSearch.ts`, `ResultRow.tsx`) to import from `api/domains/search`. No dual import paths remain for any type (consistent with W15 fix).
  - Fix 5: Added missing sub-fields to `HitProvenanceSchema` Zod: `chunkMerge` gets `spladeRank`/`spladeScore`/`ccScore`, `branchFusion` gets `wholeBranchScore`/`chunkBranchScore`/`fusionScore`/`method`.
  - Fix 6 (Zod coverage test): Deferred — 115 top-level JSON Schema properties, ~55 in Zod, allowlist would be 60 entries (52%). Not cost-effective until remaining `@JsonUnwrapped` annotations are removed.
  - Verification: Frontend typecheck clean. 203/203 tests pass (3 new). Java `app-api:test` passes. Cannot live in `app-api` (no Lucene dependency). Would require full integration-test infrastructure in `adapters-lucene` or `system-tests`, cross-module fixture file access, and still needs an expected-fields source. The `content` exclusion in `SearchResultFormatter` means the test would need hardcoded knowledge of formatter behavior — same kind of exception the approach was trying to avoid. Catalog-derived approach confirmed as the right fit for Phase 6's location and purpose.

### Remaining fixes (post-implementation)

Seven issues identified during critical analysis. Planned as a single cleanup pass.

**Fix 1 — Phase 6: Catalog-derived field completeness (replaces hardcoded expected set)**
Replace the hardcoded `Set.of(...)` in `searchFixtureFieldsMapCompleteness()` with catalog-derived logic:
1. Read `SSOT/catalogs/fields.v1.json` from the filesystem (same resolution pattern as `resolveUiWebFixture()`)
2. Parse JSON, filter to entries with `"stored": true`
3. Subtract an explicit `INTERNAL_FIELDS` exclusion set (~38 entries: enrichment status, chunk fields, NER fields, etc.) and the `content` field (excluded by `SearchResultFormatter`)
4. Assert the remainder appears in `sampleSearchResponse()`'s rich hit fields map
5. Wrap in `@Nested @DisplayName("SSOT field completeness")` class

**Key property:** Adding a new `stored: true` field to the catalog automatically fails the test. The developer must route it to either `sampleSearchResponse()` (fixture → downstream chain) or `INTERNAL_FIELDS` (documented exclusion). The exclusion list is large but stable — internal fields rarely change.

**Why this works:** Backend returns ALL stored fields by default (`ReadPathOps.java` passes `null` allowlist, `StoredFieldVisitor` returns `YES` for everything except `content`). So `stored: true` in the catalog IS equivalent to "in the search API response."

Files:
- `StatusRecordSchemaTest.java` — rewrite `searchFixtureFieldsMapCompleteness()` to read catalog, add `INTERNAL_FIELDS` set, wrap in `@Nested`

**Fix 2 — Phase 7: Remove `filename` from `FIELD_RENAME_MAP` and `title` skip**
`filename` is consumed internally by the mapper for title derivation — it never appears as a field on `SearchHit`. Remove it from `FIELD_RENAME_MAP`. Remove the `camelKey !== 'title'` skip — `title` maps directly to `title` in the fields map and should be verified like any other field.

Files:
- `contract.test.ts` — remove `filename: 'title'` entry, remove `title` skip condition

**Fix 3 — Phase 7: Test sparse hit**
Add an `it` block that maps the full fixture and checks the sparse hit (index 1) doesn't crash. Verifies that missing optional fields produce `undefined`, not exceptions.

Files:
- `contract.test.ts` — new test in the mapper propagation `describe` block

**Fix 4 — Phase 4: Remove re-exports from `models/types.ts`**
Remove `SearchHit`, `ExcerptRegion`, `MatchSpan` re-exports from `models/types.ts`. Update the 3 importers (`resultMapper.ts`, `useSearch.ts`, `ResultRow.tsx`) to import from `api/domains/search`. Makes the W15 fix consistent — no dual import paths for any type.

Files:
- `models/types.ts` — remove re-export line
- `resultMapper.ts` — change import path
- `useSearch.ts` — change import path
- `ResultRow.tsx` — change import path

**Fix 5 — Phase 5: HitProvenance sub-field coverage**
Add missing fields to Zod `HitProvenanceSchema` sub-objects to match the `HitProvenance` TypeScript interface:
- `chunkMerge`: add `spladeRank`, `spladeScore`, `ccScore`
- `branchFusion`: add `wholeBranchScore`, `chunkBranchScore`, `fusionScore`, `method`

Files:
- `schemas.ts` — update `HitProvenanceSchema`

**Fix 6 — Phase 7: Zod coverage test — DEFERRED**
Investigation result: `victools` flattens `@JsonUnwrapped` fields into top-level `properties`. The JSON Schema has **115 top-level properties**. The Zod schema declares ~55. The allowlist would be **~60 entries (52% of all properties)**. More than half the test would be allowlist — a code smell that indicates the test is mostly maintaining exclusions rather than catching drift.

Root cause: the `@JsonUnwrapped` pattern on `WorkerOperationalView` multiplies 10 sub-records into ~80 flat properties, most of which are internal diagnostics the frontend intentionally ignores. Until the remaining `@JsonUnwrapped` annotations are removed (which would reduce the top-level property count to ~35), the coverage test is not cost-effective.

**Decision:** Defer until the broader `@JsonUnwrapped` removal happens (outside this tempdoc's scope). Document as a known limitation. The other prevention layers (schema staleness test, SSOT completeness test, mapper propagation test) provide sufficient coverage for the fields the frontend actually consumes.
