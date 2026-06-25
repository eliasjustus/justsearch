/**
 * Tempdoc 583 §D.3d — unit tests for the typed FE API-client codegen.
 *
 * `renderClient(manifest)` is a pure projection (manifest object → TS source), so the generation law
 * is tested with a fixture, no live backend and no filesystem.
 *
 * Run with: `node scripts/codegen/gen-api-client.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { renderClient } from './gen-api-client.mjs';

let passed = 0;
const failures = [];

function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

const FIXTURE = {
  schemaVersion: '1.0',
  count: 3,
  routes: [
    { method: 'POST', path: '/api/knowledge/search', cohort: 'knowledge', requiredCapabilities: ['WORKER'] },
    { method: 'GET', path: '/api/status', cohort: 'observability', requiredCapabilities: [] },
    { method: 'POST', path: '/api/chat/agent', cohort: 'agent', requiredCapabilities: ['WORKER', 'INFERENCE'] },
  ],
};

run('emits a typed route table keyed by "<METHOD> <path>" with capabilities', () => {
  const ts = renderClient(FIXTURE);
  assert.match(ts, /export const API_ROUTES = \{/);
  assert.match(ts, /"GET \/api\/status": \{ method: "GET", path: "\/api\/status", cohort: "observability", requiredCapabilities: \[\] \}/);
  assert.match(ts, /"POST \/api\/chat\/agent":.*requiredCapabilities: \["WORKER", "INFERENCE"\]/);
  assert.match(ts, /export type ApiRouteKey = keyof typeof API_ROUTES/);
  assert.match(ts, /export function apiPath\(key: ApiRouteKey\): string/);
  assert.match(ts, /satisfies Record<string, ApiRoute>/);
});

run('routes are sorted by key (deterministic output)', () => {
  const ts = renderClient(FIXTURE);
  const iAgent = ts.indexOf('"POST /api/chat/agent"');
  const iSearch = ts.indexOf('"POST /api/knowledge/search"');
  const iStatus = ts.indexOf('"GET /api/status"');
  assert.ok(iStatus < iAgent && iAgent < iSearch, 'keys appear in sorted order');
});

run('a duplicate method+path is de-duplicated, not emitted twice', () => {
  const dup = { schemaVersion: '1.0', routes: [...FIXTURE.routes, FIXTURE.routes[1]] };
  const ts = renderClient(dup);
  const occurrences = ts.split('"GET /api/status":').length - 1;
  assert.equal(occurrences, 1, 'GET /api/status appears exactly once');
});

run('an empty manifest still produces a valid (empty) table', () => {
  const ts = renderClient({ schemaVersion: '1.0', routes: [] });
  assert.match(ts, /export const API_ROUTES = \{\n\n\} as const/);
  assert.match(ts, /export type ApiRouteKey = keyof typeof API_ROUTES/);
});

if (failures.length > 0) {
  console.error(`gen-api-client.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`gen-api-client.test: all ${passed} checks passed`);
