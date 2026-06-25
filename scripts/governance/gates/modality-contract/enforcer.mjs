/**
 * modality-contract enforcer — tempdoc 574 §25 Phase 4 (Edge 4).
 *
 * Folds the standalone `scripts/ci/check-modality-contract.mjs` into the 530
 * kernel via the shared {@link makeScanGate} factory. Whole-tree scan: any file
 * calling `.showModal()` without composing a `ModalityController` is a half-wired
 * modal. Detection is REUSED verbatim (the backstop to the modal-arbitration
 * positive-coverage gate).
 */
import { makeScanGate } from '../../lib/scan-gate.mjs';
import { detect } from '../../../ci/check-modality-contract.mjs';

export const MODALITY_CONTRACT_RULE_DESCRIPTIONS = {
  'modality-contract/half-wired-modal':
    'A file calls .showModal() but does not compose a ModalityController (leaks scroll-lock / focus-restore)',
};

export const enforceModalityContract = makeScanGate({
  detect,
  toolName: 'justsearch-modality-contract',
  rulePrefix: 'modality-contract',
  ruleDescriptions: MODALITY_CONTRACT_RULE_DESCRIPTIONS,
  defaultRule: 'half-wired-modal',
});
