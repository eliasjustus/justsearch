/**
 * ts-any enforcer — tempdoc 530 §2.5.
 * Per-file count of TS `any` casts (`as any`, `: any`, `<any>`); only-shrinks.
 *
 * Baseline file format: same TSV as todo-fixme (`<path> <count> <date>`).
 * The existing eslint.config.js has `@typescript-eslint/no-explicit-any: off`;
 * this gate provides the missing enforcement without re-enabling the rule
 * (which would block all current callsites).
 */

import { existsSync, readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { resolve, relative, join } from 'node:path';

import { loadChangesets } from '../../lib/changeset-loader.mjs';
import { readFileAtRef } from '../../lib/git-utils.mjs';

export const TS_ANY_CLASSIFICATIONS = new Set(['declared-growth', 'merge-import', 'emergency-override', 'monotonic-shrink']);
export const TS_ANY_RULE_DESCRIPTIONS = {
  'ts-any/within-baseline': 'TS `any` count at or below baseline',
  'ts-any/silent-growth': 'TS `any` count grew without a declared changeset',
  'ts-any/declared-growth': 'TS `any` count grew; classification covers it',
  'ts-any/merge-import': 'TS `any` growth via merge; classification supplied',
  'ts-any/emergency-override': 'Growth permitted via emergency-override',
  'ts-any/rebalance-available': 'TS `any` count shrunk; rebalance available',
  'ts-any/rebalanced': 'Baseline auto-updated',
};

const ANY_PATTERN = /\bas\s+any\b|:\s*any\b|<\s*any\s*>/g;

function countAny(content) { return (content.match(ANY_PATTERN) ?? []).length; }

function globToRegex(g) {
  let re = '', i = 0;
  while (i < g.length) {
    const c = g[i];
    if (c === '*') { if (g[i+1]==='*') { re += '.*'; i += 2; if (g[i]==='/') i++; continue; } re += '[^/]*'; i++; continue; }
    if (c === '?') { re += '[^/]'; i++; continue; }
    if (c === '{') { const end = g.indexOf('}', i); if (end !== -1) { re += `(?:${g.slice(i + 1, end).split(',').join('|')})`; i = end + 1; continue; } }
    if ('.+^$()|[]\\'.includes(c)) { re += '\\' + c; i++; continue; }
    re += c; i++;
  }
  return new RegExp('^' + re + '$');
}

function collectFiles(root, inc, exc) {
  const includeRes = inc.map(globToRegex), excludeRes = (exc ?? []).map(globToRegex);
  const out = [];
  const walk = dir => {
    let entries; try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const ent of entries) {
      const full = join(dir, ent.name);
      const rel = relative(root, full).replaceAll('\\','/');
      if (excludeRes.some(re => re.test(rel))) continue;
      if (ent.isDirectory()) walk(full);
      else if (ent.isFile() && includeRes.some(re => re.test(rel))) out.push(full);
    }
  };
  walk(root);
  return out;
}

function parseBaseline(text) {
  const m = new Map();
  for (const raw of (text ?? '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const parts = line.split(/\s+/);
    if (parts.length < 2) continue;
    const c = Number(parts[1]);
    if (Number.isFinite(c)) m.set(parts[0], c);
  }
  return m;
}

export async function enforceTsAny(options) {
  const { repoRoot, gate, baselineRef, rebalance=false, fixtureMode=false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const baselinePath = resolve(sourceRoot, gate.baseline.path);
  const baseline = existsSync(baselinePath) ? parseBaseline(readFileSync(baselinePath, 'utf8')) : new Map();

  const decls = gate.changesetsDir ? loadChangesets({
    repoRoot: sourceRoot, changesetsDir: gate.changesetsDir, baselineRef,
    allowedClassifications: TS_ANY_CLASSIFICATIONS, classificationField: 'classification',
    requireJustificationFor: new Set(['declared-growth','merge-import','emergency-override']),
    fixtureMode,
  }) : [];
  const growthCovered = decls.some(d => ['declared-growth','merge-import','emergency-override'].includes(d.classification));
  const coveringCls = decls.find(d => ['declared-growth','merge-import','emergency-override'].includes(d.classification))?.classification ?? 'declared-growth';

  const files = collectFiles(sourceRoot, gate.config?.sourceGlobs ?? [], gate.config?.excludeGlobs);
  const findings = [];
  let verdict = 'pass';
  const rebalanceWrites = new Map();

  for (const f of files) {
    const rel = relative(sourceRoot, f).replaceAll('\\','/');
    const c = countAny(readFileSync(f, 'utf8'));
    if (c === 0 && !baseline.has(rel)) continue;
    const pinned = baseline.get(rel) ?? 0;
    if (c > pinned) {
      if (!growthCovered) {
        verdict = 'fail';
        findings.push({ ruleId: 'ts-any/silent-growth', level: 'error', message: `${rel}: ${pinned} → ${c} any-casts without declared changeset`, uri: rel });
      } else {
        findings.push({ ruleId: `ts-any/${coveringCls}`, level: 'note', message: `${rel}: ${pinned} → ${c}; '${coveringCls}' covers`, uri: rel });
      }
    } else if (c < pinned) {
      findings.push({ ruleId: rebalance ? 'ts-any/rebalanced' : 'ts-any/rebalance-available', level: 'note', message: `${rel}: ${c} < pinned ${pinned}`, uri: rel });
      if (rebalance) rebalanceWrites.set(rel, c);
    }
  }

  if (rebalance && rebalanceWrites.size > 0) {
    const date = new Date().toISOString().slice(0,10);
    const out = [`# ts-any ratchet — tempdoc 530 §2.5. <path> <count> <date>`];
    for (const [p, c] of [...baseline.entries()].sort()) {
      const nc = rebalanceWrites.has(p) ? rebalanceWrites.get(p) : c;
      if (nc > 0) out.push(`${p} ${nc} ${date}`);
    }
    writeFileSync(baselinePath, out.join('\n') + '\n');
  }

  return {
    toolName: 'justsearch-ts-any', toolVersion: '0.1.0',
    findings, verdict,
    ruleDescriptions: TS_ANY_RULE_DESCRIPTIONS,
  };
}
