/**
 * lib/ledger/boundary-check.test.mjs — unit tests for the pure
 * `findBoundaryViolations` checker itself (`boundary-check.mjs`,
 * independent-review SHOULD-FIX 4). `boundary.test.mjs` applies this
 * checker to the real files under `lib/ledger/`; this file proves the
 * checker actually catches every violation SHAPE it claims to — a checker
 * that always returns clean would pass a positive-only scan trivially, so
 * negative coverage here is what makes that scan mean something.
 *
 * Run with: `node scripts/agent-analytics/lib/ledger/boundary-check.test.mjs`
 */

import assert from 'node:assert/strict';
import { findBoundaryViolations } from './boundary-check.mjs';

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

// --- clean source produces no violations ------------------------------------

run('a clean file with only allowed imports produces zero violations', () => {
  const src = `import fs from 'node:fs';\nimport { a } from '../transcript-store.mjs';\nimport { b } from './tool-roles.mjs';\nexport function f() { return 1; }\n`;
  assert.deepEqual(findBoundaryViolations(src, 'clean.mjs'), []);
});

// --- forbidden strings -------------------------------------------------------

run('catches a forbidden governance/ path string', () => {
  const violations = findBoundaryViolations(`const x = "governance/foo.json";`, 'fake.mjs');
  assert.ok(violations.some((v) => v.includes('governance/')));
});

run('catches a forbidden CLAUDE.md string', () => {
  const violations = findBoundaryViolations(`// see CLAUDE.md for the rule`, 'fake.mjs');
  assert.ok(violations.some((v) => v.includes('CLAUDE.md')));
});

run('catches a forbidden tmp/agent-telemetry string', () => {
  const violations = findBoundaryViolations(`const dir = 'tmp/agent-telemetry/events.ndjson';`, 'fake.mjs');
  assert.ok(violations.some((v) => v.includes('tmp/agent-telemetry')));
});

// --- repo-root climb ---------------------------------------------------------

run('catches a repo-root climb via \',\'..\',\'..\'', () => {
  const violations = findBoundaryViolations(`path.join(__dirname, '..', '..', '..', 'governance')`, 'fake.mjs');
  assert.ok(violations.some((v) => v.includes('repo-root climb')));
});

run('does NOT flag a single ".." or two of them (only a three-deep climb)', () => {
  const violations = findBoundaryViolations(`path.join(__dirname, '..', 'sibling.mjs')`, 'fake.mjs');
  assert.deepEqual(violations.filter((v) => v.includes('repo-root climb')), []);
});

// --- import allowlist: negative shapes ---------------------------------------

run('catches a side-effect import with no "from" clause', () => {
  const violations = findBoundaryViolations(`import '../../../scripts/ci/x.mjs';\n`, 'fake.mjs');
  assert.ok(violations.some((v) => v.includes('disallowed import') && v.includes('../../../scripts/ci/x.mjs')));
});

run('catches a MULTI-LINE named import climbing above lib/ledger/', () => {
  const src = `import {\n  a,\n  b,\n} from '../../../scripts/ci/x.mjs';\n`;
  const violations = findBoundaryViolations(src, 'fake.mjs');
  assert.ok(violations.some((v) => v.includes('disallowed import') && v.includes('../../../scripts/ci/x.mjs')));
});

run('catches a one-level-too-far specifier \'../../foo.mjs\' (not one of the two exact allowances)', () => {
  const violations = findBoundaryViolations(`import { foo } from '../../foo.mjs';\n`, 'fake.mjs');
  assert.ok(violations.some((v) => v.includes('disallowed import') && v.includes('../../foo.mjs')));
});

run('catches a bare (non-relative) node module specifier', () => {
  const violations = findBoundaryViolations(`import something from 'some-npm-package';\n`, 'fake.mjs');
  assert.ok(violations.some((v) => v.includes('disallowed import') && v.includes('some-npm-package')));
});

run('catches an absolute-path-shaped specifier', () => {
  const violations = findBoundaryViolations(`import x from '/abs/path.mjs';\n`, 'fake.mjs');
  assert.ok(violations.some((v) => v.includes('disallowed import') && v.includes('/abs/path.mjs')));
});

// --- import allowlist: positive shapes ---------------------------------------

run('does NOT flag the two allowed exact specifiers', () => {
  const src = `import { a } from '../transcript-store.mjs';\nimport { b } from '../transcript-cost.mjs';\n`;
  const violations = findBoundaryViolations(src, 'fake.mjs');
  assert.deepEqual(violations.filter((v) => v.includes('disallowed import')), []);
});

run('does NOT flag a node: builtin', () => {
  const violations = findBoundaryViolations(`import fs from 'node:fs';\nimport path from 'node:path';\n`, 'fake.mjs');
  assert.deepEqual(violations.filter((v) => v.includes('disallowed import')), []);
});

run('does NOT flag a sibling ./ ledger import (single or multi-line)', () => {
  const src = `import { roleFor } from './tool-roles.mjs';\nimport {\n  makeCall,\n  isCall,\n} from './record.mjs';\n`;
  const violations = findBoundaryViolations(src, 'fake.mjs');
  assert.deepEqual(violations.filter((v) => v.includes('disallowed import')), []);
});

run('does NOT flag a sibling ./ side-effect import', () => {
  const violations = findBoundaryViolations(`import './setup.mjs';\n`, 'fake.mjs');
  assert.deepEqual(violations.filter((v) => v.includes('disallowed import')), []);
});

// --- second-pass review escapes (2026-09-02) ----------------------------------

run('catches a re-export edge: export { a } from a climbing path', () => {
  const violations = findBoundaryViolations(
    `export { foo } from '../../../scripts/ci/x.mjs';\n`, 'fake.mjs');
  assert.ok(violations.some((v) => v.includes('disallowed import "../../../scripts/ci/x.mjs"')), violations.join('\n'));
});

run('catches a star re-export edge: export * as ns from a climbing path', () => {
  const violations = findBoundaryViolations(
    `export * as ns from '../../foo.mjs';\n`, 'fake.mjs');
  assert.ok(violations.some((v) => v.includes('disallowed import "../../foo.mjs"')), violations.join('\n'));
});

run('catches a dynamic import(...) with no whitespace after import', () => {
  const violations = findBoundaryViolations(
    `const m = await import('../../../scripts/ci/x.mjs');\n`, 'fake.mjs');
  assert.ok(violations.some((v) => v.includes('disallowed import "../../../scripts/ci/x.mjs"')), violations.join('\n'));
});

run('catches the createRequire escape hatch', () => {
  const violations = findBoundaryViolations(
    `import { createRequire } from 'node:module';\nconst r = createRequire(import.meta.url);\n`, 'fake.mjs');
  assert.ok(violations.some((v) => v.includes('createRequire')), violations.join('\n'));
});

run('does NOT flag a dynamic import of a node: builtin or sibling', () => {
  const violations = findBoundaryViolations(
    `const a = await import('node:fs');\nconst b = await import('./record.mjs');\n`, 'fake.mjs');
  assert.deepEqual(violations.filter((v) => v.includes('disallowed import')), []);
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`boundary-check.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`boundary-check.test: ${passed} passed`);
