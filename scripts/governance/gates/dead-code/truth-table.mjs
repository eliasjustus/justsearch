/** dead-code truth-table — tempdoc 530 §2.9. Verdict logic inlined in enforcer.mjs. */
export function verdictForFile({ path, current, pinned, classification }) {
  if (current <= pinned) return current < pinned
    ? { ruleId: 'dead-code/rebalance-available', status: 'info', reason: `${path}: ${current} < ${pinned}` }
    : { ruleId: 'dead-code/within-baseline', status: 'pass', reason: `${path}: at baseline` };
  return classification === 'silent-growth'
    ? { ruleId: 'dead-code/silent-growth', status: 'fail', reason: `${path}: silent growth` }
    : { ruleId: 'dead-code/declared-growth', status: 'info', reason: `${path}: classification covers` };
}
