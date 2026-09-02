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
 * The system-access allowlist grew (tempdoc 883 decision 5).
 *
 * `gates/config-surface/sysaccess-allowlist.txt` records every direct `System.getenv` /
 * `getProperty` / `setProperty` / `clearProperty` / `Boolean.getBoolean` / `Integer.getInteger`
 * site outside `io.justsearch.configuration`. `SystemAccessFunnelTest` fails on a site that is NOT
 * on the list; this verdict is the other half — it fails on a LIST that grew, which is the obvious
 * way to make that test pass without routing the value through the resolver.
 *
 * @param {{added: string[], classification: string}} input
 */
export function verdictForSysaccessGrowth({ added, classification }) {
  if (added.length === 0) {
    return {
      ruleId: 'config-surface/within-baseline',
      status: 'pass',
      reason: 'sysaccess allowlist unchanged or shrinking',
    };
  }
  if (classification === 'silent-growth') {
    return {
      ruleId: 'config-surface/sysaccess-allowlist-growth',
      status: 'fail',
      reason:
        `sysaccess allowlist gained ${added.length} entr${added.length === 1 ? 'y' : 'ies'} ` +
        `(${added.slice(0, 5).join(', ')}${added.length > 5 ? ', …' : ''}) without a declared ` +
        `changeset. The list only shrinks: a new direct System.getenv/getProperty site should be ` +
        `routed through io.justsearch.configuration, not recorded as permanent.`,
    };
  }
  return {
    ruleId: `config-surface/${classification}`,
    status: 'info',
    reason: `sysaccess allowlist gained ${added.length}; '${classification}' covers`,
  };
}

/**
 * A key present in the shipped `config/application.yaml` that no contributor or consumer reads
 * (tempdoc 883 decision 5).
 *
 * The mirror of {@link verdictForDeadKey}. That one asks "is this DECLARED setting read?" and can
 * only see keys the resolver already knows about. This one starts from the YAML file itself, which
 * nothing in `scripts/` parsed before 883 — so a key an operator can edit, that reaches nothing,
 * was invisible to every check in the repo. Tempdoc 882 found two by hand.
 *
 * @param {{key: string, baselined: boolean}} input
 */
export function verdictForUnreadYamlKey({ key, baselined }) {
  if (baselined) {
    return {
      ruleId: 'config-surface/yaml-key-unread-baselined',
      status: 'info',
      reason: `${key}: in application.yaml with no reader (known — in the dead-config baseline)`,
    };
  }
  return {
    ruleId: 'config-surface/yaml-key-unread',
    status: 'fail',
    reason:
      `${key}: present in config/application.yaml, but no putYaml* contribution, no node walk and ` +
      `no consumer names it — editing it does nothing, silently. This is the mirror of the dead-key ` +
      `class: the setting exists where the USER looks, and nowhere the CODE looks. Wire it into ` +
      `ResolvedConfigBuilder, or delete it from the shipped YAML.`,
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
