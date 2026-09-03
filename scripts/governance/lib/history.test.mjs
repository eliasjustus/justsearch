import assert from 'node:assert/strict';
import {
  existsSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  utimesSync,
  writeFileSync,
} from 'node:fs';
import { spawn, spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

import {
  appendRunRecord,
  readGateHistory,
  readHistory,
  readRepositoryHealthHistory,
} from './history.mjs';

const repoRoot = mkdtempSync(join(tmpdir(), 'governance-history-'));
mkdirSync(join(repoRoot, 'modules', 'a', 'src', 'main', 'java'), { recursive: true });
writeFileSync(join(repoRoot, 'settings.gradle.kts'), 'include(":modules:a")\n');
writeFileSync(join(repoRoot, 'modules', 'a', 'src', 'main', 'java', 'A.java'), 'class A {}\n');
const path = 'tmp/test-history.ndjson';

appendRunRecord({
  repoRoot,
  path,
  runs: [{ categoryId: 'sample', findings: [{ level: 'warning' }] }],
  verdicts: [{ gate: 'sample', verdict: 'pass' }],
});
const full = join(repoRoot, path);
writeFileSync(
  full,
  `${JSON.stringify({ ts: 'legacy', gate: 'legacy-gate', verdict: 'pass', findings: {} })}\n`,
  { flag: 'a' },
);

assert.equal(readHistory({ repoRoot, path }).length, 3);
const gates = readGateHistory({ repoRoot, path });
assert.deepEqual(gates.map(row => row.gate), ['sample', 'legacy-gate']);
assert.equal(gates[0].schemaVersion, 2);
const health = readRepositoryHealthHistory({ repoRoot, path });
assert.equal(health.length, 1);
assert.equal(health[0].metrics.gradleModuleCount, 1);

const boundedPath = 'tmp/bounded-history.ndjson';
for (let i = 0; i < 4; i++) {
  appendRunRecord({
    repoRoot,
    path: boundedPath,
    maxLines: 5,
    runs: [{ categoryId: `gate-${i}`, findings: [] }],
    verdicts: [{ gate: `gate-${i}`, verdict: 'pass' }],
  });
}
const boundedRaw = readFileSync(join(repoRoot, boundedPath), 'utf8');
assert.equal(
  boundedRaw.trimEnd().split(/\r?\n/).length,
  5,
  'append compacts the local history to the configured row bound',
);
assert.deepEqual(
  readGateHistory({ repoRoot, path: boundedPath }).map(row => row.gate),
  ['gate-2', 'gate-3'],
  'compaction preserves the newest complete gate rows',
);
assert.equal(
  readRepositoryHealthHistory({ repoRoot, path: boundedPath }).length,
  3,
  'mixed versioned row kinds remain readable after compaction',
);
assert.throws(
  () => appendRunRecord({ repoRoot, path: boundedPath, maxLines: 0, runs: [], verdicts: [] }),
  /positive safe integer/,
);

const stalePath = 'tmp/stale-history.ndjson';
const staleLock = `${join(repoRoot, stalePath)}.lock`;
const exitedChild = spawnSync(process.execPath, ['--eval', 'process.exit(0)']);
assert.equal(exitedChild.status, 0);
mkdirSync(staleLock);
writeFileSync(
  join(staleLock, 'owner.json'),
  `${JSON.stringify({ pid: exitedChild.pid, acquiredAt: Date.now() })}\n`,
);
appendRunRecord({
  repoRoot,
  path: stalePath,
  runs: [{ categoryId: 'after-stale-lock', findings: [] }],
  verdicts: [{ gate: 'after-stale-lock', verdict: 'pass' }],
});
assert.deepEqual(
  readGateHistory({ repoRoot, path: stalePath }).map(row => row.gate),
  ['after-stale-lock'],
  'a lock left by an exited process is safely recovered',
);

for (const [name, owner] of [
  ['malformed', {}],
  ['reused-pid', { pid: process.pid, acquiredAt: Date.now() - 120_000 }],
]) {
  const recoveryPath = `tmp/${name}-history.ndjson`;
  const recoveryLock = `${join(repoRoot, recoveryPath)}.lock`;
  mkdirSync(recoveryLock);
  writeFileSync(join(recoveryLock, 'owner.json'), `${JSON.stringify(owner)}\n`);
  if (name === 'malformed') {
    const old = new Date(Date.now() - 120_000);
    utimesSync(recoveryLock, old, old);
  }
  appendRunRecord({
    repoRoot,
    path: recoveryPath,
    runs: [{ categoryId: name, findings: [] }],
    verdicts: [{ gate: name, verdict: 'pass' }],
  });
  assert.deepEqual(
    readGateHistory({ repoRoot, path: recoveryPath }).map(row => row.gate),
    [name],
    `${name} lock metadata cannot block history forever`,
  );
}

const concurrentRoot = mkdtempSync(join(tmpdir(), 'governance-history-concurrent-'));
mkdirSync(join(concurrentRoot, 'modules', 'a', 'src', 'main', 'java'), { recursive: true });
writeFileSync(join(concurrentRoot, 'settings.gradle.kts'), 'include(":modules:a")\n');
writeFileSync(join(concurrentRoot, 'modules', 'a', 'src', 'main', 'java', 'A.java'), 'class A {}\n');
const concurrentPath = 'tmp/concurrent-history.ndjson';
const startFile = join(concurrentRoot, 'start');
const appendsPerProcess = 25;
const concurrentMaxLines = appendsPerProcess * 2 * 2;
const historyModuleUrl = pathToFileURL(join(import.meta.dirname, 'history.mjs')).href;
const childProgram = `
  import { existsSync, writeFileSync } from 'node:fs';
  import { appendRunRecord } from ${JSON.stringify(historyModuleUrl)};
  const { JS_HISTORY_ROOT, JS_HISTORY_PATH, JS_HISTORY_START, JS_HISTORY_WORKER,
    JS_HISTORY_APPENDS, JS_HISTORY_MAX_LINES } = process.env;
  writeFileSync(\`${'${JS_HISTORY_START}'}.\${JS_HISTORY_WORKER}.ready\`, 'ready');
  while (!existsSync(JS_HISTORY_START)) {
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 5);
  }
  for (let i = 0; i < Number(JS_HISTORY_APPENDS); i++) {
    const gate = \`worker-\${JS_HISTORY_WORKER}-\${i}\`;
    appendRunRecord({
      repoRoot: JS_HISTORY_ROOT,
      path: JS_HISTORY_PATH,
      maxLines: Number(JS_HISTORY_MAX_LINES),
      runs: [{ categoryId: gate, findings: [] }],
      verdicts: [{ gate, verdict: 'pass' }],
    });
  }
`;

const children = ['a', 'b'].map(worker => spawn(
  process.execPath,
  ['--input-type=module', '--eval', childProgram],
  {
    env: {
      ...process.env,
      JS_HISTORY_ROOT: concurrentRoot,
      JS_HISTORY_PATH: concurrentPath,
      JS_HISTORY_START: startFile,
      JS_HISTORY_WORKER: worker,
      JS_HISTORY_APPENDS: String(appendsPerProcess),
      JS_HISTORY_MAX_LINES: String(concurrentMaxLines),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  },
));

await waitUntil(
  () => ['a', 'b'].every(worker => existsSync(`${startFile}.${worker}.ready`)),
  5_000,
  'history workers did not reach the start barrier',
);
writeFileSync(startFile, 'go');
await Promise.all(children.map(waitForChild));

const concurrentRows = readHistory({ repoRoot: concurrentRoot, path: concurrentPath });
assert.equal(
  concurrentRows.length,
  concurrentMaxLines,
  'coordinated writers retain exactly the configured maximum number of rows',
);
const concurrentGates = readGateHistory({ repoRoot: concurrentRoot, path: concurrentPath });
assert.equal(concurrentGates.length, appendsPerProcess * 2, 'no gate-run append is lost');
assert.equal(
  new Set(concurrentGates.map(row => row.gate)).size,
  appendsPerProcess * 2,
  'every coordinated child append remains distinct',
);

async function waitUntil(predicate, timeoutMs, message) {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(message);
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}

function waitForChild(child) {
  return new Promise((resolve, reject) => {
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', chunk => { stdout += chunk; });
    child.stderr.on('data', chunk => { stderr += chunk; });
    child.on('error', reject);
    child.on('close', code => {
      if (code === 0) resolve();
      else reject(new Error(`history worker exited ${code}\n${stdout}${stderr}`));
    });
  });
}

console.log('history.test: OK');
