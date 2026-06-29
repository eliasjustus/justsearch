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
    repoSlug: 'eliasjustus/justsearch',
    repo: repoOverride,
    pr: {
      number: 123,
      title: 'docs: improve publication guidance',
      url: 'https://github.com/eliasjustus/justsearch/pull/123',
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
  assert.deepEqual(ids(emptyTemplate), ['empty-template-sections', 'missing-testing-signal']);
  assert.match(emptyTemplate.warnings[0].message, /Summary, Changes, Testing, Related Issues/);
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
        body: '## Summary\n\nFixture body.\n\n## Testing\n\nFixture verified.',
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
