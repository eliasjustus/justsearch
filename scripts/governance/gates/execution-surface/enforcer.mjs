/**
 * Execution-surface enforcer — tempdoc 553 pillar c (the anti-fragmentation meta-gate).
 *
 * The keystone of the canonical-search-execution-record design: it makes "every surface that
 * describes what the pipeline did is a DECLARED projection of the one record" a build-time
 * invariant. It is a META-COORDINATOR (cf. prose-tier-register), not a re-implementation of
 * conformance — field/vocabulary conformance stays in the guards the register names
 * (gate:stage-completeness, gate:wire, test:KnowledgeWireContractConformanceTest). See the sibling
 * truth-table.mjs for the full check set (Checks 1-7) and the honest scope limit (553 §5).
 *
 * Config (registry.v1.json gate.config):
 *   - register: path to governance/execution-surfaces.v1.json (the allowlist + scan config).
 *   - registry: path to governance/registry.v1.json (to resolve gate:<id> guards).
 */

import { readFileSync, existsSync } from 'node:fs';
import { resolve, relative } from 'node:path';

import { EXECUTION_SURFACE_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import {
  verdictForUndeclaredSurfaces,
  verdictForOrphanSurfaces,
  verdictForDanglingGuards,
  verdictForUnguardedProjection,
  verdictForUnregisteredSpanEmitter,
  verdictForNonConformanceGuard,
  verdictForMissingReflectiveGuard,
  verdictForMissingRegister,
} from './truth-table.mjs';
import { statusToSarifLevel } from '../../lib/truth-table-runner.mjs';
import { walk, parseGuards, loadGateIds, testFileExists } from '../../lib/guard-resolver.mjs';
import { verdictForVacuousScan } from '../../lib/population-floor.mjs';

const TOOL = { toolName: 'justsearch-execution-surface', toolVersion: '0.1.0' };

export async function enforceExecutionSurface(options) {
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

  const registerRel = cfg.register ?? 'governance/execution-surfaces.v1.json';
  const registerAbs = resolve(root, registerRel);
  if (!existsSync(registerAbs)) {
    push(verdictForMissingRegister({ path: registerRel }), registerRel);
    return { ...TOOL, findings, verdict, ruleDescriptions: EXECUTION_SURFACE_RULE_DESCRIPTIONS };
  }
  const register = JSON.parse(readFileSync(registerAbs, 'utf8'));
  const surfaces = Array.isArray(register.surfaces) ? register.surfaces : [];
  const declared = new Set(surfaces.map((s) => norm(s.path)));
  const scan = register.scan ?? {};

  // --- Check 1: undeclared surfaces (auto-scan Java-main + TS for canonical-type references). ---
  const detected = new Set();
  for (const f of scanJava(root, scan)) detected.add(f);
  for (const f of scanTs(root, scan)) detected.add(f);
  // --- §5 vacuous-pass guard (tempdoc 576): the auto-scan must not have silently collapsed to zero
  // (a renamed module root would make every coverage check below pass vacuously). ---
  push(
    verdictForVacuousScan({
      rulePrefix: 'execution-surface',
      detected: detected.size,
      min: scan.expectedMinPopulation ?? 1,
      what: 'canonical-type-referencing files',
    }),
    registerRel,
  );
  const undeclared = [...detected].filter((p) => !declared.has(p)).sort();
  push(verdictForUndeclaredSurfaces({ undeclared }), registerRel);

  // --- Check 2: orphan surfaces (registered path no longer exists). ---
  const orphans = surfaces
    .filter((s) => existsSync(resolve(root, s.path)) === false)
    .map((s) => s.path)
    .sort();
  push(verdictForOrphanSurfaces({ orphans }), registerRel);

  // --- Check 3: dangling guards (gate:<id> / test:<Name> must resolve). ---
  const gateIds = loadGateIds(root, cfg.registry ?? 'governance/registry.v1.json');
  const dangling = [];
  for (const s of surfaces) {
    for (const token of parseGuards(s.guard)) {
      if (token.kind === 'gate' && !gateIds.has(token.value)) {
        dangling.push(`${s.id}: gate:${token.value} (no such gate)`);
      } else if (token.kind === 'test' && !testFileExists(root, token.value)) {
        dangling.push(`${s.id}: test:${token.value} (no matching test file)`);
      }
    }
  }
  push(verdictForDanglingGuards({ dangling: dangling.sort() }), registerRel);

  // --- Check 4: projection-purity (553 pillar b) — every surface that PROJECTS the canonical
  // record (kind: projection / producer) must name a real derivation guard (a gate: or test:,
  // not "self"): a surface that derives execution facts from the record must be VERIFIED to do
  // so. `projection-pending` is exempt (an explicitly-tracked not-yet-converged surface, e.g.
  // the worker search/* spans). This is the structural form of "no surface authors execution
  // facts independently"; semantic purity itself is the §5 undecidable limit (not mechanized). ---
  const PROJECTION_KINDS = new Set(['projection', 'producer']);
  const unguarded = surfaces
    .filter((s) => PROJECTION_KINDS.has(s.kind))
    .filter((s) => parseGuards(s.guard).length === 0) // "self" / "none-yet" / absent → no real guard
    .map((s) => `${s.id} (kind=${s.kind}, guard=${s.guard ?? 'none'})`)
    .sort();
  push(verdictForUnguardedProjection({ unguarded }), registerRel);

  // --- Check 5: execution-vocabulary fork (553 Phase B) — Java-main files that emit a search/*
  // span-name literal (the execution-span vocabulary) but are NOT registered surfaces. This widens
  // detection beyond Check 1's canonical-TYPE import: the span tree (553 §2's founding third fork)
  // re-models execution WITHOUT importing SearchTrace, so a type-scan can't see it. A span-name
  // literal scan can. HONEST LIMIT (553 §5): a string-literal heuristic — a fork that invents a
  // different vocabulary still escapes. It converts the span-fork class from invisible → caught,
  // not "all forks". No-op unless the register configures scan.executionSpanNameRegex. ---
  const spanForks = scanSpanNameLiterals(root, scan)
    .filter((p) => !declared.has(p))
    .sort();
  push(verdictForUnregisteredSpanEmitter({ spanForks }), registerRel);

  // --- Check 6: conformance-grade guards (553 Phase C, G4) — a projection/producer surface guarded
  // ONLY by test(s) must name a CONFORMANCE test (one whose name matches Conformance / Projection /
  // searchTrace), not an arbitrary unit test. `gate:` guards are conformance by construction (build
  // gates that verify the field/vocabulary). This upgrades Check 4's "names *a* guard" to "names a
  // guard that actually verifies the projection ⊆ record relationship". Honest §5 limit: a naming
  // convention is a proxy for the undecidable "is a pure projection" — it forces an auditable claim,
  // not a proof. Surfaces with no guard at all are handled by Check 4 (unguarded-projection). ---
  const nonConformance = surfaces
    .filter((s) => PROJECTION_KINDS.has(s.kind))
    .filter((s) => {
      const tokens = parseGuards(s.guard);
      if (tokens.length === 0) return false; // unguarded — Check 4 owns this
      if (tokens.some((t) => t.kind === 'gate')) return false; // gate guards = conformance
      return !tokens.some((t) => t.kind === 'test' && CONFORMANCE_TEST_RE.test(t.value));
    })
    .map((s) => `${s.id} (guard=${s.guard})`)
    .sort();
  push(verdictForNonConformanceGuard({ nonConformance }), registerRel);

  // --- Check 7: reflective-guard-per-record (553 §14 G-B) — every canonical + sibling record must
  // have ≥1 registered surface carrying guardKind:"reflective" (a guard that mechanically reflects
  // over the record's fields — getRecordComponents() / Object.keys(FULL) / assertFieldRoles — and
  // asserts each is projected-or-deliberately-dropped). Check 6 forces a conformance-NAMED guard;
  // Check 7 forces, per RECORD, that at least one such guard is a declared REFLECTIVE totality check,
  // so a record can never be added (or have its only reflective guard removed) without a build break.
  // Honest §5 limit: guardKind:"reflective" is an auditable declared claim, not a mechanical proof of
  // totality — it raises the floor from "named conformance test" to "declared field-exhaustive guard". ---
  const recordNames = [
    register.canonicalRecord?.name,
    ...(Array.isArray(register.siblingRecords) ? register.siblingRecords.map((r) => r?.name) : []),
  ].filter(Boolean);
  const reflectivelyGuarded = new Set(
    surfaces.filter((s) => s.guardKind === 'reflective' && s.recordId).map((s) => s.recordId),
  );
  const recordsMissingReflective = recordNames.filter((n) => !reflectivelyGuarded.has(n)).sort();
  push(verdictForMissingReflectiveGuard({ records: recordsMissingReflective }), registerRel);

  return { ...TOOL, findings, verdict, ruleDescriptions: EXECUTION_SURFACE_RULE_DESCRIPTIONS };
}

/** A test guard counts as conformance when its name signals it verifies the projection relationship. */
const CONFORMANCE_TEST_RE = /(?:conformance|projection|searchtrace)/i;

/** Java-main files containing a `"<executionSpanNameRegex>"` string literal (span-name vocabulary). */
function scanSpanNameLiterals(root, scan) {
  const pat = scan.executionSpanNameRegex;
  if (!pat) return [];
  const roots = Array.isArray(scan.javaMainRoots) ? scan.javaMainRoots : [];
  const include = (scan.javaInclude ?? '/src/main/java/').replace(/\\/g, '/');
  const literal = new RegExp(`"${pat}"`);
  const out = [];
  for (const r of roots) {
    for (const abs of walk(resolve(root, r), (f) => f.endsWith('.java'))) {
      const rel = norm(relative(root, abs));
      if (!rel.includes(include)) continue;
      if (literal.test(readFileSync(abs, 'utf8'))) out.push(rel);
    }
  }
  return out;
}

function norm(p) {
  return String(p ?? '').replace(/\\/g, '/');
}

/** Java-main files whose source contains an `import <pattern>;` of a canonical type. */
function scanJava(root, scan) {
  const patterns = Array.isArray(scan.javaImportPatterns) ? scan.javaImportPatterns : [];
  const roots = Array.isArray(scan.javaMainRoots) ? scan.javaMainRoots : [];
  const include = scan.javaInclude ?? '/src/main/java/';
  if (patterns.length === 0) return [];
  const out = [];
  for (const r of roots) {
    for (const abs of walk(resolve(root, r), (f) => f.endsWith('.java'))) {
      const rel = norm(relative(root, abs));
      if (!rel.includes(include.replace(/\\/g, '/'))) continue;
      const src = readFileSync(abs, 'utf8');
      if (patterns.some((p) => src.includes(`import ${p};`) || src.includes(`import ${p}.`))) {
        out.push(rel);
      }
    }
  }
  return out;
}

/** TS files (non-test) under the configured roots that reference the canonical type as an identifier. */
function scanTs(root, scan) {
  const roots = Array.isArray(scan.tsRoots) ? scan.tsRoots : [];
  const excl = Array.isArray(scan.tsExcludeSuffixes) ? scan.tsExcludeSuffixes : [];
  const pat = scan.tsRefPattern ?? 'SearchTrace';
  const re = new RegExp(`\\b${pat}\\b`);
  const out = [];
  for (const r of roots) {
    for (const abs of walk(resolve(root, r), (f) => f.endsWith('.ts') || f.endsWith('.tsx'))) {
      const rel = norm(relative(root, abs));
      if (excl.some((s) => rel.endsWith(s))) continue;
      if (re.test(readFileSync(abs, 'utf8'))) out.push(rel);
    }
  }
  return out;
}

// walk / parseGuards / loadGateIds / testFileExists now live in ../../lib/guard-resolver.mjs
// (tempdoc 576 §3.1) — shared with prose-tier-register and the register-guard-resolution gate.
