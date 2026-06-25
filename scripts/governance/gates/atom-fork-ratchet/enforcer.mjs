/**
 * atom-fork-ratchet enforcer — tempdoc 574 §25 Phase 4 (Edge 4).
 *
 * Folds the standalone `scripts/ci/check-atom-fork-ratchet.mjs` into the 530
 * kernel via the shared {@link makeRatchetGate} factory. Detection (the catalog-
 * projected raw atom-class scan) is REUSED verbatim — the CLI and this enforcer
 * call the same `detect()`. The single shared baseline
 * (`scripts/ci/atom-fork-ratchet-baseline.v1.json`) is read by both, not forked.
 */
import { makeRatchetGate } from '../../lib/ratchet-gate.mjs';
import { detect, rebalanceBaseline } from '../../../ci/check-atom-fork-ratchet.mjs';

export const ATOM_FORK_CLASSIFICATIONS = new Set([
  'declared-growth',
  'merge-import',
  'emergency-override',
  'monotonic-shrink',
]);

export const ATOM_FORK_RULE_DESCRIPTIONS = {
  'atom-fork-ratchet/within-baseline':
    'No new raw atom-class CSS rules outside the @atom authority',
  'atom-fork-ratchet/silent-growth':
    'A file gained a raw atom-class CSS rule above baseline without a declared changeset',
  'atom-fork-ratchet/declared-growth':
    'Atom-fork growth covered by a declared changeset',
  'atom-fork-ratchet/merge-import':
    'Atom-fork growth via merge; classification supplied',
  'atom-fork-ratchet/emergency-override':
    'Atom-fork growth permitted via emergency-override',
  'atom-fork-ratchet/rebalance-available':
    'A file shrank below baseline; --rebalance available',
  'atom-fork-ratchet/rebalanced': 'Baseline auto-updated',
};

export const enforceAtomForkRatchet = makeRatchetGate({
  detect,
  rebalanceBaseline,
  srcSubdir: 'modules/ui-web/src/shell-v0',
  toolName: 'justsearch-atom-fork-ratchet',
  rulePrefix: 'atom-fork-ratchet',
  ruleDescriptions: ATOM_FORK_RULE_DESCRIPTIONS,
  classifications: ATOM_FORK_CLASSIFICATIONS,
});
