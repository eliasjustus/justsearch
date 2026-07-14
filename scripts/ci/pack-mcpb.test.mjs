/**
 * Tests for the deterministic MCPB packer (scripts/ci/pack-mcpb.mjs).
 *
 * Run: `node scripts/ci/pack-mcpb.test.mjs` (exits non-zero on failure)
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';

import { packMcpb } from './pack-mcpb.mjs';

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

if (failures.length > 0) {
  console.error(`pack-mcpb.test: FAIL (${failures.length}/${passed + failures.length})`);
  for (const f of failures) console.error(`- ${f}`);
  process.exit(1);
}
console.log(`pack-mcpb.test: OK (${passed} assertions)`);
