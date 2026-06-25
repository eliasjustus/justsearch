/**
 * Surface-altitude enforcer — tempdoc 571 (the altitude governing axis), DERIVED form.
 *
 * Altitude is NOT a declared field the gate reads — it is DERIVED from the authority a surface consumes,
 * the SAME derivation the backend wire (RegistryController) and the validator (SurfaceAreaValidator) use:
 *
 *   - consumes ≥1 DiagnosticChannel        ⟹ DIAGNOSTIC
 *   - consumes a DIAGNOSTIC-role Resource   ⟹ DIAGNOSTIC
 *   - consumes a TRUST-role Resource        ⟹ TRUST
 *   - Placement.HEADLESS_AGENT_TOOL         ⟹ TOOL
 *   - (empty signal set)                    ⟹ PRODUCT  (the benign default)
 *
 * Resource roles are parsed from the `*ResourceCatalog.java` files' `.withRole(Role.X)` declarations —
 * coverage projects from the catalogs, so there is no hand-maintained `diagnosticResources` /
 * `trustedMountTags` allowlist to rot behind them (the 530/553 property). The two invariants derivation
 * can still violate, and that this gate forecloses:
 *
 *   - a surface consuming two distinct non-PRODUCT authorities ⟹ a derivation CONFLICT (§4c
 *     merge-foreclosure);
 *   - a surface that DERIVES TRUST but is not CORE provenance ⟹ a forged trust surface (§4d).
 *
 * Config (registry.v1.json gate.config → register surface-altitude.v1.json scan):
 *   - scan.surfaceCatalog: path to the surface catalog (the surface authority).
 *   - scan.resourceCatalogRoots: dirs to walk for `*ResourceCatalog.java` (the Resource-role authority).
 *   - scan.javaInclude: substring a resource-catalog path must contain (default `/src/main/java/`).
 */

import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { resolve, join } from 'node:path';

import { SURFACE_ALTITUDE_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import {
  verdictForMissingRegister,
  verdictForMissingSource,
  verdictForAltitudeConflict,
  verdictForTrustNotCore,
  verdictForDiagnosticNotCore,
} from './truth-table.mjs';
import { statusToSarifLevel } from '../../lib/truth-table-runner.mjs';
import { verdictForVacuousScan } from '../../lib/population-floor.mjs';

const TOOL = { toolName: 'justsearch-surface-altitude', toolVersion: '0.2.0' };
const WALK_EXCLUDES = new Set(['node_modules', 'build', '.git', '.gradle', 'dist']);

export async function enforceSurfaceAltitude(options) {
  const { repoRoot, gate, fixtureMode = false, fixtureRoot } = options;
  const root = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const cfg = gate.config ?? {};

  const findings = [];
  let verdict = 'pass';
  const push = (v, uri) => {
    if (v.status === 'fail') {
      verdict = 'fail';
      findings.push({ ruleId: v.ruleId, level: statusToSarifLevel(v.status), message: v.reason, uri });
    }
  };
  const done = () => ({ ...TOOL, findings, verdict, ruleDescriptions: SURFACE_ALTITUDE_RULE_DESCRIPTIONS });

  const registerRel = cfg.register ?? 'governance/surface-altitude.v1.json';
  const registerAbs = resolve(root, registerRel);
  if (!existsSync(registerAbs)) {
    push(verdictForMissingRegister({ path: registerRel }), registerRel);
    return done();
  }
  const register = JSON.parse(readFileSync(registerAbs, 'utf8'));
  const scan = register.scan ?? {};

  const surfaceCatalogRel = scan.surfaceCatalog;
  const surfaceCatalogAbs = surfaceCatalogRel ? resolve(root, surfaceCatalogRel) : null;
  if (!surfaceCatalogAbs || !existsSync(surfaceCatalogAbs)) {
    push(verdictForMissingSource({ path: surfaceCatalogRel ?? '(unset)' }), surfaceCatalogRel);
    return done();
  }

  // Resource-role index (id → 'DIAGNOSTIC' | 'TRUST'), parsed from every *ResourceCatalog.java under the
  // configured roots — the authority replacing the old hand-maintained allowlists.
  const resourceRoles = parseResourceRoles(root, scan);

  const surfaces = parseSurfaces(readFileSync(surfaceCatalogAbs, 'utf8'));

  // --- §5 vacuous-pass guard (tempdoc 576): the surface set parsed from the catalog must not have
  // silently collapsed to zero (a catalog refactor that breaks the parser would make the altitude /
  // trust / diagnostic conflict checks below pass vacuously while the file still exists). ---
  push(
    verdictForVacuousScan({
      rulePrefix: 'surface-altitude',
      detected: surfaces.length,
      min: scan.expectedMinPopulation ?? 1,
      what: 'surfaces parsed from the surface catalog',
    }),
    surfaceCatalogRel,
  );

  const conflictViolations = [];
  const trustViolations = [];
  const diagnosticViolations = [];
  for (const s of surfaces) {
    const d = deriveAltitude(s, resourceRoles);
    if (d.conflict) {
      conflictViolations.push(`${s.id} (signals ${[...d.signals].sort().join(' + ')})`);
    } else if (d.altitude === 'TRUST' && !s.core) {
      trustViolations.push(`${s.id} (provenance ${s.provenance})`);
    } else if (d.altitude === 'DIAGNOSTIC' && !s.core) {
      diagnosticViolations.push(`${s.id} (provenance ${s.provenance})`);
    }
  }
  conflictViolations.sort();
  trustViolations.sort();
  diagnosticViolations.sort();

  push(verdictForAltitudeConflict({ violations: conflictViolations }), surfaceCatalogRel);
  push(verdictForTrustNotCore({ violations: trustViolations }), surfaceCatalogRel);
  push(verdictForDiagnosticNotCore({ violations: diagnosticViolations }), surfaceCatalogRel);

  return done();
}

/**
 * Mirror of {@code SurfaceAltitude.derive} (Java): collect the non-PRODUCT signal set from the consumed
 * authority, then resolve 0 ⟹ PRODUCT, 1 ⟹ that altitude, ≥2 distinct ⟹ conflict.
 */
function deriveAltitude(surface, resourceRoles) {
  const signals = new Set();
  if (surface.hasChannel) signals.add('DIAGNOSTIC');
  for (const ref of surface.resources) {
    const role = resourceRoles.get(ref);
    if (role === 'DIAGNOSTIC') signals.add('DIAGNOSTIC');
    else if (role === 'TRUST') signals.add('TRUST');
  }
  if (surface.placement === 'HEADLESS_AGENT_TOOL') signals.add('TOOL');
  if (signals.size === 0) return { altitude: 'PRODUCT', signals, conflict: false };
  if (signals.size === 1) return { altitude: [...signals][0], signals, conflict: false };
  return { altitude: 'PRODUCT', signals, conflict: true };
}

/**
 * Build the Resource-role index by walking {@code scan.resourceCatalogRoots} for `*ResourceCatalog.java`
 * files (whose path contains {@code scan.javaInclude}) and parsing each `new Resource(<id>, …)
 * .withRole(Role.X)`. Resources without a `.withRole` default to PRODUCT and are omitted (only
 * DIAGNOSTIC / TRUST raise an altitude signal).
 */
function parseResourceRoles(root, scan) {
  const roots = Array.isArray(scan.resourceCatalogRoots) ? scan.resourceCatalogRoots : [];
  const include = (scan.javaInclude ?? '/src/main/java/').replace(/\\/g, '/');
  const index = new Map();
  for (const r of roots) {
    for (const abs of walk(resolve(root, r), (f) => f.endsWith('ResourceCatalog.java'))) {
      if (include && !abs.replace(/\\/g, '/').includes(include)) continue;
      mergeResourceRoles(index, readFileSync(abs, 'utf8'));
    }
  }
  return index;
}

/** Parse one ResourceCatalog file's `new Resource(<id>, …).withRole(Role.X)` declarations into the index. */
function mergeResourceRoles(index, src) {
  // Local ResourceRef constant map: `FOO_ID = new ResourceRef("core.foo")`.
  const refMap = new Map();
  for (const m of src.matchAll(/(\w+)\s*=\s*new\s+ResourceRef\(\s*"([^"]+)"\s*\)/g)) {
    refMap.set(m[1], m[2]);
  }
  // Split on the `new Resource(` token; each segment's `.withRole(Role.X)` (if any) belongs to that
  // resource, whose id is the first ctor arg (an inline `new ResourceRef("…")` or a constant reference).
  const segments = src.split(/new\s+Resource\(/).slice(1);
  for (const seg of segments) {
    const role = seg.match(/\.withRole\(\s*Role\.(\w+)\s*\)/)?.[1];
    if (role !== 'DIAGNOSTIC' && role !== 'TRUST') continue; // PRODUCT / none → no signal
    const inlineId = seg.match(/^\s*new\s+ResourceRef\(\s*"([^"]+)"\s*\)/)?.[1];
    let id = inlineId;
    if (!id) {
      const idConst = seg.match(/^\s*(\w+)/)?.[1];
      id = idConst ? (refMap.get(idConst) ?? null) : null;
    }
    if (id) index.set(id, role);
  }
}

/**
 * Parse every `new Surface(...)` declaration into { id, provenance, core, hasChannel, placement,
 * resources }. Mirrors the interaction-surface enforcer's parseSurfaces: split on the `new Surface(`
 * token so the FIRST match of each field in a segment belongs to that surface. SurfaceRef / ResourceRef
 * identifier args resolve to their `core.*` string via constant maps built from the same file. The
 * declared `Altitude.X` field (if any) is intentionally NOT read — altitude is derived, not declared.
 */
function parseSurfaces(src) {
  const surfaceRefMap = new Map();
  for (const m of src.matchAll(/(\w+)\s*=\s*new\s+SurfaceRef\(\s*"([^"]+)"\s*\)/g)) {
    surfaceRefMap.set(m[1], m[2]);
  }
  const resourceRefMap = new Map();
  for (const m of src.matchAll(/(\w+)\s*=\s*new\s+ResourceRef\(\s*"([^"]+)"\s*\)/g)) {
    resourceRefMap.set(m[1], m[2]);
  }
  const out = [];
  const segments = src.split(/new\s+Surface\(/).slice(1);
  for (const seg of segments) {
    const idConst = seg.match(/^\s*(\w+)/)?.[1];
    if (!idConst) continue;
    const id = surfaceRefMap.get(idConst) ?? idConst;
    const provenance = seg.match(/Provenance\.(\w+)\s*\(/)?.[1] ?? 'unknown';
    const core = provenance === 'core';
    const placement = seg.match(/Placement\.(\w+)/)?.[1] ?? null;
    const dc = seg.match(/diagnosticChannels\s*\*\/\s*Set\.(?:<[^>]+>)?of\(([^)]*)\)/);
    const hasChannel = !!(dc && dc[1].trim().length > 0);
    const resBlock = seg.match(/resources\s*\*\/\s*Set\.(?:<[^>]+>)?of\(([^)]*)\)/)?.[1] ?? '';
    const resources = resBlock
      .split(',')
      .map((r) => r.trim())
      .filter(Boolean)
      .map((c) => resourceRefMap.get(c) ?? c);
    out.push({ id, provenance, core, hasChannel, placement, resources });
  }
  return out;
}

/** Recursively collect files under {@code dir} whose name satisfies {@code keep}. */
function walk(dir, keep) {
  const out = [];
  if (!existsSync(dir)) return out;
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of entries) {
    if (WALK_EXCLUDES.has(e.name)) continue;
    const abs = join(dir, e.name);
    let isDir = e.isDirectory();
    if (e.isSymbolicLink()) {
      try {
        isDir = statSync(abs).isDirectory();
      } catch {
        continue;
      }
    }
    if (isDir) out.push(...walk(abs, keep));
    else if (keep(e.name)) out.push(abs);
  }
  return out;
}
