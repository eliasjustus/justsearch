/**
 * SARIF rule descriptions for the consumer-drift gate (tempdoc 531).
 */

export const CONSUMER_DRIFT_RULE_DESCRIPTIONS = {
  'consumer-drift/healthy':
    'Substrate slot has at least its required minimum production consumers',
  'consumer-drift/within-grace':
    'Slot is below its consumer floor but inside its declared grace window',
  'consumer-drift/below-min':
    'Slot dropped below its required consumer minimum with grace expired and no classifying changeset — the recurring C-018 substrate-without-consumer failure (tempdoc 531)',
  'consumer-drift/retracted':
    'Slot intentionally retracted (declaredIn source deleted); drift below min is expected',
  'consumer-drift/misclassified-retraction':
    "Changeset declared 'slot-retraction' but the slot's declaredIn source still exists",
  'consumer-drift/grace-extension':
    'Consumer is in-flight; grace extended via a justified changeset',
  'consumer-drift/emergency-override':
    'Drift permitted via emergency-override classification (manual review required)',
  'consumer-drift/stale-slot':
    "A slot's declaredIn source no longer exists but the slot was not retracted via a changeset",
  'consumer-drift/changeset-mismatch':
    'A changeset names a slot that is not declared in slots.json (stale or typo)',
  'consumer-drift/silent-floor-drop':
    "A slot's expectedMin was lowered vs the baseline ref without a declared changeset — the silent escape-hatch class the kernel's pin-bump detection closes (tempdoc 530 §Layer 1), applied to consumer floors",
  'consumer-drift/declared-floor-drop':
    "A slot's expectedMin was lowered with a justifying grace-extension / emergency-override changeset",
  'consumer-drift/floor-raised':
    "A slot's expectedMin was raised (tightening; no changeset required)",
  'consumer-drift/floor-unchanged': "A slot's expectedMin is unchanged vs the baseline ref",
  'consumer-drift/silent-slot-removal':
    'A slot present at the baseline ref was removed from slots.json without a slot-retraction changeset (untracking a still-present substrate)',
  'consumer-drift/declared-slot-removal':
    'A slot was removed via a slot-retraction changeset (its declaredIn source was deleted)',
  'consumer-drift/malformed-slots':
    'gates/consumer-drift/slots.json failed to parse — the gate fails (rather than crashing the whole governance run)',
  'consumer-drift/uncovered-read-view':
    'A read-view custom element is defined but has NO production mount (not referenced in any other production file, not registered via registerViewFactory, not grandfathered in knownUncovered) — the plumbed-but-invisible read-view class (tempdoc 550 thesis II), now caught automatically for every read-view rather than per hand-added slot',
  'consumer-drift/read-view-grandfathered':
    'A pre-existing uncovered read-view is grandfathered via discovery.knownUncovered (dated debt; should be mounted or removed)',
  'consumer-drift/silent-known-uncovered-add':
    'A read-view was added to discovery.knownUncovered vs the baseline without a classifying changeset — silently exempting it from the read-view coverage gate (tempdoc 550 F4; the silent-weakening class the slot floor-drop guard also closes)',
  'consumer-drift/uncovered-substrate':
    'A discovered substrate (discovery.roots/entryGlob) has no covering slot and is not grandfathered in discovery.knownUncovered — a new substrate silently escaping consumer-drift coverage (tempdoc 548 §5.2 catalog-completeness closure)',
  'consumer-drift/grandfathered-substrate':
    'A discovered substrate is not yet covered by a measured slot but is grandfathered via discovery.knownUncovered (ratchet-from-here; add a measured slot to tighten)',
  'consumer-drift/substrate-covered':
    'A discovered substrate is covered by a declared slot (substrate: <id>)',
  'consumer-drift/stale-grandfather':
    'A discovery.knownUncovered entry is now covered by a slot or no longer discovered — prune it so the grandfather list does not ossify',
};
