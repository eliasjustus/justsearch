/**
 * Tempdoc 910 — test-efficacy's prior-baseline read, exercised on the REAL (git) branch.
 *
 * Why this file exists at all: `readPriorBaseline` called `readFileAtRef` with an OPTIONS OBJECT
 * against a POSITIONAL signature (`git-utils.mjs:236`), so `git show [object Object]:undefined`
 * threw, the catch returned null, and the prior baseline was ALWAYS null outside fixture mode.
 * `test-efficacy/silent-baseline-shift` therefore could not fire in any real run.
 *
 * The gate's own fixtures could never have caught it: the fixture branch returns the LIVE baseline
 * as the prior, so prior === live and no shift is representable. A test that only runs fixture mode
 * is a green that cannot see this defect — so this one builds a scratch git repo and reads a real
 * committed ref.
 *
 * Run with: `node scripts/governance/gates/test-efficacy/enforcer.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { readPriorBaseline } from './enforcer.mjs';

let passed = 0;
const failures = [];
const tmpDirs = [];

const BASELINE_REL = 'gates/test-efficacy/strength-baseline.v1.json';

/** A scratch git repo with `content` committed at HEAD, and `live` in the working tree. */
function scratchRepo({ committed, live }) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'test-efficacy-git-'));
  tmpDirs.push(root);
  const git = (...args) => execFileSync('git', args, { cwd: root, stdio: ['pipe', 'pipe', 'pipe'] });
  git('init', '-q');
  git('config', 'user.email', 'test@example.invalid');
  git('config', 'user.name', 'test');
  git('config', 'commit.gpgsign', 'false');
  const abs = path.join(root, BASELINE_REL);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, JSON.stringify(committed), 'utf8');
  git('add', '-A');
  git('commit', '-q', '-m', 'baseline');
  if (live !== undefined) fs.writeFileSync(abs, JSON.stringify(live), 'utf8');
  return root;
}

function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

run('the real branch reads the baseline committed at the ref', () => {
  const committed = { seams: { 'seam-a': { baseline: 9 } } };
  const root = scratchRepo({ committed, live: { seams: { 'seam-a': { baseline: 2 } } } });
  const prior = readPriorBaseline({
    fixtureMode: false,
    repoRoot: root,
    sourceRoot: root,
    baselineRef: 'HEAD',
    baselinePath: BASELINE_REL,
  });
  // The whole defect in one assertion: this was null for every real run.
  assert.notEqual(prior, null, 'prior baseline must not be null on the real branch');
  assert.deepEqual(prior, committed);
  // And it must be the COMMITTED content, not the working tree — otherwise a lowered floor would
  // compare equal to itself and never look like a shift.
  assert.equal(prior.seams['seam-a'].baseline, 9);
});

run('an absent baseline at the ref is null, not a throw', () => {
  const root = scratchRepo({ committed: { seams: {} } });
  const prior = readPriorBaseline({
    fixtureMode: false,
    repoRoot: root,
    sourceRoot: root,
    baselineRef: 'HEAD',
    baselinePath: 'gates/test-efficacy/does-not-exist.json',
  });
  assert.equal(prior, null);
});

run('no baselineRef is null', () => {
  const root = scratchRepo({ committed: { seams: {} } });
  assert.equal(
    readPriorBaseline({ fixtureMode: false, repoRoot: root, sourceRoot: root, baselineRef: null, baselinePath: BASELINE_REL }),
    null,
  );
});

run('fixture mode still returns the live baseline', () => {
  const root = scratchRepo({ committed: { seams: { s: { baseline: 1 } } }, live: { seams: { s: { baseline: 5 } } } });
  const prior = readPriorBaseline({
    fixtureMode: true,
    repoRoot: root,
    sourceRoot: root,
    baselineRef: 'HEAD',
    baselinePath: BASELINE_REL,
  });
  assert.equal(prior.seams.s.baseline, 5, 'fixture mode reads the working tree by design');
});

for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`test-efficacy enforcer.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`test-efficacy enforcer.test: all ${passed} checks passed`);
