/**
 * Tests for ensure-ci-on-sha's pure judgment: argument parsing and the sha→run match.
 *
 * `findRunForSha` is the whole point of the tool — the bug it exists for is a PR reading green off a
 * run that belongs to an OLDER commit, so a match that is loose about which sha a run carries would
 * reproduce exactly that bug. The negative controls below are therefore the important half.
 *
 * Run: `node scripts/ci/ensure-ci-on-sha.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { parseArgs, findRunForSha } from './ensure-ci-on-sha.mjs';

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
};
const throws = (label, fn) => {
  try {
    fn();
    failures.push(`${label}: expected a throw, got none`);
  } catch {
    passed += 1;
  }
};

const SHA = 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678';
const OTHER = 'ffffffffffffffffffffffffffffffffffffffff';

// --- findRunForSha ---
const runs = [
  { databaseId: 3, headSha: OTHER, event: 'push' },
  { databaseId: 2, headSha: SHA, event: 'workflow_dispatch' },
  { databaseId: 1, headSha: OTHER, event: 'push' },
];
ok('finds the run carrying the sha', findRunForSha(runs, SHA)?.databaseId === 2);
ok('returns null when no run carries the sha', findRunForSha(runs, '0'.repeat(40)) === null);
ok('an empty list is null, not a crash', findRunForSha([], SHA) === null);
ok('a missing list is null, not a crash', findRunForSha(undefined, SHA) === null);
ok('a missing sha never matches', findRunForSha(runs, null) === null);
ok('a missing sha never matches (empty string)', findRunForSha(runs, '') === null);
// The bug class, stated as a test: a run on the PARENT commit must not satisfy "CI ran on this sha".
ok('a run on a different sha does NOT match', findRunForSha([{ databaseId: 9, headSha: OTHER }], SHA) === null);
// Abbreviated shas are a different string; matching them loosely is how you accept the wrong run.
ok('a prefix does not match', findRunForSha([{ databaseId: 9, headSha: SHA.slice(0, 8) }], SHA) === null);
ok('the full sha does not match a stored prefix query', findRunForSha([{ databaseId: 9, headSha: SHA }], SHA.slice(0, 8)) === null);
// Newest-first ordering from `gh run list` must be preserved: the first match wins.
ok(
  'the newest matching run wins',
  findRunForSha([{ databaseId: 5, headSha: SHA }, { databaseId: 4, headSha: SHA }], SHA)?.databaseId === 5,
);

// --- parseArgs ---
{
  const o = parseArgs([]);
  ok('defaults: no branch', o.branch === null);
  ok('defaults: ci.yml', o.workflow === 'ci.yml');
  ok('defaults: dispatch enabled', o.dispatch === true);
  ok('defaults: local (not remote)', o.remote === false);
  ok('defaults: waitSec 90', o.waitSec === 90);
  ok('defaults: confirmSec 120', o.confirmSec === 120);
}
{
  const o = parseArgs(['worktree-x', '--workflow', 'codeql.yml', '--wait-sec', '5', '--no-dispatch', '--remote', '--json']);
  ok('positional branch', o.branch === 'worktree-x');
  ok('--workflow', o.workflow === 'codeql.yml');
  ok('--wait-sec', o.waitSec === 5);
  ok('--no-dispatch', o.dispatch === false);
  ok('--remote', o.remote === true);
  ok('--json', o.json === true);
}
ok('--sha overrides branch resolution', parseArgs(['--sha', SHA]).sha === SHA);
ok('--confirm-sec', parseArgs(['--confirm-sec', '7']).confirmSec === 7);
ok('zero waits are allowed (a single-shot check)', parseArgs(['--wait-sec', '0']).waitSec === 0);

throws('an unknown option is rejected, not ignored', () => parseArgs(['--rerun-everything']));
throws('a second positional is rejected', () => parseArgs(['a', 'b']));
throws('a non-numeric --wait-sec is rejected', () => parseArgs(['--wait-sec', 'soon']));
throws('a negative --confirm-sec is rejected', () => parseArgs(['--confirm-sec', '-1']));

if (failures.length > 0) {
  console.error(`ensure-ci-on-sha.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`ensure-ci-on-sha.test: all ${passed} checks passed`);
