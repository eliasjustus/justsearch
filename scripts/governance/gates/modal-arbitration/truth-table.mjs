/**
 * modal-arbitration truth-table — tempdoc 574 §25 Phase 4. Positive-coverage
 * scan: any uncovered modal host is a FAIL (no baseline). Verdict inlined in the
 * shared `makeScanGate` factory; this is the kernel-required pure function.
 */
export function verdictForScan({ violationCount = 0 }) {
  return violationCount > 0
    ? {
        ruleId: 'modal-arbitration/missing-controller',
        status: 'fail',
        reason: `${violationCount} modal host(s) do not compose ModalController`,
      }
    : {
        ruleId: 'modal-arbitration/covered',
        status: 'pass',
        reason: 'every modal host composes the controller',
      };
}
