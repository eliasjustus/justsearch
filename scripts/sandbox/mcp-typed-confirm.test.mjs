/**
 * Regression test for the PENDING_ID resolution defect in mcp-typed-confirm.mjs
 * (found live-verified twice: the driver's own author checked it only against a
 * synthetic HTTP server replicating the wire shape, never against the real
 * record shape a running dev stack actually emits).
 *
 * The bug: findPendingId(node, wantOperationId) only returns a pendingId when
 * `!wantOperationId || !extras.operationId || extras.operationId === wantOperationId`
 * (mcp-typed-confirm.mjs). The production advisory record's classExtras.operationId
 * is the WIRE operation id ("core.ingest-files" -- see
 * modules/app-observability/.../PendingAuthorizationAdvisoryProjector.java:70,
 * which stamps `event.operationId()`, and McpToolSurface.java:343, which
 * dispatches the "justsearch_ingest" MCP tool via
 * `callOperation("core.ingest-files", ...)`). The tool name and the wire
 * operation id are DIFFERENT strings. Calling resolvePendingId with the tool
 * name as wantOperationId (as the script did before this fix) means the
 * operationId comparison never matches on a real record, so findPendingId falls
 * through its "keep recursing" branch and returns null on every real run --
 * exactly the "WARN: could not resolve pendingId" every PASS run printed.
 *
 * This test builds fixtures shaped like the REAL wire records (not a synthetic
 * HTTP replica) and asserts against the two SSE frame shapes the docstring in
 * mcp-typed-confirm.mjs itself claims to handle: the LIFECYCLE snapshot-on-
 * subscribe frame (`{ advisories: [AdvisoryRecord, ...] }`,
 * AdvisoryStreamController.snapshotExtras) and a live UPDATE frame (the
 * payload IS a single AdvisoryRecord, per AdvisoryRecord.java's field shape:
 * classId/id/occurredAt/renderHint/.../classExtras).
 *
 * Run: `node scripts/sandbox/mcp-typed-confirm.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';

import { findPendingId, INGEST_OPERATION_ID } from './mcp-typed-confirm.mjs';

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (error) {
    failures.push(error.message);
  }
};

// A record shaped exactly like PendingAuthorizationAdvisoryProjector.project()
// + AdvisoryRecord.fromProjection() produce it: classExtras.operationId is the
// WIRE operation id ("core.ingest-files"), never the MCP tool name
// ("justsearch_ingest").
function realIngestAdvisoryRecord(pendingId) {
  return {
    classId: 'authorization.pending',
    id: 'evt-1',
    occurredAt: '2026-07-15T00:00:00Z',
    renderHint: 'toast',
    diagnosticsLink: null,
    provenance: null,
    primaryAction: null,
    primaryActionKind: null,
    bodyI18nKey: null,
    classExtras: {
      pendingId,
      operationId: 'core.ingest-files',
      riskTier: 'DESTRUCTIVE',
      gateBehavior: 'TYPED_CONFIRM',
    },
  };
}

// 1. The constant the fix introduced must be the WIRE id, not the tool name --
//    locks in the McpToolSurface.java:343 mapping so a future rename of either
//    side is caught here instead of silently reverting to the bug.
{
  ok('INGEST_OPERATION_ID is the wire operation id, not the MCP tool name', INGEST_OPERATION_ID === 'core.ingest-files');
  ok('INGEST_OPERATION_ID is not the MCP tool name (today\'s bug)', INGEST_OPERATION_ID !== 'justsearch_ingest');
}

// 2. THE regression test: a real single-record UPDATE-frame payload, matched
//    against the WIRE operation id, must resolve. This is the exact shape a
//    live dev-stack run produces and the exact call this script now makes
//    (mcp-typed-confirm.mjs's resolvePendingId(port, INGEST_OPERATION_ID, ...)).
{
  const record = realIngestAdvisoryRecord('pending-abc-123');
  const found = findPendingId(record, INGEST_OPERATION_ID);
  ok('resolves pendingId from a real record against the WIRE operationId', found === 'pending-abc-123');
}

// 3. The documented bug, pinned down: matching against the MCP TOOL NAME
//    ("justsearch_ingest") instead of the wire id never resolves against a
//    real record -- this is precisely why every PASS run printed the WARN.
//    A regression back to the tool name as wantOperationId must fail this.
{
  const record = realIngestAdvisoryRecord('pending-abc-123');
  const found = findPendingId(record, 'justsearch_ingest');
  ok('matching against the MCP tool name (the old bug) does NOT resolve', found === null);
}

// 4. The LIFECYCLE snapshot-on-subscribe shape: `{ advisories: [...] }`
//    (AdvisoryStreamController.snapshotExtras puts the ring-buffer replay
//    under an "advisories" key). The driver connects AFTER the gate already
//    fired, so this is the shape it actually receives in practice.
{
  const snapshot = { advisories: [realIngestAdvisoryRecord('pending-snapshot-1')] };
  const found = findPendingId(snapshot, INGEST_OPERATION_ID);
  ok('resolves pendingId nested under a LIFECYCLE snapshot\'s "advisories" array', found === 'pending-snapshot-1');
}

// 5. Multiple pending advisories in the same snapshot (AdvisoryLog.recent()
//    replays several past events) -- the filter must pick the one whose
//    operationId matches, not just the first pending in the list. This is
//    the concrete justification for keeping wantOperationId as a real filter
//    instead of dropping it outright: an unfiltered scan would return
//    whichever pending happens to be recursed into first.
{
  const unrelated = {
    classId: 'authorization.pending',
    id: 'evt-0',
    occurredAt: '2026-07-15T00:00:00Z',
    renderHint: 'toast',
    diagnosticsLink: null,
    provenance: null,
    primaryAction: null,
    primaryActionKind: null,
    bodyI18nKey: null,
    classExtras: {
      pendingId: 'pending-unrelated-browse',
      operationId: 'core.browse-folders',
      riskTier: 'LOW',
      gateBehavior: 'TYPED_CONFIRM',
    },
  };
  const snapshot = { advisories: [unrelated, realIngestAdvisoryRecord('pending-correct-ingest')] };
  const found = findPendingId(snapshot, INGEST_OPERATION_ID);
  ok('picks the matching pending among multiple, not merely the first', found === 'pending-correct-ingest');
}

// 6. Negative control precision check: without ANY operationId filter, the
//    unrelated pending (listed first) would be returned instead -- proves
//    case 5 passes BECAUSE of the operationId match, not by accident of
//    fixture ordering.
{
  const unrelated = {
    classId: 'authorization.pending',
    id: 'evt-0',
    occurredAt: '2026-07-15T00:00:00Z',
    renderHint: 'toast',
    diagnosticsLink: null,
    provenance: null,
    primaryAction: null,
    primaryActionKind: null,
    bodyI18nKey: null,
    classExtras: {
      pendingId: 'pending-unrelated-browse',
      operationId: 'core.browse-folders',
      riskTier: 'LOW',
      gateBehavior: 'TYPED_CONFIRM',
    },
  };
  const snapshot = { advisories: [unrelated, realIngestAdvisoryRecord('pending-correct-ingest')] };
  const foundUnfiltered = findPendingId(snapshot, undefined);
  ok(
    'control: with no operationId filter, the FIRST pending wins (proves case 5 is not a fixture-ordering accident)',
    foundUnfiltered === 'pending-unrelated-browse',
  );
}

if (failures.length > 0) {
  console.error(`mcp-typed-confirm.test: FAIL (${failures.length}/${passed + failures.length})`);
  for (const f of failures) console.error(`- ${f}`);
  process.exit(1);
}
console.log(`mcp-typed-confirm.test: OK (${passed} assertions)`);
