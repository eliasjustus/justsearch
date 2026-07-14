/**
 * Tests for the deterministic MCPB packer (scripts/ci/pack-mcpb.mjs).
 *
 * Run: `node scripts/ci/pack-mcpb.test.mjs` (exits non-zero on failure)
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

import { packMcpb } from './pack-mcpb.mjs';

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'pack-mcpb.mjs');
const runCli = (root, args) =>
  spawnSync(process.execPath, [SCRIPT, ...args], { env: { ...process.env, CHECK_MCPB_ROOT: root }, encoding: 'utf8' });

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (error) {
    failures.push(error.message);
  }
};

function makeSource({ serverJs } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mcpb-pack-'));
  const mcpbDir = path.join(root, 'packaging', 'mcpb');
  fs.mkdirSync(path.join(mcpbDir, 'server'), { recursive: true });
  fs.writeFileSync(path.join(mcpbDir, 'manifest.json'), JSON.stringify({ name: 'x' }));
  fs.writeFileSync(path.join(mcpbDir, 'server', 'index.js'), serverJs ?? '// bridge\n');
  return root;
}

// 1. Deterministic: two packs of identical source -> identical hash + bytes.
{
  const root = makeSource();
  const a = packMcpb(root);
  const b = packMcpb(root);
  ok('same hash across packs', a.sha256 === b.sha256);
  ok('same bytes across packs', Buffer.compare(a.buffer, b.buffer) === 0);
  ok('entries = manifest + server/index.js', JSON.stringify(a.entries) === JSON.stringify(['manifest.json', 'server/index.js']));
  ok('is a zip (PK header)', a.buffer[0] === 0x50 && a.buffer[1] === 0x4b);
  fs.rmSync(root, { recursive: true, force: true });
}

// 2. Source change -> different hash.
{
  const r1 = makeSource({ serverJs: '// A\n' });
  const r2 = makeSource({ serverJs: '// B (different)\n' });
  ok('different source -> different hash', packMcpb(r1).sha256 !== packMcpb(r2).sha256);
  fs.rmSync(r1, { recursive: true, force: true });
  fs.rmSync(r2, { recursive: true, force: true });
}

// 3. Extra file under server/ is included (recursive walk).
{
  const root = makeSource();
  const base = packMcpb(root).entries.length;
  fs.writeFileSync(path.join(root, 'packaging', 'mcpb', 'server', 'lib.js'), '// lib\n');
  const withExtra = packMcpb(root).entries;
  ok('server/** recursive include', withExtra.length === base + 1 && withExtra.includes('server/lib.js'));
  fs.rmSync(root, { recursive: true, force: true });
}

function withServerJson(root, { fileSha256, version, identifier, extraPkgFields } = {}) {
  const p = path.join(root, 'packaging', 'mcpb', 'server.json');
  const server = {
    name: 'io.github.eliasjustus/justsearch',
    version: version ?? '0.2.0',
    repository: { url: 'https://github.com/eliasjustus/justsearch', source: 'github' },
    packages: [
      {
        registryType: 'mcpb',
        identifier: identifier ?? 'https://github.com/eliasjustus/justsearch/releases/download/v0.2.0/justsearch-mcp.mcpb',
        fileSha256: fileSha256 ?? 'x',
        transport: { type: 'stdio' },
        ...(extraPkgFields || {}),
      },
    ],
  };
  fs.writeFileSync(p, JSON.stringify(server, null, 2) + '\n');
  return p;
}

const throws = (fn) => {
  try {
    fn();
    return false;
  } catch {
    return true;
  }
};

// 4. --sync fixes a malformed fileSha256 (the old regex would have silently no-op'd on "TBD").
{
  const root = makeSource();
  const sjPath = withServerJson(root, { fileSha256: 'TBD' });
  const r = runCli(root, ['--sync']);
  ok('--sync exits 0', r.status === 0);
  const after = JSON.parse(fs.readFileSync(sjPath, 'utf8'));
  ok('--sync wrote the real hash over a malformed value', after.packages[0].fileSha256 === packMcpb(root).sha256);
  fs.rmSync(root, { recursive: true, force: true });
}

// 5. --set-version sets top-level version + URL; leaves a nested packages[0].version untouched (N1).
{
  const root = makeSource();
  const sjPath = withServerJson(root, { version: '0.2.0', extraPkgFields: { version: 'DO-NOT-TOUCH' } });
  const r = runCli(root, ['--set-version', '0.9.9']);
  ok('--set-version exits 0', r.status === 0);
  const after = JSON.parse(fs.readFileSync(sjPath, 'utf8'));
  ok('top-level version set', after.version === '0.9.9');
  ok('asset URL points at the tag', after.packages[0].identifier.endsWith('/v0.9.9/justsearch-mcp.mcpb'));
  ok('nested packages[0].version untouched (replace-all regression)', after.packages[0].version === 'DO-NOT-TOUCH');
  fs.rmSync(root, { recursive: true, force: true });
}

// 6. Guard: a non-ASCII archive name throws (flags=0 STORED zip).
{
  const root = makeSource();
  fs.writeFileSync(path.join(root, 'packaging', 'mcpb', 'server', 'naïve.js'), '// x\n');
  ok('non-ASCII entry name throws', throws(() => packMcpb(root)));
  fs.rmSync(root, { recursive: true, force: true });
}

// 7. Guard: a manifest asset outside server/ throws.
{
  const root = makeSource();
  fs.writeFileSync(path.join(root, 'packaging', 'mcpb', 'manifest.json'), JSON.stringify({ name: 'x', icon: 'icon.png' }));
  ok('top-level manifest asset throws', throws(() => packMcpb(root)));
  fs.rmSync(root, { recursive: true, force: true });
}

if (failures.length > 0) {
  console.error(`pack-mcpb.test: FAIL (${failures.length}/${passed + failures.length})`);
  for (const f of failures) console.error(`- ${f}`);
  process.exit(1);
}
console.log(`pack-mcpb.test: OK (${passed} assertions)`);
