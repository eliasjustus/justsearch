#!/usr/bin/env node

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  buildSquashMessagePreview,
  renderMarkdown,
  renderText,
} from './preview-squash-message.mjs';

const repo = {
  squash_merge_commit_title: 'PR_TITLE',
  squash_merge_commit_message: 'PR_BODY',
};

function report(pr, repoOverride = repo) {
  return buildSquashMessagePreview({
    repoSlug: 'justsearch-app/justsearch',
    repo: repoOverride,
    pr: {
      number: 123,
      title: 'docs: improve publication guidance',
      url: 'https://github.com/justsearch-app/justsearch/pull/123',
      headRefName: 'codex/example',
      isDraft: false,
      state: 'OPEN',
      mergeStateStatus: 'CLEAN',
      ...pr,
    },
  });
}

function ids(reportValue) {
  return reportValue.warnings.map((warning) => warning.id);
}

{
  const clean = report({
    body: [
      '## Summary',
      '',
      'Clarifies the maintainer publication workflow.',
      '',
      '## Changes',
      '',
      '- Adds a focused maintainer note.',
      '',
      '## Testing',
      '',
      'Verified with the relevant script checks.',
      '',
      '## Related Issues',
      '',
      'None.',
      '',
      'Session-Id: 1568032c-aff9-459c-9afd-7adb22e80473',
    ].join('\n'),
  });
  assert.equal(clean.settings.matchesPrTitleBody, true);
  assert.deepEqual(ids(clean), []);
  assert.match(renderMarkdown(clean), /Squash Message Preview/);
  assert.match(renderText(clean), /preview-squash-message: OK/);
}

{
  const emptyTemplate = report({
    body: ['## Summary', '', '## Changes', '', '## Testing', '', '## Related Issues', ''].join('\n'),
  });
  assert.deepEqual(ids(emptyTemplate), ['empty-template-sections', 'missing-testing-signal', 'missing-session-id-line']);
  assert.match(emptyTemplate.warnings[0].message, /Summary, Changes, Testing, Related Issues/);
}

// --- tempdoc 856 §3: Session-Id trailer (ADVISORY — warning only) ---
{
  const noTrailer = report({ body: '## Testing\n\nVerified locally.' });
  assert(ids(noTrailer).includes('missing-session-id-line'));
  // The suggestion falls back to a placeholder when no session id is supplied.
  const warning = noTrailer.warnings.find((w) => w.id === 'missing-session-id-line');
  assert.match(warning.message, /Session-Id: <session-uuid>/);
}

{
  const withSessionId = buildSquashMessagePreview({
    repoSlug: 'justsearch-app/justsearch',
    repo,
    sessionId: '1568032c-aff9-459c-9afd-7adb22e80473',
    pr: { number: 1, title: 'fix: thing', body: '## Testing\n\nVerified.' },
  });
  const warning = withSessionId.warnings.find((w) => w.id === 'missing-session-id-line');
  assert.match(warning.message, /Session-Id: 1568032c-aff9-459c-9afd-7adb22e80473/);
}

{
  const lastLine = report({
    body: ['## Testing', '', 'Verified locally.', '', 'Session-Id: 1568032c-aff9-459c-9afd-7adb22e80473'].join('\n'),
  });
  assert(!ids(lastLine).includes('missing-session-id-line'));
}

{
  // POSITION DOES NOT MATTER. An earlier revision warned when the line was not
  // in the final paragraph; that premise was refuted — GitHub appends its own
  // `---------` / `Co-authored-by:` paragraph on squash, so a trailing position
  // is not achievable, and merge-links.mjs scans the whole message instead.
  const midBody = report({
    body: [
      'Session-Id: 1568032c-aff9-459c-9afd-7adb22e80473',
      '',
      '## Testing',
      '',
      'Verified locally.',
    ].join('\n'),
  });
  assert(!ids(midBody).includes('missing-session-id-line'));
  assert.deepEqual(ids(midBody).filter((id) => id.includes('session-id')), []);
}

{
  // No space after the colon is accepted, because merge-links.mjs's reader
  // accepts it — the preview imports that predicate rather than owning a second
  // one, so the two cannot disagree.
  const noSpace = report({
    body: ['## Testing', '', 'Verified.', '', 'Session-Id:1568032c-aff9-459c-9afd-7adb22e80473'].join('\n'),
  });
  assert(!ids(noSpace).includes('missing-session-id-line'));
}

{
  // A mid-line mention is not a declaration, and the preview must agree with
  // the reader that this body carries nothing.
  const mention = report({
    body: ['## Testing', '', 'Verified.', '', 'Write Session-Id: <uuid> into the body.'].join('\n'),
  });
  assert(ids(mention).includes('missing-session-id-line'));
}

{
  // Advisory: an empty body already warns via missing-body and must not gain a
  // Session-Id warning on top of it.
  const emptyBody = report({ body: '' });
  assert(!ids(emptyBody).includes('missing-session-id-line'));
}

{
  const generated = report({
    title: 'chore(deps): bump actions/checkout from 6 to 7',
    body: [
      'Bumps [actions/checkout](https://github.com/actions/checkout) from 6 to 7.',
      '<details>',
      '<summary>Release notes</summary>',
      '<blockquote>',
      '<h2>v7.0.0</h2>',
      '<ul>',
      '<li>Generated release note.</li>',
      '</ul>',
      '<!-- raw HTML omitted -->',
      '</blockquote>',
      '</details>',
      'x'.repeat(5100),
    ].join('\n'),
  });
  assert(ids(generated).includes('very-long-body'));
  assert(ids(generated).includes('html-details'));
  assert(ids(generated).includes('html-comment'));
  assert(ids(generated).includes('missing-testing-signal'));
}

{
  const settingsMismatch = report(
    {
      body: '## Testing\n\nVerified locally.',
    },
    {
      squash_merge_commit_title: 'COMMIT_OR_PR_TITLE',
      squash_merge_commit_message: 'COMMIT_MESSAGES',
    }
  );
  assert(ids(settingsMismatch).includes('repo-settings-not-pr-title-body'));
  assert.equal(settingsMismatch.settings.matchesPrTitleBody, false);
}

{
  const missingBody = report({ body: '' });
  assert(ids(missingBody).includes('missing-body'));
}

{
  const releaseNotesDraftWord = report({
    title: 'chore(deps): bump actions/cache from 5 to 6',
    body: [
      'Bumps actions/cache from 5 to 6.',
      '',
      '## Testing',
      '',
      'Verified by public CI.',
      '',
      '<details>',
      '<summary>Release notes</summary>',
      'Later instructions say to draft a new release after publishing.',
      '</details>',
    ].join('\n'),
  });
  assert(!ids(releaseNotesDraftWord).includes('draft-publication-marker'));
}

{
  const generatedWithIncidentalTestingWord = report({
    title: 'chore(deps): bump generated dependency',
    body: [
      'Bumps example from 1 to 2.',
      '<details>',
      '<summary>Release notes</summary>',
      'The upstream project tested a new release process.',
      '</details>',
    ].join('\n'),
  });
  assert(ids(generatedWithIncidentalTestingWord).includes('missing-testing-signal'));
}

{
  const topLevelTestingLabel = report({
    body: ['## Summary', '', 'Small change.', '', 'Testing: verified locally.'].join('\n'),
  });
  assert(!ids(topLevelTestingLabel).includes('missing-testing-signal'));
}

{
  const wipOpening = report({
    title: 'WIP: docs publication preview',
    body: '## Testing\n\nNot ready yet.',
  });
  assert(ids(wipOpening).includes('draft-publication-marker'));
}

{
  const fencedPreview = report({
    title: 'docs: include fenced preview',
    body: ['## Summary', '', '```powershell', 'node scripts/ci/example.mjs', '```', '', '## Testing', '', 'Verified.'].join('\n'),
  });
  const md = renderMarkdown(fencedPreview);
  assert.match(md, /^````markdown$/m);
  assert.match(md, /^````$/m);
  assert.match(md, /```powershell/);
  assert(!ids(fencedPreview).includes('missing-testing-signal'));
}

{
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-squash-preview-'));
  try {
    const repoFile = path.join(dir, 'repo.json');
    const prFile = path.join(dir, 'pr.json');
    fs.writeFileSync(repoFile, `${JSON.stringify(repo)}\n`, 'utf8');
    fs.writeFileSync(
      prFile,
      `${JSON.stringify({
        number: 77,
        title: 'docs: fixture preview',
        body: '## Summary\n\nFixture body.\n\n## Testing\n\nFixture verified.\n\nSession-Id: 1568032c-aff9-459c-9afd-7adb22e80473',
      })}\n`,
      'utf8'
    );
    const script = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'preview-squash-message.mjs');
    const out = execFileSync(process.execPath, [script, '--repo-json', repoFile, '--pr-json', prFile, '--json'], {
      encoding: 'utf8',
      windowsHide: true,
    });
    const cliReport = JSON.parse(out);
    assert.equal(cliReport.pr.number, 77);
    assert.deepEqual(cliReport.warnings, []);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

console.log('test-preview-squash-message: PASS');
