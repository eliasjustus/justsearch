#!/usr/bin/env node
/** Preview the commit-safe PR title/body used by GitHub's squash merge. */

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { buildPublicSquashRecord } from './lib/squash-message-projection.mjs';

const KIND = 'justsearch-squash-message-preview.v2';
const BODY_PREVIEW_LINES = 12;

function parseArgs(argv) {
  const opts = { repo: null, pr: null, repoJson: null, prJson: null, json: false, md: false, help: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--repo' && argv[i + 1]) opts.repo = argv[++i];
    else if (arg === '--pr' && argv[i + 1]) opts.pr = argv[++i];
    else if (arg === '--repo-json' && argv[i + 1]) opts.repoJson = argv[++i];
    else if (arg === '--pr-json' && argv[i + 1]) opts.prJson = argv[++i];
    else if (arg === '--json') opts.json = true;
    else if (arg === '--md') opts.md = true;
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  return opts;
}

function usage() {
  return [
    'Usage: node scripts/ci/preview-squash-message.mjs --pr N [--repo owner/repo] [--json|--md]',
    '',
    'Previews and validates the complete commit-safe PR title/body used by the',
    'repository PR_TITLE / PR_BODY squash configuration. Review evidence belongs',
    'in the separate managed PR comment checked by pr-review-record.mjs.',
  ].join('\n');
}

function loadJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function execGh(args) {
  return execFileSync('gh', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
}

function detectRepoSlug() {
  try {
    const url = execFileSync('git', ['remote', 'get-url', 'origin'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], windowsHide: true }).trim();
    const match = url.match(/github\.com[:/](.+?)\/(.+?)(?:\.git)?$/i);
    if (match) return `${match[1]}/${match[2]}`;
  } catch {
    // Fall through to the public repository default.
  }
  return 'justsearch-app/justsearch';
}

function loadRepo(opts) {
  return opts.repoJson ? loadJson(opts.repoJson) : JSON.parse(execGh(['api', `repos/${opts.repo}`]));
}

function loadPullRequest(opts) {
  if (opts.prJson) return loadJson(opts.prJson);
  return JSON.parse(execGh(['pr', 'view', String(opts.pr), '--repo', opts.repo, '--json', 'number,title,body,url,headRefName,headRefOid,updatedAt,author,isDraft,state,mergeStateStatus']));
}

function normalizePullRequest(pr) {
  return {
    number: pr?.number ?? null,
    title: String(pr?.title ?? '').trim(),
    body: pr?.body == null ? '' : String(pr.body),
    url: pr?.url || pr?.html_url || null,
    headRefName: pr?.headRefName || pr?.head?.ref || null,
    headRefOid: pr?.headRefOid || pr?.head?.sha || null,
    updatedAt: pr?.updatedAt || pr?.updated_at || null,
    author: pr?.author || pr?.user || null,
    isDraft: Boolean(pr?.isDraft ?? pr?.draft),
    state: pr?.state || null,
    mergeStateStatus: pr?.mergeStateStatus || null,
  };
}

function markdownFenceFor(lines) {
  let maxRun = 0;
  for (const line of lines) for (const match of line.matchAll(/`+/g)) maxRun = Math.max(maxRun, match[0].length);
  const fence = '`'.repeat(Math.max(3, maxRun + 1));
  return { open: `${fence}markdown`, close: fence };
}

export function buildSquashMessagePreview({ repoSlug = null, repo, pr }) {
  const pullRequest = normalizePullRequest(pr);
  const publicRecord = buildPublicSquashRecord({ repoSlug, pr: pullRequest });
  const errors = [...publicRecord.errors];
  const warnings = [];
  const titleSource = repo?.squash_merge_commit_title ?? null;
  const bodySource = repo?.squash_merge_commit_message ?? null;
  if (titleSource !== 'PR_TITLE' || bodySource !== 'PR_BODY') {
    errors.push({ id: 'repo-settings-not-pr-title-body', message: `Repository squash settings are ${JSON.stringify(titleSource)} / ${JSON.stringify(bodySource)}, so this preview cannot establish GitHub's default squash message.` });
  }
  warnings.push(...publicRecord.warnings);
  const bodyLines = publicRecord.body === '' ? [] : publicRecord.body.split('\n');
  return {
    kind: KIND,
    repo: repoSlug,
    pr: {
      number: pullRequest.number,
      title: pullRequest.title,
      url: pullRequest.url,
      headRefName: pullRequest.headRefName,
      isDraft: pullRequest.isDraft,
      state: pullRequest.state,
      mergeStateStatus: pullRequest.mergeStateStatus,
    },
    settings: { titleSource, bodySource, matchesPrTitleBody: titleSource === 'PR_TITLE' && bodySource === 'PR_BODY' },
    proposedCommit: {
      title: publicRecord.expectedLandedSubject,
      body: publicRecord.body,
      bodySha256: publicRecord.publicBodySha256,
      bodyChars: publicRecord.bodyChars,
      bodyLines: publicRecord.bodyLines,
      bodyPreviewLines: bodyLines.slice(0, BODY_PREVIEW_LINES),
    },
    errors,
    warnings,
  };
}

export function renderMarkdown(report) {
  const lines = [
    '# Squash Message Preview', '',
    `Repository: ${report.repo || 'unknown'}`,
    `PR: ${report.pr.number == null ? 'fixture' : `#${report.pr.number}`} ${report.pr.url ? `(${report.pr.url})` : ''}`.trim(),
    `Settings: title=${JSON.stringify(report.settings.titleSource)}, body=${JSON.stringify(report.settings.bodySource)}`,
    `Errors: ${report.errors.length}`,
    `Warnings: ${report.warnings.length}`, '',
    '## Proposed Commit', '',
    `Title: ${report.proposedCommit.title || '<empty>'}`,
    `Body: ${report.proposedCommit.bodyChars} chars, ${report.proposedCommit.bodyLines} lines`, '',
  ];
  if (report.errors.length > 0) {
    lines.push('## Errors', '');
    for (const error of report.errors) lines.push(`- **${error.id}:** ${error.message}`);
    lines.push('');
  }
  if (report.warnings.length > 0) {
    lines.push('## Warnings', '');
    for (const warning of report.warnings) lines.push(`- **${warning.id}:** ${warning.message}`);
    lines.push('');
  }
  lines.push('## Body Preview', '');
  if (report.proposedCommit.bodyPreviewLines.length === 0) lines.push('_No body content._', '');
  else {
    const fence = markdownFenceFor(report.proposedCommit.bodyPreviewLines);
    lines.push(fence.open, ...report.proposedCommit.bodyPreviewLines, fence.close, '');
  }
  return `${lines.join('\n')}\n`;
}

export function renderText(report) {
  const prLabel = report.pr.number == null ? 'fixture' : `#${report.pr.number}`;
  const lines = [
    `preview-squash-message: ${report.errors.length ? 'FAIL' : 'OK'} (${prLabel}, ${report.proposedCommit.bodyChars} chars, ${report.errors.length} errors, ${report.warnings.length} warnings)`,
    `title: ${report.proposedCommit.title || '<empty>'}`,
    `settings: title=${JSON.stringify(report.settings.titleSource)}, body=${JSON.stringify(report.settings.bodySource)}`,
  ];
  for (const error of report.errors) lines.push(`- error ${error.id}: ${error.message}`);
  for (const warning of report.warnings) lines.push(`- ${warning.id}: ${warning.message}`);
  return `${lines.join('\n')}\n`;
}

function main() {
  try {
    const opts = parseArgs(process.argv.slice(2));
    if (opts.help) return console.log(usage());
    if (!opts.pr && !opts.prJson) throw new Error('Provide --pr N or --pr-json file.');
    opts.repo ??= detectRepoSlug();
    const report = buildSquashMessagePreview({ repoSlug: opts.repo, repo: loadRepo(opts), pr: loadPullRequest(opts) });
    if (opts.json) console.log(JSON.stringify(report, null, 2));
    else if (opts.md) console.log(renderMarkdown(report));
    else process.stdout.write(renderText(report));
    if (report.errors.length > 0) process.exitCode = 1;
  } catch (error) {
    console.error(`preview-squash-message: FAIL\n- ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) main();
