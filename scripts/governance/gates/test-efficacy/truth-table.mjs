/**
 * test-efficacy truth table — tempdoc 555 (Pillar C).
 *
 * Pure verdict functions conforming to the substrate contract at
 * `scripts/governance/lib/truth-table-runner.mjs`. Direction is inverted vs npm-audit: a HIGHER
 * mutation test-strength is better, so a regression is a DECREASE below the baseline floor.
 */

/**
 * @param {{seam: string, baseline: number, current: number, classification: string}} input
 */
export function verdictForSeam(input) {
  const { seam, baseline, current, classification } = input;
  if (current >= baseline) {
    return current > baseline
      ? {
          ruleId: 'test-efficacy/rebalance-available',
          status: 'info',
          reason: `${seam} strength rose ${baseline} → ${current}`,
        }
      : {
          ruleId: 'test-efficacy/within-baseline',
          status: 'pass',
          reason: `${seam} strength at baseline ${baseline}`,
        };
  }
  // current < baseline — regression.
  if (classification === 'silent-regression') {
    return {
      ruleId: 'test-efficacy/silent-regression',
      status: 'fail',
      reason: `${seam} strength regressed ${baseline} → ${current} (Δ ${current - baseline}) without declared changeset`,
    };
  }
  return {
    ruleId: `test-efficacy/${classification}`,
    status: 'pass',
    reason: `${seam} strength regressed ${baseline} → ${current}; '${classification}' covers it`,
  };
}

/**
 * @param {{seam: string, priorBaseline: number, liveBaseline: number, classification: string}} input
 */
export function verdictForBaselineShift(input) {
  const { seam, priorBaseline, liveBaseline, classification } = input;
  if (liveBaseline >= priorBaseline) {
    return {
      ruleId: 'test-efficacy/baseline-stable',
      status: 'pass',
      reason: `${seam} baseline floor unchanged or raised`,
    };
  }
  if (classification === 'silent-regression') {
    return {
      ruleId: 'test-efficacy/silent-baseline-shift',
      status: 'fail',
      reason: `${seam} baseline floor lowered ${priorBaseline} → ${liveBaseline} without declared changeset`,
    };
  }
  return {
    ruleId: 'test-efficacy/declared-baseline-shift',
    status: 'info',
    reason: `${seam} baseline floor lowered ${priorBaseline} → ${liveBaseline}; '${classification}' covers it`,
  };
}
