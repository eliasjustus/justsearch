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
import { execFileSync } from 'node:child_process';
import { listShards, entriesFromShard, foldShards, isBaseFresh, INBOX_FILE } from './fold-observations.mjs';
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
    '### obs:unanchored-package-version — `package.json` self-presentation version says 1.0.0 stale',
    '`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-04` `last: 2026-07-04`',
    '- [ ] `package.json` self-presentation bug: version says 1.0.0 (app is 0.1.0-alpha), description stale placeholder (2026-07-04)',
    '',
  ].join('\n');
  run('fold-leak fix: an anchorless re-observation MERGES into the matching anchorless condition (not a new unanchored-N)', () => {
    const root = freshRoot(ANCHORLESS_FIXTURE);
    appendObservation({ description: '`package.json` self-presentation version 1.0.0 should be 0.1.0-alpha, description still a stale placeholder', root, sessionId: 'sessI', date: '2026-07-09' });
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

  // --- tempdoc 721 independent-review hardening: over-merge + parked absorption ---
  const TEMPLATE_PKG_FIXTURE = [
    '---', 'title: Observations', '---', '', '# Observations', '', '## Rules', '', '- rule one', '',
    '## Conditions', '',
    '### obs:pkg-self-presentation — `package.json` self-presentation version says 1.0.0 stale',
    '`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-04` `last: 2026-07-04`',
    '- [ ] `package.json` self-presentation bug: version says 1.0.0 (should be 0.1.0-alpha), description is a stale placeholder (2026-07-04)',
    '',
  ].join('\n');
  run('hardening: a same-template note about a DIFFERENT file does not merge (disjoint identifiers)', () => {
    const root = freshRoot(TEMPLATE_PKG_FIXTURE);
    // Identical boilerplate, only the backticked filename differs (both stoplisted → anchorless).
    appendObservation({ description: '`settings.json` self-presentation bug: version says 1.0.0 (should be 0.1.0-alpha), description is a stale placeholder', root, sessionId: 'sK', date: '2026-07-09' });
    const r = foldShards({ root, apply: true });
    assert.equal(r.merged, 0, 'shared boilerplate must not merge two different-file notes');
    assert.equal(r.opened, 1);
  });

  const PARKED_FIXTURE = [
    '---', 'title: Observations', '---', '', '# Observations', '', '## Rules', '', '- rule one', '',
    '## Conditions', '',
    '### obs:something-else — some other general note entirely different words',
    '`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-01` `last: 2026-07-01`',
    '- [ ] some other general note entirely different words here nothing alike (2026-07-01)',
    '',
    '## Parked', '',
    '### obs:parked-flake — intermittent `flakeWidget` flake deferred pending a repro signal',
    '`kind: environment?` `anchor: none` `seen: 2` `first: 2026-06-01` `last: 2026-06-10` `status: parked (deferred — revisit on a repro)`',
    '- [ ] intermittent `flakeWidget` flake deferred pending a repro signal that does not exist yet (2026-06-01)',
    '',
  ].join('\n');
  run('hardening: a recurrence does not absorb into a PARKED condition — it resurfaces', () => {
    const root = freshRoot(PARKED_FIXTURE);
    // Shares the `flakeWidget` identifier with the parked condition, so it WOULD fuzzy-match
    // but for the parked-skip — exercising that guard, not just the no-identifier path.
    appendObservation({ description: 'intermittent `flakeWidget` flake deferred pending a repro signal that does not exist yet', root, sessionId: 'sP', date: '2026-07-09' });
    const r = foldShards({ root, apply: true });
    assert.equal(r.merged, 0, 'must not silently bump a dismissed (parked) condition');
    assert.equal(r.opened, 1, 'the recurrence opens a fresh condition so triage sees it');
    assert.equal(groupBySlug(root, 'parked-flake').fields.seen, '2', 'parked condition left untouched');
  });

  const PROSE_FIXTURE = [
    '---', 'title: Observations', '---', '', '# Observations', '', '## Rules', '', '- rule one', '',
    '## Conditions', '',
    '### obs:retry-budget-ingest — consider adding a retry budget to the ingest pipeline before release',
    '`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-07-01` `last: 2026-07-01`',
    '- [ ] Consider adding a retry budget to the ingest pipeline before the next release cycle wraps up (2026-07-01)',
    '',
  ].join('\n');
  run('hardening: two same-template PROSE notes (no identifier) do NOT merge on boilerplate Jaccard', () => {
    // Independent-review repro (tempdoc 721): same template, only a content word differs
    // (ingest vs summary). Jaccard clears 0.6, but with no shared backtick identifier the
    // fuzzy path must not activate — these are different subsystems, not a re-observation.
    const root = freshRoot(PROSE_FIXTURE);
    appendObservation({ description: 'Consider adding a retry budget to the summary pipeline before the next release cycle wraps up', root, sessionId: 'sQ', date: '2026-07-09' });
    const r = foldShards({ root, apply: true });
    assert.equal(r.merged, 0, 'identifier-less prose must not fuzzy-merge on shared boilerplate');
    assert.equal(r.opened, 1, 'the different-subsystem note opens its own condition');
  });

  // --- base-freshness guard (tempdoc 814 §D8.4) ---
  //
  // The fold REWRITES the shared store from THIS checkout's parse and then deletes the
  // shards, so folding from a checkout behind origin/main silently drops every condition
  // landed since — and the shards that carried them are gone. These build real (tiny) git
  // repos rather than stubbing the shell-out, so the guard is tested through the same
  // `git merge-base --is-ancestor` it will run in production.
  const git = (root, ...args) =>
    execFileSync('git', args, { cwd: root, stdio: 'pipe', encoding: 'utf8' });

  function gitRoot(store = STORE_FIXTURE) {
    const root = freshRoot(store);
    git(root, 'init', '-q', '-b', 'main');
    git(root, 'config', 'user.email', 'test@example.com');
    git(root, 'config', 'user.name', 'test');
    git(root, 'add', '-A');
    git(root, 'commit', '-q', '-m', 'base');
    return root;
  }

  run('freshness: a plain (non-git) root is INDETERMINATE — the guard has no opinion', () => {
    // The indeterminate arm must not invent a refusal: every existing test root above is a
    // bare tmp dir, and they all still fold.
    const root = freshRoot();
    assert.equal(isBaseFresh(root), null);
  });

  run('freshness: HEAD == origin/main is FRESH and folds', () => {
    const root = gitRoot();
    git(root, 'update-ref', 'refs/remotes/origin/main', 'HEAD');
    assert.equal(isBaseFresh(root), true);
    appendObservation({ description: 'a note about `foo.ts`', root, sessionId: 'sFresh', date: '2026-08-06' });
    const r = foldShards({ root, apply: true });
    assert.equal(r.entries, 1);
  });

  run('freshness: HEAD BEHIND origin/main refuses --apply, names the remedy, keeps shards', () => {
    const root = gitRoot();
    // origin/main moves ahead of HEAD: commit, point the remote ref at it, roll HEAD back.
    const base = git(root, 'rev-parse', 'HEAD').trim();
    fs.writeFileSync(path.join(root, 'newer.txt'), 'newer\n', 'utf8');
    git(root, 'add', '-A');
    git(root, 'commit', '-q', '-m', 'newer');
    git(root, 'update-ref', 'refs/remotes/origin/main', git(root, 'rev-parse', 'HEAD').trim());
    git(root, 'reset', '-q', '--hard', base);
    assert.equal(isBaseFresh(root), false);

    appendObservation({ description: 'a note about `bar.ts`', root, sessionId: 'sStale', date: '2026-08-06' });
    const shardsBefore = listShards(root).length;
    assert.equal(shardsBefore, 1);
    assert.throws(
      () => foldShards({ root, apply: true }),
      (e) => /NOT a descendant of origin\/main/.test(e.message) && /--allow-stale/.test(e.message),
    );
    assert.equal(listShards(root).length, shardsBefore, 'a refusal must leave every shard intact');
  });

  run('freshness: --allow-stale folds a deliberately old base', () => {
    const root = gitRoot();
    const base = git(root, 'rev-parse', 'HEAD').trim();
    fs.writeFileSync(path.join(root, 'newer.txt'), 'newer\n', 'utf8');
    git(root, 'add', '-A');
    git(root, 'commit', '-q', '-m', 'newer');
    git(root, 'update-ref', 'refs/remotes/origin/main', git(root, 'rev-parse', 'HEAD').trim());
    git(root, 'reset', '-q', '--hard', base);
    appendObservation({ description: 'a note about `baz.ts`', root, sessionId: 'sOverride', date: '2026-08-06' });
    const r = foldShards({ root, apply: true, allowStale: true });
    assert.equal(r.entries, 1);
    assert.equal(listShards(root).length, 0);
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
