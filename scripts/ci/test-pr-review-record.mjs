#!/usr/bin/env node

import assert from 'node:assert/strict';

import {
  checkReviewSnapshot,
  createGhGateway,
  detectRepoSlug,
  executeReviewRecordPlan,
  planReviewRecordUpsert,
} from './pr-review-record.mjs';

const REPO = 'justsearch-app/justsearch';
const HEAD = 'a'.repeat(40);
const SESSION = '1568032c-aff9-459c-9afd-7adb22e80473';

function reviewBody(extra = 'No unresolved review items.') {
  return [
    '## Review record', '', 'Authorship: agent', '',
    '### Scope and risk', '', 'Only publication tooling is affected.', '',
    '### Verification evidence', '', 'Unicode fixture passed: café Δ.', '',
    '### Review state', '', extra,
  ].join('\n');
}

function pullRequest(overrides = {}) {
  return {
    number: 123,
    title: 'Preserve public squash record',
    body: `Keeps mutable review evidence out of commits.\n\n- Adds one managed review comment.\n\nSession-Id: ${SESSION}`,
    head: { sha: HEAD },
    user: { login: 'eliasjustus' },
    state: 'open',
    merged_at: null,
    ...overrides,
  };
}

function fakeGateway({ comments = [], pr = pullRequest(), actor = { login: 'eliasjustus', repositoryPermissions: { push: true } } } = {}) {
  let nextId = 100;
  const state = { comments: structuredClone(comments), pr: structuredClone(pr), actor, creates: 0, updates: 0 };
  return {
    state,
    getPullRequest: () => structuredClone(state.pr),
    getActor: () => structuredClone(state.actor),
    listComments: () => structuredClone(state.comments),
    createComment: (_prNumber, body) => {
      state.creates += 1;
      const value = { id: nextId++, html_url: `https://example.test/comment/${nextId}`, user: { login: state.actor.login }, author_association: 'MEMBER', body };
      state.comments.push(value);
      return structuredClone(value);
    },
    updateComment: (id, body) => {
      state.updates += 1;
      const value = state.comments.find((item) => item.id === id);
      value.body = body;
      return structuredClone(value);
    },
  };
}

function plan(gateway, body = reviewBody()) {
  return planReviewRecordUpsert({
    repoSlug: REPO,
    pr: gateway.state.pr,
    actor: gateway.state.actor,
    comments: gateway.state.comments,
    reviewBody: body,
  });
}

{
  const gateway = fakeGateway();
  const dryRun = plan(gateway);
  assert.equal(dryRun.action, 'create');
  assert.deepEqual(dryRun.constraints, []);
  assert.deepEqual(dryRun.errors, []);
  assert.equal(gateway.state.creates, 0);
  await assert.rejects(() => executeReviewRecordPlan({ gateway, plan: dryRun, confirm: 'wrong' }), /Confirmation mismatch/);
  assert.equal(gateway.state.creates, 0);
  const result = await executeReviewRecordPlan({ gateway, plan: dryRun, confirm: dryRun.fingerprint });
  assert.equal(result.action, 'verified');
  assert.equal(result.reconciled, false);
  assert.equal(gateway.state.creates, 1);
  assert.deepEqual(checkReviewSnapshot({ repoSlug: REPO, pr: gateway.state.pr, comments: gateway.state.comments }).errors, []);
}

{
  const gateway = fakeGateway({ pr: pullRequest({ state: 'closed', merged_at: '2026-09-03T12:00:00Z' }) });
  assert(plan(gateway).errors.some((item) => item.id === 'pr-not-open'));
  const unchangedTemplate = plan(gateway, [
    '## Review record', '', 'Authorship: agent | human | mixed | trusted-bot', '',
    '### Scope and risk', '', 'Describe the affected surface, important risks, and explicit non-goals.', '',
    '### Verification evidence', '', 'List reproducible checks and results, or `Not run: <honest reason>`.', '',
    '### Review state', '', 'State unresolved findings and decisions, or `No unresolved review items.`',
  ].join('\n'));
  assert(unchangedTemplate.errors.some((item) => item.id === 'review-template-residue'));
}

{
  const outsider = fakeGateway({ actor: { login: 'external-contributor', repositoryPermissions: { pull: true } } });
  const dryRun = plan(outsider);
  assert(dryRun.errors.some((item) => item.id === 'untrusted-actor'));
  await assert.rejects(() => executeReviewRecordPlan({ gateway: outsider, plan: dryRun, confirm: dryRun.fingerprint }), /validation error/);
  assert.equal(outsider.state.creates, 0);
}

{
  const gateway = fakeGateway();
  const created = plan(gateway);
  await executeReviewRecordPlan({ gateway, plan: created, confirm: created.fingerprint });
  const update = plan(gateway, reviewBody('One maintainer review remains.'));
  assert.equal(update.action, 'update');
  assert.equal(update.constraints.length, 1);
  assert.match(update.constraints[0], /sole writer/);
  await executeReviewRecordPlan({ gateway, plan: update, confirm: update.fingerprint });
  assert.equal(gateway.state.updates, 1);
  assert.equal(plan(gateway, reviewBody('One maintainer review remains.')).action, 'noop');
}

{
  const gateway = fakeGateway();
  const created = plan(gateway);
  await executeReviewRecordPlan({ gateway, plan: created, confirm: created.fingerprint });
  gateway.state.comments[0].id = null;
  gateway.state.comments[0].user = {};
  gateway.state.comments[0].author_association = 'NONE';
  const checked = checkReviewSnapshot({ repoSlug: REPO, pr: gateway.state.pr, comments: gateway.state.comments });
  assert(checked.errors.some((item) => item.id === 'invalid-review-comment-id'));
  assert(checked.errors.some((item) => item.id === 'missing-review-owner'));
  assert(checked.errors.some((item) => item.id === 'untrusted-review-owner'));
  const malformedUpdate = plan(gateway, reviewBody('Changed after malformed transport data.'));
  assert.equal(malformedUpdate.commentId, null);
  assert(malformedUpdate.errors.some((item) => item.id === 'invalid-review-comment-id'));
  await assert.rejects(
    () => executeReviewRecordPlan({ gateway, plan: malformedUpdate, confirm: malformedUpdate.fingerprint }),
    /validation error/,
  );
  assert.equal(gateway.state.updates, 0);
}

{
  const gateway = fakeGateway();
  const dryRun = plan(gateway);
  gateway.state.pr.head.sha = 'b'.repeat(40);
  await assert.rejects(
    () => executeReviewRecordPlan({ gateway, plan: dryRun, confirm: dryRun.fingerprint }),
    /No mutation was attempted/,
  );
  assert.equal(gateway.state.creates, 0);
}

{
  const gateway = fakeGateway();
  const dryRun = plan(gateway);
  gateway.state.pr.body = `${gateway.state.pr.body}\n\nChanged after dry-run.`;
  await assert.rejects(() => executeReviewRecordPlan({ gateway, plan: dryRun, confirm: dryRun.fingerprint }), /No mutation was attempted/);
  assert.equal(gateway.state.creates, 0);
}

{
  const gateway = fakeGateway();
  const created = plan(gateway);
  await executeReviewRecordPlan({ gateway, plan: created, confirm: created.fingerprint });
  gateway.state.comments[0].user.login = 'other-maintainer';
  assert(plan(gateway).errors.some((item) => item.id === 'review-record-owner-mismatch'));
  gateway.state.comments.push({ ...structuredClone(gateway.state.comments[0]), id: 500 });
  assert(plan(gateway).errors.some((item) => item.id === 'review-record-cardinality'));
}

{
  const gateway = fakeGateway();
  const dryRun = plan(gateway);
  const originalCreate = gateway.createComment;
  gateway.createComment = (...args) => {
    originalCreate(...args);
    throw new Error('simulated timeout');
  };
  const result = await executeReviewRecordPlan({ gateway, plan: dryRun, confirm: dryRun.fingerprint });
  assert.equal(result.reconciled, true);
  assert.equal(gateway.state.creates, 1);
}

{
  const gateway = fakeGateway();
  const created = plan(gateway);
  await executeReviewRecordPlan({ gateway, plan: created, confirm: created.fingerprint });
  const update = plan(gateway, reviewBody('Updated review state.'));
  const originalUpdate = gateway.updateComment;
  gateway.updateComment = (...args) => {
    originalUpdate(...args);
    throw new Error('simulated update timeout');
  };
  const result = await executeReviewRecordPlan({ gateway, plan: update, confirm: update.fingerprint });
  assert.equal(result.reconciled, true);
  assert.equal(gateway.state.updates, 1);
}

{
  const gateway = fakeGateway();
  const dryRun = plan(gateway);
  gateway.createComment = () => {
    gateway.state.creates += 1;
    throw new Error('simulated timeout before mutation');
  };
  await assert.rejects(
    () => executeReviewRecordPlan({ gateway, plan: dryRun, confirm: dryRun.fingerprint }),
    /No retry was attempted/,
  );
  assert.equal(gateway.state.creates, 1);
}

{
  const gateway = fakeGateway();
  const dryRun = plan(gateway);
  const originalList = gateway.listComments;
  let lists = 0;
  gateway.listComments = () => {
    lists += 1;
    if (lists >= 2) throw new Error('read-back unavailable');
    return originalList();
  };
  await assert.rejects(
    () => executeReviewRecordPlan({ gateway, plan: dryRun, confirm: dryRun.fingerprint }),
    /reported mutation success.*Final state is unknown.*No retry was attempted/,
  );
  assert.equal(gateway.state.creates, 1);
}

{
  let captured = null;
  const run = (_bin, args, options) => {
    captured = { args, options };
    return { status: 0, stdout: JSON.stringify({ id: 1, body: 'ok' }), stderr: '' };
  };
  const gateway = createGhGateway({ repoSlug: REPO, ghBin: 'gh-test', run });
  gateway.createComment(123, 'Unicode: café Δ');
  assert.deepEqual(captured.args, ['api', '--method', 'POST', `repos/${REPO}/issues/123/comments`, '--input', '-']);
  assert(Buffer.isBuffer(captured.options.input));
  assert.equal(JSON.parse(captured.options.input.toString('utf8')).body, 'Unicode: café Δ');
  assert.equal(captured.options.timeout, 30_000);
}

{
  assert.throws(
    () => detectRepoSlug(() => ({ status: 1, stdout: '', stderr: 'no origin' })),
    /pass --repo owner\/repo explicitly/,
  );
}

{
  const gateway = createGhGateway({
    repoSlug: REPO,
    ghBin: 'gh-test',
    run: () => ({ status: 0, stdout: '{not-json', stderr: '' }),
  });
  assert.throws(() => gateway.getActor(), /malformed JSON/);
}

{
  const comments = [{ id: 1 }, { id: 2 }];
  let paginationArgs = null;
  const paged = createGhGateway({
    repoSlug: REPO,
    ghBin: 'gh-test',
    run: (_bin, args) => {
      paginationArgs = args;
      return { status: 0, stdout: JSON.stringify([[comments[0]], [comments[1]]]), stderr: '' };
    },
  });
  assert.deepEqual(paged.listComments(123), comments);
  assert.deepEqual(paginationArgs, ['api', '--paginate', '--slurp', `repos/${REPO}/issues/123/comments?per_page=100`]);
  const malformed = createGhGateway({
    repoSlug: REPO,
    ghBin: 'gh-test',
    run: () => ({ status: 0, stdout: JSON.stringify([comments[0]]), stderr: '' }),
  });
  assert.throws(() => malformed.listComments(123), /unexpected shape/);
}

{
  const calls = [];
  const gateway = createGhGateway({
    repoSlug: REPO,
    ghBin: 'gh-test',
    run: (_bin, args) => {
      calls.push(args);
      const value = args[1] === 'user'
        ? { login: 'maintainer' }
        : { permissions: { push: true } };
      return { status: 0, stdout: JSON.stringify(value), stderr: '' };
    },
  });
  assert.deepEqual(gateway.getActor(), { login: 'maintainer', repositoryPermissions: { push: true } });
  assert.deepEqual(calls, [
    ['api', 'user'],
    ['api', `repos/${REPO}`],
  ]);
}

console.log('test-pr-review-record: PASS');
