/**
 * module-dependency-budget enforcer - tempdoc 530 sec 2.8 (input contract: tempdoc 742 D1).
 * Wraps `scripts/architecture/module-deps.mjs` JSON output to count per-module
 * cross-module imports; only-shrinks ratchet.
 *
 * Baseline file: `<module> <prod_dep_count> <date>`. Report presence is the
 * RUNNER's contract: `tmp/arch-preflight/module-deps.json` is a `required` input
 * under config.inputs, so a missing report fails at the runner
 * (kernel/input-missing) before this enforcer is dispatched. Produce it with
 * `node scripts/architecture/module-deps.mjs`. A malformed report fails closed.
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { loadChangesets } from '../../lib/changeset-loader.mjs';
import { readPriorBaselineText } from '../../lib/prior-baseline.mjs';
import { repinFinding, repinRuleDescription } from '../../lib/declared-growth-repin.mjs';
import { verdictForBaselineShift } from './truth-table.mjs';

export const MODULE_DEPS_CLASSIFICATIONS = new Set([
  'declared-growth', 'merge-import', 'emergency-override', 'dep-shrink',
]);
export const MODULE_DEPS_RULE_DESCRIPTIONS = {
  'module-deps/within-baseline': 'Cross-module dep count at or below baseline',
  'module-deps/silent-growth': 'Module gained dependencies without a declared changeset',
  'module-deps/declared-growth': 'Module gained dependencies; classification covers',
  ...repinRuleDescription('module-deps'),
  'module-deps/rebalance-available': 'Module shed dependencies; ratchet can be rebalanced',
  'module-deps/rebalanced': 'Baseline auto-updated',
  'module-deps/report-malformed': 'module-deps JSON report could not be parsed (fail-closed)',
  'module-deps/silent-baseline-shift': 'Baseline raised without a declared changeset',
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

export async function enforceModuleDeps(options) {
  const { repoRoot, gate, baselineRef, rebalance=false, fixtureMode=false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const reportPath = resolve(sourceRoot, gate.config?.reportPath ?? 'tmp/arch-preflight/module-deps.json');
  const baselinePath = resolve(sourceRoot, gate.baseline.path);
  const baseline = existsSync(baselinePath) ? parseBaseline(readFileSync(baselinePath, 'utf8')) : new Map();

  const findings = [];
  let verdict = 'pass';

  let report;
  try {
    report = JSON.parse(readFileSync(reportPath, 'utf8'));
  } catch {
    findings.push({ ruleId: 'module-deps/report-malformed', level: 'error', message: `malformed JSON in module-deps report at ${reportPath}`, uri: gate.config?.reportPath });
    return { toolName: 'justsearch-module-deps', toolVersion: '0.1.0', findings, verdict: 'fail', ruleDescriptions: MODULE_DEPS_RULE_DESCRIPTIONS };
  }

  // module-deps.mjs's output shape: { modules: [ {name, productionDeps: []} ... ] } or similar.
  // Be flexible: support either `productionDeps`/`prodDeps`/`dependencies` arrays.
  const counts = new Map();
  const modules = report.modules ?? report.nodes ?? [];
  for (const m of modules) {
    const name = m.name ?? m.id ?? m.module;
    if (!name) continue;
    const prodDeps = m.productionDeps ?? m.prodDeps ?? m.dependencies ?? [];
    counts.set(name, Array.isArray(prodDeps) ? prodDeps.length : 0);
  }

  const decls = gate.changesetsDir ? loadChangesets({
    repoRoot: sourceRoot, changesetsDir: gate.changesetsDir, baselineRef,
    allowedClassifications: MODULE_DEPS_CLASSIFICATIONS, classificationField: 'classification',
    requireJustificationFor: new Set(['declared-growth','merge-import','emergency-override']),
    fixtureMode,
  }) : [];
  const growthCovered = decls.some(d => ['declared-growth','merge-import','emergency-override'].includes(d.classification));
  const covering = decls.find(d => ['declared-growth','merge-import','emergency-override'].includes(d.classification))?.classification ?? 'declared-growth';

  // Tempdoc 918: prior pin read before the loop; the baseline-shift block below reuses it.
  const priorText = readPriorBaselineText({
    fixtureMode, fixtureRoot, sourceRoot, baselineRef, baselinePath: gate.baseline.path,
  });
  const priorBaseline = priorText === null ? null : parseBaseline(priorText);

  const rebalanceWrites = new Map();
  for (const [name, cur] of counts) {
    const pinned = baseline.get(name);
    if (pinned === undefined) continue; // un-pinned modules don't fail
    if (cur > pinned) {
      if (!growthCovered) {
        verdict = 'fail';
        findings.push({ ruleId: 'module-deps/silent-growth', level: 'error', message: `${name}: ${pinned} → ${cur} cross-module deps without declared changeset`, uri: name });
      } else {
        // Tempdoc 918: the changeset licenses the pin advance, not an unpinned overflow.
        verdict = 'fail';
        findings.push(repinFinding({
          rulePrefix: 'module-deps', classification: covering, row: name, measured: cur,
          livePin: pinned, priorPin: priorBaseline?.get(name), baselineFile: gate.baseline.path,
          unit: 'cross-module deps', pinLine: `${name} ${cur} <today>`,
        }));
      }
    } else if (cur < pinned) {
      findings.push({ ruleId: rebalance ? 'module-deps/rebalanced' : 'module-deps/rebalance-available', level: 'note', message: `${name}: ${cur} < pinned ${pinned}`, uri: name });
      if (rebalance) rebalanceWrites.set(name, cur);
    }
  }

  // Baseline-shift detection (tempdoc 910). `module-deps/silent-baseline-shift` was DECLARED in the
  // rule descriptions above but nothing ever emitted it: `readFileAtRef` was imported and never
  // called, so raising a pinned number by hand passed as `rebalance-available`. A documented ruleId
  // that cannot fire is worse than an absent one — the catalog claims the hole is covered.
  if (priorBaseline !== null) {
    for (const [name, livePin] of baseline.entries()) {
      const priorPin = priorBaseline.get(name);
      if (priorPin === undefined) continue;
      const v = verdictForBaselineShift({
        module: name, priorPin, livePin, classification: growthCovered ? covering : 'silent-growth',
      });
      if (v.status === 'fail') {
        verdict = 'fail';
        findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: gate.baseline.path });
      } else if (v.status === 'info') {
        findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: gate.baseline.path });
      }
    }
  }

  if (rebalance && rebalanceWrites.size > 0) {
    const date = new Date().toISOString().slice(0,10);
    const out = [`# module-deps ratchet — tempdoc 530 §2.8. <module> <count> <date>`];
    for (const [m, c] of [...baseline.entries()].sort()) {
      const nc = rebalanceWrites.has(m) ? rebalanceWrites.get(m) : c;
      out.push(`${m} ${nc} ${date}`);
    }
    writeFileSync(baselinePath, out.join('\n') + '\n');
  }

  return { toolName: 'justsearch-module-deps', toolVersion: '0.1.0', findings, verdict, ruleDescriptions: MODULE_DEPS_RULE_DESCRIPTIONS };
}
