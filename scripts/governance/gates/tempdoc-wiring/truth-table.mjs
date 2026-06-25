/** tempdoc-wiring truth-table — tempdoc 530 §4.1. */
export function verdictForGateReference({ tempdoc, gate, exists }) {
  return exists
    ? { ruleId: 'tempdoc-wiring/within-spec', status: 'pass', reason: `${tempdoc}: gate '${gate}' resolves` }
    : { ruleId: 'tempdoc-wiring/unknown-gate', status: 'fail', reason: `${tempdoc}: gate '${gate}' not in registry` };
}
export function verdictForClassification({ tempdoc, gate, classification, allowed }) {
  return allowed
    ? { ruleId: 'tempdoc-wiring/within-spec', status: 'pass', reason: `${tempdoc}/${gate}: '${classification}' OK` }
    : { ruleId: 'tempdoc-wiring/unknown-classification', status: 'fail', reason: `${tempdoc}/${gate}: '${classification}' not allowed` };
}
