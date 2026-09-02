/**
 * lib/ledger/index.test.mjs — unit tests for the harness merge point
 * (tempdoc 886 §12 PR 1), run against both fixture trees.
 *
 * Run with: `node scripts/agent-analytics/lib/ledger/index.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { listCalls } from './index.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES_CLAUDE = path.join(HERE, '..', '..', 'fixtures', 'claude');
const FIXTURES_CODEX = path.join(HERE, '..', '..', 'fixtures', 'codex');
// A Claude-slug-shaped filter (`F--fixture-project`) would NOT match Codex's
// raw-cwd project string (`F:\FixtureProject`) -- a real, correct asymmetry
// between the two harnesses' project axis, not a bug. `discoverProjectDirs`'s
// OWN default filter (/justsearch/i) also would not match this fixture dir
// name, so a projectFilter is required for Claude discovery either way; /fixture/i
// matches both harnesses' differently-shaped project strings for the merge test.
const CLAUDE_PROJECT_FILTER = /F--fixture-project/;
const BOTH_HARNESS_PROJECT_FILTER = /fixture/i;

let passed = 0;
const failures = [];
function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

// --- both harnesses merge -----------------------------------------------

run('listCalls with both harnesses merges Claude + Codex fixture results', () => {
  const r = listCalls({
    harnesses: ['claude-code', 'codex-cli'],
    projectsRoot: FIXTURES_CLAUDE,
    projectFilter: BOTH_HARNESS_PROJECT_FILTER,
    codexHome: FIXTURES_CODEX,
  });
  const claudeCalls = r.calls.filter((c) => c.harness === 'claude-code');
  const codexCalls = r.calls.filter((c) => c.harness === 'codex-cli');
  assert.ok(claudeCalls.length > 0, 'expected at least one claude-code call');
  assert.ok(codexCalls.length > 0, 'expected at least one codex-cli call');
  assert.equal(r.calls.length, claudeCalls.length + codexCalls.length);
});

// --- harness filter is respected ------------------------------------------

run('listCalls({harnesses: ["claude-code"]}) excludes codex-cli entirely', () => {
  const r = listCalls({
    harnesses: ['claude-code'],
    projectsRoot: FIXTURES_CLAUDE,
    projectFilter: CLAUDE_PROJECT_FILTER,
    codexHome: FIXTURES_CODEX,
  });
  assert.ok(r.calls.every((c) => c.harness === 'claude-code'));
  assert.ok(r.sessions.every((s) => s.harness === 'claude-code'));
});

run('listCalls({harnesses: ["codex-cli"]}) excludes claude-code entirely', () => {
  const r = listCalls({
    harnesses: ['codex-cli'],
    projectsRoot: FIXTURES_CLAUDE,
    codexHome: FIXTURES_CODEX,
  });
  assert.ok(r.calls.every((c) => c.harness === 'codex-cli'));
  assert.ok(r.sessions.every((s) => s.harness === 'codex-cli'));
});

// --- resilience --------------------------------------------------------------

run('listCalls never throws when both roots are missing', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ledger-index-test-'));
  const r = listCalls({
    projectsRoot: path.join(tmp, 'no-claude'),
    codexHome: path.join(tmp, 'no-codex'),
  });
  assert.deepEqual(r, { calls: [], toolEvents: [], sessions: [], skipped: [] });
});

run('listCalls merges skipped Codex files through from the adapter', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ledger-index-test-'));
  const dir = path.join(tmp, 'codex', 'sessions', '2026', '08', '08');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(
    path.join(dir, 'rollout-2026-08-08T00-00-00-noid.jsonl'),
    JSON.stringify({ timestamp: '2026-08-08T00:00:00.000Z', type: 'session_meta', payload: { cwd: 'F:\\NoId', model_provider: 'openai' } }) + '\n',
    'utf8',
  );
  const r = listCalls({ harnesses: ['codex-cli'], codexHome: path.join(tmp, 'codex') });
  assert.equal(r.skipped.length, 1);
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`index.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`index.test: ${passed} passed`);
