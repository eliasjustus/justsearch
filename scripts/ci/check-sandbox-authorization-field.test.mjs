/**
 * Tests for the sandbox-authorization-field gate
 * (scripts/ci/check-sandbox-authorization-field.mjs).
 *
 * Builds disposable fixture roots with scripts/sandbox/mcp-client-README.md
 * and governance/sandbox-coverage.v1.json present, then flips the retired
 * `authorizationId` field name in and out to prove the gate fires both ways.
 *
 * Run: `node scripts/ci/check-sandbox-authorization-field.test.mjs` (exits non-zero on failure)
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'check-sandbox-authorization-field.mjs');

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
 * Build a disposable fixture root with both scanned files present. By
 * default both use the correct `pendingId` field; overrides let tests
 * reintroduce the retired `authorizationId` name.
 */
function makeFixture({ readmeText, coverageText } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'sandbox-auth-field-gate-'));
  fs.mkdirSync(path.join(root, 'scripts', 'sandbox'), { recursive: true });
  fs.mkdirSync(path.join(root, 'governance'), { recursive: true });
  fs.writeFileSync(
    path.join(root, 'scripts', 'sandbox', 'mcp-client-README.md'),
    readmeText ?? '# fixture\n\nApprove with `{"pendingId": "<id>", "execute": true}`.\n',
  );
  fs.writeFileSync(
    path.join(root, 'governance', 'sandbox-coverage.v1.json'),
    coverageText ?? JSON.stringify({ cohortCoverage: [{ cohort: 'mcp', validateHow: 'POST pendingId + execute:true' }] }),
  );
  return root;
}

function run(root) {
  return spawnSync(process.execPath, [SCRIPT], {
    env: { ...process.env, CHECK_SANDBOX_AUTH_FIELD_ROOT: root },
    encoding: 'utf8',
  });
}

// 1. Both files use the correct `pendingId` field -> OK, exit 0.
{
  const root = makeFixture();
  const r = run(root);
  ok('correct field name exits 0', r.status === 0);
  ok('prints OK', /check-sandbox-authorization-field: OK/.test(r.stdout));
  fs.rmSync(root, { recursive: true, force: true });
}

// 2. README reintroduces `authorizationId` -> FAIL, exit 1, names the file+line.
{
  const root = makeFixture({
    readmeText: '# fixture\n\nApprove with `{ authorizationId = "<id>" }`.\n',
  });
  const r = run(root);
  ok('README with authorizationId exits 1', r.status === 1);
  ok('reports FAIL', /check-sandbox-authorization-field: FAIL/.test(r.stderr));
  ok('names the retired field', /authorizationId/.test(r.stderr));
  ok('cites the README path and line', /mcp-client-README\.md:3/.test(r.stderr));
  fs.rmSync(root, { recursive: true, force: true });
}

// 3. governance/sandbox-coverage.v1.json reintroduces `authorizationId` -> FAIL, exit 1.
{
  const root = makeFixture({
    coverageText: JSON.stringify({
      cohortCoverage: [{ cohort: 'mcp', validateHow: 'resolve via { authorizationId: "<id>" }' }],
    }),
  });
  const r = run(root);
  ok('coverage register with authorizationId exits 1', r.status === 1);
  ok('cites the coverage register path', /sandbox-coverage\.v1\.json/.test(r.stderr));
  fs.rmSync(root, { recursive: true, force: true });
}

// 4. Both files reintroduce it -> FAIL reports both.
{
  const root = makeFixture({
    readmeText: '# fixture\n\n{ authorizationId }\n',
    coverageText: JSON.stringify({ cohortCoverage: [{ cohort: 'mcp', validateHow: '{ authorizationId }' }] }),
  });
  const r = run(root);
  ok('both files flagged exits 1', r.status === 1);
  ok('README violation reported', /mcp-client-README\.md/.test(r.stderr));
  ok('coverage violation reported', /sandbox-coverage\.v1\.json/.test(r.stderr));
  fs.rmSync(root, { recursive: true, force: true });
}

// 5. A missing scanned file is reported as a violation, not silently skipped.
{
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'sandbox-auth-field-gate-missing-'));
  const r = run(root);
  ok('missing scanned files exits 1', r.status === 1);
  ok('reports file not found', /file not found/.test(r.stderr));
  fs.rmSync(root, { recursive: true, force: true });
}

if (failures.length > 0) {
  console.error(`check-sandbox-authorization-field.test: FAIL (${failures.length}/${passed + failures.length})`);
  for (const f of failures) console.error(`- ${f}`);
  process.exit(1);
}
console.log(`check-sandbox-authorization-field.test: OK (${passed} assertions)`);
