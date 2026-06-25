/**
 * npm-audit gate classification vocabulary — tempdoc 530.
 *
 * Used when a PR raises a tracked-severity (default: high, critical) vulnerability
 * count above its baseline. Without a changeset, the gate fails with
 * `npm-audit/silent-regression`.
 */

export const NPM_AUDIT_CLASSIFICATIONS = new Set([
  'declared-regression',
  'lockfile-import',
  'emergency-override',
  'severity-decrease',
]);

/**
 * @param {Array<{classification: string}>} declarations
 */
export function aggregateNpmAuditClassifications(declarations) {
  const classifications = declarations.map(d => d.classification);
  const regressionCovered = classifications.some(c =>
    ['declared-regression', 'lockfile-import', 'emergency-override'].includes(c),
  );
  return { regressionCovered, classifications };
}
