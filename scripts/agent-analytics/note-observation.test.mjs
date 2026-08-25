/**
 * Tempdoc 618 Seam C + tempdoc 862 — unit tests for note-observation.mjs
 * (per-writer shard write: keyed by session AND writing tree).
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
  resolveWriterSuffix,
  appendObservation,
  SHARD_DIR,
} from './note-observation.mjs';
// The strip this round-trips against lives in the 856 recovery — the two are one
// contract (mint dot-free / recover by last dot), so the test exercises both ends.
import { sessionIdFromShardName } from './recover-merge-links.mjs';

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
  // Env-first is deliberate (tempdoc 684): the pointer file records whatever
  // session last STARTED in this checkout, which is foreign in the shared
  // main checkout — env always carries the calling process's own identity.
  run('resolveSessionId: env wins over the pointer file', () => {
    const root = freshRoot();
    fs.mkdirSync(path.join(root, 'tmp', 'agent-telemetry'), { recursive: true });
    fs.writeFileSync(path.join(root, 'tmp', 'agent-telemetry', 'current-session-id'), 'file-loses');
    assert.equal(resolveSessionId({ root, env: { JUSTSEARCH_AGENT_SESSION_ID: 'env-wins' } }), 'env-wins');
  });
  run('resolveSessionId: foreign pointer file does not override the caller env (tempdoc 684 fix)', () => {
    const root = freshRoot();
    fs.mkdirSync(path.join(root, 'tmp', 'agent-telemetry'), { recursive: true });
    fs.writeFileSync(path.join(root, 'tmp', 'agent-telemetry', 'current-session-id'), 'foreign-session-from-another-agent');
    const id = resolveSessionId({ root, env: { CLAUDE_CODE_SESSION_ID: 'mine-123' } });
    assert.equal(id, 'mine-123');
    assert.notEqual(id, 'foreign-session-from-another-agent');
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

  // --- writer discriminator (tempdoc 862) ---
  // The defect 862 repairs: a subagent inherits the parent's session id, so one
  // session wrote one shard path from many worktrees (nine writers, one file, 859
  // wave). The shard is now keyed by the tree that merges, not by the actor.
  run('shardPathFor: no writer → the bare <sid>.md (D.6.2, no shard in flight is renamed)', () => {
    const root = freshRoot();
    assert.equal(path.basename(shardPathFor('sess1', root, '')), 'sess1.md');
  });
  run('shardPathFor: a writer → <sid>.<writer>.md', () => {
    const root = freshRoot();
    assert.equal(path.basename(shardPathFor('sess1', root, 'agent-a5ec1173')), 'sess1.agent-a5ec1173.md');
  });
  // The strip in recover-merge-links.mjs takes the LAST dot-segment, so exactly
  // one dot in the name is a load-bearing invariant, not tidiness (862 §D.4).
  run('shardPathFor: a dotted writer is flattened, so the name holds exactly one dot', () => {
    const root = freshRoot();
    const name = path.basename(shardPathFor('sess1', root, 'weird.tree.name'));
    assert.equal(name, 'sess1.weird_tree_name.md');
    assert.equal((name.match(/\./g) || []).length, 2); // the writer dot + `.md`
  });
  // A session id is EXTERNAL INPUT ($CLAUDE_CODE_SESSION_ID) and sanitizeId permits
  // dots, so "ids have no dots" is an assumption, not an invariant. Left unenforced,
  // a dotted id mints the bare shard `sess.with.dots.md`, which recover-merge-links
  // truncates to session `sess.with` — a silently wrong row in the measurement
  // ledger, the exact class 856 exists to remove. Enforced at the mint instead.
  run('shardPathFor: a dotted SESSION id is flattened at the mint', () => {
    const root = freshRoot();
    assert.equal(path.basename(shardPathFor('sess.with.dots', root, '')), 'sess_with_dots.md');
  });
  // The property that makes the strip sound, asserted as a round-trip rather than
  // as two separate string checks: for every (id, writer), recovering the session
  // from the minted basename returns the session token and never a truncation.
  run('shardPathFor -> sessionIdFromShardName round-trips for every id/writer shape', () => {
    const root = freshRoot();
    // [sessionId, writer, expected recovered session] — expectations are literal,
    // not recomputed from the implementation's own sanitizer (that would pass even
    // if the sanitizer were wrong).
    const cases = [
      ['bccfc163-7b8f-4b1a-b9e4-0c011632d8a1', '', 'bccfc163-7b8f-4b1a-b9e4-0c011632d8a1'],
      ['bccfc163-7b8f-4b1a-b9e4-0c011632d8a1', 'agent-af06f4a5', 'bccfc163-7b8f-4b1a-b9e4-0c011632d8a1'],
      ['wt-0a1b2c3d4e5f', 'worktree-name', 'wt-0a1b2c3d4e5f'],
      ['sess.with.dots', '', 'sess_with_dots'],
      ['sess.with.dots', 'tree.with.dots', 'sess_with_dots'],
      ['a.b', 'c.d', 'a_b'],
    ];
    for (const [sid, writer, expected] of cases) {
      const name = path.basename(shardPathFor(sid, root, writer), '.md');
      assert.ok((name.match(/\./g) || []).length <= 1, `>1 dot in ${name}`);
      assert.equal(
        sessionIdFromShardName(name),
        expected,
        `round-trip failed for ${JSON.stringify([sid, writer])} -> ${name}`,
      );
    }
  });
  run('resolveWriterSuffix: indeterminate (non-git) root fails OPEN to the bare name', () => {
    const root = freshRoot();
    assert.equal(resolveWriterSuffix({ root }), '');
    assert.equal(path.basename(shardPathFor('sess1', root)), 'sess1.md');
  });
  // The invariant 618 claimed and 862 restores: two writers, never the same bytes.
  run('appendObservation: two trees sharing ONE session id write two distinct shards', () => {
    const root = freshRoot();
    const a = appendObservation({ description: 'from the orchestrator', root, sessionId: 'sessX', writer: '', date: '2026-08-25' });
    const b = appendObservation({ description: 'from the worker', root, sessionId: 'sessX', writer: 'agent-af06f4a5', date: '2026-08-25' });
    assert.notEqual(a, b);
    assert.equal(path.basename(a), 'sessX.md');
    assert.equal(path.basename(b), 'sessX.agent-af06f4a5.md');
    // Independent, not interleaved: each file holds exactly its own writer's entry.
    const textA = fs.readFileSync(a, 'utf8');
    const textB = fs.readFileSync(b, 'utf8');
    assert.match(textA, /from the orchestrator/);
    assert.doesNotMatch(textA, /from the worker/);
    assert.match(textB, /from the worker/);
    assert.doesNotMatch(textB, /from the orchestrator/);
    assert.match(textB, /tree agent-af06f4a5/); // attribution stays legible in the file
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
