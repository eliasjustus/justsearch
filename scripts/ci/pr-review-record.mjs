#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { resolveGhBin } from '../dev/run-gh.mjs';
import {
  buildManagedReviewBody,
  buildPublicSquashRecord,
  buildSquashMessageProjection,
  findManagedReviewComments,
  normalizeLf,
  sha256,
} from './lib/squash-message-projection.mjs';

const GH_TIMEOUT_MS = 30_000;

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

export function operationFingerprint(value) {
  return sha256(stableJson(value));
}

export function detectRepoSlug(execFile = spawnSync) {
  const result = execFile('git', ['remote', 'get-url', 'origin'], { encoding: 'utf8', windowsHide: true });
  if (result.error || result.status !== 0) throw new Error('Cannot resolve the GitHub repository from origin; pass --repo owner/repo explicitly.');
  const match = String(result.stdout).trim().match(/github\.com[:/](.+?)\/(.+?)(?:\.git)?$/i);
  if (!match) throw new Error('Origin is not a recognizable GitHub repository; pass --repo owner/repo explicitly.');
  return `${match[1]}/${match[2]}`;
}

function ghFailure(result, args) {
  const detail = String(result.stderr || result.error?.message || `exit ${result.status}`).trim();
  const error = new Error(`gh ${args.slice(0, 3).join(' ')} failed: ${detail}`);
  error.ambiguousMutation = Boolean(result.error || result.signal);
  return error;
}

export function createGhGateway({ repoSlug, ghBin = resolveGhBin(), run = spawnSync, timeoutMs = GH_TIMEOUT_MS }) {
  function request(args, input = null) {
    const result = run(ghBin, args, {
      encoding: 'utf8',
      input: input == null ? undefined : Buffer.from(JSON.stringify(input), 'utf8'),
      maxBuffer: 8 * 1024 * 1024,
      timeout: timeoutMs,
      windowsHide: true,
    });
    if (result.error || result.status !== 0) throw ghFailure(result, args);
    try {
      return JSON.parse(String(result.stdout));
    } catch (error) {
      throw new Error(`gh ${args.slice(0, 3).join(' ')} returned malformed JSON: ${error.message}`);
    }
  }

  return {
    getPullRequest(prNumber) {
      return request(['api', `repos/${repoSlug}/pulls/${prNumber}`]);
    },
    getActor() {
      const actor = request(['api', 'user']);
      const repository = request(['api', `repos/${repoSlug}`]);
      return { ...actor, repositoryPermissions: repository?.permissions ?? null };
    },
    listComments(prNumber) {
      const pages = request(['api', '--paginate', '--slurp', `repos/${repoSlug}/issues/${prNumber}/comments?per_page=100`]);
      if (!Array.isArray(pages) || pages.some((page) => !Array.isArray(page))) throw new Error('GitHub comment pagination returned an unexpected shape.');
      return pages.flat();
    },
    createComment(prNumber, body) {
      return request(['api', '--method', 'POST', `repos/${repoSlug}/issues/${prNumber}/comments`, '--input', '-'], { body });
    },
    updateComment(commentId, body) {
      return request(['api', '--method', 'PATCH', `repos/${repoSlug}/issues/comments/${commentId}`, '--input', '-'], { body });
    },
  };
}

export async function loadReviewSnapshot({ gateway, prNumber, includeActor = true }) {
  const [pr, comments] = await Promise.all([
    gateway.getPullRequest(prNumber),
    gateway.listComments(prNumber),
  ]);
  const actor = includeActor ? await gateway.getActor() : null;
  return { pr, actor, comments };
}

function duplicateFinding(count) {
  return { id: 'review-record-cardinality', message: `Expected at most one managed review-record comment; found ${count}. Refusing to guess ownership.` };
}

export function checkReviewSnapshot({ repoSlug, pr, comments }) {
  const managed = findManagedReviewComments(comments);
  if (managed.length !== 1) {
    const projection = buildPublicSquashRecord({ repoSlug, pr });
    return {
      projection,
      managed,
      errors: [...projection.errors, { id: 'review-record-cardinality', message: `Expected exactly one managed review-record comment; found ${managed.length}.` }],
    };
  }
  const projection = buildSquashMessageProjection({ repoSlug, pr, reviewComment: managed[0] });
  return { projection, managed, errors: projection.errors };
}

export function planReviewRecordUpsert({ repoSlug, pr, actor, comments, reviewBody }) {
  const managed = findManagedReviewComments(comments);
  const actorLogin = actor?.login ?? null;
  const desiredBody = buildManagedReviewBody({ pr, reviewBody });
  const errors = [];
  if (String(pr?.state).toLowerCase() !== 'open' || pr?.merged_at) {
    errors.push({ id: 'pr-not-open', message: 'Managed review records can be created or updated only while the pull request is open.' });
  }
  if (!actorLogin) errors.push({ id: 'missing-actor', message: 'GitHub did not identify the authenticated actor.' });
  const permissions = actor?.repositoryPermissions ?? {};
  const canManage = Boolean(permissions.admin || permissions.maintain || permissions.push);
  if (!canManage) errors.push({ id: 'untrusted-actor', message: 'Authenticated actor needs admin, maintain, or push permission before creating or updating the managed review record.' });
  if (managed.length > 1) errors.push(duplicateFinding(managed.length));
  const existing = managed.length === 1 ? managed[0] : null;
  if (existing && existing.user?.login !== actorLogin) {
    errors.push({ id: 'review-record-owner-mismatch', message: `Managed review record belongs to ${existing.user?.login ?? 'unknown'}, not authenticated actor ${actorLogin}.` });
  }
  const candidate = existing ? { ...existing, body: desiredBody } : {
    id: null,
    html_url: null,
    user: { login: actorLogin },
    author_association: null,
    body: desiredBody,
  };
  const projection = buildSquashMessageProjection({ repoSlug, pr, reviewComment: candidate });
  errors.push(...projection.errors);
  const action = existing ? (normalizeLf(existing.body) === desiredBody ? 'noop' : 'update') : 'create';
  const lock = {
    kind: 'justsearch-review-record-upsert.v1',
    action,
    repo: repoSlug,
    prNumber: Number(pr.number),
    headSha: pr?.head?.sha ?? pr?.headSha ?? pr?.headRefOid ?? null,
    publicBodySha256: sha256(pr.body),
    actorLogin,
    actorPermission: permissions.admin ? 'ADMIN' : permissions.maintain ? 'MAINTAIN' : permissions.push ? 'WRITE' : 'INSUFFICIENT',
    commentId: existing?.id ?? null,
    previousBodySha256: existing ? sha256(existing.body) : null,
    desiredBodySha256: sha256(desiredBody),
    updateTransport: action === 'update' ? 'unconditional-rest-patch' : action,
  };
  const constraints = action === 'update' ? [
    'GitHub comment PATCH has no compare-and-swap precondition. The authenticated comment owner must be the sole writer from dry-run through exact read-back.',
  ] : [];
  return {
    kind: lock.kind,
    action,
    repo: repoSlug,
    prNumber: lock.prNumber,
    actorLogin,
    commentId: lock.commentId,
    commentUrl: existing?.html_url ?? null,
    desiredBody,
    desiredBodySha256: lock.desiredBodySha256,
    publicBodySha256: lock.publicBodySha256,
    headSha: lock.headSha,
    fingerprint: operationFingerprint(lock),
    constraints,
    projection,
    warnings: projection.warnings,
    errors,
  };
}

function exactManagedComment({ comments, plan }) {
  const managed = findManagedReviewComments(comments);
  return managed.length === 1
    && managed[0].user?.login === plan.actorLogin
    && normalizeLf(managed[0].body) === plan.desiredBody
    ? managed[0]
    : null;
}

function reviewSourceFromDesired(desiredBody) {
  return desiredBody.split('\n').slice(1).join('\n');
}

async function verifyAfterMutation({ gateway, plan, reconciled }) {
  const snapshot = await loadReviewSnapshot({ gateway, prNumber: plan.prNumber });
  const comment = exactManagedComment({ comments: snapshot.comments, plan });
  const fresh = planReviewRecordUpsert({
    repoSlug: plan.repo,
    pr: snapshot.pr,
    actor: snapshot.actor,
    comments: snapshot.comments,
    reviewBody: reviewSourceFromDesired(plan.desiredBody),
  });
  if (!comment || fresh.errors.length > 0 || fresh.headSha !== plan.headSha || fresh.publicBodySha256 !== plan.publicBodySha256) {
    throw new Error('Mutation outcome could not be verified against the locked PR head/body and exact managed comment. Do not retry blindly; inspect the PR conversation.');
  }
  return { ...plan, action: 'verified', reconciled, commentId: comment.id, commentUrl: comment.html_url };
}

export async function executeReviewRecordPlan({ gateway, plan, confirm }) {
  if (plan.errors.length > 0) throw new Error(`Review-record plan has ${plan.errors.length} validation error(s).`);
  if (plan.action === 'noop') return { ...plan, action: 'verified', reconciled: false };
  if (confirm !== plan.fingerprint) throw new Error(`Confirmation mismatch. Re-run the dry-run and pass --confirm ${plan.fingerprint}.`);
  const freshSnapshot = await loadReviewSnapshot({ gateway, prNumber: plan.prNumber });
  const freshPlan = planReviewRecordUpsert({
    repoSlug: plan.repo,
    pr: freshSnapshot.pr,
    actor: freshSnapshot.actor,
    comments: freshSnapshot.comments,
    reviewBody: reviewSourceFromDesired(plan.desiredBody),
  });
  if (freshPlan.errors.length > 0 || freshPlan.fingerprint !== confirm) {
    throw new Error('PR head, public body, review record, or actor changed after dry-run. No mutation was attempted; produce a fresh plan.');
  }
  try {
    if (freshPlan.action === 'create') await gateway.createComment(freshPlan.prNumber, freshPlan.desiredBody);
    else await gateway.updateComment(freshPlan.commentId, freshPlan.desiredBody);
  } catch (error) {
    try {
      return await verifyAfterMutation({ gateway, plan: freshPlan, reconciled: true });
    } catch {
      throw new Error(`${error.message} Mutation completion is unknown and exact read-back did not reconcile it. No retry was attempted.`);
    }
  }
  try {
    return await verifyAfterMutation({ gateway, plan: freshPlan, reconciled: false });
  } catch (error) {
    throw new Error(`GitHub reported mutation success, but exact read-back failed: ${error.message} Final state is unknown. No retry was attempted.`);
  }
}

function parseArgs(argv) {
  const opts = { command: argv[0], pr: null, repo: null, file: null, execute: false, confirm: null, json: false, help: false };
  for (let i = 1; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--pr' && argv[i + 1]) opts.pr = Number(argv[++i]);
    else if (arg === '--repo' && argv[i + 1]) opts.repo = argv[++i];
    else if (arg === '--file' && argv[i + 1]) opts.file = argv[++i];
    else if (arg === '--confirm' && argv[i + 1]) opts.confirm = argv[++i];
    else if (arg === '--execute') opts.execute = true;
    else if (arg === '--json') opts.json = true;
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  return opts;
}

function usage() {
  return [
    'Usage:',
    '  node scripts/ci/pr-review-record.mjs check --pr N [--repo owner/repo] [--json]',
    '  node scripts/ci/pr-review-record.mjs upsert --pr N --file PATH [--repo owner/repo] [--json]',
    '  node scripts/ci/pr-review-record.mjs upsert --pr N --file PATH --execute --confirm SHA256',
    '',
    'Upsert is dry-run by default. Copy the fresh fingerprint into --confirm to authorize',
    'one exact create/update. The review file starts at `## Review record`; the command',
    'owns the hidden PR/head/public-body marker.',
  ].join('\n');
}

function printable(result) {
  const { desiredBody: _desiredBody, projection: _projection, ...safe } = result;
  return safe;
}

function render(result) {
  const lines = [`pr-review-record: ${result.errors?.length ? 'FAIL' : 'OK'}`];
  if (result.action) lines.push(`action: ${result.action}`);
  if (result.fingerprint) lines.push(`confirm: ${result.fingerprint}`);
  if (result.commentUrl) lines.push(`comment: ${result.commentUrl}`);
  for (const constraint of result.constraints ?? []) lines.push(`constraint: ${constraint}`);
  for (const warning of result.warnings ?? []) lines.push(`- warning ${warning.id}: ${warning.message}`);
  for (const error of result.errors ?? []) lines.push(`- error ${error.id}: ${error.message}`);
  return `${lines.join('\n')}\n`;
}

async function main() {
  try {
    const opts = parseArgs(process.argv.slice(2));
    if (opts.help) return console.log(usage());
    if (!['check', 'upsert'].includes(opts.command)) throw new Error('First argument must be check or upsert.');
    if (!Number.isInteger(opts.pr) || opts.pr <= 0) throw new Error('Provide a positive --pr N.');
    if (opts.command === 'upsert' && !opts.file) throw new Error('upsert requires --file PATH.');
    opts.repo ??= detectRepoSlug();
    const gateway = createGhGateway({ repoSlug: opts.repo });
    const snapshot = await loadReviewSnapshot({ gateway, prNumber: opts.pr, includeActor: opts.command === 'upsert' });
    let result;
    if (opts.command === 'check') {
      const checked = checkReviewSnapshot({ repoSlug: opts.repo, pr: snapshot.pr, comments: snapshot.comments });
      result = { kind: 'justsearch-review-record-check.v1', errors: checked.errors, warnings: checked.projection.warnings, commentUrl: checked.managed[0]?.html_url ?? null };
    } else {
      const reviewBody = fs.readFileSync(path.resolve(opts.file), 'utf8');
      const plan = planReviewRecordUpsert({ repoSlug: opts.repo, ...snapshot, reviewBody });
      result = opts.execute ? await executeReviewRecordPlan({ gateway, plan, confirm: opts.confirm }) : plan;
    }
    if (opts.json) console.log(JSON.stringify(printable(result), null, 2));
    else process.stdout.write(render(result));
    if (result.errors?.length) process.exitCode = 1;
  } catch (error) {
    process.stderr.write(`pr-review-record: FAIL\n- ${error.message}\n`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) main();
