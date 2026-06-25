---
title: "330: Worker State Accuracy & Observability"
type: tempdoc
status: done
created: 2026-03-21
updated: 2026-03-21
---

> NOTE: Noncanonical doc (architecture). May drift.

# 330: Worker State Accuracy & Observability

## Purpose

Fix cases where the Worker's reported state (via `/api/status` and gRPC
health) doesn't match actual state, and improve the tools available for
inspecting Worker internals during development and debugging.

## Origin

See tempdoc 312 §Retrospective. During item 20 live verification,
`/api/status` reported `embeddingFingerprintStored: ""` and
`embeddingReady: false` despite the fingerprint being present in Lucene
commit metadata and the embedding service being functional. This caused
wasted investigation cycles diagnosing phantom problems.

## Work Items

### State accuracy

#### 1. ECC `storedFingerprint` goes stale on fresh-index first-commit path — DONE

**Bug:** On a fresh/empty index, `ECC.refresh()` takes the `docs == 0`
early return, sets state=COMPATIBLE but leaves `storedFingerprint` as
null. The first Lucene commit stamps the fingerprint via
`EmbeddingMetadataOverlay`, but only the REBUILDING completion path
(IndexingLoop:426) calls `onFingerprintStamped()`. The other three
commit sites (idle, time/buffer, shutdown) never notify the ECC.

**Fix:**
- Added `ECC.refreshStoredFingerprintAfterCommit()` — re-reads the
  stored fingerprint from Lucene commit metadata and updates the cached
  value if it changed. Cheap (one map lookup), only does work when stale.
- Called from all three non-rebuild commit sites in IndexingLoop
  (idle:505, time/buffer:621, shutdown:668) via private helper
  `refreshEccStoredFingerprint()`.

**Files changed:**
- `modules/worker-core/.../embed/EmbeddingCompatibilityController.java`
- `modules/worker-services/.../loop/IndexingLoop.java`

#### 2. Late-bind the embedding provider in GrpcHealthService — DONE

**Bug:** `DefaultWorkerAppServices` constructs `GrpcHealthService` with
null `embeddingProvider` (replaced by `NoOpEmbeddingProvider.INSTANCE`).
`wireEmbeddingProvider()` updates IndexingLoop and GrpcSearchService but
never GrpcHealthService. `embeddingReady: false` forever for ONNX users.

**Fix:**
- Made `embeddingProvider` field volatile with a setter.
- Added `healthService.setEmbeddingProvider(provider)` to
  `DefaultWorkerAppServices.wireEmbeddingProvider()`.

**Files changed:**
- `modules/worker-services/.../services/GrpcHealthService.java`
- `modules/worker-services/.../server/DefaultWorkerAppServices.java`

### Observability

#### 3. Debug endpoint for Lucene commit metadata — DONE

**Problem:** No way to inspect the full raw Lucene commit metadata map.

**Fix:**
- Added `map<string, string> commit_user_data = 111` to `StatusResponse`
  protobuf.
- Populated from `latestCommitUserDataBestEffort` in
  `IndexStatusOps.buildStatusResponse()`.
- Added `RemoteKnowledgeClient.getCommitMetadata()` (extracts the map
  from existing `indexStatus` RPC — no new RPC needed).
- Added `DebugStateController.handleGetCommitMetadata()` handler with
  503/502 error handling.
- Registered `GET /api/debug/commit-metadata` in `DebugRoutes`.

**Files changed:**
- `modules/ipc-common/.../proto/indexing.proto`
- `modules/worker-services/.../services/IndexStatusOps.java`
- `modules/app-services/.../worker/RemoteKnowledgeClient.java`
- `modules/ui/.../api/DebugStateController.java`
- `modules/ui/.../api/routes/DebugRoutes.java`

#### 4. ~~Structure the status endpoint into subsystem groups~~ — MOVED

Extracted to **tempdoc 331** (backlog). The Head-side `/api/status` is
already structured; the flatness is in the internal gRPC proto only.
See 331 for trigger criteria.

#### 5. Standalone Worker test harness — DONE

**Problem:** No way to start a Worker without the Head process.

**Fix:** Added `runWorkerStandalone` Gradle task in
`modules/indexer-worker/build.gradle.kts` that:
- Generates a minimal config snapshot JSON with path-dependent keys
  (dataDir, modelsDir, collection, repoRoot, ssotPath).
- Launches `IndexerWorker` directly with the snapshot via
  `-Djustsearch.worker.config_snapshot`.
- Forwards model-related env vars and auto-detects CUDA DLLs.
- Accepts `-PdataDir`, `-PmodelsDir`, `-Pcollection` properties.
- Supports `-Pstandalone.console=true` for console logging.
- Configuration-cache compatible.

**Smoke tested:** Worker starts on ephemeral port, loads all models
(embedding, SPLADE, NER, disambiguation), enters idle loop without Head.

**Files changed:**
- `modules/indexer-worker/build.gradle.kts`

## Verification

- `./gradlew.bat spotlessApply` — pass
- `./gradlew.bat build -x test -x integrationTest` — pass
- `./gradlew.bat :modules:worker-services:test` — pass
- `./gradlew.bat :modules:app-services:test` — pass
- `./gradlew.bat :modules:ui:test` — pass
- `./gradlew.bat :modules:worker-core:test` — 2 pre-existing failures
  in `EmbeddingCompatibilityControllerTest` (also fail on main)
- `./gradlew.bat :modules:app-services:integrationTest` — 9 pre-existing
  failures (environment-dependent, also fail on main)
- Standalone Worker smoke test — Worker starts and serves on port 54702

## Related

- **329 (Head→Worker Config Pipeline):** Config forwarding fixes.
- **312 (Primary Indexing Throughput):** Origin of the findings.
- **324 (IndexingLoop/KnowledgeServer Cleanup):** Related but separate.

## Non-Goals

- Rewriting KnowledgeServer initialization (tempdoc 324).
- Changing the Head/Worker process architecture.
- Restructuring the gRPC protobuf (see item 4 deferral rationale).
