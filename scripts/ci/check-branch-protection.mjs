#!/usr/bin/env node
/**
 * Validate GitHub branch protection required checks against workflow-signal-policy.v1.json.
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
    protectionJson: null,
    json: false,
    md: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--repo' && argv[i + 1]) opts.repo = argv[++i];
    else if (arg === '--branch' && argv[i + 1]) opts.branch = argv[++i];
    else if (arg === '--policy' && argv[i + 1]) opts.policy = argv[++i];
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
    'Usage: node scripts/ci/check-branch-protection.mjs [--repo owner/repo] [--branch main] [--json|--md]',
    '',
    'Checks that GitHub branch protection requires exactly the status checks declared in',
    'scripts/ci/workflow-signal-policy.v1.json, with strict up-to-date branches enabled.',
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

export function requiredStatusChecksFromPolicy(policy) {
  const checks = [];
  const errors = [];
  for (const workflow of policy.workflows || []) {
    for (const check of workflow.requiredStatusChecks || []) {
      if (typeof check !== 'string' || check.trim() === '') {
        errors.push(`workflow ${workflow.name || workflow.path || '<unknown>'} has an invalid requiredStatusChecks entry`);
        continue;
      }
      checks.push(check);
    }
  }

  const seen = new Set();
  const duplicates = new Set();
  for (const check of checks) {
    if (seen.has(check)) duplicates.add(check);
    seen.add(check);
  }
  for (const duplicate of duplicates) {
    errors.push(`duplicate required status check in policy: ${duplicate}`);
  }

  return { checks, errors };
}

export function protectedStatusChecksFromProtection(protection) {
  const required = protection?.required_status_checks || null;
  const contexts = new Set();
  for (const context of required?.contexts || []) contexts.add(String(context));
  for (const check of required?.checks || []) {
    if (check?.context) contexts.add(String(check.context));
  }
  return {
    strict: Boolean(required?.strict),
    contexts: [...contexts].sort((a, b) => a.localeCompare(b)),
    configured: Boolean(required),
  };
}

export function validateBranchProtection({ policy, protection, branch = 'main' }) {
  const expected = requiredStatusChecksFromPolicy(policy);
  const actual = protectedStatusChecksFromProtection(protection);
  const errors = [...expected.errors];
  const expectedSet = new Set(expected.checks);
  const actualSet = new Set(actual.contexts);

  if (expected.checks.length === 0) errors.push('policy declares no required status checks');
  if (!actual.configured) errors.push(`branch ${branch} has no required status checks configured`);
  if (actual.configured && !actual.strict) errors.push(`branch ${branch} required status checks are not strict`);

  for (const check of expected.checks) {
    if (!actualSet.has(check)) errors.push(`branch ${branch} is missing required status check: ${check}`);
  }
  for (const check of actual.contexts) {
    if (!expectedSet.has(check)) errors.push(`branch ${branch} requires undeclared status check: ${check}`);
  }

  return {
    ok: errors.length === 0,
    branch,
    strict: actual.strict,
    expectedChecks: [...expected.checks].sort((a, b) => a.localeCompare(b)),
    actualChecks: actual.contexts,
    errors,
  };
}

export function renderMarkdown(report) {
  const lines = [
    '# Branch Protection Required Checks',
    '',
    `Branch: ${report.branch}`,
    `Strict: ${report.strict ? 'yes' : 'no'}`,
    `Result: ${report.ok ? 'pass' : 'fail'}`,
    '',
    '| Check | Required by policy | Protected on branch |',
    '|---|---:|---:|',
  ];
  const names = [...new Set([...report.expectedChecks, ...report.actualChecks])].sort((a, b) => a.localeCompare(b));
  for (const name of names) {
    lines.push(`| ${name} | ${report.expectedChecks.includes(name) ? 'yes' : 'no'} | ${report.actualChecks.includes(name) ? 'yes' : 'no'} |`);
  }
  if (report.errors.length > 0) {
    lines.push('', '## Errors', '');
    for (const error of report.errors) lines.push(`- ${error}`);
  }
  lines.push('');
  return `${lines.join('\n')}\n`;
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
  opts.policy ??= path.join(repoRoot, 'scripts', 'ci', 'workflow-signal-policy.v1.json');
  let report;
  try {
    const policy = loadJson(opts.policy);
    const protection = loadProtection(opts);
    report = validateBranchProtection({ policy, protection, branch: opts.branch });
  } catch (error) {
    console.error(`check-branch-protection: FAIL\n- ${error.message}`);
    process.exitCode = 1;
    return;
  }

  if (opts.json) console.log(JSON.stringify(report, null, 2));
  else if (opts.md) console.log(renderMarkdown(report));
  else if (report.ok) console.log(`check-branch-protection: OK (${opts.branch} requires ${report.expectedChecks.length} declared checks)`);
  else {
    console.error('check-branch-protection: FAIL');
    for (const error of report.errors) console.error(`- ${error}`);
  }

  if (!report.ok) process.exitCode = 1;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
