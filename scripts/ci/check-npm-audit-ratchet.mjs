#!/usr/bin/env node
/**
 * Enforce npm audit ratchet policy for selected severities.
 *
 * Contract:
 *   - Inputs: --report, --baseline, --severities, --mode warn|gate, --out, --write-baseline
 *   - Output schema: npm-audit-ratchet-report.v1
 *   - Exit non-zero only when mode=gate and selected-severity increases are detected
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const SEVERITY_ORDER = ['info', 'low', 'moderate', 'high', 'critical', 'total'];

function usage(code = 1) {
  process.stderr.write(
    [
      'Usage: node scripts/ci/check-npm-audit-ratchet.mjs [options]',
      '',
      'Options:',
      '  --report <path>         npm audit report JSON path (default: tmp/npm-audit-report.json)',
      '  --baseline <path>       Ratchet baseline JSON path (default: scripts/ci/npm-audit-ratchet-baseline.v1.json)',
      '  --severities <csv>      Severities to gate (default: high,critical)',
      '  --mode <warn|gate>      Enforcement mode (default: warn)',
      '  --out <path>            JSON report path (default: tmp/npm-audit-ratchet-report.json)',
      '  --write-baseline        Write baseline from current report and exit success',
      '  -h, --help',
      '',
    ].join('\n'),
  );
  process.exit(code);
}

function parseArgs(argv) {
  const out = {
    report: 'tmp/npm-audit-report.json',
    baseline: 'scripts/ci/npm-audit-ratchet-baseline.v1.json',
    severities: ['high', 'critical'],
    mode: 'warn',
    out: 'tmp/npm-audit-ratchet-report.json',
    writeBaseline: false,
  };
  const args = [...argv];
  const takeValue = (i) => {
    const token = args[i];
    const eq = token.indexOf('=');
    if (eq !== -1) return token.slice(eq + 1);
    return args[i + 1] ?? null;
  };
  const consumed = new Set();
  for (let i = 0; i < args.length; i += 1) {
    if (consumed.has(i)) continue;
    const token = args[i];
    if (token === '-h' || token === '--help') usage(0);
    if (!token.startsWith('--')) continue;
    const key = token.split('=')[0];
    const inline = token.includes('=');
    const value = takeValue(i);
    if (!inline) consumed.add(i + 1);
    switch (key) {
      case '--report':
        out.report = String(value || '').trim();
        break;
      case '--baseline':
        out.baseline = String(value || '').trim();
        break;
      case '--severities':
        out.severities = String(value || '')
          .split(',')
          .map((part) => part.trim().toLowerCase())
          .filter(Boolean);
        break;
      case '--mode':
        out.mode = String(value || '').trim().toLowerCase();
        break;
      case '--out':
        out.out = String(value || '').trim();
        break;
      case '--write-baseline':
        out.writeBaseline = true;
        if (!inline) consumed.delete(i + 1);
        break;
      default:
        throw new Error(`Unknown option: ${token}`);
    }
  }
  if (!out.report) throw new Error('Missing --report');
  if (!out.baseline) throw new Error('Missing --baseline');
  if (!out.out) throw new Error('Missing --out');
  if (!['warn', 'gate'].includes(out.mode)) throw new Error(`Invalid --mode: ${out.mode}`);
  if (!Array.isArray(out.severities) || out.severities.length === 0) {
    throw new Error('No severities selected');
  }
  for (const severity of out.severities) {
    if (!SEVERITY_ORDER.includes(severity)) {
      throw new Error(`Unsupported severity: ${severity}`);
    }
  }
  return out;
}

function emptyCounts() {
  return { info: 0, low: 0, moderate: 0, high: 0, critical: 0, total: 0 };
}

function normalizeCounts(value) {
  const out = { ...emptyCounts() };
  if (value && typeof value === 'object') {
    for (const key of SEVERITY_ORDER) {
      const numeric = Number(value[key]);
      if (Number.isFinite(numeric) && numeric >= 0) out[key] = numeric;
    }
  }
  return out;
}

function toPosix(relPath) {
  return String(relPath).split(path.sep).join('/');
}

async function readJson(absPath) {
  const raw = await fs.readFile(absPath, 'utf8');
  return JSON.parse(raw);
}

async function writeJson(absPath, payload) {
  await fs.mkdir(path.dirname(absPath), { recursive: true });
  await fs.writeFile(absPath, `${JSON.stringify(payload, null, 2)}\n`, 'utf8');
}

function buildBaselineFromReport(report, sourcePath) {
  const targets = {};
  for (const target of Array.isArray(report?.targets) ? report.targets : []) {
    const targetId = String(target?.target_id || '').trim();
    if (!targetId) continue;
    targets[targetId] = normalizeCounts(target?.vulnerabilities);
  }
  const aggregate = Object.values(targets).reduce((acc, counts) => {
    const next = { ...acc };
    for (const key of SEVERITY_ORDER) {
      next[key] += Number(counts[key] || 0);
    }
    return next;
  }, emptyCounts());
  return {
    schema: 'npm-audit-ratchet-baseline.v1',
    generated_at: new Date().toISOString(),
    source_report: sourcePath,
    targets,
    aggregate,
  };
}

function normalizeBaseline(rawBaseline) {
  const targets = {};
  if (rawBaseline?.targets && typeof rawBaseline.targets === 'object') {
    for (const [targetId, counts] of Object.entries(rawBaseline.targets)) {
      targets[targetId] = normalizeCounts(counts);
    }
  }
  const aggregate = normalizeCounts(rawBaseline?.aggregate);
  return {
    schema: 'npm-audit-ratchet-baseline.v1',
    targets,
    aggregate,
  };
}

function normalizeCurrentReport(rawReport) {
  const targets = {};
  for (const row of Array.isArray(rawReport?.targets) ? rawReport.targets : []) {
    const targetId = String(row?.target_id || '').trim();
    if (!targetId) continue;
    targets[targetId] = normalizeCounts(row?.vulnerabilities);
  }
  const aggregate = Object.values(targets).reduce((acc, counts) => {
    const next = { ...acc };
    for (const key of SEVERITY_ORDER) {
      next[key] += Number(counts[key] || 0);
    }
    return next;
  }, emptyCounts());
  return { targets, aggregate };
}

function compareSeveritySet({ baseline, current, severities }) {
  const allTargetIds = [...new Set([...Object.keys(baseline.targets), ...Object.keys(current.targets)])].sort();
  const targetRows = [];
  const violations = [];

  for (const targetId of allTargetIds) {
    const baselineCounts = normalizeCounts(baseline.targets[targetId]);
    const currentCounts = normalizeCounts(current.targets[targetId]);
    const severityRows = [];
    for (const severity of severities) {
      const baseVal = Number(baselineCounts[severity] || 0);
      const curVal = Number(currentCounts[severity] || 0);
      const delta = curVal - baseVal;
      const violates = delta > 0;
      severityRows.push({
        severity,
        baseline: baseVal,
        current: curVal,
        delta,
        status: violates ? 'FAIL' : 'PASS',
      });
      if (violates) {
        violations.push({
          kind: 'severity_increase',
          target: targetId,
          severity,
          baseline: baseVal,
          current: curVal,
          delta,
          message: `${targetId} ${severity} increased by ${delta}`,
        });
      }
    }
    targetRows.push({
      target: targetId,
      baseline: baselineCounts,
      current: currentCounts,
      severities: severityRows,
      status: severityRows.some((row) => row.status === 'FAIL') ? 'FAIL' : 'PASS',
    });
  }

  const aggregateRows = severities.map((severity) => {
    const baseVal = Number(baseline.aggregate[severity] || 0);
    const curVal = Number(current.aggregate[severity] || 0);
    const delta = curVal - baseVal;
    const status = delta > 0 ? 'FAIL' : 'PASS';
    if (delta > 0) {
      violations.push({
        kind: 'aggregate_severity_increase',
        target: 'aggregate',
        severity,
        baseline: baseVal,
        current: curVal,
        delta,
        message: `aggregate ${severity} increased by ${delta}`,
      });
    }
    return { severity, baseline: baseVal, current: curVal, delta, status };
  });

  return {
    targets: targetRows,
    aggregate: aggregateRows,
    violations,
  };
}

function buildMarkdown(report) {
  const lines = [
    '## NPM Audit Ratchet Gate',
    '',
    `Mode: \`${report.mode}\``,
    `Severities: \`${report.severities.join(',')}\``,
    '',
    '| Target | Severity | Baseline | Current | Delta | Status |',
    '|---|---|---:|---:|---:|---|',
  ];
  for (const target of report.targets) {
    for (const row of target.severities) {
      lines.push(
        `| ${target.target} | ${row.severity} | ${row.baseline} | ${row.current} | ${row.delta} | ${row.status} |`,
      );
    }
  }
  lines.push('');
  lines.push('| Aggregate severity | Baseline | Current | Delta | Status |');
  lines.push('|---|---:|---:|---:|---|');
  for (const row of report.aggregate) {
    lines.push(`| ${row.severity} | ${row.baseline} | ${row.current} | ${row.delta} | ${row.status} |`);
  }
  lines.push('', `Verdict: **${report.verdict.toUpperCase()}**`);
  if (report.violations.length > 0) {
    lines.push('', 'Violations:');
    for (const violation of report.violations) {
      lines.push(`- ${violation.message}`);
    }
  }
  return `${lines.join('\n')}\n`;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const cwd = process.cwd();
  const reportAbs = path.resolve(cwd, args.report);
  const baselineAbs = path.resolve(cwd, args.baseline);
  const outAbs = path.resolve(cwd, args.out);

  const rawReport = await readJson(reportAbs);
  if (rawReport?.schema !== 'npm-audit-report.v1') {
    throw new Error(`Unsupported report schema in ${args.report}: expected npm-audit-report.v1`);
  }

  if (args.writeBaseline) {
    const baselineDoc = buildBaselineFromReport(rawReport, toPosix(path.relative(cwd, reportAbs)));
    await writeJson(baselineAbs, baselineDoc);
    process.stdout.write(`Wrote npm audit ratchet baseline to ${baselineAbs}\n`);
    process.exit(0);
  }

  const rawBaseline = await readJson(baselineAbs);
  if (rawBaseline?.schema !== 'npm-audit-ratchet-baseline.v1') {
    throw new Error(`Unsupported baseline schema in ${args.baseline}: expected npm-audit-ratchet-baseline.v1`);
  }

  const baseline = normalizeBaseline(rawBaseline);
  const current = normalizeCurrentReport(rawReport);
  const comparison = compareSeveritySet({
    baseline,
    current,
    severities: args.severities,
  });

  const report = {
    schema: 'npm-audit-ratchet-report.v1',
    generated_at: new Date().toISOString(),
    mode: args.mode,
    severities: args.severities,
    baseline_path: toPosix(path.relative(cwd, baselineAbs)),
    report_path: toPosix(path.relative(cwd, reportAbs)),
    targets: comparison.targets,
    aggregate: comparison.aggregate,
    violations: comparison.violations,
    verdict: comparison.violations.length === 0 ? 'pass' : 'fail',
  };

  await writeJson(outAbs, report);
  const markdown = buildMarkdown(report);
  if (process.env.GITHUB_STEP_SUMMARY) {
    await fs.appendFile(process.env.GITHUB_STEP_SUMMARY, markdown, 'utf8');
  }
  process.stdout.write(markdown);
  process.stdout.write(`Saved JSON report to ${outAbs}\n`);

  if (args.mode === 'gate' && report.verdict === 'fail') {
    process.exit(1);
  }
  process.exit(0);
}

main().catch((error) => {
  const modeArgIndex = process.argv.findIndex((arg) => arg === '--mode' || arg.startsWith('--mode='));
  const modeFromArgs = (() => {
    if (modeArgIndex < 0) return 'warn';
    const token = process.argv[modeArgIndex];
    if (token.includes('=')) return token.split('=')[1].trim().toLowerCase();
    return String(process.argv[modeArgIndex + 1] || 'warn').trim().toLowerCase();
  })();
  const message = error instanceof Error ? (error.stack || error.message) : String(error);
  process.stderr.write(`[check-npm-audit-ratchet] ${message}\n`);
  if (modeFromArgs === 'gate') {
    process.exit(1);
  }
  process.exit(0);
});

