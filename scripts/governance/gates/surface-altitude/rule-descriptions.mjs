/**
 * Rule descriptions for the surface-altitude gate (tempdoc 571 — the altitude governing axis), DERIVED
 * form. Keyed by ruleId; surfaced in SARIF + `--explain`.
 */
export const SURFACE_ALTITUDE_RULE_DESCRIPTIONS = {
  'surface-altitude/register-missing':
    'governance/surface-altitude.v1.json (the surface-altitude register) was not found at its ' +
    'configured path.',
  'surface-altitude/source-missing':
    'The scanned surface catalog (CoreSurfaceCatalog.java) was not found at its register-declared ' +
    'path. Fix scan.surfaceCatalog in governance/surface-altitude.v1.json.',
  'surface-altitude/altitude-conflict':
    'A surface consumes two distinct non-PRODUCT authorities (e.g. a diagnostic Resource AND a trust ' +
    'Resource), so no single altitude derives. A surface carries exactly one altitude (tempdoc 571 ' +
    '§4c, the merge-foreclosure) — split the responsibilities into separate surfaces, or drop one ' +
    'authority.',
  'surface-altitude/no-altitude-conflict':
    'No surface consumes two distinct non-PRODUCT authorities (healthy).',
  'surface-altitude/trust-requires-core':
    'A surface DERIVES Altitude.TRUST (it consumes a TRUST-role Resource) but is not CORE provenance. ' +
    'TRUST (the authorization audit read-view) is host-owned by construction — a non-CORE trust ' +
    'surface could forge or occlude the audit. This is the surface-tier completion of 569\'s reserved ' +
    'channel (tempdoc 571 §4d). Make the surface CORE, or drop the TRUST-role Resource. (The ' +
    'component-tier foreclosure on the trust read-view itself lives in the FE RESERVED_COMPONENTS.)',
  'surface-altitude/trust-is-core':
    'Every surface that derives TRUST is CORE provenance (healthy).',
  'surface-altitude/diagnostic-requires-core':
    'A surface DERIVES Altitude.DIAGNOSTIC (it consumes a DiagnosticChannel or a DIAGNOSTIC-role ' +
    'Resource) but is not CORE provenance. A diagnostic surface is plugin-ineligible until tempdoc ' +
    '560 §4a (backend capability-attenuation) ships — a plugin cannot claim diagnostic altitude / ' +
    'Diagnostics-band homing (tempdoc 571 §4d, the build-time sibling of the FE merge clamp). Make the ' +
    'surface CORE, or drop the diagnostic authority. (The deeper channel-DATA attenuation is 560 §4a.)',
  'surface-altitude/diagnostic-is-core':
    'Every surface that derives DIAGNOSTIC is CORE provenance (healthy).',
  'surface-altitude/vacuous-scan':
    'Fewer surfaces were parsed from the surface catalog than scan.expectedMinPopulation (default 1) — ' +
    'almost always a catalog refactor that broke the parser (the file still exists, so missing-source ' +
    'does not fire). The altitude / trust / diagnostic conflict checks would then pass VACUOUSLY — the ' +
    '§5 vacuous-pass downgrade (tempdoc 576). Fix the catalog/parser, or lower scan.expectedMinPopulation.',
  'surface-altitude/scan-population-live':
    'The catalog parse yielded >= the declared floor of surfaces — not vacuous (healthy).',
};
