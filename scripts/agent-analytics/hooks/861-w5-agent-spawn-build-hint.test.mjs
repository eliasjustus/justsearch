/**
 * Tempdoc 861 W5 — `agent-spawn-build-hint.mjs`'s classifier corpus, emit-shape contract, and
 * (the load-bearing proof) that this hint NEVER kills a process even when its own holder
 * lookup is genuinely reap-eligible on the §6.3 matrix.
 *
 * Three layers:
 *
 *   1. `classifyBuildCommand` pure corpus — gradlew/npm-shaped commands fire, read-only npm
 *      subcommands and unrelated commands stay silent.
 *   2. Emit-shape + dedup, against a REAL disposable child process and an ISOLATED fixture
 *      register (`JUSTSEARCH_DEV_RUNNER_STATE_ROOT` override) — never the real repo's register
 *      or the real dev stack.
 *   3. THE BUILD-HOOK-NEVER-KILLS PROOF: the fixture record is deliberately SAME-SESSION with a
 *      live lease — reap-eligible on every axis the §6.3 matrix has — yet after the hook runs
 *      (as a real subprocess, exactly as Claude Code would invoke it) the child process is still
 *      alive. [A4] is structural (`before-a-build` binds to `capability:'advisory'` in the
 *      reaper's frozen `OCCASIONS` map — `agent-spawn-sweep.test.mjs` proves the disposition
 *      itself never reaches 'reap'), and this is the end-to-end confirmation that nothing between
 *      the hook and the reaper reintroduces a kill path.
 *
 * Run with: `node scripts/agent-analytics/hooks/agent-spawn-build-hint.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { execFileSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { classifyBuildCommand } from './agent-spawn-build-hint.mjs';

const require = createRequire(import.meta.url);
const { buildAgentSpawnRecord, writeAgentSpawnRecord } = require('../../dev/lib/agent-spawn-record.cjs');
const { readProcessTable, normalizeCreationTime } = require('../../dev/lib/process-identity.cjs');

const HERE = path.dirname(fileURLToPath(import.meta.url));
const HOOK = path.join(HERE, 'agent-spawn-build-hint.mjs');
const AGENT_TELEMETRY_DIR = path.resolve(HERE, '..', '..', '..', 'tmp', 'agent-telemetry');
// hook-base.mjs's `repoRoot` for THIS worktree — the exact path the hook itself will pass as
// `targetPath` to `findBuildHolders`.
const REPO_ROOT = path.resolve(HERE, '..', '..', '..');

let passed = 0;
const failures = [];
function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.stack || e.message}`);
  }
}
async function check(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.stack || e.message}`);
  }
}

// --- Layer 1: classifyBuildCommand corpus ---------------------------------------------------

const CORPUS = [
  ['./gradlew.bat build -x test', true],
  ['gradlew build', true],
  ['./gradlew spotlessApply', true],
  ['npm install', true],
  ['npm ci', true],
  ['npm run build', true],
  ['npm run typecheck && npm run test:unit:run', true],
  ['npm rebuild', true],
  // --- should stay silent ---
  ['npm ls', false],
  ['npm view foo', false],
  ['npm outdated', false],
  ['git status', false],
  // Deliberately broad on gradlew (per the hook's own doc comment: "nearly every task writes
  // under build/") — the word "gradlew" anywhere after a command boundary fires, including this
  // edge case. Advisory + per-session dedup keeps the cost of that breadth low.
  ['echo gradlew', true],
  ['', false],
  [undefined, false],
];
for (const [cmd, want] of CORPUS) {
  run(`classifyBuildCommand(${JSON.stringify(cmd)}) === ${want}`, () => {
    assert.equal(classifyBuildCommand(cmd), want);
  });
}

// --- Layers 2+3: real subprocess, isolated fixture register, real disposable child ----------

function killIfAlive(pid) {
  try { process.kill(pid, 'SIGKILL'); } catch { /* already gone */ }
}

async function findOwnChildRow(pid) {
  for (let i = 0; i < 15; i += 1) {
    const table = readProcessTable();
    if (table.ok) {
      const row = table.table.find((r) => Number(r?.ProcessId) === pid);
      if (row) return row;
    }
    await new Promise((r) => setTimeout(r, 200));
  }
  return null;
}

function runHook({ command, sessionId, env }) {
  return execFileSync('node', [HOOK], {
    input: JSON.stringify({ tool_name: 'Bash', tool_input: { command }, session_id: sessionId }),
    encoding: 'utf8',
    env: { ...process.env, ...env },
  });
}

async function main() {
  if (process.platform !== 'win32') {
    console.log('861-w5-agent-spawn-build-hint.test: skipped live layer (win32-only surfaces); pure corpus still ran.');
  } else {
    const tmp = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w5-build-hint-'));
    const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore', windowsHide: true });
    const testSession = `agent-spawn-build-hint-test-${process.pid}`;
    const markerFile = path.join(AGENT_TELEMETRY_DIR, `agent-spawn-build-nudged-${testSession}.json`);
    try { fs.unlinkSync(markerFile); } catch { /* none yet */ }

    try {
      const row = await findOwnChildRow(child.pid);
      assert.ok(row, `spawned child pid ${child.pid} never appeared in the process table`);
      const creationFileTimeUtc = normalizeCreationTime(row.CreationFileTimeUtc);
      assert.ok(creationFileTimeUtc, 'child has no readable creation time');

      const dir = path.join(tmp, 'agent-spawns');
      // SAME-SESSION, LIVE lease: reap-eligible on every axis the §6.3 matrix has. If this
      // hook could kill anything, this is the fixture that would prove it.
      const record = await buildAgentSpawnRecord({
        recordId: 'w5-build-hint-fixture',
        producer: 'w5-build-hint-test',
        pid: child.pid,
        creationFileTimeUtc,
        cmdlineFingerprint: '-e',
        port: 40100,
        leaseDurationSec: 3600,
        sessionId: testSession,
        resourceRoots: { worktreeRoot: REPO_ROOT },
      });
      await writeAgentSpawnRecord({ dir, record });

      const env = { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: tmp };

      await check('build-shaped command with a real holder emits a named-cause hint (advisory, never blocks)', async () => {
        const out = runHook({ command: './gradlew.bat build -x test', sessionId: testSession, env });
        const parsed = JSON.parse(out);
        assert.equal(parsed.hookSpecificOutput.hookEventName, 'PreToolUse');
        assert.ok(parsed.hookSpecificOutput.additionalContext.includes('w5-build-hint-test'), 'hint should name the producer');
        assert.ok(parsed.hookSpecificOutput.additionalContext.includes('taskkill'), 'hint should carry a ready-to-run remedy line');
        assert.equal(parsed.hookSpecificOutput.permissionDecision, undefined, 'advisory only — never a permission decision');
      });

      await check('SUBPROCESS-CONTRACT PIN (861 W5 review F-7a): the deployed hook binary never kills, even against a reap-eligible fixture', async () => {
        // What this DOES prove: the real, as-invoked-by-Claude-Code hook process — the actual
        // artifact that ships — took no action against a process it could see and identify.
        // What this does NOT prove: the STRUCTURAL guarantee that `before-a-build` can never
        // mint a `reap` disposition in the first place — that lives at the projection layer
        // and is asserted directly (on `entry.disposition`) in
        // `861-w5-agent-spawn-sweep.test.mjs`'s before-a-build checks. This test is the
        // end-to-end pin on top of that guarantee: even if some future refactor gave this hook
        // a path to `executeReap`, THIS assertion would still have to fail for a regression to
        // slip through undetected here.
        await new Promise((r) => setTimeout(r, 300)); // generous margin — if it were going to die, it would have by now
        const after = readProcessTable();
        assert.ok(after.ok);
        assert.ok(after.table.some((r) => Number(r?.ProcessId) === child.pid), 'the before-a-build hint must NEVER kill the process it named, even though it is reap-eligible on every matrix axis');
      });

      await check('same holder does not re-fire twice in one session (per-session marker dedup)', () => {
        const second = runHook({ command: 'npm run build', sessionId: testSession, env });
        assert.equal(second.trim(), '', 'a second build-shaped command naming the same already-nudged holder must stay silent');
      });

      await check('non-matching command stays silent even with a live holder present', () => {
        const out = runHook({ command: 'git status', sessionId: `${testSession}-other`, env });
        assert.equal(out.trim(), '');
      });

      await check('non-Bash tool emits nothing', () => {
        const out = execFileSync('node', [HOOK], {
          input: JSON.stringify({ tool_name: 'Read', tool_input: { file_path: 'x' } }),
          encoding: 'utf8',
        });
        assert.equal(out.trim(), '');
      });

      await check('JUSTSEARCH_DISABLE_HOOKS=1 silences the hook even with a live holder present', () => {
        const out = runHook({
          command: './gradlew.bat build',
          sessionId: `${testSession}-disabled`,
          env: { ...env, JUSTSEARCH_DISABLE_HOOKS: '1' },
        });
        assert.equal(out.trim(), '');
      });

      await check('no registered holder under the target path stays silent', () => {
        const emptyTmp = tmp + '-empty';
        const out = runHook({
          command: './gradlew.bat build',
          sessionId: `${testSession}-empty`,
          env: { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: emptyTmp },
        });
        assert.equal(out.trim(), '');
      });
    } finally {
      killIfAlive(child.pid);
      try { fs.unlinkSync(markerFile); } catch { /* cleanup */ }
      await fsp.rm(tmp, { recursive: true, force: true }).catch(() => {});
    }
  }

  if (failures.length) {
    console.error(`861-w5-agent-spawn-build-hint.test: ${failures.length} FAILED / ${passed} passed`);
    for (const f of failures) console.error('  ✗ ' + f);
    process.exitCode = 1;
    return;
  }
  console.log(`861-w5-agent-spawn-build-hint.test: ${passed} passed`);
}

main();
