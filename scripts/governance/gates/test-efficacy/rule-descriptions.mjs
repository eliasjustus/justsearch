/**
 * SARIF rule descriptions for the test-efficacy gate (tempdoc 555 Pillar C).
 */

export const TEST_EFFICACY_RULE_DESCRIPTIONS = {
  'test-efficacy/within-baseline': 'Seam mutation test-strength at or above baseline floor',
  'test-efficacy/silent-regression':
    "A registered seam's mutation test-strength dropped below its baseline floor without a declared changeset",
  'test-efficacy/strength-regression':
    'Strength drop covered by an explicit strength-regression changeset',
  'test-efficacy/seam-retraction': 'Seam removed from the register; drop covered by classification',
  'test-efficacy/emergency-override':
    'Strength drop permitted via emergency-override (manual review required)',
  'test-efficacy/rebalance-available':
    'Seam strength rose above baseline; baseline floor may be rebalanced upward',
  'test-efficacy/rebalanced': 'Baseline floor automatically raised to the current strength',
  'test-efficacy/seam-not-measured':
    'A registered seam has no entry in the strength report (was PIT run for its module?)',
  'test-efficacy/report-missing':
    'PIT strength report not found (run scripts/ci/report-pit-strength.mjs first)',
  'test-efficacy/baseline-missing':
    'test-efficacy baseline not found (initialize with --rebalance after a clean PIT run)',
  'test-efficacy/schema-mismatch': 'Strength report or baseline does not match the expected schema',
  'test-efficacy/silent-baseline-shift':
    'Baseline floor was lowered in this PR without a declared changeset (the silent escape-hatch class tempdoc 530 §Layer 1 closes)',
  'test-efficacy/declared-baseline-shift': 'Baseline floor was lowered; classification covers the shift',
};
