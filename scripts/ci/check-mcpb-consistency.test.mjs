/**
 * Tests for the MCPB consistency gate (scripts/ci/check-mcpb-consistency.mjs).
 *
 * The gate re-packs the bundle from source and compares to server.json.fileSha256,
 * so fixtures ship manifest.json + server/index.js + server.json (no committed
 * bundle). Failure paths set process.exitCode=1, exercised by spawning the script
 * with CHECK_MCPB_ROOT at a disposable fixture root.
 *
 * Run: `node scripts/ci/check-mcpb-consistency.test.mjs` (exits non-zero on failure)
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

import { packMcpb } from './pack-mcpb.mjs';

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'check-mcpb-consistency.mjs');

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

/**
 * Build a disposable fixture root with MCPB source. By default server.json.fileSha256
 * is set to the correct deterministic hash of the source; overrides let tests break it.
 */
function makeFixture({ fileSha256, version, identifier, serverJs } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mcpb-gate-'));
  const mcpbDir = path.join(root, 'packaging', 'mcpb');
  fs.mkdirSync(path.join(mcpbDir, 'server'), { recursive: true });
  fs.writeFileSync(path.join(mcpbDir, 'manifest.json'), JSON.stringify({ name: 'justsearch', version: '0.2.0' }));
  fs.writeFileSync(path.join(mcpbDir, 'server', 'index.js'), serverJs ?? '// fixture bridge\n');
  const trueHash = packMcpb(root).sha256;
  const server = {
    name: 'io.github.eliasjustus/justsearch',
    version: version ?? '0.2.0',
    packages: [
      {
        registryType: 'mcpb',
        identifier:
          identifier ??
          'https://github.com/eliasjustus/justsearch/releases/download/v0.2.0/justsearch-mcp.mcpb',
        fileSha256: fileSha256 ?? trueHash,
        transport: { type: 'stdio' },
      },
    ],
  };
  fs.writeFileSync(path.join(mcpbDir, 'server.json'), JSON.stringify(server, null, 2));
  return root;
}

function run(root, args = []) {
  return spawnSync(process.execPath, [SCRIPT, ...args], {
    env: { ...process.env, CHECK_MCPB_ROOT: root },
    encoding: 'utf8',
  });
}

// 1. Source packs to the stored hash -> OK.
{
  const root = makeFixture();
  const r = run(root);
  ok('matching hash exits 0', r.status === 0);
  ok('prints OK', /check-mcpb-consistency: OK/.test(r.stdout));
  fs.rmSync(root, { recursive: true, force: true });
}

// 2. Stored hash wrong -> FAIL (drift).
{
  const root = makeFixture({ fileSha256: 'deadbeef'.repeat(8) });
  const r = run(root);
  ok('hash drift exits 1', r.status === 1);
  ok('reports drift', /hash drift/i.test(r.stderr));
  fs.rmSync(root, { recursive: true, force: true });
}

// 3. Freshness: source edited after the hash was stored -> FAIL.
{
  const root = makeFixture(); // server.json.fileSha256 = hash of the ORIGINAL source
  fs.writeFileSync(path.join(root, 'packaging', 'mcpb', 'server', 'index.js'), '// edited, not re-synced\n');
  const r = run(root);
  ok('stale source exits 1', r.status === 1);
  ok('freshness reports drift', /hash drift/i.test(r.stderr));
  fs.rmSync(root, { recursive: true, force: true });
}

// 4. Release-version match -> OK.
{
  const root = makeFixture({ version: '0.2.0' });
  const r = run(root, ['--release-version', '0.2.0']);
  ok('release-version match exits 0', r.status === 0);
  ok('release scope printed', /release 0\.2\.0/.test(r.stdout));
  fs.rmSync(root, { recursive: true, force: true });
}

// 5. Release-version mismatch -> FAIL.
{
  const root = makeFixture({ version: '0.2.0' });
  const r = run(root, ['--release-version', '0.3.0']);
  ok('release-version mismatch exits 1', r.status === 1);
  ok('reports version mismatch', /Release-version mismatch/i.test(r.stderr));
  fs.rmSync(root, { recursive: true, force: true });
}

// 6. Asset URL not pointing at the tag -> FAIL.
{
  const root = makeFixture({
    version: '0.3.0',
    identifier: 'https://github.com/eliasjustus/justsearch/releases/download/v0.2.0/justsearch-mcp.mcpb',
  });
  const r = run(root, ['--release-version', '0.3.0']);
  ok('asset-URL tag mismatch exits 1', r.status === 1);
  ok('reports URL mismatch', /Release-asset URL mismatch/i.test(r.stderr));
  fs.rmSync(root, { recursive: true, force: true });
}

// 7. Bare --release-version -> FAIL.
{
  const root = makeFixture();
  const r = run(root, ['--release-version']);
  ok('bare --release-version exits 1', r.status === 1);
  fs.rmSync(root, { recursive: true, force: true });
}

if (failures.length > 0) {
  console.error(`check-mcpb-consistency.test: FAIL (${failures.length}/${passed + failures.length})`);
  for (const f of failures) console.error(`- ${f}`);
  process.exit(1);
}
console.log(`check-mcpb-consistency.test: OK (${passed} assertions)`);
