/**
 * todo-fixme truth-table — tempdoc 530 §2.6.
 * Conforms to scripts/governance/lib/truth-table-runner.mjs.
 */

/** @param {{path: string, current: number, pinned: number, classification: string}} input */
export function verdictForFile(input) {
  const { path, current, pinned, classification } = input;
  if (current <= pinned) {
    if (current < pinned) {
      return {
        ruleId: 'todo-fixme/rebalance-available',
        status: 'info',
        reason: `${path}: ${current} ≤ pinned ${pinned} (rebalance available)`,
      };
    }
    return {
      ruleId: 'todo-fixme/within-baseline',
      status: 'pass',
      reason: `${path}: ${current} at baseline`,
    };
  }
  if (classification === 'silent-growth') {
    return {
      ruleId: 'todo-fixme/silent-growth',
      status: 'fail',
      reason: `${path}: ${pinned} → ${current} TODOs without declared changeset`,
    };
  }
  return {
    ruleId: `todo-fixme/${classification}`,
    status: 'pass',
    reason: `${path}: ${pinned} → ${current} TODOs; '${classification}' covers`,
  };
}

/** @param {{path: string, priorPin: number, livePin: number, classification: string}} input */
export function verdictForBaselineShift(input) {
  const { path, priorPin, livePin, classification } = input;
  if (livePin <= priorPin) {
    return {
      ruleId: 'todo-fixme/within-baseline',
      status: 'pass',
      reason: `${path}: baseline unchanged or tightening`,
    };
  }
  if (classification === 'silent-growth') {
    return {
      ruleId: 'todo-fixme/silent-baseline-shift',
      status: 'fail',
      reason: `${path}: baseline raised ${priorPin} → ${livePin} without declared changeset`,
    };
  }
  return {
    ruleId: 'todo-fixme/declared-growth',
    status: 'info',
    reason: `${path}: baseline raised ${priorPin} → ${livePin}; '${classification}' covers`,
  };
}
