#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  AsyncMergeProtocolError,
  buildAsyncMergePayload,
  classifyAsyncResult,
  parseGhIncludedResponse,
  pollAsyncMerge,
  requireFreshPendingReceipt,
  responseDiagnostic,
  sha256Utf8,
} from './lib/github-async-merge.mjs';
import { buildEnqueuePlan, executeEnqueuePlan, renderEnqueuePlan, runGhApi } from './enqueue-squash-message.mjs';

const HEAD = 'a'.repeat(40);
const UUID = '630b9d5e-3f2a-4f7e-8b0c-2d5f9a8c1e42';
const expected = { headSha: HEAD, mergeMethod: 'squash', mergeAction: 'merge_queue' };
const SESSION = '1568032c-aff9-459c-9afd-7adb22e80473';

function included(status, json, { protocol = '2', eol = '\r\n', headers = [] } = {}) {
  return `HTTP/${protocol} ${status} Example${eol}content-type: application/json${eol}${headers.join(eol)}${headers.length ? eol : ''}${eol}${JSON.stringify(json)}`;
}

function response(status, json, options) {
  return parseGhIncludedResponse(included(status, json, options));
}

function pending(overrides = {}) {
  return {
    status: 'pending',
    details: {
      message: 'Merge request is in progress.',
      uuid: UUID,
      merge_method: 'squash',
      merge_action: 'merge_queue',
      expected_head_sha: HEAD,
      ...overrides,
    },
  };
}

function snapshot(overrides = {}) {
  return {
    number: 77,
    title: 'fix: preserve exact queue body',
    body: [
      '## Public commit', '',
      'Preserves café and Δ exactly.', '',
      `Session-Id: ${SESSION}`, '',
      '## Review record', '',
      'Authorship: agent', '',
      '### Scope and risk', '',
      'Transport code only. REVIEW-ONLY-SENTINEL', '',
      '### Verification evidence', '',
      'Offline fixtures passed.', '',
      '### Review state', '',
      'No unresolved review items.',
    ].join('\n'),
    url: 'https://github.com/justsearch-app/justsearch/pull/77',
    headRefName: 'codex/queue',
    headRefOid: HEAD,
    updatedAt: '2026-09-03T12:00:00Z',
    baseRefName: 'main',
    isDraft: false,
    state: 'OPEN',
    mergeStateStatus: 'CLEAN',
    isInMergeQueue: false,
    mergeQueueEntry: null,
    autoMergeRequest: null,
    author: { login: 'eliasjustus' },
    viewerMergeHeadlineText: 'fix: preserve exact queue body (#77)',
    ...overrides,
  };
}

{
  const parsed = response(202, pending(), { protocol: '1.1', headers: ['x-test: one', 'x-test: two'] });
  assert.equal(parsed.statusCode, 202);
  assert.deepEqual(parsed.headers['x-test'], ['one', 'two']);
  assert.equal(parsed.json.details.uuid, UUID);
  const lf = response(409, pending(), { eol: '\n' });
  assert.equal(lf.statusCode, 409);
  assert.equal(response(200, { status: 'merged' }, { protocol: '2.0' }).statusCode, 200);
  const redirected = parseGhIncludedResponse(`${included(301, { message: 'redirect' })}\n${included(202, pending())}`);
  assert.equal(redirected.statusCode, 202);
  assert.throws(() => parseGhIncludedResponse('not an HTTP response'), AsyncMergeProtocolError);
  assert.throws(() => parseGhIncludedResponse('HTTP/2 200 OK\r\nbad-header\r\n\r\n{}'), /malformed header/);
  assert.throws(() => parseGhIncludedResponse('HTTP/2 200 OK\ncontent-type: application\/json\n\n{'), /not valid JSON/);
}

{
  const receipt = requireFreshPendingReceipt(response(202, pending()), expected);
  assert.equal(receipt.uuid, UUID);
  for (const status of [200, 409, 400, 403, 404, 422]) {
    assert.throws(() => requireFreshPendingReceipt(response(status, pending()), expected), /fresh 202/);
  }
  assert.throws(() => requireFreshPendingReceipt(response(202, pending({ uuid: undefined })), expected), /uuid/);
  assert.throws(() => requireFreshPendingReceipt(response(202, pending({ uuid: 'not-a-uuid' })), expected), /malformed UUID/);
  assert.throws(() => requireFreshPendingReceipt(response(202, pending({ merge_method: 'merge' })), expected), /method mismatch/);
  assert.throws(() => requireFreshPendingReceipt(response(202, pending({ merge_action: 'direct_merge' })), expected), /action mismatch/);
  assert.throws(() => requireFreshPendingReceipt(response(202, pending({ expected_head_sha: 'b'.repeat(40) })), expected), /head mismatch/);
}

{
  assert.equal(classifyAsyncResult(response(200, { status: 'enqueued', details: { message: 'queued' } }), expected).state, 'enqueued');
  assert.equal(classifyAsyncResult(response(200, { status: 'merged', details: { sha: 'c'.repeat(40) } }), expected).state, 'merged');
  assert.equal(classifyAsyncResult(response(200, { status: 'failed', details: { message: 'rules failed' } }), expected).state, 'failed');
  assert.throws(() => classifyAsyncResult(response(200, { status: 'enqueued', details: { merge_action: 'direct_merge' } }), expected), /action mismatch/);
  assert.throws(() => classifyAsyncResult(response(200, pending({ uuid: '730b9d5e-3f2a-4f7e-8b0c-2d5f9a8c1e42' })), { ...expected, uuid: UUID }), /UUID mismatch/);
  assert.throws(() => classifyAsyncResult(response(200, { status: 'mystery' }), expected), /Unknown/);
  const mergedDiagnostic = responseDiagnostic(response(200, { status: 'merged', details: { sha: 'c'.repeat(40) } }));
  assert.equal(mergedDiagnostic.expectedHeadSha, null);
  assert.equal(mergedDiagnostic.mergeCommitSha, 'c'.repeat(40));
}

{
  const queue = [response(200, pending()), response(200, { status: 'enqueued', details: { message: 'queued' } })];
  const result = await pollAsyncMerge({ fetchResult: async () => queue.shift(), expected, sleep: async () => {} });
  assert.equal(result.state, 'enqueued');
  const failed = await pollAsyncMerge({
    fetchResult: async () => response(200, { status: 'failed', details: { message: 'no' } }), expected,
  });
  assert.equal(failed.state, 'failed');
  let clock = 0;
  await assert.rejects(() => pollAsyncMerge({
    fetchResult: async () => response(200, pending()), expected,
    timeoutMs: 10,
    now: () => clock,
    sleep: async () => { clock = 11; },
  }), /Timed out/);
}

{
  const payload = buildAsyncMergePayload({ subject: 'fix: café Δ (#77)', body: 'why café Δ', headSha: HEAD });
  const bytes = Buffer.from(JSON.stringify(payload), 'utf8');
  assert.equal(JSON.parse(bytes.toString('utf8')).commit_message, 'why café Δ');
  assert.equal(payload.merge_method, 'squash');
  assert.equal(payload.merge_action, 'merge_queue');
}

{
  const plan = buildEnqueuePlan({ repoSlug: 'justsearch-app/justsearch', pr: snapshot() });
  assert.deepEqual(plan.projection.errors, []);
  assert.equal(plan.bodySha256, sha256Utf8(plan.projection.body));
  assert(!JSON.stringify(plan.payload).includes('REVIEW-ONLY-SENTINEL'));
  assert(!JSON.stringify(plan).includes('REVIEW-ONLY-SENTINEL'));
  assert(!renderEnqueuePlan(plan).includes('REVIEW-ONLY-SENTINEL'));
  for (const overrides of [
    { isInMergeQueue: true },
    { mergeQueueEntry: { position: 1 } },
    { autoMergeRequest: { enabledAt: 'now' } },
    { headRefOid: null },
    { headRefOid: 'not-a-sha' },
    { baseRefName: 'release' },
    { isDraft: true },
  ]) {
    assert(buildEnqueuePlan({ repoSlug: 'justsearch-app/justsearch', pr: snapshot(overrides) }).projection.errors.length > 0);
  }
}

{
  const plan = buildEnqueuePlan({ repoSlug: 'justsearch-app/justsearch', pr: snapshot() });
  const calls = [];
  const request = (args, options = {}) => {
    calls.push({ args, input: options.input });
    return calls.length === 1
      ? response(202, pending())
      : response(200, { status: 'enqueued', details: { message: 'queued' } });
  };
  const result = await executeEnqueuePlan({
    plan,
    expectedHead: HEAD,
    expectedBodySha256: plan.bodySha256,
    expectedRequestSha256: plan.requestSha256,
    timeoutMs: 1000,
    checksVerified: true,
    request,
  });
  assert.equal(result.result.state, 'enqueued');
  assert(Buffer.isBuffer(calls[0].input));
  const sent = JSON.parse(calls[0].input.toString('utf8'));
  assert.equal(sent.commit_message, plan.projection.body);
  assert(!sent.commit_message.includes('REVIEW-ONLY-SENTINEL'));
  assert(calls[0].args.includes('--input'));
  assert(calls[0].args.includes('PUT'));
  assert(calls[0].args.includes('repos/justsearch-app/justsearch/pulls/77/merge-async'));
  assert(calls[0].args.includes('X-GitHub-Api-Version: 2026-03-10'));
  assert(!calls[0].args.includes(sent.commit_message));
  await assert.rejects(() => executeEnqueuePlan({ plan, expectedHead: HEAD, expectedBodySha256: plan.bodySha256, expectedRequestSha256: plan.requestSha256, request }), /Required checks/);
  await assert.rejects(() => executeEnqueuePlan({ plan, expectedHead: 'b'.repeat(40), expectedBodySha256: plan.bodySha256, expectedRequestSha256: plan.requestSha256, checksVerified: true, request }), /head lock/);
  await assert.rejects(() => executeEnqueuePlan({ plan, expectedHead: HEAD, expectedBodySha256: '0'.repeat(64), expectedRequestSha256: plan.requestSha256, checksVerified: true, request }), /body fingerprint/);
  await assert.rejects(() => executeEnqueuePlan({ plan, expectedHead: HEAD, expectedBodySha256: plan.bodySha256, expectedRequestSha256: '0'.repeat(64), checksVerified: true, request }), /request fingerprint/);

  const titleChanged = buildEnqueuePlan({
    repoSlug: 'justsearch-app/justsearch',
    pr: snapshot({ viewerMergeHeadlineText: 'fix: changed after preview (#77)' }),
  });
  assert.equal(titleChanged.headSha, plan.headSha);
  assert.equal(titleChanged.bodySha256, plan.bodySha256);
  assert.notEqual(titleChanged.requestSha256, plan.requestSha256);

  for (const status of [400, 403, 404, 409, 422]) {
    let failureCalls = 0;
    await assert.rejects(() => executeEnqueuePlan({
      plan,
      expectedHead: HEAD,
      expectedBodySha256: plan.bodySha256,
      expectedRequestSha256: plan.requestSha256,
      timeoutMs: 1000,
      checksVerified: true,
      request: () => {
        failureCalls += 1;
        return response(status, pending());
      },
    }), /fresh 202/);
    assert.equal(failureCalls, 1, `${status} must not poll or retry`);
  }
}

{
  let captured = null;
  const parsed = runGhApi(['api', '--include', '-X', 'PUT', 'repos/o/r/pulls/1/merge-async', '--input', '-'], {
    input: Buffer.from('{"commit_message":"café Δ"}', 'utf8'),
    timeoutMs: 1234,
    mutation: true,
    ghBin: 'fake-gh',
    spawn: (bin, args, options) => {
      captured = { bin, args, options };
      return { status: 0, signal: null, stdout: included(202, pending()), stderr: '' };
    },
  });
  assert.equal(parsed.statusCode, 202);
  assert.equal(captured.bin, 'fake-gh');
  assert.equal(captured.options.timeout, 1234);
  assert.equal(captured.options.input.toString('utf8'), '{"commit_message":"café Δ"}');
  assert(!captured.args.includes('{"commit_message":"café Δ"}'));
  assert.throws(() => runGhApi(['api'], {
    mutation: true,
    ghBin: 'fake-gh',
    spawn: () => ({ status: null, signal: 'SIGTERM', stdout: '', stderr: '' }),
  }), /External state is unknown; do not retry/);
  assert.throws(() => runGhApi(['api'], {
    ghBin: 'fake-gh',
    spawn: () => ({ status: null, signal: null, error: Object.assign(new Error('timed out'), { code: 'ETIMEDOUT' }), stdout: '', stderr: '' }),
  }), /timed out/);
}

{
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-enqueue-'));
  try {
    const file = path.join(dir, 'snapshot.json');
    fs.writeFileSync(file, `${JSON.stringify(snapshot())}\n`, 'utf8');
    const script = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'enqueue-squash-message.mjs');
    const run = spawnSync(process.execPath, [script, '--repo', 'justsearch-app/justsearch', '--pr', '77', '--snapshot-json', file], {
      encoding: 'utf8', windowsHide: true,
    });
    assert.equal(run.status, 0, run.stderr);
    assert.match(run.stdout, /READY \(dry-run\)/);
    assert(!run.stdout.includes('REVIEW-ONLY-SENTINEL'));
    const blocked = spawnSync(process.execPath, [script, '--repo', 'justsearch-app/justsearch', '--pr', '77', '--snapshot-json', file, '--execute'], {
      encoding: 'utf8', windowsHide: true,
    });
    assert.notEqual(blocked.status, 0);
    assert.match(blocked.stderr, /offline-only/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

console.log('test-enqueue-squash-message: PASS');
