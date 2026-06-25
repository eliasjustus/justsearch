/** module-deps truth-table — tempdoc 530 §2.8. */
export function verdictForModule({ module, current, pinned, classification }) {
  if (current <= pinned) return current < pinned
    ? { ruleId: 'module-deps/rebalance-available', status: 'info', reason: `${module}: ${current} < ${pinned}` }
    : { ruleId: 'module-deps/within-baseline', status: 'pass', reason: `${module}: at baseline` };
  return classification === 'silent-growth'
    ? { ruleId: 'module-deps/silent-growth', status: 'fail', reason: `${module}: silent dep growth` }
    : { ruleId: 'module-deps/declared-growth', status: 'pass', reason: `${module}: classification covers` };
}
