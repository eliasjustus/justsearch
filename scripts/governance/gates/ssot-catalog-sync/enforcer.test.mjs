/**
 * Integration tests for the ssot-catalog-sync enforcer — drives
 * enforceSsotCatalogSync over scaffolded temp fixture trees.
 *
 * Run with: `node scripts/governance/gates/ssot-catalog-sync/enforcer.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { enforceSsotCatalogSync } from './enforcer.mjs';

let passed = 0;
const failures = [];
const tmpDirs = [];

const GATE = {
  baseline: { path: 'gates/ssot-catalog-sync/mirrors.json' },
  changesetsDir: 'gates/ssot-catalog-sync/.changesets',
};

function scaffold({ mirrors, baselineMirrors, files = {}, changesets = [] }) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ssot-sync-'));
  tmpDirs.push(root);
  const write = (rel, content) => {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content, 'utf8');
  };
  write('gates/ssot-catalog-sync/mirrors.json', JSON.stringify({ mirrors }, null, 2));
  if (baselineMirrors) write('_baseline/gates/ssot-catalog-sync/mirrors.json', JSON.stringify({ mirrors: baselineMirrors }, null, 2));
  for (const [rel, content] of Object.entries(files)) write(rel, content);
  for (const cs of changesets) {
    const fm = Object.entries(cs.frontmatter).map(([k, v]) => `${k}: ${v}`).join('\n');
    write(`gates/ssot-catalog-sync/.changesets/${cs.id}.md`, `---\n${fm}\n---\nbody\n`);
  }
  return root;
}

async function enforce(fixtureRoot) {
  return enforceSsotCatalogSync({ repoRoot: fixtureRoot, gate: GATE, baselineRef: 'HEAD', mode: 'gate', fixtureMode: true, fixtureRoot });
}
async function run(label, fn) {
  try { await fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}
const ids = (r) => r.findings.map((f) => f.ruleId);

const M = (kind = 'json') => [{ id: 'demo', root: 'root/x', copy: 'copy/x', kind }];

await run('json in-sync (order-insensitive) → pass', async () => {
  const root = scaffold({ mirrors: M(), files: { 'root/x': '{"a":1,"b":2}', 'copy/x': '{"b":2,"a":1}' } });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', ids(r).join(','));
});

await run('text in-sync (CRLF-normalized) → pass', async () => {
  const root = scaffold({ mirrors: M('text'), files: { 'root/x': 'a\nb\n', 'copy/x': 'a\r\nb\r\n' } });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', ids(r).join(','));
});

await run('json drift → fail (drift)', async () => {
  const root = scaffold({ mirrors: M(), files: { 'root/x': '{"a":1}', 'copy/x': '{"a":2}' } });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', ids(r).join(','));
  assert.ok(ids(r).includes('ssot-catalog-sync/drift'));
});

await run('copy missing → fail (copy-missing)', async () => {
  const root = scaffold({ mirrors: M(), files: { 'root/x': '{"a":1}' } }); // copy/x not written
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', ids(r).join(','));
  assert.ok(ids(r).includes('ssot-catalog-sync/copy-missing'));
});

await run('drift covered by intentional-divergence → pass', async () => {
  const root = scaffold({
    mirrors: M(),
    files: { 'root/x': '{"a":1}', 'copy/x': '{"a":2}' },
    changesets: [{ id: 'div', frontmatter: { classification: 'intentional-divergence', mirror: 'demo', tempdoc: '0' } }],
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', ids(r).join(','));
  assert.ok(ids(r).includes('ssot-catalog-sync/intentional-divergence'));
});

await run('malformed mirrors.json → fail verdict, no crash', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ssot-sync-'));
  tmpDirs.push(root);
  const p = path.join(root, 'gates/ssot-catalog-sync/mirrors.json');
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, '{ not json', 'utf8');
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', ids(r).join(','));
  assert.ok(ids(r).includes('ssot-catalog-sync/malformed-mirrors'));
});

await run('silent mirror removal vs baseline → fail', async () => {
  const root = scaffold({
    mirrors: M(), // only 'demo'
    baselineMirrors: [...M(), { id: 'gone', root: 'root/g', copy: 'copy/g', kind: 'json' }],
    files: { 'root/x': '{"a":1}', 'copy/x': '{"a":1}' },
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', ids(r).join(','));
  assert.ok(ids(r).includes('ssot-catalog-sync/silent-mirror-removal'));
});

for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`ssot-catalog-sync enforcer.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`ssot-catalog-sync enforcer.test: all ${passed} checks passed`);
