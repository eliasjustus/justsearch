/** dead-code-jvm truth-table — tempdoc 638. Verdict logic mirrors enforcer.mjs (set ratchet). */
export function verdictForClass({ symbol, inBaseline, inReport, growthCovered }) {
  if (inReport && !inBaseline) {
    return growthCovered
      ? {
          ruleId: 'dead-code-jvm/declared-growth',
          status: 'info',
          reason: `${symbol}: new dead class; classification covers`,
        }
      : {
          ruleId: 'dead-code-jvm/new-dead-class',
          status: 'fail',
          reason: `${symbol}: newly whole-program-unreferenced without a declared changeset`,
        };
  }
  if (inBaseline && !inReport) {
    return {
      ruleId: 'dead-code-jvm/rebalance-available',
      status: 'info',
      reason: `${symbol}: in baseline but no longer dead`,
    };
  }
  return {
    ruleId: 'dead-code-jvm/within-baseline',
    status: 'pass',
    reason: `${symbol}: within baseline`,
  };
}
