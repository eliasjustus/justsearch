#!/usr/bin/env node
/**
 * Summarize Gradle/JUnit unit-test XML for the public CI Unit tests lane.
 *
 * This is attribution only: it reports timing, skips, failures, and runner
 * identity without changing the Gradle test result.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const KIND = 'justsearch-unit-test-attribution.v1';

function repoRootFromCwd() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

function usage() {
  return [
    'Usage: node scripts/ci/report-unit-test-attribution.mjs [options]',
    '',
    'Options:',
    '  --results-root <path>     Root to scan (default: repo root)',
    '  --runner-label <label>    Workflow runner label, e.g. windows-latest',
    '  --top <n>                 Number of slow suites to include (default: 20)',
    '  --out-json <path>         Write JSON report',
    '  --out-md <path>           Write Markdown report',
    '  --json                    Print JSON to stdout',
    '  --md                      Print Markdown to stdout',
    '  --allow-empty             Exit 0 when no JUnit XML files are found',
    '  -h, --help',
  ].join('\n');
}

function parseArgs(argv) {
  const out = {
    resultsRoot: null,
    runnerLabel: null,
    top: 20,
    outJson: null,
    outMd: null,
    json: false,
    md: false,
    allowEmpty: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--results-root' && argv[i + 1]) out.resultsRoot = argv[++i];
    else if (arg === '--runner-label' && argv[i + 1]) out.runnerLabel = argv[++i];
    else if (arg === '--top' && argv[i + 1]) out.top = Number.parseInt(argv[++i], 10);
    else if (arg === '--out-json' && argv[i + 1]) out.outJson = argv[++i];
    else if (arg === '--out-md' && argv[i + 1]) out.outMd = argv[++i];
    else if (arg === '--json') out.json = true;
    else if (arg === '--md') out.md = true;
    else if (arg === '--allow-empty') out.allowEmpty = true;
    else if (arg === '--help' || arg === '-h') out.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  if (!Number.isInteger(out.top) || out.top <= 0) {
    throw new Error('--top must be a positive integer');
  }
  return out;
}

function normalizeRel(root, absPath) {
  return path.relative(root, absPath).split(path.sep).join('/');
}

function decodeXmlAttr(value) {
  return String(value)
    .replaceAll('&quot;', '"')
    .replaceAll('&apos;', "'")
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&amp;', '&');
}

function attrsFrom(tagText) {
  const attrs = {};
  const re = /([A-Za-z_:][\w:.-]*)\s*=\s*"([^"]*)"/g;
  for (const match of tagText.matchAll(re)) attrs[match[1]] = decodeXmlAttr(match[2]);
  return attrs;
}

function asNumber(value, fallback = 0) {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

function asInt(value) {
  return Math.trunc(asNumber(value, 0));
}

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const abs = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === '.git' || entry.name === 'node_modules') continue;
      walk(abs, out);
    } else if (entry.isFile() && /^TEST-.+\.xml$/i.test(entry.name)) {
      const normalized = abs.split(path.sep).join('/');
      if (normalized.includes('/build/test-results/test/')) out.push(abs);
    }
  }
  return out;
}

function modulePathFor(root, filePath) {
  const rel = normalizeRel(root, filePath);
  const match = /^(modules\/[^/]+)\/build\/test-results\/test\//.exec(rel);
  if (match) return match[1];
  const buildIndex = rel.indexOf('/build/test-results/test/');
  return buildIndex === -1 ? '<unknown>' : rel.slice(0, buildIndex);
}

function parseSuite(root, filePath) {
  const text = fs.readFileSync(filePath, 'utf8');
  const suiteMatch = /<testsuite\b([^>]*)>/s.exec(text);
  if (!suiteMatch) return null;
  const attrs = attrsFrom(suiteMatch[1]);
  return {
    module: modulePathFor(root, filePath),
    name: attrs.name || path.basename(filePath, '.xml').replace(/^TEST-/, ''),
    file: normalizeRel(root, filePath),
    tests: asInt(attrs.tests),
    skipped: asInt(attrs.skipped),
    failures: asInt(attrs.failures),
    errors: asInt(attrs.errors),
    timeSeconds: asNumber(attrs.time),
  };
}

function runnerInfo(opts, env = process.env) {
  return {
    runnerLabel: opts.runnerLabel || null,
    runnerOs: env.RUNNER_OS || null,
    runnerArch: env.RUNNER_ARCH || null,
    runnerName: env.RUNNER_NAME || null,
    imageOs: env.ImageOS || null,
    imageVersion: env.ImageVersion || null,
  };
}

function round3(n) {
  return Math.round(n * 1000) / 1000;
}

export function buildReport({ root, top = 20, runner = runnerInfo({}) }) {
  const suites = walk(root)
    .map((filePath) => parseSuite(root, filePath))
    .filter(Boolean);
  const modules = new Map();
  const totals = {
    suites: suites.length,
    tests: 0,
    skipped: 0,
    failures: 0,
    errors: 0,
    timeSeconds: 0,
  };

  for (const suite of suites) {
    totals.tests += suite.tests;
    totals.skipped += suite.skipped;
    totals.failures += suite.failures;
    totals.errors += suite.errors;
    totals.timeSeconds += suite.timeSeconds;
    const current = modules.get(suite.module) || {
      module: suite.module,
      suites: 0,
      tests: 0,
      skipped: 0,
      failures: 0,
      errors: 0,
      timeSeconds: 0,
    };
    current.suites += 1;
    current.tests += suite.tests;
    current.skipped += suite.skipped;
    current.failures += suite.failures;
    current.errors += suite.errors;
    current.timeSeconds += suite.timeSeconds;
    modules.set(suite.module, current);
  }

  const rounded = (entry) => ({ ...entry, timeSeconds: round3(entry.timeSeconds) });
  return {
    kind: KIND,
    generatedAt: new Date().toISOString(),
    runner,
    totals: rounded(totals),
    modules: [...modules.values()]
      .map(rounded)
      .sort((a, b) => b.timeSeconds - a.timeSeconds || a.module.localeCompare(b.module)),
    slowestSuites: suites
      .map(rounded)
      .sort((a, b) => b.timeSeconds - a.timeSeconds || a.name.localeCompare(b.name))
      .slice(0, top),
  };
}

function fmtSeconds(n) {
  if (n >= 60) return `${Math.floor(n / 60)}m ${Math.round(n % 60)}s`;
  return `${round3(n)}s`;
}

export function renderMarkdown(report) {
  const lines = [
    '### Unit test attribution',
    '',
    `Generated: ${report.generatedAt}`,
    '',
    `Runner: ${report.runner.runnerLabel || 'unknown label'} / ${report.runner.runnerOs || 'unknown OS'} / image ${report.runner.imageOs || 'unknown'} ${report.runner.imageVersion || ''}`.trim(),
    '',
    `Total: ${report.totals.tests} tests, ${report.totals.skipped} skipped, ${report.totals.failures} failures, ${report.totals.errors} errors, ${report.totals.suites} suites, ${fmtSeconds(report.totals.timeSeconds)} summed suite time.`,
    '',
    '| Module | Suites | Tests | Skipped | Failures | Errors | Suite time |',
    '|---|---:|---:|---:|---:|---:|---:|',
  ];
  for (const module of report.modules) {
    lines.push(`| ${module.module} | ${module.suites} | ${module.tests} | ${module.skipped} | ${module.failures} | ${module.errors} | ${fmtSeconds(module.timeSeconds)} |`);
  }
  lines.push('', '| Slow suite | Module | Tests | Skipped | Failures | Errors | Time |', '|---|---|---:|---:|---:|---:|---:|');
  for (const suite of report.slowestSuites) {
    lines.push(`| ${suite.name} | ${suite.module} | ${suite.tests} | ${suite.skipped} | ${suite.failures} | ${suite.errors} | ${fmtSeconds(suite.timeSeconds)} |`);
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
    const root = path.resolve(opts.resultsRoot || repoRootFromCwd());
    const report = buildReport({ root, top: opts.top, runner: runnerInfo(opts) });
    const json = `${JSON.stringify(report, null, 2)}\n`;
    const md = renderMarkdown(report);

    if (report.totals.suites === 0 && !opts.allowEmpty) {
      console.error(`report-unit-test-attribution: no JUnit XML files found under ${root}`);
      process.exitCode = 1;
    }
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
    console.error(`report-unit-test-attribution: ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
