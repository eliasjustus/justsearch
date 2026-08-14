/**
 * Tempdoc 743 second wave (P-K) — unit tests for exec-substrate-hint's pure classifier.
 *
 * The classifier decides whether (and which) non-blocking redirect hint fires. A wrong
 * predicate either nags on common commands (`gh pr view`, `npm run watch &`, plain
 * `python -c`) or stays silent on the exact pastes it exists to catch (a PowerShell
 * call-operator quoting a scoop path, a hand-rolled `gh pr checks` poll loop, piped
 * non-ASCII Python output). This corpus is the living regression guard.
 *
 * Run with: `node scripts/agent-analytics/hooks/exec-substrate-hint.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import fs from 'node:fs';
import { classifyExecSubstrate } from './exec-substrate-hint.mjs';

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

// [command, expectedClass]  (null = must stay silent)
const CORPUS = [
  // --- should-fire: call-operator (real-failure paste class) ---
  ['& "F:\\scoop\\apps\\gh\\2.90.0\\bin\\gh.exe" pr list --repo justsearch-app/justsearch', 'call-operator'],
  ["& 'C:\\tools\\gh\\gh.exe' pr view 42", 'call-operator'],

  // --- should-fire: wait-shaped gh pr checks / gh run watch ---
  ['"$GH" pr checks 204 | tail', 'wait-shaped'],
  ['gh run watch 123456 &', 'wait-shaped'],
  ['while ! gh pr checks 204 | grep -q .; do sleep 1; done', 'wait-shaped'],
  ['gh pr checks 204 --watch', 'wait-shaped'],

  // --- should-fire: python-risk (non-ASCII/backslash + piped/redirected) ---
  ["python -c \"print('umlaut-ä dash-— check')\" | cat", 'python-risk'],
  ['python -c "open(\'C:\\\\data\\\\x.txt\')" > out.log', 'python-risk'],

  // --- must-NOT-fire ---
  ['gh pr view 42', null],
  ['gh pr checks 204', null], // single status read, not a wait
  ['python -c "print(1)"', null], // ascii, no pipe
  ["python -c \"print('café')\"", null], // non-ASCII but NOT piped — tests the AND condition
  ['node scripts/dev/run-gh.mjs pr checks 229', null], // already on the paved path
  ['node scripts/dev/run-gh.mjs checks-wait 229', null],
  ['node scripts/dev/run-py.mjs -c "print(1)"', null],
  ['echo "& sons"', null], // literal text inside an argument, not a real call operator
  ['npm run watch &', null], // common backgrounded dev command — no gh prefix
  ['curl -s http://127.0.0.1:7860/api/health', null], // unrelated
  ['gh pr checks 204 | wc -l', null], // piped, but not to tail/grep/head
  ['', null],
];

for (const [cmd, want] of CORPUS) {
  run(`${want ?? 'silent'}: ${cmd}`, () => {
    assert.equal(classifyExecSubstrate(cmd), want);
  });
}

run('undefined command does not fire', () => {
  assert.equal(classifyExecSubstrate(undefined), null);
});

// --- emit-shape contract: the hook emits additionalContext on a match, nothing otherwise ---
const HOOK = resolve(dirname(fileURLToPath(import.meta.url)), 'exec-substrate-hint.mjs');
function runHook(command, sessionId) {
  return execFileSync('node', [HOOK], {
    input: JSON.stringify({ tool_name: 'Bash', tool_input: { command }, session_id: sessionId }),
    encoding: 'utf8',
  });
}

const TEST_SESSION = `exec-substrate-hint-test-${process.pid}`;
const markerFile = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '..', '..', '..', 'tmp', 'agent-telemetry', `exec-substrate-nudged-${TEST_SESSION}.json`,
);
try { fs.unlinkSync(markerFile); } catch { /* none yet */ }

run('call-operator match emits a PreToolUse additionalContext JSON (advisory, never blocks)', () => {
  const out = runHook('& "F:\\scoop\\apps\\gh\\2.90.0\\bin\\gh.exe" pr list', TEST_SESSION);
  const parsed = JSON.parse(out);
  assert.equal(parsed.hookSpecificOutput.hookEventName, 'PreToolUse');
  assert.ok(parsed.hookSpecificOutput.additionalContext.includes('run-gh.mjs'));
  assert.equal(parsed.hookSpecificOutput.permissionDecision, undefined);
});
run('non-Bash tool emits nothing', () => {
  const out = execFileSync('node', [HOOK], {
    input: JSON.stringify({ tool_name: 'Read', tool_input: { file_path: 'x' } }),
    encoding: 'utf8',
  });
  assert.equal(out.trim(), '');
});
run('negative command emits nothing', () => {
  assert.equal(runHook('gh pr view 42', TEST_SESSION).trim(), '');
});
run('same class does not re-fire twice in one session (per-class dedup)', () => {
  const session = `exec-substrate-hint-test-dedup-${process.pid}`;
  const marker = resolve(
    dirname(fileURLToPath(import.meta.url)),
    '..', '..', '..', 'tmp', 'agent-telemetry', `exec-substrate-nudged-${session}.json`,
  );
  try { fs.unlinkSync(marker); } catch { /* none yet */ }
  const first = runHook('gh run watch 1 &', session);
  const second = runHook('gh run watch 2 &', session);
  assert.ok(JSON.parse(first).hookSpecificOutput.additionalContext.length > 0);
  assert.equal(second.trim(), '', 'second wait-shaped command in the same session must stay silent');
  try { fs.unlinkSync(marker); } catch { /* cleanup */ }
});
run('JUSTSEARCH_DISABLE_HOOKS=1 silences the hook', () => {
  const out = execFileSync('node', [HOOK], {
    input: JSON.stringify({ tool_name: 'Bash', tool_input: { command: 'gh run watch 3 &' } }),
    encoding: 'utf8',
    env: { ...process.env, JUSTSEARCH_DISABLE_HOOKS: '1' },
  });
  assert.equal(out.trim(), '');
});

try { fs.unlinkSync(markerFile); } catch { /* cleanup */ }

// --- Report ---
if (failures.length > 0) {
  console.error(`exec-substrate-hint.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`exec-substrate-hint.test: all ${passed} checks passed`);
