/**
 * Tempdoc 618 Seam C — unit tests for note-observation.mjs (per-session shard write).
 *
 * Run with: `node scripts/agent-analytics/note-observation.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  resolveSessionId,
  formatEntry,
  shardPathFor,
  appendObservation,
  SHARD_DIR,
} from './note-observation.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'note-obs-test-'));
function freshRoot() {
  const root = fs.mkdtempSync(path.join(tmp, 'root-'));
  fs.mkdirSync(path.join(root, SHARD_DIR), { recursive: true });
  return root;
}

try {
  // --- resolveSessionId ---
  run('resolveSessionId reads the current-session-id pointer file', () => {
    const root = freshRoot();
    fs.mkdirSync(path.join(root, 'tmp', 'agent-telemetry'), { recursive: true });
    fs.writeFileSync(path.join(root, 'tmp', 'agent-telemetry', 'current-session-id'), 'abc-123\n');
    assert.equal(resolveSessionId({ root, env: {} }), 'abc-123');
  });
  run('resolveSessionId falls back to JUSTSEARCH_AGENT_SESSION_ID', () => {
    const root = freshRoot();
    assert.equal(resolveSessionId({ root, env: { JUSTSEARCH_AGENT_SESSION_ID: 'env-sid' } }), 'env-sid');
  });
  run('resolveSessionId falls back to CLAUDE_CODE_SESSION_ID', () => {
    const root = freshRoot();
    assert.equal(resolveSessionId({ root, env: { CLAUDE_CODE_SESSION_ID: 'cc-sid' } }), 'cc-sid');
  });
  run('resolveSessionId pointer file wins over env', () => {
    const root = freshRoot();
    fs.mkdirSync(path.join(root, 'tmp', 'agent-telemetry'), { recursive: true });
    fs.writeFileSync(path.join(root, 'tmp', 'agent-telemetry', 'current-session-id'), 'file-wins');
    assert.equal(resolveSessionId({ root, env: { JUSTSEARCH_AGENT_SESSION_ID: 'env' } }), 'file-wins');
  });
  run('resolveSessionId sanitizes unsafe filename chars', () => {
    const root = freshRoot();
    const id = resolveSessionId({ root, env: { JUSTSEARCH_AGENT_SESSION_ID: 'a/b\\c:d e' } });
    assert.match(id, /^[A-Za-z0-9._-]+$/);
  });

  // --- formatEntry ---
  run('formatEntry produces the canonical inbox line', () => {
    assert.equal(formatEntry('thing broke', '2026-06-21'), '- [ ] thing broke (2026-06-21)');
  });
  run('formatEntry preserves a description that already ends with a date', () => {
    assert.equal(formatEntry('x — `f:1` (2026-01-02)', '2026-06-21'), '- [ ] x — `f:1` (2026-01-02)');
  });
  run('formatEntry throws on empty', () => {
    assert.throws(() => formatEntry('   '));
  });

  // --- appendObservation ---
  run('appendObservation creates the shard with a header then appends', () => {
    const root = freshRoot();
    const shard = appendObservation({ description: 'first finding', root, sessionId: 'sess1', date: '2026-06-21' });
    assert.equal(shard, shardPathFor('sess1', root));
    const text = fs.readFileSync(shard, 'utf8');
    assert.match(text, /# Observations shard — session sess1/);
    assert.match(text, /Seam C/);
    assert.ok(text.trimEnd().endsWith('- [ ] first finding (2026-06-21)'));
  });
  run('appendObservation second call appends a second entry (no header dup)', () => {
    const root = freshRoot();
    appendObservation({ description: 'one', root, sessionId: 'sess1', date: '2026-06-21' });
    appendObservation({ description: 'two', root, sessionId: 'sess1', date: '2026-06-21' });
    const text = fs.readFileSync(shardPathFor('sess1', root), 'utf8');
    assert.equal((text.match(/# Observations shard/g) || []).length, 1);
    assert.equal((text.match(/^- \[ \]/gm) || []).length, 2);
  });
  run('appendObservation isolates sessions into distinct shard files', () => {
    const root = freshRoot();
    appendObservation({ description: 'a', root, sessionId: 'sessA', date: '2026-06-21' });
    appendObservation({ description: 'b', root, sessionId: 'sessB', date: '2026-06-21' });
    assert.ok(fs.existsSync(shardPathFor('sessA', root)));
    assert.ok(fs.existsSync(shardPathFor('sessB', root)));
    assert.notEqual(shardPathFor('sessA', root), shardPathFor('sessB', root));
  });
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}

if (failures.length) {
  console.error(`note-observation.test: ${failures.length} FAILED / ${passed} passed`);
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log(`note-observation.test: ${passed} passed`);
