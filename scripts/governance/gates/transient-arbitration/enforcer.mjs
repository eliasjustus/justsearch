/**
 * transient-arbitration enforcer — tempdoc 574 §25 Phase 4 (Edge 4).
 *
 * Folds the standalone `scripts/ci/check-transient-arbitration.mjs` into the 530
 * kernel via the shared {@link makeScanGate} factory. Positive-coverage: every
 * `governance/transients.v1.json` adopter MUST compose the one TransientController
 * symbol. Detection is REUSED verbatim (the CLI and this enforcer call the same
 * `detect({ root })`).
 */
import { makeScanGate } from '../../lib/scan-gate.mjs';
import { detect } from '../../../ci/check-transient-arbitration.mjs';

export const TRANSIENT_ARBITRATION_RULE_DESCRIPTIONS = {
  'transient-arbitration/missing-controller':
    'A registered transient adopter does not compose the one TransientController (single-open by construction)',
};

export const enforceTransientArbitration = makeScanGate({
  detect,
  toolName: 'justsearch-transient-arbitration',
  rulePrefix: 'transient-arbitration',
  ruleDescriptions: TRANSIENT_ARBITRATION_RULE_DESCRIPTIONS,
  defaultRule: 'missing-controller',
});
