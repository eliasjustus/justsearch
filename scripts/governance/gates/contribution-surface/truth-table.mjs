/**
 * Contribution-surface truth table (tempdoc 560 §5 — the prevention keystone that locks in §4c).
 * Sibling of operation-surface / execution-surface; conforms to the kernel truth-table contract:
 * (input) → { ruleId, status, reason }.
 *
 * Mechanizes "the registry FE type barrel (modules/ui-web/src/api/types/registry.ts) is a
 * re-export/derivation surface over the GENERATED wire types — never a second shape authority."
 * The keystone is `registry-barrel-purity` (no new hand-authored wire object) + `…-import-purity`
 * (no import of a hand-authored shape from a non-generated module). The rest keep the register
 * honest + catch grandfather drift.
 *
 * HONEST LIMIT (mirrors operation-surface): the barrel scan catches a hand-authored object/interface
 * and a non-generated import; a hand shape laundered through a generated-LOOKING path, or a deeply
 * obfuscated alias chain, is residue for review — declared in the register note, not auto-caught.
 */

/** KEYSTONE — a NEW hand-authored wire object/interface (a second shape authority) in the barrel. */
export function verdictForBarrelPurity({ violations }) {
  if (violations.length > 0) {
    return {
      ruleId: 'contribution-surface/registry-barrel-purity',
      status: 'fail',
      reason:
        `These declarations in the registry barrel are hand-authored wire-object shapes, not ` +
        `derivations of a generated wire type: ${violations.join('; ')}. The registry barrel must ` +
        `not contain a second shape authority (tempdoc 560 §4c/§5): every wire-shaped type must ` +
        `derive from the generated source (Omit<…Wire,…>/NonNullable<…Wire[…]>/a generated alias), ` +
        `not a bare \`interface X {}\` or \`type X = { … }\`. Derive it from the generated wire, or — ` +
        `if it is a genuine local helper/envelope — add it to registryAllowlist with a reason.`,
    };
  }
  return {
    ruleId: 'contribution-surface/registry-barrel-pure',
    status: 'pass',
    reason: 'The registry barrel declares no hand-authored wire-object shape (every type derives from the generated wire).',
  };
}

/** A barrel import from a module outside the allowed (generated / zod) set — a laundered authority. */
export function verdictForBarrelImportPurity({ violations }) {
  if (violations.length > 0) {
    return {
      ruleId: 'contribution-surface/registry-barrel-import-purity',
      status: 'fail',
      reason:
        `The registry barrel imports from module(s) outside the allowed set: ${violations.join('; ')}. ` +
        `registry.ts may only import from the generated projection (../generated/*) or zod — importing ` +
        `a hand-authored shape from a sibling FE module launders a second shape authority into the ` +
        `barrel. Move the shape into the generated pipeline, or remove the import.`,
    };
  }
  return {
    ruleId: 'contribution-surface/registry-barrel-imports-pure',
    status: 'pass',
    reason: 'The registry barrel imports only from the generated projection / zod.',
  };
}

/** A registryAllowlist entry that is not actually declared in the barrel (stale allowlist). */
export function verdictForAllowlistOrphans({ orphans }) {
  if (orphans.length > 0) {
    return {
      ruleId: 'contribution-surface/allowlist-orphan',
      status: 'fail',
      reason:
        `These registryAllowlist names are not declared in the registry barrel: ${orphans.join(', ')}. ` +
        `The allowlist has drifted — remove the stale entry (a deleted helper/envelope must not keep ` +
        `a standing purity exemption).`,
    };
  }
  return {
    ruleId: 'contribution-surface/allowlist-resolves',
    status: 'pass',
    reason: 'Every registryAllowlist name is declared in the barrel.',
  };
}

/** A generated surface whose generated module is missing, or whose wireType the barrel never references. */
export function verdictForSurfaceCoherence({ problems }) {
  if (problems.length > 0) {
    return {
      ruleId: 'contribution-surface/surface-projection-coherence',
      status: 'fail',
      reason:
        `Generated-surface coherence problem(s): ${problems.join('; ')}. Every \`generated\`/` +
        `\`generated-nested\` surface must have its generatedModule present AND its wireType referenced ` +
        `by the barrel (so the projection it claims actually backs the FE). Fix the register or the barrel.`,
    };
  }
  return {
    ruleId: 'contribution-surface/surfaces-coherent',
    status: 'pass',
    reason: 'Every generated surface has a present module the barrel references.',
  };
}

/** A grandfathered-pending primitive whose RegistryController handler no longer exists (silent drop). */
export function verdictForGrandfatherCoverage({ missing }) {
  if (missing.length > 0) {
    return {
      ruleId: 'contribution-surface/grandfather-coverage',
      status: 'fail',
      reason:
        `These grandfathered-pending registry primitives name a RegistryController handler that no ` +
        `longer exists: ${missing.join('; ')}. The register has drifted from the served endpoints — ` +
        `if the endpoint was removed, drop the grandfatheredPending entry; if the handler was renamed, ` +
        `update servedRaw.`,
    };
  }
  return {
    ruleId: 'contribution-surface/grandfather-covered',
    status: 'pass',
    reason: 'Every grandfathered-pending primitive still has its RegistryController handler.',
  };
}

/** A grandfathered-pending primitive that GAINED a generated module — it must be promoted to surfaces[]. */
export function verdictForGrandfatherDrift({ promoted }) {
  if (promoted.length > 0) {
    return {
      ruleId: 'contribution-surface/grandfather-drift',
      status: 'fail',
      reason:
        `These grandfathered-pending primitives have GAINED a generated schema-types module but are ` +
        `still listed as pending: ${promoted.join(', ')}. They are now projected — PROMOTE each to ` +
        `surfaces[] (projection: "generated") and remove it from grandfatheredPending, so the register ` +
        `reflects that the projection is done. This is the future-proofing teeth: a primitive can't be ` +
        `quietly projected while the register still calls it unprojected.`,
    };
  }
  return {
    ruleId: 'contribution-surface/grandfather-stable',
    status: 'pass',
    reason: 'No grandfathered-pending primitive has silently gained a generated module.',
  };
}

/** A surface's declared guard (gate:<id> / test:<Name>) does not resolve. */
export function verdictForDanglingGuards({ dangling }) {
  if (dangling.length > 0) {
    return {
      ruleId: 'contribution-surface/dangling-guard',
      status: 'fail',
      reason:
        `Surface guard reference(s) do not resolve: ${dangling.join('; ')}. A "gate:<id>" must name a ` +
        `real gate in governance/registry.v1.json; a "test:<Name>" must match a real test file. Fix the ` +
        `guard or the reference.`,
    };
  }
  return {
    ruleId: 'contribution-surface/guards-resolve',
    status: 'pass',
    reason: 'Every registered surface guard resolves.',
  };
}

/** Every surface must declare a resolvable consumesProjection (its semantic source). */
export function verdictForProjectionLineage({ missing, dangling }) {
  const problems = [];
  for (const id of missing) problems.push(`${id}: no consumesProjection declared`);
  for (const d of dangling) problems.push(d);
  if (problems.length > 0) {
    return {
      ruleId: 'contribution-surface/projection-lineage',
      status: 'fail',
      reason:
        `Surface projection-lineage problem(s): ${problems.join('; ')}. Every surface must declare ` +
        `consumesProjection — another surface id, "canonical-record", or "self" — so no surface claims ` +
        `a projection from an undeclared source. Add or fix the declaration.`,
    };
  }
  return {
    ruleId: 'contribution-surface/projection-lineage-resolves',
    status: 'pass',
    reason: 'Every surface declares a resolvable consumesProjection.',
  };
}

/** The register file itself is absent. */
export function verdictForMissingRegister({ path }) {
  return {
    ruleId: 'contribution-surface/register-missing',
    status: 'fail',
    reason: `Cannot run the contribution-surface gate: the register was not found at ${path}.`,
  };
}
