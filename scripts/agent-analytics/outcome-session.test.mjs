/**
 * Unit tests for outcome-session.mjs.
 *
 * Tempdoc 856 chunk A — `mergeFact`. Two defects under test:
 *   1. Absent evidence was reported as negative evidence (`fact(false, …)`), at the
 *      fact tier that the LLM judge is forbidden to overwrite. §3.2: absent -> 'unknown'.
 *   2. `.at(-1)` discarded every merge but the last. §6: report the full set.
 *
 * Tempdoc 858 §3/§3.1/§9.1 — outcomes are a VIEW, and captured facts are marked:
 *   3. The default run must write no file; `--write` is opt-in and stamps its output
 *      as a generated report rather than a maintained authority.
 *   4. A field whose source time destroys (shared SARIF, deleted build counter, paid
 *      judge verdict) must be distinguishable from a recomputable one — `basis`.
 *
 * The `mergeFact` tests are pure. The CLI tests spawn the script into a scratch
 * directory under the OS temp dir; nothing is ever written under `tmp/agent-telemetry`.
 * Importing outcome-session.mjs must not run its CLI main(), which the import guard ensures.
 *
 * Run with: `node --test scripts/agent-analytics/outcome-session.test.mjs`
 * (also runs bare via `node <file>`, which is how run-all-tests.mjs invokes it).
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import {
  mergeFact, buildFact, gatesFact, tempdocFacts, inferenceBlock,
  capturedFields, reportStamp, outcomeForSession, loadJoinInputs, BASIS,
} from './outcome-session.mjs';
import {
  MERGE_LINK_SOURCES, DEFAULT_MERGE_LINK_SOURCE,
  repoRoot, TELEMETRY_DIR, OUTCOMES_FILE,
} from './lib/telemetry-io.mjs';

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

// ===========================================================================
// Tempdoc 858 §3 — outcomes are a VIEW: printing is the default, writing opt-in
// ===========================================================================

const CLI = fileURLToPath(new URL('./outcome-session.mjs', import.meta.url));

/**
 * Scratch dir under the OS temp dir. Deliberately NOT under `tmp/agent-telemetry`:
 * these tests must leave no residue in the telemetry lane they are testing.
 */
function withScratch(fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'outcome-session-test-'));
  try { return fn(dir); } finally { fs.rmSync(dir, { recursive: true, force: true }); }
}

const runCli = (...args) => spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8' });

test('the DEFAULT run computes and prints, and writes NO file (858 §3)', () => {
  withScratch(dir => {
    const out = path.join(dir, 'report.ndjson');
    const res = runCli('--session-id', SID, '--out', out);
    assert.equal(res.status, 0, `CLI must succeed; stderr:\n${res.stderr}`);
    assert.equal(fs.existsSync(out), false,
      'a bare run must not persist anything — the whole point of §3 is that the store stops existing');
    assert.equal(fs.readdirSync(dir).length, 0, 'not even a temp file may be left behind');
    assert.match(res.stdout, /computed on demand, nothing written/i,
      'the reader must be told the record is a view, not a file they can go read');
    assert.match(res.stdout, /--write/, 'and told how to opt in to a report');
  });
});

test('--json without --write still writes nothing', () => {
  withScratch(dir => {
    const out = path.join(dir, 'report.ndjson');
    const res = runCli('--session-id', SID, '--out', out, '--json');
    assert.equal(res.status, 0, res.stderr);
    assert.equal(fs.existsSync(out), false, '--json is a print mode, not a write mode');
    const rec = JSON.parse(res.stdout);
    assert.equal(rec.session_id, SID);
  });
});

test('--write emits the report, atomically, leaving no .tmp residue', () => {
  withScratch(dir => {
    const out = path.join(dir, 'report.ndjson');
    const res = runCli('--session-id', SID, '--out', out, '--write');
    assert.equal(res.status, 0, res.stderr);
    assert.equal(fs.existsSync(out), true, '--write must actually write');
    assert.deepEqual(fs.readdirSync(dir), ['report.ndjson'],
      'atomicWriteFileSync must rename its temp file away, not leave a torn-write artifact');
    const lines = fs.readFileSync(out, 'utf8').trim().split('\n');
    assert.equal(lines.length, 1);
    assert.equal(JSON.parse(lines[0]).session_id, SID);
    assert.match(res.stdout, /not an authority/i,
      'even the console line must not call the file an authority');
  });
});

test('a written record is stamped as GENERATED, with provenance and a recompute recipe', () => {
  withScratch(dir => {
    const out = path.join(dir, 'report.ndjson');
    const before = Date.now();
    assert.equal(runCli('--session-id', SID, '--out', out, '--write').status, 0);
    const rec = JSON.parse(fs.readFileSync(out, 'utf8').trim());

    assert.equal(rec.report.kind, 'report', 'the stamp must say what the record IS');
    const gen = Date.parse(rec.report.generated_at);
    assert.ok(!Number.isNaN(gen), 'generated_at must be a parseable timestamp');
    assert.ok(gen >= before - 1000 && gen <= Date.now() + 1000,
      'the stamp must be this generation, not a copied constant');
    assert.equal(rec.report.generator, 'scripts/agent-analytics/outcome-session.mjs',
      'a reader must be able to find what produced the file');
    assert.match(rec.report.recompute, /outcome-session\.mjs .*--session-id sess-under-test/,
      'the stamp must carry the command that supersedes it');
    assert.match(rec.report.note, /not maintained state/i,
      'the stamp must deny authority in words, not only by convention');
    assert.ok(Array.isArray(rec.report.captured_fields));
    assert.equal(rec.ts, rec.report.generated_at,
      'the legacy `ts` alias must agree with the stamp rather than drift from it');
  });
});

test('the stamp is PER-RECORD, so an upserted row keeps its own generation time', () => {
  withScratch(dir => {
    const out = path.join(dir, 'report.ndjson');
    const old = { session_id: 'ancient-session', facts: {}, inference: null,
      report: { kind: 'report', generated_at: '2020-01-01T00:00:00.000Z' } };
    fs.writeFileSync(out, JSON.stringify(old) + '\n', 'utf8');

    assert.equal(runCli('--session-id', SID, '--out', out, '--write').status, 0);
    const rows = fs.readFileSync(out, 'utf8').trim().split('\n').map(l => JSON.parse(l));
    assert.equal(rows.length, 2, 'the upsert must keep the pre-existing row');
    const kept = rows.find(r => r.session_id === 'ancient-session');
    assert.equal(kept.report.generated_at, '2020-01-01T00:00:00.000Z',
      'a row this run did not regenerate must NOT inherit this run\'s timestamp — exactly what a file-level header would have done');
    const fresh = rows.find(r => r.session_id === SID);
    assert.notEqual(fresh.report.generated_at, kept.report.generated_at);
  });
});

// ===========================================================================
// Tempdoc 858 §3.1 / §9.1 — captured facts are marked apart from derived ones
// ===========================================================================

test('gatesFact is CAPTURED and carries the SARIF mtime as its observation time', () => {
  withScratch(dir => {
    const sarif = path.join(dir, 'governance-report.sarif');
    fs.writeFileSync(sarif, JSON.stringify({ runs: [{ results: [] }] }), 'utf8');
    const mtime = fs.statSync(sarif).mtimeMs;
    const f = gatesFact({ start: mtime - 5000, end: mtime + 5000 }, sarif);

    assert.equal(f.value, 'pass', 'precondition: an empty results array is a pass');
    assert.equal(f.kind, 'fact', 'it is still an observation, not an inference');
    assert.equal(f.basis, BASIS.CAPTURED,
      'a single shared file overwritten by every gate run cannot be recomputed (858 §3.1)');
    assert.notEqual(f.basis, BASIS.DERIVED);
    assert.equal(f.observed_at, new Date(mtime).toISOString(),
      'the observation time must be when the SARIF was written, not when we read it');
    assert.match(f.note, /overwritten by every gate run/i,
      'the note must say WHY it is unrecomputable, or the mark is unexplained ceremony');
    assert.match(f.note, /run\.mjs:52/, 'and cite the shared --out default that causes it');
  });
});

test('a non-empty SARIF still reports fail, and is still captured', () => {
  withScratch(dir => {
    const sarif = path.join(dir, 'r.sarif');
    fs.writeFileSync(sarif, JSON.stringify({ runs: [{ results: [{ ruleId: 'x' }] }] }), 'utf8');
    const mtime = fs.statSync(sarif).mtimeMs;
    const f = gatesFact({ start: mtime - 5000, end: mtime + 5000 }, sarif);
    assert.equal(f.value, 'fail');
    assert.equal(f.basis, BASIS.CAPTURED);
    assert.match(f.note, /1 findings/);
  });
});

test('an unattributable or absent SARIF is STILL captured, not silently derived', () => {
  withScratch(dir => {
    const sarif = path.join(dir, 'r.sarif');
    fs.writeFileSync(sarif, JSON.stringify({ runs: [] }), 'utf8');
    const mtime = fs.statSync(sarif).mtimeMs;

    const outside = gatesFact({ start: mtime + 10_000, end: mtime + 20_000 }, sarif);
    assert.equal(outside.value, 'unknown', 'mtime outside the window is not this session\'s result');
    assert.equal(outside.basis, BASIS.CAPTURED,
      'the unknown is as time-destroyed as the answer would have been — a later recompute cannot revisit it');
    assert.equal(outside.observed_at, new Date(mtime).toISOString());

    const absent = gatesFact(null, path.join(dir, 'nope.sarif'));
    assert.equal(absent.value, 'unknown');
    assert.equal(absent.basis, BASIS.CAPTURED);
    assert.ok(Date.parse(absent.observed_at) > 0, 'an absence is observed at read time');
    assert.match(absent.note, /no SARIF present at read time/i);
  });
});

test('buildFact is CAPTURED — the counter file is deleted at SessionEnd', () => {
  withScratch(dir => {
    const counter = path.join(dir, 'build-fails-x.json');
    fs.writeFileSync(counter, JSON.stringify({ consecutiveFailures: 2 }), 'utf8');
    const mtime = fs.statSync(counter).mtimeMs;
    const f = buildFact('x', counter);

    assert.equal(f.value, 'failing');
    assert.equal(f.kind, 'fact');
    assert.equal(f.basis, BASIS.CAPTURED,
      'hooks/dispatch.mjs deletes build-fails-<id>.json at SessionEnd, so this is not recomputable');
    assert.equal(f.observed_at, new Date(mtime).toISOString());
    assert.match(f.note, /consecutiveFailures=2/);
    assert.match(f.note, /not recomputable later/i);

    fs.writeFileSync(counter, JSON.stringify({ consecutiveFailures: 0 }), 'utf8');
    assert.equal(buildFact('x', counter).value, 'not_failing');
  });
});

test('a missing build counter reads unknown and says why that is not "no failures"', () => {
  withScratch(dir => {
    const f = buildFact('x', path.join(dir, 'absent.json'));
    assert.equal(f.value, 'unknown');
    assert.notEqual(f.value, 'not_failing', 'absent evidence is not negative evidence (856 §3.2)');
    assert.equal(f.basis, BASIS.CAPTURED);
    assert.match(f.note, /deleted at SessionEnd/i,
      'the note must explain that a post-session read always says unknown');
    assert.ok(Date.parse(f.observed_at) > 0);
  });
});

test('the inference block is CAPTURED (a paid derivation) and still kind:inference', () => {
  const judged = inferenceBlock({ ts: '2026-08-01T12:00:00.000Z', task_completion: 'complete', confidence: 0.9 });
  assert.equal(judged.kind, 'inference', 'the 622 tier must not change');
  assert.equal(judged.basis, BASIS.CAPTURED,
    'this join reads a cache it cannot reproduce; recomputing costs money (858 §3.1)');
  assert.equal(judged.observed_at, '2026-08-01T12:00:00.000Z', 'stamped with when it was judged');
  assert.match(judged.note, /costs money/i,
    'the reason must be distinguishable from the time-destroyed reason, and must use the same `note` key every fact block uses — not a parallel vocabulary');
  assert.equal(judged.task_completion, 'complete');
  assert.equal(inferenceBlock(null), null, 'no judge verdict stays absent, not fabricated');
});

test('mergeFact is DERIVED — the ledger survives, so recompute rather than cache', () => {
  const cases = [
    mergeFact(SID, []),
    mergeFact(SID, [row(SID, 'c1', 'observed')]),
    mergeFact(SID, [row(SID, 'r1', 'recovered', { source: MERGE_LINK_SOURCES.SHARD_INFERENCE })]),
    mergeFact(SID, [row(SID, 'u1', 'foreign', { source: 'some-foreign-writer' })]),
  ];
  for (const f of cases) {
    assert.equal(f.basis, BASIS.DERIVED,
      'session-merges.ndjson accumulates and is git-recoverable — 858 §9.1 lists it as conforming');
    assert.notEqual(f.basis, BASIS.CAPTURED);
    assert.equal('observed_at' in f, false, 'a derived field must not claim an observation moment');
  }
});

// --- REVIEWER F2: the touched-tempdoc SET rides on destructible events ------
// Both event lanes destroy history: event-writer.mjs:8-11,21-28 keeps ONE ndjson
// generation, and otlp-sink.py:133,202-216 prunes the `logs` stream to 2 archives.
// So an empty list must never be emitted where the evidence may simply have rotated.

const tempdocEvent = (lane) => ({
  event: 'post_tool_use', tool_name: 'Edit', ts: '2026-08-19T00:00:00Z',
  input_summary: { file_path: 'docs/tempdocs/858-analytics-lane-liveness.md' },
  ...(lane ? { _lane: lane } : {}),
});

test('tempdocFacts is CAPTURED — the SET rides on events that rotate away', () => {
  const f = tempdocFacts([tempdocEvent('otlp')]);
  assert.equal(f.basis, BASIS.CAPTURED,
    'the touched-tempdoc set comes from destructible session events, so it cannot be recomputed');
  assert.notEqual(f.basis, BASIS.DERIVED);
  assert.ok(Date.parse(f.observed_at) > 0, 'a captured field must carry its observation time');
  assert.match(f.note, /rotation can truncate/i, 'the note must say why the set is not recomputable');

  assert.equal(f.value.length, 1, 'the edited tempdoc must still be picked up');
  assert.equal(f.value[0].number, '858');
  assert.equal(f.value[0].basis, BASIS.DERIVED,
    'only the SET is captured — each row\'s status is re-read live from a file that survives');
  assert.match(f.value[0].note, /current truth, not the value while the session ran/i);
});

test('NO events -> "unknown", never [] — rotation must not read as "touched none"', () => {
  const gone = tempdocFacts([]);
  assert.equal(gone.value, 'unknown',
    'an empty list here would be indistinguishable from "this session touched no tempdocs"');
  assert.notDeepEqual(gone.value, [], 'specifically NOT the empty array the old code returned');
  assert.equal(gone.basis, BASIS.CAPTURED);
  assert.match(gone.note, /not "no tempdocs touched"/i,
    'the note must name the ambiguity it is avoiding');
  assert.match(gone.note, /rotation, not evidence/i);

  // …and it must stay distinguishable from a session we DID observe touching nothing
  const observedNone = tempdocFacts([{ event: 'post_tool_use', tool_name: 'Bash', _lane: 'otlp' }]);
  assert.deepEqual(observedNone.value, [],
    'events present but no tempdoc edits IS an observed empty set');
  assert.notEqual(observedNone.value, gone.value,
    'the two cases must not collapse — that collapse is the whole defect');
});

test('the event lane is reported, because the two lanes have different horizons', () => {
  assert.equal(tempdocFacts([tempdocEvent('ndjson')]).source, 'session-events/ndjson',
    'the ndjson lane keeps ONE generation — a reader must be able to tell');
  assert.equal(tempdocFacts([tempdocEvent('otlp')]).source, 'session-events/otlp',
    'the otlp logs lane keeps 2 archives — longer, but still bounded');
  assert.equal(tempdocFacts([tempdocEvent('ndjson'), tempdocEvent('otlp')]).source,
    'session-events/ndjson+otlp', 'a union must name both lanes, not pick one');
  assert.equal(tempdocFacts([tempdocEvent(null)]).source, 'session-events',
    'an untagged event must not fabricate a lane');
});

test('capturedFields names exactly the fields a stale report is the only copy of', () => {
  const record = {
    session_id: SID,
    facts: {
      merged: mergeFact(SID, [row(SID, 'c1', 'observed')]),
      build_last_status: { value: 'failing', kind: 'fact', basis: BASIS.CAPTURED, observed_at: 'x' },
      tempdocs: tempdocFacts([tempdocEvent('otlp')]),
      gates: { value: 'pass', kind: 'fact', basis: BASIS.CAPTURED, observed_at: 'y' },
    },
    inference: inferenceBlock({ ts: 'z', task_completion: 'complete' }),
  };
  const captured = capturedFields(record).sort();
  assert.deepEqual(captured,
    ['facts.build_last_status', 'facts.gates', 'facts.tempdocs', 'inference'],
    'exactly the unrecomputable four — a derived field in this list would tell a reader to keep a file they should recompute instead');
  assert.equal(captured.includes('facts.merged'), false,
    'the merge ledger survives, so it must stay off the keep-this-file list');
  assert.equal(captured.some(p => p.startsWith('facts.tempdocs.')), false,
    'the walker must stop at the captured block, not descend into its derived rows');

  const stamp = reportStamp(record, '2026-08-19T00:00:00.000Z');
  assert.deepEqual(stamp.captured_fields.sort(), captured, 'the stamp must publish the same list');
  assert.equal(stamp.generated_at, '2026-08-19T00:00:00.000Z');
});

test('an all-derived record publishes an EMPTY captured list, not a missing one', () => {
  const record = { session_id: SID, facts: { merged: mergeFact(SID, []) }, inference: null };
  const stamp = reportStamp(record, '2026-08-19T00:00:00.000Z');
  assert.deepEqual(stamp.captured_fields, [],
    'nothing captured must read as "recompute everything", not as "unknown"');
});

// ===========================================================================
// Tempdoc 858 §3 — "consumers recompute rather than read": the join is importable
// ===========================================================================

const PINNED = 1_700_000_000_000;

/** Synthetic join inputs — same shape loadJoinInputs() returns, no disk needed. */
const fakeInputs = () => ({
  sessions: new Map([[SID, [{
    event: 'post_tool_use', tool_name: 'Edit', ts: '2026-08-19T00:00:00Z',
    input_summary: { file_path: 'docs/tempdocs/858-analytics-lane-liveness.md' },
  }]]]),
  mergeRecords: [row(SID, 'c1', 'feat(x): thing'), row(OTHER, 'zz', 'noise')],
  judgeMap: new Map([[SID, { ts: '2026-08-01T12:00:00.000Z', task_completion: 'complete', task_type: 'refactor' }]]),
});

test('outcomeForSession computes one session from a session id alone, writing nothing', () => {
  const outcomesPath = path.join(repoRoot, TELEMETRY_DIR, OUTCOMES_FILE);
  const existedBefore = fs.existsSync(outcomesPath);

  const rec = outcomeForSession(SID, { inputs: fakeInputs(), nowMs: PINNED });

  assert.equal(rec.session_id, SID);
  assert.equal(rec.facts.merged.value, true, 'the join must actually join, not return a stub');
  assert.equal(rec.report.kind, 'report', 'a consumer gets the same shape the file would have held');
  assert.ok(Array.isArray(rec.report.captured_fields));
  assert.equal(rec.inference.task_type, 'refactor',
    'the field consumers read must survive the import path, not only the file path');
  assert.equal(fs.existsSync(outcomesPath), existedBefore,
    'importing and calling the join must not create the store it replaced');
});

test('outcomeForSession is idempotent — two calls produce the SAME record', () => {
  const inputs = fakeInputs();
  const a = outcomeForSession(SID, { inputs, nowMs: PINNED });
  const b = outcomeForSession(SID, { inputs, nowMs: PINNED });
  assert.deepEqual(a, b,
    'a pure function of its sources must recompute to the same record — that is what lets a consumer stop caching it');
  assert.notEqual(a, b, 'and they must be distinct objects, not the same one memoised');

  // freshly loaded inputs, same content -> still the same record
  const c = outcomeForSession(SID, { inputs: fakeInputs(), nowMs: PINNED });
  assert.deepEqual(a, c, 'identical sources must yield an identical record, not one keyed to a load');
});

test('under a real clock only the generation timestamps move; the derived facts do not', () => {
  const inputs = fakeInputs();
  const a = outcomeForSession(SID, { inputs });
  const b = outcomeForSession(SID, { inputs });

  assert.deepEqual(a.facts.merged, b.facts.merged, 'a derived fact must not vary between recomputations');
  assert.deepEqual(a.facts.tempdocs.value, b.facts.tempdocs.value,
    'a captured field\'s CONTENT is stable across recomputation; only its observation stamp moves');
  assert.deepEqual(a.inference, b.inference, 'a cached judge verdict is stable too');
  assert.ok(Date.parse(a.report.generated_at) > 0);
  assert.deepEqual(a.report.captured_fields, b.report.captured_fields,
    'which fields are captured is a property of the record, not of when it was computed');
});

test('a session with no surviving events joins, and reports tempdocs as UNKNOWN not empty', () => {
  const rec = outcomeForSession('never-seen-session', { inputs: fakeInputs(), nowMs: PINNED });
  assert.equal(rec.session_id, 'never-seen-session');
  assert.equal(rec.facts.merged.value, 'unknown', 'absent evidence, not a crash and not a false');
  assert.equal(rec.facts.tempdocs.value, 'unknown',
    'REVIEWER F2: this assertion used to bless `[]` here, which silently claimed the session touched no tempdocs when the evidence had merely rotated away');
  assert.equal(rec.inference, null);
  assert.ok(rec.report.captured_fields.includes('facts.tempdocs'),
    'and the stamp must tell a reader this field is one they cannot recompute');
});

test('loadJoinInputs returns the three join inputs and writes nothing', () => {
  const outcomesPath = path.join(repoRoot, TELEMETRY_DIR, OUTCOMES_FILE);
  const existedBefore = fs.existsSync(outcomesPath);

  const inputs = loadJoinInputs();
  assert.ok(inputs.sessions instanceof Map, 'sessions must be the grouped event map');
  assert.ok(Array.isArray(inputs.mergeRecords), 'mergeRecords must be the ledger rows');
  assert.ok(inputs.judgeMap instanceof Map, 'judgeMap must be keyed by session');
  assert.equal(fs.existsSync(outcomesPath), existedBefore, 'loading inputs must not write');

  // hoisting it must give the same answer as letting outcomeForSession load its own
  assert.deepEqual(
    outcomeForSession(SID, { inputs, nowMs: PINNED }),
    outcomeForSession(SID, { nowMs: PINNED }),
    'the hoisted-inputs path and the self-loading path must agree, or a consumer optimising its loop changes its answers');
});
