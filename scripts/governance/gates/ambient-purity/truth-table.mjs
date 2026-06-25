/**
 * ambient-purity truth-table — tempdoc 574 §25 Phase 4. Catalog-projected scan:
 * any ban / missing-base / missing-facet violation is a FAIL (no baseline).
 * Verdict inlined in the shared `makeScanGate` factory; this is the kernel-
 * required pure function.
 */
export function verdictForScan({ violationCount = 0 }) {
  return violationCount > 0
    ? {
        ruleId: 'ambient-purity/ambient-outside-authority',
        status: 'fail',
        reason: `${violationCount} ambient-purity violation(s)`,
      }
    : {
        ruleId: 'ambient-purity/pure',
        status: 'pass',
        reason: 'ambient authority + JfElement base intact',
      };
}
