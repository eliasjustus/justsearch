#!/usr/bin/env node

/**
 * Agent-quality TREND diagnostic (tempdoc 622 Layer C).
 *
 * Standalone, tolerance-aware, REPORT-ONLY validator over the most recent N
 * agent sessions in scores.ndjson. Models the `check-run-renderers` /
 * `relevance-ratchet` shape (a standalone validator with a tolerance band), NOT
 * the discipline-gate kernel — the kernel is binary (gate/warn) with no
 * non-blocking tier, and a noisy PHI baseline would thrash its ratchet (§6.4).
 *
 * Defaults to --mode warn (exit 0 ALWAYS — never blocks a build). --mode gate
 * is opt-in and intentionally discouraged: per tempdoc 277 PHI is a friction
 * diagnostic, not a target; making it a gate in a repo whose agents can read the
 * rubric is a Goodhart trap.
 *
 * Usage:
 *   node scripts/ci/check-agent-quality-trend.mjs [--mode warn|gate]
 *     [--scores <path>] [--baseline <path>] [--json]
 */

import fs from 'node:fs';
import path from 'node:path';

const REPO_ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname).replace(/^\/([A-Za-z]:)/, '$1'), '..', '..');
const DEFAULT_SCORES = path.join(REPO_ROOT, 'tmp', 'agent-telemetry', 'scores.ndjson');
const DEFAULT_BASELINE = path.join(REPO_ROOT, 'scripts', 'ci', 'agent-quality-baselines.v1.json');

function arg(name, fallback) {
  const i = process.argv.indexOf(name);
  return i !== -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

function loadNdjson(p) {
  try {
    return fs.readFileSync(p, 'utf8').split('\n').filter(l => l.trim())
      .map(l => { try { return JSON.parse(l); } catch { return null; } }).filter(Boolean);
  } catch { return []; }
}

const mean = (xs) => xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null;
const round = (n, d = 3) => n == null ? null : Math.round(n * 10 ** d) / 10 ** d;

// metric -> how to extract its value from a score record
const EXTRACT = {
  phi_score: (r) => r.score,
  tool_failure_rate: (r) => r.signals?.tool_failure_rate,
};

function evaluate(scores, baseline) {
  const window = scores.slice(-baseline.window_n);
  const findings = [];
  let insufficient = window.length < (baseline.min_sessions ?? 1);

  for (const [metric, spec] of Object.entries(baseline.metrics)) {
    const vals = window.map(EXTRACT[metric] || (() => undefined)).filter(v => typeof v === 'number');
    const trend = mean(vals);
    let status = 'ok', breach = false;
    if (trend == null) { status = 'no-data'; }
    else if (spec.direction === 'lower_is_better') {
      const ceil = spec.baseline + spec.tolerance_abs;
      breach = trend > ceil; status = breach ? 'regressed' : 'ok';
    } else {
      const floor = spec.baseline - spec.tolerance_abs;
      breach = trend < floor; status = breach ? 'regressed' : 'ok';
    }
    findings.push({ metric, trend: round(trend), baseline: spec.baseline,
      tolerance_abs: spec.tolerance_abs, direction: spec.direction, n: vals.length, status, breach });
  }
  return { window_n: window.length, insufficient, findings };
}

function toMarkdown(res, baseline) {
  const lines = [`### Agent-quality trend (diagnostic — tempdoc 622 Layer C)`,
    ``, `Window: last ${res.window_n} sessions (target N=${baseline.window_n}).`,
    res.insufficient ? `> ⚠️ Insufficient sessions (< ${baseline.min_sessions ?? 1}); trend is indicative only.` : ``,
    ``, `| metric | trend (n) | baseline ±tol | dir | status |`, `|---|---|---|---|---|`];
  for (const f of res.findings) {
    const band = `${f.baseline} ±${f.tolerance_abs}`;
    const icon = f.status === 'regressed' ? '🔻' : f.status === 'no-data' ? '·' : '✓';
    lines.push(`| ${f.metric} | ${f.trend ?? '—'} (${f.n}) | ${band} | ${f.direction === 'lower_is_better' ? '↓' : '↑'} | ${icon} ${f.status} |`);
  }
  lines.push(``, `_Report-only: this never blocks a build (§6.4). PHI is a friction diagnostic, not a target._`);
  return lines.filter(l => l !== ``).join('\n') + '\n';
}

function main() {
  const mode = (arg('--mode', 'warn') || 'warn').toLowerCase();
  const scores = loadNdjson(arg('--scores', DEFAULT_SCORES));
  let baseline;
  try { baseline = JSON.parse(fs.readFileSync(arg('--baseline', DEFAULT_BASELINE), 'utf8')); }
  catch (e) { console.error(`check-agent-quality-trend: cannot read baseline: ${e.message}`); process.exit(0); }

  const res = evaluate(scores, baseline);
  const md = toMarkdown(res, baseline);

  if (process.argv.includes('--json')) process.stdout.write(JSON.stringify(res, null, 2) + '\n');
  else process.stdout.write(md);
  if (process.env.GITHUB_STEP_SUMMARY) {
    try { fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, md, 'utf8'); } catch { /* best-effort */ }
  }

  const regressed = res.findings.some(f => f.breach) && !res.insufficient;
  // --mode warn (default): ALWAYS exit 0 — report-only, never blocks (§6.4).
  if (mode === 'gate' && regressed) {
    console.error('check-agent-quality-trend: regression in --mode gate (opt-in; discouraged per §6.4).');
    process.exit(1);
  }
  process.exit(0);
}

main();
