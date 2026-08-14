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

// Hand-formatted like the real manifest.json: the single-line "args" array is what a
// JSON.stringify(obj, null, 2) round-trip would expand, so it doubles as the formatting oracle.
const MANIFEST_TEXT = `{
  "manifest_version": "0.4",
  "name": "x",
  "version": "0.1.0",
  "server": {
    "mcp_config": {
      "command": "node",
      "args": ["\${__dirname}/server/index.js"]
    }
  }
}
`;

function makeSource({ serverJs, manifest } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mcpb-pack-'));
  const mcpbDir = path.join(root, 'packaging', 'mcpb');
  fs.mkdirSync(path.join(mcpbDir, 'server'), { recursive: true });
  fs.writeFileSync(path.join(mcpbDir, 'manifest.json'), manifest ?? MANIFEST_TEXT);
  fs.writeFileSync(path.join(mcpbDir, 'server', 'index.js'), serverJs ?? '// bridge\n');
  return root;
}

const readManifest = (root) => fs.readFileSync(path.join(root, 'packaging', 'mcpb', 'manifest.json'), 'utf8');

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
    name: 'io.github.justsearch-app/justsearch',
    version: version ?? '0.2.0',
    repository: { url: 'https://github.com/justsearch-app/justsearch', source: 'github' },
    packages: [
      {
        registryType: 'mcpb',
        identifier: identifier ?? 'https://github.com/justsearch-app/justsearch/releases/download/v0.2.0/justsearch-mcp.mcpb',
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
  const manifestText = readManifest(root);
  ok('manifest.json version stamped too (0.1.0-vs-0.2.0 drift)', JSON.parse(manifestText).version === '0.9.9');
  ok('manifest.json hand formatting preserved', manifestText.includes('"args": ["${__dirname}/server/index.js"]'));
  ok(
    'manifest.json changed in the version field only',
    JSON.stringify(JSON.parse(manifestText)) === JSON.stringify({ ...JSON.parse(MANIFEST_TEXT), version: '0.9.9' }),
  );
  fs.rmSync(root, { recursive: true, force: true });
}

// 5b. --set-version re-syncs fileSha256: manifest.json is IN the bundle, so a version bump that
// did not re-hash would leave check-mcpb-consistency red on every release.
{
  const root = makeSource();
  const sjPath = withServerJson(root, { fileSha256: 'stale' });
  ok('--set-version exits 0', runCli(root, ['--set-version', '0.9.9']).status === 0);
  const after = JSON.parse(fs.readFileSync(sjPath, 'utf8'));
  ok('fileSha256 re-synced to the freshly packed bundle', after.packages[0].fileSha256 === packMcpb(root).sha256);
  fs.rmSync(root, { recursive: true, force: true });
}

// 5c. --dry-run writes neither file.
{
  const root = makeSource();
  const sjPath = withServerJson(root, { version: '0.2.0' });
  ok('--set-version --dry-run exits 0', runCli(root, ['--set-version', '0.9.9', '--dry-run']).status === 0);
  ok('server.json untouched by dry-run', JSON.parse(fs.readFileSync(sjPath, 'utf8')).version === '0.2.0');
  ok('manifest.json untouched by dry-run', readManifest(root) === MANIFEST_TEXT);
  fs.rmSync(root, { recursive: true, force: true });
}

// 5d. An un-stampable manifest fails closed and leaves BOTH files untouched (no half-bumped pair).
{
  for (const [label, manifest] of [
    ['ambiguous second "version"', MANIFEST_TEXT.replace('"name": "x",', '"name": "x",\n  "nested": {\n    "version": "9"\n  },')],
    ['no top-level "version"', '{\n  "name": "x"\n}\n'],
  ]) {
    const root = makeSource({ manifest });
    const sjPath = withServerJson(root, { version: '0.2.0' });
    const r = runCli(root, ['--set-version', '0.9.9']);
    ok(`--set-version fails on ${label}`, r.status !== 0);
    ok(`server.json not half-bumped on ${label}`, JSON.parse(fs.readFileSync(sjPath, 'utf8')).version === '0.2.0');
    ok(`manifest.json untouched on ${label}`, readManifest(root) === manifest);
    fs.rmSync(root, { recursive: true, force: true });
  }
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
