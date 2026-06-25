/**
 * Consumer-drift enforcer — discipline-gate kernel gate kind (tempdoc 531).
 *
 * Generalizes the one-shot C-018 audit (tempdoc 527) into a recurring CI gate:
 * for each declared substrate "slot", count current *production* consumers and
 * fail when the count drops below the slot's `expectedMin` floor (after any
 * declared grace window), unless a classifying changeset is present.
 *
 * Slots live in `gates/consumer-drift/slots.json` (the gate's `baseline.path`,
 * modeled as a ratchet-file the same way class-size pins LOC). Each slot:
 *
 *   {
 *     "id": "virtual-operation-catalog",
 *     "symbol": "VirtualOperationCatalog",          // word-boundary match
 *     "declaredIn": "modules/.../VirtualOperationCatalog.ts",
 *     "includeGlobs": ["modules/**\/src/**\/*.ts"], // where consumers may live
 *     "excludeGlobs": ["**\/*.test.ts"],
 *     "expectedMin": 1,
 *     "grace": { "untilDate": "2026-07-01" }         // optional, date-bound
 *   }
 *
 * A consumer is a production file (in includeGlobs − excludeGlobs − declaredIn)
 * whose content references the slot symbol. Verdicts come from `truth-table.mjs`.
 *
 * First-slice scope: count-based drift + date-bound grace + changeset escape.
 * Tempdoc-bound / commit-bound grace DSL (tempdoc 531 §grace semantics) and
 * the `match: type-reference|method-invocation` refinement are documented
 * follow-ups; this slice biases to symbol-occurrence counting.
 */

import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { resolve, relative, join } from 'node:path';

import {
  CONSUMER_DRIFT_CLASSIFICATIONS,
  CONSUMER_DRIFT_REQUIRE_JUSTIFICATION,
  indexClassificationsBySlot,
} from './classifications.mjs';
import { CONSUMER_DRIFT_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import {
  verdictForSlot,
  verdictForFloorChange,
  verdictForSlotRemoval,
  verdictForUncoveredSubstrate,
  verdictForStaleGrandfather,
} from './truth-table.mjs';
import { loadChangesets } from '../../lib/changeset-loader.mjs';
import { readFileAtRef } from '../../lib/git-utils.mjs';

export async function enforceConsumerDrift(options) {
  const { repoRoot, gate, baselineRef, fixtureMode = false, fixtureRoot, now } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;

  // Malformed slots.json must fail THIS gate, not crash the whole governance
  // run (review M1 — run.mjs does not wrap runGate in try/catch).
  let slots;
  try {
    slots = loadSlots(resolve(sourceRoot, gate.baseline.path));
  } catch (e) {
    return {
      toolName: 'justsearch-consumer-drift',
      toolVersion: '0.1.0',
      findings: [{
        ruleId: 'consumer-drift/malformed-slots',
        level: 'error',
        message: `${gate.baseline.path} failed to parse: ${e.message}. Fix the JSON.`,
        uri: gate.baseline.path,
      }],
      verdict: 'fail',
      ruleDescriptions: CONSUMER_DRIFT_RULE_DESCRIPTIONS,
    };
  }

  const declarations = gate.changesetsDir
    ? loadChangesets({
        repoRoot: sourceRoot,
        changesetsDir: gate.changesetsDir,
        baselineRef,
        allowedClassifications: CONSUMER_DRIFT_CLASSIFICATIONS,
        classificationField: 'classification',
        requireJustificationFor: CONSUMER_DRIFT_REQUIRE_JUSTIFICATION,
        fixtureMode,
      })
    : [];
  const classBySlot = indexClassificationsBySlot(declarations);
  const slotIds = new Set(slots.map((s) => s.id));

  const findings = [];
  let verdict = 'pass';
  const today = now ? new Date(now) : new Date();

  for (const slot of slots) {
    const declaredInExists = slot.declaredIn
      ? existsSync(resolve(sourceRoot, slot.declaredIn))
      : true;
    const count = countConsumers(sourceRoot, slot);
    const withinGrace = isWithinGrace(slot.grace, today);
    const classification = classBySlot.get(slot.id) ?? null;

    // Stale-slot (declaredIn vanished, not retracted) is the actionable finding
    // and SUPERSEDES the below-min verdict for the same slot (review L1 — they
    // used to double-fire). The slot-retraction case falls through to
    // verdictForSlot, which returns `retracted`/pass.
    if (slot.declaredIn && !declaredInExists && classification !== 'slot-retraction') {
      verdict = 'fail';
      findings.push({
        ruleId: 'consumer-drift/stale-slot',
        level: 'error',
        message:
          `${slot.id}: declaredIn '${slot.declaredIn}' no longer exists but no ` +
          `'slot-retraction' changeset was declared. Remove the slot from slots.json ` +
          `or declare the retraction.`,
        uri: gate.baseline.path,
      });
      continue;
    }

    const v = verdictForSlot({
      slotId: slot.id,
      count,
      expectedMin: slot.expectedMin ?? 1,
      withinGrace,
      classification,
      declaredInExists,
    });
    if (v.status === 'fail') verdict = 'fail';
    // Suppress healthy passes from SARIF (avoid noise); emit info + fail.
    if (v.ruleId !== 'consumer-drift/healthy') {
      findings.push({
        ruleId: v.ruleId,
        level: v.status === 'fail' ? 'error' : 'note',
        message: v.reason,
        uri: slot.declaredIn ?? gate.baseline.path,
      });
    }
  }

  // Baseline-tampering guard (mirrors class-size's silent-pin-bump): compare
  // live slots to the baseline-ref slots. Lowering a slot's expectedMin or
  // removing a slot in the same change silently weakens enforcement.
  const priorSlots = readPriorSlots({ fixtureMode, fixtureRoot, repoRoot, baselineRef, baselinePath: gate.baseline.path });
  if (priorSlots) {
    const liveById = new Map(slots.map((s) => [s.id, s]));
    for (const prior of priorSlots) {
      const live = liveById.get(prior.id);
      const classification = classBySlot.get(prior.id) ?? null;
      if (!live) {
        const declaredInExists = prior.declaredIn ? existsSync(resolve(sourceRoot, prior.declaredIn)) : false;
        const v = verdictForSlotRemoval({ slotId: prior.id, classification, declaredInExists });
        if (v.status === 'fail') verdict = 'fail';
        findings.push({ ruleId: v.ruleId, level: v.status === 'fail' ? 'error' : 'note', message: v.reason, uri: gate.baseline.path });
        continue;
      }
      const v = verdictForFloorChange({
        slotId: prior.id,
        priorMin: prior.expectedMin ?? 1,
        liveMin: live.expectedMin ?? 1,
        classification,
      });
      if (v.status === 'fail') verdict = 'fail';
      if (v.ruleId !== 'consumer-drift/floor-unchanged') {
        findings.push({ ruleId: v.ruleId, level: v.status === 'fail' ? 'error' : 'note', message: v.reason, uri: gate.baseline.path });
      }
    }
  }

  // Changeset-mismatch: a changeset names a slot not present in slots.json.
  for (const slotId of classBySlot.keys()) {
    if (!slotIds.has(slotId)) {
      findings.push({
        ruleId: 'consumer-drift/changeset-mismatch',
        level: 'warning',
        message: `A consumer-drift changeset names slot '${slotId}', which is not declared in slots.json (stale or typo).`,
        uri: gate.baseline.path,
      });
    }
  }

  // Tempdoc 550 thesis II — general read-view coverage. Beyond the explicit slots, auto-discover
  // every read-view custom element and require it to have a production mount, so a NEW unmounted
  // read-view is caught automatically (not only the hand-added ones). Pre-existing orphans are
  // grandfathered via discovery.readViews.knownUncovered.
  const readViewDiscovery = runReadViewDiscovery(
    sourceRoot,
    resolve(sourceRoot, gate.baseline.path),
    today,
  );
  for (const f of readViewDiscovery.findings) findings.push(f);
  if (readViewDiscovery.fail) verdict = 'fail';

  // Tempdoc 550 thesis II / F4 — tamper-guard the read-view discovery's knownUncovered list.
  // Silently adding a tag here would dodge the coverage gate (the same silent-weakening class the
  // slot floor-drop guard closes). A net-new entry vs the baseline fails unless a changeset names
  // it (slot == the tag, classified grace-extension / emergency-override).
  const priorKnownUncovered = readPriorKnownUncovered({
    fixtureMode,
    fixtureRoot,
    repoRoot,
    baselineRef,
    baselinePath: gate.baseline.path,
  });
  if (priorKnownUncovered) {
    const priorSet = new Set(priorKnownUncovered);
    const liveKnownUncovered = loadKnownUncovered(resolve(sourceRoot, gate.baseline.path));
    for (const tag of liveKnownUncovered) {
      if (!priorSet.has(tag) && !classBySlot.has(tag)) {
        verdict = 'fail';
        findings.push({
          ruleId: 'consumer-drift/silent-known-uncovered-add',
          level: 'error',
          message:
            `read-view '${tag}' was added to discovery.knownUncovered vs the baseline without a ` +
            `classifying changeset — silently exempting a read-view from the coverage gate. Mount ` +
            `it instead, or declare a grace-extension / emergency-override changeset naming '${tag}'.`,
          uri: gate.baseline.path,
        });
      }
    }
  }

  // §5.2 catalog-completeness closure (tempdoc 548): the slot coverage must be a
  // projection of the substrate universe, so a NEW substrate cannot silently
  // escape the gate. Skipped entirely when no `discovery` block is declared
  // (preserves the pre-548 fixture/self-test contract). Distinct from the 550
  // read-view discovery above (substrate-index universe vs read-view-tag universe);
  // both dimensions live under the one `discovery` block in slots.json.
  const substrateDiscovery = loadDiscovery(resolve(sourceRoot, gate.baseline.path));
  if (substrateDiscovery) {
    const discovered = discoverSubstrates(sourceRoot, substrateDiscovery);
    const covered = new Set(
      slots.map((s) => s.substrate).filter((x) => typeof x === 'string' && x.length > 0),
    );
    const knownUncovered = new Set(
      Array.isArray(substrateDiscovery.knownUncovered) ? substrateDiscovery.knownUncovered : [],
    );
    for (const substrateId of discovered) {
      const isCovered = covered.has(substrateId);
      if (isCovered) continue; // covered → silent pass
      const v = verdictForUncoveredSubstrate({
        substrateId,
        isCovered,
        isGrandfathered: knownUncovered.has(substrateId),
      });
      if (v.status === 'fail') verdict = 'fail';
      findings.push({
        ruleId: v.ruleId,
        level: v.status === 'fail' ? 'error' : 'note',
        message: v.reason,
        uri: gate.baseline.path,
      });
    }
    // Tightening hints: a knownUncovered entry now covered or no longer
    // discovered should be pruned so the grandfather list can't ossify.
    for (const substrateId of knownUncovered) {
      if (covered.has(substrateId)) {
        const v = verdictForStaleGrandfather({ substrateId, reason: 'now-covered' });
        findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: gate.baseline.path });
      } else if (!discovered.has(substrateId)) {
        const v = verdictForStaleGrandfather({ substrateId, reason: 'not-discovered' });
        findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: gate.baseline.path });
      }
    }
  }

  return {
    toolName: 'justsearch-consumer-drift',
    toolVersion: '0.1.0',
    findings,
    verdict,
    ruleDescriptions: CONSUMER_DRIFT_RULE_DESCRIPTIONS,
  };
}

/** Parse the slots config; returns [] if the file is absent (gate passes trivially). */
function loadSlots(slotsPath) {
  if (!existsSync(slotsPath)) return [];
  const parsed = JSON.parse(readFileSync(slotsPath, 'utf8'));
  return Array.isArray(parsed.slots) ? parsed.slots : [];
}

/**
 * §5.2 — read the optional `discovery` block from the slots config. Absent →
 * null (the completeness closure is skipped, preserving the pre-548 contract).
 * Reuses the slots file (the "substrate catalog" is the slots file with a
 * discovery binding), so there is no second source of truth.
 */
function loadDiscovery(slotsPath) {
  if (!existsSync(slotsPath)) return null;
  try {
    const parsed = JSON.parse(readFileSync(slotsPath, 'utf8'));
    return parsed && typeof parsed.discovery === 'object' ? parsed.discovery : null;
  } catch {
    return null; // malformed is already reported by loadSlots's caller
  }
}

/**
 * §5.2 — discover the substrate universe from `discovery.roots` +
 * `discovery.entryGlob` (e.g. every `<root>/<name>/index.ts`). Returns the set
 * of substrate ids (the `<name>` segment). Reuses the gate's own glob walker so
 * matching + Windows path normalization are identical to consumer counting.
 */
function discoverSubstrates(sourceRoot, discovery) {
  const ids = new Set();
  const roots = Array.isArray(discovery.roots) ? discovery.roots : [];
  const entryGlob = typeof discovery.entryGlob === 'string' ? discovery.entryGlob : '*/index.ts';
  for (const root of roots) {
    const rootRel = String(root).replaceAll('\\', '/').replace(/\/+$/, '');
    const includeGlob = `${rootRel}/${entryGlob}`;
    for (const { rel } of collectFiles(sourceRoot, [includeGlob], [])) {
      // rel = `${rootRel}/<name>/index.ts` → extract `<name>` (the segment
      // immediately after the root).
      const tail = rel.slice(rootRel.length + 1); // `<name>/index.ts`
      const name = tail.split('/')[0];
      if (name) ids.add(name);
    }
  }
  return ids;
}

/**
 * Read slots.json as it was at the baseline ref (real mode) or from a
 * `_baseline/` sibling (fixture mode). Returns the slot array, or null when no
 * prior state is available — in which case tampering detection is skipped
 * (graceful degradation; live-state enforcement still runs). Mirrors the
 * class-size gate's readPriorRatchet.
 */
function readPriorSlots(opts) {
  const parsed = readPriorConfig(opts);
  if (parsed === null) return null;
  return Array.isArray(parsed.slots) ? parsed.slots : [];
}

/**
 * The baseline slots.json's read-view `knownUncovered` list (tempdoc 550 F4). Returns null when the
 * baseline has no read-view discovery block at all — there is nothing to ratchet from yet (e.g. the
 * baseline predates the discovery feature), so the tamper-check is skipped rather than flagging the
 * initial grandfathering. Returns [] when discovery exists with an empty/absent knownUncovered.
 */
function readPriorKnownUncovered(opts) {
  const parsed = readPriorConfig(opts);
  if (parsed === null) return null;
  const readViews = parsed?.discovery?.readViews;
  if (!readViews) return null; // no prior discovery block — nothing to ratchet against
  return Array.isArray(readViews.knownUncovered) ? readViews.knownUncovered : [];
}

/** The live slots.json's read-view `knownUncovered` list. */
function loadKnownUncovered(slotsPath) {
  if (!existsSync(slotsPath)) return [];
  try {
    const ku = JSON.parse(readFileSync(slotsPath, 'utf8'))?.discovery?.readViews?.knownUncovered;
    return Array.isArray(ku) ? ku : [];
  } catch {
    return [];
  }
}

/** Read + parse slots.json at the baseline ref (real) or `_baseline/` (fixture); null if absent. */
function readPriorConfig({ fixtureMode, fixtureRoot, repoRoot, baselineRef, baselinePath }) {
  let content = null;
  if (fixtureMode) {
    if (!fixtureRoot) return null;
    const fixtureBaseline = resolve(fixtureRoot, '_baseline', baselinePath);
    if (!existsSync(fixtureBaseline)) return null;
    content = readFileSync(fixtureBaseline, 'utf8');
  } else {
    if (!baselineRef) return null;
    content = readFileAtRef(baselineRef, baselinePath, repoRoot);
    if (content === null) return null;
  }
  try {
    return JSON.parse(content);
  } catch {
    return null; // malformed prior state — skip tampering detection
  }
}

/** Count production files (include − exclude − declaredIn) referencing the slot symbol. */
function countConsumers(sourceRoot, slot) {
  if (!slot.symbol) return 0;
  const declaredInRel = slot.declaredIn ? slot.declaredIn.replaceAll('\\', '/') : null;
  const files = collectFiles(sourceRoot, slot.includeGlobs ?? [], slot.excludeGlobs ?? []);
  const symbolRe = new RegExp(`\\b${escapeRegex(slot.symbol)}\\b`);
  let count = 0;
  for (const { abs, rel } of files) {
    if (declaredInRel && rel === declaredInRel) continue; // the declaration site is not a consumer
    let content;
    try {
      content = readFileSync(abs, 'utf8');
    } catch {
      continue;
    }
    if (symbolRe.test(content)) count++;
  }
  return count;
}

/**
 * Date-bound grace only (first slice). tempdoc/commit-bound grace is a
 * follow-up. `untilDate` is treated as INCLUSIVE through the end of that UTC
 * day, so grace declared `untilDate: 2026-07-01` covers all of 2026-07-01.
 */
function isWithinGrace(grace, today) {
  if (!grace || !grace.untilDate) return false;
  const until = new Date(grace.untilDate);
  if (Number.isNaN(until.getTime())) return false;
  until.setUTCHours(23, 59, 59, 999);
  return today.getTime() <= until.getTime();
}

function escapeRegex(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Walk include globs and return {abs, rel} for files passing the exclude globs.
 * Self-contained manual walker + glob→regex (mirrors the class-size gate;
 * avoids a glob dependency). Supports `**`, `*`, `?`, `{a,b}`.
 */
function collectFiles(sourceRoot, includeGlobs, excludeGlobs) {
  const includeMatchers = includeGlobs.map(globToRegex);
  const excludeMatchers = excludeGlobs.map(globToRegex);
  const out = [];
  const walk = (dir) => {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      const full = join(dir, entry.name);
      const rel = relative(sourceRoot, full).replaceAll('\\', '/');
      if (excludeMatchers.some((re) => re.test(rel))) continue;
      if (entry.isDirectory()) walk(full);
      else if (entry.isFile() && includeMatchers.some((re) => re.test(rel))) out.push({ abs: full, rel });
    }
  };
  walk(sourceRoot);
  return out;
}

function globToRegex(glob) {
  let re = '';
  let i = 0;
  while (i < glob.length) {
    const ch = glob[i];
    if (ch === '*') {
      if (glob[i + 1] === '*') {
        re += '.*';
        i += 2;
        if (glob[i] === '/') i++;
        continue;
      }
      re += '[^/]*';
      i++;
      continue;
    }
    if (ch === '?') {
      re += '[^/]';
      i++;
      continue;
    }
    if (ch === '{') {
      const end = glob.indexOf('}', i);
      if (end !== -1) {
        re += `(?:${glob.slice(i + 1, end).split(',').join('|')})`;
        i = end + 1;
        continue;
      }
    }
    if ('.+^$()|[]\\'.includes(ch)) {
      re += '\\' + ch;
      i++;
      continue;
    }
    re += ch;
    i++;
  }
  return new RegExp('^' + re + '$');
}

/**
 * Tempdoc 550 thesis II — general read-view coverage. Auto-discovers every read-view custom
 * element (`customElements.define('jf-…'|'justsearch-…', …)`) under `discovery.readViews
 * .definitionGlobs`, and requires each to have a production MOUNT: a literal tag reference in some
 * other production file (template / createElement), OR a `registerViewFactory(...,'tag')`
 * registration (the chat-shape factory-mount path). A discovered read-view with no mount is the
 * plumbed-but-invisible failure — it FAILS the gate, unless it is grandfathered in
 * `knownUncovered` (pre-existing dated debt) or inside a declared grace window. This generalizes
 * the per-symbol slots: a NEW unmounted read-view is caught automatically.
 */
function runReadViewDiscovery(sourceRoot, slotsPath, today) {
  let cfg = null;
  try {
    const parsed = JSON.parse(readFileSync(slotsPath, 'utf8'));
    cfg = parsed.discovery && parsed.discovery.readViews ? parsed.discovery.readViews : null;
  } catch {
    return { findings: [], fail: false };
  }
  if (!cfg) return { findings: [], fail: false };

  const defFiles = collectFiles(sourceRoot, cfg.definitionGlobs ?? [], cfg.excludeGlobs ?? []);
  const mountFiles = collectFiles(
    sourceRoot,
    cfg.mountGlobs ?? cfg.definitionGlobs ?? [],
    cfg.mountExcludeGlobs ?? cfg.excludeGlobs ?? [],
  );
  const mountContents = mountFiles.map(({ abs, rel }) => {
    try {
      return [rel, readFileSync(abs, 'utf8')];
    } catch {
      return [rel, ''];
    }
  });

  // Discover tag -> defining file (first definition wins).
  const defRe = /customElements\.define\(\s*['"]((?:jf|justsearch)-[a-z0-9-]+)['"]/g;
  const defs = new Map();
  for (const [rel, content] of mountContents) {
    if (!defFiles.some((d) => d.rel === rel)) continue;
    let m;
    defRe.lastIndex = 0;
    while ((m = defRe.exec(content))) {
      if (!defs.has(m[1])) defs.set(m[1], rel);
    }
  }

  const known = new Set(cfg.knownUncovered ?? []);
  const withinGrace = isWithinGrace(cfg.grace, today);
  const findings = [];
  let fail = false;

  for (const [tag, defRel] of defs) {
    const tagRe = new RegExp(`\\b${escapeRegex(tag)}\\b`);
    let covered = false;
    for (const [rel, content] of mountContents) {
      if (rel !== defRel && tagRe.test(content)) {
        covered = true;
        break;
      }
      if (content.includes('registerViewFactory') && content.includes(`'${tag}'`)) {
        covered = true;
        break;
      }
    }
    if (covered) continue;

    if (known.has(tag)) {
      findings.push({
        ruleId: 'consumer-drift/read-view-grandfathered',
        level: 'note',
        message: `${tag}: pre-existing uncovered read-view (grandfathered in discovery.knownUncovered); mount it or remove it.`,
        uri: defRel,
      });
      continue;
    }
    if (withinGrace) {
      findings.push({
        ruleId: 'consumer-drift/uncovered-read-view',
        level: 'note',
        message: `${tag}: uncovered read-view, within discovery grace window.`,
        uri: defRel,
      });
      continue;
    }
    fail = true;
    findings.push({
      ruleId: 'consumer-drift/uncovered-read-view',
      level: 'error',
      message:
        `${tag} (defined in ${defRel}) has NO production mount: not referenced in any other ` +
        `production file, not registered via registerViewFactory, not grandfathered. A read-view ` +
        `nobody mounts is invisible (tempdoc 550 thesis II). Mount it, or add it to ` +
        `discovery.knownUncovered with a note.`,
      uri: defRel,
    });
  }
  return { findings, fail };
}
