/**
 * npm-audit enforcer — discipline-gate kernel (tempdoc 530).
 *
 * Replaces `scripts/ci/check-npm-audit-ratchet.mjs` (kept in place during the
 * parity period). Wraps the same per-target severity-count comparison, but
 * adds the changeset escape-hatch protocol so regressions require an explicit
 * `npm-audit/declared-regression` (or sibling) classification — closing the
 * `--write-baseline` silent-bypass surface the old script exposes.
 *
 * Input artifacts (config-supplied paths, repo-relative):
 *   - report   — `npm-audit-report.v1` JSON (produced by report-npm-audit.mjs)
 *   - baseline — `npm-audit-ratchet-baseline.v1` JSON (the ratchet)
 *
 * Tracked severities default to ['high', 'critical'] to match the legacy CI
 * gate (ci.yml line 135).
 */

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

import { NPM_AUDIT_CLASSIFICATIONS, aggregateNpmAuditClassifications } from './classifications.mjs';
import { NPM_AUDIT_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import { loadChangesets } from '../../lib/changeset-loader.mjs';
import { readFileAtRef } from '../../lib/git-utils.mjs';

const SEVERITY_ORDER = ['info', 'low', 'moderate', 'high', 'critical', 'total'];

export async function enforceNpmAudit(options) {
  const { repoRoot, gate, baselineRef, rebalance = false, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const reportPath = resolve(sourceRoot, gate.config?.reportPath ?? 'tmp/npm-audit-report.json');
  const baselinePath = resolve(sourceRoot, gate.baseline.path);
  const trackedSeverities = gate.config?.trackedSeverities ?? ['high', 'critical'];

  const findings = [];
  let verdict = 'pass';

  if (!existsSync(reportPath)) {
    return {
      toolName: 'justsearch-npm-audit',
      toolVersion: '0.1.0',
      findings: [
        {
          ruleId: 'npm-audit/report-missing',
          level: 'warning',
          message:
            `npm audit report not found at ${reportPath}. ` +
            `Run \`node scripts/ci/report-npm-audit.mjs --out tmp/npm-audit-report.json\` first.`,
          uri: gate.config?.reportPath ?? 'tmp/npm-audit-report.json',
        },
      ],
      verdict: 'pass',
      ruleDescriptions: NPM_AUDIT_RULE_DESCRIPTIONS,
    };
  }

  if (!existsSync(baselinePath)) {
    return {
      toolName: 'justsearch-npm-audit',
      toolVersion: '0.1.0',
      findings: [
        {
          ruleId: 'npm-audit/baseline-missing',
          level: 'error',
          message:
            `npm-audit baseline not found at ${baselinePath}. ` +
            `Initialize with \`node scripts/governance/run.mjs --gate npm-audit --rebalance\`.`,
          uri: gate.baseline.path,
        },
      ],
      verdict: 'fail',
      ruleDescriptions: NPM_AUDIT_RULE_DESCRIPTIONS,
    };
  }

  const rawReport = JSON.parse(readFileSync(reportPath, 'utf8'));
  const rawBaseline = JSON.parse(readFileSync(baselinePath, 'utf8'));

  if (rawReport?.schema !== 'npm-audit-report.v1') {
    return {
      toolName: 'justsearch-npm-audit',
      toolVersion: '0.1.0',
      findings: [
        {
          ruleId: 'npm-audit/schema-mismatch',
          level: 'error',
          message: `Unexpected report schema '${rawReport?.schema}' (expected 'npm-audit-report.v1')`,
          uri: gate.config?.reportPath ?? 'tmp/npm-audit-report.json',
        },
      ],
      verdict: 'fail',
      ruleDescriptions: NPM_AUDIT_RULE_DESCRIPTIONS,
    };
  }
  if (rawBaseline?.schema !== 'npm-audit-ratchet-baseline.v1') {
    return {
      toolName: 'justsearch-npm-audit',
      toolVersion: '0.1.0',
      findings: [
        {
          ruleId: 'npm-audit/schema-mismatch',
          level: 'error',
          message: `Unexpected baseline schema '${rawBaseline?.schema}' (expected 'npm-audit-ratchet-baseline.v1')`,
          uri: gate.baseline.path,
        },
      ],
      verdict: 'fail',
      ruleDescriptions: NPM_AUDIT_RULE_DESCRIPTIONS,
    };
  }

  const baseline = normalize(rawBaseline.targets ?? {});
  const current = normalize(currentTargetsFromReport(rawReport));

  // Changeset escape-hatch protocol.
  const changesetsDir = gate.changesetsDir;
  const declarations = changesetsDir
    ? loadChangesets({
        repoRoot: sourceRoot,
        changesetsDir,
        baselineRef,
        allowedClassifications: NPM_AUDIT_CLASSIFICATIONS,
        classificationField: 'classification',
        requireJustificationFor: new Set(['declared-regression', 'lockfile-import', 'emergency-override']),
        fixtureMode,
      })
    : [];
  const aggregated = aggregateNpmAuditClassifications(declarations);

  const allTargets = [...new Set([...Object.keys(baseline), ...Object.keys(current)])].sort();
  const rebalanceWrites = {};
  let hasRegression = false;
  let hasShrink = false;

  for (const target of allTargets) {
    const baseCounts = baseline[target] ?? emptyCounts();
    const currCounts = current[target] ?? emptyCounts();
    for (const severity of trackedSeverities) {
      const bv = Number(baseCounts[severity] ?? 0);
      const cv = Number(currCounts[severity] ?? 0);
      if (cv > bv) {
        hasRegression = true;
        const ruleId = classifyRegression(aggregated);
        const level = ruleId === 'silent-regression' ? 'error' : 'note';
        if (ruleId === 'silent-regression') verdict = 'fail';
        findings.push({
          ruleId: `npm-audit/${ruleId}`,
          level,
          message:
            `${target} ${severity} regressed: ${bv} → ${cv} (Δ +${cv - bv}). ` +
            (ruleId === 'silent-regression'
              ? `Declare in gates/npm-audit/.changesets/ with classification 'declared-regression' / ` +
                `'lockfile-import' / 'emergency-override', or resolve the advisory before merging.`
              : `Classification '${ruleId}' covers this regression.`),
          uri: gate.baseline.path,
        });
      } else if (cv < bv) {
        hasShrink = true;
        findings.push({
          ruleId: rebalance ? 'npm-audit/rebalanced' : 'npm-audit/rebalance-available',
          level: 'note',
          message: `${target} ${severity} improved: ${bv} → ${cv} (Δ ${cv - bv}). ` +
            (rebalance ? `Baseline auto-updated.` : `Re-run with --rebalance to rewrite baseline.`),
          uri: gate.baseline.path,
        });
        if (rebalance) {
          rebalanceWrites[target] ??= { ...baseCounts };
          rebalanceWrites[target][severity] = cv;
        }
      }
    }
  }

  // Changeset-mismatch: declarations present but no regression observed.
  if (
    declarations.length > 0 &&
    !hasRegression &&
    !aggregated.classifications.every(c => c === 'severity-decrease')
  ) {
    findings.push({
      ruleId: 'npm-audit/changeset-mismatch',
      level: 'warning',
      message:
        `${declarations.length} npm-audit changeset(s) declared but no qualifying regression observed.`,
    });
  }

  if (rebalance && Object.keys(rebalanceWrites).length > 0) {
    writeRebalanced(baselinePath, rawBaseline, rebalanceWrites, current);
  }

  // Baseline-shift detection (tempdoc 530 §Layer 1 closure for npm-audit).
  // A commit can edit the baseline JSON directly — relaxing the tracked
  // severity counts in the same diff as a regression. Detect by reading the
  // baseline file at the PR's baseline ref and flagging any tracked-severity
  // count that increased baseline-side without a classified changeset.
  const priorBaseline = readPriorBaseline({
    fixtureMode,
    fixtureRoot,
    repoRoot,
    baselineRef,
    baselineFilePath: gate.baseline.path,
  });
  if (priorBaseline) {
    const priorTargets = normalize(priorBaseline.targets ?? {});
    const allBaselineTargets = [
      ...new Set([...Object.keys(priorTargets), ...Object.keys(baseline)]),
    ].sort();
    for (const target of allBaselineTargets) {
      const prior = priorTargets[target] ?? emptyCounts();
      const live = baseline[target] ?? emptyCounts();
      for (const severity of trackedSeverities) {
        const pv = Number(prior[severity] ?? 0);
        const lv = Number(live[severity] ?? 0);
        if (lv > pv) {
          const ruleId = classifyRegression(aggregated);
          if (ruleId === 'silent-regression') {
            verdict = 'fail';
            findings.push({
              ruleId: 'npm-audit/silent-baseline-shift',
              level: 'error',
              message:
                `Baseline ${target}/${severity} raised in this PR: ${pv} → ${lv} (Δ +${lv - pv}). ` +
                `The baseline JSON cannot be relaxed silently; add a 'declared-regression' / ` +
                `'lockfile-import' / 'emergency-override' changeset.`,
              uri: gate.baseline.path,
            });
          } else {
            findings.push({
              ruleId: 'npm-audit/declared-baseline-shift',
              level: 'note',
              message:
                `Baseline ${target}/${severity} raised ${pv} → ${lv}; classification '${ruleId}' covers it.`,
              uri: gate.baseline.path,
            });
          }
        }
      }
    }
  }

  return {
    toolName: 'justsearch-npm-audit',
    toolVersion: '0.1.0',
    findings,
    verdict,
    ruleDescriptions: NPM_AUDIT_RULE_DESCRIPTIONS,
    rebalanceWrites: Object.keys(rebalanceWrites).map(target => ({ file: target, before: '', after: '' })),
  };
}

function readPriorBaseline({ fixtureMode, fixtureRoot, repoRoot, baselineRef, baselineFilePath }) {
  if (fixtureMode) {
    if (!fixtureRoot) return null;
    const fp = resolve(fixtureRoot, '_baseline', baselineFilePath);
    if (!existsSync(fp)) return null;
    try {
      return JSON.parse(readFileSync(fp, 'utf8'));
    } catch {
      return null;
    }
  }
  if (!baselineRef) return null;
  const content = readFileAtRef(baselineRef, baselineFilePath, repoRoot);
  if (content === null) return null;
  try {
    return JSON.parse(content);
  } catch {
    return null;
  }
}

function classifyRegression(aggregated) {
  const cls = aggregated.classifications;
  if (cls.includes('declared-regression')) return 'declared-regression';
  if (cls.includes('lockfile-import')) return 'lockfile-import';
  if (cls.includes('emergency-override')) return 'emergency-override';
  return 'silent-regression';
}

function emptyCounts() {
  return { info: 0, low: 0, moderate: 0, high: 0, critical: 0, total: 0 };
}

function normalize(targetMap) {
  const out = {};
  for (const [k, v] of Object.entries(targetMap)) {
    out[k] = { ...emptyCounts() };
    if (v && typeof v === 'object') {
      for (const sev of SEVERITY_ORDER) {
        const n = Number(v[sev]);
        if (Number.isFinite(n) && n >= 0) out[k][sev] = n;
      }
    }
  }
  return out;
}

function currentTargetsFromReport(report) {
  const out = {};
  for (const row of Array.isArray(report?.targets) ? report.targets : []) {
    const id = String(row?.target_id ?? '').trim();
    if (!id) continue;
    out[id] = row?.vulnerabilities ?? {};
  }
  return out;
}

function writeRebalanced(baselinePath, originalBaseline, rebalanceWrites, current) {
  const targets = { ...originalBaseline.targets };
  for (const [target, counts] of Object.entries(rebalanceWrites)) {
    targets[target] = { ...emptyCounts(), ...counts };
  }
  // Recompute aggregate from current targets (matches the legacy
  // buildBaselineFromReport behavior — aggregate is target sums).
  const aggregate = { ...emptyCounts() };
  for (const counts of Object.values(targets)) {
    for (const sev of SEVERITY_ORDER) {
      aggregate[sev] += Number(counts[sev] ?? 0);
    }
  }
  const out = {
    ...originalBaseline,
    generated_at: new Date().toISOString(),
    targets,
    aggregate,
  };
  writeFileSync(baselinePath, JSON.stringify(out, null, 2) + '\n');
}
