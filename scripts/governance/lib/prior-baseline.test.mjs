/**
 * Tempdoc 910 — the shared prior-baseline read, exercised on the REAL (git) branch.
 *
 * A fixture-only test cannot see this function's failure mode: the fixture branch returns the live
 * baseline, so prior === live and nothing is ever detectable as a shift. The bug this file exists
 * to prevent (a wrong-arity readFileAtRef call) survived precisely that blind spot.
 *
 * Run with: `node scripts/governance/lib/prior-baseline.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { readPriorBaselineText } from './prior-baseline.mjs';

let passed = 0;
const failures = [];
const tmpDirs = [];
const REL = 'gates/example/baseline.txt';

function scratchRepo({ committed, live }) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'prior-baseline-'));
  tmpDirs.push(root);
  const git = (...a) => execFileSync('git', a, { cwd: root, stdio: ['pipe', 'pipe', 'pipe'] });
  git('init', '-q');
  git('config', 'user.email', 'test@example.invalid');
  git('config', 'user.name', 'test');
  git('config', 'commit.gpgsign', 'false');
  const abs = path.join(root, REL);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, committed, 'utf8');
  git('add', '-A');
  git('commit', '-q', '-m', 'baseline');
  if (live !== undefined) fs.writeFileSync(abs, live, 'utf8');
  return root;
}

function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

run('reads the COMMITTED text at the ref, not the working tree', () => {
  const root = scratchRepo({ committed: 'src/a.ts 1 2026-01-01\n', live: 'src/a.ts 99 2026-09-02\n' });
  const prior = readPriorBaselineText({ sourceRoot: root, baselineRef: 'HEAD', baselinePath: REL });
  assert.equal(prior.trim(), 'src/a.ts 1 2026-01-01',
    'must be the ref content — comparing the working tree to itself can never detect a shift');
});

run('a path absent at the ref is null', () => {
  const root = scratchRepo({ committed: 'x 1 2026-01-01\n' });
  assert.equal(readPriorBaselineText({ sourceRoot: root, baselineRef: 'HEAD', baselinePath: 'gates/nope.txt' }), null);
});

run('no baselineRef is null', () => {
  const root = scratchRepo({ committed: 'x 1 2026-01-01\n' });
  assert.equal(readPriorBaselineText({ sourceRoot: root, baselineRef: null, baselinePath: REL }), null);
});

run('fixtureMode reads _baseline/<path>', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'prior-baseline-fx-'));
  tmpDirs.push(root);
  const abs = path.join(root, '_baseline', REL);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, 'src/a.ts 3 2026-01-01\n', 'utf8');
  const prior = readPriorBaselineText({ fixtureMode: true, fixtureRoot: root, sourceRoot: root, baselinePath: REL });
  assert.equal(prior.trim(), 'src/a.ts 3 2026-01-01');
});

run('fixtureMode with no _baseline tree is null', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'prior-baseline-fx2-'));
  tmpDirs.push(root);
  assert.equal(readPriorBaselineText({ fixtureMode: true, fixtureRoot: root, sourceRoot: root, baselinePath: REL }), null);
});

for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`prior-baseline.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`prior-baseline.test: all ${passed} checks passed`);
