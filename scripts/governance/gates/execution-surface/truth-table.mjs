/**
 * Execution-surface truth table (tempdoc 553 pillar c — the anti-fragmentation meta-gate).
 *
 * Mechanizes "every production surface that describes what the search pipeline did is a DECLARED
 * projection/consumer of the one canonical record, never an independent model." This gate is a
 * meta-coordinator (like prose-tier-register): it does NOT re-implement field/vocabulary
 * conformance — those stay in the specialized guards the register names (gate:stage-completeness,
 * gate:wire, test:KnowledgeWireContractConformanceTest). It enforces seven checks:
 *
 *   - undeclared-surface: every Java-main / TS file that references the canonical SearchTrace type
 *     must appear in governance/execution-surfaces.v1.json. A new fork that touches the canonical
 *     type without registering fails the build (the discovery moment, forced).
 *   - orphan-surface: every registered surface path must still exist (register stays honest).
 *   - dangling-guard: every surface's named guard must resolve (a real gate id / test file).
 *   - unguarded-projection: every projection/producer surface names a real derivation guard.
 *   - undeclared-vocabulary-fork: every Java-main search/* span emitter is a registered surface.
 *   - non-conformance-guard: a test-guarded projection names a conformance-named test.
 *   - missing-reflective-guard: every canonical/sibling record has ≥1 guardKind:"reflective" guard
 *     (553 §14 G-B) — a declared field-exhaustive totality check, not just a conformance-named test.
 *
 * HONEST LIMIT (553 §5): the auto-scan only sees surfaces that reference the canonical type. An
 * undeclared fork that re-models execution from scratch is invisible here — that residue is for
 * review + the Phase-3 discovery checkpoint, not this gate. Documented, not papered over.
 *
 * Conforms to the kernel truth-table contract: (input) → { ruleId, status, reason }.
 */

/** Verdict: production files reference the canonical type but are absent from the register. */
export function verdictForUndeclaredSurfaces({ undeclared }) {
  if (undeclared.length > 0) {
    return {
      ruleId: 'execution-surface/undeclared-surface',
      status: 'fail',
      reason:
        `These files reference the canonical SearchTrace record but are NOT registered in ` +
        `governance/execution-surfaces.v1.json: ${undeclared.join(', ')}. Every surface that ` +
        `describes "what the pipeline did" must be a declared projection/consumer of the one ` +
        `record — not an independent model (tempdoc 553). Add each to the register (deciding ` +
        `projection vs fork), or stop referencing the canonical type.`,
    };
  }
  return {
    ruleId: 'execution-surface/all-surfaces-declared',
    status: 'pass',
    reason: 'Every production reference to the canonical SearchTrace record is a registered surface.',
  };
}

/** Verdict: a registered surface path no longer exists on disk (stale register entry). */
export function verdictForOrphanSurfaces({ orphans }) {
  if (orphans.length > 0) {
    return {
      ruleId: 'execution-surface/orphan-surface',
      status: 'fail',
      reason:
        `Registered surface path(s) no longer exist: ${orphans.join(', ')}. The register ` +
        `(governance/execution-surfaces.v1.json) has drifted from reality — remove the stale ` +
        `entry or fix the path.`,
    };
  }
  return {
    ruleId: 'execution-surface/surfaces-resolve',
    status: 'pass',
    reason: 'Every registered surface path exists.',
  };
}

/** Verdict: a surface's declared guard does not resolve to a real gate id / test file. */
export function verdictForDanglingGuards({ dangling }) {
  if (dangling.length > 0) {
    return {
      ruleId: 'execution-surface/dangling-guard',
      status: 'fail',
      reason:
        `Surface guard reference(s) do not resolve: ${dangling.join('; ')}. A "gate:<id>" must ` +
        `name a real gate in governance/registry.v1.json; a "test:<Name>" must match a real ` +
        `*<Name>*.{java,py} file. Fix the guard or the reference.`,
    };
  }
  return {
    ruleId: 'execution-surface/guards-resolve',
    status: 'pass',
    reason: 'Every registered surface guard resolves.',
  };
}

/**
 * Verdict: a surface that PROJECTS the canonical record (kind: projection / producer) has no real
 * derivation guard (guard is "self"/"none-yet"/absent). 553 pillar (b): a surface that derives
 * execution facts from the record must be VERIFIED to derive (a conformance test / gate), so it
 * cannot silently start authoring facts independently. `projection-pending` surfaces are exempt
 * (explicitly-tracked not-yet-converged). Structural form of "no surface authors independently";
 * semantic purity is the §5 undecidable limit, not mechanized.
 */
export function verdictForUnguardedProjection({ unguarded }) {
  if (unguarded.length > 0) {
    return {
      ruleId: 'execution-surface/unguarded-projection',
      status: 'fail',
      reason:
        `These projection/producer surfaces have no derivation guard (guard is self/none-yet): ` +
        `${unguarded.join(', ')}. A surface that projects the canonical record must name a real ` +
        `guard (a conformance test: or gate:) proving it derives from the record — not "self". ` +
        `Add a guard, or mark a not-yet-converged surface kind: projection-pending.`,
    };
  }
  return {
    ruleId: 'execution-surface/projections-guarded',
    status: 'pass',
    reason: 'Every projection/producer surface names a real derivation guard.',
  };
}

/**
 * Verdict: a Java-main file emits a `search/*` span-name literal (the execution-span vocabulary)
 * but is not a registered surface. 553 Phase B: widens fork detection beyond the canonical-TYPE
 * import (Check 1) to the span tree that re-models execution WITHOUT importing SearchTrace (§2's
 * founding third fork). HONEST LIMIT (§5): a string-literal heuristic — a fork inventing a different
 * vocabulary still escapes; this converts the span-fork class from invisible → caught, not "all".
 */
export function verdictForUnregisteredSpanEmitter({ spanForks }) {
  if (spanForks.length > 0) {
    return {
      ruleId: 'execution-surface/undeclared-vocabulary-fork',
      status: 'fail',
      reason:
        `These Java-main files emit a search/* execution-span literal but are NOT registered in ` +
        `governance/execution-surfaces.v1.json: ${spanForks.join(', ')}. A span tree describing ` +
        `"what the pipeline did" is an execution surface even though it doesn't import the canonical ` +
        `SearchTrace type — register it (projection / projection-pending) so the span content is a ` +
        `governed projection, not an independent record (tempdoc 553 §2/§5).`,
    };
  }
  return {
    ruleId: 'execution-surface/span-emitters-declared',
    status: 'pass',
    reason: 'Every Java-main search/* span emitter is a registered surface.',
  };
}

/**
 * Verdict: a projection/producer surface is guarded ONLY by test(s) none of which is a conformance
 * test (name matching Conformance / Projection / searchTrace). 553 Phase C / G4: upgrades "names a
 * guard" to "names a guard that verifies the projection ⊆ record relationship". `gate:` guards are
 * conformance by construction. Honest §5 limit: the naming convention is a proxy for the undecidable
 * "is a pure projection" — it forces an auditable claim, not a proof.
 */
export function verdictForNonConformanceGuard({ nonConformance }) {
  if (nonConformance.length > 0) {
    return {
      ruleId: 'execution-surface/non-conformance-guard',
      status: 'fail',
      reason:
        `These projection/producer surfaces are guarded only by non-conformance test(s): ` +
        `${nonConformance.join(', ')}. A surface that projects the canonical record must be guarded ` +
        `by a CONFORMANCE test (name matching Conformance / Projection / searchTrace, verifying it ` +
        `derives from the record) or a gate: — not an arbitrary unit test (553 Phase C / G4).`,
    };
  }
  return {
    ruleId: 'execution-surface/conformance-guards-present',
    status: 'pass',
    reason: 'Every test-guarded projection/producer surface names a conformance test.',
  };
}

/**
 * Verdict: a canonical/sibling record has NO registered surface carrying guardKind:"reflective".
 * 553 §14 G-B: Check 6 forces a conformance-NAMED guard; this forces, per record, that at least one
 * guard is a declared REFLECTIVE totality check (reflects over the record's fields — Java
 * getRecordComponents() / TS Object.keys(FULL) / assertFieldRoles — classifying each
 * represented-or-deliberately-dropped). A new record, or removal of a record's only reflective guard,
 * fails the build. Honest §5 limit: guardKind:"reflective" is an auditable declared claim, not a
 * mechanical proof of totality.
 */
export function verdictForMissingReflectiveGuard({ records }) {
  if (records.length > 0) {
    return {
      ruleId: 'execution-surface/missing-reflective-guard',
      status: 'fail',
      reason:
        `These canonical/sibling records have no registered surface with a reflective totality ` +
        `guard (guardKind:"reflective"): ${records.join(', ')}. Every record in the register must ` +
        `have at least one surface whose guard mechanically reflects over the record's fields and ` +
        `asserts each is projected-or-deliberately-dropped (e.g. SearchTraceProjectionConformanceTest ` +
        `for SearchTrace, evidenceProjection.test for ContextCitation). Tag that surface with ` +
        `"recordId": "<RecordName>", "guardKind": "reflective" (553 §14 G-B).`,
    };
  }
  return {
    ruleId: 'execution-surface/reflective-guards-present',
    status: 'pass',
    reason: 'Every canonical/sibling record has ≥1 reflective totality guard.',
  };
}

/** Verdict when the register file itself is absent. */
export function verdictForMissingRegister({ path }) {
  return {
    ruleId: 'execution-surface/register-missing',
    status: 'fail',
    reason: `Cannot run the execution-surface gate: the register was not found at ${path}.`,
  };
}
