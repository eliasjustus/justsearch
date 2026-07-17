/**
 * Tempdoc 743 second wave, Slice 3 — unit tests for lib/transcript-store.mjs.
 *
 * Run with: `node scripts/agent-analytics/lib/transcript-store.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  discoverProjectDirs, listSessions, listSubagentPaths, streamLines, iterateTurns,
} from './transcript-store.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'transcript-store-test-'));

// --- discovery tolerates a missing/nonexistent projects root -------------

run('discoverProjectDirs returns [] for a nonexistent root, not a throw', () => {
  const missing = path.join(tmp, 'does-not-exist-' + Math.random().toString(36).slice(2));
  assert.deepEqual(discoverProjectDirs(missing), []);
});

run('listSessions returns [] for a nonexistent root, not a throw', () => {
  const missing = path.join(tmp, 'also-missing-' + Math.random().toString(36).slice(2));
  assert.deepEqual(listSessions({ projectsRoot: missing }), []);
});

run('listSubagentPaths returns [] when there is no subagents dir', () => {
  assert.deepEqual(listSubagentPaths(tmp, 'no-such-session'), []);
});

run('discoverProjectDirs only matches /justsearch/i dirs, skips others', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'root-'));
  fs.mkdirSync(path.join(root, 'F--justsearch-public'));
  fs.mkdirSync(path.join(root, 'F--justsearch-public--claude-worktrees-foo'));
  fs.mkdirSync(path.join(root, 'F--some-other-repo'));
  const dirs = discoverProjectDirs(root).map((d) => d.name).sort();
  assert.deepEqual(dirs, ['F--justsearch-public', 'F--justsearch-public--claude-worktrees-foo']);
});

run('listSessions discovers .jsonl files and applies sinceMs/untilMs mtime filters', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'root-'));
  const proj = path.join(root, 'F--justsearch-public');
  fs.mkdirSync(proj);
  const p1 = path.join(proj, 'session-old.jsonl');
  const p2 = path.join(proj, 'session-new.jsonl');
  fs.writeFileSync(p1, '{}\n', 'utf8');
  fs.writeFileSync(p2, '{}\n', 'utf8');
  const oldMs = Date.now() - 10 * 24 * 3600 * 1000;
  const newMs = Date.now();
  fs.utimesSync(p1, new Date(oldMs), new Date(oldMs));
  fs.utimesSync(p2, new Date(newMs), new Date(newMs));

  const all = listSessions({ projectsRoot: root });
  assert.equal(all.length, 2);
  for (const s of all) {
    assert.ok(s.path && s.sessionId && s.projectDir === 'F--justsearch-public');
    assert.equal(typeof s.mtime, 'number');
    assert.equal(typeof s.size, 'number');
  }

  const recentOnly = listSessions({ projectsRoot: root, sinceMs: Date.now() - 3600 * 1000 });
  assert.deepEqual(recentOnly.map((s) => s.sessionId), ['session-new']);
});

// --- streamLines skips bad JSON per-line, doesn't abort the read ---------

run('streamLines calls onLine only for parseable lines, skipping bad ones', () => {
  const file = path.join(tmp, 'stream-lines.jsonl');
  fs.writeFileSync(file, [
    '{"a":1}',
    'not json at all {{{',
    '',
    '{"a":2}',
    '{"a":3', // truncated
    '{"a":4}',
  ].join('\n'), 'utf8');

  const seen = [];
  streamLines(file, (parsed, lineNumber) => seen.push({ parsed, lineNumber }));
  assert.deepEqual(seen.map((s) => s.parsed.a), [1, 2, 4]);
  assert.deepEqual(seen.map((s) => s.lineNumber), [1, 4, 6]);
});

run('streamLines on a missing file is a silent no-op', () => {
  let called = false;
  streamLines(path.join(tmp, 'nope.jsonl'), () => { called = true; });
  assert.equal(called, false);
});

// --- iterateTurns on a small synthetic fixture ---------------------------

const FIXTURE_LINES = [
  JSON.stringify({
    type: 'user',
    timestamp: '2026-07-17T10:00:00.000Z',
    isSidechain: false,
    message: { content: 'please fix the bug' },
  }),
  JSON.stringify({
    type: 'assistant',
    timestamp: '2026-07-17T10:00:05.000Z',
    isSidechain: false,
    message: {
      content: [
        { type: 'text', text: 'Looking into it.' },
        { type: 'tool_use', name: 'Bash', input: { command: 'ls' } },
      ],
      usage: { input_tokens: 10, output_tokens: 20 },
    },
  }),
  JSON.stringify({
    type: 'user',
    timestamp: '2026-07-17T10:00:06.000Z',
    isSidechain: false,
    message: {
      content: [
        { type: 'tool_result', is_error: true, content: 'ENOENT: file not found' },
      ],
    },
  }),
  'this line is not valid JSON {{{',
  JSON.stringify({
    type: 'assistant',
    timestamp: '2026-07-17T10:00:10.000Z',
    isSidechain: true,
    message: {
      content: [{ type: 'text', text: 'Retrying with a different path.' }],
      usage: { input_tokens: 5, output_tokens: 8 },
    },
  }),
];

run('iterateTurns yields one turn per parseable line, skipping bad ones', () => {
  const file = path.join(tmp, 'fixture.jsonl');
  fs.writeFileSync(file, FIXTURE_LINES.join('\n'), 'utf8');

  const turns = [...iterateTurns(file)];
  assert.equal(turns.length, 4, 'the unparseable line must not produce a turn');

  assert.equal(turns[0].type, 'user');
  assert.equal(turns[0].userText, 'please fix the bug');
  assert.equal(turns[0].isSidechain, false);
  assert.deepEqual(turns[0].toolResults, []);

  assert.equal(turns[1].type, 'assistant');
  assert.equal(turns[1].assistantText, 'Looking into it.');
  assert.deepEqual(turns[1].toolUses, [{ name: 'Bash', input: { command: 'ls' } }]);
  assert.deepEqual(turns[1].usage, { input_tokens: 10, output_tokens: 20 });

  assert.equal(turns[2].type, 'user');
  assert.equal(turns[2].userText, '');
  assert.equal(turns[2].toolResults.length, 1);
  assert.equal(turns[2].toolResults[0].isError, true);
  assert.equal(turns[2].toolResults[0].text, 'ENOENT: file not found');

  assert.equal(turns[3].isSidechain, true);
  assert.equal(turns[3].assistantText, 'Retrying with a different path.');
});

run('iterateTurns on a missing file yields nothing (empty generator, no throw)', () => {
  const turns = [...iterateTurns(path.join(tmp, 'missing.jsonl'))];
  assert.deepEqual(turns, []);
});

if (failures.length > 0) {
  for (const f of failures) console.error(`FAIL ${f}`);
  console.error(`\n${failures.length}/${passed + failures.length} failure(s).`);
  process.exit(1);
}
console.log(`all ${passed} transcript-store checks passed.`);
