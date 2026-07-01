/**
 * Tempdoc 618 Seam C — unit tests for fold-observations.mjs (shard → inbox reconcile).
 *
 * Run with: `node scripts/agent-analytics/fold-observations.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  listShards,
  entriesFromShard,
  insertIntoInbox,
  foldShards,
  countStaleResolved,
  INBOX_FILE,
} from './fold-observations.mjs';
import { appendObservation, SHARD_DIR } from './note-observation.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'fold-obs-test-'));

const INBOX_FIXTURE = [
  '---', 'title: Observations', '---', '', '# Observations', '', '## Rules', '', '- rule one', '',
  '## Inbox', '', 'New entries land here.', '', '- [ ] pre-existing entry (2026-06-01)', '',
  '## Post-push handoff', '', '- archived', '',
].join('\n');

function freshRoot(inbox = INBOX_FIXTURE) {
  const root = fs.mkdtempSync(path.join(tmp, 'root-'));
  fs.mkdirSync(path.join(root, SHARD_DIR), { recursive: true });
  fs.mkdirSync(path.join(root, path.dirname(INBOX_FILE)), { recursive: true });
  fs.writeFileSync(path.join(root, INBOX_FILE), inbox, 'utf8');
  return root;
}
function inboxText(root) { return fs.readFileSync(path.join(root, INBOX_FILE), 'utf8'); }
function inboxSection(text) {
  const lines = text.split('\n');
  const a = lines.findIndex((l) => /^##\s+Inbox/.test(l));
  let b = lines.length;
  for (let i = a + 1; i < lines.length; i++) if (/^##\s+/.test(lines[i])) { b = i; break; }
  return lines.slice(a, b).join('\n');
}

try {
  // --- helpers ---
  run('entriesFromShard extracts only entry lines (not the header)', () => {
    const text = '# Observations shard — session x\n\n> blurb\n\n- [ ] a (2026-06-21)\n- [x] b (2026-06-21)\n';
    assert.deepEqual(entriesFromShard(text), ['- [ ] a (2026-06-21)', '- [x] b (2026-06-21)']);
  });
  run('insertIntoInbox returns null when there is no ## Inbox', () => {
    assert.equal(insertIntoInbox('# Doc\n\n## Other\n', ['- [ ] x']), null);
  });
  run('insertIntoInbox appends within the Inbox section, before the next heading', () => {
    const next = insertIntoInbox(INBOX_FIXTURE, ['- [ ] new one (2026-06-21)']);
    const sec = inboxSection(next);
    assert.match(sec, /pre-existing entry/);
    assert.match(sec, /new one/);
    // new entry stays inside Inbox (not after Post-push handoff)
    assert.ok(next.indexOf('new one') < next.indexOf('## Post-push handoff'));
  });
  run('insertIntoInbox dedupes an entry already present (idempotent)', () => {
    const next = insertIntoInbox(INBOX_FIXTURE, ['- [ ] pre-existing entry (2026-06-01)']);
    assert.equal(next, INBOX_FIXTURE);
  });

  // --- countStaleResolved (tempdoc 665: report-only retire-on-resolve signal) ---
  run('countStaleResolved is 0 when the Inbox has no [x] entries', () => {
    assert.equal(countStaleResolved(INBOX_FIXTURE), 0);
  });
  run('countStaleResolved counts [x] entries inside Inbox only, not other sections', () => {
    const withResolved = INBOX_FIXTURE.replace(
      '- [ ] pre-existing entry (2026-06-01)',
      '- [ ] pre-existing entry (2026-06-01)\n- [x] fixed one (2026-06-02)\n- [X] fixed two, capital X (2026-06-03)',
    ).replace('- archived', '- [x] archived-but-outside-inbox (2026-06-01)');
    assert.equal(countStaleResolved(withResolved), 2); // the two inside Inbox; the one in Post-push handoff doesn't count
  });
  run('countStaleResolved returns 0 when there is no "## Inbox" heading', () => {
    assert.equal(countStaleResolved('# Doc\n\n## Other\n- [x] x\n'), 0);
  });

  // --- foldShards: the core data-loss-proof behaviour ---
  run('foldShards (apply) lands entries from TWO sessions and deletes shards', () => {
    const root = freshRoot();
    appendObservation({ description: 'finding A', root, sessionId: 'sessA', date: '2026-06-21' });
    appendObservation({ description: 'finding B', root, sessionId: 'sessB', date: '2026-06-21' });
    const r = foldShards({ root, apply: true });
    assert.equal(r.folded, 2);
    assert.equal(r.entries, 2);
    const sec = inboxSection(inboxText(root));
    assert.match(sec, /finding A/);
    assert.match(sec, /finding B/);
    assert.deepEqual(listShards(root), []); // shards consumed
  });
  run('foldShards dry-run writes nothing and deletes nothing', () => {
    const root = freshRoot();
    appendObservation({ description: 'dry finding', root, sessionId: 'sessC', date: '2026-06-21' });
    const before = inboxText(root);
    const r = foldShards({ root, apply: false });
    assert.equal(r.entries, 1);
    assert.equal(inboxText(root), before);
    assert.equal(listShards(root).length, 1);
  });
  run('foldShards is idempotent: re-run after apply makes no change', () => {
    const root = freshRoot();
    appendObservation({ description: 'once only', root, sessionId: 'sessD', date: '2026-06-21' });
    foldShards({ root, apply: true });
    const after1 = inboxText(root);
    const r2 = foldShards({ root, apply: true }); // no shards left
    assert.equal(r2.entries, 0);
    assert.equal(inboxText(root), after1);
    assert.equal((after1.match(/once only/g) || []).length, 1);
  });
  run('foldShards does not duplicate if a shard survives a prior fold (dedupe guard)', () => {
    const root = freshRoot();
    appendObservation({ description: 'survivor', root, sessionId: 'sessE', date: '2026-06-21' });
    foldShards({ root, apply: true });
    // simulate a failed-delete: re-create the same shard with the same entry
    appendObservation({ description: 'survivor', root, sessionId: 'sessE', date: '2026-06-21' });
    foldShards({ root, apply: true });
    assert.equal((inboxText(root).match(/survivor/g) || []).length, 1);
  });
  run('foldShards tolerates an empty shard dir (no-op)', () => {
    const root = freshRoot();
    const r = foldShards({ root, apply: true });
    assert.equal(r.entries, 0);
    assert.equal(r.changed, false);
  });
  run('foldShards ignores README.md / non-entry content in shards', () => {
    const root = freshRoot();
    fs.writeFileSync(path.join(root, SHARD_DIR, 'README.md'), '# not a shard\n- [ ] should be ignored (2026-06-21)\n');
    fs.writeFileSync(path.join(root, SHARD_DIR, 'sessF.md'), '# header\n\nprose, not an entry\n');
    const r = foldShards({ root, apply: true });
    assert.equal(r.entries, 0); // README excluded; sessF has no entry lines
    assert.ok(!inboxText(root).includes('should be ignored'));
  });
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}

if (failures.length) {
  console.error(`fold-observations.test: ${failures.length} FAILED / ${passed} passed`);
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log(`fold-observations.test: ${passed} passed`);
