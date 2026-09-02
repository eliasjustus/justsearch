/**
 * Tempdoc 884 PART E — unit tests for maintain-doc-hint's governing-doc recognition.
 *
 * What is worth pinning: the hook's escape hatch ("you DID update the governing doc, so
 * don't block"). The transcript records ABSOLUTE Edit `file_path`s; a consult-register row
 * names its doc repo-relative. The original comparison was an exact `Set.has()` between the
 * two, which can never hold — so the hatch never opened, and the `architecture-decisions`
 * row (whose governing doc `docs/decisions/README.md` lives inside its own watched region)
 * blocked even when README.md was the only file edited.
 *
 * Run with: `node scripts/agent-analytics/hooks/maintain-doc-hint.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { governingDocTouched } from './maintain-doc-hint.mjs';
import { GOVERNED_REGIONS } from '../lib/governed-regions.mjs';

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

const WT = 'F:/justsearch-public/.claude/worktrees/lane-B/';
const adrRegion = { docs: [{ path: 'docs/decisions/README.md' }] };

// --- governingDocTouched ---

run('absolute worktree path to the governing doc counts as touched', () => {
  assert.equal(governingDocTouched(new Set([`${WT}docs/decisions/README.md`]), adrRegion), true);
});

run('absolute MAIN-checkout path to the governing doc counts as touched', () => {
  assert.equal(governingDocTouched(new Set(['F:/justsearch-public/docs/decisions/README.md']), adrRegion), true);
});

run('repo-relative path still counts as touched (no regression)', () => {
  assert.equal(governingDocTouched(new Set(['docs/decisions/README.md']), adrRegion), true);
});

run('editing only a governed file, not the governing doc, is NOT touched', () => {
  assert.equal(governingDocTouched(new Set([`${WT}docs/decisions/0046-local-api-trust-boundary.md`]), adrRegion), false);
});

run('a same-named file in a DIFFERENT directory is not the governing doc', () => {
  // The suffix must be a whole path segment, so `docs/tempdocs/decisions/README.md` and a
  // bare `README.md` must not satisfy `docs/decisions/README.md`.
  assert.equal(governingDocTouched(new Set([`${WT}README.md`]), adrRegion), false);
  assert.equal(governingDocTouched(new Set([`${WT}modules/ui-web/docs/README.md`]), adrRegion), false);
});

run('a region with no docs is never satisfied (consult-only rows never block anyway)', () => {
  assert.equal(governingDocTouched(new Set([`${WT}anything.md`]), { docs: [] }), false);
  assert.equal(governingDocTouched(new Set([`${WT}anything.md`]), {}), false);
});

run('any ONE of several governing docs satisfies the region', () => {
  const multi = { docs: [{ path: 'docs/a.md' }, { path: 'docs/b.md' }] };
  assert.equal(governingDocTouched(new Set([`${WT}docs/b.md`]), multi), true);
});

// --- Integration with the real register: every maintain:true row is satisfiable ---

run('every maintain:true register row can be satisfied by an absolute edit of its own doc', () => {
  const maintainRows = GOVERNED_REGIONS.filter((r) => r.maintain);
  assert.ok(maintainRows.length > 0, 'the register must declare at least one maintain:true row');
  for (const row of maintainRows) {
    assert.ok(row.docs.length > 0, `maintain:true row "${row.id}" declares no governing doc, so it can never be satisfied`);
    const edited = new Set(row.docs.map((d) => WT + d.path));
    assert.equal(governingDocTouched(edited, row), true, `row "${row.id}" is unsatisfiable`);
  }
});

run('architecture-decisions: editing ONLY docs/decisions/README.md satisfies its own region', () => {
  const row = GOVERNED_REGIONS.find((r) => r.id === 'architecture-decisions');
  assert.ok(row, 'the architecture-decisions region must exist (tempdoc 884 PART E, E2)');
  const readmeOnly = new Set([`${WT}docs/decisions/README.md`]);
  // The region DOES watch README.md (it is under docs/decisions/) — which is fine precisely
  // because editing it also satisfies the region. Assert both halves.
  assert.equal(row.match(`${WT}docs/decisions/README.md`), true);
  assert.equal(governingDocTouched(readmeOnly, row), true);
});

if (failures.length > 0) {
  console.error(`maintain-doc-hint.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`maintain-doc-hint.test: all ${passed} checks passed`);
