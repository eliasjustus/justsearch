/** Rule descriptions for the register-guard-resolution meta-pass (tempdoc 576 §3.1). */
export const REGISTER_GUARD_RESOLUTION_RULE_DESCRIPTIONS = {
  'register-guard-resolution/dangling-guard':
    'A surface names a guard (gate:<id> / test:<Name>) that does not resolve to a real artifact. ' +
    'Point it at an existing gate or test, or remove the dangling reference.',
  'register-guard-resolution/unguarded':
    'A surface of a kind this register requires to be guarded names no REAL guard (gate:/test:) — an ' +
    'exemption does not satisfy a required-guarded kind. It sits at rung 3 — declared but not actually ' +
    'enforced (tempdoc 576 §3). Name a gate:/test: guard.',
  'register-guard-resolution/invalid-guard-form':
    'A surface declares a bare "self"/"none-yet"/absent guard, which is no longer representable ' +
    '(tempdoc 576 §3.1 rung-1: the silent-unguarded state is made impossible to add). Name a real ' +
    'guard (gate:<id> / test:<Name>), or declare an accountable "exempt:<reason>" stating why the ' +
    'surface legitimately has no biting guard (canonical type, re-export barrel, opaque carrier, a ' +
    'consumer with no projection law). The reason is mandatory and must not contain "+".',
  'register-guard-resolution/register-unreadable':
    'A configured register file could not be read/parsed. Fix the JSON (its own gate validates schema).',
  'register-guard-resolution/silent-guard-downgrade':
    'A register entry\'s guard enforcement weakened vs the baseline (real gate:/test: → exempt:, or → ' +
    'bare) WITHOUT a guard-downgrade changeset — a silently removed/weakened guard (tempdoc 576 §5 ' +
    'no-silent-downgrade). Restore the guard, or author a gates/register-guard-resolution/.changesets/' +
    '<id>.md classified guard-downgrade stating why the weakening is intended.',
  'register-guard-resolution/declared-guard-downgrade':
    'A guard weakened vs baseline, but a guard-downgrade changeset accounts for it (allowed, recorded).',
};
