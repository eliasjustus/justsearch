/**
 * Tempdoc 618 Seam C + tempdoc 680 — unit tests for fold-observations.mjs
 * (shard → grouped-conditions fold).
 *
 * Run with: `node scripts/agent-analytics/fold-observations.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { listShards, entriesFromShard, foldShards, INBOX_FILE } from './fold-observations.mjs';
import { appendObservation, SHARD_DIR } from './note-observation.mjs';
import { parseStore } from './lib/observations-store.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'fold-obs-test-'));

const STORE_FIXTURE = [
  '---', 'title: Observations', '---', '', '# Observations', '', '## Rules', '', '- rule one', '',
  '## Conditions', '',
  '### obs:recentsmenu — RecentsMenu ghost theme tokens',
  '`kind: environment` `anchor: RecentsMenu.ts` `seen: 2` `first: 2026-06-22` `last: 2026-06-30`',
  '- [ ] check-theme-token-closure fails on ghost tokens in `RecentsMenu.ts` (2026-06-22)',
  '- [ ] theme-token-closure red on main: 8 ghost tokens in `RecentsMenu.ts` (2026-06-30)',
  '',
].join('\n');

function freshRoot(store = STORE_FIXTURE) {
  const root = fs.mkdtempSync(path.join(tmp, 'root-'));
  fs.mkdirSync(path.join(root, SHARD_DIR), { recursive: true });
  fs.mkdirSync(path.join(root, path.dirname(INBOX_FILE)), { recursive: true });
  fs.writeFileSync(path.join(root, INBOX_FILE), store, 'utf8');
  return root;
}
function storeText(root) { return fs.readFileSync(path.join(root, INBOX_FILE), 'utf8'); }
function groupBySlug(root, slug) {
  return parseStore(storeText(root)).groups.find((g) => g.slug === slug);
}

try {
  // --- helpers ---
  run('entriesFromShard extracts only entry lines (not the header)', () => {
    const text = '# Observations shard — session x\n\n> blurb\n\n- [ ] a (2026-06-21)\n- [x] b (2026-06-21)\n';
    assert.deepEqual(entriesFromShard(text), ['- [ ] a (2026-06-21)', '- [x] b (2026-06-21)']);
  });

  // --- grouped-fold core behavior (tempdoc 680) ---
  run('foldShards (apply) merges an anchor-matching entry into the existing condition', () => {
    const root = freshRoot();
    appendObservation({ description: 'ghost tokens again in `modules/ui-web/src/shell-v0/components/RecentsMenu.ts`', root, sessionId: 'sessA', date: '2026-07-06' });
    const r = foldShards({ root, apply: true });
    assert.equal(r.merged, 1);
    assert.equal(r.opened, 0);
    const g = groupBySlug(root, 'recentsmenu');
    assert.equal(g.fields.seen, '3');
    assert.equal(g.fields.last, '2026-07-06');
    assert.equal(g.occurrences.length, 3);
    assert.deepEqual(listShards(root), []); // shards consumed
  });
  run('foldShards (apply) opens a NEW condition with a proposed kind for an unknown anchor', () => {
    const root = freshRoot();
    appendObservation({ description: 'Pre-existing red on main: `FooBarTest` fails in isolation', root, sessionId: 'sessB', date: '2026-07-06' });
    const r = foldShards({ root, apply: true });
    assert.equal(r.opened, 1);
    assert.equal(r.proposedKinds, 1);
    const s = parseStore(storeText(root));
    const g = s.groups.find((x) => x.fields.anchor === 'FooBarTest');
    assert.ok(g, 'new condition exists');
    assert.match(g.fields.kind, /\?$/); // proposed, awaiting triage
    assert.equal(g.fields.seen, '1');
  });
  run('foldShards lands entries from TWO sessions in one pass', () => {
    const root = freshRoot();
    appendObservation({ description: 'RecentsMenu.ts ghosts, session one', root, sessionId: 'sessC1', date: '2026-07-06' });
    appendObservation({ description: 'brand new thing in `modules/ui/src/main/java/io/justsearch/ui/Zed.java`', root, sessionId: 'sessC2', date: '2026-07-06' });
    const r = foldShards({ root, apply: true });
    assert.equal(r.folded, 2);
    assert.equal(r.entries, 2);
    assert.equal(r.merged + r.opened, 2);
  });
  run('foldShards dry-run writes nothing and deletes nothing', () => {
    const root = freshRoot();
    appendObservation({ description: 'dry finding `RecentsMenu.ts`', root, sessionId: 'sessD', date: '2026-07-06' });
    const before = storeText(root);
    const r = foldShards({ root, apply: false });
    assert.equal(r.entries, 1);
    assert.equal(storeText(root), before);
    assert.equal(listShards(root).length, 1);
  });
  run('foldShards is idempotent: re-run after apply makes no change', () => {
    const root = freshRoot();
    appendObservation({ description: 'once only `RecentsMenu.ts`', root, sessionId: 'sessE', date: '2026-07-06' });
    foldShards({ root, apply: true });
    const after1 = storeText(root);
    const r2 = foldShards({ root, apply: true }); // no shards left
    assert.equal(r2.entries, 0);
    assert.equal(storeText(root), after1);
  });
  run('foldShards does not double-count if a shard survives a prior fold (failed-delete recovery)', () => {
    const root = freshRoot();
    appendObservation({ description: 'survivor `RecentsMenu.ts`', root, sessionId: 'sessF', date: '2026-07-06' });
    foldShards({ root, apply: true });
    const seenAfter1 = groupBySlug(root, 'recentsmenu').fields.seen;
    // simulate a failed delete: re-create the same shard with the same entry
    appendObservation({ description: 'survivor `RecentsMenu.ts`', root, sessionId: 'sessF', date: '2026-07-06' });
    const r2 = foldShards({ root, apply: true });
    assert.equal(r2.unchangedDupes, 1);
    assert.equal(r2.merged, 0);
    assert.equal(groupBySlug(root, 'recentsmenu').fields.seen, seenAfter1); // seen not inflated
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
    fs.writeFileSync(path.join(root, SHARD_DIR, 'sessG.md'), '# header\n\nprose, not an entry\n');
    const r = foldShards({ root, apply: true });
    assert.equal(r.entries, 0);
    assert.ok(!storeText(root).includes('should be ignored'));
  });
  run('foldShards throws a migration pointer on a pre-680 flat-inbox store', () => {
    const root = freshRoot('# Observations\n\n## Inbox\n\n- [ ] flat entry (2026-06-01)\n');
    appendObservation({ description: 'anything', root, sessionId: 'sessH', date: '2026-07-06' });
    assert.throws(() => foldShards({ root, apply: false }), /Conditions.*tempdoc 680|tempdoc 680/s);
  });

  // --- anchorless-merge fold-leak fix (tempdoc 721) ---
  const ANCHORLESS_FIXTURE = [
    '---', 'title: Observations', '---', '', '# Observations', '', '## Rules', '', '- rule one', '',
    '## Conditions', '',
    '### obs:unanchored-package-version — package.json self-presentation version says 1.0.0 stale',
    '`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-04` `last: 2026-07-04`',
    '- [ ] package.json self-presentation bug: version says 1.0.0 (app is 0.1.0-alpha), description stale placeholder (2026-07-04)',
    '',
  ].join('\n');
  run('fold-leak fix: an anchorless re-observation MERGES into the matching anchorless condition (not a new unanchored-N)', () => {
    const root = freshRoot(ANCHORLESS_FIXTURE);
    appendObservation({ description: 'package.json self-presentation version 1.0.0 should be 0.1.0-alpha, description still a stale placeholder', root, sessionId: 'sessI', date: '2026-07-09' });
    const r = foldShards({ root, apply: true });
    assert.equal(r.opened, 0, 'must not open a new unanchored condition');
    assert.equal(r.merged, 1, 'must merge into the existing anchorless condition');
    const g = groupBySlug(root, 'unanchored-package-version');
    assert.equal(g.fields.seen, '2');
    assert.equal(g.fields.last, '2026-07-09');
  });
  run('fold-leak fix stays conservative: a DISSIMILAR anchorless note opens its own condition', () => {
    const root = freshRoot(ANCHORLESS_FIXTURE);
    appendObservation({ description: 'the reranker latency budget should be raised for large corpora during warmup', root, sessionId: 'sessJ', date: '2026-07-09' });
    const r = foldShards({ root, apply: true });
    assert.equal(r.opened, 1, 'an unrelated anchorless note must not be force-merged');
    assert.equal(r.merged, 0);
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
