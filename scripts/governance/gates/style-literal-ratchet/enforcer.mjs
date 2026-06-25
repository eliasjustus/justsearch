/**
 * style-literal-ratchet enforcer — tempdoc 574 §25 Phase 4 (Edge 4).
 *
 * Folds the standalone `scripts/ci/check-style-literal-ratchet.mjs` into the 530
 * discipline-gate kernel. Detection is REUSED verbatim from that script (one
 * authority — the CLI and this enforcer call the same `detect()`); this file only
 * wires it into the kernel via the shared {@link makeRatchetGate} factory, gaining
 * the run-record / SARIF / changeset-justification machinery the standalone script
 * lacked. The single shared baseline (`scripts/ci/style-literal-ratchet-baseline.v1.json`)
 * is read by both — NOT moved/forked.
 */
import { makeRatchetGate } from '../../lib/ratchet-gate.mjs';
import { detect, rebalanceBaseline } from '../../../ci/check-style-literal-ratchet.mjs';

export const STYLE_LITERAL_CLASSIFICATIONS = new Set([
  'declared-growth',
  'merge-import',
  'emergency-override',
  'monotonic-shrink',
]);

export const STYLE_LITERAL_RULE_DESCRIPTIONS = {
  'style-literal-ratchet/within-baseline':
    'No new raw z-index/transition/font-size literals (all route through their token)',
  'style-literal-ratchet/silent-growth':
    'A file gained a raw style literal above baseline without a declared changeset',
  'style-literal-ratchet/declared-growth':
    'Style-literal growth covered by a declared changeset',
  'style-literal-ratchet/merge-import':
    'Style-literal growth via merge; classification supplied',
  'style-literal-ratchet/emergency-override':
    'Style-literal growth permitted via emergency-override',
  'style-literal-ratchet/rebalance-available':
    'A file shrank below baseline; --rebalance available',
  'style-literal-ratchet/rebalanced': 'Baseline auto-updated',
};

export const enforceStyleLiteralRatchet = makeRatchetGate({
  detect,
  rebalanceBaseline,
  srcSubdir: 'modules/ui-web/src',
  toolName: 'justsearch-style-literal-ratchet',
  rulePrefix: 'style-literal-ratchet',
  ruleDescriptions: STYLE_LITERAL_RULE_DESCRIPTIONS,
  classifications: STYLE_LITERAL_CLASSIFICATIONS,
});
