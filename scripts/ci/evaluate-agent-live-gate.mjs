#!/usr/bin/env node

import fsp from 'node:fs/promises';
import path from 'node:path';

function usage() {
  console.error(
    'Usage: node scripts/ci/evaluate-agent-live-gate.mjs ' +
      '--scorecard <path> [--mode <A|B|C>] [--current-manifest <path>]',
  );
  process.exit(2);
}

function parseArgs(argv) {
  const out = {
    scorecard: null,
    mode: 'B',
    currentManifest: null,
  };
  for (let i = 2; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--scorecard' && argv[i + 1]) out.scorecard = argv[++i];
    else if (arg === '--mode' && argv[i + 1]) out.mode = String(argv[++i]).toUpperCase();
    else if (arg === '--current-manifest' && argv[i + 1]) out.currentManifest = argv[++i];
    else if (arg === '--help') usage();
    else usage();
  }
  if (!out.scorecard) usage();
  if (!['A', 'B', 'C'].includes(out.mode)) usage();
  return out;
}

async function readJson(filePath) {
  const abs = path.resolve(filePath);
  const text = await fsp.readFile(abs, 'utf8');
  return JSON.parse(text);
}

function formatBool(v) {
  return v ? 'PASS' : 'FAIL';
}

function summarize(scorecard, mode) {
  const lines = [];
  lines.push(`Mode: ${mode}`);
  lines.push(
    `Window: ${scorecard.window?.actualRuns ?? 0}/${scorecard.window?.targetRuns ?? 0}`,
  );
  lines.push(
    `Infra failure rate gate: ${formatBool(!!scorecard.gates?.infraFailureRate)} ` +
      `(${(100 * Number(scorecard.metrics?.infraFailureRate || 0)).toFixed(1)}%)`,
  );
  lines.push(
    `Pass-rate stddev gate: ${formatBool(!!scorecard.gates?.passRateStdDev)} ` +
      `(${(100 * Number(scorecard.metrics?.passRateStdDev || 0)).toFixed(1)}pp)`,
  );
  lines.push(
    `Scenario instability gate: ${formatBool(!!scorecard.gates?.scenarioInstability)} ` +
      `(${(100 * Number(scorecard.metrics?.scenarioInstabilityRate || 0)).toFixed(1)}%)`,
  );
  lines.push(`Runs-required gate: ${formatBool(!!scorecard.gates?.runsRequired)}`);
  return lines.join('\n');
}

function evaluate(scorecard, mode) {
  const gates = scorecard.gates || {};
  const failures = [];
  const warnings = [];

  if (mode === 'A') {
    return { ok: true, failures, warnings };
  }

  if (!gates.infraFailureRate) failures.push('infraFailureRate');
  if (!gates.passRateStdDev) failures.push('passRateStdDev');
  if (!gates.scenarioInstability) failures.push('scenarioInstability');

  if (mode === 'C') {
    if (!gates.runsRequired) failures.push('runsRequired');
  } else if (!gates.runsRequired) {
    warnings.push('runsRequired');
  }

  return { ok: failures.length === 0, failures, warnings };
}

async function main() {
  const args = parseArgs(process.argv);
  const scorecard = await readJson(args.scorecard);

  let currentManifest = null;
  if (args.currentManifest) {
    try {
      currentManifest = await readJson(args.currentManifest);
    } catch (err) {
      console.error(
        `Current manifest read failed (${args.currentManifest}): ${err?.message || err}`,
      );
      process.exit(1);
    }
    const errors = Array.isArray(currentManifest?.errors) ? currentManifest.errors : [];
    const hasStopFailure = errors.some((e) => String(e || '').startsWith('stop_failed:'));
    const infraFailure = !!currentManifest?.aggregate?.infraFailure;
    const teardownFailure = !!currentManifest?.aggregate?.teardownFailure;
    if (infraFailure || teardownFailure || hasStopFailure) {
      const reasons = [];
      if (infraFailure) reasons.push('infraFailure=true');
      if (teardownFailure) reasons.push('teardownFailure=true');
      if (hasStopFailure) reasons.push('errors contains stop_failed:*');
      console.error(
        `Current run unhealthy (${reasons.join(', ')}); failing gate in mode ${args.mode}.`,
      );
      process.exit(1);
    }
  }

  const result = evaluate(scorecard, args.mode);
  const summary = summarize(scorecard, args.mode);
  console.log(summary);

  if (result.warnings.length > 0) {
    console.log(`Warnings: ${result.warnings.join(', ')}`);
  }
  if (!result.ok) {
    console.error(`Gate failed (${args.mode}): ${result.failures.join(', ')}`);
    process.exit(1);
  }
}

main().catch((err) => {
  console.error(err?.stack || String(err));
  process.exit(1);
});
