/**
 * Tempdoc 856 chunk A — unit tests for `mergeFact` in outcome-session.mjs.
 *
 * Two defects under test:
 *   1. Absent evidence was reported as negative evidence (`fact(false, …)`), at the
 *      fact tier that the LLM judge is forbidden to overwrite. §3.2: absent -> 'unknown'.
 *   2. `.at(-1)` discarded every merge but the last. §6: report the full set.
 *
 * These are pure-function tests — no telemetry file is read or written. Importing
 * outcome-session.mjs must not run its CLI main(), which the import guard ensures.
 *
 * Run with: `node --test scripts/agent-analytics/outcome-session.test.mjs`
 * (also runs bare via `node <file>`, which is how run-all-tests.mjs invokes it).
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import { mergeFact } from './outcome-session.mjs';
import { MERGE_LINK_SOURCES, DEFAULT_MERGE_LINK_SOURCE } from './lib/telemetry-io.mjs';

const SID = 'sess-under-test';
const OTHER = 'sess-someone-else';

const row = (sessionId, commit, subject, extra = {}) =>
  ({ session_id: sessionId, merge_commit: commit, subject, ts: '2026-08-19T00:00:00Z', ...extra });

test('no rows for the session -> unknown, and specifically NOT false', () => {
  const f = mergeFact(SID, [row(OTHER, 'aaaaaaa', 'someone else merged')]);
  assert.equal(f.value, 'unknown', 'absent evidence must read as unknown');
  assert.notEqual(f.value, false, 'absent evidence must never be reported as negative evidence');
  assert.equal(f.value === false, false, 'value must not be the boolean false');
  assert.equal(f.kind, 'fact');
  assert.equal(f.source, 'git/session-merges');
  assert.match(f.note, /absence of a link is not evidence/i,
    'the note must say why absence is not a negative — otherwise a reader re-derives the old meaning');
  assert.equal('merges' in f, false, 'an unknown must not carry an empty merge set that reads as "zero merges"');
});

test('an empty / missing ledger -> unknown, not false', () => {
  for (const input of [[], null, undefined]) {
    const f = mergeFact(SID, input);
    assert.equal(f.value, 'unknown', `input ${JSON.stringify(input) ?? 'undefined'} must yield unknown`);
    assert.notEqual(f.value, false);
  }
});

test('one row -> fact carrying that row\'s commit', () => {
  const f = mergeFact(SID, [row(OTHER, 'zzzzzzz', 'noise'), row(SID, 'abc1234', 'feat(x): thing')]);
  assert.equal(f.value, true);
  assert.equal(f.kind, 'fact');
  assert.equal(f.source, 'git/session-merges');
  assert.equal(f.count, 1);
  assert.equal(f.merges.length, 1, 'must not pick up the other session\'s row');
  assert.equal(f.merges[0].commit, 'abc1234');
  assert.equal(f.merges[0].subject, 'feat(x): thing');
  assert.notEqual(f.merges[0].commit, 'zzzzzzz', 'the other session\'s commit must not leak in');
});

test('several rows -> ALL of them present, in ledger order (not just .at(-1))', () => {
  const f = mergeFact(SID, [
    row(SID, 'c1', 'first'),
    row(OTHER, 'x9', 'interleaved other session'),
    row(SID, 'c2', 'second'),
    row(SID, 'c3', 'third'),
  ]);
  assert.equal(f.value, true);
  assert.equal(f.count, 3, 'count must be the full set size, not 1');
  assert.deepEqual(f.merges.map(m => m.commit), ['c1', 'c2', 'c3'],
    'all three commits, in order — the old .at(-1) would have kept only c3');
  assert.deepEqual(f.merges.map(m => m.subject), ['first', 'second', 'third']);
  assert.equal(f.merges.some(m => m.commit === 'x9'), false, 'other-session rows must be filtered out');
});

test('a large fan-out is reported whole (the real ledger holds up to 56 links for one session)', () => {
  const rows = Array.from({ length: 56 }, (_, i) => row(SID, `commit${i}`, `subject ${i}`));
  const f = mergeFact(SID, rows);
  assert.equal(f.count, 56);
  assert.equal(f.merges.length, 56);
  assert.equal(f.merges[0].commit, 'commit0', 'the first link must survive, not only the last');
  assert.equal(f.merges[55].commit, 'commit55');
});

test('a row\'s own source is preserved when present', () => {
  const f = mergeFact(SID, [row(SID, 'c1', 'commit-message-derived', { source: MERGE_LINK_SOURCES.COMMIT_MESSAGE })]);
  assert.equal(f.merges[0].source, MERGE_LINK_SOURCES.COMMIT_MESSAGE,
    'the per-row tier must survive, or a recovered row is indistinguishable from an observed one');
  assert.notEqual(f.merges[0].source, DEFAULT_MERGE_LINK_SOURCE,
    'an explicit source must not be flattened to the legacy default');
  assert.equal(f.source, 'git/session-merges', 'the block-level source stays the ledger');
});

test('a recovered row keeps its inference tier at row level', () => {
  const f = mergeFact(SID, [row(SID, 'c1', 'recovered from a shard add', { source: MERGE_LINK_SOURCES.SHARD_INFERENCE })]);
  assert.equal(f.merges[0].source, MERGE_LINK_SOURCES.SHARD_INFERENCE);
  assert.equal(f.merges[0].kind, 'inference',
    'the fact tier must not silently absorb a derived row (tempdoc 856 §3.1)');
});

test('a row missing source does not throw, and normalizes to the legacy meaning', () => {
  let f;
  assert.doesNotThrow(() => { f = mergeFact(SID, [row(SID, 'c1', 'legacy row, no source field')]); });
  assert.equal(f.count, 1);
  assert.equal(f.merges[0].source, DEFAULT_MERGE_LINK_SOURCE,
    'a pre-856 row is a teardown-observed link, not an unknown-provenance one');
  assert.equal(f.merges[0].kind, 'fact');
  assert.equal(f.merges[0].commit, 'c1');
});

test('rows with and without source coexist (chunk C lands source incrementally)', () => {
  const f = mergeFact(SID, [
    row(SID, 'c1', 'legacy'),
    row(SID, 'c2', 'new', { source: MERGE_LINK_SOURCES.COMMIT_MESSAGE }),
  ]);
  assert.equal(f.count, 2);
  assert.equal(f.merges[0].source, DEFAULT_MERGE_LINK_SOURCE);
  assert.equal(f.merges[1].source, MERGE_LINK_SOURCES.COMMIT_MESSAGE);
  assert.notEqual(f.merges[0].source, f.merges[1].source,
    'the two provenances must remain distinguishable, not be collapsed');
});

test('a malformed row shape degrades to nulls rather than throwing', () => {
  let f;
  assert.doesNotThrow(() => {
    f = mergeFact(SID, [null, { session_id: SID }, row(SID, 'c9', 'ok')]);
  });
  assert.equal(f.value, true);
  assert.equal(f.count, 2, 'the null row is skipped; the field-less row is still a link');
  assert.equal(f.merges[0].commit, null);
  assert.equal(f.merges[0].subject, null);
  assert.equal(f.merges[0].source, DEFAULT_MERGE_LINK_SOURCE);
  assert.equal(f.merges[1].commit, 'c9');
});

// --- the block tier is DERIVED from the rows, never pinned (856 §3.1) -------

test('all-inference rows -> the BLOCK is inference, not fact', () => {
  const f = mergeFact(SID, [
    row(SID, 'r1', 'recovered A', { source: MERGE_LINK_SOURCES.SHARD_INFERENCE }),
    row(SID, 'r2', 'recovered B', { source: MERGE_LINK_SOURCES.SHARD_INFERENCE }),
  ]);
  assert.equal(f.kind, 'inference',
    'a fact-tier block on inference-only evidence is the catalog-verbatim shape §3.1 prevents');
  assert.notEqual(f.kind, 'fact', 'the fact tier must not absorb a derived row');
  assert.equal(f.value, true, 'the claim itself still stands — only its tier changes');
  assert.equal(f.count, 2);
  // the block-level derivation must not rewrite the rows it derived from
  assert.deepEqual(f.merges.map(m => m.kind), ['inference', 'inference']);
  assert.deepEqual(f.merges.map(m => m.source),
    [MERGE_LINK_SOURCES.SHARD_INFERENCE, MERGE_LINK_SOURCES.SHARD_INFERENCE]);
});

test('all-inference block carries a source that claims no more than it has', () => {
  const f = mergeFact(SID, [row(SID, 'r1', 'recovered', { source: MERGE_LINK_SOURCES.SHARD_INFERENCE })]);
  assert.equal(f.source, MERGE_LINK_SOURCES.SHARD_INFERENCE,
    'the block must not claim ledger-observation provenance for derived-only evidence');
  assert.notEqual(f.source, 'git/session-merges');
  assert.match(f.note, /recovered by 'shard-inference'/i);
  assert.match(f.note, /8\.9%/, 'the measured error rate must travel with the claim');
});

// --- the block source is read off what the rows DECLARE, not off their tier ---
// Regression: a row can carry a `kind` that disagrees with its `source`, and deriving the
// block source from the tier then manufactures a provenance nothing observed.

test('REVIEWER CASE: kind:inference with a non-shard source must NOT yield a shard-inference block', () => {
  // normalizeMergeLinkRow keeps an explicit `kind` and backfills the absent source to
  // teardown, so this row lands as {source:'teardown', kind:'inference'}.
  const f = mergeFact(SID, [{ session_id: SID, merge_commit: 'c1', subject: 's', kind: 'inference' }]);
  assert.equal(f.merges[0].source, MERGE_LINK_SOURCES.TEARDOWN, 'precondition: the row declares teardown');
  assert.equal(f.merges[0].kind, 'inference', 'precondition: the row declares the inference tier');

  assert.notEqual(f.source, MERGE_LINK_SOURCES.SHARD_INFERENCE,
    'the block must not claim a recovery source the row never declared');
  assert.equal(f.source, null, 'a source that disagrees with its own tier is not namable');
  assert.equal(f.kind, 'inference', 'no row claims fact, so the block must not claim fact either');
  assert.match(f.note, /declare no single derivation source/i);
  assert.match(f.note, /teardown/, 'the note must report the source actually seen');
});

test('a source declared by the row but not a derivation source is never named', () => {
  for (const src of [MERGE_LINK_SOURCES.TEARDOWN, MERGE_LINK_SOURCES.PUBLISH, MERGE_LINK_SOURCES.COMMIT_MESSAGE]) {
    const f = mergeFact(SID, [{ session_id: SID, merge_commit: 'c1', subject: 's', source: src, kind: 'inference' }]);
    assert.equal(f.source, null, `${src} denotes observation, so an inference block must not name it`);
    assert.equal(f.merges[0].source, src, 'the row keeps its own declared source regardless');
  }
});

test('derived rows disagreeing on source -> the block names none and reports both', () => {
  const f = mergeFact(SID, [
    { session_id: SID, merge_commit: 'r1', subject: 'a', source: MERGE_LINK_SOURCES.SHARD_INFERENCE },
    { session_id: SID, merge_commit: 'r2', subject: 'b', source: MERGE_LINK_SOURCES.TEARDOWN, kind: 'inference' },
  ]);
  assert.equal(f.kind, 'inference');
  assert.equal(f.source, null, 'disagreeing sources must not be collapsed to one');
  assert.notEqual(f.source, MERGE_LINK_SOURCES.SHARD_INFERENCE);
  assert.match(f.note, /shard-inference/, 'both declared sources must be reported');
  assert.match(f.note, /teardown/);
  assert.deepEqual(f.merges.map(m => m.source),
    [MERGE_LINK_SOURCES.SHARD_INFERENCE, MERGE_LINK_SOURCES.TEARDOWN], 'per-row sources unchanged');
});

test('a unanimous genuine shard-inference set is still named (the fix does not over-suppress)', () => {
  const f = mergeFact(SID, [
    row(SID, 'r1', 'a', { source: MERGE_LINK_SOURCES.SHARD_INFERENCE }),
    row(SID, 'r2', 'b', { source: MERGE_LINK_SOURCES.SHARD_INFERENCE }),
  ]);
  assert.equal(f.source, MERGE_LINK_SOURCES.SHARD_INFERENCE,
    'honest unanimous derivation must still be named, or the block loses real provenance');
  assert.notEqual(f.source, null);
});

test('mixed rows -> the BLOCK is fact (one observed link is enough), per-row tiers preserved', () => {
  const f = mergeFact(SID, [
    row(SID, 'r1', 'recovered', { source: MERGE_LINK_SOURCES.SHARD_INFERENCE }),
    row(SID, 'o1', 'observed at teardown', { source: MERGE_LINK_SOURCES.TEARDOWN }),
  ]);
  assert.equal(f.kind, 'fact', 'one observed link makes "this session merged something" an observation');
  assert.equal(f.source, 'git/session-merges');
  assert.equal('note' in f, false, 'no derived-evidence caveat belongs on an observed block');
  assert.deepEqual(f.merges.map(m => m.kind), ['inference', 'fact'],
    'per-row tiers must survive the block-level derivation unchanged');
  assert.deepEqual(f.merges.map(m => m.source),
    [MERGE_LINK_SOURCES.SHARD_INFERENCE, MERGE_LINK_SOURCES.TEARDOWN]);
});

test('all-observed rows -> fact block (the ordinary case is unchanged)', () => {
  const f = mergeFact(SID, [
    row(SID, 'o1', 'teardown', { source: MERGE_LINK_SOURCES.TEARDOWN }),
    row(SID, 'o2', 'publish', { source: MERGE_LINK_SOURCES.PUBLISH }),
    row(SID, 'o3', 'commit-message', { source: MERGE_LINK_SOURCES.COMMIT_MESSAGE }),
  ]);
  assert.equal(f.kind, 'fact');
  assert.equal(f.source, 'git/session-merges');
  assert.deepEqual(f.merges.map(m => m.kind), ['fact', 'fact', 'fact']);
});

test('legacy (source-less) rows produce a fact block — they are teardown observations', () => {
  const f = mergeFact(SID, [row(SID, 'c1', 'pre-856 row')]);
  assert.equal(f.kind, 'fact', 'a pre-856 row is observed, so it must not demote the block');
  assert.equal(f.source, 'git/session-merges');
});

test('the unknown block stays fact-tier (the absence itself is observed)', () => {
  const f = mergeFact(SID, []);
  assert.equal(f.value, 'unknown');
  assert.equal(f.kind, 'fact', 'only a merge CLAIM can be derived; an honest unknown is an observation');
});

// --- an `unknown`-tier row cannot support value:true -----------------------
// The sibling's normalizeMergeLinkRow gives an UNRECOGNISED source kind:'unknown' rather
// than laundering it into fact. Rows we cannot recognise leave us where no rows do.

const UNRECOGNISED = 'some-foreign-writer';

test('all-unknown rows -> value is "unknown", and specifically NOT true', () => {
  const f = mergeFact(SID, [
    row(SID, 'u1', 'from a writer we do not know', { source: UNRECOGNISED }),
    row(SID, 'u2', 'likewise', { source: UNRECOGNISED }),
  ]);
  assert.equal(f.merges[0].kind, 'unknown', 'precondition: an unrecognised source is unknown-tier');
  assert.equal(f.value, 'unknown', 'unrecognised provenance cannot carry a merge claim');
  assert.notEqual(f.value, true, 'a merge must not be asserted on unrecognisable evidence');
  assert.equal(f.value === true, false);
  assert.notEqual(f.kind, 'inference', 'and it must not claim a derivation nobody performed');
  assert.equal(f.kind, 'fact', 'the absence of recognisable evidence is itself an observation');
});

test('all-unknown is distinguishable from the empty case — the rows stay legible', () => {
  const rows = [row(SID, 'u1', 'x', { source: UNRECOGNISED })];
  const withRows = mergeFact(SID, rows);
  const empty = mergeFact(SID, []);

  assert.equal(withRows.value, empty.value, 'both report unknown');
  assert.notEqual(withRows.note, empty.note, 'but they must not be indistinguishable');
  assert.match(withRows.note, /rows exist/i, 'the note must say rows were recorded');
  assert.match(withRows.note, new RegExp(UNRECOGNISED), 'and name the unrecognised source');
  assert.equal(withRows.count, 1, 'the count must survive');
  assert.equal(withRows.merges.length, 1, 'the rows must not be swallowed');
  assert.equal(withRows.merges[0].commit, 'u1');
  assert.equal('merges' in empty, false, 'the empty case still carries no row set');
});

test('one fact plus unknowns -> fact block, with the unknown rows still listed', () => {
  const f = mergeFact(SID, [
    row(SID, 'u1', 'unrecognised', { source: UNRECOGNISED }),
    row(SID, 'o1', 'observed', { source: MERGE_LINK_SOURCES.TEARDOWN }),
  ]);
  assert.equal(f.value, true, 'one observed link still proves a merge');
  assert.equal(f.kind, 'fact');
  assert.equal(f.source, 'git/session-merges');
  assert.equal(f.count, 2, 'unknown rows must not be dropped from the count');
  assert.deepEqual(f.merges.map(m => m.kind), ['unknown', 'fact'], 'both rows listed, tiers intact');
});

test('one inference plus unknowns -> inference block (unknowns do not demote it further)', () => {
  const f = mergeFact(SID, [
    row(SID, 'u1', 'unrecognised', { source: UNRECOGNISED }),
    row(SID, 'r1', 'recovered', { source: MERGE_LINK_SOURCES.SHARD_INFERENCE }),
  ]);
  assert.equal(f.value, true, 'a genuine derived link still carries the claim');
  assert.equal(f.kind, 'inference');
  assert.equal(f.source, MERGE_LINK_SOURCES.SHARD_INFERENCE,
    'the source comes from the derived row that carries the claim, not from the unknown one');
  assert.equal(f.count, 2);
  assert.deepEqual(f.merges.map(m => m.kind), ['unknown', 'inference']);
});

test('an unknown row does not pollute the derived block source', () => {
  const f = mergeFact(SID, [
    row(SID, 'u1', 'unrecognised', { source: UNRECOGNISED }),
    row(SID, 'r1', 'recovered', { source: MERGE_LINK_SOURCES.SHARD_INFERENCE }),
  ]);
  assert.notEqual(f.source, UNRECOGNISED, 'a foreign source must never become the block source');
  assert.notEqual(f.source, null, 'nor should an unknown row suppress a genuine unanimous derivation');
});
