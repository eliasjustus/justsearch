/**
 * Tests for the MCPB consistency gate (scripts/ci/check-mcpb-consistency.mjs).
 *
 * The guard calls process.exitCode = 1 on failure, so paths are exercised by
 * spawning the script as a subprocess with CHECK_MCPB_ROOT pointed at a
 * disposable fixture root (sibling convention: check-public-agent-utility.test.mjs).
 *
 * Run: `node scripts/ci/check-mcpb-consistency.test.mjs` (exits non-zero on failure)
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'check-mcpb-consistency.mjs');
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

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

/** Build a disposable fixture root; return its path. */
function makeFixture({ bundleBytes, fileSha256, version, identifier, omitBundle } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mcpb-gate-'));
  const mcpbDir = path.join(root, 'packaging', 'mcpb');
  fs.mkdirSync(path.join(mcpbDir, 'dist'), { recursive: true });
  const bytes = bundleBytes ?? Buffer.from('fake-mcpb-bundle-bytes');
  if (!omitBundle) {
    fs.writeFileSync(path.join(mcpbDir, 'dist', 'justsearch-mcp.mcpb'), bytes);
  }
  const server = {
    name: 'io.github.eliasjustus/justsearch',
    version: version ?? '0.2.0',
    packages: [
      {
        registryType: 'mcpb',
        identifier:
          identifier ??
          'https://github.com/eliasjustus/justsearch/releases/download/v0.2.0/justsearch-mcp.mcpb',
        fileSha256: fileSha256 ?? sha256(bytes),
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

// 1. Matching hash → OK (exit 0).
{
  const root = makeFixture();
  const r = run(root);
  ok('matching hash exits 0', r.status === 0);
  ok('matching hash prints OK', /check-mcpb-consistency: OK/.test(r.stdout));
  fs.rmSync(root, { recursive: true, force: true });
}

// 2. Mismatched hash → FAIL (exit 1).
{
  const root = makeFixture({ fileSha256: 'deadbeef'.repeat(8) });
  const r = run(root);
  ok('hash drift exits 1', r.status === 1);
  ok('hash drift reports drift', /hash drift/i.test(r.stderr));
  fs.rmSync(root, { recursive: true, force: true });
}

// 3. Missing bundle → FAIL (exit 1).
{
  const root = makeFixture({ omitBundle: true });
  const r = run(root);
  ok('missing bundle exits 1', r.status === 1);
  ok('missing bundle is reported', /is missing/i.test(r.stderr));
  fs.rmSync(root, { recursive: true, force: true });
}

// 4. Release-version match → OK.
{
  const root = makeFixture({ version: '0.2.0' });
  const r = run(root, ['--release-version', '0.2.0']);
  ok('release-version match exits 0', r.status === 0);
  ok('release-version scope printed', /release 0\.2\.0/.test(r.stdout));
  fs.rmSync(root, { recursive: true, force: true });
}

// 5. Release-version mismatch → FAIL.
{
  const root = makeFixture({ version: '0.2.0' });
  const r = run(root, ['--release-version', '0.3.0']);
  ok('release-version mismatch exits 1', r.status === 1);
  ok('release-version mismatch reported', /Release-version mismatch/i.test(r.stderr));
  fs.rmSync(root, { recursive: true, force: true });
}

// 6. Release-asset URL not pointing at the tag → FAIL.
{
  const root = makeFixture({
    version: '0.3.0',
    identifier: 'https://github.com/eliasjustus/justsearch/releases/download/v0.2.0/justsearch-mcp.mcpb',
  });
  const r = run(root, ['--release-version', '0.3.0']);
  ok('asset-URL tag mismatch exits 1', r.status === 1);
  ok('asset-URL mismatch reported', /Release-asset URL mismatch/i.test(r.stderr));
  fs.rmSync(root, { recursive: true, force: true });
}

// 7. --release-version without a value → FAIL.
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
