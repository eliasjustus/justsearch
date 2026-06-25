/**
 * Unit tests for the hook-integrity verdicts (tempdoc 592).
 * Run: `node scripts/governance/gates/hook-integrity/truth-table.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import {
  verdictForBinding,
  verdictForCatalogBound,
  verdictForCwdInvariantCommand,
  verdictForLoad,
  verdictForBite,
  verdictForBiteDeclared,
  verdictForTierRegisterSync,
} from './truth-table.mjs';

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

ok('resolved binding passes', verdictForBinding({ event: 'PreToolUse', hookId: 'x', resolved: true }).status === 'pass');
ok('dangling binding fails', verdictForBinding({ event: 'PreToolUse', hookId: 'x', resolved: false }).status === 'fail');

ok('bound catalog hook passes', verdictForCatalogBound({ hookId: 'x', bound: true }).status === 'pass');
ok('orphan catalog hook fails', verdictForCatalogBound({ hookId: 'x', bound: false }).status === 'fail');

ok('exec-form command passes', verdictForCwdInvariantCommand({ location: 'L', isExecForm: true, commandRepr: 'node ${CLAUDE_PROJECT_DIR}/x' }).status === 'pass');
ok('cwd-relative command fails', verdictForCwdInvariantCommand({ location: 'L', isExecForm: false, commandRepr: 'node scripts/x' }).status === 'fail');

ok('loadable hook passes', verdictForLoad({ hookId: 'x', loaded: true, detail: '' }).status === 'pass');
ok('crash-on-load fails', verdictForLoad({ hookId: 'x', loaded: false, detail: 'SyntaxError' }).status === 'fail');

ok('bite satisfied passes', verdictForBite({ hookId: 'x', satisfied: true, kind: 'command-signal', detail: '' }).status === 'pass');
ok('bite unsatisfied fails', verdictForBite({ hookId: 'x', satisfied: false, kind: 'command-signal', detail: 'no exit 2' }).status === 'fail');

ok('blocking with bite spec passes', verdictForBiteDeclared({ hookId: 'x', hasBite: true }).status === 'pass');
ok('blocking without bite spec fails', verdictForBiteDeclared({ hookId: 'x', hasBite: false }).status === 'fail');

ok('resolved tier marker passes', verdictForTierRegisterSync({ marker: 'bash-guard.mjs', resolved: true }).status === 'pass');
ok('unresolved tier marker fails', verdictForTierRegisterSync({ marker: 'ghost.mjs', resolved: false }).status === 'fail');

// every verdict returns the {ruleId, status, reason} contract shape
for (const v of [
  verdictForBinding({ event: 'E', hookId: 'h', resolved: true }),
  verdictForCwdInvariantCommand({ location: 'L', isExecForm: false, commandRepr: 'x' }),
]) {
  ok('verdict has ruleId/status/reason', typeof v.ruleId === 'string' && typeof v.status === 'string' && typeof v.reason === 'string');
}

if (failures.length > 0) {
  console.error(`hook-integrity truth-table.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`hook-integrity truth-table.test: all ${passed} checks passed`);
