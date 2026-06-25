/**
 * Rule descriptions for the contribution-surface gate (tempdoc 560 §5).
 * Keyed by ruleId; surfaced in SARIF + `--explain`.
 */
export const CONTRIBUTION_SURFACE_RULE_DESCRIPTIONS = {
  'contribution-surface/registry-barrel-purity':
    'The registry FE type barrel (modules/ui-web/src/api/types/registry.ts) declares a hand-authored ' +
    'wire-object shape (a bare `interface X {}` or `type X = { … }`) that is not a derivation of a ' +
    'generated wire type and not on registryAllowlist. That is a second shape authority — the exact ' +
    'drift class tempdoc 560 §4c retired. Derive it from the generated wire (Omit<…Wire>/NonNullable<…>) ' +
    'or add it to registryAllowlist with a reason.',
  'contribution-surface/registry-barrel-import-purity':
    'The registry barrel imports from a module outside the allowed set (../generated/* or zod). ' +
    'Importing a hand-authored shape from a sibling FE module launders a second shape authority into ' +
    'the barrel — move the shape into the generated pipeline or remove the import.',
  'contribution-surface/allowlist-orphan':
    'A registryAllowlist name is not declared in the barrel — the allowlist has drifted. Remove the ' +
    'stale entry so a deleted helper/envelope keeps no standing purity exemption.',
  'contribution-surface/surface-projection-coherence':
    'A `generated`/`generated-nested` surface either has a missing generatedModule or a wireType the ' +
    'barrel never references — the projection it claims does not back the FE. Fix the register or barrel.',
  'contribution-surface/grandfather-coverage':
    'A grandfathered-pending registry primitive names a RegistryController handler that no longer ' +
    'exists. Update servedRaw (renamed) or drop the entry (endpoint removed).',
  'contribution-surface/grandfather-drift':
    'A grandfathered-pending primitive has gained a generated schema-types module but is still listed ' +
    'as pending. Promote it to surfaces[] (projection: "generated") and remove it from ' +
    'grandfatheredPending — a primitive cannot be quietly projected while the register calls it ' +
    'unprojected. This is the future-proofing teeth.',
  'contribution-surface/dangling-guard':
    'A surface declares a guard (gate:<id> / test:<Name>) that does not resolve to a real gate in ' +
    'registry.v1.json or a real test file. Fix the guard or the reference.',
  'contribution-surface/projection-lineage':
    'A surface is missing consumesProjection, or names one that does not resolve. Every surface must ' +
    'declare its semantic source (another surface id, "canonical-record", or "self"). Add or fix it.',
  'contribution-surface/register-missing':
    'governance/contribution-surfaces.v1.json (the contribution-surface register) was not found at its ' +
    'configured path.',
  'contribution-surface/registry-barrel-pure':
    'The registry barrel declares no hand-authored wire-object shape — every type derives from the generated wire (healthy).',
  'contribution-surface/registry-barrel-imports-pure':
    'The registry barrel imports only from the generated projection / zod (healthy).',
  'contribution-surface/allowlist-resolves': 'Every registryAllowlist name is declared in the barrel (healthy).',
  'contribution-surface/surfaces-coherent': 'Every generated surface has a present module the barrel references (healthy).',
  'contribution-surface/grandfather-covered': 'Every grandfathered-pending primitive still has its RegistryController handler (healthy).',
  'contribution-surface/grandfather-stable': 'No grandfathered-pending primitive has silently gained a generated module (healthy).',
  'contribution-surface/guards-resolve': 'Every registered surface guard resolves (healthy).',
  'contribution-surface/projection-lineage-resolves': 'Every surface declares a resolvable consumesProjection (healthy).',
  'contribution-surface/vacuous-scan':
    'Fewer non-empty registry type barrels were read than scan.expectedMinPopulation (default 1) — every ' +
    'configured barrel was renamed/moved so the purity scan inspected empty source and would pass ' +
    'VACUOUSLY — the §5 vacuous-pass downgrade (tempdoc 576). Fix scan.barrel, or lower ' +
    'scan.expectedMinPopulation with the change that removed the barrel(s).',
  'contribution-surface/scan-population-live':
    'At least the declared floor of registry type barrels were read non-empty — not vacuous (healthy).',
};
