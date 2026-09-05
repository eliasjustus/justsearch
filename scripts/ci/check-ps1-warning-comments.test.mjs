/* eslint-disable no-warning-comments -- same reason as the module under test: this file's
   SUBJECT is the marker vocabulary, so its prose has to name the terms. */
/**
 * Tests for the `*.ps1` TODO-marker check (scripts/ci/check-ps1-warning-comments.mjs).
 *
 * A gate that cannot fail reads as coverage, so these exercise the BITE: a marker in a `#` line
 * comment, in a `<# … #>` block, and the false-positive cases the retired `todo-fixme` gate's
 * regex-over-raw-text approach got wrong (a marker inside a string literal or a path is CODE,
 * not a comment). The last block runs the real check against the real tree.
 *
 * Run: `node --test scripts/ci/check-ps1-warning-comments.test.mjs`
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { scan, unsuppressed, SUPPRESSIONS, REPO_ROOT } from './check-ps1-warning-comments.mjs';

function withTree(files, fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ps1-marker-'));
  try {
    for (const [name, body] of Object.entries(files)) {
      fs.mkdirSync(path.dirname(path.join(dir, name)), { recursive: true });
      fs.writeFileSync(path.join(dir, name), body, 'utf8');
    }
    return fn(dir, Object.keys(files));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

test('flags a marker in a line comment', () => {
  withTree({ 'a.ps1': 'Write-Host "hi"\n# TODO: finish this\n' }, (dir, files) => {
    const found = scan(dir, files);
    assert.equal(found['a.ps1'].length, 1);
    assert.equal(found['a.ps1'][0].line, 2);
  });
});

test('flags every term, case-insensitively, anywhere in the comment', () => {
  const body = '# leading prose FIXME trailing\n# xxx\n# a Hack for now\n# TODO\n';
  withTree({ 'a.ps1': body }, (dir, files) => {
    assert.equal(scan(dir, files)['a.ps1'].length, 4);
  });
});

test('flags a marker inside a <# … #> block comment', () => {
  withTree({ 'a.ps1': '<#\n  TODO: block-comment debt\n#>\nWrite-Host "x"\n' }, (dir, files) => {
    assert.equal(scan(dir, files)['a.ps1'].length, 1);
  });
});

test('does not flag a marker in a string literal or a path (code, not comment)', () => {
  const body = '$msg = "TODO is a word"\n$p = "C:/todo/fixme.txt"\nWrite-Host $msg\n';
  withTree({ 'a.ps1': body }, (dir, files) => {
    assert.deepEqual(scan(dir, files), {});
  });
});

test('does not flag a term embedded in a longer word', () => {
  withTree({ 'a.ps1': '# hackathon results and todos are fine\n' }, (dir, files) => {
    assert.deepEqual(scan(dir, files), {});
  });
});

test('a clean file produces no entry at all', () => {
  withTree({ 'a.ps1': '# ordinary comment\nWrite-Host "x"\n' }, (dir, files) => {
    assert.deepEqual(scan(dir, files), {});
  });
});

test('suppression pins the existing count and only the excess is reported', () => {
  const found = { 'a.ps1': [{ line: 1, text: '# TODO one' }, { line: 2, text: '# TODO two' }] };
  assert.deepEqual(unsuppressed(found, { 'a.ps1': { 'no-warning-comments': { count: 2 } } }), []);
  const excess = unsuppressed(found, { 'a.ps1': { 'no-warning-comments': { count: 1 } } });
  assert.equal(excess.length, 1);
  assert.equal(excess[0][1].length, 1);
  assert.equal(unsuppressed(found, {}).length, 1);
});

test('the real tree is at or below its pinned counts, and every pin still has a marker', () => {
  const found = scan();
  assert.deepEqual(unsuppressed(found, JSON.parse(fs.readFileSync(SUPPRESSIONS, 'utf8'))), []);
  for (const rel of Object.keys(JSON.parse(fs.readFileSync(SUPPRESSIONS, 'utf8')))) {
    assert.ok(fs.existsSync(path.join(REPO_ROOT, rel)), `stale suppression entry: ${rel}`);
    assert.ok(found[rel]?.length > 0, `suppression entry with no marker behind it: ${rel}`);
  }
});
