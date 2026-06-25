/**
 * SARIF rule descriptions for the npm-audit gate (tempdoc 530).
 */

export const NPM_AUDIT_RULE_DESCRIPTIONS = {
  'npm-audit/within-baseline': 'Severity counts within baseline',
  'npm-audit/silent-regression':
    'Tracked-severity count increased above baseline without a declared changeset',
  'npm-audit/declared-regression':
    'Severity count increase covered by an explicit declared-regression changeset',
  'npm-audit/lockfile-import': 'Regression imported via lockfile sync; classification supplied',
  'npm-audit/emergency-override':
    'Regression permitted via emergency-override classification (manual review required)',
  'npm-audit/rebalance-available':
    'Severity counts dropped below baseline; baseline may be rebalanced',
  'npm-audit/rebalanced':
    'Baseline automatically rebalanced (counts updated to current values)',
  'npm-audit/report-missing':
    'npm audit report file not found at the configured path (was the npm audit step run?)',
  'npm-audit/baseline-missing':
    'npm-audit baseline file not found (initialize with --rebalance after a clean audit)',
  'npm-audit/schema-mismatch':
    'npm-audit report or baseline does not match the expected schema',
  'npm-audit/silent-baseline-shift':
    'Baseline JSON was relaxed in this PR without a declared changeset (the same silent escape-hatch class tempdoc 530 §Layer 1 closes)',
  'npm-audit/declared-baseline-shift':
    'Baseline JSON was relaxed; classification covers the shift',
};
