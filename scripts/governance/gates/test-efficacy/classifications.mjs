/**
 * test-efficacy gate classification vocabulary — tempdoc 555 (Pillar C).
 *
 * Used when a PR lowers a registered seam's mutation test-strength below its baseline floor.
 * Without a changeset, the gate fails with `test-efficacy/silent-regression`.
 */

export const TEST_EFFICACY_CLASSIFICATIONS = new Set([
  'strength-regression', // a deliberate, justified drop (e.g. a seam genuinely simplified)
  'seam-retraction', // a seam is being removed from the register
  'emergency-override', // escape hatch; manual review required
]);

/**
 * @param {Array<{classification: string}>} declarations
 */
export function aggregateTestEfficacyClassifications(declarations) {
  const classifications = declarations.map(d => d.classification);
  const regressionCovered = classifications.some(c =>
    ['strength-regression', 'seam-retraction', 'emergency-override'].includes(c),
  );
  return { regressionCovered, classifications };
}
