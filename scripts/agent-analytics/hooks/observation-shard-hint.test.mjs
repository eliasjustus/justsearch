/**
 * Tempdoc 665 — unit tests for observation-shard-hint's pure decision logic.
 *
 * The shard-existence + git-dirty checks are fs/git-dependent and fail-open;
 * what is worth pinning is `decideAction`, which decides whether the
 * non-blocking Stop nudge fires. A wrong condition here would either nag on
 * an already-committed (or nonexistent) shard, or stay silent on the exact
 * uncommitted-shard case this hook exists to catch.
 *
 * Run with: `node scripts/agent-analytics/hooks/observation-shard-hint.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { decideAction, HINT } from './observation-shard-hint.mjs';

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

const BASE = { stopHookActive: false, shardExists: true, shardDirty: true, alreadyNudged: false };

run('nudges when the shard exists, is dirty, not yet nudged, and stop is not re-entrant', () => {
  assert.deepEqual(decideAction(BASE), { action: 'nudge' });
});
run('no-ops when stop_hook_active (avoid re-firing within one forced continuation)', () => {
  assert.deepEqual(decideAction({ ...BASE, stopHookActive: true }), { action: 'noop' });
});
run('no-ops when the shard does not exist (nothing written this session)', () => {
  assert.deepEqual(decideAction({ ...BASE, shardExists: false }), { action: 'noop' });
});
run('no-ops when the shard exists but is already committed (clean)', () => {
  assert.deepEqual(decideAction({ ...BASE, shardDirty: false }), { action: 'noop' });
});
run('no-ops when already nudged this session (no nagging every turn)', () => {
  assert.deepEqual(decideAction({ ...BASE, alreadyNudged: true }), { action: 'noop' });
});

run('HINT names the shard path and never claims it will block', () => {
  const text = HINT('docs/observations.d/abc123.md');
  assert.match(text, /docs\/observations\.d\/abc123\.md/);
  assert.doesNotMatch(text, /\bblock/i);
});

if (failures.length) {
  console.error(`observation-shard-hint.test: ${failures.length} FAILED / ${passed} passed`);
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log(`observation-shard-hint.test: ${passed} passed`);
