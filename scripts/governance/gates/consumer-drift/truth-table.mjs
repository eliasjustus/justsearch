/**
 * Consumer-drift truth table — tempdoc 531 (gate kind on the 530 kernel).
 *
 * Pure verdict function used by `enforcer.mjs`. Conforms to the kernel's
 * truth-table contract: (input) → { ruleId, status: 'pass'|'fail'|'info', reason }.
 *
 * A "slot" is a declared substrate symbol that C-018 requires to retain at
 * least `expectedMin` production consumers. The enforcer counts current
 * production callsites per slot; this table maps (count, expectedMin, grace,
 * changeset classification) to a verdict.
 *
 *   verdictForSlot (count, expectedMin, withinGrace, classification, declaredInExists)
 *     - count >= expectedMin                          → pass (healthy)
 *     - count <  expectedMin && withinGrace           → info (within-grace)
 *     - count <  expectedMin && slot-retraction
 *         && !declaredInExists                        → pass (retracted)
 *     - count <  expectedMin && slot-retraction
 *         && declaredInExists                         → fail (misclassified-retraction)
 *     - count <  expectedMin && grace-extension
 *         | emergency-override                        → pass (covered)
 *     - count <  expectedMin && no classification     → fail (below-min)
 */

/**
 * @param {{
 *   slotId: string,
 *   count: number,
 *   expectedMin: number,
 *   withinGrace: boolean,
 *   classification: string | null,
 *   declaredInExists: boolean,
 * }} input
 * @returns {{ ruleId: string, status: 'pass'|'fail'|'info', reason: string }}
 */
export function verdictForSlot(input) {
  const { slotId, count, expectedMin, withinGrace, classification, declaredInExists } = input;

  if (count >= expectedMin) {
    return {
      ruleId: 'consumer-drift/healthy',
      status: 'pass',
      reason: `${slotId}: ${count} consumer(s) ≥ min ${expectedMin}`,
    };
  }

  // count < expectedMin — the slot has drifted below its consumer floor.
  if (withinGrace) {
    return {
      ruleId: 'consumer-drift/within-grace',
      status: 'info',
      reason: `${slotId}: ${count} < min ${expectedMin}, but within declared grace window`,
    };
  }

  if (classification === 'slot-retraction') {
    return declaredInExists
      ? {
          ruleId: 'consumer-drift/misclassified-retraction',
          status: 'fail',
          reason:
            `${slotId}: declared 'slot-retraction' but its declaredIn source still exists. ` +
            `Retraction must delete the substrate in the same change.`,
        }
      : {
          ruleId: 'consumer-drift/retracted',
          status: 'pass',
          reason: `${slotId}: retracted (declaredIn deleted); drift below min is intentional`,
        };
  }

  if (classification === 'grace-extension' || classification === 'emergency-override') {
    return {
      ruleId: `consumer-drift/${classification}`,
      status: 'pass',
      reason: `${slotId}: ${count} < min ${expectedMin}; classification '${classification}' covers it`,
    };
  }

  return {
    ruleId: 'consumer-drift/below-min',
    status: 'fail',
    reason:
      `${slotId}: ${count} production consumer(s) < required min ${expectedMin} and grace has expired. ` +
      `Add a consumer, or declare a changeset (slot-retraction / grace-extension / emergency-override).`,
  };
}

/**
 * Baseline-tampering guard for a slot whose `expectedMin` changed vs. the
 * baseline ref (mirrors class-size's silent-pin-bump). Raising the floor is
 * always fine (tightening); LOWERING it weakens enforcement in the same
 * commit — the silent escape hatch — and must be classified.
 *
 * @param {{ slotId: string, priorMin: number, liveMin: number, classification: string|null }} input
 */
export function verdictForFloorChange(input) {
  const { slotId, priorMin, liveMin, classification } = input;
  if (liveMin > priorMin) {
    return {
      ruleId: 'consumer-drift/floor-raised',
      status: 'info',
      reason: `${slotId}: expectedMin raised ${priorMin} → ${liveMin} (tightening)`,
    };
  }
  if (liveMin === priorMin) {
    return { ruleId: 'consumer-drift/floor-unchanged', status: 'pass', reason: `${slotId}: expectedMin unchanged` };
  }
  // liveMin < priorMin — the floor was lowered.
  if (classification === 'grace-extension' || classification === 'emergency-override') {
    return {
      ruleId: 'consumer-drift/declared-floor-drop',
      status: 'pass',
      reason: `${slotId}: expectedMin lowered ${priorMin} → ${liveMin}; '${classification}' covers it`,
    };
  }
  return {
    ruleId: 'consumer-drift/silent-floor-drop',
    status: 'fail',
    reason:
      `${slotId}: expectedMin lowered ${priorMin} → ${liveMin} without a declared changeset. ` +
      `Lowering a consumer floor weakens C-018 enforcement — declare 'grace-extension' or 'emergency-override'.`,
  };
}

/**
 * Baseline-tampering guard for a slot that existed at the baseline ref but is
 * gone from live slots.json. Removing tracking for a still-present substrate
 * is the drift this gate prevents; only a real retraction (declaredIn deleted)
 * or an explicit emergency-override legitimizes a removal.
 *
 * @param {{ slotId: string, classification: string|null, declaredInExists: boolean }} input
 */
export function verdictForSlotRemoval(input) {
  const { slotId, classification, declaredInExists } = input;
  if (classification === 'slot-retraction') {
    return declaredInExists
      ? {
          ruleId: 'consumer-drift/misclassified-retraction',
          status: 'fail',
          reason: `${slotId}: slot removed via 'slot-retraction' but its declaredIn source still exists.`,
        }
      : {
          ruleId: 'consumer-drift/declared-slot-removal',
          status: 'pass',
          reason: `${slotId}: slot removed via 'slot-retraction' (declaredIn deleted)`,
        };
  }
  if (classification === 'emergency-override') {
    return {
      ruleId: 'consumer-drift/emergency-override',
      status: 'pass',
      reason: `${slotId}: slot removed under 'emergency-override'`,
    };
  }
  return {
    ruleId: 'consumer-drift/silent-slot-removal',
    status: 'fail',
    reason:
      `${slotId}: slot removed from slots.json without a 'slot-retraction' changeset. ` +
      `Untracking a substrate that still exists silently disables its drift check.`,
  };
}

/**
 * §5.2 catalog-completeness closure (tempdoc 548). The gate's coverage must be a
 * *projection of the substrate universe*, not a hand-list that a new substrate
 * can silently escape. The enforcer discovers the substrate universe from
 * `discovery.roots`/`entryGlob` (e.g. every
 * `modules/ui-web/src/shell-v0/substrates/<name>/index.ts`) and, for each
 * discovered substrate, asks this table whether it is accounted for:
 *
 *   - a slot declares `substrate: <id>`  → covered (silent pass)
 *   - listed in `discovery.knownUncovered` → grandfathered (info; ratchet-from-here)
 *   - neither                              → fail: a NEW substrate escaped coverage
 *
 * This is the consumer-drift analog of the prose-tier-register meta-loop's
 * discovered-vs-declared anchor check. Grandfathering (the explicit
 * `knownUncovered` list, mirroring class-size-exceptions.txt) lets it land green
 * and tighten over time: adding the 11th substrate dir fails until it is either
 * given a measured slot or explicitly added to `knownUncovered` in a reviewable
 * diff — it can no longer escape silently.
 *
 * @param {{ substrateId: string, isCovered: boolean, isGrandfathered: boolean }} input
 */
export function verdictForUncoveredSubstrate(input) {
  const { substrateId, isCovered, isGrandfathered } = input;
  if (isCovered) {
    return {
      ruleId: 'consumer-drift/substrate-covered',
      status: 'pass',
      reason: `${substrateId}: covered by a declared slot`,
    };
  }
  if (isGrandfathered) {
    return {
      ruleId: 'consumer-drift/grandfathered-substrate',
      status: 'info',
      reason:
        `${substrateId}: discovered substrate not yet covered by a measured slot, but ` +
        `grandfathered via discovery.knownUncovered. Add a slot (substrate: '${substrateId}') ` +
        `with a measured expectedMin to tighten.`,
    };
  }
  return {
    ruleId: 'consumer-drift/uncovered-substrate',
    status: 'fail',
    reason:
      `${substrateId}: a discovered substrate has no covering slot and is not in ` +
      `discovery.knownUncovered. A new substrate must declare a consumer floor (add a slot with ` +
      `substrate: '${substrateId}') or be explicitly grandfathered — it cannot silently escape ` +
      `consumer-drift coverage (tempdoc 548 §5.2).`,
  };
}

/**
 * §5.2 — a `discovery.knownUncovered` entry that is now either covered by a slot
 * or no longer discoverable (dir deleted). Not a failure; a tightening hint so
 * the grandfather list doesn't ossify.
 *
 * @param {{ substrateId: string, reason: 'now-covered'|'not-discovered' }} input
 */
export function verdictForStaleGrandfather(input) {
  const { substrateId, reason } = input;
  return {
    ruleId: 'consumer-drift/stale-grandfather',
    status: 'info',
    reason:
      reason === 'now-covered'
        ? `${substrateId}: listed in discovery.knownUncovered but now covered by a slot — remove it from knownUncovered.`
        : `${substrateId}: listed in discovery.knownUncovered but no longer discovered — remove it from knownUncovered.`,
  };
}
