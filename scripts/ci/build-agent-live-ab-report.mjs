#!/usr/bin/env node

import fsp from 'node:fs/promises';
import path from 'node:path';

function usage() {
  console.error(
    'Usage: node scripts/ci/build-agent-live-ab-report.mjs ' +
      '--baseline-dir <dir> --candidate-dir <dir> --out-json <path> --out-md <path> ' +
      '[--baseline-label <label>] [--candidate-label <label>] [--token-gate <ratio>] ' +
      '[--critical-codes <csv>]',
  );
  process.exit(2);
}

function parseArgs(argv) {
  const out = {
    baselineDir: null,
    candidateDir: null,
    outJson: null,
    outMd: null,
    baselineLabel: 'off',
    candidateLabel: 'on',
    tokenGate: 0.2,
    criticalCodes: ['EMPTY_RESPONSE', 'TOOL_LOOP', 'TOOL_CONTRACT'],
  };

  for (let i = 2; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--baseline-dir' && argv[i + 1]) out.baselineDir = argv[++i];
    else if (arg === '--candidate-dir' && argv[i + 1]) out.candidateDir = argv[++i];
    else if (arg === '--out-json' && argv[i + 1]) out.outJson = argv[++i];
    else if (arg === '--out-md' && argv[i + 1]) out.outMd = argv[++i];
    else if (arg === '--baseline-label' && argv[i + 1]) out.baselineLabel = argv[++i];
    else if (arg === '--candidate-label' && argv[i + 1]) out.candidateLabel = argv[++i];
    else if (arg === '--token-gate' && argv[i + 1]) out.tokenGate = Number.parseFloat(argv[++i]);
    else if (arg === '--critical-codes' && argv[i + 1]) {
      out.criticalCodes = String(argv[++i])
        .split(',')
        .map((v) => v.trim())
        .filter(Boolean);
    } else if (arg === '--help') usage();
    else usage();
  }

  if (!out.baselineDir || !out.candidateDir || !out.outJson || !out.outMd) usage();
  if (!Number.isFinite(out.tokenGate) || out.tokenGate < 0 || out.tokenGate > 1) usage();
  return out;
}

function mean(values) {
  if (values.length === 0) return 0;
  return values.reduce((a, b) => a + b, 0) / values.length;
}

function median(values) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

function percentile(values, p) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.floor((sorted.length - 1) * p);
  return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
}

function toNumberOrNull(v) {
  return Number.isFinite(Number(v)) ? Number(v) : null;
}

function repFromName(name) {
  const m = String(name).match(/-r(\d+)-/);
  return m ? Number.parseInt(m[1], 10) : null;
}

async function loadManifestDir(dirPath) {
  const abs = path.resolve(dirPath);
  let entries;
  try {
    entries = await fsp.readdir(abs, { withFileTypes: true });
  } catch {
    return [];
  }

  const out = [];
  for (const entry of entries) {
    if (!entry.isFile()) continue;
    if (!entry.name.endsWith('.json')) continue;
    const fullPath = path.join(abs, entry.name);
    try {
      const doc = JSON.parse(await fsp.readFile(fullPath, 'utf8'));
      if (doc?.kind !== 'agent-live-battery-manifest.v1') continue;
      out.push({
        file: entry.name,
        absPath: fullPath,
        relPath: path.relative(process.cwd(), fullPath).split(path.sep).join('/'),
        rep: repFromName(entry.name),
        doc,
      });
    } catch {
      // ignore malformed files
    }
  }

  out.sort((a, b) => a.file.localeCompare(b.file));
  return out;
}

function scenarioMap(doc) {
  const scenarios = Array.isArray(doc?.scenarios) ? doc.scenarios : [];
  return new Map(scenarios.map((s) => [s.id, s]));
}

function eventIsCritical(reason, criticalCodes) {
  const text = String(reason || '');
  return criticalCodes.some((code) => text.includes(code));
}

function buildMarkdown(report) {
  const lines = [];
  lines.push('# Agent Live A/B Report');
  lines.push('');
  lines.push(`Generated: ${report.generatedAt}`);
  lines.push(`Baseline: ${report.labels.baseline}`);
  lines.push(`Candidate: ${report.labels.candidate}`);
  lines.push(`Paired repetitions: ${report.aggregate.repetitions}`);
  lines.push('');
  lines.push('| Metric | Baseline | Candidate | Gate |');
  lines.push('|---|---:|---:|---|');
  lines.push(
    `| Pass rate | ${(100 * Number(report.aggregate.baseline.passRate || 0)).toFixed(1)}% | ${(100 * Number(report.aggregate.candidate.passRate || 0)).toFixed(1)}% | ${report.gates.qualityNonRegression ? 'PASS' : 'FAIL'} |`,
  );
  lines.push(
    `| Infra failures | ${report.aggregate.baseline.infraFailures} | ${report.aggregate.candidate.infraFailures} | n/a |`,
  );
  lines.push(
    `| Critical failures | ${report.aggregate.criticalFailures.baseline} | ${report.aggregate.criticalFailures.candidate} | ${report.gates.noCriticalFailureIncrease ? 'PASS' : 'FAIL'} |`,
  );
  lines.push(
    `| Token reduction median | n/a | ${(100 * Number(report.aggregate.tokenReduction.median || 0)).toFixed(1)}% | ${report.gates.tokenReductionMedian ? 'PASS' : 'FAIL'} (>= ${(100 * report.thresholds.tokenReductionMedianMin).toFixed(1)}%) |`,
  );
  lines.push('');
  lines.push(`Overall gate: **${report.gates.allPassed ? 'PASS' : 'FAIL'}**`);
  lines.push('');
  lines.push('## Pair Details');
  lines.push('');
  for (const pair of report.pairs) {
    const tokenText =
      pair.tokenReductionMean == null
        ? 'n/a'
        : `${(100 * pair.tokenReductionMean).toFixed(1)}%`;
    lines.push(
      `- rep ${pair.rep}: ${report.labels.baseline} ${(100 * pair.baselinePassRate).toFixed(1)}% vs ${report.labels.candidate} ${(100 * pair.candidatePassRate).toFixed(1)}%, paired token reduction mean ${tokenText}`,
    );
  }
  return `${lines.join('\n')}\n`;
}

async function writeJson(filePath, obj) {
  const abs = path.resolve(filePath);
  await fsp.mkdir(path.dirname(abs), { recursive: true });
  await fsp.writeFile(abs, `${JSON.stringify(obj, null, 2)}\n`, 'utf8');
}

async function writeText(filePath, text) {
  const abs = path.resolve(filePath);
  await fsp.mkdir(path.dirname(abs), { recursive: true });
  await fsp.writeFile(abs, text, 'utf8');
}

async function main() {
  const args = parseArgs(process.argv);
  const baselineRuns = await loadManifestDir(args.baselineDir);
  const candidateRuns = await loadManifestDir(args.candidateDir);

  const baselineByRep = new Map(
    baselineRuns.filter((r) => Number.isFinite(r.rep)).map((r) => [r.rep, r]),
  );
  const candidateByRep = new Map(
    candidateRuns.filter((r) => Number.isFinite(r.rep)).map((r) => [r.rep, r]),
  );
  const reps = [...new Set([...baselineByRep.keys(), ...candidateByRep.keys()])].sort(
    (a, b) => a - b,
  );

  let baselinePassed = 0;
  let candidatePassed = 0;
  let scenarioTotal = 0;
  let baselineInfraFailures = 0;
  let candidateInfraFailures = 0;
  let baselineCriticalFailures = 0;
  let candidateCriticalFailures = 0;
  const tokenReductions = [];
  const pairs = [];

  for (const rep of reps) {
    const baseline = baselineByRep.get(rep);
    const candidate = candidateByRep.get(rep);
    if (!baseline || !candidate) continue;

    const bDoc = baseline.doc;
    const cDoc = candidate.doc;

    baselineInfraFailures += bDoc?.aggregate?.infraFailure ? 1 : 0;
    candidateInfraFailures += cDoc?.aggregate?.infraFailure ? 1 : 0;
    baselinePassed += Number(bDoc?.aggregate?.passed || 0);
    candidatePassed += Number(cDoc?.aggregate?.passed || 0);
    scenarioTotal += Number(bDoc?.aggregate?.total || 0);

    const bMap = scenarioMap(bDoc);
    const cMap = scenarioMap(cDoc);
    const ids = [...new Set([...bMap.keys(), ...cMap.keys()])];
    let pairTokenComparisons = 0;
    let pairTokenReductionSum = 0;

    for (const id of ids) {
      const b = bMap.get(id) || {};
      const c = cMap.get(id) || {};

      if (eventIsCritical(b.reason, args.criticalCodes)) baselineCriticalFailures += 1;
      if (eventIsCritical(c.reason, args.criticalCodes)) candidateCriticalFailures += 1;

      const bTokens = toNumberOrNull(b.totalTokensUsed);
      const cTokens = toNumberOrNull(c.totalTokensUsed);
      // Only count token reduction for pass/pass pairs — error-path scenarios
      // have anomalous token counts that skew the median measurement.
      if (b.status === 'pass' && c.status === 'pass' && bTokens != null && bTokens > 0 && cTokens != null) {
        const reduction = (bTokens - cTokens) / bTokens;
        tokenReductions.push(reduction);
        pairTokenComparisons += 1;
        pairTokenReductionSum += reduction;
      }
    }

    pairs.push({
      rep,
      baselineFile: baseline.file,
      candidateFile: candidate.file,
      baselinePath: baseline.relPath,
      candidatePath: candidate.relPath,
      baselinePassRate: Number(bDoc?.aggregate?.passRate || 0),
      candidatePassRate: Number(cDoc?.aggregate?.passRate || 0),
      tokenComparisons: pairTokenComparisons,
      tokenReductionMean:
        pairTokenComparisons > 0 ? pairTokenReductionSum / pairTokenComparisons : null,
    });
  }

  const aggregate = {
    repetitions: pairs.length,
    scenariosPerRun: pairs.length > 0 ? scenarioTotal / pairs.length : null,
    baseline: {
      passed: baselinePassed,
      total: scenarioTotal,
      passRate: scenarioTotal > 0 ? baselinePassed / scenarioTotal : null,
      infraFailures: baselineInfraFailures,
    },
    candidate: {
      passed: candidatePassed,
      total: scenarioTotal,
      passRate: scenarioTotal > 0 ? candidatePassed / scenarioTotal : null,
      infraFailures: candidateInfraFailures,
    },
    criticalFailures: {
      baseline: baselineCriticalFailures,
      candidate: candidateCriticalFailures,
    },
    tokenReduction: {
      comparisons: tokenReductions.length,
      mean: mean(tokenReductions),
      median: median(tokenReductions),
      p25: percentile(tokenReductions, 0.25),
      p75: percentile(tokenReductions, 0.75),
    },
  };

  const gates = {
    tokenReductionMedian: Number(aggregate.tokenReduction.median || 0) >= args.tokenGate,
    qualityNonRegression:
      Number(aggregate.candidate.passRate || 0) >= Number(aggregate.baseline.passRate || 0),
    noCriticalFailureIncrease:
      Number(aggregate.criticalFailures.candidate || 0) <=
      Number(aggregate.criticalFailures.baseline || 0),
  };
  gates.allPassed = Object.values(gates).every((v) => v === true);

  const report = {
    kind: 'agent-live-ab-report.v1',
    generatedAt: new Date().toISOString(),
    labels: {
      baseline: args.baselineLabel,
      candidate: args.candidateLabel,
    },
    inputs: {
      baselineDir: path.relative(process.cwd(), path.resolve(args.baselineDir)).split(path.sep).join('/'),
      candidateDir: path.relative(process.cwd(), path.resolve(args.candidateDir)).split(path.sep).join('/'),
    },
    thresholds: {
      tokenReductionMedianMin: args.tokenGate,
      criticalCodes: args.criticalCodes,
    },
    aggregate,
    gates,
    pairs,
  };

  await writeJson(args.outJson, report);
  await writeText(args.outMd, buildMarkdown(report));
  console.log(
    JSON.stringify(
      {
        ok: true,
        outJson: path.resolve(args.outJson),
        outMd: path.resolve(args.outMd),
        gates,
      },
      null,
      2,
    ),
  );
}

main().catch((err) => {
  console.error(err?.stack || String(err));
  process.exit(1);
});
