---
title: "384 — Type Contract Gaps (380 Follow-up)"
---

# 384 — Type Contract Gaps (380 Follow-up)

**Status:** Items 1-5 implemented + Zod coverage test.
**Created:** 2026-04-09
**Updated:** 2026-04-16
**Parent:** tempdoc 380 (Frontend-Backend Type Contract)
**Goal:** Close remaining gaps identified during tempdoc 380's critical analysis. Four concrete fixes + integration test repair.

---

## Items

### Item 1: Second mapper layer propagation test

**Gap:** The mapper propagation test (380 Phase 7) covers `KnowledgeSearchResponse → SearchHit` but NOT `SearchHit → HUDResultItem` (`mapHitsToResults` in `resultMapper.ts`). The `metaPublishedAt` bug was originally at this layer.

**SearchHit fields NOT mapped to HUDResultItem:**
| Field | Reason | Category |
|-------|--------|----------|
| `mimeBase` | Used as `meta` fallback | Consumed-not-surfaced |
| `fileKind` | Used to derive `type` | Consumed-not-surfaced |
| `highlights` | Raw map consumed for title/path/snippet extraction | Consumed-not-surfaced |
| `contentPreview` | Used for snippet + approxFirstMatchLine derivation | Consumed-not-surfaced |
| `provenance` | Entirely dropped | Deliberate gap |

**Implementation:** New test in `resultMapper.test.ts`. Map a SearchHit with all fields populated through `mapHitsToResults`. Verify 1:1 passthrough fields are defined. Maintain `CONSUMED_NOT_SURFACED` set for fields legitimately used to derive other properties.

**File:** `modules/ui-web/src/utils/resultMapper.test.ts`

### Item 2: Stale contract fixture cleanup

**Gap:** `modules/app-api/src/test/resources/contract/status-response-live.json` is a stale duplicate with the old flat shape. No test references it. The active fixture lives at `modules/ui-web/src/api/__fixtures__/status-response-live.json`.

**Implementation:** Delete the stale file. Verify `app-api:test` passes.

**File:** `modules/app-api/src/test/resources/contract/status-response-live.json` (DELETE)

### Item 3: Workflow docs — add Zod schema step

**Gap:** The "Add a field to an API record" workflow in `common-workflows.md` and `agent-guide.md` doesn't mention updating Zod schemas or frontend TypeScript types. `.loose()` schemas don't catch new fields, so the contract test alone is insufficient.

**Implementation:** Add a step between current steps 7 and 8 in both files.

**Files:**
- `.claude/rules/common-workflows.md` (lines 55-77)
- `docs/reference/contributing/agent-guide.md` (lines 239-248)

### Item 4: Complete @JsonUnwrapped removal

Remove `@JsonUnwrapped` from `WorkerOperationalView` on `StatusResponse` AND all 9 remaining sub-records on `WorkerOperationalView`. Everything nests under `"worker"` — no collision risk.

**Approach:** Remove all annotations. Wire format changes from 115 flat properties to ~25 top-level properties (`worker`, `embedding`, `schema`, `chunkCoverage`, `queueHealth`, `migration`, `gpu`, `meta`, `lifecycle`, `components`, `llm`, `onlineAi`, `readiness`, plus head-level scalars). Frontend `SystemStatus` stays flat — the store mapping flattens `validated.worker` sub-records with spread operators. Components don't change.

**Name collision handling:** `WorkerOperationalView.migration` and `WorkerOperationalView.gpu` are now under `worker` namespace, so no collision with `StatusResponse.migration` (MigrationStatusGroup) or `StatusResponse.gpu` (GpuStatusView).

**Backend changes:**
- `StatusResponse.java`: Remove `@JsonUnwrapped` from `WorkerOperationalView worker`
- `WorkerOperationalView.java`: Remove all 9 `@JsonUnwrapped` annotations
- `EnrichmentProgressView.java`: Remove `@JsonUnwrapped` from `ChunkCoverageView chunk`
- Run `updateSchemas` to regenerate fixtures and JSON Schema

**Frontend changes:**
- `schemas.ts`: Rewrite `SystemStatusSchema` to validate nested wire format (`worker.core`, `worker.failure`, etc.)
- `useSystemStore.ts`: Flatten `validated.worker` sub-records into flat SystemStatus via spread
- `fixtures.mjs`: Update mock to nested structure
- `fixtures.test.ts`: Update assertions
- `contract.test.ts`: Add Zod coverage test (JSON Schema vs Zod — now feasible with ~25 top-level properties)

**Fields consumed by frontend per sub-record (must be preserved in flattening):**
| Sub-record | Frontend-consumed flat fields |
|------------|------------------------------|
| CoreIndexView | `indexHealthy`, `indexedDocuments`, `pendingJobs`, `indexState`, `indexSizeBytes` |
| FailureTrackingView | `failedJobs`, `lastFailedPath`, `lastFailedErrorMessage`, `lastFailedAtMs`, `nextRetryAtMs`, `failedByFileKind` |
| MigrationGenerationView | `activeGenerationId`, `activeIndexedDocuments`, `buildingIndexedDocuments` |
| CompatibilityStatusView | (none — all covered by `embedding` + `schema` groups) |
| QueueDbStatusView | (none — all covered by `queueHealth` group) |
| EnrichmentProgressView | `pendingNerCount`, `completedNerCount`, `encoderProfiles` |
| GpuDiagnosticsView | `rerankerOrtCuda`, `embedOrtCuda`, `spladeOrtCuda`, `rerankerModelPath`, `spladeModelPath`, `nerModelPath` |
| VectorFormatView | (none — completely absent from frontend) |
| TelemetryMetricsView | `throughputDocsPerSec`, `throughputWindowState` |

### Item 5: SchemaMismatchStatusContractTest broken by Items 4 + ConfigStore evolution

**Gap:** `SchemaMismatchStatusContractTest` (ui module integrationTest) was
not updated when Item 4 changed the `/api/status` wire format from flat to
nested. Three compounding failures:

1. **ConfigStore not initialized (crash).** `RerankerConfig.fromEnv()` — added
   to `KnowledgeHttpApiAdapter` constructor during tempdoc 385 — calls
   `ConfigStore.global()`. The test constructs `LocalApiServer` (which
   constructs `KnowledgeHttpApiAdapter`) without initializing ConfigStore.
   Throws `IllegalStateException` at setup, masking all subsequent issues.

2. **JSON paths stale (poll never exits).** The test reads
   `json.path("indexSchemaFpStored")` and `json.path("reindexRequired")` at
   the top level. After Item 4's `@JsonUnwrapped` removal, these fields live
   under `schema.fpStored`, `schema.reindexRequired`, etc. The poll condition
   always sees blank strings → 50s timeout.

3. **Bogus fingerprint overwritten (assertion fails).** The test seeds a
   `v0_imported` generation with `index_schema_fp = "000...000"`. When the
   Worker starts, it ingests 5 built-in help files into the same generation
   and commits — writing the CURRENT schema FP over the bogus one. By the
   time `/api/status` is polled, `fpStored == fpCurrent`, so
   `reindexRequired = false`.

**Fix applied (2026-04-16):**
- Issue 1: ConfigStore initialization via `TestResolvedConfigHelper.storeFromEnvironment()`
  with save/restore in `@BeforeEach`/`@AfterEach`.
- Issue 2: JSON paths updated to `schema.fpStored`, `schema.fpCurrent`,
  `schema.reindexRequired`, `schema.reindexRequiredReason`,
  `schema.compatState`. `@Timeout(60s)` class-level override.
- Issue 3: One-line fix in `LuceneLifecycleManager.latestCommitUserDataBestEffort()`
  — added `RuntimeState.STARTING` to allowed states. The open-time snapshot
  capture in `startInternal()` now succeeds (was returning empty map due to
  state-gate). Unit test added: `OpenTimeCommitUserDataTest`.
- Verified: `embeddingFingerprintStored` is NOT affected — it reads from
  `EmbeddingCompatibilityController.storedFingerprint()` (AtomicReference),
  populated via `latestCommitUserDataBestEffort` only after state=RUNNING.

---

## Item 5 Root Cause: State-Gate Bug in `latestCommitUserDataBestEffort()`

The open-time snapshot infrastructure (`openTimeCommitUserData`) already
existed in `LuceneLifecycleManager` and `safeSchemaFingerprintStored()`
already read from it. However, **the snapshot always captured an empty
map** because `latestCommitUserDataBestEffort()` requires state=RUNNING
but the capture in `startInternal()` runs during state=STARTING:

```
start():    ctx.state = STARTING
startInternal():
  applyComponents()    ← directory ready
  openTimeCommitUserData = latestCommitUserDataBestEffort()  ← returns empty!
start():    ctx.state = RUNNING  ← too late
```

**Fix:** Added `RuntimeState.STARTING` to the allowed states in
`latestCommitUserDataBestEffort()`. One-line change. The method body is
safe during STARTING because `ctx.snapshot` (directory) is set by
`applyComponents()` immediately before the capture. No other code path
calls this method during STARTING (the state lock prevents concurrent
`start()` calls).

**Verified:** `embeddingFingerprintStored` is NOT affected — it reads from
`EmbeddingCompatibilityController.storedFingerprint()` (in-memory
`AtomicReference`, populated after state=RUNNING).

**Unit test:** `OpenTimeCommitUserDataTest` — seeds an index with a bogus
`index_schema_fp`, re-opens with the real SSOT source, commits new data,
asserts the open-time snapshot retains the original bogus fingerprint.

---

## Investigation Log

- 2026-04-09: Created tempdoc. Investigation from 380's critical analysis carried over. 4 SearchHit fields confirmed NOT mapped to HUDResultItem (mimeBase, fileKind, highlights, provenance — all legitimately consumed-not-surfaced except provenance which is a deliberate gap). Stale fixture confirmed orphaned (no test references). Workflow docs confirmed missing Zod step.
- 2026-04-09: All 3 items implemented.
  - Item 1: Added second mapper layer propagation test (`resultMapper.test.ts`). 16 field mappings verified. `modifiedAt` required numeric timestamp string (ISO strings return `undefined` from `formatRelativeTime` — pre-existing formatter limitation, not a bug from 380). Sparse hit test added. 2 new tests (205 total).
  - Item 2: Deleted stale `app-api/src/test/resources/contract/status-response-live.json`. Java `app-api:test` passes.
  - Item 3: Added Zod schema step to "Add a field to an API record" workflow in `common-workflows.md` (new step 8) and `agent-guide.md` (new step 7). Ran `llmstxt-generate.mjs` and `skills-sync.mjs` per docs-maintenance hook.
  - Verification: 205/205 frontend tests pass. Java tests pass. Typecheck clean. Full build passes.
  - **Discovered issue (pre-existing, out of scope):** `formatRelativeTime` in `resultMapper.ts:15-18` calls `Number()` on the `modifiedAt` string. The backend sends ISO date strings (`"2025-06-15T10:30:00Z"`), not numeric epoch milliseconds. `Number()` on an ISO string returns `NaN`, so `modifiedAt` on `HUDResultItem` is silently `undefined` for every search result. The propagation test uses a numeric timestamp string to verify the mapping path exists — the formatter bug is a separate issue. This should be filed as a standalone bug fix.
- 2026-04-09: **Item 4 implemented** — complete @JsonUnwrapped removal.
  - Backend: Removed `@JsonUnwrapped` from `WorkerOperationalView worker` on `StatusResponse.java`, all 9 annotations on `WorkerOperationalView.java`, and `ChunkCoverageView chunk` on `EnrichmentProgressView.java`. Wire format now nests all worker data under `"worker"` key with sub-records (`core`, `failure`, `migration`, `compatibility`, `queueDb`, `enrichment`, `gpu`, `vectorFormat`, `telemetry`, `searchConfig`). Top-level properties reduced from 115 to 26.
  - Java tests: Updated `ContractValidation` assertions (3 flat-vs-grouped comparisons updated to use `worker` paths). Updated `updateSchemas` to regenerate fixtures and JSON Schema baselines. SSOT field completeness test still passes.
  - Frontend Zod: Rewrote `SystemStatusSchema` with `WorkerViewSchema` containing sub-record schemas (`CoreIndexSchema`, `FailureTrackingSchema`, `MigrationGenerationSchema`, `EnrichmentProgressSchema`, `GpuDiagnosticsSchema`, `TelemetryMetricsSchema`, `SearchConfigSchema`). 330 §4 grouped objects remain at top level.
  - Frontend store: `useSystemStore.ts` mapping flattens `validated.worker` sub-records into flat `SystemStatus` via property access (`w?.core?.indexHealthy`, `w?.failure?.failedJobs`, etc.). `SystemStatus` interface unchanged — components don't need any changes.
  - jseval: Updated `_snapshot_models()` in `run.py` to read GPU diagnostics and compatibility from `worker.gpu` and `worker.compatibility` instead of flat top-level fields.
  - Mock fixtures: `fixtures.mjs` restructured with nested `worker` object. `fixtures.test.ts` assertions updated.
  - Contract test: Updated top-level field assertions to check `worker` key with sub-records.
  - Verification: 205/205 frontend tests pass. Java `app-api:test` passes. Typecheck clean. Full build passes. Spotless clean.
  - Fixed stale Javadoc on `EmbeddingStatusGroup.java` that referenced removed `@JsonUnwrapped` pattern.
  - Confirmed: zero `@JsonUnwrapped` annotations remain in `app-api` production code.
- 2026-04-09: **Zod coverage test implemented** — the last deferred prevention mechanism from tempdoc 380.
  - JSON Schema now has 28 top-level properties (down from 115). Zod declares 21. Allowlist: 7 entries (25% — acceptable). Added `knowledgeServerStartError` to Zod schema (was read by store mapping but not validated).
  - Test loads `status-response.schema.json`, extracts property names, compares against `Object.keys(SystemStatusSchema.shape)`, fails on any property not in Zod or allowlist.
  - 206/206 frontend tests pass.
- 2026-04-16: **Item 5 implemented** — `SchemaMismatchStatusContractTest`
  broken by three compounding issues. All three fixed:
  (1) ConfigStore init via `TestResolvedConfigHelper.storeFromEnvironment()`.
  (2) JSON paths updated for nested `schema.*` format.
  (3) State-gate bug: added `STARTING` to allowed states in
  `latestCommitUserDataBestEffort()` (one-line fix). Initial implementation
  used an inline `DirectoryReader.open()` bypass (12 lines); simplified to
  the one-liner after critical analysis confirmed STARTING is safe for this
  method. Unit test: `OpenTimeCommitUserDataTest` (seeds bogus FP, verifies
  immutability after re-open + commit). Verified embedding path unaffected
  (uses `EmbeddingCompatibilityController` with its own AtomicReference).
