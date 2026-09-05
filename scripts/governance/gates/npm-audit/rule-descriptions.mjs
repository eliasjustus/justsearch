/** SARIF rule descriptions for the historical npm-audit gate id. */

export const NPM_AUDIT_RULE_DESCRIPTIONS = {
  'npm-audit/within-baseline': 'Advisory identity and severity are within the accepted baseline',
  'npm-audit/silent-regression': 'A new high/critical advisory or severity escalation lacks a declared changeset',
  'npm-audit/declared-regression-without-repin': 'Declared advisory regression was not repinned in the same change',
  'npm-audit/lockfile-import-without-repin': 'Imported advisory regression was not repinned in the same change',
  'npm-audit/emergency-override-without-repin': 'Emergency advisory override was not repinned in the same change',
  'npm-audit/rebalance-available': 'An accepted advisory resolved or decreased in severity; baseline may be rebalanced',
  'npm-audit/rebalanced': 'Resolved or improved advisory identities were removed from the baseline',
  'npm-audit/baseline-missing': 'Advisory identity baseline file is missing',
  'npm-audit/schema-mismatch': 'Advisory report or baseline does not match the required schema',
  'npm-audit/report-unavailable': 'A required lockfile target lacks complete GitHub advisory evidence',
  'npm-audit/baseline-entry-not-current': 'A newly accepted baseline identity is absent from current evidence',
  'npm-audit/silent-baseline-shift': 'An advisory identity or severity was accepted without a declared changeset',
  'npm-audit/declared-baseline-shift': 'An advisory identity or severity acceptance is covered by a changeset',
  'npm-audit/changeset-mismatch': 'A changeset exists without a matching advisory regression or baseline expansion',
};
