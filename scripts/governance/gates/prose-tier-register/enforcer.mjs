/**
 * prose-tier-register enforcer — tempdoc 530 §Meta-loop.
 *
 * The "fourth gate" the tempdoc names as the closure piece. Enforces:
 *   1. `.claude/rules/tier-register.md` parses correctly (every row has a tier).
 *   2. Every row tagged with tier `gate` references an existing gate id in
 *      `governance/registry.v1.json`.
 *   3. Tier-changes vs the baseline ref require a declared changeset
 *      (`tier-change` / `new-rule-registered` / `rule-retired` /
 *      `emergency-override`).
 *
 * Together with the three concrete-discipline gates (class-size, npm-audit,
 * ui-bundle), this makes the kernel a self-describing discipline: every
 * prose rule chooses a tier, and the tier choice is itself gated.
 */

import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

import { PROSE_TIER_CLASSIFICATIONS, aggregateProseTierClassifications } from './classifications.mjs';
import { PROSE_TIER_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import {
  verdictForRowTier,
  verdictForGateReference,
  verdictForTierChange,
  verdictForRowRemoval,
  verdictForRuleAnchor,
  verdictForOrphanRegisterRow,
  verdictForSentence,
  verdictForHookReference,
  verdictForArchunitReference,
  verdictForMissingResolvesTo,
} from './truth-table.mjs';
import { loadChangesets } from '../../lib/changeset-loader.mjs';
import { readFileAtRef } from '../../lib/git-utils.mjs';
import {
  scanRuleAnchors,
  scanRuleSentences,
  extractRegisterSlugs,
  extractResolvesToMarkers,
  discoverRuleFiles,
} from './scanner.mjs';
import { classFileExists } from '../../lib/guard-resolver.mjs';

export async function enforceProseTierRegister(options) {
  const { repoRoot, gate, baselineRef, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const registerPath = resolve(sourceRoot, gate.baseline.path);
  const registryPath = resolve(sourceRoot, gate.config?.registryPath ?? 'governance/registry.v1.json');

  const findings = [];
  let verdict = 'pass';

  if (!existsSync(registerPath)) {
    return {
      toolName: 'justsearch-prose-tier-register',
      toolVersion: '0.1.0',
      findings: [
        {
          ruleId: 'prose-tier-register/register-missing',
          level: 'error',
          message: `tier register not found at ${registerPath}`,
          uri: gate.baseline.path,
        },
      ],
      verdict: 'fail',
      ruleDescriptions: PROSE_TIER_RULE_DESCRIPTIONS,
    };
  }

  const liveRows = parseRegister(readFileSync(registerPath, 'utf8'));

  // Load gate ids from registry for reference validation.
  let knownGateIds = [];
  if (existsSync(registryPath)) {
    try {
      const registry = JSON.parse(readFileSync(registryPath, 'utf8'));
      knownGateIds = (registry.gates ?? []).map(g => g.id);
    } catch {
      /* ignored — surfaces below as dangling references */
    }
  }

  // Changeset escape-hatch.
  const declarations = gate.changesetsDir
    ? loadChangesets({
        repoRoot: sourceRoot,
        changesetsDir: gate.changesetsDir,
        baselineRef,
        allowedClassifications: PROSE_TIER_CLASSIFICATIONS,
        classificationField: 'classification',
        requireJustificationFor: new Set(['tier-change', 'new-rule-registered', 'rule-retired', 'emergency-override']),
        fixtureMode,
      })
    : [];
  const aggregated = aggregateProseTierClassifications(declarations);

  // 1: per-row tier validity. Gate/hook/archunit reference validation moved
  // to the Resolves-to-markers path below (Pass-5) — the legacy `catchesVia`
  // backtick-extraction heuristic is superseded by structured markers.
  for (const row of liveRows) {
    const v = verdictForRowTier({ rowId: row.id, tier: row.tier });
    if (v.status === 'fail') {
      verdict = 'fail';
      findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: gate.baseline.path });
    }
  }

  // 3: tier-change detection vs prior register.
  const priorRegister = readPriorRegister({
    fixtureMode,
    fixtureRoot,
    repoRoot,
    baselineRef,
    registerFilePath: gate.baseline.path,
  });
  if (priorRegister) {
    const priorRows = parseRegister(priorRegister);
    const priorById = new Map(priorRows.map(r => [r.id, r]));
    const liveById = new Map(liveRows.map(r => [r.id, r]));

    for (const [id, live] of liveById) {
      const prior = priorById.get(id);
      if (!prior) continue;
      const v = verdictForTierChange({
        rowId: id,
        priorTier: prior.tier,
        liveTier: live.tier,
        changeCovered: aggregated.changeCovered,
      });
      if (v.status === 'fail') {
        verdict = 'fail';
        findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: gate.baseline.path });
      } else if (v.status === 'info' && live.tier !== prior.tier) {
        findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: gate.baseline.path });
      }
    }
    for (const [id] of priorById) {
      if (!liveById.has(id)) {
        const r = verdictForRowRemoval({ rowId: id, changeCovered: aggregated.changeCovered });
        if (r.status === 'fail') {
          verdict = 'fail';
          findings.push({ ruleId: r.ruleId, level: 'error', message: r.reason, uri: gate.baseline.path });
        } else {
          findings.push({ ruleId: r.ruleId, level: 'note', message: r.reason, uri: gate.baseline.path });
        }
      }
    }
  }

  // 4: Rule-file anchor ↔ register cross-validation (tempdoc 530 §Meta-loop
  // headline closure). Scan the load-bearing rule files for
  // <!-- rule:<slug> --> anchors; cross-reference with the register's Slug
  // column. Anchors without a row = untagged rules. Rows without an anchor
  // = orphan register entries.
  const ruleFiles = gate.config?.ruleFiles ?? discoverRuleFiles(sourceRoot);
  const liveAnchors = scanRuleAnchors({ sourceRoot, files: ruleFiles });
  const liveRegisterSlugs = extractRegisterSlugs(readFileSync(registerPath, 'utf8'));

  // To distinguish "new this PR" from "grandfathered", read the same artifacts
  // at the baseline ref. If unreachable, treat all anchors/rows as pre-existing.
  const priorAnchorSlugs = new Set();
  const priorRegisterSlugs = new Set();
  if (priorRegister) {
    extractRegisterSlugs(priorRegister).forEach(s => priorRegisterSlugs.add(s));
  }
  if (baselineRef) {
    for (const rel of ruleFiles) {
      const priorContent = readFileAtRef(baselineRef, rel, sourceRoot);
      if (!priorContent) continue;
      for (const m of priorContent.matchAll(/<!--\s*rule:([a-z][a-z0-9-]*)\s*-->/g)) {
        priorAnchorSlugs.add(m[1]);
      }
    }
  } else if (fixtureMode && fixtureRoot) {
    // Fixture mode supports a _baseline/ sibling tree.
    for (const rel of ruleFiles) {
      const priorPath = resolve(fixtureRoot, '_baseline', rel);
      if (!existsSync(priorPath)) continue;
      const priorContent = readFileSync(priorPath, 'utf8');
      for (const m of priorContent.matchAll(/<!--\s*rule:([a-z][a-z0-9-]*)\s*-->/g)) {
        priorAnchorSlugs.add(m[1]);
      }
    }
  }

  // For each live anchor, verdict.
  const liveAnchorSlugs = new Set(liveAnchors.map(a => a.slug));
  for (const a of liveAnchors) {
    const presentInRegister = liveRegisterSlugs.has(a.slug);
    const isNewVsBaseline =
      (baselineRef || (fixtureMode && fixtureRoot)) && !priorAnchorSlugs.has(a.slug);
    const v = verdictForRuleAnchor({
      slug: a.slug,
      source: a.source,
      presentInRegister,
      isNewVsBaseline,
      changeCovered: aggregated.changeCovered,
    });
    if (v.status === 'fail') {
      verdict = 'fail';
      findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: a.source });
    } else if (v.status === 'info' && !presentInRegister) {
      findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: a.source });
    }
  }

  // For each register slug not in any anchor, orphan verdict.
  for (const slug of liveRegisterSlugs) {
    if (liveAnchorSlugs.has(slug)) continue;
    const isNewVsBaseline =
      (baselineRef || (fixtureMode && fixtureRoot)) && !priorRegisterSlugs.has(slug);
    const v = verdictForOrphanRegisterRow({
      slug,
      isNewVsBaseline,
      changeCovered: aggregated.changeCovered,
    });
    if (v.status === 'fail') {
      verdict = 'fail';
      findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: gate.baseline.path });
    } else if (v.status === 'info') {
      findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: gate.baseline.path });
    }
  }

  // 5: Sentence-level "must/never/always" scan (tempdoc 530 §Meta-loop
  // literal closure — Pass-5). For each load-bearing sentence in scoped
  // rule files, check whether it falls inside an anchored section. New
  // unanchored sentences fail; pre-existing unanchored sentences emit info.
  const liveSentences = scanRuleSentences({ sourceRoot, files: ruleFiles });
  const baselineSentenceTexts = new Set();
  if (baselineRef) {
    for (const rel of ruleFiles) {
      const priorContent = readFileAtRef(baselineRef, rel, sourceRoot);
      if (!priorContent) continue;
      // Re-extract sentences from the baseline content directly (don't need
      // anchors here — only the sentence-text set, for diffing).
      const priorLines = priorContent.split(/\r?\n/);
      let priorInCode = false;
      for (const raw of priorLines) {
        if (/^\s*```/.test(raw)) {
          priorInCode = !priorInCode;
          continue;
        }
        if (priorInCode) continue;
        if (/\b(must|must not|never|always|do not|you must|cannot|may not)\b/i.test(raw)) {
          const cleaned = raw.replace(/<!--[^>]*-->/g, '').trim();
          if (cleaned.length > 0) baselineSentenceTexts.add(cleaned);
        }
      }
    }
  } else if (fixtureMode && fixtureRoot) {
    for (const rel of ruleFiles) {
      const priorPath = resolve(fixtureRoot, '_baseline', rel);
      if (!existsSync(priorPath)) continue;
      const priorContent = readFileSync(priorPath, 'utf8');
      const priorLines = priorContent.split(/\r?\n/);
      let priorInCode = false;
      for (const raw of priorLines) {
        if (/^\s*```/.test(raw)) {
          priorInCode = !priorInCode;
          continue;
        }
        if (priorInCode) continue;
        if (/\b(must|must not|never|always|do not|you must|cannot|may not)\b/i.test(raw)) {
          const cleaned = raw.replace(/<!--[^>]*-->/g, '').trim();
          if (cleaned.length > 0) baselineSentenceTexts.add(cleaned);
        }
      }
    }
  }
  for (const s of liveSentences) {
    const isNewVsBaseline =
      (baselineRef || (fixtureMode && fixtureRoot)) && !baselineSentenceTexts.has(s.sentence);
    const v = verdictForSentence({
      sentence: s.sentence,
      source: s.source,
      anchor: s.anchor,
      isNewVsBaseline,
      changeCovered: aggregated.changeCovered,
    });
    if (v.status === 'fail') {
      verdict = 'fail';
      findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: s.source });
    }
    // Suppress per-sentence pass and grandfathered info findings to avoid
    // SARIF noise — there are hundreds of these in the existing rule files.
    // The fail path is the load-bearing signal.
  }

  // 6: Resolves-to marker validation (gate / hook / archunit).
  const liveMarkers = extractResolvesToMarkers(readFileSync(registerPath, 'utf8'));
  const priorMarkers = priorRegister ? extractResolvesToMarkers(priorRegister) : [];
  const priorRowsById = new Map(priorMarkers.map(r => [r.rowId, r]));
  const hooksDir = resolve(sourceRoot, gate.config?.hooksDir ?? 'scripts/agent-analytics/hooks');
  const testRoots = gate.config?.testRoots ?? ['modules'];
  for (const row of liveMarkers) {
    const requiresMarker = ['gate', 'hook', 'hook-hint', 'archunit'].includes(row.tier);
    if (requiresMarker && row.markers.length === 0) {
      const isNew = !priorRowsById.has(row.rowId);
      const v = verdictForMissingResolvesTo({
        rowId: row.rowId,
        tier: row.tier,
        isNewVsBaseline: isNew,
      });
      if (v.status === 'fail') {
        verdict = 'fail';
        findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: gate.baseline.path });
      } else {
        findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: gate.baseline.path });
      }
      continue;
    }
    for (const marker of row.markers) {
      if (marker.kind === 'gate') {
        const v = verdictForGateReference({
          rowId: row.rowId,
          gateId: marker.token,
          knownGateIds,
        });
        if (v.status === 'fail') {
          verdict = 'fail';
          findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: gate.baseline.path });
        }
      } else if (marker.kind === 'hook') {
        const hookFile = resolve(hooksDir, marker.token);
        const exists = existsSync(hookFile);
        const v = verdictForHookReference({
          rowId: row.rowId,
          token: marker.token,
          hookFileExists: exists,
        });
        if (v.status === 'fail') {
          verdict = 'fail';
          findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: gate.baseline.path });
        }
      } else if (marker.kind === 'archunit') {
        const found = classFileExists(sourceRoot, testRoots, marker.token);
        const v = verdictForArchunitReference({
          rowId: row.rowId,
          token: marker.token,
          testClassFound: found,
        });
        if (v.status === 'fail') {
          verdict = 'fail';
          findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: gate.baseline.path });
        }
      }
    }
  }

  return {
    toolName: 'justsearch-prose-tier-register',
    toolVersion: '0.1.0',
    findings,
    verdict,
    ruleDescriptions: PROSE_TIER_RULE_DESCRIPTIONS,
  };
}

// archunitClassExists / findFileSuffix now live in ../../lib/guard-resolver.mjs as classFileExists
// (tempdoc 576 §3.1) — shared with execution-surface and the register-guard-resolution gate.

/**
 * Parse the register file. Tables have the shape:
 *
 *   | # | Slug | Rule | Tier | Catches violations via |
 *
 * Returns one record per numbered row across all tables.
 *
 * @returns {Array<{id: string, slug: string, rule: string, tier: string, catchesVia: string}>}
 */
function parseRegister(content) {
  const rows = [];
  const lines = content.split(/\r?\n/);
  let inTable = false;
  for (const raw of lines) {
    const line = raw.trim();
    if (!line.startsWith('|')) {
      inTable = false;
      continue;
    }
    const cells = line
      .split('|')
      .slice(1, -1)
      .map(c => c.trim());
    if (cells.length < 5) continue;
    if (cells.every(c => /^[-: ]+$/.test(c))) {
      inTable = true;
      continue;
    }
    if (!inTable) continue;
    const [num, slug, rule, tier, catchesVia] = cells;
    // Skip non-numeric # cells (header rows when separator hasn't been seen
    // yet — defensive only; the inTable flag should already filter).
    if (!/^\d+$/.test(num)) continue;
    rows.push({
      id: `rule-${num}`,
      slug: slug.replace(/`/g, '').trim(),
      rule,
      tier: tier.replace(/`/g, '').split(/\s/)[0],
      catchesVia,
    });
  }
  return rows;
}

function readPriorRegister({ fixtureMode, fixtureRoot, repoRoot, baselineRef, registerFilePath }) {
  if (fixtureMode) {
    if (!fixtureRoot) return null;
    const p = resolve(fixtureRoot, '_baseline', registerFilePath);
    if (!existsSync(p)) return null;
    return readFileSync(p, 'utf8');
  }
  if (!baselineRef) return null;
  return readFileAtRef(baselineRef, registerFilePath, repoRoot);
}
