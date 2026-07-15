/**
 * Runtime-state truth table (tempdoc 737 §12c — the AI-runtime fork-killer, applying the
 * execution-surface pattern from tempdoc 553/576 to the RuntimeStatus/RuntimeSpec/RuntimeGpuLease/
 * RuntimeReconciler quartet).
 *
 * A deliberately SMALLER check set than execution-surface's seven checks (this register has no
 * projection-purity / span-vocabulary / conformance-naming obligations yet — Phase 1 is Head-only,
 * pre-wire, per 737 §12e). Three checks:
 *
 *   - unregistered-referencer: every Java-main file that references the runtimestate package's
 *     fully-qualified prefix must appear in governance/runtime-state.v1.json. A new fork that
 *     touches the canonical package without registering fails the build (the discovery moment).
 *   - register-row-resolves: every registered surface path must exist (orphan-surface) AND every
 *     surface's named guard must resolve to a real gate id / test file (dangling-guard).
 *   - vacuous-scan: the shared §5 population-floor guard (scripts/governance/lib/population-floor.mjs)
 *     — the scan must detect >= scan.expectedMinPopulation referencers, else a renamed/moved scan
 *     root would let the undeclared-referencer check pass vacuously.
 *
 * HONEST LIMIT (mirrors 553 §5): the auto-scan only sees files that reference the runtimestate
 * package's FQN. An undeclared fork that re-models runtime state from scratch without ever
 * referencing the package is invisible here — that residue is for review.
 *
 * Conforms to the kernel truth-table contract: (input) → { ruleId, status, reason }.
 */

/** Verdict: production files reference the runtimestate package but are absent from the register. */
export function verdictForUnregisteredReferencer({ undeclared }) {
  if (undeclared.length > 0) {
    return {
      ruleId: 'runtime-state/unregistered-referencer',
      status: 'fail',
      reason:
        `These files reference io.justsearch.app.services.runtimestate but are NOT registered in ` +
        `governance/runtime-state.v1.json: ${undeclared.join(', ')}. Every surface that describes or ` +
        `consumes the AI-runtime authority (RuntimeStatus/RuntimeSpec/RuntimeGpuLease/RuntimeReconciler) ` +
        `must be a declared projection/consumer of that one package — not an independent model ` +
        `(tempdoc 737 §12c, the five-vocabularies fork class). Add each to the register (deciding ` +
        `projection vs fork), or stop referencing the runtimestate package.`,
    };
  }
  return {
    ruleId: 'runtime-state/all-referencers-declared',
    status: 'pass',
    reason: 'Every production reference to the runtimestate package is a registered surface.',
  };
}

/** Verdict: a registered surface path no longer exists on disk (stale register entry). */
export function verdictForOrphanSurfaces({ orphans }) {
  if (orphans.length > 0) {
    return {
      ruleId: 'runtime-state/orphan-surface',
      status: 'fail',
      reason:
        `Registered surface path(s) no longer exist: ${orphans.join(', ')}. The register ` +
        `(governance/runtime-state.v1.json) has drifted from reality — remove the stale entry or ` +
        `fix the path.`,
    };
  }
  return {
    ruleId: 'runtime-state/surfaces-resolve',
    status: 'pass',
    reason: 'Every registered surface path exists.',
  };
}

/** Verdict: a surface's declared guard does not resolve to a real gate id / test file / exempt reason. */
export function verdictForDanglingGuards({ dangling }) {
  if (dangling.length > 0) {
    return {
      ruleId: 'runtime-state/dangling-guard',
      status: 'fail',
      reason:
        `Surface guard reference(s) do not resolve: ${dangling.join('; ')}. A "gate:<id>" must name ` +
        `a real gate in governance/registry.v1.json; a "test:<Name>" must match a real *<Name>*.java/ ` +
        `.py/.ts/.tsx file; an "exempt:<reason>" must carry a non-empty reason. Fix the guard or the ` +
        `reference.`,
    };
  }
  return {
    ruleId: 'runtime-state/guards-resolve',
    status: 'pass',
    reason: 'Every registered surface guard resolves.',
  };
}

/** Verdict when the register file itself is absent. */
export function verdictForMissingRegister({ path }) {
  return {
    ruleId: 'runtime-state/register-missing',
    status: 'fail',
    reason: `governance/runtime-state.v1.json (the runtime-state register) was not found at ${path}.`,
  };
}
