// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 662 — the FE/BE `streamId` discriminators for the 5 streams multiplexed onto
 * `/api/shell-events/stream`. MUST match the backend `StreamId` constants verbatim (a
 * mismatch is a silently-dropped consumer — `MultiplexedStream.routeFrame` warns in dev mode
 * but the FE has no compile-time cross-language check, so this is the ONE place to update
 * either side):
 *
 *  - `INTENT`            ↔ `IntentEnvelopeChangeRegistry.STREAM_ID`
 *      (`modules/app-observability/.../intent/IntentEnvelopeChangeRegistry.java`)
 *  - `ADVISORY_OPERATION_COMPLETED` ↔ `AdvisoryChangeRegistry.streamIdFor(OperationCompletionProjector.CLASS_ID)`
 *  - `ADVISORY_HEALTH_RECOVERABLE`  ↔ `AdvisoryChangeRegistry.streamIdFor(HealthRecoveryProjector.CLASS_ID)`
 *      (`modules/app-observability/.../advisory/AdvisoryChangeRegistry.java` — `"surface:advisory-" + classId`)
 *  - `ACTION_LEDGER`      ↔ `ActionLedgerChangeRegistry.STREAM_ID`
 *      (`modules/app-observability/.../ledger/ActionLedgerChangeRegistry.java`)
 *  - `INDEXING_JOBS`      ↔ `IndexingJobsChangeRegistry.STREAM_ID`
 *      (`modules/app-observability/.../indexing/IndexingJobsChangeRegistry.java`)
 */
export const SHELL_EVENT_STREAM_IDS = {
  INTENT: 'system:intent-envelopes',
  ADVISORY_OPERATION_COMPLETED: 'surface:advisory-operation-completed',
  ADVISORY_HEALTH_RECOVERABLE: 'surface:advisory-health-recoverable',
  ACTION_LEDGER: 'surface:action-ledger',
  INDEXING_JOBS: 'surface:indexing-jobs',
} as const;

/** Path of the multiplexed endpoint these streamIds are demuxed from. */
export const SHELL_EVENTS_STREAM_PATH = '/api/shell-events/stream';

/**
 * `AdvisoryStore` discovers advisory Resources DYNAMICALLY from the Resource Catalog
 * (`KIND_ADVISORY`) rather than hardcoding the 2 known classes — so unlike the other 4
 * migrated consumers, it cannot reference a `SHELL_EVENT_STREAM_IDS` constant directly; it
 * only has each Resource's `endpoint` (the SSE URL path, e.g.
 * `/api/advisory/operation-completed/stream`), which is NOT the same string as the backend
 * `streamId` (`surface:advisory-operation-completed`). This is the one place that translates
 * between them. Returns `null` for an endpoint this map doesn't recognize (e.g. a FUTURE
 * third advisory class added before this map is updated) — `AdvisoryStore` falls back to its
 * own direct `EnvelopeStream` for any unrecognized endpoint, so a new advisory class is never
 * silently dropped; it just doesn't get the multiplexing benefit until this map is extended.
 */
export function advisoryEndpointToShellEventStreamId(endpoint: string): string | null {
  switch (endpoint) {
    case '/api/advisory/operation-completed/stream':
      return SHELL_EVENT_STREAM_IDS.ADVISORY_OPERATION_COMPLETED;
    case '/api/advisory/health-recoverable/stream':
      return SHELL_EVENT_STREAM_IDS.ADVISORY_HEALTH_RECOVERABLE;
    default:
      return null;
  }
}
