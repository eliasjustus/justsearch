/**
 * Runtime-state enforcer — tempdoc 737 §12c (the AI-runtime fork-killer).
 *
 * Applies the execution-surface pattern (tempdoc 553 pillar c / 576) to the AI-runtime lifecycle:
 * makes "every surface that describes/consumes the AI-runtime authority (RuntimeStatus/RuntimeSpec/
 * RuntimeGpuLease/RuntimeReconciler) is a DECLARED projection/consumer of that one package" a
 * build-time invariant. Deliberately a SMALLER gate than execution-surface's seven checks — Phase 1
 * has no projection-purity / conformance-naming / span-vocabulary obligations yet (737 §12e); those
 * can be added here if a later phase needs them, following the sibling gate's precedent.
 *
 * Three checks (see truth-table.mjs):
 *   (a) unregistered-referencer — every Java-main file referencing the runtimestate package's FQN
 *       must be a registered surface.
 *   (b) register-row-resolves — every registered surface path exists (orphan-surface) AND every
 *       surface's guard resolves to a real gate/test/exempt-reason (dangling-guard).
 *   (c) vacuous-scan — the shared §5 population-floor guard (scripts/governance/lib/population-floor.mjs).
 *
 * Config (registry.v1.json gate.config):
 *   - register: path to governance/runtime-state.v1.json (the allowlist + scan config).
 *   - registry: path to governance/registry.v1.json (to resolve gate:<id> guards).
 */

import { readFileSync, existsSync } from 'node:fs';
import { resolve, relative } from 'node:path';

import { RUNTIME_STATE_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import {
  verdictForUnregisteredReferencer,
  verdictForOrphanSurfaces,
  verdictForDanglingGuards,
  verdictForMissingRegister,
} from './truth-table.mjs';
import { statusToSarifLevel } from '../../lib/truth-table-runner.mjs';
import { walk, parseGuards, loadGateIds, testFileExists } from '../../lib/guard-resolver.mjs';
import { verdictForVacuousScan } from '../../lib/population-floor.mjs';

const TOOL = { toolName: 'justsearch-runtime-state', toolVersion: '0.1.0' };

export async function enforceRuntimeState(options) {
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

  const registerRel = cfg.register ?? 'governance/runtime-state.v1.json';
  const registerAbs = resolve(root, registerRel);
  if (!existsSync(registerAbs)) {
    push(verdictForMissingRegister({ path: registerRel }), registerRel);
    return { ...TOOL, findings, verdict, ruleDescriptions: RUNTIME_STATE_RULE_DESCRIPTIONS };
  }
  const register = JSON.parse(readFileSync(registerAbs, 'utf8'));
  const surfaces = Array.isArray(register.surfaces) ? register.surfaces : [];
  const declared = new Set(surfaces.map((s) => norm(s.path)));
  const scan = register.scan ?? {};

  // --- Check (a): unregistered referencer (auto-scan Java-main for the runtimestate FQN prefix). ---
  const detected = new Set(scanJava(root, scan));
  // --- §5 vacuous-pass guard (tempdoc 576): the auto-scan must not have silently collapsed to zero. ---
  push(
    verdictForVacuousScan({
      rulePrefix: 'runtime-state',
      detected: detected.size,
      min: scan.expectedMinPopulation ?? 1,
      what: 'runtimestate-referencing files',
    }),
    registerRel,
  );
  const undeclared = [...detected].filter((p) => !declared.has(p)).sort();
  push(verdictForUnregisteredReferencer({ undeclared }), registerRel);

  // --- Check (b) part 1: orphan surfaces (registered path no longer exists). ---
  const orphans = surfaces
    .filter((s) => existsSync(resolve(root, s.path)) === false)
    .map((s) => s.path)
    .sort();
  push(verdictForOrphanSurfaces({ orphans }), registerRel);

  // --- Check (b) part 2: dangling guards (gate:<id> / test:<Name> / exempt:<reason> must resolve). ---
  const gateIds = loadGateIds(root, cfg.registry ?? 'governance/registry.v1.json');
  const dangling = [];
  for (const s of surfaces) {
    for (const token of parseGuards(s.guard)) {
      if (token.kind === 'gate' && !gateIds.has(token.value)) {
        dangling.push(`${s.id}: gate:${token.value} (no such gate)`);
      } else if (token.kind === 'test' && !testFileExists(root, token.value)) {
        dangling.push(`${s.id}: test:${token.value} (no matching test file)`);
      } else if (token.kind === 'exempt' && !(token.value && token.value.trim())) {
        dangling.push(`${s.id}: exempt: (missing reason)`);
      }
    }
  }
  push(verdictForDanglingGuards({ dangling: dangling.sort() }), registerRel);

  return { ...TOOL, findings, verdict, ruleDescriptions: RUNTIME_STATE_RULE_DESCRIPTIONS };
}

function norm(p) {
  return String(p ?? '').replace(/\\/g, '/');
}

/**
 * Java-main files (under scan.javaMainRoots, restricted to scan.javaInclude) whose source contains
 * any of scan.javaFqnPrefixPatterns as a substring. Broader than an "import X;" literal check
 * (execution-surface's approach): catches both `import io.justsearch...RuntimeReconciler;` AND a
 * bare fully-qualified inline reference (`io.justsearch...RuntimeReconciler reconciler` as a field/
 * parameter type) — two current referencers in this package (OrchestrationAssembly, OrchestrationPhase,
 * HeadAssembly) use the latter form.
 */
function scanJava(root, scan) {
  const patterns = Array.isArray(scan.javaFqnPrefixPatterns) ? scan.javaFqnPrefixPatterns : [];
  const roots = Array.isArray(scan.javaMainRoots) ? scan.javaMainRoots : [];
  const include = (scan.javaInclude ?? '/src/main/java/').replace(/\\/g, '/');
  if (patterns.length === 0) return [];
  const out = [];
  for (const r of roots) {
    for (const abs of walk(resolve(root, r), (f) => f.endsWith('.java'))) {
      const rel = norm(relative(root, abs));
      if (!rel.includes(include)) continue;
      const src = readFileSync(abs, 'utf8');
      if (patterns.some((p) => src.includes(p))) {
        out.push(rel);
      }
    }
  }
  return out;
}
