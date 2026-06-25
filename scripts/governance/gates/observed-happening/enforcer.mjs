/**
 * Observed-happening enforcer — tempdoc 575 (the projection spine at the observability-authority tier).
 *
 * The observability stream family is declared as CONCEPTS in governance/observed-happening.v1.json, each
 * naming one canonical source + its contributors. The gate parses the actual stream ids + origins from
 * the catalog SOURCE (the *ResourceCatalog.java / *DiagnosticChannelCatalog.java files — coverage projects
 * from the catalogs, no hand-maintained allowlist; the 530/553 property) and forecloses, BOUNDED per
 * 575 §9:
 *
 *   - a declared contributor that resolves to no real stream id           ⟹ contributor-unresolved
 *   - a concept whose canonicalSource is not one of its contributors      ⟹ concept-canonical-source
 *   - a stream claimed by two concepts (the F-2 re-fragmentation class)   ⟹ contributor-shared
 *   - a Resource declaring an operator-trace origin (ProducerKind)        ⟹ operator-trace-must-be-channel
 *
 * Config (registry.v1.json gate.config → register observed-happening.v1.json scan):
 *   - scan.resourceCatalogRoots: dirs to walk for `*ResourceCatalog.java` (Resource ids + origins).
 *   - scan.diagnosticChannelCatalogRoots: dirs to walk for `*DiagnosticChannelCatalog.java` (channel ids).
 *   - scan.javaInclude: substring a catalog path must contain (default `/src/main/java/`).
 */

import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { resolve, join } from 'node:path';

import { OBSERVED_HAPPENING_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import {
  verdictForMissingRegister,
  verdictForUnresolvedContributors,
  verdictForSourcelessConcept,
  verdictForSharedContributor,
  verdictForOperatorTraceResource,
  verdictForKindMismatch,
  verdictForUnresolvedProjection,
  verdictForStatefulMissingLiveness,
  verdictForStatefulUnprojected,
  verdictForUncoveredStream,
} from './truth-table.mjs';
import { statusToSarifLevel } from '../../lib/truth-table-runner.mjs';
import { verdictForVacuousScan } from '../../lib/population-floor.mjs';

const TOOL = { toolName: 'justsearch-observed-happening', toolVersion: '0.1.0' };
const WALK_EXCLUDES = new Set(['node_modules', 'build', '.git', '.gradle', 'dist']);

export async function enforceObservedHappening(options) {
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
  const done = () => ({ ...TOOL, findings, verdict, ruleDescriptions: OBSERVED_HAPPENING_RULE_DESCRIPTIONS });

  const registerRel = cfg.register ?? 'governance/observed-happening.v1.json';
  const registerAbs = resolve(root, registerRel);
  if (!existsSync(registerAbs)) {
    push(verdictForMissingRegister({ path: registerRel }), registerRel);
    return done();
  }
  const register = JSON.parse(readFileSync(registerAbs, 'utf8'));
  const scan = register.scan ?? {};
  const concepts = Array.isArray(register.concepts) ? register.concepts : [];

  // The real stream universe, parsed from catalog source.
  const resourceOrigins = new Map(); // resource id → ProducerKind (only resources with a declared origin)
  const resourceIds = parseResourceIds(root, scan, resourceOrigins);
  const channelIds = parseChannelIds(root, scan);
  const streamIds = new Set([...resourceIds, ...channelIds]);
  const mountTags = parseSurfaceMountTags(root, scan); // the read-view surface mount tags (jf-*)

  // --- §5 vacuous-pass guard (tempdoc 576): the stream universe parsed from the Resource/Channel
  // catalogs must not have silently collapsed to zero (a renamed catalog root would make every
  // coverage check below — reverse-coverage, kind-match, contributor-resolution — pass vacuously). ---
  push(
    verdictForVacuousScan({
      rulePrefix: 'observed-happening',
      detected: streamIds.size,
      min: scan.expectedMinPopulation ?? 1,
      what: 'parsed Resource/Channel stream ids',
    }),
    registerRel,
  );

  // Rule inputs.
  const unresolved = [];
  const sourceless = [];
  const seen = new Map(); // contributor id → first concept that claimed it
  const shared = [];
  const kindMismatch = [];
  const projectionUnresolved = [];
  const statefulNoLiveness = [];
  const statefulUnprojected = [];
  for (const c of concepts) {
    const cid = c.id ?? '(unnamed concept)';
    const contributors = Array.isArray(c.contributors) ? c.contributors : [];
    for (const ref of contributors) {
      if (!streamIds.has(ref)) unresolved.push(`${ref} (concept ${cid})`);
      const prior = seen.get(ref);
      if (prior && prior !== cid) shared.push(`${ref} (concepts ${prior} + ${cid})`);
      else if (!prior) seen.set(ref, cid);
    }
    const src = c.canonicalSource;
    if (typeof src !== 'string' || !contributors.includes(src)) {
      sourceless.push(`${cid} (canonicalSource ${src ?? '(unset)'})`);
    }
    // (A) kind ↔ primitive-class consistency: a channel-sourced concept is a diagnostic-channel; a
    // Resource-sourced one is not. Only checked when the source resolves (unresolved is its own rule).
    if (typeof src === 'string') {
      const isChannel = channelIds.has(src);
      const isResource = resourceIds.has(src);
      if (isChannel && c.kind !== 'diagnostic-channel') {
        kindMismatch.push(`${cid} (kind "${c.kind}" but source is a DiagnosticChannel)`);
      } else if (isResource && c.kind === 'diagnostic-channel') {
        kindMismatch.push(`${cid} (kind "diagnostic-channel" but source is a Resource)`);
      }
    }
    // (B) governed projections: each DECLARED projection must resolve to a real surface mount tag.
    for (const p of Array.isArray(c.projections) ? c.projections : []) {
      if (!mountTags.has(p)) projectionUnresolved.push(`${p} (concept ${cid})`);
    }
    // (C-i) stateful ⟹ liveness declared.
    if (c.stateful === true && (typeof c.livenessOwner !== 'string' || c.livenessOwner.trim() === '')) {
      statefulNoLiveness.push(cid);
    }
    // (C-iii / §17 Face B) stateful ⟹ at least one projection renders its live state (presentation
    // reverse-coverage). Resolution of those projections is the projection-unresolved rule above.
    if (c.stateful === true && !(Array.isArray(c.projections) && c.projections.length > 0)) {
      statefulUnprojected.push(cid);
    }
  }
  const operatorTrace = [...resourceOrigins.entries()].map(([id, kind]) => `${id} (origin ${kind})`);

  // (8) reverse-coverage (575 §13 L1): every parsed Resource/Channel id must be a declared contributor
  // of some concept OR explicitly listed in `outOfFamily` (with a reason) — a new undeclared stream is a
  // build failure. `seen` holds every declared contributor; `streamIds` is the parsed catalog universe.
  const outOfFamily = new Set(
    (Array.isArray(register.outOfFamily) ? register.outOfFamily : []).map((e) => e?.id).filter(Boolean),
  );
  const uncovered = [...streamIds].filter((id) => !seen.has(id) && !outOfFamily.has(id));

  // (9) liveness-window-coherent — RETIRED (tempdoc 575 §17 Face A). The window's coherence is no
  // longer DETECTED here: the worker + FE constants are now GENERATED from this register
  // (scripts/codegen/gen-liveness-constants.mjs), which validates the ordering law at generation and
  // throws on an incoherent window. The `check-liveness-constants-regen` gate (runs the generator
  // --check) is the replacement teeth — drift is impossible by construction, not caught after the fact.

  push(verdictForUnresolvedContributors({ violations: unique(unresolved).sort() }), registerRel);
  push(verdictForSourcelessConcept({ violations: unique(sourceless).sort() }), registerRel);
  push(verdictForSharedContributor({ violations: unique(shared).sort() }), registerRel);
  push(verdictForOperatorTraceResource({ violations: operatorTrace.sort() }), registerRel);
  push(verdictForKindMismatch({ violations: unique(kindMismatch).sort() }), registerRel);
  push(verdictForUnresolvedProjection({ violations: unique(projectionUnresolved).sort() }), registerRel);
  push(verdictForStatefulMissingLiveness({ violations: unique(statefulNoLiveness).sort() }), registerRel);
  push(verdictForStatefulUnprojected({ violations: unique(statefulUnprojected).sort() }), registerRel);
  push(verdictForUncoveredStream({ violations: unique(uncovered).sort() }), registerRel);

  return done();
}

/** Parse the surface catalog's `"jf-…"` mount-tag string literals — the valid read-view set for §4.1. */
function parseSurfaceMountTags(root, scan) {
  const tags = new Set();
  const rel = scan.surfaceCatalog;
  if (!rel) return tags;
  const abs = resolve(root, rel);
  if (!existsSync(abs)) return tags;
  for (const m of readFileSync(abs, 'utf8').matchAll(/"(jf-[a-z0-9-]+)"/g)) tags.add(m[1]);
  return tags;
}

function unique(arr) {
  return [...new Set(arr)];
}

/**
 * Walk {@code scan.resourceCatalogRoots} for `*ResourceCatalog.java`, returning the set of declared
 * Resource ids and (as a side-effect into {@code origins}) the id → ProducerKind map for any Resource
 * that declares an operator-trace `.withOrigin(ProducerKind.X)`.
 */
function parseResourceIds(root, scan, origins) {
  const roots = Array.isArray(scan.resourceCatalogRoots) ? scan.resourceCatalogRoots : [];
  const include = (scan.javaInclude ?? '/src/main/java/').replace(/\\/g, '/');
  const ids = new Set();
  for (const r of roots) {
    for (const abs of walk(resolve(root, r), (f) => f.endsWith('Catalog.java'))) {
      if (include && !abs.replace(/\\/g, '/').includes(include)) continue;
      const src = readFileSync(abs, 'utf8');
      // Key off `implements ResourceCatalog`, NOT the *ResourceCatalog.java filename (575 §13 L1): the
      // suffix proxy missed ConditionRecoveryIndexCatalog (a real ResourceCatalog whose name ends
      // `IndexCatalog`). The implements-clause is the actual declaration; robust to naming.
      if (!/\bimplements\b[^{]*\bResourceCatalog\b/.test(src)) continue;
      mergeResourceIds(ids, origins, src);
    }
  }
  return ids;
}

/**
 * Parse one ResourceCatalog file. Every `ResourceRef("core.x")` literal (a constant or inline) is a
 * declared stream id — catalogs commonly build Resources through a helper (e.g. AdvisoryResourceCatalog's
 * `advisoryResource(id, …)`), so the id is NOT always the literal first arg of `new Resource(`; reading
 * the ResourceRef literals resolves those. Origins are read from the `new Resource(<id>, …).withOrigin(
 * ProducerKind.X)` segments (mapping the segment's first-arg id to the operator-trace origin).
 */
function mergeResourceIds(ids, origins, src) {
  const refMap = refConstants(src, 'ResourceRef');
  // Declared ids: every ResourceRef literal in the file (constant values + any inline literal).
  for (const v of refMap.values()) ids.add(v);
  for (const m of src.matchAll(/new\s+ResourceRef\(\s*"([^"]+)"\s*\)/g)) ids.add(m[1]);
  // Origins: tie any `.withOrigin(ProducerKind.X)` to the id of its `new Resource(` segment.
  for (const seg of src.split(/new\s+Resource\(/).slice(1)) {
    const originKind = seg.match(/\.withOrigin\(\s*ProducerKind\.(\w+)\s*\)/)?.[1];
    if (!originKind) continue;
    const id = firstId(seg, refMap, 'ResourceRef');
    if (id) origins.set(id, originKind);
  }
}

/** Walk {@code scan.diagnosticChannelCatalogRoots} for `*DiagnosticChannelCatalog.java`, returning ids. */
function parseChannelIds(root, scan) {
  const roots = Array.isArray(scan.diagnosticChannelCatalogRoots) ? scan.diagnosticChannelCatalogRoots : [];
  const include = (scan.javaInclude ?? '/src/main/java/').replace(/\\/g, '/');
  const ids = new Set();
  for (const r of roots) {
    for (const abs of walk(resolve(root, r), (f) => f.endsWith('Catalog.java'))) {
      if (include && !abs.replace(/\\/g, '/').includes(include)) continue;
      const src = readFileSync(abs, 'utf8');
      // Key off `implements DiagnosticChannelCatalog` (robust to filename — symmetric with the Resource
      // parser, 575 §13 L1). Every DiagnosticChannelRef literal (constant or inline) is a declared id.
      if (!/\bimplements\b[^{]*\bDiagnosticChannelCatalog\b/.test(src)) continue;
      for (const m of src.matchAll(/new\s+DiagnosticChannelRef\(\s*"([^"]+)"\s*\)/g)) ids.add(m[1]);
    }
  }
  return ids;
}

/** Build the `CONST = new <RefType>("core.x")` → "core.x" map for a catalog source. */
function refConstants(src, refType) {
  const map = new Map();
  const re = new RegExp(`(\\w+)\\s*=\\s*new\\s+${refType}\\(\\s*"([^"]+)"\\s*\\)`, 'g');
  for (const m of src.matchAll(re)) map.set(m[1], m[2]);
  return map;
}

/** The id of a `new X(<first-arg>, …)` segment: an inline `new <RefType>("…")` or a constant reference. */
function firstId(seg, refMap, refType) {
  const inline = seg.match(new RegExp(`^\\s*new\\s+${refType}\\(\\s*"([^"]+)"\\s*\\)`))?.[1];
  if (inline) return inline;
  const idConst = seg.match(/^\s*(\w+)/)?.[1];
  return idConst ? (refMap.get(idConst) ?? null) : null;
}

/** Recursively yield files under {@code dir} matching {@code match(filename)}. */
function* walk(dir, match) {
  if (!existsSync(dir)) return;
  for (const entry of readdirSync(dir)) {
    if (WALK_EXCLUDES.has(entry)) continue;
    const abs = join(dir, entry);
    const st = statSync(abs);
    if (st.isDirectory()) yield* walk(abs, match);
    else if (match(entry)) yield abs;
  }
}

