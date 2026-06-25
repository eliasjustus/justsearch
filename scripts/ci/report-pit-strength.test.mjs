/**
 * report-pit-strength register-agnosticism (tempdoc 576 §15 / G6): the mutation-strength pipeline is
 * NOT hardwired to governance/logic-seams.v1.json — `--register <path>` is honored. This runs the
 * report (no --run, so no gradle/PIT) against a SECOND fixture register and asserts the output reflects
 * THAT register's seams (registerPath + the fixture's 2 seam ids), not the default 5-seam register.
 *
 * Run: `node scripts/ci/report-pit-strength.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, rmSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const FIXTURE = 'scripts/governance/_fixtures/report-pit-strength/alt-register.v1.json';
const OUT = 'tmp/report-pit-strength.test.out.json';

let passed = 0;
const failures = [];
function ok(label, cond) {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
}

const outAbs = resolve(REPO_ROOT, OUT);
if (existsSync(outAbs)) rmSync(outAbs);
// No --run → no gradle/PIT; the report reads the register + iterates ITS seams (which have no
// mutations.xml here). registerPath + the iterated seam ids in stdout prove --register is honored.
const stdout = execFileSync(
  'node',
  ['scripts/ci/report-pit-strength.mjs', '--register', FIXTURE, '--out', OUT],
  { cwd: REPO_ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
);
const report = JSON.parse(readFileSync(outAbs, 'utf8'));

ok('report records the non-default register path', report.registerPath === FIXTURE);
ok('report has the pit-strength schema', report.schema === 'pit-strength-report.v1');
ok('the tool iterated the FIXTURE seams (alt-seam-one/two)', /alt-seam-one/.test(stdout) && /alt-seam-two/.test(stdout));
ok('the tool did NOT iterate the DEFAULT register seams (hybrid-fusion)', !/hybrid-fusion/.test(stdout));

rmSync(outAbs, { force: true });

if (failures.length > 0) {
  console.error(`report-pit-strength.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`report-pit-strength.test: all ${passed} checks passed (--register honored; register-agnostic)`);
