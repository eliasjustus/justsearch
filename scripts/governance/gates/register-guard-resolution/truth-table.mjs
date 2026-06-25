/**
 * register-guard-resolution truth-table (tempdoc 576 §3.1). Conforms to the kernel contract:
 * each verdict function maps an input to { ruleId, status: pass|fail|info, reason }.
 */

export function verdictForUnreadableRegisters({ unreadable }) {
  if (unreadable.length > 0) {
    return {
      ruleId: 'register-guard-resolution/register-unreadable',
      status: 'fail',
      reason: `Configured register(s) could not be read/parsed: ${unreadable.join('; ')}.`,
    };
  }
  return {
    ruleId: 'register-guard-resolution/register-unreadable',
    status: 'pass',
    reason: 'all configured registers parsed.',
  };
}

export function verdictForUnguardedSurfaces({ unguarded }) {
  if (unguarded.length > 0) {
    return {
      ruleId: 'register-guard-resolution/unguarded',
      status: 'fail',
      reason:
        `Surfaces of a kind their register requires to be guarded name no REAL guard ` +
        `(gate:/test:) — an exemption does not satisfy a required-guarded kind (rung 3 — declared ` +
        `but unenforced; tempdoc 576 §3): ${unguarded.join('; ')}.`,
    };
  }
  return {
    ruleId: 'register-guard-resolution/unguarded',
    status: 'pass',
    reason: 'every surface of a required-guarded kind names a real guard.',
  };
}

/**
 * Rung-1 (tempdoc 576 §3.1): a bare `self` / `none-yet` / absent guard is no longer representable.
 * Every surface must EITHER name a real guard (gate:/test:) OR carry an explicit, reasoned
 * `exempt:<reason>`. This makes the silent-unguarded state (the rung-3 masquerade) impossible to add
 * — a new entry cannot be left implicitly unguarded; it must declare why.
 */
export function verdictForInvalidGuardForm({ invalid }) {
  if (invalid.length > 0) {
    return {
      ruleId: 'register-guard-resolution/invalid-guard-form',
      status: 'fail',
      reason:
        `Surfaces declare a bare "self"/"none-yet"/absent guard, which is no longer representable ` +
        `(tempdoc 576 §3.1 rung-1): ${invalid.join('; ')}. Name a real guard (gate:<id> / ` +
        `test:<Name>), or declare an accountable "exempt:<reason>" (e.g. canonical type, re-export ` +
        `barrel, opaque carrier, consumer with no projection law).`,
    };
  }
  return {
    ruleId: 'register-guard-resolution/invalid-guard-form',
    status: 'pass',
    reason: 'no surface uses a bare self/none-yet/absent guard (every entry is guarded or exempt).',
  };
}

export function verdictForDanglingGuards({ dangling }) {
  if (dangling.length > 0) {
    return {
      ruleId: 'register-guard-resolution/dangling-guard',
      status: 'fail',
      reason: `Guards naming an artifact that does not resolve: ${dangling.join('; ')}.`,
    };
  }
  return {
    ruleId: 'register-guard-resolution/dangling-guard',
    status: 'pass',
    reason: 'every named guard (gate:/test:) resolves.',
  };
}
