/**
 * modality-contract truth-table — tempdoc 574 §25 Phase 4. Whole-tree scan: any
 * half-wired modal is a FAIL (no baseline). Verdict inlined in the shared
 * `makeScanGate` factory; this is the kernel-required pure function.
 */
export function verdictForScan({ violationCount = 0 }) {
  return violationCount > 0
    ? {
        ruleId: 'modality-contract/half-wired-modal',
        status: 'fail',
        reason: `${violationCount} .showModal() site(s) without a ModalityController`,
      }
    : {
        ruleId: 'modality-contract/complete',
        status: 'pass',
        reason: 'every .showModal() composes a ModalityController',
      };
}
