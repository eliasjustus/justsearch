/**
 * Tempdoc 743 second wave, Slice 3 — unit tests for signature-census.mjs.
 *
 * Classifier precision is tested against REAL error strings reproduced live
 * on this machine during Slice 3 (not paraphrases) — see the Bash-tool
 * transcript this session's own report cites. The duplicate-user-message
 * filter cases test `dedupeAdjacentUserTurns` (lib/transcript-store.mjs),
 * the shared substrate signature-census.mjs's `countRealUserMessagesInTurns`
 * is built on (tempdoc 743 evidence-lane finding F-1).
 *
 * Run with: `node scripts/agent-analytics/signature-census.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { classify, countRealUserMessagesInTurns } from './signature-census.mjs';
import { dedupeAdjacentUserTurns } from './lib/transcript-store.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

// --- classifier precision, real error strings ----------------------------

run('classifies the real PowerShell call-operator bash syntax error', () => {
  // Reproduced live on this box (Slice 3, 2026-07-17):
  // `& "F:\scoop\apps\gh\2.90.0\bin\gh.exe" --version` pasted into bash.
  const text = [
    '/usr/bin/bash: eval: line 2: syntax error near unexpected token `&\'',
    '/usr/bin/bash: eval: line 2: `& "F:\\scoop\\apps\\gh\\2.90.0\\bin\\gh.exe" --version 2>&1; echo "EXIT:$?"\'',
  ].join('\n');
  assert.equal(classify(text), 'ps-call-operator-in-bash');
});

run('classifies a real cp1252/UnicodeEncodeError charmap traceback', () => {
  // Reproduced live on this box (Slice 3, 2026-07-17):
  // `python -c "print('café → test')"` on the Windows cp1252 console.
  const text = [
    'Traceback (most recent call last):',
    '  File "<string>", line 1, in <module>',
    "    print('caf� \\u2192 test')",
    '  File "F:\\scoop\\apps\\python\\current\\Lib\\encodings\\cp1252.py", line 19, in encode',
    '    return codecs.charmap_encode(input,self.errors,encoding_table)[0]',
    "UnicodeEncodeError: 'charmap' codec can't encode character '\\u2192' in position 5: character maps to <undefined>",
  ].join('\n');
  assert.equal(classify(text), 'cp1252-encode');
});

run('classifies a real unexpected-EOF quoting error', () => {
  // Reproduced live on this box (Slice 3, 2026-07-17): a mismatched-quote
  // Bash tool call earlier in this same session.
  const text = "/usr/bin/bash: eval: line 2: unexpected EOF while looking for matching `\"'";
  assert.equal(classify(text), 'quoting-eof');
});

run('classifies an InputValidationError schema-not-loaded message', () => {
  const text = 'InputValidationError: tool "mcp__github__get_pull_request" is not in the discovered-tool set for this turn — call ToolSearch first.';
  assert.equal(classify(text), 'schema-not-loaded');
});

run('classifies a gh pending/exit-code message', () => {
  const text = 'gh pr checks 4821\n  ci/build   pending   0s\nexit status 8';
  assert.equal(classify(text), 'gh-pending-exit');
});

run('classifies a /tmp path-dialect miss', () => {
  const text = 'cat: /tmp/overhead-taxonomy.json: No such file or directory';
  assert.equal(classify(text), 'path-not-found-dialect');
});

run('classifies an edit-not-read error', () => {
  const text = 'File has not been read yet. Read it first before writing to it.';
  assert.equal(classify(text), 'edit-not-read');
});

run('classify returns null for text matching no signature', () => {
  assert.equal(classify('Compilation succeeded, 0 errors, 0 warnings.'), null);
});

run('classify prefers the first matching signature when a text could match more than one', () => {
  // A quoting-EOF message that also happens to contain "& " should still
  // classify by the FIRST regex it matches in signature order.
  const text = '& "some path" unexpected EOF while looking for matching `"\'';
  assert.equal(classify(text), 'ps-call-operator-in-bash');
});

// --- duplicate-user-message artifact filter (tempdoc 743 F-1) ------------

function userTurn(text, extra = {}) {
  return { type: 'user', userText: text, toolResults: [], ...extra };
}

run('adjacent near-duplicate user turns collapse to one (storage artifact)', () => {
  const turns = [
    userTurn('please copy the documentation over'),
    userTurn('please copy the documentation over'), // storage artifact, no interrupt marker
    { type: 'assistant', assistantText: 'On it.', toolResults: [], toolUses: [] },
  ];
  const out = dedupeAdjacentUserTurns(turns);
  const userTexts = out.filter((t) => t.type === 'user').map((t) => t.userText);
  assert.deepEqual(userTexts, ['please copy the documentation over']);
  assert.equal(countRealUserMessagesInTurns(turns), 1);
});

run('an interrupt-marked repeat is kept as two real user messages', () => {
  const turns = [
    userTurn('please copy the documentation over'),
    // The interrupt marker is embedded in the SAME turn as the repeat, the
    // shape Claude Code actually produces (aborted tool's result batched
    // with the user's next typed message in one JSONL entry).
    userTurn('please copy the documentation over', {
      toolResults: [{ isError: true, text: '[Request interrupted by user]' }],
    }),
  ];
  const out = dedupeAdjacentUserTurns(turns);
  const userTexts = out.filter((t) => t.type === 'user').map((t) => t.userText);
  assert.deepEqual(userTexts, ['please copy the documentation over', 'please copy the documentation over']);
  assert.equal(countRealUserMessagesInTurns(turns), 2);
});

run('the dominant real shape (marker as its own pure-text user turn) keeps the repeat — verified against 56/57 real occurrences on this machine, tempdoc 743 Slice 3', () => {
  const turns = [
    userTurn('the agent finished, now copy all relevant documentation over'),
    userTurn('[Request interrupted by user]'),
    userTurn('the agent finished, now copy all relevant documentation over'),
  ];
  const out = dedupeAdjacentUserTurns(turns);
  const userTexts = out.filter((t) => t.type === 'user').map((t) => t.userText);
  assert.deepEqual(userTexts, [
    'the agent finished, now copy all relevant documentation over',
    '[Request interrupted by user]',
    'the agent finished, now copy all relevant documentation over',
  ]);
  assert.equal(countRealUserMessagesInTurns(turns), 3);
});

run('a rare real shape (marker as a tool_result on its own turn between duplicates) also keeps the repeat', () => {
  const turns = [
    userTurn('please copy the documentation over'),
    { type: 'user', userText: '', toolResults: [{ isError: true, text: '[Request interrupted by user]' }] },
    userTurn('please copy the documentation over'),
  ];
  const out = dedupeAdjacentUserTurns(turns);
  const userTexts = out.filter((t) => t.type === 'user' && t.userText).map((t) => t.userText);
  assert.deepEqual(userTexts, ['please copy the documentation over', 'please copy the documentation over']);
});

run('a THIRD consecutive duplicate with no further interrupt marker collapses again', () => {
  const turns = [
    userTurn('please copy the documentation over'),
    userTurn('please copy the documentation over', {
      toolResults: [{ isError: true, text: '[Request interrupted by user]' }],
    }),
    userTurn('please copy the documentation over'), // no new marker — artifact again
  ];
  const out = dedupeAdjacentUserTurns(turns);
  const userTexts = out.filter((t) => t.type === 'user').map((t) => t.userText);
  assert.deepEqual(userTexts, ['please copy the documentation over', 'please copy the documentation over']);
});

run('non-adjacent (different text) user turns are never collapsed', () => {
  const turns = [
    userTurn('please copy the documentation over'),
    userTurn('actually, do the tests first'),
  ];
  const out = dedupeAdjacentUserTurns(turns);
  assert.equal(out.filter((t) => t.type === 'user').length, 2);
});

if (failures.length > 0) {
  for (const f of failures) console.error(`FAIL ${f}`);
  console.error(`\n${failures.length}/${passed + failures.length} failure(s).`);
  process.exit(1);
}
console.log(`all ${passed} signature-census checks passed.`);
