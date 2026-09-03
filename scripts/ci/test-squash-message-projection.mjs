#!/usr/bin/env node

import assert from 'node:assert/strict';

import { buildSquashMessageProjection, PROJECTION_KIND } from './lib/squash-message-projection.mjs';

const SESSION = '1568032c-aff9-459c-9afd-7adb22e80473';

function validBody({ publicBody = `Why this durable change was needed.\n\n- Adds one outcome.\n\nSession-Id: ${SESSION}`, authorship = 'agent' } = {}) {
  return [
    '<!-- template guidance outside the projection -->',
    '## Public commit', '', publicBody, '',
    '## Review record', '', `Authorship: ${authorship}`, '',
    '### Scope and risk', '', 'Only publication tooling is affected.', '',
    '### Verification evidence', '', 'Node regression tests passed.', '',
    '### Review state', '', 'No unresolved review items.',
  ].join('\n');
}

function project(overrides = {}) {
  return buildSquashMessageProjection({
    repoSlug: 'justsearch-app/justsearch',
    pr: {
      number: 123,
      title: 'fix: preserve public squash record',
      body: validBody(),
      headRefName: 'codex/example',
      headRefOid: 'a'.repeat(40),
      updatedAt: '2026-09-03T12:00:00Z',
      baseRefName: 'main',
      isDraft: false,
      state: 'OPEN',
      mergeStateStatus: 'CLEAN',
      author: { login: 'eliasjustus' },
      viewerMergeHeadlineText: 'fix: preserve public squash record (#123)',
      ...overrides,
    },
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
  assert.equal(result.body, `Why this durable change was needed.\n\n- Adds one outcome.\n\nSession-Id: ${SESSION}`);
}

{
  const publicBody = `First line with trailing spaces.  \n\n- Unicode outcome: café Δ\n\nSession-Id: ${SESSION}`;
  const body = validBody({ publicBody }).replace(/\n/g, '\r\n');
  const result = project({ body });
  assert.equal(result.body, `First line with trailing spaces.  \n\n- Unicode outcome: café Δ\n\nSession-Id: ${SESSION}`);
  assert.deepEqual(result.errors, []);
}

{
  const body = [
    '```markdown', '## Public commit', 'fake', '```', '',
    '> ## Public commit', '',
    '<div>', '## Public commit', '</div>', '',
    validBody(),
  ].join('\n');
  const result = project({ body });
  assert(!ids(result.errors).includes('public-section-cardinality'));
  assert.match(result.body, /^Why this durable change/m);
}

{
  const duplicate = project({ body: `${validBody()}\n\n## Public commit\n\nsecond` });
  assert(ids(duplicate.errors).includes('public-section-cardinality'));
  const reversed = project({ body: validBody().replace('## Public commit', '## TEMP').replace('## Review record', '## Public commit').replace('## TEMP', '## Review record') });
  assert(ids(reversed.errors).includes('section-order'));
}

for (const [snippet, errorId] of [
  ['- [ ] hidden work', 'public-checklist'],
  ['<!-- hidden -->', 'public-html-comment'],
  ['<details>noise</details>', 'public-details'],
  ['WIP: not ready', 'public-process-marker'],
  ['Stack: a -> b', 'public-stack-base-log'],
  ['```base-log\nmain..head\n```', 'public-stack-base-log'],
  ['Generated with Claude Code', 'public-provider-banner'],
]) {
  const result = project({ body: validBody({ publicBody: `${snippet}\n\nSession-Id: ${SESSION}` }) });
  assert(ids(result.errors).includes(errorId), `${snippet} should produce ${errorId}`);
}

{
  const malformed = project({ body: validBody({ publicBody: 'Why.\n\nSession-Id:' }) });
  assert(ids(malformed.errors).includes('malformed-session-id'));
  assert(ids(malformed.errors).includes('missing-session-id'));
  for (const opaque of [
    `Why.\n\n~~~text\nSession-Id: ${SESSION}\n~~~`,
    `Why.\n\n<div>\nSession-Id: ${SESSION}\n</div>`,
    `Why.\n\n> Session-Id: ${SESSION}`,
  ]) {
    const result = project({ body: validBody({ publicBody: opaque }) });
    assert(ids(result.errors).includes('session-id-not-root-content'));
    assert(ids(result.errors).includes('missing-session-id'));
  }
}

{
  const human = project({ body: validBody({ publicBody: 'Why a maintainer changed this.', authorship: 'human' }) });
  assert.deepEqual(human.errors, []);
  const humanWithSession = project({ body: validBody({ authorship: 'human' }) });
  assert(ids(humanWithSession.errors).includes('unexpected-session-id'));
  const bot = project({
    author: { login: 'dependabot[bot]' },
    body: validBody({ publicBody: 'Bumps the durable dependency.', authorship: 'trusted-bot' }),
  });
  assert.deepEqual(bot.errors, []);
  const untrustedBot = project({
    author: { login: 'random-bot[bot]' },
    body: validBody({ publicBody: 'Changes a dependency.', authorship: 'trusted-bot' }),
  });
  assert(ids(untrustedBot.errors).includes('untrusted-bot-actor'));
}

{
  const missingReviewContent = project({ body: validBody().replace('Node regression tests passed.', '') });
  assert(ids(missingReviewContent.errors).includes('empty-review-section'));
  const fencedAuthorship = project({ body: validBody().replace('Authorship: agent', '```text\nAuthorship: agent\n```') });
  assert(ids(fencedAuthorship.errors).includes('authorship-cardinality'));
  const missingSubject = project({ viewerMergeHeadlineText: '' });
  assert(ids(missingSubject.errors).includes('missing-projected-subject'));
  const longSubject = project({ viewerMergeHeadlineText: `${'x'.repeat(73)}` });
  assert(ids(longSubject.errors).includes('subject-too-long'));
  const aboveTarget = project({ title: 'x'.repeat(61) });
  assert(ids(aboveTarget.warnings).includes('title-above-target'));
}

{
  const warning = project({ body: validBody({ publicBody: `Why.\n\nPinned source ${'d'.repeat(40)}.\n\nSession-Id: ${SESSION}` }) });
  assert(ids(warning.warnings).includes('public-raw-sha'));
  const longBody = `${'x'.repeat(1250)}\n\nSession-Id: ${SESSION}`;
  assert(ids(project({ body: validBody({ publicBody: longBody }) }).warnings).includes('public-body-large'));
  const oversized = `${'x'.repeat(2050)}\n\nSession-Id: ${SESSION}`;
  assert(ids(project({ body: validBody({ publicBody: oversized }) }).errors).includes('public-body-too-large'));
}

console.log('test-squash-message-projection: PASS');
