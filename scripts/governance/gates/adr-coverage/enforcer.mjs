/**
 * adr-coverage enforcer — tempdoc 530 §2.7.
 * Validates each ADR's frontmatter `Covers:` field. The Covers field is
 * net-new (Pass-6 audit found no existing path-coverage convention in
 * docs/decisions/); this gate gives it teeth.
 *
 * Verdicts:
 *   - ADR with Covers paths that all resolve              → pass
 *   - ADR with stale Covers path (no matching file)        → fail
 *   - ADR with no Covers field at all                      → info (grandfathered)
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { loadChangesets } from '../../lib/changeset-loader.mjs';
import { parseFrontmatter } from '../../lib/frontmatter.mjs';

export const ADR_COVERAGE_CLASSIFICATIONS = new Set([
  'covers-added', 'covers-updated', 'adr-superseded', 'emergency-override',
]);
export const ADR_COVERAGE_RULE_DESCRIPTIONS = {
  'adr-coverage/all-paths-resolve': 'ADR Covers paths all resolve to real files',
  'adr-coverage/stale-coverage': 'ADR Covers path does not match any existing file',
  'adr-coverage/no-covers-field': 'ADR has no Covers field (informational; add Covers: glob)',
};

function globToRegex(g) {
  let re = '', i = 0;
  while (i < g.length) {
    const c = g[i];
    if (c === '*') { if (g[i+1]==='*') { re += '.*'; i+=2; if (g[i]==='/') i++; continue; } re += '[^/]*'; i++; continue; }
    if (c === '?') { re += '[^/]'; i++; continue; }
    if ('.+^$()|[]\\'.includes(c)) { re += '\\' + c; i++; continue; }
    re += c; i++;
  }
  return new RegExp('^' + re + '$');
}

function listAllRepoFiles(root, out=[]) {
  let entries; try { entries = readdirSync(root, { withFileTypes: true }); } catch { return out; }
  for (const e of entries) {
    if (e.name === '.git' || e.name === 'node_modules' || e.name === 'build' || e.name === 'tmp' || e.name === '.gradle') continue;
    const full = join(root, e.name);
    if (e.isDirectory()) listAllRepoFiles(full, out);
    else if (e.isFile()) out.push(full);
  }
  return out;
}

export async function enforceAdrCoverage(options) {
  const { repoRoot, gate, baselineRef, fixtureMode=false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const adrDir = resolve(sourceRoot, gate.config?.adrDir ?? 'docs/decisions');
  const findings = [];
  let verdict = 'pass';

  if (!existsSync(adrDir)) {
    return { toolName: 'justsearch-adr-coverage', toolVersion: '0.1.0', findings, verdict, ruleDescriptions: ADR_COVERAGE_RULE_DESCRIPTIONS };
  }

  // Listing all repo files for path-validation is expensive; lazy-load on demand.
  let repoFiles = null;
  const repoFilesRel = () => {
    if (repoFiles !== null) return repoFiles;
    const abs = listAllRepoFiles(sourceRoot);
    repoFiles = abs.map(p => p.replace(sourceRoot, '').replaceAll('\\', '/').replace(/^\//, ''));
    return repoFiles;
  };

  // Changeset escape-hatch.
  if (gate.changesetsDir) {
    loadChangesets({
      repoRoot: sourceRoot, changesetsDir: gate.changesetsDir, baselineRef,
      allowedClassifications: ADR_COVERAGE_CLASSIFICATIONS, classificationField: 'classification',
      requireJustificationFor: new Set(['covers-added','covers-updated','adr-superseded','emergency-override']),
      fixtureMode,
    });
  }

  const adrEntries = readdirSync(adrDir).filter(n => n.endsWith('.md'));
  for (const adr of adrEntries) {
    const path = resolve(adrDir, adr);
    const stat = statSync(path);
    if (!stat.isFile()) continue;
    const content = readFileSync(path, 'utf8');
    const parsed = parseFrontmatter(content);
    if (!parsed) {
      findings.push({ ruleId: 'adr-coverage/no-covers-field', level: 'note', message: `${adr}: no frontmatter`, uri: `docs/decisions/${adr}` });
      continue;
    }
    const covers = parsed.frontmatter.covers ?? parsed.frontmatter.Covers;
    if (!covers || covers.trim() === '') {
      findings.push({ ruleId: 'adr-coverage/no-covers-field', level: 'note', message: `${adr}: missing Covers field (informational; add Covers: glob list)`, uri: `docs/decisions/${adr}` });
      continue;
    }
    // Covers: comma- or whitespace-separated glob patterns.
    const globs = covers.split(/[,\s]+/).map(s => s.trim()).filter(Boolean);
    const files = repoFilesRel();
    for (const g of globs) {
      const re = globToRegex(g);
      if (!files.some(p => re.test(p))) {
        verdict = 'fail';
        findings.push({ ruleId: 'adr-coverage/stale-coverage', level: 'error', message: `${adr}: Covers glob '${g}' matches no file`, uri: `docs/decisions/${adr}` });
      }
    }
  }

  return { toolName: 'justsearch-adr-coverage', toolVersion: '0.1.0', findings, verdict, ruleDescriptions: ADR_COVERAGE_RULE_DESCRIPTIONS };
}
