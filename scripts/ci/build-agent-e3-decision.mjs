#!/usr/bin/env node

import fsp from 'node:fs/promises';
import path from 'node:path';

function usage() {
  console.error(
    'Usage: node scripts/ci/build-agent-e3-decision.mjs ' +
      '--baseline-scorecard <path> --candidate-scorecard <path> ' +
      '--baseline-judge-dir <path> --candidate-judge-dir <path> ' +
      '--candidate-label <label> --out-json <path> --out-md <path> ' +
      '[--baseline-label <label>] [--passk-threshold <n>] [--latency-regression-max <n>]',
  );
  process.exit(2);
}

function parseArgs(argv) {
  const out = {
    baselineScorecard: null,
    candidateScorecard: null,
    baselineJudgeDir: null,
    candidateJudgeDir: null,
    baselineLabel: '0',
    candidateLabel: null,
    outJson: null,
    outMd: null,
    passKThreshold: 0.75,
    latencyRegressionMax: 0.30,
  };

  for (let i = 2; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--baseline-scorecard' && argv[i + 1]) out.baselineScorecard = argv[++i];
    else if (arg === '--candidate-scorecard' && argv[i + 1]) out.candidateScorecard = argv[++i];
    else if (arg === '--baseline-judge-dir' && argv[i + 1]) out.baselineJudgeDir = argv[++i];
    else if (arg === '--candidate-judge-dir' && argv[i + 1]) out.candidateJudgeDir = argv[++i];
    else if (arg === '--baseline-label' && argv[i + 1]) out.baselineLabel = String(argv[++i] || '').trim();
    else if (arg === '--candidate-label' && argv[i + 1]) out.candidateLabel = String(argv[++i] || '').trim();
    else if (arg === '--out-json' && argv[i + 1]) out.outJson = argv[++i];
    else if (arg === '--out-md' && argv[i + 1]) out.outMd = argv[++i];
    else if (arg === '--passk-threshold' && argv[i + 1]) out.passKThreshold = Number(argv[++i]);
    else if (arg === '--latency-regression-max' && argv[i + 1]) out.latencyRegressionMax = Number(argv[++i]);
    else if (arg === '--help') usage();
    else usage();
  }

  if (
    !out.baselineScorecard
    || !out.candidateScorecard
    || !out.baselineJudgeDir
    || !out.candidateJudgeDir
    || !out.candidateLabel
    || !out.outJson
    || !out.outMd
  ) {
    usage();
  }
  if (!Number.isFinite(out.passKThreshold) || out.passKThreshold < 0 || out.passKThreshold > 1) usage();
  if (
    !Number.isFinite(out.latencyRegressionMax)
    || out.latencyRegressionMax < 0
    || out.latencyRegressionMax > 10
  ) {
    usage();
  }
  return out;
}

async function readJson(filePath) {
  const raw = await fsp.readFile(filePath, 'utf8');
  return JSON.parse(raw);
}

async function writeJson(filePath, obj) {
  await fsp.mkdir(path.dirname(filePath), { recursive: true });
  await fsp.writeFile(filePath, `${JSON.stringify(obj, null, 2)}\n`, 'utf8');
}

async function writeText(filePath, text) {
  await fsp.mkdir(path.dirname(filePath), { recursive: true });
  await fsp.writeFile(filePath, text, 'utf8');
}

function toNumber(value, fallback = null) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string') {
    const n = Number(value);
    if (Number.isFinite(n)) return n;
  }
  return fallback;
}

function safeRel(filePath) {
  return path.relative(process.cwd(), path.resolve(filePath)).split(path.sep).join('/');
}

function summarizeScorecard(doc) {
  const process = doc?.metrics?.process || {};
  const families = Array.isArray(process?.stratifiedReliability?.families)
    ? process.stratifiedReliability.families
    : [];
  const handoff = families.find((row) => String(row?.family || '') === 'handoff') || null;

  return {
    scorecardPath: doc?.historyDir ? String(doc.historyDir) : null,
    totalScenarios: toNumber(process?.totalScenarios, 0),
    infraFailureRate: toNumber(doc?.metrics?.infraFailureRate, 0),
    passPowK: toNumber(handoff?.passPowK, toNumber(process?.passKReliability?.passPowK, null)),
    passK: toNumber(process?.passKReliability?.k, toNumber(process?.stratifiedReliability?.k, null)),
    deterministicWouldFailStrictRate: toNumber(
      process?.deterministicCheckSummary?.wouldFailStrictRate,
      null,
    ),
    deterministicEvaluableScenarios: toNumber(
      process?.deterministicCheckSummary?.evaluableScenarios,
      0,
    ),
    latencyP50Ms: toNumber(process?.latencyPercentilesMs?.p50, null),
    latencyP90Ms: toNumber(process?.latencyPercentilesMs?.p90, null),
    latencyP99Ms: toNumber(process?.latencyPercentilesMs?.p99, null),
  };
}

function mean(values) {
  if (!values.length) return null;
  return values.reduce((sum, v) => sum + v, 0) / values.length;
}

async function walkJsonFiles(rootDir) {
  const out = [];
  async function walk(current) {
    let entries = [];
    try {
      entries = await fsp.readdir(current, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      const abs = path.join(current, entry.name);
      if (entry.isDirectory()) {
        // eslint-disable-next-line no-await-in-loop
        await walk(abs);
      } else if (entry.isFile() && entry.name.endsWith('.json')) {
        out.push(abs);
      }
    }
  }
  await walk(rootDir);
  return out.sort((a, b) => a.localeCompare(b));
}

function scoreRubricOutput(output) {
  const criteria = Array.isArray(output?.criteria) ? output.criteria : [];
  if (criteria.length > 0) {
    const numeric = criteria
      .map((row) => toNumber(row?.score, null))
      .filter((row) => row != null);
    if (numeric.length > 0) {
      return mean(numeric);
    }
  }
  const overall = String(output?.overall || '').trim().toLowerCase();
  if (overall === 'pass') return 1;
  if (overall === 'fail') return 0;
  return null;
}

async function summarizeJudgeDir(judgeDir) {
  const files = await walkJsonFiles(judgeDir);
  const summary = {
    dir: safeRel(judgeDir),
    totalArtifacts: 0,
    primaryArtifacts: 0,
    evaluatedCount: 0,
    skippedPrecheckCount: 0,
    errorCount: 0,
    passCount: 0,
    failCount: 0,
    meanScore: null,
    scoredScenarioCount: 0,
  };
  const scoreValues = [];
  const scoredScenarios = new Set();

  for (const abs of files) {
    let doc;
    try {
      // eslint-disable-next-line no-await-in-loop
      doc = await readJson(abs);
    } catch {
      continue;
    }
    if (doc?.kind !== 'agent-run-judge.v1') continue;
    summary.totalArtifacts += 1;
    if (String(doc?.rubric || '') !== 'primary') continue;
    summary.primaryArtifacts += 1;
    if (doc?.skippedDueToDeterministicPrecheck === true) {
      summary.skippedPrecheckCount += 1;
      continue;
    }
    const judge = doc?.judge || {};
    if (judge?.ok !== true || !judge?.output) {
      summary.errorCount += 1;
      continue;
    }
    summary.evaluatedCount += 1;
    const overall = String(judge?.output?.overall || '').trim().toLowerCase();
    if (overall === 'pass') summary.passCount += 1;
    else summary.failCount += 1;
    const score = scoreRubricOutput(judge.output);
    if (score != null) scoreValues.push(score);
    if (doc?.scenarioId) scoredScenarios.add(String(doc.scenarioId));
  }

  summary.meanScore = mean(scoreValues);
  summary.scoredScenarioCount = scoredScenarios.size;
  return summary;
}

async function summarizeManifestErrorsFromScorecard(scorecardDoc) {
  const historyDir = scorecardDoc?.historyDir ? path.resolve(String(scorecardDoc.historyDir)) : null;
  const out = {
    historyDir: historyDir ? safeRel(historyDir) : null,
    manifestErrorCounts: {},
    reasoningBudgetUnsupportedCount: 0,
  };
  if (!historyDir) return out;

  let files = [];
  try {
    files = await fsp.readdir(historyDir, { withFileTypes: true });
  } catch {
    return out;
  }

  for (const entry of files) {
    if (!entry.isFile() || !entry.name.endsWith('.json')) continue;
    const abs = path.join(historyDir, entry.name);
    let doc;
    try {
      // eslint-disable-next-line no-await-in-loop
      doc = await readJson(abs);
    } catch {
      continue;
    }
    if (doc?.kind !== 'agent-live-battery-manifest.v1') continue;
    const errors = Array.isArray(doc?.errors) ? doc.errors : [];
    for (const err of errors) {
      const key = String(err || '').trim();
      if (!key) continue;
      out.manifestErrorCounts[key] = (out.manifestErrorCounts[key] || 0) + 1;
    }

    const dataDir = typeof doc?.run?.dataDir === 'string' ? doc.run.dataDir : null;
    if (!dataDir) continue;
    const llamaLog = path.join(dataDir, 'logs', 'llama-server.log');
    try {
      // eslint-disable-next-line no-await-in-loop
      const text = await fsp.readFile(llamaLog, 'utf8');
      if (text.includes('error while handling argument "--reasoning-budget": invalid value')) {
        out.reasoningBudgetUnsupportedCount += 1;
      }
    } catch {
      // ignore missing logs
    }
  }

  return out;
}

function pct(value) {
  if (value == null || !Number.isFinite(value)) return 'n/a';
  return `${(value * 100).toFixed(1)}%`;
}

function ms(value) {
  if (value == null || !Number.isFinite(value)) return 'n/a';
  return `${Math.round(value)} ms`;
}

function buildMarkdown(report) {
  const checks = Array.isArray(report?.checks) ? report.checks : [];
  const lines = [];
  lines.push('# Agent E3 Decision');
  lines.push('');
  lines.push(`Generated: ${report.generatedAt}`);
  lines.push(`Mode: ${report.mode}`);
  lines.push(`Baseline: ${report.baseline.label}`);
  lines.push(`Candidate: ${report.candidate.label}`);
  lines.push('');
  lines.push('| Check | Baseline | Candidate | Threshold | Status |');
  lines.push('|---|---:|---:|---:|---|');
  for (const check of checks) {
    lines.push(
      `| ${check.name} | ${check.baselineDisplay} | ${check.candidateDisplay} | ${check.thresholdDisplay} | ${check.passed ? 'PASS' : 'FAIL'} |`,
    );
  }
  lines.push('');
  lines.push(`wouldFailE3Gate: **${report.wouldFailE3Gate ? 'true' : 'false'}**`);
  lines.push(`Recommendation: **${report.recommendation}**`);
  lines.push('');
  lines.push('## Failure Reasons');
  lines.push('');
  if (report.failureReasons.length === 0) {
    lines.push('- none');
  } else {
    for (const reason of report.failureReasons) {
      lines.push(`- ${reason}`);
    }
  }
  lines.push('');
  lines.push('## Evidence');
  lines.push('');
  lines.push(`- Baseline scorecard: \`${report.baseline.scorecardPath}\``);
  lines.push(`- Candidate scorecard: \`${report.candidate.scorecardPath}\``);
  lines.push(`- Baseline judge dir: \`${report.baseline.judgeSummary.dir}\``);
  lines.push(`- Candidate judge dir: \`${report.candidate.judgeSummary.dir}\``);
  return `${lines.join('\n')}\n`;
}

async function main() {
  const args = parseArgs(process.argv);
  const baselineScorecardPath = path.resolve(args.baselineScorecard);
  const candidateScorecardPath = path.resolve(args.candidateScorecard);
  const baselineJudgeDir = path.resolve(args.baselineJudgeDir);
  const candidateJudgeDir = path.resolve(args.candidateJudgeDir);

  const [baselineDoc, candidateDoc, baselineJudge, candidateJudge] = await Promise.all([
    readJson(baselineScorecardPath),
    readJson(candidateScorecardPath),
    summarizeJudgeDir(baselineJudgeDir),
    summarizeJudgeDir(candidateJudgeDir),
  ]);

  const baseline = summarizeScorecard(baselineDoc);
  const candidate = summarizeScorecard(candidateDoc);
  baseline.label = args.baselineLabel;
  candidate.label = args.candidateLabel;
  baseline.scorecardPath = safeRel(baselineScorecardPath);
  candidate.scorecardPath = safeRel(candidateScorecardPath);
  baseline.judgeSummary = baselineJudge;
  candidate.judgeSummary = candidateJudge;

  const candidateManifestErrors = await summarizeManifestErrorsFromScorecard(candidateDoc);

  const checks = [];

  const passKCheckPassed =
    candidate.passPowK != null && Number.isFinite(candidate.passPowK)
      ? candidate.passPowK >= args.passKThreshold
      : false;
  checks.push({
    id: 'handoff_pass_pow_k',
    name: `Handoff pass^${candidate.passK || baseline.passK || 5}`,
    passed: passKCheckPassed,
    baselineDisplay: pct(baseline.passPowK),
    candidateDisplay: pct(candidate.passPowK),
    thresholdDisplay: `>= ${pct(args.passKThreshold)}`,
  });

  const deterministicPassed =
    baseline.deterministicWouldFailStrictRate != null
      && candidate.deterministicWouldFailStrictRate != null
      && candidate.deterministicWouldFailStrictRate <= baseline.deterministicWouldFailStrictRate;
  checks.push({
    id: 'deterministic_no_regression',
    name: 'Deterministic strict-would-fail rate',
    passed: deterministicPassed,
    baselineDisplay: pct(baseline.deterministicWouldFailStrictRate),
    candidateDisplay: pct(candidate.deterministicWouldFailStrictRate),
    thresholdDisplay: '<= baseline',
  });

  const rubricComparable =
    baselineJudge.evaluatedCount > 0 && candidateJudge.evaluatedCount > 0;
  const rubricPassed =
    rubricComparable
      && candidateJudge.meanScore != null
      && baselineJudge.meanScore != null
      && candidateJudge.meanScore >= baselineJudge.meanScore;
  checks.push({
    id: 'primary_rubric_no_regression',
    name: 'PRIMARY rubric mean score',
    passed: rubricPassed,
    baselineDisplay: baselineJudge.meanScore == null ? 'n/a' : baselineJudge.meanScore.toFixed(3),
    candidateDisplay: candidateJudge.meanScore == null ? 'n/a' : candidateJudge.meanScore.toFixed(3),
    thresholdDisplay: '<= baseline regression not allowed',
  });

  let latencyIncreaseRatio = null;
  if (
    baseline.latencyP50Ms != null
    && baseline.latencyP50Ms > 0
    && candidate.latencyP50Ms != null
  ) {
    latencyIncreaseRatio = (candidate.latencyP50Ms - baseline.latencyP50Ms) / baseline.latencyP50Ms;
  }
  const latencyPassed =
    latencyIncreaseRatio != null ? latencyIncreaseRatio <= args.latencyRegressionMax : false;
  checks.push({
    id: 'latency_p50_regression',
    name: 'Latency p50 increase',
    passed: latencyPassed,
    baselineDisplay: ms(baseline.latencyP50Ms),
    candidateDisplay: ms(candidate.latencyP50Ms),
    thresholdDisplay: `<= ${(args.latencyRegressionMax * 100).toFixed(1)}%`,
  });

  const failureReasons = checks.filter((row) => !row.passed).map((row) => row.id);
  if (!rubricComparable) {
    failureReasons.push('primary_rubric_insufficient_comparable_evidence');
  }
  if (
    candidate.totalScenarios === 0
    && candidate.infraFailureRate >= 1
    && candidateManifestErrors.reasoningBudgetUnsupportedCount > 0
  ) {
    failureReasons.push('candidate_reasoning_budget_unsupported_by_runtime');
  }

  const dedupedFailureReasons = Array.from(new Set(failureReasons));
  const wouldFailE3Gate = dedupedFailureReasons.length > 0;
  const recommendation = wouldFailE3Gate ? 'defer' : 'ship';

  const out = {
    kind: 'agent-e3-decision.v1',
    generatedAt: new Date().toISOString(),
    mode: 'report',
    thresholds: {
      handoffPassPowKMin: args.passKThreshold,
      latencyIncreaseMax: args.latencyRegressionMax,
      deterministicNoRegression: true,
      primaryRubricNoRegression: true,
    },
    baseline,
    candidate,
    checks,
    candidateManifestErrorSummary: candidateManifestErrors,
    wouldFailE3Gate,
    failureReasons: dedupedFailureReasons,
    recommendation,
  };

  const markdown = buildMarkdown(out);
  await Promise.all([writeJson(path.resolve(args.outJson), out), writeText(path.resolve(args.outMd), markdown)]);

  console.log(
    JSON.stringify(
      {
        ok: true,
        outJson: path.resolve(args.outJson),
        outMd: path.resolve(args.outMd),
        wouldFailE3Gate,
        recommendation,
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
