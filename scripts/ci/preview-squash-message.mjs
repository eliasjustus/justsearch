#!/usr/bin/env node
/** Preview and validate the exact squash subject/body projection for a PR. */

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { resolveGhBin } from '../dev/run-gh.mjs';
import { buildSquashMessageProjection } from './lib/squash-message-projection.mjs';

const SNAPSHOT_QUERY = `
query PublicationSnapshot($owner: String!, $name: String!, $number: Int!) {
  repository(owner: $owner, name: $name) {
    pullRequest(number: $number) {
      number title body url headRefName headRefOid updatedAt baseRefName
      isDraft state mergeStateStatus isInMergeQueue
      mergeQueueEntry { position }
      autoMergeRequest { enabledAt }
      author { login }
      viewerMergeHeadlineText(mergeType: SQUASH)
    }
  }
}`;

function parseArgs(argv) {
  const opts = { repo: null, pr: null, prJson: null, json: false, md: false, help: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--repo' && argv[i + 1]) opts.repo = argv[++i];
    else if (arg === '--pr' && argv[i + 1]) opts.pr = Number(argv[++i]);
    else if (arg === '--pr-json' && argv[i + 1]) opts.prJson = argv[++i];
    else if (arg === '--json') opts.json = true;
    else if (arg === '--md') opts.md = true;
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  if (opts.pr != null && (!Number.isInteger(opts.pr) || opts.pr <= 0)) throw new Error('--pr must be a positive integer.');
  return opts;
}

function usage() {
  return [
    'Usage: node scripts/ci/preview-squash-message.mjs --pr N [--repo owner/repo] [--json|--md]',
    '',
    'Validates the PR body contract and shows the exact subject/body intended for',
    'the asynchronous squash-merge request. This command never enqueues a merge.',
    '',
    'Options:',
    '  --repo owner/repo  GitHub repository (default: detected origin)',
    '  --pr N             Pull request number',
    '  --pr-json file     Read a GraphQL-shaped PR snapshot fixture',
    '  --json             Print the v2 projection as JSON',
    '  --md               Print a Markdown report',
    '  -h, --help',
  ].join('\n');
}

function detectRepoSlug() {
  const url = execFileSync('git', ['remote', 'get-url', 'origin'], {
    encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], windowsHide: true,
  }).trim();
  const match = url.match(/github\.com[:/](.+?)\/(.+?)(?:\.git)?$/i);
  if (!match) throw new Error('Could not detect a GitHub owner/repository from origin; pass --repo.');
  return `${match[1]}/${match[2]}`;
}

export function splitRepoSlug(slug) {
  const match = /^([^/]+)\/([^/]+)$/.exec(String(slug ?? ''));
  if (!match) throw new Error(`Invalid --repo value: ${JSON.stringify(slug)}.`);
  return { owner: match[1], name: match[2] };
}

export function loadPublicationSnapshot(opts) {
  if (opts.prJson) return JSON.parse(fs.readFileSync(opts.prJson, 'utf8'));
  const { owner, name } = splitRepoSlug(opts.repo);
  const output = execFileSync(resolveGhBin(), [
    'api', 'graphql', '-f', `query=${SNAPSHOT_QUERY}`,
    '-F', `owner=${owner}`, '-F', `name=${name}`, '-F', `number=${opts.pr}`,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  const result = JSON.parse(output);
  const pr = result?.data?.repository?.pullRequest;
  if (!pr) throw new Error(`PR #${opts.pr} was not returned by GitHub.`);
  return pr;
}

function markdownFenceFor(text) {
  let maxRun = 0;
  for (const match of String(text).matchAll(/`+/g)) maxRun = Math.max(maxRun, match[0].length);
  const fence = '`'.repeat(Math.max(3, maxRun + 1));
  return { open: `${fence}markdown`, close: fence };
}

export function renderMarkdown(report) {
  const lines = [
    '# Squash Message Projection', '',
    `Repository: ${report.repo || 'unknown'}`,
    `PR: ${report.source.prNumber == null ? 'fixture' : `#${report.source.prNumber}`}`,
    `Head: ${report.source.headSha || 'unknown'}`,
    `Updated: ${report.source.updatedAt || 'unknown'}`,
    `Errors: ${report.errors.length}; warnings: ${report.warnings.length}`, '',
    '## Expected landed subject', '', report.expectedLandedSubject || '_Unavailable._', '',
  ];
  if (report.errors.length) {
    lines.push('## Errors', '');
    for (const error of report.errors) lines.push(`- **${error.id}:** ${error.message}`);
    lines.push('');
  }
  if (report.warnings.length) {
    lines.push('## Warnings', '');
    for (const warning of report.warnings) lines.push(`- **${warning.id}:** ${warning.message}`);
    lines.push('');
  }
  lines.push('## Exact body', '');
  if (!report.body) lines.push('_Empty body._', '');
  else {
    const fence = markdownFenceFor(report.body);
    lines.push(fence.open, report.body, fence.close, '');
  }
  return `${lines.join('\n')}\n`;
}

export function renderText(report) {
  const verdict = report.errors.length ? 'FAIL' : 'OK';
  const lines = [
    `preview-squash-message: ${verdict} (${report.bodyChars} chars, ${report.errors.length} errors, ${report.warnings.length} warnings)`,
    `subject: ${report.expectedLandedSubject || '<unavailable>'}`,
    `head: ${report.source.headSha || '<unknown>'}`,
  ];
  for (const error of report.errors) lines.push(`- ERROR ${error.id}: ${error.message}`);
  for (const warning of report.warnings) lines.push(`- WARN ${warning.id}: ${warning.message}`);
  lines.push('', 'exact body:', report.body || '<empty>');
  return `${lines.join('\n')}\n`;
}

export function buildSquashMessagePreview(args) {
  return buildSquashMessageProjection(args);
}

function main() {
  try {
    const opts = parseArgs(process.argv.slice(2));
    if (opts.help) return void console.log(usage());
    if (!opts.pr && !opts.prJson) throw new Error('Provide --pr N or --pr-json file.');
    opts.repo ??= detectRepoSlug();
    const report = buildSquashMessageProjection({ repoSlug: opts.repo, pr: loadPublicationSnapshot(opts) });
    if (opts.json) console.log(JSON.stringify(report, null, 2));
    else if (opts.md) process.stdout.write(renderMarkdown(report));
    else process.stdout.write(renderText(report));
    if (report.errors.length) process.exitCode = 1;
  } catch (error) {
    console.error(`preview-squash-message: FAIL\n- ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) main();
