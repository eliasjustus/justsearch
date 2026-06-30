#!/usr/bin/env node
/**
 * Validate the public-main history publication settings declared in repo-history-policy.v1.json.
 */

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

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
    branch: 'main',
    policy: null,
    repoJson: null,
    protectionJson: null,
    json: false,
    md: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--repo' && argv[i + 1]) opts.repo = argv[++i];
    else if (arg === '--branch' && argv[i + 1]) opts.branch = argv[++i];
    else if (arg === '--policy' && argv[i + 1]) opts.policy = argv[++i];
    else if (arg === '--repo-json' && argv[i + 1]) opts.repoJson = argv[++i];
    else if (arg === '--protection-json' && argv[i + 1]) opts.protectionJson = argv[++i];
    else if (arg === '--json') opts.json = true;
    else if (arg === '--md') opts.md = true;
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  return opts;
}

function usage() {
  return [
    'Usage: node scripts/ci/check-repo-history-policy.mjs [--repo owner/repo] [--branch main] [--json|--md]',
    '',
    'Checks that GitHub repository and branch-protection settings match',
    'scripts/ci/repo-history-policy.v1.json for public-main history publication.',
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

function expectedRepoSettings(policy) {
  const settings = policy?.repoSettings || {};
  const errors = [];
  const expected = {};
  for (const [key, value] of Object.entries(settings)) {
    if (!['boolean', 'string'].includes(typeof value)) {
      errors.push(`policy repoSettings.${key} must be a boolean or string`);
      continue;
    }
    expected[key] = value;
  }
  if (Object.keys(expected).length === 0) errors.push('policy declares no repoSettings');
  return { expected, errors };
}

function actualRepoSettings(repo, expectedKeys) {
  const actual = {};
  for (const key of expectedKeys) actual[key] = repo?.[key];
  return actual;
}

function branchPublicationFromProtection(protection) {
  const pullRequestReviews = protection?.required_pull_request_reviews || null;
  return {
    requirePullRequestBeforeMerging: Boolean(pullRequestReviews),
    requiredApprovingReviewCount: pullRequestReviews?.required_approving_review_count ?? null,
    enforceAdmins: Boolean(protection?.enforce_admins?.enabled),
  };
}

export function validateRepoHistoryPolicy({ policy, repo, protection, branch = 'main' }) {
  const repoExpectation = expectedRepoSettings(policy);
  const expected = repoExpectation.expected;
  const actual = actualRepoSettings(repo, Object.keys(expected));
  const errors = [...repoExpectation.errors];

  for (const [key, value] of Object.entries(expected)) {
    if (actual[key] !== value) {
      errors.push(`repo setting ${key}: expected ${JSON.stringify(value)}, got ${JSON.stringify(actual[key])}`);
    }
  }

  const expectedProtection = policy?.branchProtection || {};
  if (expectedProtection.branch && expectedProtection.branch !== branch) {
    errors.push(`policy branchProtection.branch is ${expectedProtection.branch}, but checked branch is ${branch}`);
  }

  const publication = branchPublicationFromProtection(protection);
  if (expectedProtection.requirePullRequestBeforeMerging !== undefined) {
    const expectedPullRequest = Boolean(expectedProtection.requirePullRequestBeforeMerging);
    if (publication.requirePullRequestBeforeMerging !== expectedPullRequest) {
      errors.push(
        `branch ${branch} requirePullRequestBeforeMerging: expected ${expectedPullRequest}, got ${publication.requirePullRequestBeforeMerging}`
      );
    }
  }

  if (expectedProtection.requiredApprovingReviewCount !== undefined) {
    if (publication.requiredApprovingReviewCount !== expectedProtection.requiredApprovingReviewCount) {
      errors.push(
        `branch ${branch} requiredApprovingReviewCount: expected ${expectedProtection.requiredApprovingReviewCount}, got ${publication.requiredApprovingReviewCount}`
      );
    }
  }

  if (expectedProtection.enforceAdmins !== undefined) {
    const expectedEnforceAdmins = Boolean(expectedProtection.enforceAdmins);
    if (publication.enforceAdmins !== expectedEnforceAdmins) {
      errors.push(`branch ${branch} enforceAdmins: expected ${expectedEnforceAdmins}, got ${publication.enforceAdmins}`);
    }
  }

  return {
    ok: errors.length === 0,
    branch,
    expectedRepoSettings: expected,
    actualRepoSettings: actual,
    expectedBranchProtection: {
      requirePullRequestBeforeMerging: expectedProtection.requirePullRequestBeforeMerging,
      requiredApprovingReviewCount: expectedProtection.requiredApprovingReviewCount,
      enforceAdmins: expectedProtection.enforceAdmins,
    },
    actualBranchProtection: publication,
    errors,
  };
}

export function renderMarkdown(report) {
  const lines = [
    '# Repo History Policy',
    '',
    `Branch: ${report.branch}`,
    `Result: ${report.ok ? 'pass' : 'fail'}`,
    '',
    '## Repository Settings',
    '',
    '| Setting | Expected | Actual |',
    '|---|---:|---:|',
  ];
  for (const key of Object.keys(report.expectedRepoSettings)) {
    lines.push(`| ${key} | ${JSON.stringify(report.expectedRepoSettings[key])} | ${JSON.stringify(report.actualRepoSettings[key])} |`);
  }
  lines.push('', '## Branch Protection', '', '| Setting | Expected | Actual |', '|---|---:|---:|');
  for (const key of Object.keys(report.expectedBranchProtection)) {
    lines.push(
      `| ${key} | ${JSON.stringify(report.expectedBranchProtection[key])} | ${JSON.stringify(report.actualBranchProtection[key])} |`
    );
  }
  if (report.errors.length > 0) {
    lines.push('', '## Errors', '');
    for (const error of report.errors) lines.push(`- ${error}`);
  }
  lines.push('');
  return `${lines.join('\n')}\n`;
}

function loadRepo(opts) {
  if (opts.repoJson) return loadJson(opts.repoJson);
  return JSON.parse(execGh(['api', `repos/${opts.repo}`]));
}

function loadProtection(opts) {
  if (opts.protectionJson) return loadJson(opts.protectionJson);
  try {
    return JSON.parse(execGh(['api', `repos/${opts.repo}/branches/${opts.branch}/protection`]));
  } catch (error) {
    const output = [error.stdout, error.stderr].filter(Boolean).join('\n');
    if (output.includes('Resource not accessible by integration') || output.includes('"status":"403"')) {
      throw new Error(
        'GitHub token cannot read branch-protection settings. Run this script with maintainer credentials, not the default pull-request GITHUB_TOKEN.'
      );
    }
    throw error;
  }
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    console.log(usage());
    return;
  }
  const repoRoot = repoRootFromCwd();
  opts.repo ??= detectRepoSlug();
  opts.policy ??= path.join(repoRoot, 'scripts', 'ci', 'repo-history-policy.v1.json');

  let report;
  try {
    const policy = loadJson(opts.policy);
    const repo = loadRepo(opts);
    const protection = loadProtection(opts);
    report = validateRepoHistoryPolicy({ policy, repo, protection, branch: opts.branch });
  } catch (error) {
    console.error(`check-repo-history-policy: FAIL\n- ${error.message}`);
    process.exitCode = 1;
    return;
  }

  if (opts.json) console.log(JSON.stringify(report, null, 2));
  else if (opts.md) console.log(renderMarkdown(report));
  else if (report.ok) console.log(`check-repo-history-policy: OK (${opts.branch} publishes via curated squash PRs)`);
  else {
    console.error('check-repo-history-policy: FAIL');
    for (const error of report.errors) console.error(`- ${error}`);
  }

  if (!report.ok) process.exitCode = 1;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
