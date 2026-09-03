/**
 * test-to-code ratio enforcer — tempdoc 530 §2.10.
 * Per-module ratio of test LOC to main LOC; only the ratio direction is
 * enforced (current ratio ≥ baseline). Baseline file: `<module> <ratio_x1000> <date>`
 * where ratio_x1000 = test_loc * 1000 / main_loc (preserves integer baseline format).
 */

import { existsSync, readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { resolve, relative, join } from 'node:path';
import { loadChangesets } from '../../lib/changeset-loader.mjs';
import { readPriorBaselineText } from '../../lib/prior-baseline.mjs';
import { repinFinding, repinRuleDescription, REPIN_REGRESSION_RULE_SUFFIX } from '../../lib/declared-growth-repin.mjs';

export const TEST_TO_CODE_CLASSIFICATIONS = new Set([
  'declared-regression', 'merge-import', 'emergency-override', 'ratio-improvement',
]);
export const TEST_TO_CODE_RULE_DESCRIPTIONS = {
  'test-to-code/within-baseline': 'Ratio meets or exceeds baseline',
  'test-to-code/silent-regression': 'Test-to-code ratio dropped without a declared changeset',
  ...repinRuleDescription('test-to-code', REPIN_REGRESSION_RULE_SUFFIX),
  'test-to-code/declared-regression': 'Ratio declined; classification covers',
  'test-to-code/rebalance-available': 'Ratio improved; baseline can be raised',
};

function countLines(file) {
  try {
    let n = 0;
    const buf = readFileSync(file);
    for (let i = 0; i < buf.length; i++) if (buf[i] === 0x0a) n++;
    return n;
  } catch { return 0; }
}

function walkFiles(root, pred) {
  const out = [];
  const visit = dir => {
    let entries; try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const e of entries) {
      const full = join(dir, e.name);
      if (e.isDirectory()) { if (!e.name.startsWith('.') && e.name !== 'build' && e.name !== 'node_modules') visit(full); }
      else if (e.isFile() && pred(full)) out.push(full);
    }
  };
  visit(root);
  return out;
}

function moduleRatio(moduleRoot) {
  const mainDir = resolve(moduleRoot, 'src', 'main');
  const testDir = resolve(moduleRoot, 'src', 'test');
  if (!existsSync(mainDir)) return null;
  const mainFiles = walkFiles(mainDir, p => p.endsWith('.java'));
  const testFiles = existsSync(testDir) ? walkFiles(testDir, p => p.endsWith('.java')) : [];
  const mainLoc = mainFiles.reduce((a, f) => a + countLines(f), 0);
  const testLoc = testFiles.reduce((a, f) => a + countLines(f), 0);
  if (mainLoc === 0) return null;
  return Math.round((testLoc * 1000) / mainLoc);
}

function listModules(repoRoot) {
  const modulesDir = resolve(repoRoot, 'modules');
  if (!existsSync(modulesDir)) return [];
  const out = [];
  for (const e of readdirSync(modulesDir, { withFileTypes: true })) {
    if (e.isDirectory()) out.push(`modules/${e.name}`);
  }
  return out;
}

function parseBaseline(text) {
  const m = new Map();
  for (const raw of (text ?? '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const parts = line.split(/\s+/);
    if (parts.length < 2) continue;
    const r = Number(parts[1]);
    if (Number.isFinite(r)) m.set(parts[0], r);
  }
  return m;
}

export async function enforceTestToCode(options) {
  const { repoRoot, gate, baselineRef, rebalance=false, fixtureMode=false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const baselinePath = resolve(sourceRoot, gate.baseline.path);
  const baseline = existsSync(baselinePath) ? parseBaseline(readFileSync(baselinePath, 'utf8')) : new Map();

  const decls = gate.changesetsDir ? loadChangesets({
    repoRoot: sourceRoot, changesetsDir: gate.changesetsDir, baselineRef,
    allowedClassifications: TEST_TO_CODE_CLASSIFICATIONS, classificationField: 'classification',
    requireJustificationFor: new Set(['declared-regression','merge-import','emergency-override']),
    fixtureMode,
  }) : [];
  const covered = decls.some(d => ['declared-regression','merge-import','emergency-override'].includes(d.classification));
  const coveringCls = decls.find(d => ['declared-regression','merge-import','emergency-override'].includes(d.classification))?.classification ?? 'declared-regression';
  // Tempdoc 918: the repin rule needs the floor as it stood at the PR base.
  const priorText = readPriorBaselineText({
    fixtureMode, fixtureRoot, sourceRoot, baselineRef, baselinePath: gate.baseline.path,
  });
  const priorBaseline = priorText === null ? null : parseBaseline(priorText);

  const findings = [];
  let verdict = 'pass';
  const rebalanceWrites = new Map();

  for (const mod of listModules(sourceRoot)) {
    const cur = moduleRatio(resolve(sourceRoot, mod));
    if (cur === null) continue;
    const base = baseline.get(mod) ?? cur; // first encounter sets baseline implicitly
    if (cur < base) {
      if (!covered) {
        verdict = 'fail';
        findings.push({ ruleId: 'test-to-code/silent-regression', level: 'error', message: `${mod}: ratio ${(base/10).toFixed(1)}% → ${(cur/10).toFixed(1)}% without declared changeset`, uri: mod });
      } else {
        // Tempdoc 918: the changeset licenses the floor being LOWERED, not a floor left in place
        // above the measured ratio. The direction is inverted here — this is a floor, not a ceiling.
        verdict = 'fail';
        findings.push(repinFinding({
          rulePrefix: 'test-to-code', classification: coveringCls, row: mod, measured: cur,
          livePin: base, priorPin: priorBaseline?.get(mod), baselineFile: gate.baseline.path,
          unit: 'ratio×1000', suffix: REPIN_REGRESSION_RULE_SUFFIX, direction: 'regression',
          pinLine: `${mod} ${cur} <today>`,
        }));
      }
    } else if (cur > base) {
      findings.push({ ruleId: 'test-to-code/rebalance-available', level: 'note', message: `${mod}: ratio improved ${(base/10).toFixed(1)}% → ${(cur/10).toFixed(1)}%`, uri: mod });
      if (rebalance) rebalanceWrites.set(mod, cur);
    }
  }

  if (rebalance && rebalanceWrites.size > 0) {
    const date = new Date().toISOString().slice(0,10);
    const out = [`# test-to-code ratio — tempdoc 530 §2.10. <module> <ratio_x1000> <date>`];
    for (const [m, r] of [...baseline.entries(), ...rebalanceWrites.entries()]) {
      out.push(`${m} ${rebalanceWrites.has(m) ? rebalanceWrites.get(m) : r} ${date}`);
    }
    writeFileSync(baselinePath, [...new Set(out)].join('\n') + '\n');
  }

  return { toolName: 'justsearch-test-to-code', toolVersion: '0.1.0', findings, verdict, ruleDescriptions: TEST_TO_CODE_RULE_DESCRIPTIONS };
}
