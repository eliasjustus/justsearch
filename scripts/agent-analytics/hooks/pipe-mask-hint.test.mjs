/**
 * Tempdoc 618 §10a — unit tests for pipe-mask-hint's pure detector.
 *
 * The detector decides whether the non-blocking exit-masking hint fires. A wrong
 * predicate here either nags on legitimate `log | tail` / `… | grep` reads, or
 * stays silent on the `build | tail` case it exists to catch (a red build read as
 * green — the §10a failure). This corpus is the living regression guard: real
 * command diversity exceeds any fixed list, so new false-positives/negatives are
 * fixed here (corpus + predicate), not by a redesign.
 *
 * Run with: `node scripts/agent-analytics/hooks/pipe-mask-hint.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { detectPipeMaskedExit, pipelineStages } from './pipe-mask-hint.mjs';

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

// [command, shouldFire]
const CORPUS = [
  // POSITIVES — a build/test command's exit masked by a trailing filter.
  ['./gradlew.bat build -x test | tail -25', true],
  ['./gradlew build | tail -n 40', true],
  ['gradle test | grep FAILED', true],
  ['npm test | tail -20', true],
  ['npm run build | head -50', true],
  ['npx vitest run | tail -30', true],
  ['vitest | grep -i fail', true],
  ['pytest | tail -15', true],
  ['python -m pytest tests/ | tail', true],
  ['jseval run --pipeline | tail -40', true],
  ['cargo test | tail -20', true],
  ['go test ./... | grep -v ok', true],
  ['mvn verify | tail -25', true],
  ['./gradlew :modules:ui:test | head -60', true],
  ['npm run typecheck | tail -10', true],
  ['./gradlew.bat build -x test 2>&1 | tail -25', true],
  ['ctest | grep Failed', true],
  ['gradlew.bat check | tail -100', true],
  ['npm test | rg FAIL', true],
  // `$?` / `&& echo` AFTER a pipe read the pipe's (tail's) exit, not the build's —
  // still masked, so these MUST fire. `| tail; echo $?` is the exact §10a recurrence.
  ['./gradlew build -x test | tail; echo $?', true],
  ['./gradlew build | tail -25 && echo done', true],

  // NEGATIVES — must NOT fire.
  ['cat build.log | tail -25', false], // reading a log, not running a build
  ['git log --oneline | head -20', false],
  ['ls -la | grep gradle', false], // build word is a search arg, not the executable
  ['grep -rn "gradlew" scripts/ | head', false],
  ['cat package.json | grep test', false],
  ['./gradlew build && echo BUILD DONE', false], // no masking sink (no pipe)
  ['./gradlew build', false], // bare, correct
  ['set -o pipefail; ./gradlew build | tail -25', false], // pipefail: genuine preserver
  ['./gradlew build | tail -25; echo ${PIPESTATUS[0]}', false], // PIPESTATUS: genuine preserver
  ['npm test > out.txt 2>&1', false], // redirect, not pipe-to-filter
  ['docker logs api | tail -50', false],
  ['journalctl -u svc | grep error', false],
  ['echo "npm test" | tail', false], // echoing a string, not running
  ['history | grep gradlew', false],
  ['find . -name "*.gradle" | head', false],
  ['tail -f server.log | grep WARN', false],
  ['ps aux | grep java', false],
  ['./gradlew tasks | grep test', false], // listing tasks — not a build/test subcommand
  ['curl -s localhost | tail', false],
  ['printenv | grep GRADLE', false],
];

for (const [cmd, want] of CORPUS) {
  run(`${want ? 'fires' : 'silent'}: ${cmd}`, () => {
    assert.equal(detectPipeMaskedExit(cmd), want);
  });
}

// --- guard the "right reason", not just the outcome (rule:critical-analysis-pass) ---
run('pipefail is the reason a guarded build stays silent (not "no sink")', () => {
  // Same command, sink present, differs only by pipefail → detector must flip on the guard.
  assert.equal(detectPipeMaskedExit('./gradlew build | tail -25'), true);
  assert.equal(detectPipeMaskedExit('set -o pipefail; ./gradlew build | tail -25'), false);
});
run('suppression is driven by GENUINE preservation, not the mere presence of echo/$?', () => {
  // `; echo $?` after a pipe reads tail's exit (masked) → MUST still fire; only a real
  // preserver (pipefail / PIPESTATUS) suppresses. This is the §10a regression guard.
  assert.equal(detectPipeMaskedExit('./gradlew build | tail; echo $?'), true);
  assert.equal(detectPipeMaskedExit('./gradlew build | tail && echo done'), true);
  assert.equal(detectPipeMaskedExit('set -o pipefail; ./gradlew build | tail; echo $?'), false);
  assert.equal(detectPipeMaskedExit('./gradlew build | tail; echo ${PIPESTATUS[0]}'), false);
});

run('empty / undefined command does not fire', () => {
  assert.equal(detectPipeMaskedExit(''), false);
  assert.equal(detectPipeMaskedExit(undefined), false);
});

run('pipelineStages splits on single | but not ||', () => {
  assert.deepEqual(pipelineStages('a | b'), ['a', 'b']);
  assert.deepEqual(pipelineStages('a || b'), ['a || b']);
});

// --- emit-shape contract: the hook emits additionalContext on a positive, nothing on a negative ---
const HOOK = resolve(dirname(fileURLToPath(import.meta.url)), 'pipe-mask-hint.mjs');
function runHook(command) {
  return execFileSync('node', [HOOK], {
    input: JSON.stringify({ tool_name: 'Bash', tool_input: { command } }),
    encoding: 'utf8',
  });
}
run('positive emits a PreToolUse additionalContext JSON', () => {
  const out = runHook('./gradlew.bat build -x test | tail -25');
  const parsed = JSON.parse(out);
  assert.equal(parsed.hookSpecificOutput.hookEventName, 'PreToolUse');
  assert.ok(parsed.hookSpecificOutput.additionalContext.includes('§10a'));
  assert.equal(parsed.hookSpecificOutput.permissionDecision, undefined); // advisory: never blocks
});
run('negative emits nothing', () => {
  assert.equal(runHook('cat build.log | tail -25').trim(), '');
});

// --- Report ---
if (failures.length > 0) {
  console.error(`pipe-mask-hint.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`pipe-mask-hint.test: all ${passed} checks passed`);
