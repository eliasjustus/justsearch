/**
 * Tests for the install-API contract-doc gate (scripts/ci/check-install-api-contract.mjs).
 *
 * A gate that cannot fail is worse than no gate — it reads as coverage. These exercise the BITE:
 * a route bound but undocumented, a route documented in prose without its method, and the
 * degenerate "matcher found nothing" case that must not pass silently.
 *
 * Run: `node scripts/ci/check-install-api-contract.test.mjs` (exits non-zero on failure)
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';

import { run, registeredInstallRoutes, undocumentedRoutes, ROUTES_FILE, DOC_FILE } from './check-install-api-contract.mjs';

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

function makeFixture({ routes, doc }) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'install-api-gate-'));
  const routesPath = path.join(root, ROUTES_FILE);
  const docPath = path.join(root, DOC_FILE);
  fs.mkdirSync(path.dirname(routesPath), { recursive: true });
  fs.mkdirSync(path.dirname(docPath), { recursive: true });
  fs.writeFileSync(routesPath, routes);
  fs.writeFileSync(docPath, doc);
  return root;
}

const TWO_ROUTES = `
    app.get("/api/ai/install/status", aiInstallController::handleGetStatus);
    app.post("/api/ai/install/repair", aiInstallController::handleRepair);
`;

// --- parsing ---------------------------------------------------------------
ok(
  'both bound routes are recognised, method-qualified',
  JSON.stringify(registeredInstallRoutes(TWO_ROUTES))
    === JSON.stringify(['GET /api/ai/install/status', 'POST /api/ai/install/repair']),
);
ok(
  'routes outside the install prefix are ignored',
  registeredInstallRoutes('app.get("/api/ai/runtime/status", h);').length === 0,
);

// --- bite ------------------------------------------------------------------
{
  const root = makeFixture({
    routes: TWO_ROUTES,
    doc: '| `/api/ai/install/status` | GET | – | `AiInstallStatus` | – |\n',
  });
  const errors = run(root);
  ok('an undocumented route fails the gate', errors.length === 1);
  ok('…and the message names it', errors[0].includes('POST /api/ai/install/repair'));
}

{
  // The path appears, but only in prose with no method — the row that documents it must say which
  // verb it answers, or a caller still cannot use it (round 16's actual problem was POST vs GET).
  const root = makeFixture({
    routes: TWO_ROUTES,
    doc: 'The `/api/ai/install/status` and `/api/ai/install/repair` endpoints exist.\n',
  });
  const errors = run(root);
  ok('a path mentioned without its method still fails', errors.length === 2);
}

{
  const root = makeFixture({
    routes: TWO_ROUTES,
    doc: [
      '| `/api/ai/install/status` | GET | – | `AiInstallStatus` | – |',
      '| `/api/ai/install/repair` | POST | `{"acceptTerms": true}` | `AiInstallStatus` | 400 `TERMS_REQUIRED` |',
      '',
    ].join('\n'),
  });
  ok('a fully documented family passes', run(root).length === 0);
}

{
  const root = makeFixture({ routes: '// the table moved\n', doc: 'anything\n' });
  const errors = run(root);
  ok('a matcher that finds nothing fails loudly instead of passing', errors.length === 1);
  ok('…naming the empty route set', errors[0].includes('No /api/ai/install/* routes found'));
}

// --- the real repo ---------------------------------------------------------
ok(
  'undocumentedRoutes is empty for an exact-match doc line',
  undocumentedRoutes(['GET /api/x'], '| `/api/x` | GET |').length === 0,
);

if (failures.length > 0) {
  console.error(`check-install-api-contract.test: FAIL (${failures.length})`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`check-install-api-contract.test: OK (${passed} assertions)`);
