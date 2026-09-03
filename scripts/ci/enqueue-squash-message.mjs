#!/usr/bin/env node
/**
 * Build and, only with explicit matching locks, submit an asynchronous squash
 * request to GitHub's merge queue. Dry-run is the default.
 */

import { spawnSync } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { checksWait, resolveGhBin } from '../dev/run-gh.mjs';
import {
  ASYNC_MERGE_API_VERSION,
  AsyncMergeProtocolError,
  MERGE_ACTION,
  MERGE_METHOD,
  buildAsyncMergePayload,
  parseGhIncludedResponse,
  pollAsyncMerge,
  requireFreshPendingReceipt,
  responseDiagnostic,
  sha256Utf8,
} from './lib/github-async-merge.mjs';
import { buildSquashMessageProjection } from './lib/squash-message-projection.mjs';
import { loadPublicationSnapshot, splitRepoSlug } from './lib/github-publication-snapshot.mjs';

const MAX_API_CALL_TIMEOUT_MS = 30_000;
const FULL_SHA_RE = /^[0-9a-f]{40}$/i;

function parseArgs(argv) {
  const opts = {
    repo: null, pr: null, prJson: null, execute: false,
    expectedHead: null, expectedBodySha256: null, expectedRequestSha256: null,
    timeoutSec: 600, help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--repo' && argv[i + 1]) opts.repo = argv[++i];
    else if (arg === '--pr' && argv[i + 1]) opts.pr = Number(argv[++i]);
    else if (arg === '--snapshot-json' && argv[i + 1]) opts.prJson = argv[++i];
    else if (arg === '--execute') opts.execute = true;
    else if (arg === '--expected-head' && argv[i + 1]) opts.expectedHead = argv[++i];
    else if (arg === '--expected-body-sha256' && argv[i + 1]) opts.expectedBodySha256 = argv[++i];
    else if (arg === '--expected-request-sha256' && argv[i + 1]) opts.expectedRequestSha256 = argv[++i];
    else if (arg === '--timeout-sec' && argv[i + 1]) opts.timeoutSec = Number(argv[++i]);
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  if (!opts.help && (!opts.repo || !Number.isInteger(opts.pr) || opts.pr <= 0)) {
    throw new Error('Provide --repo owner/repo and a positive --pr N.');
  }
  if (!Number.isFinite(opts.timeoutSec) || opts.timeoutSec <= 0) throw new Error('--timeout-sec must be positive.');
  return opts;
}

function usage() {
  return [
    'Usage: node scripts/ci/enqueue-squash-message.mjs --repo owner/repo --pr N [options]', '',
    'Default: fetch, validate, and print the exact request without changing GitHub.', '',
    'Mutation lock (all required):',
    '  --execute',
    '  --expected-head SHA',
    '  --expected-body-sha256 SHA256',
    '  --expected-request-sha256 SHA256', '',
    'Other options:',
    '  --snapshot-json file  Offline GraphQL-shaped snapshot (dry-run only)',
    '  --timeout-sec N       Receipt polling timeout (default 600)',
    '  -h, --help',
  ].join('\n');
}

export function buildEnqueuePlan({ repoSlug, pr }) {
  splitRepoSlug(repoSlug);
  const projection = buildSquashMessageProjection({ repoSlug, pr });
  const errors = [...projection.errors];
  const add = (id, message) => errors.push({ id, message });
  if (projection.pr.state !== 'OPEN') add('pr-not-open', `PR state is ${JSON.stringify(projection.pr.state)}.`);
  if (projection.pr.isDraft) add('pr-is-draft', 'Draft PRs cannot be published.');
  if (projection.pr.baseRefName !== 'main') add('unexpected-base', `Expected base main; received ${JSON.stringify(projection.pr.baseRefName)}.`);
  if (!projection.source.headSha) add('missing-head-sha', 'GitHub snapshot omitted headRefOid.');
  else if (!FULL_SHA_RE.test(String(projection.source.headSha))) add('malformed-head-sha', 'GitHub headRefOid is not a 40-character hexadecimal object ID.');
  if (projection.pr.isInMergeQueue || projection.pr.mergeQueueEntry) add('already-in-merge-queue', 'PR is already represented in the merge queue.');
  if (projection.pr.autoMergeRequest) add('auto-merge-already-enabled', 'PR already has an auto-merge request.');
  const bodySha256 = sha256Utf8(projection.body);
  const payload = FULL_SHA_RE.test(String(projection.source.headSha ?? '')) && projection.expectedLandedSubject
    ? buildAsyncMergePayload({ subject: projection.expectedLandedSubject, body: projection.body, headSha: projection.source.headSha })
    : null;
  return {
    kind: 'justsearch-async-squash-enqueue-plan.v1',
    repo: repoSlug,
    prNumber: projection.source.prNumber,
    headSha: projection.source.headSha,
    bodySha256,
    requestSha256: payload ? sha256Utf8(JSON.stringify(payload)) : null,
    projection: { ...projection, errors },
    payload,
  };
}

export function runGhApi(args, {
  input = null,
  timeoutMs = MAX_API_CALL_TIMEOUT_MS,
  mutation = false,
  spawn = spawnSync,
  ghBin = resolveGhBin(),
} = {}) {
  const result = spawn(ghBin, args, {
    input,
    encoding: 'utf8',
    stdio: ['pipe', 'pipe', 'pipe'],
    windowsHide: true,
    timeout: timeoutMs,
    killSignal: 'SIGTERM',
  });
  if (result.error || result.signal) {
    const detail = result.error?.message || `process ended with signal ${result.signal}`;
    const consequence = mutation
      ? ' External state is unknown; do not retry this merge request.'
      : '';
    throw new AsyncMergeProtocolError(`gh API process did not complete: ${detail}.${consequence}`);
  }
  try {
    return parseGhIncludedResponse(result.stdout);
  } catch (error) {
    const suffix = result.stderr?.trim() ? ` gh stderr: ${result.stderr.trim()}` : '';
    throw new AsyncMergeProtocolError(`${error.message}${suffix}`);
  }
}

function apiArgs(endpoint, method = 'GET') {
  const args = [
    'api', '--include', '-X', method, endpoint,
    '-H', 'Accept: application/vnd.github+json',
    '-H', `X-GitHub-Api-Version: ${ASYNC_MERGE_API_VERSION}`,
  ];
  if (method === 'PUT') args.push('--input', '-');
  return args;
}

export async function executeEnqueuePlan({
  plan,
  expectedHead,
  expectedBodySha256,
  expectedRequestSha256,
  timeoutMs = 10 * 60_000,
  checksVerified = false,
  request = runGhApi,
}) {
  if (!checksVerified) throw new Error('Required checks must be verified immediately before the publication snapshot.');
  if (plan.projection.errors.length) throw new Error('Projection/preflight errors must be fixed before enqueue.');
  if (expectedHead !== plan.headSha) throw new Error('Expected head lock does not match the fresh GitHub snapshot.');
  if (expectedBodySha256 !== plan.bodySha256) throw new Error('Expected body fingerprint does not match the fresh projection.');
  if (expectedRequestSha256 !== plan.requestSha256) throw new Error('Expected request fingerprint does not match the fresh subject/body/head projection.');
  const endpoint = `repos/${plan.repo}/pulls/${plan.prNumber}/merge-async`;
  const input = Buffer.from(JSON.stringify(plan.payload), 'utf8');
  const submit = request(apiArgs(endpoint, 'PUT'), {
    input,
    timeoutMs: Math.min(timeoutMs, MAX_API_CALL_TIMEOUT_MS),
    mutation: true,
  });
  const submitExpected = { headSha: plan.headSha, mergeMethod: MERGE_METHOD, mergeAction: MERGE_ACTION };
  const receipt = requireFreshPendingReceipt(submit, submitExpected);
  const expected = { ...submitExpected, uuid: receipt.uuid };
  const result = await pollAsyncMerge({
    expected,
    timeoutMs,
    fetchResult: ({ remainingMs }) => request(
      apiArgs(`${endpoint}/${encodeURIComponent(receipt.uuid)}`),
      { timeoutMs: Math.min(remainingMs, MAX_API_CALL_TIMEOUT_MS) },
    ),
  });
  return { receipt, result };
}

export function renderEnqueuePlan(plan) {
  const verdict = plan.projection.errors.length ? 'FAIL' : 'READY';
  const lines = [
    `enqueue-squash-message: ${verdict} (dry-run)`,
    `repo/pr: ${plan.repo}#${plan.prNumber}`,
    `head: ${plan.headSha || '<missing>'}`,
    `body-sha256: ${plan.bodySha256}`,
    `request-sha256: ${plan.requestSha256 || '<missing>'}`,
    `subject: ${plan.projection.expectedLandedSubject || '<missing>'}`,
  ];
  for (const error of plan.projection.errors) lines.push(`- ERROR ${error.id}: ${error.message}`);
  for (const warning of plan.projection.warnings) lines.push(`- WARN ${warning.id}: ${warning.message}`);
  lines.push('', 'exact body:', plan.projection.body || '<empty>');
  if (!plan.projection.errors.length) {
    lines.push('', 'To submit this exact fresh snapshot, rerun with:',
      `  --execute --expected-head ${plan.headSha} --expected-body-sha256 ${plan.bodySha256}`);
    lines[lines.length - 1] += ` --expected-request-sha256 ${plan.requestSha256}`;
  }
  return `${lines.join('\n')}\n`;
}

async function main() {
  try {
    const opts = parseArgs(process.argv.slice(2));
    if (opts.help) return void console.log(usage());
    if (opts.execute && opts.prJson) throw new Error('--snapshot-json is offline-only and cannot be used with --execute.');
    if (opts.execute) {
      const checkStatus = await checksWait(resolveGhBin(), opts.pr, opts.timeoutSec, true);
      if (checkStatus !== 0) throw new Error(`Required-check watcher stopped with status ${checkStatus}; no publication snapshot or merge request was sent.`);
    }
    const pr = loadPublicationSnapshot({ repo: opts.repo, pr: opts.pr, snapshotJson: opts.prJson });
    const plan = buildEnqueuePlan({ repoSlug: opts.repo, pr });
    process.stdout.write(renderEnqueuePlan(plan));
    if (plan.projection.errors.length) {
      process.exitCode = 1;
      return;
    }
    if (!opts.execute) return;
    if (!opts.expectedHead || !opts.expectedBodySha256 || !opts.expectedRequestSha256) {
      throw new Error('--execute requires --expected-head, --expected-body-sha256, and --expected-request-sha256 from the dry-run.');
    }
    const { receipt, result } = await executeEnqueuePlan({
      plan,
      expectedHead: opts.expectedHead,
      expectedBodySha256: opts.expectedBodySha256,
      expectedRequestSha256: opts.expectedRequestSha256,
      timeoutMs: opts.timeoutSec * 1000,
      checksVerified: true,
    });
    process.stdout.write(`accepted: ${JSON.stringify(receipt.diagnostic)}\n`);
    process.stdout.write(`request result: ${JSON.stringify(result.diagnostic)}\n`);
    if (result.state === 'failed') process.exitCode = 1;
    else if (result.state === 'enqueued') {
      process.stdout.write('handoff: request is enqueued; continue with the manual PR/merge-group observer and post-merge equality checks.\n');
    } else if (result.state === 'merged') {
      process.stdout.write('handoff: GitHub reports merged; reconcile the landed commit and verify exact subject/body before declaring success.\n');
    }
  } catch (error) {
    console.error(`enqueue-squash-message: FAIL\n- ${error.message}`);
    if (error instanceof AsyncMergeProtocolError && error.diagnostic) {
      console.error(`- response: ${JSON.stringify(error.diagnostic)}`);
    } else if (error?.response) {
      console.error(`- response: ${JSON.stringify(responseDiagnostic(error.response))}`);
    }
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) await main();
