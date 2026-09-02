/**
 * lib/ledger/boundary.test.mjs — applies `findBoundaryViolations`
 * (`boundary-check.mjs`) to every REAL file under `lib/ledger/`, enforcing
 * the 886 §10.4 boundary rule: the measurement library is machine-level, the
 * hooks/gates it might one day feed are repo-level, and the two must not
 * conflate. The checker's own correctness (positive/negative unit cases) is
 * covered separately in `boundary-check.test.mjs`; this file is the
 * integration application of it.
 *
 * Run with: `node scripts/agent-analytics/lib/ledger/boundary.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { findBoundaryViolations } from './boundary-check.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

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

// `boundary-check.mjs` is the checker itself: its source legitimately
// contains the forbidden-string literals and example "import ..." text AS
// DATA (the rule table, doc comments, violation-message templates like
// `disallowed import "${spec}"`, which the import regex itself matches
// against). Excluded from THIS scan the same way `*.test.mjs` files already
// are — it is test/checker infrastructure, not adapter/record code the
// boundary rule is protecting. Its own behaviour is what
// `boundary-check.test.mjs` verifies directly.
const SELF_EXCLUDE = new Set(['boundary-check.mjs']);

function ledgerFiles() {
  return fs.readdirSync(HERE, { withFileTypes: true })
    .filter((e) => e.isFile() && e.name.endsWith('.mjs') && !e.name.endsWith('.test.mjs') && !SELF_EXCLUDE.has(e.name))
    .map((e) => e.name);
}

for (const name of ledgerFiles()) {
  const file = path.join(HERE, name);
  const content = fs.readFileSync(file, 'utf8');

  run(`${name}: no boundary violations`, () => {
    const violations = findBoundaryViolations(content, name);
    assert.deepEqual(violations, [], violations.join('; '));
  });
}

run('at least one ledger file was actually checked', () => {
  assert.ok(ledgerFiles().length >= 5, 'expected record.mjs, tool-roles.mjs, claude-adapter.mjs, codex-adapter.mjs, index.mjs at minimum');
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`boundary.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`boundary.test: ${passed} passed`);
