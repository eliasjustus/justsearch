/**
 * Dashboard generator — Layer 3 §3.7b of tempdoc 530.
 *
 * Reads governance/registry.v1.json + tmp/governance-history.ndjson + the
 * most recent SARIF run and emits `docs/reference/governance-state.md`,
 * a GitHub-renderable summary of per-gate verdicts, rebalance history,
 * and discipline-debt totals.
 *
 * Pattern matches scripts/docs/llmstxt-generate.mjs: regenerate between
 * `<!-- generated:start -->` / `<!-- generated:end -->` markers, so
 * narrative around the generated block stays editable.
 *
 * Usage:
 *   node scripts/governance/lib/dashboard.mjs
 *   node scripts/governance/lib/dashboard.mjs --check    # exit 1 if out of date
 */

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdirSync } from 'node:fs';

import { readHistory } from './history.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(__dirname, '..', '..', '..');
const OUT_PATH = resolve(REPO_ROOT, 'docs/reference/governance-state.md');
// Timestamped per-run history goes to a gitignored side-file. The committed
// dashboard stays stable (verdicts, debt totals, gate roster) so it does not
// churn on every kernel invocation. Tempdoc 530 Phase G follow-up.
const RUNS_OUT_PATH = resolve(REPO_ROOT, 'tmp/governance-runs.md');
const REGISTRY_PATH = resolve(REPO_ROOT, 'governance/registry.v1.json');
// The machine projection consumed by the /api/governance/state read surface (tempdoc 576 §15 / 530
// Layer 4). Written ONCE to the Head's classpath resources (no dual copy) so GovernanceController can
// serve it via getResourceAsStream; committed + stable (committed-authority projection, no churn).
const JSON_OUT_PATH = resolve(REPO_ROOT, 'modules/ui/src/main/resources/governance/governance-state.json');

function parseArgs(argv) {
  return { check: argv.includes('--check') };
}

function loadRegistry() {
  if (!existsSync(REGISTRY_PATH)) return { gates: [] };
  return JSON.parse(readFileSync(REGISTRY_PATH, 'utf8'));
}

function pad(n, width = 0) {
  const s = String(n);
  return s.padStart(width, ' ');
}

/**
 * The ONE structured governance-state projection (tempdoc 576 §15 / 530 Layer 3-4). The machine JSON
 * served by /api/governance/state derives ONLY from COMMITTED authorities — the registry roster, the
 * class-size ratchet, the exception-count ceiling, and the per-seam strength floors — so it is stable
 * (no machine-specific local run history → no commit churn) and is a pure projection of the registry +
 * baselines, never a fork. (The human markdown keeps a local run-history view; that stays separate.)
 */
export function buildState({ repoRoot = REPO_ROOT } = {}) {
  const registryPath = resolve(repoRoot, 'governance/registry.v1.json');
  const reg = existsSync(registryPath) ? JSON.parse(readFileSync(registryPath, 'utf8')) : { gates: [] };
  const gates = (reg.gates ?? []).map((g) => ({
    id: g.id,
    title: g.title ?? '',
    tier: g.baseline?.kind === 'ratchet-file' ? 'ratchet' : g.changesetsDir ? 'changeset-gated' : 'scan',
    hasChangesets: Boolean(g.changesetsDir),
  }));
  return {
    schema: 'governance-state.v1',
    gateCount: gates.length,
    gates,
    exceptions: readExceptionCeiling(repoRoot),
    strengthFloors: readStrengthFloors(repoRoot),
    classSizeDebt: readClassSizeDebt(repoRoot),
  };
}

/** The exception-ledger ceiling (rung-4 meta-ratchet), from the committed baseline. */
function readExceptionCeiling(repoRoot) {
  const p = resolve(repoRoot, 'gates/exception-count/baseline.v1.json');
  if (!existsSync(p)) return { maxExceptions: null };
  try {
    return { maxExceptions: JSON.parse(readFileSync(p, 'utf8')).maxExceptions ?? null };
  } catch {
    return { maxExceptions: null };
  }
}

/** Per-seam mutation-strength floors (rung-2), from the committed test-efficacy baseline. */
function readStrengthFloors(repoRoot) {
  const p = resolve(repoRoot, 'gates/test-efficacy/strength-baseline.v1.json');
  if (!existsSync(p)) return [];
  try {
    const seams = JSON.parse(readFileSync(p, 'utf8')).seams ?? {};
    return Object.entries(seams).map(([id, v]) => ({ id, minStrength: v.minStrength, maxNoCoverage: v.maxNoCoverage }));
  } catch {
    return [];
  }
}

/** Per-pin headroom above the class-size ceiling (the rung-4 discipline-debt projection). */
function readClassSizeDebt(repoRoot) {
  const ratchet = resolve(repoRoot, 'gradle/class-size-exceptions.txt');
  if (!existsSync(ratchet)) return { files: 0, totalDebt: 0, worst: [] };
  const ceiling = 1000;
  const rows = [];
  for (const raw of readFileSync(ratchet, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const parts = line.split(/\s+/);
    const pinned = Number(parts[1]);
    if (parts.length < 2 || !Number.isFinite(pinned)) continue;
    rows.push({ path: parts[0], pinned, over: Math.max(0, pinned - ceiling) });
  }
  const totalDebt = rows.reduce((acc, r) => acc + r.over, 0);
  const worst = rows.sort((a, b) => b.pinned - a.pinned).slice(0, 5);
  return { files: rows.length, ceiling, totalDebt, worst };
}

function generate() {
  const reg = loadRegistry();
  const history = readHistory({ repoRoot: REPO_ROOT });

  // Group history by gate; pick latest verdict + last-rebalance hint.
  const byGate = new Map();
  for (const entry of history) {
    const cur = byGate.get(entry.gate) ?? { runs: 0, lastVerdict: null, lastTs: null, errorTotal: 0 };
    cur.runs++;
    cur.lastVerdict = entry.verdict;
    cur.lastTs = entry.ts;
    cur.errorTotal += entry.findings?.error ?? 0;
    byGate.set(entry.gate, cur);
  }

  const lines = [];
  lines.push(`---`);
  lines.push(`title: Governance state`);
  lines.push(`type: reference`);
  lines.push(`status: auto-generated`);
  lines.push(`description: "Per-gate verdict snapshot + run history + discipline-debt totals. Regenerated by scripts/governance/lib/dashboard.mjs."`);
  lines.push(`---`);
  lines.push('');
  lines.push(`# Governance state`);
  lines.push('');
  lines.push(`Auto-regenerated by \`scripts/governance/lib/dashboard.mjs\`. Tempdoc 530 Layer 3 §3.7b.`);
  lines.push('');
  lines.push('<!-- generated:start -->');
  lines.push('');
  lines.push(`## Gates (${reg.gates?.length ?? 0})`);
  lines.push('');
  lines.push('| Gate | Title | Last verdict | Runs in history | Cumulative errors |');
  lines.push('|---|---|---|---:|---:|');
  for (const gate of reg.gates ?? []) {
    const h = byGate.get(gate.id) ?? { runs: 0, lastVerdict: 'unknown', errorTotal: 0 };
    lines.push(`| \`${gate.id}\` | ${gate.title ?? ''} | ${h.lastVerdict ?? 'unknown'} | ${pad(h.runs)} | ${pad(h.errorTotal)} |`);
  }
  lines.push('');

  // Class-size discipline-debt: per-pin headroom above ceiling.
  const classSizeRatchet = resolve(REPO_ROOT, 'gradle/class-size-exceptions.txt');
  if (existsSync(classSizeRatchet)) {
    const content = readFileSync(classSizeRatchet, 'utf8');
    const rows = [];
    for (const raw of content.split(/\r?\n/)) {
      const line = raw.trim();
      if (!line || line.startsWith('#')) continue;
      const parts = line.split(/\s+/);
      if (parts.length < 2) continue;
      const pinned = Number(parts[1]);
      if (!Number.isFinite(pinned)) continue;
      rows.push({ path: parts[0], pinned });
    }
    const ceiling = 1000;
    const totalDebt = rows.reduce((acc, r) => acc + Math.max(0, r.pinned - ceiling), 0);
    lines.push(`## Class-size discipline-debt`);
    lines.push('');
    lines.push(`- ${rows.length} ratcheted files`);
    lines.push(`- Sum of \`pin − ceiling\` (LOC over ceiling): **${totalDebt}**`);
    lines.push(`- Worst offenders:`);
    for (const r of rows.sort((a, b) => b.pinned - a.pinned).slice(0, 5)) {
      lines.push(`  - \`${r.path}\` — pinned ${r.pinned} (${r.pinned - ceiling} over)`);
    }
    lines.push('');
  }

  lines.push(`Recent timestamped runs are written to \`tmp/governance-runs.md\` (gitignored) to keep the committed dashboard stable.`);
  lines.push('');
  lines.push('<!-- generated:end -->');
  lines.push('');
  lines.push('## See also');
  lines.push('');
  lines.push('- Historical tempdoc 530 — original design note for the discipline-gate kernel');
  lines.push('- [`docs/reference/contributing/discipline-gate-kernel.md`](contributing/discipline-gate-kernel.md) — substrate reference');
  lines.push('- [`governance/registry.v1.json`](../../governance/registry.v1.json) — gate registry');
  lines.push('');
  return lines.join('\n');
}

function generateRuns() {
  const history = readHistory({ repoRoot: REPO_ROOT });
  const out = [];
  out.push('# Governance runs (timestamped history)');
  out.push('');
  out.push('Auto-regenerated by `scripts/governance/lib/dashboard.mjs`. Gitignored — see `docs/reference/governance-state.md` for the stable view.');
  out.push('');
  if (history.length === 0) {
    out.push('_No run history yet._');
    return out.join('\n') + '\n';
  }
  out.push('| Timestamp | Gate | Verdict | Errors | Warnings | Notes |');
  out.push('|---|---|---|---:|---:|---:|');
  for (const e of history.slice(-50).reverse()) {
    const f = e.findings ?? {};
    out.push(`| ${e.ts} | \`${e.gate}\` | ${e.verdict} | ${pad(f.error)} | ${pad(f.warning)} | ${pad(f.note)} |`);
  }
  return out.join('\n') + '\n';
}

function main() {
  const args = parseArgs(process.argv);
  const generated = generate();
  const generatedJson = JSON.stringify(buildState({ repoRoot: REPO_ROOT }), null, 2) + '\n';

  if (args.check) {
    // Only the STABLE machine projection is freshness-gated — the markdown embeds local run history
    // and is intentionally not a --check subject (it would false-fail on a clean checkout).
    const existingJson = existsSync(JSON_OUT_PATH) ? readFileSync(JSON_OUT_PATH, 'utf8') : '';
    if (existingJson.trim() === generatedJson.trim()) {
      console.log('dashboard: governance-state.json up to date');
      process.exit(0);
    }
    console.error('dashboard: modules/ui/src/main/resources/governance/governance-state.json is out of date.');
    console.error('Run: node scripts/governance/lib/dashboard.mjs');
    process.exit(1);
  }

  mkdirSync(dirname(OUT_PATH), { recursive: true });
  writeFileSync(OUT_PATH, generated);
  console.log(`dashboard: wrote ${OUT_PATH}`);
  mkdirSync(dirname(JSON_OUT_PATH), { recursive: true });
  writeFileSync(JSON_OUT_PATH, generatedJson);
  console.log(`dashboard: wrote ${JSON_OUT_PATH}`);
  mkdirSync(dirname(RUNS_OUT_PATH), { recursive: true });
  writeFileSync(RUNS_OUT_PATH, generateRuns());
  console.log(`dashboard: wrote ${RUNS_OUT_PATH}`);
}

main();
