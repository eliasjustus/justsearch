/**
 * transient-arbitration truth-table — tempdoc 574 §25 Phase 4. A positive-
 * coverage scan: any uncovered adopter is a FAIL (no baseline). The per-finding
 * verdict is inlined in the shared `makeScanGate` factory; this is the kernel-
 * required pure verdict function.
 */
export function verdictForScan({ violationCount = 0 }) {
  return violationCount > 0
    ? {
        ruleId: 'transient-arbitration/missing-controller',
        status: 'fail',
        reason: `${violationCount} adopter(s) do not compose TransientController`,
      }
    : {
        ruleId: 'transient-arbitration/covered',
        status: 'pass',
        reason: 'every adopter composes the controller',
      };
}
