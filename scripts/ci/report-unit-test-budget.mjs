#!/usr/bin/env node
/**
 * Produce a warn-only budget report for one public unit-test shard.
 *
 * The Gradle test step remains the pass/fail authority. This report only turns
 * attribution JSON into advisory budget evidence for future trend work.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const KIND = 'justsearch-unit-test-budget.v1';

function repoRootFromCwd() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

function usage() {
  return [
    'Usage: node scripts/ci/report-unit-test-budget.mjs --lane <lane> --attribution <file> [options]',
    '',
    'Options:',
    '  --policy <file>           Unit-test shard policy JSON',
    '  --out-json <path>         Write JSON report',
    '  --out-md <path>           Write Markdown report',
    '  --json                    Print JSON to stdout',
    '  --md                      Print Markdown to stdout',
    '  -h, --help',
  ].join('\n');
}

function parseArgs(argv) {
  const out = {
    lane: null,
    attribution: null,
    policy: null,
    outJson: null,
    outMd: null,
    json: false,
    md: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--lane' && argv[i + 1]) out.lane = argv[++i];
    else if (arg === '--attribution' && argv[i + 1]) out.attribution = argv[++i];
    else if (arg === '--policy' && argv[i + 1]) out.policy = argv[++i];
    else if (arg === '--out-json' && argv[i + 1]) out.outJson = argv[++i];
    else if (arg === '--out-md' && argv[i + 1]) out.outMd = argv[++i];
    else if (arg === '--json') out.json = true;
    else if (arg === '--md') out.md = true;
    else if (arg === '--help' || arg === '-h') out.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  if (!out.help && (!out.lane || !out.attribution)) {
    throw new Error('--lane and --attribution are required');
  }
  return out;
}

function loadJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function findLane(policy, laneName) {
  return (policy.lanes || []).find((lane) => lane.lane === laneName) || null;
}

function warn(code, message, details = {}) {
  return { code, message, ...details };
}

export function buildBudgetReport({ lane, attribution, policy }) {
  const policyLane = findLane(policy, lane);
  if (!policyLane) throw new Error(`lane not found in unit-test shard policy: ${lane}`);
  const budgets = policyLane.advisoryBudgets || {};
  const warnings = [];
  const totals = attribution.totals || {};
  const slowestSuites = Array.isArray(attribution.slowestSuites) ? attribution.slowestSuites : [];

  if (attribution.lane && attribution.lane !== lane) {
    warnings.push(warn('lane-mismatch', `Attribution lane is ${attribution.lane}, expected ${lane}.`, {
      actual: attribution.lane,
      expected: lane,
    }));
  }
  if (attribution.runner?.runnerLabel && attribution.runner.runnerLabel !== policyLane.runnerLabel) {
    warnings.push(warn('runner-label-mismatch', `Runner label is ${attribution.runner.runnerLabel}, expected ${policyLane.runnerLabel}.`, {
      actual: attribution.runner.runnerLabel,
      expected: policyLane.runnerLabel,
    }));
  }
  if ((totals.suites || 0) === 0) {
    warnings.push(warn('empty-attribution', 'No JUnit suites were found in the attribution report.'));
  }
  if (Number.isFinite(budgets.maxSummedSuiteSeconds) && (totals.timeSeconds || 0) > budgets.maxSummedSuiteSeconds) {
    warnings.push(warn('summed-suite-time-over-budget', `Summed suite time ${totals.timeSeconds}s exceeds advisory budget ${budgets.maxSummedSuiteSeconds}s.`, {
      actualSeconds: totals.timeSeconds,
      budgetSeconds: budgets.maxSummedSuiteSeconds,
    }));
  }
  if (Number.isFinite(budgets.maxSkipped) && (totals.skipped || 0) > budgets.maxSkipped) {
    warnings.push(warn('skipped-tests-over-budget', `Skipped tests ${totals.skipped} exceed advisory budget ${budgets.maxSkipped}.`, {
      actualSkipped: totals.skipped,
      budgetSkipped: budgets.maxSkipped,
    }));
  }
  if (Number.isFinite(budgets.slowSuiteWarnSeconds)) {
    for (const suite of slowestSuites.filter((suite) => (suite.timeSeconds || 0) > budgets.slowSuiteWarnSeconds)) {
      warnings.push(warn('slow-suite-over-budget', `${suite.name} took ${suite.timeSeconds}s, above advisory budget ${budgets.slowSuiteWarnSeconds}s.`, {
        suite: suite.name,
        module: suite.module,
        actualSeconds: suite.timeSeconds,
        budgetSeconds: budgets.slowSuiteWarnSeconds,
      }));
    }
  }

  return {
    kind: KIND,
    generatedAt: new Date().toISOString(),
    lane,
    advisory: true,
    owner: policyLane.owner,
    requiredCheck: policyLane.requiredCheck,
    platformClass: policyLane.platformClass,
    runner: attribution.runner || {},
    budgets,
    totals: {
      suites: totals.suites || 0,
      tests: totals.tests || 0,
      skipped: totals.skipped || 0,
      failures: totals.failures || 0,
      errors: totals.errors || 0,
      timeSeconds: totals.timeSeconds || 0,
    },
    warningCount: warnings.length,
    warnings,
  };
}

function renderMarkdown(report) {
  const lines = [
    '### Unit test budget',
    '',
    `Generated: ${report.generatedAt}`,
    '',
    `Lane: ${report.lane}`,
    '',
    `Owner: ${report.owner}`,
    '',
    `Required check: ${report.requiredCheck}`,
    '',
    `Platform class: ${report.platformClass}`,
    '',
    `Status: warn-only, ${report.warningCount} warning(s)`,
    '',
    '| Budget | Actual | Advisory ceiling |',
    '|---|---:|---:|',
    `| Summed suite time | ${report.totals.timeSeconds}s | ${report.budgets.maxSummedSuiteSeconds}s |`,
    `| Skipped tests | ${report.totals.skipped} | ${report.budgets.maxSkipped} |`,
    `| Slow suite threshold | n/a | ${report.budgets.slowSuiteWarnSeconds}s |`,
    '',
  ];
  if (report.warnings.length === 0) {
    lines.push('No advisory budget warnings.');
  } else {
    lines.push('| Code | Message |', '|---|---|');
    for (const warning of report.warnings) {
      lines.push(`| ${warning.code} | ${warning.message} |`);
    }
  }
  lines.push('');
  return `${lines.join('\n')}\n`;
}

function writeText(filePath, text) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, text, 'utf8');
}

function main() {
  try {
    const opts = parseArgs(process.argv.slice(2));
    if (opts.help) {
      console.log(usage());
      return;
    }
    const root = repoRootFromCwd();
    const policyPath = path.resolve(opts.policy || path.join(root, 'scripts', 'ci', 'unit-test-shard-policy.v1.json'));
    const report = buildBudgetReport({
      lane: opts.lane,
      attribution: loadJson(path.resolve(opts.attribution)),
      policy: loadJson(policyPath),
    });
    const json = `${JSON.stringify(report, null, 2)}\n`;
    const md = renderMarkdown(report);
    if (opts.outJson) writeText(path.resolve(opts.outJson), json);
    if (opts.outMd) writeText(path.resolve(opts.outMd), md);
    if (process.env.GITHUB_STEP_SUMMARY) {
      try {
        fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, md, 'utf8');
      } catch {
        // Best effort only.
      }
    }
    if (opts.json) process.stdout.write(json);
    else if (opts.md || (!opts.outJson && !opts.outMd)) process.stdout.write(md);
  } catch (error) {
    console.error(`report-unit-test-budget: ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}

export { renderMarkdown };
