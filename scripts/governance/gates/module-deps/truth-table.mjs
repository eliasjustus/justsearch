/** module-deps truth-table — tempdoc 530 §2.8. */
export function verdictForModule({ module, current, pinned, classification }) {
  if (current <= pinned) return current < pinned
    ? { ruleId: 'module-deps/rebalance-available', status: 'info', reason: `${module}: ${current} < ${pinned}` }
    : { ruleId: 'module-deps/within-baseline', status: 'pass', reason: `${module}: at baseline` };
  return classification === 'silent-growth'
    ? { ruleId: 'module-deps/silent-growth', status: 'fail', reason: `${module}: silent dep growth` }
    : { ruleId: 'module-deps/declared-growth', status: 'pass', reason: `${module}: classification covers` };
}

/**
 * Was a pinned number in the module-deps baseline RAISED in this PR without a changeset saying so?
 * Mirrors dead-code. Added by tempdoc 910: the ruleId was declared but unreachable.
 *
 * @param {{module: string, priorPin: number, livePin: number, classification: string}} input
 */
export function verdictForBaselineShift({ module, priorPin, livePin, classification }) {
  if (livePin <= priorPin) {
    return {
      ruleId: 'module-deps/within-baseline',
      status: 'pass',
      reason: `${module}: baseline unchanged or tightening`,
    };
  }
  if (classification === 'silent-growth') {
    return {
      ruleId: 'module-deps/silent-baseline-shift',
      status: 'fail',
      reason: `${module}: baseline raised ${priorPin} → ${livePin} without declared changeset`,
    };
  }
  return {
    ruleId: 'module-deps/declared-growth',
    status: 'info',
    reason: `${module}: baseline raised ${priorPin} → ${livePin}; '${classification}' covers`,
  };
}
