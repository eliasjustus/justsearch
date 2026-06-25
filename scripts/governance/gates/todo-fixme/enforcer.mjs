/**
 * todo-fixme enforcer — tempdoc 530 §2.6.
 * Per-file count of TODO / FIXME / XXX inline comments; only-shrinks ratchet.
 *
 * Baseline file format (TSV, per-line): `<path> <count> <date>`.
 * Mirrors gradle/class-size-exceptions.txt shape; merge-conflict-friendly.
 */

import { existsSync, readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { resolve, relative, join } from 'node:path';

import { TODO_FIXME_CLASSIFICATIONS, aggregateTodoFixmeClassifications } from './classifications.mjs';
import { TODO_FIXME_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import { verdictForFile, verdictForBaselineShift } from './truth-table.mjs';
import { loadChangesets } from '../../lib/changeset-loader.mjs';
import { readFileAtRef } from '../../lib/git-utils.mjs';

const TODO_PATTERN = /\b(TODO|FIXME|XXX)\b/g;

function countTodos(content) {
  return (content.match(TODO_PATTERN) ?? []).length;
}

function globToRegex(glob) {
  let re = '';
  let i = 0;
  while (i < glob.length) {
    const ch = glob[i];
    if (ch === '*') {
      if (glob[i + 1] === '*') { re += '.*'; i += 2; if (glob[i] === '/') i++; continue; }
      re += '[^/]*'; i++; continue;
    }
    if (ch === '?') { re += '[^/]'; i++; continue; }
    if (ch === '{') { const end = glob.indexOf('}', i); if (end !== -1) { re += `(?:${glob.slice(i + 1, end).split(',').join('|')})`; i = end + 1; continue; } }
    if ('.+^$()|[]\\'.includes(ch)) { re += '\\' + ch; i++; continue; }
    re += ch; i++;
  }
  return new RegExp('^' + re + '$');
}

function collectFiles(root, sourceGlobs, excludeGlobs) {
  const includeMatchers = sourceGlobs.map(globToRegex);
  const excludeMatchers = (excludeGlobs ?? []).map(globToRegex);
  const out = [];
  const walk = dir => {
    let entries;
    try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const ent of entries) {
      const full = join(dir, ent.name);
      const rel = relative(root, full).replaceAll('\\', '/');
      if (excludeMatchers.some(re => re.test(rel))) continue;
      if (ent.isDirectory()) walk(full);
      else if (ent.isFile() && includeMatchers.some(re => re.test(rel))) out.push(full);
    }
  };
  walk(root);
  return out;
}

function parseBaseline(path) {
  if (!existsSync(path)) return new Map();
  const map = new Map();
  for (const raw of readFileSync(path, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const parts = line.split(/\s+/);
    if (parts.length < 2) continue;
    const count = Number(parts[1]);
    if (Number.isFinite(count)) map.set(parts[0], count);
  }
  return map;
}

function parseBaselineContent(content) {
  const map = new Map();
  for (const raw of (content ?? '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const parts = line.split(/\s+/);
    if (parts.length < 2) continue;
    const count = Number(parts[1]);
    if (Number.isFinite(count)) map.set(parts[0], count);
  }
  return map;
}

export async function enforceTodoFixme(options) {
  const { repoRoot, gate, baselineRef, rebalance = false, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const baselinePath = resolve(sourceRoot, gate.baseline.path);
  const baseline = parseBaseline(baselinePath);

  const declarations = gate.changesetsDir
    ? loadChangesets({
        repoRoot: sourceRoot,
        changesetsDir: gate.changesetsDir,
        baselineRef,
        allowedClassifications: TODO_FIXME_CLASSIFICATIONS,
        classificationField: 'classification',
        requireJustificationFor: new Set(['declared-growth', 'merge-import', 'emergency-override']),
        fixtureMode,
      })
    : [];
  const aggregated = aggregateTodoFixmeClassifications(declarations);

  const findings = [];
  let verdict = 'pass';
  const rebalanceWrites = new Map();

  const files = collectFiles(sourceRoot, gate.config?.sourceGlobs ?? [], gate.config?.excludeGlobs);
  const liveCounts = new Map();
  for (const file of files) {
    const rel = relative(sourceRoot, file).replaceAll('\\', '/');
    const count = countTodos(readFileSync(file, 'utf8'));
    if (count === 0 && !baseline.has(rel)) continue;
    liveCounts.set(rel, count);
    const pinned = baseline.get(rel) ?? 0;
    const cls = !aggregated.growthCovered ? 'silent-growth' : aggregated.classifications.find(c => ['declared-growth','merge-import','emergency-override'].includes(c)) ?? 'silent-growth';
    const v = verdictForFile({ path: rel, current: count, pinned, classification: cls });
    if (v.status === 'fail') {
      verdict = 'fail';
      findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: rel });
    } else if (v.status === 'info') {
      findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: rel });
      if (rebalance && count < pinned) rebalanceWrites.set(rel, count);
    }
  }

  // Baseline-shift detection.
  let priorBaseline = null;
  if (fixtureMode && fixtureRoot) {
    const p = resolve(fixtureRoot, '_baseline', gate.baseline.path);
    if (existsSync(p)) priorBaseline = parseBaselineContent(readFileSync(p, 'utf8'));
  } else if (baselineRef) {
    const content = readFileAtRef(baselineRef, gate.baseline.path, sourceRoot);
    if (content !== null) priorBaseline = parseBaselineContent(content);
  }
  if (priorBaseline) {
    const cls = !aggregated.growthCovered ? 'silent-growth' : (aggregated.classifications[0] ?? 'silent-growth');
    for (const [path, livePin] of baseline.entries()) {
      const priorPin = priorBaseline.get(path);
      if (priorPin === undefined) continue;
      const v = verdictForBaselineShift({ path, priorPin, livePin, classification: cls });
      if (v.status === 'fail') {
        verdict = 'fail';
        findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: gate.baseline.path });
      }
    }
  }

  if (rebalance && rebalanceWrites.size > 0) {
    const date = new Date().toISOString().slice(0, 10);
    const lines = [`# TODO/FIXME ratchet — tempdoc 530 §2.6. <path> <count> <date>`];
    for (const [path, count] of [...baseline.entries()].sort()) {
      const newCount = rebalanceWrites.has(path) ? rebalanceWrites.get(path) : count;
      if (newCount > 0) lines.push(`${path} ${newCount} ${date}`);
    }
    writeFileSync(baselinePath, lines.join('\n') + '\n');
  }

  return {
    toolName: 'justsearch-todo-fixme',
    toolVersion: '0.1.0',
    findings,
    verdict,
    ruleDescriptions: TODO_FIXME_RULE_DESCRIPTIONS,
    rebalanceWrites: [...rebalanceWrites.entries()].map(([path, c]) => ({ file: path, before: '', after: String(c) })),
  };
}
