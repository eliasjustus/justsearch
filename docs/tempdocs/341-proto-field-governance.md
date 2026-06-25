---
title: "341: StatusResponse Decomposition"
type: tempdoc
status: done
created: 2026-03-22
updated: 2026-03-22
supersedes: [331, 336]
---

> NOTE: Noncanonical doc (architecture). May drift.

# 341: StatusResponse Decomposition

## Problem

`StatusResponse` in `indexing.proto` was a single flat message with 116
fields spanning 12+ subsystems. Field numbers were declared out of order
(e.g., field 111 sat between 86 and 97 in the file), making "next
available" hard to determine by inspection. Adding fields 111/112 for
NER model path collided with existing `commit_user_data` (111) and
`enrichment_embedding_completed` (112). The proto compiler caught it,
but only after a build attempt.

The Java consumer was equally flat: `WorkerOperationalView` was a record
with 70 positional constructor arguments. Adding any field broke every
call site — `fallback()`, `WorkerStatusMapper`, and 3 test files.

## Supersedes

- **331** (Status Proto Restructuring) — proto decomposition scope.
- **336** (Status Response Composability) — Java record decomposition.

## Implementation Summary

### Proto (Phase 1)

Replaced 116 flat fields with 10 nested sub-messages, each owning its
own field number space. Reusable types: `FeatureCoverage`,
`ChunkCoverage`, `OrtCudaProbeResult`, `EnumeratorProgress`. Every
sub-message has `// Next available field number: N`.

```protobuf
message StatusResponse {
  CoreStatus core = 1;
  FailureStatus failure = 2;
  MigrationStatus migration = 3;
  CompatibilityStatus compatibility = 4;
  QueueDbHealth queue_db = 5;
  EnrichmentCoverage enrichment = 6;
  GpuDiagnostics gpu = 7;
  VectorQuantization vector_quantization = 8;
  TelemetryStatus telemetry = 9;
  map<string, string> commit_user_data = 10;
}
```

### Builder (Phase 1)

Split `IndexStatusOps.buildStatusResponse()` (340-line monolith) into
10 focused per-subsystem helpers: `buildCore`, `buildFailure`,
`buildMigration`, `buildCompatibility`, `buildQueueDb`,
`buildEnrichment`, `buildGpu`, `buildVectorQuantization`,
`buildTelemetry`, `safeCommitUserData`.

### Consumers (Phase 2)

Updated all StatusResponse consumers to read nested proto sub-messages:
`WorkerStatusMapper`, `KnowledgeHttpApiAdapter`, `VduOps`,
`GplEvalSnapshot`, `RemoteKnowledgeClient`, `AppFacadeBootstrap`,
`GrpcTestClient`, and 13 test/integration/system-test files.

### Java View (Phase 3 + fixes)

Decomposed `WorkerOperationalView` (70 positional args) into 11
sub-records:

| Sub-record | Fields | Mirrors proto |
|------------|--------|---------------|
| `CoreIndexView` | 6 | `CoreStatus` |
| `FailureTrackingView` | 7 | `FailureStatus` |
| `MigrationGenerationView` | 19 | `MigrationStatus` |
| `CompatibilityStatusView` | 9 | `CompatibilityStatus` |
| `QueueDbStatusView` | 5 | `QueueDbHealth` |
| `EnrichmentProgressView` | 16 (+ nested `ChunkCoverageView`) | `EnrichmentCoverage` |
| `GpuDiagnosticsView` | 9 | `GpuDiagnostics` |
| `VectorFormatView` | 5 | `VectorQuantization` |
| `TelemetryMetricsView` | 5 | `TelemetryStatus` |
| `ChunkCoverageView` | 6 | `ChunkCoverage` |
| `FeatureCoverageView` | 5 | `FeatureCoverage` |

`WorkerOperationalView` is now an 11-field record (9 sub-records +
2 nullable Booleans) with one `fallback()` factory. No convenience
accessor methods — callers use sub-records directly (e.g.,
`view.compatibility().embeddingCompatState()`).

The `*Group.from()` factories (`EmbeddingStatusGroup`,
`SchemaStatusGroup`, etc.) read from sub-records directly.

### Frontend (Phase 4a-c)

- Removed dead `toMap()` bridge (130 lines) and `getStatusMapForUi()`
- Store reads grouped sub-objects first with flat fallbacks and
  type-appropriate defaults (`?? ''`, `?? false`, `?? 0`)
- Components (`BrainCompatibilitySection`, `HealthView`,
  `deriveHealthEvents`, `StatusDeck`) migrated to grouped field paths

### Tests

- Value-correctness test verifying flat `@JsonUnwrapped` fields match
  grouped sub-object values in serialized `StatusResponse`
- All unit tests pass (app-api, app-services, ui, worker-services,
  indexer-worker, frontend 184 tests)

## Remaining (deferred)

### 4d. Frontend cleanup: remove flat-field remnants

- [ ] Remove flat fields from `SystemStatus` type in `systemTypes.ts`
- [ ] Remove flat field schemas from `SystemStatusSchema` in `schemas.ts`
- [ ] Remove flat-field mapping lines + fallbacks from `useSystemStore.ts`

### 4e. Backend: drop `@JsonUnwrapped`

- [ ] Remove `@JsonUnwrapped` from `WorkerOperationalView` in
      `StatusResponse.java` — grouped sub-objects remain
- [ ] Remove `*Group` records if sub-records fully replace them
- [ ] Verify: full test suite + frontend e2e

**4d-4e deferred**: The grouped-first reads (4b-4c) use fallback to
flat fields as a safety net. Removing the flat fields is a breaking
change that should happen after grouped reads are verified in production.

## Related

- **330** (Worker State Accuracy): completed. Added grouped JSON
  sub-objects that make this refactor's JSON story safe.
- **333** (Status State Provenance): added `observedAtMs` per group.
- **334** (Pipeline Throughput): added NER fields that triggered the
  original field collision.

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

StatusResponse decomposition follow-on to 330 (annotated as completed in body) + 333. Design captured; the grouped sub-object pattern is in production per the canonical status surface.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

