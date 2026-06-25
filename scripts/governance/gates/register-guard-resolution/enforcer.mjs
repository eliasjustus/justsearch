/**
 * register-guard-resolution enforcer — the guard-integrity meta-pass (tempdoc 576 §3.1).
 *
 * The repo's register family declares, per surface, a `guard` naming what proves the surface bites
 * (`gate:<id>` / `test:<Name>`, or `self`/`none-yet`/absent = the rung-3 "declared but unguarded"
 * marker). Guard-resolution was previously re-implemented inside the execution-surface enforcer
 * only. This gate generalizes #374 across ALL guard-string registers in one pass:
 *   (a) every NAMED guard resolves to a real artifact (no dangling gate:/test:) — universal, and
 *   (b) per-register, every surface of a kind the register requires to be guarded names a real
 *       guard (the rung-3 "no silent under-enforcement" property), via `requireGuardedKinds`.
 *
 * (b) is per-register because registers differ on which kinds legitimately use `self` (e.g.
 * operation-surfaces has self-grade projections by its own contract) — per AHA, only registers that
 * declare a kind guarded are held to it.
 */

import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

import {
  parseGuards,
  loadGateIds,
  resolveGuardToken,
  detectGuardDowngradeEvents,
} from '../../lib/guard-resolver.mjs';
import { REGISTER_GUARD_RESOLUTION_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import {
  verdictForUnreadableRegisters,
  verdictForUnguardedSurfaces,
  verdictForDanglingGuards,
  verdictForInvalidGuardForm,
} from './truth-table.mjs';
import { statusToSarifLevel } from '../../lib/truth-table-runner.mjs';
import { readFileAtRef } from '../../lib/git-utils.mjs';
import { detectBaselineTamper } from '../../lib/baseline-tamper-detector.mjs';
import { loadChangesets } from '../../lib/changeset-loader.mjs';

const TOOL = { toolName: 'justsearch-register-guard-resolution', toolVersion: '0.1.0' };

export async function enforceRegisterGuardResolution(options) {
  const { repoRoot, gate, baselineRef = null, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const registers = gate.config?.registers ?? [];
  const registryRel = gate.config?.registry ?? 'governance/registry.v1.json';
  const gateIds = loadGateIds(sourceRoot, registryRel);

  const unreadable = [];
  const unguarded = [];
  const dangling = [];
  const invalid = [];
  const downgradeEvents = []; // §5 no-silent-downgrade: guard-strength regressions vs baseline.

  // §5 (tempdoc 576): a guard-downgrade changeset accounts for an intentional weakening across this PR.
  const changesetsDir = gate.changesetsDir;
  const covered =
    changesetsDir != null &&
    loadChangesets({
      repoRoot,
      changesetsDir,
      baselineRef,
      allowedClassifications: new Set(['guard-downgrade']),
      fixtureMode,
      validate: false,
    }).some((d) => d.classification === 'guard-downgrade');

  /** Bare self/none-yet/absent → tokens == []. parseGuards keeps those collapsing to empty. */
  const isBareUnguarded = (guard) => parseGuards(guard).length === 0;

  for (const entry of registers) {
    const regRel = typeof entry === 'string' ? entry : entry.path;
    const requireGuardedKinds = new Set(
      typeof entry === 'object' && Array.isArray(entry.requireGuardedKinds)
        ? entry.requireGuardedKinds
        : [],
    );
    const abs = resolve(sourceRoot, regRel);
    if (!existsSync(abs)) continue; // a register absent in a fixture tree is not this gate's failure
    let reg;
    try {
      reg = JSON.parse(readFileSync(abs, 'utf8'));
    } catch (e) {
      unreadable.push(`${regRel} (${e.message})`);
      continue;
    }
    const surfaces = Array.isArray(reg.surfaces) ? reg.surfaces : [];
    for (const s of surfaces) {
      const tokens = parseGuards(s.guard);
      const hasRealGuard = tokens.some((t) => t.kind === 'gate' || t.kind === 'test');
      // Rung-1 (tempdoc 576 §3.1): a bare self/none-yet/absent guard is no longer representable.
      if (isBareUnguarded(s.guard)) {
        invalid.push(`${regRel} :: ${s.id} (kind=${s.kind}, guard=${s.guard ?? 'absent'})`);
      }
      // A required-guarded kind must name a REAL guard — an exemption does not satisfy it.
      if (requireGuardedKinds.has(s.kind) && !hasRealGuard) {
        unguarded.push(`${regRel} :: ${s.id} (kind=${s.kind}, guard=${s.guard ?? 'none'})`);
      }
      for (const token of tokens) {
        const r = resolveGuardToken(token, { root: sourceRoot, gateIds });
        if (!r.resolved) dangling.push(`${regRel} :: ${s.id}: ${r.reason}`);
      }
    }

    // §5 no-silent-downgrade: compare each entry's guard strength to its baseline. The prior register
    // comes from the git baseline on the real repo, or from `_baseline/<regRel>` in fixtureMode (mirrors
    // class-size's readPriorRatchet) so the downgrade path is exercised by the gate self-test, not only
    // live. A no-op only when no prior exists (first commit / fixture without a _baseline).
    let priorRaw = null;
    if (fixtureMode && fixtureRoot) {
      const fixtureBaseline = resolve(fixtureRoot, '_baseline', regRel);
      if (existsSync(fixtureBaseline)) priorRaw = readFileSync(fixtureBaseline, 'utf8');
    } else if (baselineRef) {
      priorRaw = readFileAtRef(baselineRef, regRel, sourceRoot);
    }
    if (priorRaw) {
      let prior;
      try {
        prior = JSON.parse(priorRaw);
      } catch {
        prior = null;
      }
      if (prior && Array.isArray(prior.surfaces)) {
        downgradeEvents.push(
          ...detectGuardDowngradeEvents({
            baselineSurfaces: prior.surfaces,
            headSurfaces: surfaces,
            regRel,
            covered,
          }),
        );
      }
    }
  }

  const findings = [];
  let verdict = 'pass';
  const push = (v) => {
    if (v.status === 'fail') verdict = 'fail';
    if (v.status !== 'pass') {
      findings.push({ ruleId: v.ruleId, level: statusToSarifLevel(v.status), message: v.reason });
    }
  };
  push(verdictForUnreadableRegisters({ unreadable: unreadable.sort() }));
  push(verdictForInvalidGuardForm({ invalid: invalid.sort() }));
  push(verdictForUnguardedSurfaces({ unguarded: unguarded.sort() }));
  push(verdictForDanglingGuards({ dangling: dangling.sort() }));

  // §5 no-silent-downgrade: the shared detector dispatches covered→note vs silent→error+fail.
  const tamper = detectBaselineTamper(downgradeEvents);
  for (const f of tamper.findings) {
    findings.push({ ruleId: f.ruleId, level: f.level, message: f.message, uri: f.uri });
  }
  if (tamper.fail) verdict = 'fail';

  return { ...TOOL, findings, verdict, ruleDescriptions: REGISTER_GUARD_RESOLUTION_RULE_DESCRIPTIONS };
}
