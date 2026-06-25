/**
 * dead-code-jvm enforcer — tempdoc 638.
 *
 * The JVM complement to the `dead-code` (Knip) gate: whole-program dead-CLASS detection.
 * The ArchUnit test in `modules/dead-code-audit` (which depends on every production module, so
 * cross-process callers are visible) writes `tmp/dead-code-jvm-report.json`:
 *   { "deadSymbols": [ { "kind": "class", "symbol": "<fqn>", "location": "<src>" } ], "count": N }
 *
 * This is a SET ratchet (presence, not count): the baseline is the accepted set of currently-dead
 * classes (test-wired validation infra + not-yet-removed orphans). A class in the report that is
 * NOT in the baseline is a NEW dead class → fail unless a changeset declares the growth. A baseline
 * entry absent from the report means a dead class was removed → the ratchet can shrink (--rebalance).
 *
 * Mirrors `scripts/governance/gates/dead-code/enforcer.mjs` (read-report-vs-ratchet shape).
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { loadChangesets } from '../../lib/changeset-loader.mjs';

const TOOL_NAME = 'justsearch-dead-code-jvm';
const TOOL_VERSION = '0.1.0';

export const DEAD_CODE_JVM_CLASSIFICATIONS = new Set([
  'declared-growth',
  'test-wired-infra',
  'emergency-override',
]);

export const DEAD_CODE_JVM_RULE_DESCRIPTIONS = {
  'dead-code-jvm/within-baseline': 'Dead classes are at or within the accepted baseline set',
  'dead-code-jvm/new-dead-class':
    'A class became whole-program-unreferenced without a declared changeset (potential dead code)',
  'dead-code-jvm/declared-growth': 'New dead class is covered by a declared changeset',
  'dead-code-jvm/rebalance-available':
    'A baseline dead class is gone from the report; the ratchet can be shrunk (--rebalance)',
  'dead-code-jvm/rebalanced': 'Baseline auto-shrunk',
  'dead-code-jvm/report-missing':
    'dead-code-jvm report not found. Run `./gradlew.bat :modules:dead-code-audit:test`.',
};

/** Baseline format: one fully-qualified class name per non-comment line. */
function parseBaseline(text) {
  const set = new Set();
  for (const raw of (text ?? '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    set.add(line.split(/\s+/)[0]);
  }
  return set;
}

function result(findings, verdict) {
  return {
    toolName: TOOL_NAME,
    toolVersion: TOOL_VERSION,
    findings,
    verdict,
    ruleDescriptions: DEAD_CODE_JVM_RULE_DESCRIPTIONS,
  };
}

export async function enforce(options) {
  const { repoRoot, gate, baselineRef, rebalance = false, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const reportPath = resolve(sourceRoot, gate.config?.reportPath ?? 'tmp/dead-code-jvm-report.json');
  const baselinePath = resolve(sourceRoot, gate.baseline.path);
  const baseline = existsSync(baselinePath) ? parseBaseline(readFileSync(baselinePath, 'utf8')) : new Set();

  const findings = [];

  if (!existsSync(reportPath)) {
    findings.push({
      ruleId: 'dead-code-jvm/report-missing',
      level: 'warning',
      message: `dead-code-jvm report not found at ${reportPath}. Run :modules:dead-code-audit:test.`,
      uri: gate.config?.reportPath,
    });
    return result(findings, 'pass');
  }

  let report;
  try {
    report = JSON.parse(readFileSync(reportPath, 'utf8'));
  } catch {
    findings.push({ ruleId: 'dead-code-jvm/report-missing', level: 'warning', message: 'malformed report JSON' });
    return result(findings, 'pass');
  }

  const current = new Set(
    (Array.isArray(report.deadSymbols) ? report.deadSymbols : [])
      .filter((d) => d && d.kind === 'class' && d.symbol)
      .map((d) => d.symbol),
  );

  const decls = gate.changesetsDir
    ? loadChangesets({
        repoRoot: sourceRoot,
        changesetsDir: gate.changesetsDir,
        baselineRef,
        allowedClassifications: DEAD_CODE_JVM_CLASSIFICATIONS,
        classificationField: 'classification',
        requireJustificationFor: new Set(['declared-growth', 'test-wired-infra', 'emergency-override']),
        fixtureMode,
      })
    : [];
  const growthCovered = decls.some((d) =>
    ['declared-growth', 'test-wired-infra', 'emergency-override'].includes(d.classification),
  );

  let verdict = 'pass';

  // New dead classes (in report, not in baseline) → fail unless a changeset covers the growth.
  for (const sym of [...current].sort()) {
    if (baseline.has(sym)) continue;
    if (growthCovered) {
      findings.push({ ruleId: 'dead-code-jvm/declared-growth', level: 'note', message: `${sym}: new dead class; classification covers`, uri: sym });
    } else {
      verdict = 'fail';
      findings.push({ ruleId: 'dead-code-jvm/new-dead-class', level: 'error', message: `${sym}: newly whole-program-unreferenced without a declared changeset`, uri: sym });
    }
  }

  // Baseline entries no longer dead → the ratchet can shrink.
  const removable = [...baseline].filter((sym) => !current.has(sym)).sort();
  for (const sym of removable) {
    findings.push({
      ruleId: rebalance ? 'dead-code-jvm/rebalanced' : 'dead-code-jvm/rebalance-available',
      level: 'note',
      message: `${sym}: in baseline but no longer dead`,
      uri: sym,
    });
  }

  if (rebalance && removable.length > 0) {
    const date = new Date().toISOString().slice(0, 10);
    const kept = [...baseline].filter((sym) => current.has(sym)).sort();
    const out = [
      `# dead-code-jvm ratchet — tempdoc 638. Accepted whole-program-dead classes (one FQN/line).`,
      `# Rebalanced ${date}.`,
      ...kept,
    ];
    writeFileSync(baselinePath, out.join('\n') + '\n');
  }

  return result(findings, verdict);
}
