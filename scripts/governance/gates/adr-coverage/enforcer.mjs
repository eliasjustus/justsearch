/**
 * adr-coverage enforcer — tempdoc 530 §2.7 (Covers: path validation) extended by
 * tempdoc 884 (ADR premise probes + reassess cadence).
 *
 * Verdicts:
 *   - ADR with Covers paths that all resolve              → pass
 *   - ADR with stale Covers path (no matching file)       → fail
 *   - ADR with no Covers field at all                     → info (grandfathered)
 *   - ADR whose premise probe no longer holds             → fail   (probe-failed)
 *   - live ADR with no probe and no stated reason         → warn   (no-probe)
 *   - ADR not re-read within the review window            → warn   (review-stale)
 *
 * Frontmatter is parsed with `gray-matter` (tempdoc 884 §R3): `probes:` is a YAML list,
 * which the repo's per-line `lib/frontmatter.mjs` parser cannot represent. That shared
 * parser is deliberately left alone — gray-matter returns typed values (numbers, Dates)
 * and three unrelated gates depend on its string-valued output.
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve, join } from 'node:path';
import matter from 'gray-matter';
import { loadChangesets } from '../../lib/changeset-loader.mjs';
import { evaluateProbe, loadProbeRegister } from './probes.mjs';
import {
  verdictForProbe,
  verdictForProbeCoverage,
  verdictForReviewStale,
} from './truth-table.mjs';

export const ADR_COVERAGE_CLASSIFICATIONS = new Set([
  'covers-added', 'covers-updated', 'adr-superseded', 'emergency-override',
  'probe-added', 'probe-updated', 'probe-retired',
]);
export const ADR_COVERAGE_RULE_DESCRIPTIONS = {
  'adr-coverage/all-paths-resolve': 'ADR Covers paths all resolve to real files',
  'adr-coverage/stale-coverage': 'ADR Covers path does not match any existing file',
  'adr-coverage/no-covers-field': 'ADR has no Covers field (informational; add Covers: glob)',
  'adr-coverage/probe-failed':
    "An ADR's declared premise probe no longer holds: the code has drifted away from the "
    + 'decision. Re-examine and amend the ADR (docs/decisions/README.md § How to re-examine '
    + 'an ADR); do not edit the probe until it passes.',
  'adr-coverage/no-probe':
    'A live (accepted*/stable*) ADR names no premise probe and states no reason. Add '
    + "'probes: [<id>]' pointing at governance/adr-probes.v1.json, or 'probes: none - <reason>'.",
  'adr-coverage/review-stale':
    "An ADR's 'last_reviewed' date is older than the review window (or missing). Nothing is "
    + 'scheduled to re-read decisions; this warning is the schedule.',
};

/** Status prefixes that make an ADR live enough to owe a probe (tempdoc 884 R1). */
const LIVE_STATUS_PREFIXES = ['accepted', 'stable'];
const DEFAULT_REVIEW_STALE_DAYS = 183;
/** The decision index, not a decision. */
const NON_ADR_FILES = new Set(['README.md']);

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

/** gray-matter yields a Date for an unquoted YAML date; normalize to YYYY-MM-DD. */
function normalizeDate(value) {
  if (value === null || value === undefined || value === '') return null;
  if (value instanceof Date) return value.toISOString().slice(0, 10);
  return String(value).trim();
}

function daysSince(isoDate, nowMs) {
  const t = Date.parse(isoDate);
  if (Number.isNaN(t)) return null;
  return Math.floor((nowMs - t) / 86_400_000);
}

function isLiveStatus(status) {
  const s = String(status ?? '').trim().toLowerCase();
  // A missing status is not an exemption: an ADR that declares nothing is exactly the kind
  // that goes unexamined, so it owes a probe or a stated reason like any live one.
  if (s === '') return true;
  return LIVE_STATUS_PREFIXES.some((p) => s.startsWith(p));
}

/**
 * `probes:` is either a YAML list of register ids, or the string `none - <reason>`.
 * Returns `{ids, statedReason}`.
 */
function readProbesField(value) {
  if (value === null || value === undefined) return { ids: [], statedReason: null };
  if (Array.isArray(value)) return { ids: value.map((v) => String(v).trim()).filter(Boolean), statedReason: null };
  const s = String(value).trim();
  if (s === '') return { ids: [], statedReason: null };
  if (s.toLowerCase().startsWith('none')) {
    // A bare `probes: none` states nothing; it must still raise no-probe.
    const reason = s.slice(4).replace(/^\s*-\s*/, '').trim();
    return { ids: [], statedReason: reason || null };
  }
  return { ids: s.split(/[,\s]+/).map((v) => v.trim()).filter(Boolean), statedReason: null };
}

export async function enforceAdrCoverage(options) {
  const { repoRoot, gate, baselineRef, fixtureMode=false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const adrDir = resolve(sourceRoot, gate.config?.adrDir ?? 'docs/decisions');
  const adrDirRel = (gate.config?.adrDir ?? 'docs/decisions').replaceAll('\\', '/');
  const reviewStaleDays = gate.config?.reviewStaleDays ?? DEFAULT_REVIEW_STALE_DAYS;
  const nowMs = Date.now();
  const findings = [];
  let verdict = 'pass';

  if (!existsSync(adrDir)) {
    return { toolName: 'justsearch-adr-coverage', toolVersion: '0.2.0', findings, verdict, ruleDescriptions: ADR_COVERAGE_RULE_DESCRIPTIONS };
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
      requireJustificationFor: ADR_COVERAGE_CLASSIFICATIONS,
      fixtureMode,
    });
  }

  const register = loadProbeRegister(sourceRoot, gate.config?.probeRegister);
  const claimedProbeIds = new Set();

  if (register?.parseError) {
    verdict = 'fail';
    findings.push({
      ruleId: 'adr-coverage/probe-failed',
      level: 'error',
      message: `${register.registerPath} is not parseable JSON (${register.parseError}); no premise probe could run`,
      uri: register.registerPath,
    });
  }

  const emit = (v, uri) => {
    if (v.status === 'pass') return;
    const level = v.status === 'fail' ? 'error' : 'warning';
    if (v.status === 'fail') verdict = 'fail';
    findings.push({ ruleId: v.ruleId, level, message: v.reason, uri });
  };

  const adrEntries = readdirSync(adrDir).filter(n => n.endsWith('.md'));
  for (const adr of adrEntries) {
    const path = resolve(adrDir, adr);
    const stat = statSync(path);
    if (!stat.isFile()) continue;
    const content = readFileSync(path, 'utf8');
    const uri = `${adrDirRel}/${adr}`;
    let data = {};
    let parsed = true;
    let hadFrontmatter = true;
    if (!content.startsWith('---')) {
      hadFrontmatter = false;
      // No frontmatter at all is not an exemption: the cadence and probe rules below still
      // apply, and a decision with no status is exactly the kind that goes unexamined.
      findings.push({ ruleId: 'adr-coverage/no-covers-field', level: 'note', message: `${adr}: no frontmatter`, uri });
    } else {
      try {
        data = matter(content).data ?? {};
      } catch (e) {
        // Unparseable frontmatter is a gate failure, not a crashed kernel run.
        verdict = 'fail';
        findings.push({ ruleId: 'adr-coverage/probe-failed', level: 'error', message: `${adr}: frontmatter is not parseable YAML (${e.message})`, uri });
        parsed = false;
      }
    }
    if (!parsed) continue;

    // --- Covers: path validation (tempdoc 530 §2.7; behaviour unchanged) ---
    const coversRaw = data.covers ?? data.Covers;
    const covers = Array.isArray(coversRaw) ? coversRaw.join(' ') : (coversRaw == null ? '' : String(coversRaw));
    if (covers.trim() === '') {
      if (hadFrontmatter) {
        findings.push({ ruleId: 'adr-coverage/no-covers-field', level: 'note', message: `${adr}: missing Covers field (informational; add Covers: glob list)`, uri });
      }
    } else {
      const globs = covers.split(/[,\s]+/).map(s => s.trim()).filter(Boolean);
      const files = repoFilesRel();
      for (const g of globs) {
        const re = globToRegex(g);
        if (!files.some(p => re.test(p))) {
          verdict = 'fail';
          findings.push({ ruleId: 'adr-coverage/stale-coverage', level: 'error', message: `${adr}: Covers glob '${g}' matches no file`, uri });
        }
      }
    }

    if (NON_ADR_FILES.has(adr)) continue;

    // --- Premise probes (tempdoc 884 §1/§2) ---
    const { ids, statedReason } = readProbesField(data.probes);
    for (const id of ids) {
      claimedProbeIds.add(id);
      const probe = register?.byId?.get(id);
      if (!probe) {
        emit(verdictForProbe({
          adr, probeId: id,
          premise: '(unknown — probe id not in the register)',
          ok: false,
          detail: `'${id}' is not an entry in ${register?.registerPath ?? 'governance/adr-probes.v1.json'}`,
        }), uri);
        continue;
      }
      const { ok, detail } = evaluateProbe(probe, sourceRoot);
      emit(verdictForProbe({ adr, probeId: id, premise: probe.premise, ok, detail }), uri);
    }

    if (isLiveStatus(data.status)) {
      emit(verdictForProbeCoverage({
        adr, status: data.status, probeCount: ids.length, statedReason,
      }), uri);
    }

    // --- Reassess cadence (tempdoc 884 §4; warn-only for the first cycle) ---
    const lastReviewed = normalizeDate(data.last_reviewed);
    const ageDays = lastReviewed ? daysSince(lastReviewed, nowMs) : null;
    emit(verdictForReviewStale({
      adr,
      lastReviewed: lastReviewed && ageDays !== null ? lastReviewed : null,
      ageDays: ageDays ?? Number.MAX_SAFE_INTEGER,
      thresholdDays: reviewStaleDays,
    }), uri);
  }

  // A register entry no ADR declares would silently never run. Surface it as drift.
  for (const probe of register?.probes ?? []) {
    if (claimedProbeIds.has(probe.id)) continue;
    emit(verdictForProbe({
      adr: `ADR-${probe.adr}`,
      probeId: probe.id,
      premise: probe.premise,
      ok: false,
      detail: `the register names ADR-${probe.adr}, but that ADR's frontmatter does not list '${probe.id}' in 'probes:'`,
    }), `${adrDirRel}`);
  }

  return { toolName: 'justsearch-adr-coverage', toolVersion: '0.2.0', findings, verdict, ruleDescriptions: ADR_COVERAGE_RULE_DESCRIPTIONS };
}
