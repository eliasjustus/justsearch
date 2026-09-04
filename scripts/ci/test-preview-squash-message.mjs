#!/usr/bin/env node

import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildSquashMessagePreview, renderMarkdown, renderText } from './preview-squash-message.mjs';

const repo = { squash_merge_commit_title: 'PR_TITLE', squash_merge_commit_message: 'PR_BODY' };
const SESSION = '1568032c-aff9-459c-9afd-7adb22e80473';

function report(pr, repoOverride = repo) {
  return buildSquashMessagePreview({
    repoSlug: 'justsearch-app/justsearch',
    repo: repoOverride,
    pr: {
      number: 123,
      title: 'Improve publication guidance',
      body: `Clarifies why the publication workflow changed.\n\n- Keeps mutable review evidence out of commits.\n\nSession-Id: ${SESSION}`,
      url: 'https://github.com/justsearch-app/justsearch/pull/123',
      headRefName: 'codex/example',
      headRefOid: 'a'.repeat(40),
      isDraft: false,
      state: 'OPEN',
      mergeStateStatus: 'CLEAN',
      author: { login: 'eliasjustus' },
      ...pr,
    },
  });
}

function ids(value) {
  return [...value.errors, ...value.warnings].map((finding) => finding.id);
}

{
  const clean = report({});
  assert.equal(clean.settings.matchesPrTitleBody, true);
  assert.deepEqual(clean.errors, []);
  assert.deepEqual(ids(clean), []);
  assert.equal(clean.proposedCommit.title, 'Improve publication guidance (#123)');
  assert.match(renderMarkdown(clean), /Squash Message Preview/);
  assert.match(renderText(clean), /preview-squash-message: OK/);
}

for (const [body, id] of [
  ['', 'missing-public-body'],
  ['<!-- hidden -->', 'public-html-comment'],
  ['<details>noise</details>', 'public-details'],
  ['- [ ] later', 'public-checklist'],
  ['## Review record\n\nAuthorship: agent', 'public-review-residue'],
  ['Testing: Node tests passed.', 'public-review-residue'],
  ['## Test plan\n\nNode tests passed.', 'public-review-residue'],
  ['## Tests\n\nNode tests passed.', 'public-review-residue'],
  ['## Verification\n\nNode tests passed.', 'public-review-residue'],
  ['Explain why this durable change was needed.', 'public-template-residue'],
  ['Generated with Claude Code', 'public-provider-banner'],
]) {
  assert(ids(report({ body })).includes(id), `${JSON.stringify(body)} should produce ${id}`);
}

{
  const fenced = report({ body: `Durable example.\n\n\`\`\`markdown\n## Testing\n\`\`\`\n\nSession-Id: ${SESSION}` });
  assert(!ids(fenced).includes('public-review-residue'));
}

{
  const mismatch = report({}, { squash_merge_commit_title: 'COMMIT_OR_PR_TITLE', squash_merge_commit_message: 'COMMIT_MESSAGES' });
  assert(ids(mismatch).includes('repo-settings-not-pr-title-body'));
  assert(mismatch.errors.some((error) => error.id === 'repo-settings-not-pr-title-body'));
  assert.equal(mismatch.settings.matchesPrTitleBody, false);
}

{
  const fenced = report({ body: ['Why.', '', '```powershell', 'node scripts/ci/example.mjs', '```', '', `Session-Id: ${SESSION}`].join('\n') });
  const markdown = renderMarkdown(fenced);
  assert.match(markdown, /^````markdown$/m);
  assert.match(markdown, /^````$/m);
}

{
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-squash-preview-'));
  try {
    const repoFile = path.join(dir, 'repo.json');
    const prFile = path.join(dir, 'pr.json');
    fs.writeFileSync(repoFile, `${JSON.stringify(repo)}\n`, 'utf8');
    fs.writeFileSync(prFile, `${JSON.stringify({
      number: 77,
      title: 'Fixture preview',
      body: `Fixture body.\n\nSession-Id: ${SESSION}`,
      headRefOid: 'b'.repeat(40),
      author: { login: 'eliasjustus' },
    })}\n`, 'utf8');
    const script = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'preview-squash-message.mjs');
    const out = execFileSync(process.execPath, [script, '--repo-json', repoFile, '--pr-json', prFile, '--json'], { encoding: 'utf8', windowsHide: true });
    const cliReport = JSON.parse(out);
    assert.equal(cliReport.pr.number, 77);
    assert.deepEqual(cliReport.warnings, []);

    fs.writeFileSync(prFile, `${JSON.stringify({
      number: 77,
      title: 'Fixture preview',
      body: `Fixture body.\n\nTesting: Node tests passed.\n\nSession-Id: ${SESSION}`,
      headRefOid: 'b'.repeat(40),
      author: { login: 'eliasjustus' },
    })}\n`, 'utf8');
    const rejected = spawnSync(process.execPath, [script, '--repo-json', repoFile, '--pr-json', prFile], { encoding: 'utf8', windowsHide: true });
    assert.equal(rejected.status, 1);
    assert.match(rejected.stdout, /preview-squash-message: FAIL/);
    assert.match(rejected.stdout, /public-review-residue/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

console.log('test-preview-squash-message: PASS');
