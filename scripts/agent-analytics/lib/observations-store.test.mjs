/**
 * Tempdoc 680 — unit tests for observations-store.mjs (grouped store grammar +
 * fingerprint matching). Run: `node scripts/agent-analytics/lib/observations-store.test.mjs`
 */

import assert from 'node:assert/strict';
import {
  entryDate, extractAnchors, symptomClass, proposeKind, slugFor,
  parseStore, serializeStore, matchGroup, mergeOccurrence, newGroupFrom,
} from './observations-store.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const STORE_FIXTURE = [
  '---', 'title: Observations', '---', '', '# Observations', '', '## Rules', '', '- rule text', '',
  '## Conditions', '',
  '### obs:recentsmenu — RecentsMenu ghost theme tokens',
  '`kind: environment` `anchor: RecentsMenu.ts` `seen: 2` `first: 2026-06-22` `last: 2026-06-30` `probe: node scripts/ci/check-theme-token-closure.mjs`',
  '- [ ] check-theme-token-closure fails on ghost tokens in `RecentsMenu.ts` (2026-06-22)',
  '- [ ] check-theme-token-closure red on main: 8 ghost tokens in `RecentsMenu.ts` (2026-06-30)',
  '',
  '### obs:healthlitview-test — HealthLitView 604 Move B tone assertion',
  '`kind: environment` `anchor: HealthLitView.test.ts` `seen: 1` `first: 2026-06-30` `last: 2026-06-30` `status: proposed-retire (probe passed 2026-07-05)`',
  '- [ ] HealthLitView test expects warning gets error — `HealthLitView.test.ts:313` (2026-06-30)',
  '',
  '## Parked', '',
  '### obs:webview2-lna — WebView2 LNA rollout watch',
  '`kind: follow-up` `anchor: none` `seen: 1` `first: 2026-07-01` `last: 2026-07-01` `status: parked (external rollout; re-check when WebView2 flips default)`',
  '- [ ] WebView2 Local Network Access enforcement rollout could affect loopback (2026-07-01)',
  '',
].join('\n');

// --- primitives ---
run('entryDate extracts trailing date', () => {
  assert.equal(entryDate('- [ ] x (2026-06-30)'), '2026-06-30');
  assert.equal(entryDate('- [ ] no date here'), null);
});
run('extractAnchors prefers full backticked path, keeps Test classes and check-names', () => {
  const a = extractAnchors('Pre-existing: `modules/ui-web/src/shell-v0/components/RecentsMenu.ts:44` red via check-theme-token-closure, also StatusWireContractConformanceTest');
  assert.equal(a[0], 'modules/ui-web/src/shell-v0/components/RecentsMenu.ts');
  assert.ok(a.includes('StatusWireContractConformanceTest'));
  assert.ok(a.includes('check-theme-token-closure'));
});
run('extractAnchors drops stoplisted generic anchors', () => {
  assert.deepEqual(extractAnchors('drift in `CLAUDE.md` and package.json only'), []);
});
run('symptomClass distinguishes red-test / gate-red / missing / drift', () => {
  assert.equal(symptomClass('FooTest fails on main'), 'red-test');
  assert.equal(symptomClass('ts-any gate red, baseline drifted'), 'gate-red');
  assert.equal(symptomClass('file does not exist anywhere in git history'), 'missing');
  assert.equal(symptomClass('docstring stale vs shipped code'), 'drift');
});
run('proposeKind heuristics match the validated classes', () => {
  assert.equal(proposeKind('Pre-existing test failure on unmodified main, unrelated to my change'), 'environment');
  assert.equal(proposeKind('Agent pitfall: zombie JVMs survive port kill'), 'lesson');
  assert.equal(proposeKind('Consider extending the badge to the RAG path'), 'follow-up');
  assert.equal(proposeKind('Search wire matchSpans entries carry empty term strings'), 'defect');
});
run('slugFor is unique and symptom-discriminated on collision', () => {
  const existing = new Set(['dev-runner']);
  const s = slugFor('scripts/dev/dev-runner.cjs', 'drift', existing);
  assert.equal(s, 'dev-runner-drift');
  existing.add(s);
  assert.equal(slugFor('scripts/dev/dev-runner.cjs', 'drift', existing), 'dev-runner-drift-2');
});

// --- parse / serialize round-trip ---
run('parseStore parses groups, fields, occurrences, and parked placement', () => {
  const s = parseStore(STORE_FIXTURE);
  assert.equal(s.groups.length, 3);
  const rm = s.groups.find((g) => g.slug === 'recentsmenu');
  assert.equal(rm.fields.seen, '2');
  assert.equal(rm.fields.probe, 'node scripts/ci/check-theme-token-closure.mjs');
  assert.equal(rm.occurrences.length, 2);
  const parked = s.groups.find((g) => g.slug === 'webview2-lna');
  assert.match(parked.fields.status, /^parked/);
});
run('parseStore returns null on a pre-migration (flat inbox) file', () => {
  assert.equal(parseStore('# Observations\n\n## Inbox\n\n- [ ] flat entry (2026-06-01)\n'), null);
});
run('serializeStore round-trips: parse(serialize(parse(x))) is stable', () => {
  const s1 = parseStore(STORE_FIXTURE);
  const text2 = serializeStore(s1);
  const s2 = parseStore(text2);
  assert.equal(serializeStore(s2), text2);
  assert.equal(s2.groups.length, 3);
  assert.ok(text2.indexOf('## Parked') > text2.indexOf('## Conditions'));
  assert.ok(text2.indexOf('webview2-lna') > text2.indexOf('## Parked'));
});

// --- matching / merging ---
run('matchGroup matches by basename anchor from a full-path entry', () => {
  const { groups } = parseStore(STORE_FIXTURE);
  const g = matchGroup(groups, '- [ ] theme-token-closure ghost tokens again — `modules/ui-web/src/shell-v0/components/RecentsMenu.ts` (2026-07-06)');
  assert.equal(g.slug, 'recentsmenu');
});
run('matchGroup returns null for an unanchored or unknown-anchor entry', () => {
  const { groups } = parseStore(STORE_FIXTURE);
  assert.equal(matchGroup(groups, '- [ ] something vague with no file mention (2026-07-06)'), null);
  assert.equal(matchGroup(groups, '- [ ] new thing in `modules/ui/src/main/java/Foo.java` (2026-07-06)'), null);
});
run('mergeOccurrence dedupes exact line, else appends and bumps seen/last', () => {
  const { groups } = parseStore(STORE_FIXTURE);
  const g = groups.find((x) => x.slug === 'recentsmenu');
  assert.equal(mergeOccurrence(g, g.occurrences[0]), false); // exact dup
  assert.equal(g.fields.seen, '2');
  assert.equal(mergeOccurrence(g, '- [ ] RecentsMenu.ts ghosts re-observed (2026-07-06)'), true);
  assert.equal(g.fields.seen, '3');
  assert.equal(g.fields.last, '2026-07-06');
});
run('newGroupFrom proposes kind with trailing ?, anchors, dates, unique slug', () => {
  const g = newGroupFrom('- [ ] Pre-existing red on main: `FooBarTest` fails in isolation (2026-07-06)', new Set());
  assert.equal(g.fields.kind, 'environment?');
  assert.equal(g.fields.anchor, 'FooBarTest');
  assert.equal(g.fields.seen, '1');
  assert.equal(g.fields.first, '2026-07-06');
  assert.equal(g.occurrences.length, 1);
  assert.ok(!/\(\d{4}-\d{2}-\d{2}\)/.test(g.title));
});

if (failures.length) {
  console.error(`observations-store.test: ${failures.length} FAILED / ${passed} passed`);
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log(`observations-store.test: ${passed} passed`);
