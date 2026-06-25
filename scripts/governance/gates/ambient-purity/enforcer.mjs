/**
 * ambient-purity enforcer — tempdoc 574 §25 Phase 4 (Edge 4).
 *
 * Folds the standalone `scripts/ci/check-ambient-purity.mjs` into the 530 kernel
 * via the shared {@link makeScanGate} factory. Catalog-projected (574 §22.F):
 * (a) Class-B ambient facets banned outside the authority sheet, (b) the authority
 * defines every catalog facet, (c) every shell-v0 component extends JfElement.
 * Detection is REUSED verbatim from the standalone script.
 */
import { makeScanGate } from '../../lib/scan-gate.mjs';
import { detect } from '../../../ci/check-ambient-purity.mjs';

export const AMBIENT_PURITY_RULE_DESCRIPTIONS = {
  'ambient-purity/ambient-outside-authority':
    'A Class-B ambient facet (scrollbar/selection/placeholder/spin keyframes) is re-authored outside the authority sheet',
  'ambient-purity/raw-litelement-base':
    'A shell-v0 component extends raw LitElement instead of JfElement (misses the adopted ambient sheet)',
  'ambient-purity/authority-missing-facet':
    'The authority sheet does not define a Class-B facet the catalog declares',
};

export const enforceAmbientPurity = makeScanGate({
  detect,
  toolName: 'justsearch-ambient-purity',
  rulePrefix: 'ambient-purity',
  ruleDescriptions: AMBIENT_PURITY_RULE_DESCRIPTIONS,
  defaultRule: 'ambient-outside-authority',
});
