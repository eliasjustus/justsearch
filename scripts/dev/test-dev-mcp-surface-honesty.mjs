#!/usr/bin/env node
//
// Tempdoc 844 §12.2 — unit tests for the honesty contract on the dev MCP surface:
// "a dev tool must not report state it did not verify, and must not report success it did not
// confirm; where it cannot verify something, it says so."
//
// Covered, in the order of §12.3:
//   B1  resolveDistRoot     — preflight/start resolve the SAME tree; bare worktree names resolve;
//                             a bad name is refused with the names that DO exist.
//   B3  probeForeignRuns    — null (did not look) is distinct from [] (looked, found nothing);
//                             owned vs observed-but-unowned never merge; unknown stays unknown.
//   B4a projectJsonPath     — a miss returns the available keys, never the body; array indexing.
//   B4b truncationNotice    — a maxBytes overrun is a declared truncation, not a failed call.
//
// Pure unit tests: no dev stack, no subprocess, no network (the port probe is injected).
//
// Run: node scripts/dev/test-dev-mcp-surface-honesty.mjs
//

import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {
  FOREIGN_BACKEND_PORTS,
  parseJsonPathExpr,
  probeForeignRuns,
  projectJsonPath,
  resolveDistRoot,
  truncationNotice,
} from './justsearch-dev-mcp/server.mjs';

// server.mjs installs process-level uncaughtException/unhandledRejection handlers that LOG rather
// than exit, so a top-level abort in this file would otherwise leave exit code 0 — a green that
// ran nothing. Fail closed: non-zero until the runner at the bottom clears it.
process.exitCode = 1;

const HERE = import.meta.dirname;

/* ── B1: the tree preflight checks is the tree start launches from ─────────────────────────── */

/** A throwaway main-repo shape: `<tmp>/main` with two worktrees, one of them not a checkout. */
async function makeFixture() {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), 'jsdev-844-'));
  const main = path.join(root, 'main');
  await fsp.mkdir(path.join(main, 'scripts', 'dev'), { recursive: true });
  await fsp.writeFile(path.join(main, 'scripts', 'dev', 'dev-runner.cjs'), '// fixture\n', 'utf8');
  for (const name of ['round14', 'validate-main']) {
    const wt = path.join(main, '.claude', 'worktrees', name);
    await fsp.mkdir(path.join(wt, 'scripts', 'dev'), { recursive: true });
    await fsp.writeFile(path.join(wt, 'scripts', 'dev', 'dev-runner.cjs'), '// fixture\n', 'utf8');
  }
  // A worktree directory that is NOT a usable checkout (no dev-runner.cjs).
  await fsp.mkdir(path.join(main, '.claude', 'worktrees', 'half-made'), { recursive: true });
  return { root, main };
}

const fx = await makeFixture();
const CALLER = path.join(fx.main, '.claude', 'worktrees', 'round14');

const distTests = [
  ['distFrom omitted → the caller checkout, unchanged behaviour', async () => {
    const r = await resolveDistRoot({ distFrom: undefined, mainRepoRoot: fx.main, fallbackRepoRoot: CALLER, fallbackDevRunnerPath: 'X' });
    assert.equal(r.ok, true);
    assert.equal(r.repoRoot, CALLER);
    assert.equal(r.devRunnerPath, 'X');
    assert.equal(r.distFrom, null);
    assert.equal(r.resolvedVia, 'caller-checkout');
  }],
  ['a bare worktree NAME resolves against .claude/worktrees (was INVALID_DIST_FROM)', async () => {
    const r = await resolveDistRoot({ distFrom: 'validate-main', mainRepoRoot: fx.main, fallbackRepoRoot: fx.main });
    assert.equal(r.ok, true);
    assert.equal(r.resolvedVia, 'worktree-name');
    assert.equal(r.repoRoot, path.join(fx.main, '.claude', 'worktrees', 'validate-main'));
  }],
  ['preflight and start resolve the SAME root for the same distFrom', async () => {
    const asStart = await resolveDistRoot({ distFrom: 'round14', mainRepoRoot: fx.main, fallbackRepoRoot: fx.main, fallbackDevRunnerPath: 'a' });
    const asPreflight = await resolveDistRoot({ distFrom: 'round14', mainRepoRoot: fx.main, fallbackRepoRoot: CALLER, fallbackDevRunnerPath: 'b' });
    assert.equal(asStart.repoRoot, asPreflight.repoRoot);
    // …and it is NOT the invoking checkout — the false green in §5.1 was checking that one.
    assert.notEqual(asPreflight.repoRoot, fx.main);
  }],
  ['a relative path to a worktree still works', async () => {
    const r = await resolveDistRoot({ distFrom: path.join('.claude', 'worktrees', 'round14'), mainRepoRoot: fx.main, fallbackRepoRoot: fx.main });
    assert.equal(r.ok, true);
    assert.equal(r.resolvedVia, 'path');
    assert.equal(r.repoRoot, CALLER);
  }],
  ['the main repo itself is accepted', async () => {
    const r = await resolveDistRoot({ distFrom: fx.main, mainRepoRoot: fx.main, fallbackRepoRoot: fx.main });
    assert.equal(r.ok, true);
    assert.equal(r.repoRoot, fx.main);
  }],
  ['an unknown bare name is refused, and the refusal lists the names that DO exist', async () => {
    const r = await resolveDistRoot({ distFrom: 'no-such-tree', mainRepoRoot: fx.main, fallbackRepoRoot: fx.main });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'INVALID_DIST_FROM');
    assert.match(r.error.message, /round14/);
    assert.match(r.error.message, /validate-main/);
  }],
  ['a directory outside the repo is still refused', async () => {
    const r = await resolveDistRoot({ distFrom: path.join(fx.root, 'elsewhere'), mainRepoRoot: fx.main, fallbackRepoRoot: fx.main });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'INVALID_DIST_FROM');
  }],
  ['a worktree directory without dev-runner.cjs is refused, naming the missing file', async () => {
    const r = await resolveDistRoot({ distFrom: 'half-made', mainRepoRoot: fx.main, fallbackRepoRoot: fx.main });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'INVALID_DIST_FROM');
    assert.match(r.error.message, /dev-runner\.cjs not found/);
  }],
];

/* ── B3: "free" must not be claimed about a machine that was not looked at ─────────────────── */

/** An injectable probe: 200 for the listed ports, null (nothing listening) otherwise. */
const listening = (...ports) => async (url) => {
  const port = Number(new URL(url).port);
  return ports.includes(port) ? 200 : null;
};

const foreignTests = [
  ['probing off → null, NOT [] — "I did not look" is not "I found nothing"', async () => {
    const r = await probeForeignRuns({ enabled: false, hasActiveRun: false, probe: listening(33221) });
    assert.equal(r, null);
  }],
  ['probed, nothing listening → [] (looked, found nothing)', async () => {
    const r = await probeForeignRuns({ enabled: true, hasActiveRun: false, probe: listening() });
    assert.deepEqual(r, []);
  }],
  ['a jseval backend on 33221 is reported as observed-but-unowned', async () => {
    const r = await probeForeignRuns({ enabled: true, hasActiveRun: false, probe: listening(33221) });
    assert.equal(r.length, 1);
    assert.deepEqual(r[0], { port: 33221, kind: 'backend', probePath: '/api/status', attribution: 'unowned' });
  }],
  ['33221 is the DEFAULT probed port (jseval binds it hardcoded)', () => {
    assert.ok(FOREIGN_BACKEND_PORTS.includes(33221));
  }],
  ['the OWNED run\'s own api port is never reported as foreign', async () => {
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: true, ownedApiPort: 33221, ports: [33221], probe: listening(33221),
    });
    assert.deepEqual(r, []);
  }],
  ['the owned run\'s own llama-server is not reported as foreign when aiActive is true', async () => {
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: true, aiActive: true, inferencePort: 8080, probe: listening(8080),
    });
    assert.deepEqual(r, []);
  }],
  ['an inference listener the run disclaims (aiActive false) is unowned', async () => {
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: true, aiActive: false, inferencePort: 8080, probe: listening(8080),
    });
    assert.equal(r[0].attribution, 'unowned');
    assert.equal(r[0].kind, 'inference');
  }],
  ['an inference listener that cannot be attributed says "unknown", not "unowned"', async () => {
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: true, aiActive: null, inferencePort: 8080, probe: listening(8080),
    });
    assert.equal(r[0].attribution, 'unknown');
  }],
  ['with no active run at all, an inference listener is definitely unowned', async () => {
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, aiActive: null, inferencePort: 8080, probe: listening(8080),
    });
    assert.equal(r[0].attribution, 'unowned');
  }],
  ['a probe that throws yields null (did not look), not [] (found nothing)', async () => {
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, probe: async () => { throw new Error('probe exploded'); },
    });
    assert.equal(r, null);
  }],
  ['no subprocess: probeForeignRuns only calls the injected probe', async () => {
    const seen = [];
    await probeForeignRuns({
      enabled: true, hasActiveRun: false, ports: [33221], inferencePort: 8080,
      probe: async (url) => { seen.push(url); return null; },
    });
    assert.deepEqual(seen.sort(), ['http://127.0.0.1:33221/api/status', 'http://127.0.0.1:8080/health']);
  }],
];

/* ── B4a: a typo must cost a hint, not the whole payload ───────────────────────────────────── */

const DOC = {
  llm: { model_path: 'C:/models/x.gguf', gpuLayers: 99 },
  results: [{ fields: { path: 'a.txt' } }, { fields: { path: 'b.txt' } }],
  empty: [],
  nil: null,
  count: 3,
};

const jsonPathTests = [
  ['a hit returns the projected subtree', () => {
    assert.deepEqual(projectJsonPath(DOC, 'llm'), { ok: true, value: DOC.llm });
    assert.deepEqual(projectJsonPath(DOC, 'llm.model_path'), { ok: true, value: 'C:/models/x.gguf' });
  }],
  ['array indexing works (a.b[0].c)', () => {
    assert.deepEqual(projectJsonPath(DOC, 'results[0].fields.path'), { ok: true, value: 'a.txt' });
    assert.deepEqual(projectJsonPath(DOC, 'results[1].fields'), { ok: true, value: { path: 'b.txt' } });
  }],
  ['parseJsonPathExpr splits keys and indices', () => {
    assert.deepEqual(parseJsonPathExpr('a.b[0].c'), ['a', 'b', 0, 'c']);
    assert.deepEqual(parseJsonPathExpr('a[0][1]'), ['a', 0, 1]);
    assert.equal(parseJsonPathExpr('a..b'), null);
    assert.equal(parseJsonPathExpr(''), null);
  }],
  ['a top-level miss names the available keys and does NOT carry the body', () => {
    const r = projectJsonPath(DOC, 'lmm');
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'JSON_PATH_MISS');
    assert.deepEqual(r.available.keys, ['llm', 'results', 'empty', 'nil', 'count']);
    for (const key of Object.keys(DOC)) assert.match(r.error.message, new RegExp(key));
    // The point of the fix: the payload itself is not in the answer.
    assert.ok(!JSON.stringify(r).includes('C:/models/x.gguf'), 'the response body must not be returned on a miss');
  }],
  ['a nested miss names the DEEPEST level that resolved', () => {
    const r = projectJsonPath(DOC, 'llm.modelPath');
    assert.equal(r.ok, false);
    assert.equal(r.resolvedPrefix, 'llm');
    assert.deepEqual(r.available.keys, ['model_path', 'gpuLayers']);
    assert.match(r.error.message, /model_path/);
  }],
  ['an out-of-range index reports the array length instead of keys', () => {
    const r = projectJsonPath(DOC, 'results[7].fields');
    assert.equal(r.ok, false);
    assert.equal(r.available.kind, 'array');
    assert.equal(r.available.length, 2);
    assert.match(r.error.message, /\[0\]\.\.\[1\]/);
  }],
  ['an empty array says so rather than offering an index', () => {
    const r = projectJsonPath(DOC, 'empty[0]');
    assert.equal(r.ok, false);
    assert.match(r.error.message, /empty/);
  }],
  ['descending into a scalar or null says what it is', () => {
    assert.match(projectJsonPath(DOC, 'count.deeper').error.message, /is number/);
    assert.match(projectJsonPath(DOC, 'nil.deeper').error.message, /is null/);
  }],
  ['a malformed expression is INVALID, not a silent miss', () => {
    const r = projectJsonPath(DOC, 'llm..model_path');
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'JSON_PATH_INVALID');
  }],
  ['many keys are capped but the total is declared', () => {
    const wide = Object.fromEntries(Array.from({ length: 80 }, (_, i) => [`k${i}`, i]));
    const r = projectJsonPath(wide, 'nope');
    assert.equal(r.available.keys.length, 50);
    assert.equal(r.available.keysTotal, 80);
  }],
];

/* ── B4b: exceeding maxBytes is a declared truncation, not a failed call ───────────────────── */

const truncationTests = [
  ['the notice names the bytes read, the limit, and that it was truncated', () => {
    const n = truncationNotice({ bytesRead: 2000, maxBytes: 2000 });
    assert.equal(n.code, 'RESPONSE_TRUNCATED');
    assert.match(n.message, /TRUNCATED/);
    assert.match(n.message, /2000 bytes/);
    assert.match(n.message, /maxBytes=2000/);
  }],
  ['the notice corrects the misconception that maxBytes shrinks output', () => {
    const n = truncationNotice({ bytesRead: 2000, maxBytes: 2000 });
    assert.match(n.message, /does NOT parse as JSON/);
    assert.match(n.message, /jsonPath/);
  }],
];

/* ── B2: a fully-understood, recoverable condition is not an unhandled exception ───────────── */
//
// Honest limit: exercising this end-to-end means running `dev-runner start`, i.e. launching a
// stack, which these tests deliberately do not do. What IS asserted here is the classification and
// the transport contract that carries it — the throw site attaches a code, and the `--json`
// writer forwards `err.code`/`err.details` instead of flattening everything to UNHANDLED. The
// writer's default branch is separately observable with `node scripts/dev/dev-runner.cjs bogus --json`.

const devRunnerSrc = await fsp.readFile(path.join(HERE, 'dev-runner.cjs'), 'utf8');

const distCodeTests = [
  ['the missing-Head-dist throw carries code DIST_NOT_BUILT', () => {
    const site = devRunnerSrc.slice(devRunnerSrc.indexOf('Head dist not found at'));
    assert.match(site.slice(0, 900), /err\.code = 'DIST_NOT_BUILT'/);
  }],
  ['…and structured context: the offending path and the remedy', () => {
    const site = devRunnerSrc.slice(devRunnerSrc.indexOf('Head dist not found at'));
    assert.match(site.slice(0, 900), /err\.details = \{ distPath: startScript, repoRoot, remedy \}/);
  }],
  ['the --json writer forwards a classified code rather than flattening to UNHANDLED', () => {
    assert.match(devRunnerSrc, /code: err\?\.code \|\| 'UNHANDLED'/);
  }],
  ['…and forwards err.details so a consumer need not scrape the message', () => {
    assert.match(devRunnerSrc, /\.\.\.\(err\?\.details \? \{ details: err\.details \} : \{\}\)/);
  }],
  ['the documented error-code table lists DIST_NOT_BUILT and INVALID_DIST_FROM', async () => {
    const doc = await fsp.readFile(
      path.join(HERE, '..', '..', 'docs', 'reference', 'contributing', 'mcp-dev-tools.md'), 'utf8');
    const section = doc.slice(doc.indexOf('## Start-Tool Error Codes'));
    assert.match(section, /`DIST_NOT_BUILT`/);
    assert.match(section, /`INVALID_DIST_FROM`/);
  }],
];

/* ── run ───────────────────────────────────────────────────────────────────────────────────── */

let pass = 0;
let fail = 0;
for (const [name, fn] of [...distTests, ...distCodeTests, ...foreignTests, ...jsonPathTests, ...truncationTests]) {
  try {
    await fn();
    console.log(`  PASS  ${name}`);
    pass += 1;
  } catch (e) {
    console.error(`  FAIL  ${name}: ${e.message}`);
    fail += 1;
  }
}
await fsp.rm(fx.root, { recursive: true, force: true });
console.log(`test-dev-mcp-surface-honesty: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
