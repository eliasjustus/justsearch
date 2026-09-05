#!/usr/bin/env node

import assert from 'node:assert/strict';

import {
  buildManagedReviewBody,
  buildSquashMessageProjection,
  findManagedReviewComments,
  PROJECTION_KIND,
  sha256,
} from './lib/squash-message-projection.mjs';

const SESSION = '1568032c-aff9-459c-9afd-7adb22e80473';
const HEAD = 'a'.repeat(40);
const PUBLIC_BODY = `Why this durable change was needed.\n\n- Adds one outcome.\n\nSession-Id: ${SESSION}`;

function reviewBody(authorship = 'agent') {
  return [
    '## Review record', '', `Authorship: ${authorship}`, '',
    '### Scope and risk', '', 'Only publication tooling is affected.', '',
    '### Verification evidence', '', 'Node regression tests passed.', '',
    '### Review state', '', 'No unresolved review items.',
  ].join('\n');
}

function pr(overrides = {}) {
  return {
    number: 123,
    title: 'Preserve public squash record',
    body: PUBLIC_BODY,
    head: { sha: HEAD },
    updated_at: '2026-09-03T12:00:00Z',
    user: { login: 'eliasjustus' },
    ...overrides,
  };
}

function comment(pullRequest = pr(), body = reviewBody()) {
  return {
    id: 99,
    html_url: 'https://github.com/justsearch-app/justsearch/pull/123#issuecomment-99',
    user: { login: 'eliasjustus' },
    author_association: 'MEMBER',
    body: buildManagedReviewBody({ pr: pullRequest, reviewBody: body }),
  };
}

function project(prOverrides = {}, commentOverride = undefined) {
  const pullRequest = pr(prOverrides);
  return buildSquashMessageProjection({
    repoSlug: 'justsearch-app/justsearch',
    pr: pullRequest,
    reviewComment: commentOverride === undefined ? comment(pullRequest) : commentOverride,
  });
}

function ids(findings) {
  return findings.map((item) => item.id);
}

{
  const result = project();
  assert.equal(result.kind, PROJECTION_KIND);
  assert.deepEqual(result.errors, []);
  assert.deepEqual(result.warnings, []);
  assert.equal(result.authorship, 'agent');
  assert.deepEqual(result.sessionIds, [SESSION]);
  assert.equal(result.body, PUBLIC_BODY);
  assert.equal(result.publicBodySha256, sha256(PUBLIC_BODY));
}

{
  const unicodeBody = `First line with trailing spaces.  \r\n\r\n- Unicode outcome: café Δ\r\n\r\nSession-Id: ${SESSION}`;
  const pullRequest = pr({ body: unicodeBody });
  const result = buildSquashMessageProjection({ pr: pullRequest, reviewComment: comment(pullRequest) });
  assert.equal(result.body, unicodeBody.replace(/\r\n/g, '\n'));
  assert.deepEqual(result.errors, []);
}

for (const [snippet, errorId] of [
  ['- [ ] hidden work', 'public-checklist'],
  ['<!-- hidden -->', 'public-html-comment'],
  ['<details>noise</details>', 'public-details'],
  ['WIP: not ready', 'public-process-marker'],
  ['Stack: a -> b', 'public-stack-base-log'],
  ['Generated with Claude Code', 'public-provider-banner'],
  ['🤖 Generated with [Claude Code](https://claude.com/claude-code)', 'public-provider-banner'],
  ['Created by Claude Code', 'public-provider-banner'],
  ['Provider details: [automation](https://claude.com/claude-code)', 'public-provider-banner'],
  ['Session: https://claude.ai/code/session/abc123', 'public-provider-banner'],
  ['<div>Generated with Claude Code</div>', 'public-provider-banner'],
  ['<p>Generated with <strong>Claude Code</strong></p>', 'public-provider-banner'],
  ['<div>Generated with Claude&nbsp;Code</div>', 'public-provider-banner'],
  ['<a href="https://claude.com/claude-code">automation</a>', 'public-provider-banner'],
  ['<div>Session: https://claude.ai/code/session/abc123</div>', 'public-provider-banner'],
  ['## Review record\n\nAuthorship: agent', 'public-review-residue'],
  ['## Testing\n\nNode tests passed.', 'public-review-residue'],
  ['## Test plan\n\nNode tests passed.', 'public-review-residue'],
  ['## Tests\n\nNode tests passed.', 'public-review-residue'],
  ['## Verification\n\nNode tests passed.', 'public-review-residue'],
  ['Explain why this durable change was needed.', 'public-template-residue'],
]) {
  const body = `${snippet}\n\nSession-Id: ${SESSION}`;
  const pullRequest = pr({ body });
  const result = buildSquashMessageProjection({ pr: pullRequest, reviewComment: comment(pullRequest) });
  assert(ids(result.errors).includes(errorId), `${snippet} should produce ${errorId}`);
}

for (const opaqueReviewText of [
  '```markdown\n## Testing\n```',
  '> ## Testing',
  '```text\nVerification: Node tests passed.\n```',
]) {
  const body = `Durable example.\n\n${opaqueReviewText}\n\nSession-Id: ${SESSION}`;
  const pullRequest = pr({ body });
  const result = buildSquashMessageProjection({ pr: pullRequest, reviewComment: comment(pullRequest) });
  assert(!ids(result.errors).includes('public-review-residue'), `${JSON.stringify(opaqueReviewText)} is not top-level review structure`);
}

for (const opaqueProviderText of [
  '```markdown\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n```',
  '> 🤖 Generated with [Claude Code](https://claude.com/claude-code)',
  '`Generated with Claude Code` is the legacy footer shape.',
  '<pre>Generated with Claude Code</pre>',
  '<code>Generated with Claude Code</code>',
  '<pre>Generated with Claude Code',
  '<script>Generated with Claude Code',
  '<style>Generated with Claude Code',
  '<template>Generated with Claude Code',
  '<div data-ref="https://claude.com/claude-code">Durable</div>',
]) {
  const body = `Durable example.\n\n${opaqueProviderText}\n\nSession-Id: ${SESSION}`;
  const pullRequest = pr({ body });
  const result = buildSquashMessageProjection({ pr: pullRequest, reviewComment: comment(pullRequest) });
  assert(!ids(result.errors).includes('public-provider-banner'), `${JSON.stringify(opaqueProviderText)} is opaque provider text`);
}

{
  assert(ids(project({}, null).errors).includes('missing-review-record'));
  const pullRequest = pr();
  const current = comment(pullRequest);
  const staleHead = { ...current, body: current.body.replace(HEAD, 'b'.repeat(40)) };
  assert(ids(project({}, staleHead).errors).includes('review-head-stale'));
  const staleBody = { ...current, body: current.body.replace(sha256(PUBLIC_BODY), 'c'.repeat(64)) };
  assert(ids(project({}, staleBody).errors).includes('review-public-body-stale'));
  const untrusted = { ...current, author_association: 'NONE' };
  assert(ids(project({}, untrusted).errors).includes('untrusted-review-owner'));
  const malformed = { ...current, id: null, user: {}, author_association: 'NONE' };
  const malformedIds = ids(project({}, malformed).errors);
  assert(malformedIds.includes('invalid-review-comment-id'));
  assert(malformedIds.includes('missing-review-owner'));
  assert(malformedIds.includes('untrusted-review-owner'));
}

{
  const pullRequest = pr();
  const empty = comment(pullRequest, reviewBody().replace('Node regression tests passed.', ''));
  assert(ids(project({}, empty).errors).includes('empty-review-section'));
  const fenced = comment(pullRequest, reviewBody().replace('Authorship: agent', '```text\nAuthorship: agent\n```'));
  assert(ids(project({}, fenced).errors).includes('authorship-cardinality'));
  const preamble = comment(pullRequest, reviewBody().replace('## Review record', 'Authorship: agent\n\n## Review record').replace('Authorship: agent\n\n### Scope', '### Scope'));
  const preambleErrors = ids(project({}, preamble).errors);
  assert(preambleErrors.includes('unexpected-review-preamble'));
  assert(preambleErrors.includes('authorship-cardinality'));
}

{
  const humanPr = pr({ body: 'Why a maintainer changed this.' });
  assert.deepEqual(buildSquashMessageProjection({ pr: humanPr, reviewComment: comment(humanPr, reviewBody('human')) }).errors, []);
  assert(ids(project({}, comment(pr(), reviewBody('human'))).errors).includes('unexpected-session-id'));
  const botPr = pr({ body: 'Bumps the durable dependency.', user: { login: 'dependabot[bot]' } });
  assert.deepEqual(buildSquashMessageProjection({ pr: botPr, reviewComment: comment(botPr, reviewBody('trusted-bot')) }).errors, []);
  const otherBotPr = pr({ body: 'Changes a dependency.', user: { login: 'random-bot[bot]' } });
  assert(ids(buildSquashMessageProjection({ pr: otherBotPr, reviewComment: comment(otherBotPr, reviewBody('trusted-bot')) }).errors).includes('untrusted-bot-actor'));
}

{
  const first = comment();
  assert.deepEqual(findManagedReviewComments([first, { id: 100, body: 'ordinary comment' }]), [first]);
  assert.equal(findManagedReviewComments([first, { ...first, id: 101 }]).length, 2);
}

{
  const warningBody = `Why.\n\nPinned source ${'d'.repeat(40)}.\n\nSession-Id: ${SESSION}`;
  const pullRequest = pr({ body: warningBody });
  assert(ids(buildSquashMessageProjection({ pr: pullRequest, reviewComment: comment(pullRequest) }).warnings).includes('public-raw-sha'));
  const longBody = `${'x'.repeat(1250)}\n\nSession-Id: ${SESSION}`;
  const longPr = pr({ body: longBody });
  assert(ids(buildSquashMessageProjection({ pr: longPr, reviewComment: comment(longPr) }).warnings).includes('public-body-large'));
}

console.log('test-squash-message-projection: PASS');
