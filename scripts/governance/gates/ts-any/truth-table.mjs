/** ts-any truth-table — tempdoc 530 §2.5. Verdict logic inlined in enforcer.mjs for terseness. */
export function verdictForFile({ path, current, pinned, classification }) {
  if (current <= pinned) {
    return current < pinned
      ? { ruleId: 'ts-any/rebalance-available', status: 'info', reason: `${path}: ${current} < ${pinned}` }
      : { ruleId: 'ts-any/within-baseline', status: 'pass', reason: `${path}: at baseline` };
  }
  if (classification === 'silent-growth') {
    return { ruleId: 'ts-any/silent-growth', status: 'fail', reason: `${path}: ${pinned} → ${current}` };
  }
  return { ruleId: `ts-any/${classification}`, status: 'pass', reason: `${path}: classification covers` };
}
