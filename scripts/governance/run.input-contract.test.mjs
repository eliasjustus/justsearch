/**
 * Gate input-contract tests - tempdoc 742 D1.
 *
 * Two layers, matching the repo's governance test convention (a small
 * assert/harness + process.exit(1) on any failure, discovered by `node --test`):
 *   1. Unit tests over the pure helper `scripts/governance/lib/input-contract.mjs`.
 *   2. Process-level tests that spawn `node scripts/governance/run.mjs` against a
 *      scratch `--registry` so the runner's input-contract wiring is exercised
 *      end-to-end (a missing required input fails exit 1; a missing on-demand
 *      input is skipped; --produce-inputs runs the producer then evaluates).
 *   3. A malformed-report fail-closed check via the dead-code enforcer directly.
 *
 * Run with: `node scripts/governance/run.input-contract.test.mjs`
 *   (also covered by `node --test scripts/governance/`).
 */

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  evaluateGateInputs,
  requiredInputsToProduce,
  INPUT_MISSING_RULE,
  INPUT_SKIPPED_RULE,
} from './lib/input-contract.mjs';
import { enforceDeadCode } from './gates/dead-code/enforcer.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const RUN_MJS = path.join(REPO_ROOT, 'scripts', 'governance', 'run.mjs');

let passed = 0;
const failures = [];
const tmpDirs = [];
const tmpFiles = [];

async function run(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

function mkTmpDir() {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), 'input-contract-'));
  tmpDirs.push(d);
  return d;
}

/** Write a scratch registry to a temp file; returns its absolute path. */
function writeRegistry(gates) {
  const p = path.join(mkTmpDir(), 'registry.json');
  fs.writeFileSync(
    p,
    JSON.stringify({ kind: 'discipline-gate-registry.v1', version: 1, gates }, null, 2),
  );
  return p;
}

function runGovernance(args) {
  const outSarif = path.join(mkTmpDir(), 'out.sarif');
  return spawnSync(
    process.execPath,
    [RUN_MJS, ...args, '--out', outSarif, '--format', 'compact'],
    { cwd: REPO_ROOT, encoding: 'utf8' },
  );
}

// ----- Layer 1: helper unit tests -----------------------------------------

await run('helper: required + absent -> fail verdict + producer in message', async () => {
  const gate = {
    config: {
      inputs: [{ path: 'tmp/does-not-exist.json', producer: 'do-the-thing', class: 'required' }],
    },
  };
  const r = evaluateGateInputs({ gate, repoRoot: REPO_ROOT, fileExists: () => false });
  assert.equal(r.verdict, 'fail');
  assert.equal(r.findings[0].ruleId, INPUT_MISSING_RULE);
  assert.equal(r.findings[0].level, 'error');
  assert.ok(r.findings[0].message.includes('do-the-thing'), r.findings[0].message);
  assert.ok(r.findings[0].message.includes('tmp/does-not-exist.json'), r.findings[0].message);
});

await run('helper: required + present -> null (enforcer dispatched)', async () => {
  const gate = {
    config: { inputs: [{ path: 'tmp/present.json', producer: 'x', class: 'required' }] },
  };
  const r = evaluateGateInputs({ gate, repoRoot: REPO_ROOT, fileExists: () => true });
  assert.equal(r, null);
});

await run('helper: on-demand + absent -> skipped', async () => {
  const gate = {
    config: { inputs: [{ path: 'tmp/on-demand.json', producer: 'run-pit', class: 'on-demand' }] },
  };
  const r = evaluateGateInputs({ gate, repoRoot: REPO_ROOT, fileExists: () => false });
  assert.equal(r.verdict, 'skipped');
  assert.equal(r.findings[0].ruleId, INPUT_SKIPPED_RULE);
  assert.equal(r.findings[0].level, 'note');
  assert.ok(r.findings[0].message.includes('run-pit'), r.findings[0].message);
});

await run('helper: required missing dominates on-demand missing', async () => {
  const gate = {
    config: {
      inputs: [
        { path: 'tmp/od.json', producer: 'od', class: 'on-demand' },
        { path: 'tmp/req.json', producer: 'req', class: 'required' },
      ],
    },
  };
  const r = evaluateGateInputs({ gate, repoRoot: REPO_ROOT, fileExists: () => false });
  assert.equal(r.verdict, 'fail');
  assert.equal(r.findings[0].ruleId, INPUT_MISSING_RULE);
});

await run('helper: no declared inputs -> null', async () => {
  const r = evaluateGateInputs({ gate: { config: {} }, repoRoot: REPO_ROOT, fileExists: () => false });
  assert.equal(r, null);
});

await run('helper: requiredInputsToProduce returns absent required only', async () => {
  const gate = {
    config: {
      inputs: [
        { path: 'tmp/present-req.json', producer: 'a', class: 'required' },
        { path: 'tmp/absent-req.json', producer: 'b', class: 'required' },
        { path: 'tmp/absent-od.json', producer: 'c', class: 'on-demand' },
      ],
    },
  };
  const present = new Set([path.resolve(REPO_ROOT, 'tmp/present-req.json')]);
  const toProduce = requiredInputsToProduce({
    gate,
    repoRoot: REPO_ROOT,
    fileExists: (p) => present.has(p),
  });
  assert.deepEqual(
    toProduce.map((i) => i.path),
    ['tmp/absent-req.json'],
  );
});

// ----- Layer 2: process-level runner tests --------------------------------

await run('runner: required-missing -> exit 1 with kernel/input-missing', async () => {
  const registry = writeRegistry([
    {
      id: 'scratch-req',
      title: 'scratch required-input gate',
      enforcer: 'scripts/governance/gates/dead-code/enforcer.mjs',
      baseline: { kind: 'ratchet-file', path: 'gates/dead-code/baseline.txt' },
      config: {
        inputs: [
          {
            path: 'tmp/scratch-req-absent.json',
            producer: 'echo produce-me-first',
            class: 'required',
          },
        ],
      },
    },
  ]);
  const res = runGovernance(['--registry', registry, '--gate', 'scratch-req', '--mode', 'gate']);
  assert.equal(res.status, 1, `stdout:\n${res.stdout}\nstderr:\n${res.stderr}`);
  const out = res.stdout + res.stderr;
  assert.ok(out.includes('kernel/input-missing'), out);
  assert.ok(out.includes('echo produce-me-first'), out);
  assert.ok(out.includes('scratch-req: fail'), out);
});

await run('runner: on-demand-missing -> exit 0 with verdict skipped', async () => {
  const registry = writeRegistry([
    {
      id: 'scratch-od',
      title: 'scratch on-demand-input gate',
      enforcer: 'scripts/governance/gates/test-efficacy/enforcer.mjs',
      baseline: { kind: 'ratchet-file', path: 'gates/test-efficacy/strength-baseline.v1.json' },
      config: {
        inputs: [
          { path: 'tmp/scratch-od-absent.json', producer: 'run-the-pit', class: 'on-demand' },
        ],
      },
    },
  ]);
  const res = runGovernance(['--registry', registry, '--gate', 'scratch-od', '--mode', 'gate']);
  assert.equal(res.status, 0, `stdout:\n${res.stdout}\nstderr:\n${res.stderr}`);
  assert.ok(res.stdout.includes('scratch-od: skipped'), res.stdout);
});

await run('runner: --produce-inputs runs producer then evaluates for real', async () => {
  const reportRel = `tmp/input-contract-produced-${Date.now()}.json`;
  const reportAbs = path.resolve(REPO_ROOT, reportRel);
  tmpFiles.push(reportAbs);
  if (fs.existsSync(reportAbs)) fs.rmSync(reportAbs);

  const registry = writeRegistry([
    {
      id: 'scratch-produce',
      title: 'scratch produce-inputs gate',
      enforcer: 'scripts/governance/gates/dead-code/enforcer.mjs',
      baseline: { kind: 'ratchet-file', path: 'tmp/nonexistent-baseline.txt' },
      config: {
        reportPath: reportRel,
        inputs: [
          {
            path: reportRel,
            producer: `${process.execPath} -e "require('fs').writeFileSync(process.argv[1],'{}')" ${reportRel}`,
            class: 'required',
          },
        ],
      },
    },
  ]);
  const res = runGovernance([
    '--registry',
    registry,
    '--gate',
    'scratch-produce',
    '--mode',
    'gate',
    '--produce-inputs',
  ]);
  assert.equal(res.status, 0, `stdout:\n${res.stdout}\nstderr:\n${res.stderr}`);
  assert.ok(fs.existsSync(reportAbs), 'producer should have written the report');
  assert.ok(res.stdout.includes('scratch-produce: pass'), res.stdout);
});

await run('runner: ./-relative producer resolves to absolute path (NoDefaultCurrentDirectoryInExePath)', async () => {
  // Windows excludes the cwd from cmd.exe's executable search when
  // NoDefaultCurrentDirectoryInExePath is set, so a bare "./tool.cmd ..."
  // producer only works if the runner resolves it absolute (run.mjs does).
  // The trap and the .cmd producer are Windows-only; on other platforms
  // (the hosted CI runner, tempdoc 884 B1 wired this suite there) the
  // check has nothing to pin, so it passes vacuously rather than
  // failing on a batch file the shell cannot run.
  if (process.platform !== 'win32') return;
  const stamp = Date.now();
  const reportRel = `tmp/input-contract-dotslash-${stamp}.json`;
  const reportAbs = path.resolve(REPO_ROOT, reportRel);
  const producerRel = `tmp/input-contract-producer-${stamp}.cmd`;
  const producerAbs = path.resolve(REPO_ROOT, producerRel);
  tmpFiles.push(reportAbs, producerAbs);
  fs.writeFileSync(producerAbs, `@echo off\r\necho {} > "${reportAbs}"\r\n`);

  const registry = writeRegistry([
    {
      id: 'scratch-dotslash',
      title: 'scratch dot-slash producer gate',
      enforcer: 'scripts/governance/gates/dead-code/enforcer.mjs',
      baseline: { kind: 'ratchet-file', path: 'tmp/nonexistent-baseline.txt' },
      config: {
        reportPath: reportRel,
        inputs: [{ path: reportRel, producer: `./${producerRel}`, class: 'required' }],
      },
    },
  ]);
  const res = runGovernance([
    '--registry',
    registry,
    '--gate',
    'scratch-dotslash',
    '--mode',
    'gate',
    '--produce-inputs',
  ]);
  assert.equal(res.status, 0, `stdout:\n${res.stdout}\nstderr:\n${res.stderr}`);
  assert.ok(fs.existsSync(reportAbs), 'dot-slash producer should have written the report');
  assert.ok(res.stdout.includes('scratch-dotslash: pass'), res.stdout);
});

// ----- Layer 3: malformed report fails closed -----------------------------

await run('enforcer: malformed dead-code report -> fail (fail-closed)', async () => {
  const root = mkTmpDir();
  fs.mkdirSync(path.join(root, 'tmp'), { recursive: true });
  fs.writeFileSync(path.join(root, 'tmp', 'knip-report.json'), 'not valid json {');
  const gate = {
    config: { reportPath: 'tmp/knip-report.json' },
    baseline: { path: 'gates/dead-code/baseline.txt' },
  };
  const r = await enforceDeadCode({ repoRoot: root, gate, fixtureMode: true, fixtureRoot: root });
  assert.equal(r.verdict, 'fail', JSON.stringify(r.findings));
  assert.ok(
    r.findings.some((f) => f.ruleId === 'dead-code/report-malformed'),
    JSON.stringify(r.findings),
  );
});

// ----- teardown / report --------------------------------------------------

for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true });
for (const f of tmpFiles) {
  try {
    if (fs.existsSync(f)) fs.rmSync(f);
  } catch {
    /* best-effort */
  }
}

if (failures.length > 0) {
  console.error(`run.input-contract.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`run.input-contract.test: all ${passed} checks passed`);
