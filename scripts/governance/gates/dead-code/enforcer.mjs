/**
 * dead-code enforcer — tempdoc 530 §2.9.
 * Wraps Knip (already wired in CI: `npm run knip --prefix modules/ui-web`).
 * Reads a Knip JSON report (preferably `--reporter json`) from a configured
 * path, counts per-file unused-export entries, ratchets the totals down.
 *
 * Baseline file: `<path> <unused_count> <date>`.
 *
 * If the report is missing, emits dead-code/report-missing (warning).
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { loadChangesets } from '../../lib/changeset-loader.mjs';

export const DEAD_CODE_CLASSIFICATIONS = new Set([
  'declared-growth', 'merge-import', 'emergency-override', 'unused-export-shrink',
]);
export const DEAD_CODE_RULE_DESCRIPTIONS = {
  'dead-code/within-baseline': 'Unused-export count at or below baseline',
  'dead-code/silent-growth': 'A file accumulated new unused exports without a declared changeset',
  'dead-code/declared-growth': 'Dead-code growth; classification covers it',
  'dead-code/rebalance-available': 'Unused-export count shrunk; ratchet can be rebalanced',
  'dead-code/rebalanced': 'Baseline auto-updated',
  'dead-code/report-missing':
    'Knip JSON report not found. Run `npm run knip --prefix modules/ui-web -- --reporter json > tmp/knip-report.json`.',
};

function parseBaseline(text) {
  const m = new Map();
  for (const raw of (text ?? '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const parts = line.split(/\s+/);
    if (parts.length < 2) continue;
    const c = Number(parts[1]);
    if (Number.isFinite(c)) m.set(parts[0], c);
  }
  return m;
}

export async function enforceDeadCode(options) {
  const { repoRoot, gate, baselineRef, rebalance=false, fixtureMode=false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const reportPath = resolve(sourceRoot, gate.config?.reportPath ?? 'tmp/knip-report.json');
  const baselinePath = resolve(sourceRoot, gate.baseline.path);
  const baseline = existsSync(baselinePath) ? parseBaseline(readFileSync(baselinePath, 'utf8')) : new Map();

  const findings = [];
  let verdict = 'pass';

  if (!existsSync(reportPath)) {
    findings.push({ ruleId: 'dead-code/report-missing', level: 'warning', message: `Knip report not found at ${reportPath}.`, uri: gate.config?.reportPath });
    return { toolName: 'justsearch-dead-code', toolVersion: '0.1.0', findings, verdict, ruleDescriptions: DEAD_CODE_RULE_DESCRIPTIONS };
  }

  let report; try { report = JSON.parse(readFileSync(reportPath, 'utf8')); } catch { findings.push({ ruleId: 'dead-code/report-missing', level: 'warning', message: 'malformed Knip JSON' }); return { toolName: 'justsearch-dead-code', toolVersion: '0.1.0', findings, verdict, ruleDescriptions: DEAD_CODE_RULE_DESCRIPTIONS }; }

  // Knip --reporter json shape varies by version. Tolerant: walk `files[]` /
  // `issues{}` / `unusedExports{}`. Default: count entries per file.
  const counts = new Map();
  const collect = (filePath, n) => counts.set(filePath, (counts.get(filePath) ?? 0) + n);
  if (Array.isArray(report.files)) {
    for (const f of report.files) {
      const p = f.file ?? f.filePath ?? f.path;
      const issues = (f.unusedExports ?? f.unusedTypes ?? f.exports ?? []).length ?? 0;
      if (p) collect(p, issues);
    }
  } else if (report.issues && typeof report.issues === 'object') {
    for (const [category, byFile] of Object.entries(report.issues)) {
      if (typeof byFile !== 'object') continue;
      for (const [p, entries] of Object.entries(byFile)) {
        collect(p, Array.isArray(entries) ? entries.length : 1);
      }
    }
  }

  const decls = gate.changesetsDir ? loadChangesets({
    repoRoot: sourceRoot, changesetsDir: gate.changesetsDir, baselineRef,
    allowedClassifications: DEAD_CODE_CLASSIFICATIONS, classificationField: 'classification',
    requireJustificationFor: new Set(['declared-growth','merge-import','emergency-override']),
    fixtureMode,
  }) : [];
  const growthCovered = decls.some(d => ['declared-growth','merge-import','emergency-override'].includes(d.classification));

  const rebalanceWrites = new Map();
  for (const [p, cur] of counts) {
    const pinned = baseline.get(p) ?? 0;
    if (cur > pinned) {
      if (!growthCovered) {
        verdict = 'fail';
        findings.push({ ruleId: 'dead-code/silent-growth', level: 'error', message: `${p}: ${pinned} → ${cur} unused exports without declared changeset`, uri: p });
      } else {
        findings.push({ ruleId: 'dead-code/declared-growth', level: 'note', message: `${p}: ${pinned} → ${cur}; classification covers`, uri: p });
      }
    } else if (cur < pinned) {
      findings.push({ ruleId: rebalance ? 'dead-code/rebalanced' : 'dead-code/rebalance-available', level: 'note', message: `${p}: ${cur} < pinned ${pinned}`, uri: p });
      if (rebalance) rebalanceWrites.set(p, cur);
    }
  }

  if (rebalance && rebalanceWrites.size > 0) {
    const date = new Date().toISOString().slice(0,10);
    const out = [`# dead-code ratchet — tempdoc 530 §2.9. <path> <count> <date>`];
    for (const [p, c] of [...baseline.entries()].sort()) {
      const nc = rebalanceWrites.has(p) ? rebalanceWrites.get(p) : c;
      if (nc > 0) out.push(`${p} ${nc} ${date}`);
    }
    writeFileSync(baselinePath, out.join('\n') + '\n');
  }

  return { toolName: 'justsearch-dead-code', toolVersion: '0.1.0', findings, verdict, ruleDescriptions: DEAD_CODE_RULE_DESCRIPTIONS };
}
