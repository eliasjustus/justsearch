/**
 * Unit tests for the ADR premise-probe engine (tempdoc 884).
 *
 * Every kind is driven through BOTH branches: a probe that cannot fail is not a probe,
 * and the self-test fixtures assert only the gate verdict, so this file is where the
 * engine's discrimination actually lives (884 review B1).
 *
 * Run with: `node scripts/governance/gates/adr-coverage/probes.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { evaluateProbe, loadProbeRegister, PROBE_KINDS } from './probes.mjs';

let passed = 0;
const failures = [];
const tmpDirs = [];

function scaffold(files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'adr-probes-'));
  tmpDirs.push(root);
  for (const [rel, content] of Object.entries(files)) {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content, 'utf8');
  }
  return root;
}

async function run(label, fn) {
  try { await fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const REGISTRY = JSON.stringify({ gates: [{ id: 'observed-happening' }, { id: 'wire' }] });

// --------------------------------------------------------------------------- kind: test

await run('test: pinned member still declared → pass', async () => {
  const root = scaffold({ 'src/FooTest.java': 'class FooTest { void pinnedRule() {} }' });
  const r = evaluateProbe({ kind: 'test', file: 'src/FooTest.java', test: 'a.b.FooTest#pinnedRule' }, root);
  assert.equal(r.ok, true, r.detail);
});

await run('test: member renamed away → fail', async () => {
  const root = scaffold({ 'src/FooTest.java': 'class FooTest { void renamed() {} }' });
  const r = evaluateProbe({ kind: 'test', file: 'src/FooTest.java', test: 'a.b.FooTest#pinnedRule' }, root);
  assert.equal(r.ok, false);
  assert.match(r.detail, /no longer declares 'pinnedRule'/);
});

await run('test: file deleted → fail (not vacuous pass)', async () => {
  const root = scaffold({ 'src/Other.java': 'x' });
  const r = evaluateProbe({ kind: 'test', file: 'src/FooTest.java', test: 'a.b.FooTest#pinnedRule' }, root);
  assert.equal(r.ok, false);
  assert.match(r.detail, /does not exist/);
});

// --------------------------------------------------------------------------- kind: gate

await run('gate: registered kernel gate id → pass', async () => {
  const root = scaffold({ 'governance/registry.v1.json': REGISTRY });
  assert.equal(evaluateProbe({ kind: 'gate', gate: 'observed-happening' }, root).ok, true);
});

await run('gate: unregistered gate id → fail', async () => {
  const root = scaffold({ 'governance/registry.v1.json': REGISTRY });
  const r = evaluateProbe({ kind: 'gate', gate: 'ghost-gate' }, root);
  assert.equal(r.ok, false);
  assert.match(r.detail, /no longer registered/);
});

await run('gate: script invoked from the pre-merge table → pass', async () => {
  const root = scaffold({
    'scripts/ci/check-thing.mjs': '// check',
    'CLAUDE.md': '| something | `check-thing` |',
  });
  const r = evaluateProbe({ kind: 'gate', script: 'scripts/ci/check-thing.mjs' }, root);
  assert.equal(r.ok, true, r.detail);
  assert.match(r.detail, /invoked from CLAUDE\.md/);
});

await run('gate: script invoked from a workflow → pass', async () => {
  const root = scaffold({
    'scripts/ci/check-thing.mjs': '// check',
    'CLAUDE.md': '# nothing here',
    '.github/workflows/ci.yml': 'run: node scripts/ci/check-thing.mjs',
  });
  assert.equal(evaluateProbe({ kind: 'gate', script: 'scripts/ci/check-thing.mjs' }, root).ok, true);
});

await run('gate: script exists but nothing invokes it → fail', async () => {
  const root = scaffold({
    'scripts/ci/check-thing.mjs': '// check',
    'CLAUDE.md': '# nothing here',
    '.github/workflows/ci.yml': 'run: node scripts/ci/check-other.mjs',
  });
  const r = evaluateProbe({ kind: 'gate', script: 'scripts/ci/check-thing.mjs' }, root);
  assert.equal(r.ok, false);
  assert.match(r.detail, /nothing invokes it/);
});

await run('gate: script deleted → fail', async () => {
  const root = scaffold({ 'CLAUDE.md': '`check-thing`' });
  const r = evaluateProbe({ kind: 'gate', script: 'scripts/ci/check-thing.mjs' }, root);
  assert.equal(r.ok, false);
  assert.match(r.detail, /no longer exists/);
});

// ------------------------------------------------------------------- kinds: grep present/absent

await run('grep-present: match found → pass', async () => {
  const root = scaffold({ 'a/b.java': 'public static final int SIZE = 64;' });
  assert.equal(evaluateProbe({ kind: 'grep-present', pattern: 'SIZE = 64', paths: ['a/b.java'] }, root).ok, true);
});

await run('grep-present: no match → fail', async () => {
  const root = scaffold({ 'a/b.java': 'public static final int SIZE = 32;' });
  const r = evaluateProbe({ kind: 'grep-present', pattern: 'SIZE = 64', paths: ['a/b.java'] }, root);
  assert.equal(r.ok, false);
  assert.match(r.detail, /no match/);
});

await run('grep-present with expect: exact count → pass', async () => {
  const root = scaffold({ 'a/b.java': 'tool("x")\ntool("y")\n' });
  assert.equal(evaluateProbe({ kind: 'grep-present', pattern: 'tool\\("', paths: ['a/b.java'], expect: 2 }, root).ok, true);
});

await run('grep-present with expect: count drifted UP → fail (the ADR-0015 shape)', async () => {
  const root = scaffold({ 'a/b.java': 'tool("x")\ntool("y")\ntool("z")\n' });
  const r = evaluateProbe({ kind: 'grep-present', pattern: 'tool\\("', paths: ['a/b.java'], expect: 2 }, root);
  assert.equal(r.ok, false);
  assert.match(r.detail, /expected 2 match\(es\).*found 3/);
});

await run('grep-present with expect: count drifted DOWN → fail', async () => {
  const root = scaffold({ 'a/b.java': 'tool("x")\n' });
  assert.equal(evaluateProbe({ kind: 'grep-present', pattern: 'tool\\("', paths: ['a/b.java'], expect: 2 }, root).ok, false);
});

await run('grep-present: multiline regex spans a line break', async () => {
  const root = scaffold({ 'a/b.java': 'tool(\n    "justsearch_answer",\n' });
  assert.equal(evaluateProbe({ kind: 'grep-present', pattern: 'tool\\(\\s*"justsearch_[a-z_]+"', paths: ['a/b.java'] }, root).ok, true);
});

await run('grep-absent: symbol gone → pass', async () => {
  const root = scaffold({ 'a/b.java': 'nothing here' });
  assert.equal(evaluateProbe({ kind: 'grep-absent', pattern: 'FORBIDDEN_FLAG', paths: ['a'] }, root).ok, true);
});

await run('grep-absent: symbol reintroduced → fail, and names the file', async () => {
  const root = scaffold({ 'a/b.java': 'if (FORBIDDEN_FLAG) {}' });
  const r = evaluateProbe({ kind: 'grep-absent', pattern: 'FORBIDDEN_FLAG', paths: ['a'] }, root);
  assert.equal(r.ok, false);
  assert.match(r.detail, /now appears 1 time\(s\).*a\/b\.java/);
});

await run('grep: include filter restricts the scanned extensions', async () => {
  const root = scaffold({ 'a/b.md': 'FORBIDDEN_FLAG in prose', 'a/c.java': 'clean' });
  assert.equal(evaluateProbe({ kind: 'grep-absent', pattern: 'FORBIDDEN_FLAG', paths: ['a'], include: ['.java'] }, root).ok, true);
  assert.equal(evaluateProbe({ kind: 'grep-absent', pattern: 'FORBIDDEN_FLAG', paths: ['a'], include: ['.md'] }, root).ok, false);
});

await run('grep: paths that match no file → fail, never vacuous pass', async () => {
  const root = scaffold({ 'a/b.java': 'x' });
  const r = evaluateProbe({ kind: 'grep-absent', pattern: 'ANY', paths: ['does/not/exist'] }, root);
  assert.equal(r.ok, false);
  assert.match(r.detail, /matched no files/);
});

// --------------------------------------------------------------------------- kind: json-path

const DOC = JSON.stringify({ categories: [{ id: 'wire' }], bundle: { windows: { nsis: { installMode: 'currentUser' } } } });

await run('json-path count: matches → pass', async () => {
  const root = scaffold({ 'r.json': DOC });
  assert.equal(evaluateProbe({ kind: 'json-path', file: 'r.json', pointer: '/categories', expect: { count: 1 } }, root).ok, true);
});

await run('json-path count: drifted → fail', async () => {
  const root = scaffold({ 'r.json': JSON.stringify({ categories: [{ id: 'wire' }, { id: 'catalog' }] }) });
  const r = evaluateProbe({ kind: 'json-path', file: 'r.json', pointer: '/categories', expect: { count: 1 } }, root);
  assert.equal(r.ok, false);
  assert.match(r.detail, /has 2 entr\(ies\), declared 1/);
});

await run('json-path equals: nested pointer resolves → pass', async () => {
  const root = scaffold({ 'r.json': DOC });
  assert.equal(evaluateProbe({
    kind: 'json-path', file: 'r.json', pointer: '/bundle/windows/nsis/installMode', expect: { equals: 'currentUser' },
  }, root).ok, true);
});

await run('json-path equals: value changed → fail', async () => {
  const root = scaffold({ 'r.json': JSON.stringify({ bundle: { windows: { nsis: { installMode: 'perMachine' } } } }) });
  const r = evaluateProbe({
    kind: 'json-path', file: 'r.json', pointer: '/bundle/windows/nsis/installMode', expect: { equals: 'currentUser' },
  }, root);
  assert.equal(r.ok, false);
  assert.match(r.detail, /"perMachine"/);
});

await run('json-path: missing pointer segment resolves to undefined → fail', async () => {
  const root = scaffold({ 'r.json': DOC });
  assert.equal(evaluateProbe({
    kind: 'json-path', file: 'r.json', pointer: '/bundle/linux/deb/installMode', expect: { equals: 'x' },
  }, root).ok, false);
});

await run('json-path contains: whole-document search, empty pointer', async () => {
  const root = scaffold({ 'r.json': DOC });
  assert.equal(evaluateProbe({ kind: 'json-path', file: 'r.json', pointer: '', expect: { contains: 'wire' } }, root).ok, true);
  assert.equal(evaluateProbe({ kind: 'json-path', file: 'r.json', pointer: '', expect: { contains: 'plugin-sdk' } }, root).ok, false);
});

await run('json-path: unparseable JSON → fail, no throw', async () => {
  const root = scaffold({ 'r.json': '{ not json' });
  const r = evaluateProbe({ kind: 'json-path', file: 'r.json', pointer: '/x', expect: { count: 1 } }, root);
  assert.equal(r.ok, false);
  assert.match(r.detail, /not parseable JSON/);
});

await run('json-path: no expect declared → fail (a probe with no claim is not a probe)', async () => {
  const root = scaffold({ 'r.json': DOC });
  assert.equal(evaluateProbe({ kind: 'json-path', file: 'r.json', pointer: '/categories' }, root).ok, false);
});

// --------------------------------------------------------------------------- kind: file-set

const MIRROR = '/** Hand-written TypeScript mirror of the Java record. */\nexport interface A { a: string }\n';
const MARKER = 'hand-written (typescript |ts )?(mirror|view|types|interfaces)';

function fileSetProbe(extra = {}) {
  return {
    kind: 'file-set',
    dir: 'src',
    extension: '.ts',
    mirrorMarker: MARKER,
    registeredIn: 'governance/contract-surfaces.v1.json',
    excludePathContains: ['/generated/', '.test.ts'],
    exceptions: [],
    ...extra,
  };
}

await run('file-set: marked mirror that is a declared exception → pass', async () => {
  const root = scaffold({
    'src/api/types/surface.ts': MIRROR,
    'governance/contract-surfaces.v1.json': '{}',
  });
  const r = evaluateProbe(fileSetProbe({
    exceptions: [{ file: 'src/api/types/surface.ts', reason: 'PR 2 generates it' }],
  }), root);
  assert.equal(r.ok, true, r.detail);
});

await run('file-set: marked mirror that is registered → pass', async () => {
  const root = scaffold({
    'src/api/types/registry.ts': MIRROR,
    'governance/contract-surfaces.v1.json': '{"consumers":["src/api/types/registry.ts"]}',
  });
  assert.equal(evaluateProbe(fileSetProbe(), root).ok, true);
});

await run('file-set: NEW unregistered marked mirror → fail', async () => {
  const root = scaffold({
    'src/api/types/scratch.ts': MIRROR,
    'governance/contract-surfaces.v1.json': '{}',
  });
  const r = evaluateProbe(fileSetProbe(), root);
  assert.equal(r.ok, false);
  assert.match(r.detail, /scratch\.ts.*neither registered/s);
});

await run('file-set: mirror found OUTSIDE the old api/types directory (884 review B2)', async () => {
  const root = scaffold({
    'src/shell-v0/handshake/capabilities-types.ts': MIRROR,
    'governance/contract-surfaces.v1.json': '{}',
  });
  const r = evaluateProbe(fileSetProbe(), root);
  assert.equal(r.ok, false, 'a recursive scan must reach shell-v0/handshake');
  assert.match(r.detail, /capabilities-types\.ts/);
});

await run('file-set: registration matches full path, not basename', async () => {
  const root = scaffold({
    'src/api/types/registry.ts': MIRROR,
    // A same-named file elsewhere in the register must NOT launder this one.
    'governance/contract-surfaces.v1.json': '{"consumers":["some/other/place/registry.ts"]}',
  });
  assert.equal(evaluateProbe(fileSetProbe(), root).ok, false);
});

await run('file-set: stale exception for a deleted file → fail', async () => {
  const root = scaffold({ 'src/a.ts': 'export const a = 1;', 'governance/contract-surfaces.v1.json': '{}' });
  const r = evaluateProbe(fileSetProbe({ exceptions: [{ file: 'src/gone.ts', reason: 'x' }] }), root);
  assert.equal(r.ok, false);
  assert.match(r.detail, /no longer exist/);
});

await run('file-set: generated output and tests are excluded from the scan', async () => {
  const root = scaffold({
    'src/api/generated/schema-types/thing.ts': MIRROR,
    'src/api/thing.test.ts': MIRROR,
    'governance/contract-surfaces.v1.json': '{}',
  });
  assert.equal(evaluateProbe(fileSetProbe(), root).ok, true);
});

await run('file-set: missing directory → fail', async () => {
  const root = scaffold({ 'other/a.ts': 'x' });
  assert.equal(evaluateProbe(fileSetProbe(), root).ok, false);
});

// --------------------------------------------------------------------------- engine plumbing

await run('unknown kind → fail, never silently pass', async () => {
  const root = scaffold({ 'a.txt': 'x' });
  const r = evaluateProbe({ kind: 'vibes', pattern: 'x' }, root);
  assert.equal(r.ok, false);
  assert.match(r.detail, /unknown probe kind/);
});

await run('PROBE_KINDS is the closed vocabulary the register may use', async () => {
  assert.deepEqual(
    [...PROBE_KINDS].sort(),
    ['file-set', 'gate', 'grep-absent', 'grep-present', 'json-path', 'test'],
  );
});

await run('loadProbeRegister: absent register → null', async () => {
  const root = scaffold({ 'a.txt': 'x' });
  assert.equal(loadProbeRegister(root), null);
});

await run('loadProbeRegister: broken JSON → parseError, not a throw', async () => {
  const root = scaffold({ 'governance/adr-probes.v1.json': '{ not json' });
  const reg = loadProbeRegister(root);
  assert.ok(reg.parseError, 'expected a parseError field');
  assert.deepEqual(reg.probes, []);
});

await run('loadProbeRegister: indexes probes by id', async () => {
  const root = scaffold({
    'governance/adr-probes.v1.json': JSON.stringify({ probes: [{ id: 'p1', adr: '0001' }] }),
  });
  assert.equal(loadProbeRegister(root).byId.get('p1').adr, '0001');
});

// --------------------------------------------------------------- the shipped register itself

await run('every shipped probe declares a known kind and a non-empty premise', async () => {
  const reg = loadProbeRegister(process.cwd());
  assert.ok(reg && reg.probes.length > 0, 'the repo register must load');
  for (const p of reg.probes) {
    assert.ok(PROBE_KINDS.has(p.kind), `${p.id}: unknown kind '${p.kind}'`);
    assert.ok(typeof p.premise === 'string' && p.premise.length > 20, `${p.id}: premise must be prose`);
    assert.match(p.adr, /^\d{4}$/, `${p.id}: adr must be a 4-digit number`);
  }
});

for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`adr-coverage probes.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`adr-coverage probes.test: all ${passed} checks passed`);
