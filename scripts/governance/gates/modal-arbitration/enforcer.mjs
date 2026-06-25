/**
 * modal-arbitration enforcer — tempdoc 574 §25 Phase 4 (Edge 4).
 *
 * Folds the standalone `scripts/ci/check-modal-arbitration.mjs` into the 530
 * kernel via the shared {@link makeScanGate} factory. Positive-coverage: every
 * `governance/modals.v1.json` adopter MUST compose the one ModalController symbol
 * (the full modal contract by construction). Detection is REUSED verbatim.
 */
import { makeScanGate } from '../../lib/scan-gate.mjs';
import { detect } from '../../../ci/check-modal-arbitration.mjs';

export const MODAL_ARBITRATION_RULE_DESCRIPTIONS = {
  'modal-arbitration/missing-controller':
    'A registered modal host does not compose the one ModalController (full modal contract by construction)',
};

export const enforceModalArbitration = makeScanGate({
  detect,
  toolName: 'justsearch-modal-arbitration',
  rulePrefix: 'modal-arbitration',
  ruleDescriptions: MODAL_ARBITRATION_RULE_DESCRIPTIONS,
  defaultRule: 'missing-controller',
});
