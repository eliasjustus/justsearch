#!/usr/bin/env node
/**
 * Preview the squash commit message GitHub will default to for a PR.
 *
 * This is maintainer UX, not a gate: warnings are advisory and do not make the
 * command fail. Usage, fetch, and parse errors still exit nonzero.
 */

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const KIND = 'justsearch-squash-message-preview.v1';
const BODY_PREVIEW_LINES = 12;
const LONG_BODY_CHARS = 5000;
const TEMPLATE_SECTIONS = ['Summary', 'Changes', 'Testing', 'Related Issues'];

function repoRootFromCwd() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

function parseArgs(argv) {
  const opts = {
    repo: null,
    pr: null,
    repoJson: null,
    prJson: null,
    json: false,
    md: false,
    help: false,
  };
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
    'Previews the PR title/body that GitHub will use as the default squash commit',
    'title/body when repository settings are PR_TITLE / PR_BODY.',
    '',
    'Options:',
    '  --repo owner/repo     GitHub repository (default: detected origin, then eliasjustus/justsearch)',
    '  --pr N                Pull request number to preview',
    '  --repo-json file      Read repository settings from fixture JSON',
    '  --pr-json file        Read PR data from fixture JSON',
    '  --json                Print structured JSON',
    '  --md                  Print Markdown report',
    '  -h, --help',
  ].join('\n');
}

function loadJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function execGh(args) {
  return execFileSync('gh', args, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
}

function detectRepoSlug() {
  try {
    const url = execFileSync('git', ['remote', 'get-url', 'origin'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
      windowsHide: true,
    }).trim();
    const match = url.match(/github\.com[:/](.+?)\/(.+?)(?:\.git)?$/i);
    if (match) return `${match[1]}/${match[2]}`;
  } catch {
    // Fall through to the public repo default.
  }
  return 'eliasjustus/justsearch';
}

function loadRepo(opts) {
  if (opts.repoJson) return loadJson(opts.repoJson);
  return JSON.parse(execGh(['api', `repos/${opts.repo}`]));
}

function loadPullRequest(opts) {
  if (opts.prJson) return loadJson(opts.prJson);
  return JSON.parse(
    execGh([
      'pr',
      'view',
      String(opts.pr),
      '--repo',
      opts.repo,
      '--json',
      'number,title,body,url,headRefName,isDraft,state,mergeStateStatus',
    ])
  );
}

function normalizePullRequest(pr) {
  return {
    number: pr?.number ?? null,
    title: String(pr?.title ?? '').trim(),
    body: pr?.body == null ? '' : String(pr.body),
    url: pr?.url || pr?.html_url || null,
    headRefName: pr?.headRefName || pr?.head?.ref || null,
    isDraft: Boolean(pr?.isDraft ?? pr?.draft),
    state: pr?.state || null,
    mergeStateStatus: pr?.mergeStateStatus || null,
  };
}

function addWarning(warnings, id, message) {
  warnings.push({ id, message });
}

function emptyTemplateSections(lines) {
  const empty = [];
  for (let i = 0; i < lines.length; i += 1) {
    const match = /^##\s+(.+?)\s*$/.exec(lines[i].trim());
    if (!match) continue;
    const section = TEMPLATE_SECTIONS.find((name) => name.toLowerCase() === match[1].toLowerCase());
    if (!section) continue;
    let j = i + 1;
    while (j < lines.length && lines[j].trim() === '') j += 1;
    if (j >= lines.length || /^##\s+/.test(lines[j].trim())) empty.push(section);
  }
  return empty;
}

function sectionHasContent(lines, sectionName) {
  for (let i = 0; i < lines.length; i += 1) {
    if (!new RegExp(`^##\\s+${sectionName}\\s*$`, 'i').test(lines[i].trim())) continue;
    for (let j = i + 1; j < lines.length; j += 1) {
      const line = lines[j].trim();
      if (/^##\s+/.test(line)) return false;
      if (line !== '') return true;
    }
    return false;
  }
  return false;
}

function hasTopLevelTestingLabel(lines) {
  return lines.some((line) => /^(Testing|Tests|Verification)\s*:\s*\S/i.test(line.trim()));
}

function hasTestingSignal(lines) {
  return sectionHasContent(lines, 'Testing') || hasTopLevelTestingLabel(lines);
}

function bodyPreviewLines(lines) {
  return lines.slice(0, BODY_PREVIEW_LINES);
}

function markdownFenceFor(lines) {
  let maxRun = 0;
  for (const line of lines) {
    for (const match of line.matchAll(/`+/g)) {
      maxRun = Math.max(maxRun, match[0].length);
    }
  }
  const fence = '`'.repeat(Math.max(3, maxRun + 1));
  return { open: `${fence}markdown`, close: fence };
}

export function buildSquashMessagePreview({ repoSlug = null, repo, pr }) {
  const pullRequest = normalizePullRequest(pr);
  const body = pullRequest.body;
  const lines = body === '' ? [] : body.split(/\r?\n/);
  const titleSource = repo?.squash_merge_commit_title ?? null;
  const bodySource = repo?.squash_merge_commit_message ?? null;
  const warnings = [];

  if (titleSource !== 'PR_TITLE' || bodySource !== 'PR_BODY') {
    addWarning(
      warnings,
      'repo-settings-not-pr-title-body',
      `Repository squash settings are ${JSON.stringify(titleSource)} / ${JSON.stringify(bodySource)}, so this PR title/body preview may not match GitHub's default squash message.`
    );
  }

  if (!pullRequest.title) addWarning(warnings, 'missing-title', 'PR title is empty; the public squash title should be edited before merge.');
  if (!body.trim()) addWarning(warnings, 'missing-body', 'PR body is empty; PR_BODY would publish an empty squash body.');
  if (body.length > LONG_BODY_CHARS) {
    addWarning(warnings, 'very-long-body', `PR body is ${body.length} characters; the public squash body may be too noisy.`);
  }
  if (/<details\b/i.test(body)) {
    addWarning(warnings, 'html-details', 'PR body contains HTML <details> blocks, often generated release-note content.');
  }
  if (/<!--/.test(body)) {
    addWarning(warnings, 'html-comment', 'PR body contains HTML comments that may survive into the squash body.');
  }
  if (/(^|\n)\s*[-*]\s+\[[ xX]\]/.test(body)) {
    addWarning(warnings, 'checklist', 'PR body contains visible checklist items.');
  }

  const empty = emptyTemplateSections(lines);
  if (empty.length > 0) {
    addWarning(warnings, 'empty-template-sections', `Template sections are empty: ${empty.join(', ')}.`);
  }

  if (body.trim() && !hasTestingSignal(lines)) {
    addWarning(warnings, 'missing-testing-signal', 'No clear testing or verification signal was found in the PR body.');
  }

  const opening = [pullRequest.title, ...lines.slice(0, BODY_PREVIEW_LINES)].join('\n');
  if (/\b(wip|work in progress|do not publish)\b/i.test(opening) || /^draft\b/i.test(pullRequest.title)) {
    addWarning(warnings, 'draft-publication-marker', 'The PR title or opening body contains a WIP/draft/do-not-publish marker.');
  }

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
    settings: {
      titleSource,
      bodySource,
      matchesPrTitleBody: titleSource === 'PR_TITLE' && bodySource === 'PR_BODY',
    },
    proposedCommit: {
      title: pullRequest.title,
      body,
      bodyChars: body.length,
      bodyLines: lines.length,
      bodyPreviewLines: bodyPreviewLines(lines),
    },
    warnings,
  };
}

export function renderMarkdown(report) {
  const lines = [
    '# Squash Message Preview',
    '',
    `Repository: ${report.repo || 'unknown'}`,
    `PR: ${report.pr.number == null ? 'fixture' : `#${report.pr.number}`} ${report.pr.url ? `(${report.pr.url})` : ''}`.trim(),
    `Settings: title=${JSON.stringify(report.settings.titleSource)}, body=${JSON.stringify(report.settings.bodySource)}`,
    `Warnings: ${report.warnings.length}`,
    '',
    '## Proposed Commit',
    '',
    `Title: ${report.proposedCommit.title || '<empty>'}`,
    `Body: ${report.proposedCommit.bodyChars} chars, ${report.proposedCommit.bodyLines} lines`,
    '',
  ];
  if (report.warnings.length > 0) {
    lines.push('## Warnings', '');
    for (const warning of report.warnings) lines.push(`- **${warning.id}:** ${warning.message}`);
    lines.push('');
  }
  lines.push('## Body Preview', '');
  if (report.proposedCommit.bodyPreviewLines.length === 0) {
    lines.push('_No body content._', '');
  } else {
    const fence = markdownFenceFor(report.proposedCommit.bodyPreviewLines);
    lines.push(fence.open, ...report.proposedCommit.bodyPreviewLines, fence.close, '');
  }
  return `${lines.join('\n')}\n`;
}

export function renderText(report) {
  const prLabel = report.pr.number == null ? 'fixture' : `#${report.pr.number}`;
  const lines = [
    `preview-squash-message: OK (${prLabel}, ${report.proposedCommit.bodyChars} chars, ${report.warnings.length} warnings)`,
    `title: ${report.proposedCommit.title || '<empty>'}`,
    `settings: title=${JSON.stringify(report.settings.titleSource)}, body=${JSON.stringify(report.settings.bodySource)}`,
  ];
  for (const warning of report.warnings) lines.push(`- ${warning.id}: ${warning.message}`);
  return `${lines.join('\n')}\n`;
}

function main() {
  let opts;
  try {
    opts = parseArgs(process.argv.slice(2));
    if (opts.help) {
      console.log(usage());
      return;
    }
    if (!opts.pr && !opts.prJson) throw new Error('Provide --pr N or --pr-json file.');
    opts.repo ??= detectRepoSlug();
    repoRootFromCwd();
    const repo = loadRepo(opts);
    const pr = loadPullRequest(opts);
    const report = buildSquashMessagePreview({ repoSlug: opts.repo, repo, pr });
    if (opts.json) console.log(JSON.stringify(report, null, 2));
    else if (opts.md) console.log(renderMarkdown(report));
    else process.stdout.write(renderText(report));
  } catch (error) {
    console.error(`preview-squash-message: FAIL\n- ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
