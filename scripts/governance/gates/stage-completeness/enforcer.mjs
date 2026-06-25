/**
 * Stage-completeness enforcer — tempdoc 549 Phase F (principle 9).
 *
 * Mechanizes "a new pipeline stage must register a trace stage node AND a renderer, or the build
 * fails" as a cross-file structural invariant over the closed `SearchTrace.StageId` vocabulary.
 * The producer side ("every stage emits a node") is already compile-enforced by the default-free
 * `HeadStage` / `LegSet` switches; this gate adds the two halves the compiler can't see:
 *
 *   A. Renderer completeness (cross-language, net-new): the FE `STAGE_LABELS` map (the single
 *      generic search-explain renderer's stage→label table) must cover EXACTLY the Java StageId
 *      wireIds. A new stage with no label — or a phantom label — fails.
 *   B. Produced coverage: every StageId wireId must appear as a literal in ≥1 producer
 *      (SearchTraceProjector / the head HeadStage enum) — no declared-but-never-produced stage.
 *
 * Paths are configured in registry.v1.json (gate.config) so the self-test fixtures can point at
 * mock sources. The gate ships no ratchet baseline / changeset escape — it is a hard structural
 * invariant (a divergence is always a bug to fix, never a deliberate drift).
 */

import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

import { STAGE_COMPLETENESS_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import {
  verdictForRendererCompleteness,
  verdictForProducedCoverage,
  verdictForMissingSource,
} from './truth-table.mjs';
import { statusToSarifLevel } from '../../lib/truth-table-runner.mjs';

const TOOL = { toolName: 'justsearch-stage-completeness', toolVersion: '0.1.0' };

export async function enforceStageCompleteness(options) {
  const { repoRoot, gate, fixtureMode = false, fixtureRoot } = options;
  const root = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const cfg = gate.config ?? {};

  const stageIdPath = cfg.stageIdEnum;
  const rendererPath = cfg.rendererLabels;
  const producerPaths = Array.isArray(cfg.producers) ? cfg.producers : [];

  const findings = [];
  let verdict = 'pass';
  const push = (v, uri) => {
    if (v.status === 'fail') verdict = 'fail';
    // Suppress healthy passes from SARIF (noise); emit only fails + notes that matter.
    if (v.status === 'fail') {
      findings.push({ ruleId: v.ruleId, level: statusToSarifLevel(v.status), message: v.reason, uri });
    }
  };

  // --- Load the closed vocabulary (source of truth: the Java StageId enum). ---
  const stageIdAbs = stageIdPath ? resolve(root, stageIdPath) : null;
  if (!stageIdAbs || !existsSync(stageIdAbs)) {
    push(verdictForMissingSource({ label: 'the StageId enum', path: stageIdPath }), stageIdPath ?? '(unset)');
    return { ...TOOL, findings, verdict, ruleDescriptions: STAGE_COMPLETENESS_RULE_DESCRIPTIONS };
  }
  const stageIds = extractStageIdWireIds(readFileSync(stageIdAbs, 'utf8'));

  // --- Check A: FE renderer completeness (STAGE_LABELS keys vs the StageId set). ---
  const rendererAbs = rendererPath ? resolve(root, rendererPath) : null;
  if (!rendererAbs || !existsSync(rendererAbs)) {
    push(verdictForMissingSource({ label: 'the FE renderer (STAGE_LABELS)', path: rendererPath }), rendererPath ?? '(unset)');
  } else {
    const labelKeys = extractStageLabelKeys(readFileSync(rendererAbs, 'utf8'));
    const missing = [...stageIds].filter((id) => !labelKeys.has(id)).sort();
    const extra = [...labelKeys].filter((id) => !stageIds.has(id)).sort();
    push(verdictForRendererCompleteness({ missing, extra }), rendererPath);
  }

  // --- Check B: produced coverage (every StageId appears in some producer). ---
  let producerText = '';
  for (const p of producerPaths) {
    const abs = resolve(root, p);
    if (existsSync(abs)) producerText += '\n' + readFileSync(abs, 'utf8');
  }
  const orphans = [...stageIds].filter((id) => !producerText.includes(`"${id}"`)).sort();
  push(verdictForProducedCoverage({ orphans }), producerPaths[0] ?? stageIdPath);

  return { ...TOOL, findings, verdict, ruleDescriptions: STAGE_COMPLETENESS_RULE_DESCRIPTIONS };
}

/**
 * Extract the StageId wireIds from the Java enum. Scopes to the `enum StageId { … }` block so
 * the sibling `StageStatus` enum (no wireId args) and unrelated strings are not matched.
 */
function extractStageIdWireIds(src) {
  const start = src.indexOf('enum StageId');
  const block = start >= 0 ? sliceEnumBody(src, start) : src;
  const ids = new Set();
  // QUERY_UNDERSTANDING("query-understanding"),
  const re = /[A-Z][A-Z0-9_]*\("([a-z][a-z0-9-]*)"\)/g;
  let m;
  while ((m = re.exec(block)) !== null) ids.add(m[1]);
  return ids;
}

/** Extract the keys of the FE `STAGE_LABELS` object literal (quoted or bare identifiers). */
function extractStageLabelKeys(src) {
  const start = src.indexOf('STAGE_LABELS');
  if (start < 0) return new Set();
  const open = src.indexOf('{', start);
  const close = src.indexOf('};', open);
  const block = open >= 0 && close > open ? src.slice(open, close) : '';
  const keys = new Set();
  // 'query-understanding': 'Query understanding',  OR  fusion: 'Fusion',
  const re = /(?:^|,|\{)\s*(?:'([a-z][a-z0-9-]*)'|([a-z][a-z0-9-]*))\s*:/g;
  let m;
  while ((m = re.exec(block)) !== null) keys.add(m[1] ?? m[2]);
  return keys;
}

/** Return the substring from the first `{` after `fromIndex` to its matching `}` (brace-balanced). */
function sliceEnumBody(src, fromIndex) {
  const open = src.indexOf('{', fromIndex);
  if (open < 0) return src.slice(fromIndex);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(open, i + 1);
    }
  }
  return src.slice(open);
}
