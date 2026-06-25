/** test-to-code truth-table — tempdoc 530 §2.10. Verdict logic inlined in enforcer.mjs. */
export function verdictForModule({ module, current, baseline, classification }) {
  if (current >= baseline) {
    return current > baseline
      ? { ruleId: 'test-to-code/rebalance-available', status: 'info', reason: `${module}: ${current/10}% > baseline ${baseline/10}%` }
      : { ruleId: 'test-to-code/within-baseline', status: 'pass', reason: `${module}: at baseline` };
  }
  if (classification === 'silent-regression') {
    return { ruleId: 'test-to-code/silent-regression', status: 'fail', reason: `${module}: ratio dropped` };
  }
  return { ruleId: 'test-to-code/declared-regression', status: 'info', reason: `${module}: classification covers` };
}
