/**
 * npm-audit truth table — tempdoc 530.
 *
 * Pure verdict functions conforming to the substrate contract at
 * `scripts/governance/lib/truth-table-runner.mjs`.
 */

/**
 * @param {{target: string, severity: string, baseline: number, current: number, classification: string}} input
 */
export function verdictForSeverity(input) {
  const { target, severity, baseline, current, classification } = input;
  if (current === baseline) {
    return {
      ruleId: 'npm-audit/within-baseline',
      status: 'pass',
      reason: `${target}/${severity} at baseline ${baseline}`,
    };
  }
  if (current < baseline) {
    return {
      ruleId: 'npm-audit/rebalance-available',
      status: 'info',
      reason: `${target}/${severity} improved ${baseline} → ${current}`,
    };
  }
  // current > baseline — regression.
  if (classification === 'silent-regression') {
    return {
      ruleId: 'npm-audit/silent-regression',
      status: 'fail',
      reason: `${target}/${severity} regressed ${baseline} → ${current} (Δ +${current - baseline}) without declared changeset`,
    };
  }
  return {
    ruleId: `npm-audit/${classification}`,
    status: 'pass',
    reason: `${target}/${severity} regressed ${baseline} → ${current}; '${classification}' covers it`,
  };
}

/**
 * @param {{target: string, severity: string, priorBaseline: number, liveBaseline: number, classification: string}} input
 */
export function verdictForBaselineShift(input) {
  const { target, severity, priorBaseline, liveBaseline, classification } = input;
  if (liveBaseline <= priorBaseline) {
    return {
      ruleId: 'npm-audit/baseline-stable',
      status: 'pass',
      reason: `${target}/${severity} baseline unchanged or shrinking`,
    };
  }
  if (classification === 'silent-regression') {
    return {
      ruleId: 'npm-audit/silent-baseline-shift',
      status: 'fail',
      reason: `${target}/${severity} baseline raised ${priorBaseline} → ${liveBaseline} without declared changeset`,
    };
  }
  return {
    ruleId: 'npm-audit/declared-baseline-shift',
    status: 'info',
    reason: `${target}/${severity} baseline raised ${priorBaseline} → ${liveBaseline}; '${classification}' covers it`,
  };
}
