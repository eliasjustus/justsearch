---
title: Health Readiness Contract v1
type: contract
status: stable
updated: 2026-06-20
description: Additive typed readiness envelope for /api/status with legacy boolean aliases.
---

# Health Readiness Contract v1

## Scope

This contract defines `/api/status.readiness` semantics and migration rules.
`/api/health` lifecycle semantics remain unchanged.

## Canonical Surface

`GET /api/status` includes additive field:

```json
{
  "readiness": {
    "schemaVersion": 1,
    "observedAt": "2026-02-19T08:00:00Z",
    "components": {
      "workerControlPlane": { "state": "READY", "reasonCode": null, "source": "lifecycle_snapshot", "observedAt": "...", "stale": false, "stalenessMs": 0 },
      "indexServing": { "state": "READY", "reasonCode": null, "source": "worker_status", "observedAt": "...", "stale": false, "stalenessMs": 0 },
      "ai": { "state": "DEGRADED", "reasonCode": "inference.offline", "source": "lifecycle_inference", "observedAt": "...", "stale": false, "stalenessMs": 0 },
      "embedding": { "state": "UNKNOWN", "reasonCode": "worker.health.embedding_probe_missing", "source": "worker_health_check", "observedAt": "...", "stale": false, "stalenessMs": 0 },
      "visualTextExtraction": { "state": "READY", "reasonCode": null, "source": "worker_status", "observedAt": "...", "stale": false, "stalenessMs": 0 },
      "visualDocumentUnderstanding": { "state": "READY", "reasonCode": null, "source": "head_vdu_status", "observedAt": "...", "stale": false, "stalenessMs": 0 }
    },
    "composites": {
      "retrieval": { "state": "READY", "reasonCodes": [] },
      "aiFeatures": { "state": "UNKNOWN", "reasonCodes": ["inference.offline", "worker.health.embedding_probe_missing"] }
    }
  }
}
```

## Typed States

Allowed values:
1. `READY`
2. `DEGRADED`
3. `NOT_READY`
4. `NOT_CONFIGURED`
5. `UNKNOWN`

Interpretation:
1. `READY`: dependency is serving for expected path.
2. `DEGRADED`: serving but with known reduced capability.
3. `NOT_READY`: expected dependency exists but is currently unavailable/failing.
4. `NOT_CONFIGURED`: dependency intentionally absent in this runtime configuration.
5. `UNKNOWN`: status cannot be established (for example missing probe signal).

## Migration and Compatibility

1. `aiReady` and `embeddingReady` remain exposed as legacy aliases.
2. New consumers must prefer `readiness.components.ai.state` and `readiness.components.embedding.state`.
3. `aiReady` is derived from canonical AI readiness (`state == READY`).
4. `embeddingReady` is derived from canonical embedding readiness (`state == READY`).
5. Legacy aliases can be removed only after a versioned contract migration with dual-read window.

## State Mapping Rules (v1)

1. Worker lifecycle (`/api/health` component state) maps to `readiness.components.workerControlPlane.state`.
2. Worker index status maps to `readiness.components.indexServing.state`.
3. Inference lifecycle state maps to `readiness.components.ai.state` with source `lifecycle_inference`.
4. Worker embedding probe maps to `readiness.components.embedding.state` with source `worker_health_check`.
5. Worker visual extraction status maps missing baseline readable visual text to `readiness.components.visualTextExtraction` with source `worker_status`.
6. Head VDU capability status maps enrichment-only visual understanding blockers to `readiness.components.visualDocumentUnderstanding` with source `head_vdu_status`.
7. OCR and VDU blockers degrade `retrieval` only while baseline visual text is still missing. VDU enrichment-only blockers degrade `aiFeatures`, not `retrieval`.
8. Missing embedding probe boolean maps to `UNKNOWN` with reason code:
- `worker.health.embedding_probe_missing`
9. Composite state precedence:
- `NOT_READY`
- `UNKNOWN`
- `NOT_CONFIGURED`
- `DEGRADED`
- `READY`

## Reason Code Taxonomy (v1)

Common reason codes:
1. `worker.not_configured`
2. `worker.not_started`
3. `worker.starting`
4. `worker.unavailable`
5. `index.not_healthy`
6. `inference.starting`
7. `inference.offline`
8. `worker.health.embedding_not_ready`
9. `worker.health.embedding_probe_missing`
10. `worker.status_missing`
11. `ocr.disabled`
12. `ocr.engine_missing`
13. `ocr.language_missing`
14. `vdu.ai_offline`
15. `vdu.insufficient_vram`
16. `vdu.missing_mmproj`
17. `vdu.circuit_open`

Worker `health_check.ai_ready` remains worker-local telemetry and is non-authoritative for governance readiness.

## Staleness Semantics

For each readiness component:
1. `observedAt` is required and ISO-8601.
2. `stale` is a boolean freshness flag.
3. `stalenessMs` is non-negative age since last confirmed source update.

v1 behavior uses `stale=false`, `stalenessMs=0` for current status snapshots.

## Non-Goals

1. Do not change `/api/health` HTTP status mapping.
2. Do not remove legacy readiness booleans in v1.
3. Do not introduce breaking schema changes on existing status fields.
