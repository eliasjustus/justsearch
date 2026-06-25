/**
 * Rule descriptions for the execution-surface gate (tempdoc 553 pillar c).
 * Keyed by ruleId; surfaced in SARIF + `--explain`.
 */
export const EXECUTION_SURFACE_RULE_DESCRIPTIONS = {
  'execution-surface/undeclared-surface':
    'A production file references the canonical SearchTrace record but is not registered in ' +
    'governance/execution-surfaces.v1.json. Every surface that describes "what the pipeline did" ' +
    'must be a declared projection/consumer of the one record (tempdoc 553) — register it (decide ' +
    'projection vs fork) or stop referencing the canonical type.',
  'execution-surface/orphan-surface':
    'A registered surface path no longer exists — the register has drifted from the code. Remove ' +
    'the stale entry or fix the path.',
  'execution-surface/dangling-guard':
    'A surface declares a guard (gate:<id> / test:<Name>) that does not resolve to a real gate in ' +
    'registry.v1.json or a real *<Name>*.{java,py,ts,tsx} file. Fix the guard or the reference.',
  'execution-surface/unguarded-projection':
    'A projection/producer surface (derives execution facts from the canonical record) has no real ' +
    'derivation guard (guard is "self"/"none-yet"). It must name a conformance test:/gate: proving it ' +
    'derives from the record, so it cannot silently author facts independently (553 pillar b). Mark ' +
    'genuinely-not-yet-converged surfaces kind: projection-pending.',
  'execution-surface/projections-guarded':
    'Every projection/producer surface names a real derivation guard (healthy).',
  'execution-surface/undeclared-vocabulary-fork':
    'A Java-main file emits a search/* execution-span literal (the span vocabulary) but is not a ' +
    'registered surface. Widens fork detection beyond canonical-type imports to the span tree that ' +
    're-models execution without importing SearchTrace (553 §2). Register it (projection / ' +
    'projection-pending). Honest limit (§5): string-literal heuristic — reduces, not eliminates.',
  'execution-surface/span-emitters-declared':
    'Every Java-main search/* span emitter is a registered surface (healthy).',
  'execution-surface/non-conformance-guard':
    'A projection/producer surface is guarded only by non-conformance test(s). It must name a ' +
    'conformance test (name matching Conformance / Projection / searchTrace, verifying projection ⊆ ' +
    'record) or a gate: — not an arbitrary unit test (553 Phase C / G4). Honest §5 limit: the naming ' +
    'convention is a proxy for the undecidable "is a pure projection", forcing an auditable claim.',
  'execution-surface/conformance-guards-present':
    'Every test-guarded projection/producer surface names a conformance test (healthy).',
  'execution-surface/missing-reflective-guard':
    'A canonical/sibling record has no registered surface with a reflective totality guard ' +
    '(guardKind:"reflective"). 553 §14 G-B: per record, ≥1 guard must mechanically reflect over the ' +
    "record's fields (getRecordComponents() / Object.keys(FULL) / assertFieldRoles) and classify each " +
    'represented-or-deliberately-dropped — so a new record (or removal of its only reflective guard) ' +
    'breaks the build. Tag the surface "recordId":"<Name>","guardKind":"reflective". Honest §5 limit: ' +
    'the tag is an auditable declared claim, not a mechanical proof of totality.',
  'execution-surface/reflective-guards-present':
    'Every canonical/sibling record has ≥1 reflective totality guard (healthy).',
  'execution-surface/register-missing':
    'governance/execution-surfaces.v1.json (the execution-surface register) was not found at its ' +
    'configured path.',
  'execution-surface/all-surfaces-declared':
    'Every production reference to the canonical SearchTrace record is a registered surface (healthy).',
  'execution-surface/surfaces-resolve': 'Every registered surface path exists (healthy).',
  'execution-surface/guards-resolve': 'Every registered surface guard resolves (healthy).',
  'execution-surface/vacuous-scan':
    'The auto-scan found fewer canonical-type-referencing files than scan.expectedMinPopulation ' +
    '(default 1) — almost always a renamed/moved scan root, not a real removal. A positive-coverage ' +
    'gate whose scan collapses to zero passes VACUOUSLY (enforces nothing while green) — the §5 ' +
    'vacuous-pass downgrade (tempdoc 576). Fix scan.javaMainRoots / tsRoots, or lower ' +
    'scan.expectedMinPopulation in the register with the change that shrank the population.',
  'execution-surface/scan-population-live':
    'The auto-scan detected >= the declared floor of canonical-type-referencing files — not vacuous ' +
    '(healthy).',
};
