/**
 * test-efficacy enforcer — discipline-gate kernel (tempdoc 555, Pillar C).
 *
 * Ratchets the per-seam mutation TEST-STRENGTH (mutants killed / mutants covered) of the law-bearing
 * seams declared in governance/logic-seams.v1.json. Strength may only rise; a drop below a seam's
 * baseline floor fails the build unless a changeset (`strength-regression` / `seam-retraction` /
 * `emergency-override`) covers it. This is the automation tier for the repo's
 * "does the assertion pass for the right reason?" discipline.
 *
 * Input artifacts (config-supplied, repo-relative):
 *   - report   — `pit-strength-report.v1` JSON (produced by scripts/ci/report-pit-strength.mjs)
 *   - baseline — `pit-strength-baseline.v1` JSON (the ratchet floor, per seam)
 *
 * Direction is INVERTED vs npm-audit: higher strength is better, so a regression is a decrease.
 */

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

import {
  TEST_EFFICACY_CLASSIFICATIONS,
  aggregateTestEfficacyClassifications,
} from './classifications.mjs';
import { TEST_EFFICACY_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import { loadChangesets } from '../../lib/changeset-loader.mjs';
import { readFileAtRef } from '../../lib/git-utils.mjs';

const TOOL = { toolName: 'justsearch-test-efficacy', toolVersion: '0.1.0' };

export async function enforceTestEfficacy(options) {
  const { repoRoot, gate, baselineRef, rebalance = false, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const reportPath = resolve(sourceRoot, gate.config?.reportPath ?? 'tmp/pit-strength-report.v1.json');
  const baselinePath = resolve(sourceRoot, gate.baseline.path);

  const ruleDescriptions = TEST_EFFICACY_RULE_DESCRIPTIONS;

  if (!existsSync(reportPath)) {
    return {
      ...TOOL,
      findings: [
        {
          ruleId: 'test-efficacy/report-missing',
          level: 'warning',
          message:
            `PIT strength report not found at ${reportPath}. ` +
            `Run \`node scripts/ci/report-pit-strength.mjs --out tmp/pit-strength-report.v1.json\` first.`,
          uri: gate.config?.reportPath ?? 'tmp/pit-strength-report.v1.json',
        },
      ],
      verdict: 'pass',
      ruleDescriptions,
    };
  }
  if (!existsSync(baselinePath)) {
    return {
      ...TOOL,
      findings: [
        {
          ruleId: 'test-efficacy/baseline-missing',
          level: 'error',
          message:
            `test-efficacy baseline not found at ${baselinePath}. ` +
            `Initialize with \`node scripts/governance/run.mjs --gate test-efficacy --rebalance\`.`,
          uri: gate.baseline.path,
        },
      ],
      verdict: 'fail',
      ruleDescriptions,
    };
  }

  const rawReport = JSON.parse(readFileSync(reportPath, 'utf8'));
  const rawBaseline = JSON.parse(readFileSync(baselinePath, 'utf8'));

  const schemaErr = checkSchema(rawReport, 'pit-strength-report.v1', gate.config?.reportPath)
    ?? checkSchema(rawBaseline, 'pit-strength-baseline.v1', gate.baseline.path);
  if (schemaErr) return { ...TOOL, findings: [schemaErr], verdict: 'fail', ruleDescriptions };

  const reportSeams = rawReport.seams ?? {};
  const baselineSeams = rawBaseline.seams ?? {};

  // Register authority (tempdoc 576 §3.2): when a logic-seam register is configured, every seam it
  // declares MUST appear in the strength report — otherwise PIT never measured it (a register seam
  // missing from BOTH baseline and report would otherwise be invisible). Additive + existence-
  // guarded: with no register configured (or in a fixture without one), behaviour is unchanged.
  let registeredSeamIds = [];
  const registerPathRel = gate.config?.registerPath;
  if (registerPathRel) {
    const registerPath = resolve(sourceRoot, registerPathRel);
    if (existsSync(registerPath)) {
      try {
        const reg = JSON.parse(readFileSync(registerPath, 'utf8'));
        registeredSeamIds = (reg.seams ?? []).map((s) => s.id).filter(Boolean);
      } catch {
        // A malformed register is the register's OWN gate's concern, not this one's — ignore here.
      }
    }
  }

  // Changeset escape-hatch protocol.
  const declarations = gate.changesetsDir
    ? loadChangesets({
        repoRoot: sourceRoot,
        changesetsDir: gate.changesetsDir,
        baselineRef,
        allowedClassifications: TEST_EFFICACY_CLASSIFICATIONS,
        classificationField: 'classification',
        requireJustificationFor: new Set([
          'strength-regression',
          'seam-retraction',
          'emergency-override',
        ]),
        fixtureMode,
      })
    : [];
  const aggregated = aggregateTestEfficacyClassifications(declarations);
  const regressionRuleId = aggregated.regressionCovered
    ? aggregated.classifications.find(c => TEST_EFFICACY_CLASSIFICATIONS.has(c))
    : 'silent-regression';

  const findings = [];
  let verdict = 'pass';
  const rebalanceWrites = {};

  const allSeams = [...new Set([...Object.keys(baselineSeams), ...Object.keys(reportSeams), ...registeredSeamIds])].sort();
  for (const seam of allSeams) {
    const baseFloor = Number(baselineSeams[seam]?.minStrength ?? 0);
    // A noCoverage ceiling of Infinity means "not yet ratcheted" (pre-F3 baseline) — don't fire.
    const baseNoCovCeiling = Number(baselineSeams[seam]?.maxNoCoverage ?? Number.POSITIVE_INFINITY);
    if (!(seam in reportSeams)) {
      // F2 (fail-closed): a registered seam absent from an EXISTING report means PIT ran but did
      // not measure a declared seam (typo'd FQCN, module not built, partial run) — that must fail,
      // not silently pass. (Total report-absence is handled earlier as an advisory report-missing.)
      verdict = 'fail';
      findings.push({
        ruleId: 'test-efficacy/seam-not-measured',
        level: 'error',
        message: `Registered seam '${seam}' has no entry in the strength report (PIT did not measure it — check its targetClass FQCN / that its module's :pitest ran).`,
        uri: gate.baseline.path,
      });
      continue;
    }
    const current = Number(reportSeams[seam].strength ?? 0);
    const currentNoCov = Number(reportSeams[seam].noCoverage ?? 0);
    if (current < baseFloor) {
      const level = regressionRuleId === 'silent-regression' ? 'error' : 'note';
      if (regressionRuleId === 'silent-regression') verdict = 'fail';
      findings.push({
        ruleId: `test-efficacy/${regressionRuleId}`,
        level,
        message:
          `${seam} test-strength regressed: ${baseFloor} → ${current} (Δ ${current - baseFloor}). ` +
          (regressionRuleId === 'silent-regression'
            ? `Strengthen the seam's tests to kill the new survivors, or declare in ` +
              `gates/test-efficacy/.changesets/ with 'strength-regression' / 'seam-retraction' / 'emergency-override'.`
            : `Classification '${regressionRuleId}' covers this regression.`),
        uri: gate.baseline.path,
      });
    } else if (current > baseFloor && rebalance) {
      findings.push({
        ruleId: 'test-efficacy/rebalanced',
        level: 'note',
        message: `${seam} test-strength improved: ${baseFloor} → ${current}. Baseline floor auto-raised.`,
        uri: gate.baseline.path,
      });
    } else if (current > baseFloor) {
      findings.push({
        ruleId: 'test-efficacy/rebalance-available',
        level: 'note',
        message: `${seam} test-strength improved: ${baseFloor} → ${current}. Re-run with --rebalance to raise the floor.`,
        uri: gate.baseline.path,
      });
    }

    // F3 (coverage-erosion guard): no-coverage may only DECREASE. Rising no-coverage means new
    // untested branches were added to the seam — strength (killed/covered) would miss this.
    if (currentNoCov > baseNoCovCeiling) {
      if (regressionRuleId === 'silent-regression') verdict = 'fail';
      findings.push({
        ruleId: `test-efficacy/${regressionRuleId === 'silent-regression' ? 'silent-regression' : regressionRuleId}`,
        level: regressionRuleId === 'silent-regression' ? 'error' : 'note',
        message:
          `${seam} no-coverage rose: ${baseNoCovCeiling} → ${currentNoCov} (new untested mutations). ` +
          (regressionRuleId === 'silent-regression'
            ? `Add tests covering the new branches, or declare a changeset.`
            : `Classification '${regressionRuleId}' covers this.`),
        uri: gate.baseline.path,
      });
    }

    // Rebalance: ratchet strength UP and no-coverage DOWN (toward the current measured values).
    if (rebalance && (seam in reportSeams)) {
      const next = {};
      next.minStrength = Math.max(baseFloor, current);
      const flooredCeiling = Number.isFinite(baseNoCovCeiling) ? baseNoCovCeiling : currentNoCov;
      next.maxNoCoverage = Math.min(flooredCeiling, currentNoCov);
      rebalanceWrites[seam] = next;
    }
  }

  if (rebalance && Object.keys(rebalanceWrites).length > 0) {
    const next = { ...rawBaseline, seams: { ...baselineSeams } };
    for (const [seam, vals] of Object.entries(rebalanceWrites)) {
      next.seams[seam] = {
        ...(next.seams[seam] ?? {}),
        minStrength: vals.minStrength,
        maxNoCoverage: vals.maxNoCoverage,
      };
    }
    writeFileSync(baselinePath, JSON.stringify(next, null, 2) + '\n');
  }

  // Baseline-shift detection (tempdoc 530 §Layer 1 closure): a commit can lower a seam's floor in
  // the same diff as the strength drop. Read the baseline at the PR's baseline ref and flag any
  // seam whose floor was LOWERED baseline-side without a classified changeset.
  const prior = readPriorBaseline({ fixtureMode, repoRoot, sourceRoot, baselineRef, baselinePath: gate.baseline.path });
  if (prior) {
    const priorSeams = prior.seams ?? {};
    for (const seam of Object.keys(priorSeams)) {
      const priorFloor = Number(priorSeams[seam]?.minStrength ?? 0);
      const liveFloor = Number(baselineSeams[seam]?.minStrength ?? 0);
      if (liveFloor < priorFloor && !aggregated.regressionCovered) {
        verdict = 'fail';
        findings.push({
          ruleId: 'test-efficacy/silent-baseline-shift',
          level: 'error',
          message: `${seam} baseline floor lowered ${priorFloor} → ${liveFloor} without a declared changeset.`,
          uri: gate.baseline.path,
        });
      }
    }
  }

  if (findings.length === 0) {
    findings.push({
      ruleId: 'test-efficacy/within-baseline',
      level: 'note',
      message: 'All registered seams at or above their baseline strength floor.',
    });
  }

  return { ...TOOL, findings, verdict, ruleDescriptions, rebalanceWrites };
}

function checkSchema(obj, expected, uri) {
  if (obj?.schema !== expected) {
    return {
      ruleId: 'test-efficacy/schema-mismatch',
      level: 'error',
      message: `Unexpected schema '${obj?.schema}' (expected '${expected}')`,
      uri,
    };
  }
  return null;
}

function readPriorBaseline({ fixtureMode, repoRoot, sourceRoot, baselineRef, baselinePath }) {
  if (fixtureMode) {
    // In fixture mode there is no git history; treat the live fixture baseline as the prior too.
    const p = resolve(sourceRoot, baselinePath);
    return existsSync(p) ? JSON.parse(readFileSync(p, 'utf8')) : null;
  }
  if (!baselineRef) return null;
  try {
    const text = readFileAtRef({ repoRoot, ref: baselineRef, path: baselinePath });
    return text ? JSON.parse(text) : null;
  } catch {
    return null;
  }
}
