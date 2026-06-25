/**
 * Surface-altitude truth table (tempdoc 571 — the surface-tier altitude governing axis), DERIVED form.
 *
 * Sibling of the interaction-surface (561), execution-surface (553), and operation-surface (550)
 * anti-fragmentation gates. Altitude is no longer a declared field the gate reads — it is DERIVED from
 * the authority a surface consumes (the same `SurfaceAltitude.derive` the backend wire + validator use):
 * a DiagnosticChannel ⟹ DIAGNOSTIC; a DIAGNOSTIC/TRUST-role Resource ⟹ that altitude;
 * HEADLESS_AGENT_TOOL ⟹ TOOL. Because altitude is derived, "channel-consuming surface hides as PRODUCT"
 * and "diagnostic-Resource surface hides as PRODUCT" are no longer possible — they are how derivation
 * WORKS. The two invariants derivation CAN still violate, and that this gate forecloses:
 *
 *   - a surface consuming two distinct non-PRODUCT authorities ⟹ a derivation CONFLICT (the §4c
 *     merge-foreclosure — a surface carries exactly one altitude);
 *   - a surface that DERIVES TRUST but is NOT CORE provenance ⟹ a forged trust surface (the surface-tier
 *     completion of 569's reserved channel; the component-tier foreclosure on the trust read-view itself
 *     stays in the FE RESERVED_COMPONENTS).
 *
 * Resource roles are read from the `*ResourceCatalog.java` files (the `.withRole(Role.X)` declarations),
 * so coverage projects from the catalogs — there is no hand-maintained `diagnosticResources` /
 * `trustedMountTags` allowlist to rot behind them (the 530/553 property).
 *
 * Conforms to the kernel truth-table contract: (input) → { ruleId, status, reason }.
 */

/** Verdict when the register file itself is absent. */
export function verdictForMissingRegister({ path }) {
  return {
    ruleId: 'surface-altitude/register-missing',
    status: 'fail',
    reason: `Cannot run the surface-altitude gate: the register was not found at ${path}.`,
  };
}

/** Verdict when the scanned surface catalog is absent. */
export function verdictForMissingSource({ path }) {
  return {
    ruleId: 'surface-altitude/source-missing',
    status: 'fail',
    reason:
      `Cannot run the surface-altitude gate: the surface catalog was not found at ${path}. The gate ` +
      `parses it (with the Resource catalogs) as the authority for deriving each surface's altitude.`,
  };
}

/**
 * Verdict (THE merge-foreclosure): one or more surfaces consume two distinct non-PRODUCT authorities,
 * so no single altitude derives. A surface carries exactly one altitude (tempdoc 571 §4c) — merging two
 * authorities (e.g. the diagnostic Logs view + the trust Activity view) into one surface is foreclosed.
 */
export function verdictForAltitudeConflict({ violations }) {
  if (violations.length > 0) {
    return {
      ruleId: 'surface-altitude/altitude-conflict',
      status: 'fail',
      reason:
        `These surfaces derive an altitude CONFLICT (consume two distinct non-PRODUCT authorities): ` +
        `${violations.join(', ')}. A surface carries exactly one altitude (tempdoc 571 §4c) — split the ` +
        `responsibilities into separate surfaces, or drop one authority. Two authorities cannot be ` +
        `merged into one surface (the merge-foreclosure).`,
    };
  }
  return {
    ruleId: 'surface-altitude/no-altitude-conflict',
    status: 'pass',
    reason: 'No surface consumes two distinct non-PRODUCT authorities (healthy).',
  };
}

/**
 * Verdict (the trust foreclosure): one or more surfaces DERIVE {@code Altitude.TRUST} (consume a
 * TRUST-role Resource) but are NOT CORE provenance. A trust read-view is host-owned by construction —
 * a plugin (or any non-CORE provenance) consuming the trust authority is the forged-trust defect at the
 * surface tier (tempdoc 571 §4d; the surface-tier completion of 569's reserved channel).
 */
export function verdictForTrustNotCore({ violations }) {
  if (violations.length > 0) {
    return {
      ruleId: 'surface-altitude/trust-requires-core',
      status: 'fail',
      reason:
        `These surfaces DERIVE TRUST (consume a TRUST-role Resource) but are not CORE provenance: ` +
        `${violations.join(', ')}. TRUST is CORE-only by construction — a non-CORE trust surface could ` +
        `forge or occlude the authorization audit (tempdoc 571 §4d). A plugin cannot consume the trust ` +
        `authority to ship a trust surface; make it CORE, or drop the TRUST-role Resource.`,
    };
  }
  return {
    ruleId: 'surface-altitude/trust-is-core',
    status: 'pass',
    reason: 'Every surface that derives TRUST is CORE provenance (healthy).',
  };
}

/**
 * Verdict (the diagnostic foreclosure): one or more surfaces DERIVE {@code Altitude.DIAGNOSTIC} (consume
 * a DiagnosticChannel or a DIAGNOSTIC-role Resource) but are NOT CORE provenance. A diagnostic surface is
 * plugin-INELIGIBLE until tempdoc 560 §4a (backend capability-attenuation) ships — streaming the raw
 * head-log to attenuated plugin code needs an attenuation substrate that does not yet exist, so this is a
 * derived "no for now" (tempdoc 571 §4d), the build-time sibling of the FE merge clamp that forecloses it
 * at runtime. (Forecloses claiming diagnostic ALTITUDE / Diagnostics-band homing; the deeper channel-DATA
 * attenuation is the 560 §4a concern.)
 */
export function verdictForDiagnosticNotCore({ violations }) {
  if (violations.length > 0) {
    return {
      ruleId: 'surface-altitude/diagnostic-requires-core',
      status: 'fail',
      reason:
        `These surfaces DERIVE DIAGNOSTIC (consume a DiagnosticChannel or DIAGNOSTIC-role Resource) but ` +
        `are not CORE provenance: ${violations.join(', ')}. A diagnostic surface is plugin-ineligible ` +
        `until 560 §4a (backend capability-attenuation) ships — a plugin cannot claim diagnostic ` +
        `altitude / Diagnostics-band homing (tempdoc 571 §4d). Make it CORE, or drop the diagnostic ` +
        `authority.`,
    };
  }
  return {
    ruleId: 'surface-altitude/diagnostic-is-core',
    status: 'pass',
    reason: 'Every surface that derives DIAGNOSTIC is CORE provenance (healthy).',
  };
}
