/**
 * population-floor — the §5 vacuous-pass guard (tempdoc 576 §5).
 *
 * A positive-coverage gate proves "every DETECTED X is a DECLARED X". If its detection (a
 * filesystem path-glob scan) silently collapses to zero — a renamed module root, a moved source
 * dir — then "no undeclared X found" is vacuously true and the gate PASSES while enforcing nothing.
 * That is a silent downgrade of enforcement strength (rung 2 → effectively rung 5) with no diff to
 * the register: exactly the failure §5 names ("a renamed dir makes a closure check silently match
 * nothing → the kernel asserts expectedMinPopulation >= 1 per register").
 *
 * This is the one shared assertion: the scan's DETECTED population must be >= the register's
 * declared floor (default 1). A scan-based coverage enforcer calls verdictForVacuousScan() with its
 * detected count right after it computes the scan, before its coverage checks. Reused across the
 * path-walk coverage gates (execution-surface, …) so the floor is one implementation, not N.
 *
 * Returns the kernel truth-table verdict shape: { ruleId, status, reason }.
 */

/**
 * @param {object} a
 * @param {string} a.rulePrefix - the gate's rule namespace (e.g. "execution-surface").
 * @param {number} a.detected   - the size of the scan's detected population this run.
 * @param {number} [a.min=1]    - the register's declared minimum (scan.expectedMinPopulation).
 * @param {string} [a.what="scan-detected surfaces"] - human label for the population.
 */
export function verdictForVacuousScan({ rulePrefix, detected, min = 1, what = 'scan-detected surfaces' }) {
  const floor = Number.isFinite(min) && min > 0 ? Math.floor(min) : 1;
  if (detected < floor) {
    return {
      ruleId: `${rulePrefix}/vacuous-scan`,
      status: 'fail',
      reason:
        `The detection scan found ${detected} ${what} (expected >= ${floor}). A positive-coverage ` +
        `gate whose scan collapses to zero passes VACUOUSLY — it enforces nothing while reporting ` +
        `green (tempdoc 576 §5 vacuous-pass). This usually means a scan root was renamed/moved, not ` +
        `that the surfaces were legitimately removed. Fix the scan config (scan.javaMainRoots / ` +
        `tsRoots), or — if the population genuinely shrank — lower scan.expectedMinPopulation in the ` +
        `register with that change.`,
    };
  }
  return {
    ruleId: `${rulePrefix}/scan-population-live`,
    status: 'pass',
    reason: `The detection scan found ${detected} ${what} (>= floor ${floor}); not vacuous.`,
  };
}
