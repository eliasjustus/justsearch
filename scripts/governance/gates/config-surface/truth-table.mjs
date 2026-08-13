/**
 * config-surface truth-table — tempdoc 799 K.4.
 * Conforms to scripts/governance/lib/truth-table-runner.mjs.
 *
 * Per-metric ratchet over the runtime configuration surface. Tempdoc 754
 * classified 70 knobs and deleted 31, but recorded "no regrowth gate" — so the
 * surface is free to return to its prior size and the next cleanup pays the same
 * cost. This is that missing pressure.
 */

/** @param {{metric: string, current: number, pinned: number, classification: string}} input */
export function verdictForMetric(input) {
  const { metric, current, pinned, classification } = input;
  if (current <= pinned) {
    if (current < pinned) {
      return {
        ruleId: 'config-surface/rebalance-available',
        status: 'info',
        reason: `${metric}: ${current} < pinned ${pinned} (rebalance available)`,
      };
    }
    return {
      ruleId: 'config-surface/within-baseline',
      status: 'pass',
      reason: `${metric}: ${current} at baseline`,
    };
  }
  if (classification === 'silent-growth') {
    return {
      ruleId: 'config-surface/silent-growth',
      status: 'fail',
      reason: `${metric}: ${pinned} → ${current} config keys without a declared changeset`,
    };
  }
  return {
    ruleId: `config-surface/${classification}`,
    status: 'pass',
    reason: `${metric}: ${pinned} → ${current}; '${classification}' covers`,
  };
}

/** @param {{metric: string, priorPin: number, livePin: number, classification: string}} input */
export function verdictForBaselineShift(input) {
  const { metric, priorPin, livePin, classification } = input;
  if (livePin <= priorPin) {
    return {
      ruleId: 'config-surface/within-baseline',
      status: 'pass',
      reason: `${metric}: baseline unchanged or tightening`,
    };
  }
  if (classification === 'silent-growth') {
    return {
      ruleId: 'config-surface/silent-baseline-shift',
      status: 'fail',
      reason: `${metric}: baseline raised ${priorPin} → ${livePin} without a declared changeset`,
    };
  }
  return {
    ruleId: 'config-surface/declared-growth',
    status: 'info',
    reason: `${metric}: baseline raised ${priorPin} → ${livePin}; '${classification}' covers`,
  };
}

/**
 * A declared setting with no reader on any of the three paths (tempdoc 799 §O.3).
 *
 * @param {{key: string, baselined: boolean}} input
 */
export function verdictForDeadKey({ key, baselined }) {
  if (baselined) {
    return {
      ruleId: 'config-surface/dead-key-baselined',
      status: 'info',
      reason: `${key}: declared with no reader (known — in the dead-config baseline)`,
    };
  }
  return {
    ruleId: 'config-surface/dead-key',
    status: 'fail',
    reason:
      `${key}: declared but NOTHING reads it — not resolved into ResolvedConfig, not read via its ` +
      `EnvRegistry constant, and the key string appears nowhere outside the configuration module. ` +
      `A documented setting that does nothing is worse than no setting (tempdoc 754). Wire it, or ` +
      `delete the declaration.`,
  };
}

/**
 * A ResolvedConfig record component whose accessor is never called (tempdoc 799 §N.2).
 *
 * @param {{component: string, baselined: boolean}} input
 */
export function verdictForUnreadComponent({ component, baselined }) {
  if (baselined) {
    return {
      ruleId: 'config-surface/unread-component-baselined',
      status: 'info',
      reason: `${component}: no accessor call (known — in the dead-config baseline)`,
    };
  }
  return {
    ruleId: 'config-surface/unread-component',
    status: 'fail',
    reason:
      `${component}: ResolvedConfig exposes it but no production code calls the accessor. It ` +
      `resolves, it is reachable, and it changes nothing — the exact shape of the 22 knobs ` +
      `tempdoc 799 withdrew.`,
  };
}
