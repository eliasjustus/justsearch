/**
 * Rule descriptions for the runtime-state gate (tempdoc 737 §12c).
 * Keyed by ruleId; surfaced in SARIF + `--explain`.
 */
export const RUNTIME_STATE_RULE_DESCRIPTIONS = {
  'runtime-state/unregistered-referencer':
    'A production file references io.justsearch.app.services.runtimestate (the RuntimeStatus/' +
    'RuntimeSpec/RuntimeGpuLease/RuntimeReconciler authority) but is not registered in ' +
    'governance/runtime-state.v1.json. Every surface that describes or consumes the AI-runtime ' +
    'authority must be a declared projection/consumer of that one package (tempdoc 737 §12c) — ' +
    'register it (decide projection vs fork) or stop referencing the package.',
  'runtime-state/all-referencers-declared':
    'Every production reference to the runtimestate package is a registered surface (healthy).',
  'runtime-state/orphan-surface':
    'A registered surface path no longer exists — the register has drifted from the code. Remove ' +
    'the stale entry or fix the path.',
  'runtime-state/surfaces-resolve': 'Every registered surface path exists (healthy).',
  'runtime-state/dangling-guard':
    'A surface declares a guard (gate:<id> / test:<Name> / exempt:<reason>) that does not resolve. ' +
    'Fix the guard or the reference.',
  'runtime-state/guards-resolve': 'Every registered surface guard resolves (healthy).',
  'runtime-state/register-missing':
    'governance/runtime-state.v1.json (the runtime-state register) was not found at its configured ' +
    'path.',
  'runtime-state/vacuous-scan':
    'The auto-scan found fewer runtimestate-referencing files than scan.expectedMinPopulation ' +
    '(default 1) — almost always a renamed/moved scan root, not a real removal. A positive-coverage ' +
    'gate whose scan collapses to zero passes VACUOUSLY (enforces nothing while green) — the §5 ' +
    'vacuous-pass downgrade (tempdoc 576). Fix scan.javaMainRoots, or lower ' +
    'scan.expectedMinPopulation in the register with the change that shrank the population.',
  'runtime-state/scan-population-live':
    'The auto-scan detected >= the declared floor of runtimestate-referencing files — not vacuous ' +
    '(healthy).',
};
