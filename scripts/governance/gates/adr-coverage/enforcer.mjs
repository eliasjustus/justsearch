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
  NON_ADR_FILES,
  daysSince,
  loadReviewStaleDays,
  normalizeDate,
} from './review-window.mjs';
import {
  verdictForProbe,
  verdictForProbeCoverage,
  verdictForReviewStale,
  verdictForRiskInstrument,
  verdictForRiskInstrumentCoverage,
  verdictForRiskRegister,
  verdictForRiskRegisterPresence,
} from './truth-table.mjs';

export const ADR_COVERAGE_CLASSIFICATIONS = new Set([
  'covers-added', 'covers-updated', 'adr-superseded', 'emergency-override',
  'probe-added', 'probe-updated', 'probe-retired',
  'risk-added', 'risk-instrument-updated',
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
  'adr-coverage/risk-instrument-ok':
    "A risk row's declared instrument reference resolves against the tree.",
  'adr-coverage/risk-instrument-unresolved':
    "A risk row in docs/reference/architectural-risks.md names an instrument that no longer "
    + 'resolves. Build the instrument or amend the risk row — never delete the reference. A row '
    + 'whose instrument stops resolving is a lane that closed without building what it promised.',
  'adr-coverage/risk-no-instrument':
    "A risk row names no instrument (or a bare 'none'). Add one reference in the grammar "
    + 'documented in docs/reference/architectural-risks.md § Instrument grammar, or '
    + "'none - <reason>'; a risk with nothing to check is a note nobody reads.",
  'adr-coverage/risk-register-ok':
    'The architectural risk register is present, parsed, and every row was evaluated.',
  'adr-coverage/risk-register-missing':
    'The risk register named by the adr-coverage gate config does not exist. It is the '
    + 'instrument-per-risk mechanism, so deleting it disables every instrument row at once — '
    + 'the exact 2026-03 failure (deleted, unnoticed for six months). Restore the file; do not '
    + 'drop the config key that names it.',
  'adr-coverage/risk-register-malformed':
    'docs/reference/architectural-risks.md exists but is structurally broken (no parseable '
    + '`## RISK-NNN:` section, or a reused id). It fails, because it would silently disable '
    + 'every instrument check in it.',
};

/** Status prefixes that make an ADR live enough to owe a probe (tempdoc 884 R1). */
const LIVE_STATUS_PREFIXES = ['accepted', 'stable'];

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

// ---------------------------------------------------------------------------------------
// Architectural risk register (tempdoc 884 PART D)
//
// The 269 review's register was deleted a week after it was written, so its triggers fired
// unobserved for six months. The register is back with one new load-bearing field per row —
// `**Instrument:**`, exactly one machine-checkable reference — and this is what checks it.
// It lives inside `adr-coverage` rather than in a second gate on purpose (884 design
// decision 1): a second register of "things that describe decisions" is the drift shape.
// ---------------------------------------------------------------------------------------

const DEFAULT_RISK_REGISTER = 'docs/reference/architectural-risks.md';
const RISK_HEADING = /^##\s+(RISK-\d+)\s*:\s*(.+?)\s*$/;
const INSTRUMENT_FIELD = /^\*\*Instrument:\*\*\s*(.+?)\s*$/m;
/** Extensions worth reading when resolving a `metric:` id under modules/**\/src/main. */
const METRIC_SCAN_EXTENSIONS = ['.java', '.kt', '.kts', '.json', '.ts', '.properties'];

function stripBackticks(s) {
  const t = s.trim();
  const m = /^`(.*)`$/s.exec(t);
  return (m ? m[1] : t).trim();
}

/**
 * Split the register into `## RISK-NNN: <title>` sections.
 *
 * Any other `##` heading closes the current section, so the preamble, the grammar table and
 * the Resolved index are skipped without needing to know their names — and a Resolved row is
 * NOT skipped, because a resolved risk's instrument is what would notice the resolution coming
 * undone (RISK-008's argv test is exactly that).
 */
function parseRiskRegister(text) {
  const risks = [];
  let current = null;
  for (const line of text.split(/\r?\n/)) {
    const heading = RISK_HEADING.exec(line);
    if (heading) {
      current = { id: heading[1], title: heading[2], body: [] };
      risks.push(current);
      continue;
    }
    if (/^##\s/.test(line)) { current = null; continue; }
    if (current) current.body.push(line);
  }
  for (const risk of risks) {
    const m = INSTRUMENT_FIELD.exec(risk.body.join('\n'));
    risk.instrument = m ? stripBackticks(m[1]) : null;
  }
  return risks;
}

/**
 * "Structurally broken" is drawn deliberately narrowly, at the two conditions that make the
 * instrument checks silently vacuous rather than merely imperfect:
 *
 *  1. zero parseable `## RISK-NNN:` sections — the file is present, so it is not the
 *     "nothing restored yet" case that absence covers, yet nothing in it would ever be
 *     checked. A renamed heading level or a broken split lands here, which is the point.
 *  2. a reused id — `RISK-NNN` is "sequential, never reused" by the register's own contract,
 *     and it is the handle every tempdoc and ADR cites. Two rows answering to one id makes
 *     every citation ambiguous and hides one row's findings behind the other's.
 *
 * Everything else (a missing Owner-tempdoc line, prose drift, ordering) is a review concern,
 * not a mechanical one: failing on it would push authors toward keeping the file minimal.
 */
function riskRegisterProblem(risks) {
  if (risks.length === 0) {
    return 'no `## RISK-NNN: <title>` section was found';
  }
  const seen = new Set();
  const duplicates = [];
  for (const r of risks) {
    if (seen.has(r.id)) duplicates.push(r.id);
    seen.add(r.id);
  }
  if (duplicates.length > 0) {
    return `id(s) ${[...new Set(duplicates)].join(', ')} appear more than once (ids are sequential and never reused)`;
  }
  return null;
}

function resolveGateInstrument(root, id) {
  const rel = 'governance/registry.v1.json';
  const abs = resolve(root, rel);
  if (!existsSync(abs)) return { ok: false, detail: `gate registry '${rel}' not found` };
  let doc;
  try { doc = JSON.parse(readFileSync(abs, 'utf8')); } catch (e) {
    return { ok: false, detail: `gate registry '${rel}' is not parseable JSON: ${e.message}` };
  }
  const ids = (doc.gates ?? []).map((g) => g.id);
  return ids.includes(id)
    ? { ok: true, detail: `gate '${id}' is registered in ${rel}` }
    : { ok: false, detail: `no gate with id '${id}' in ${rel}` };
}

function resolveTestInstrument(root, ref) {
  const hash = ref.lastIndexOf('#');
  if (hash < 0) {
    return { ok: false, detail: "the test: form is 'test:<repo-relative path>#<member>'; no '#' found" };
  }
  const file = ref.slice(0, hash).trim();
  const member = ref.slice(hash + 1).trim();
  if (!file || !member) {
    return { ok: false, detail: "the test: form is 'test:<repo-relative path>#<member>'; path or member is empty" };
  }
  const abs = resolve(root, file);
  if (!existsSync(abs)) return { ok: false, detail: `'${file}' does not exist` };
  let content;
  try { content = readFileSync(abs, 'utf8'); } catch (e) {
    return { ok: false, detail: `'${file}' could not be read: ${e.message}` };
  }
  return content.includes(member)
    ? { ok: true, detail: `'${file}' still declares '${member}'` }
    : { ok: false, detail: `'${file}' exists but no longer declares '${member}'` };
}

/** Lazily collected `modules/<m>/src/main/**` files, cached per enforcer run. */
function makeMetricScanner(root) {
  let files = null;
  return (id) => {
    if (files === null) {
      files = [];
      const modulesDir = resolve(root, 'modules');
      if (existsSync(modulesDir)) {
        for (const e of readdirSync(modulesDir, { withFileTypes: true })) {
          if (!e.isDirectory()) continue;
          const srcMain = join(modulesDir, e.name, 'src', 'main');
          if (!existsSync(srcMain)) continue;
          for (const f of listAllRepoFiles(srcMain)) {
            if (METRIC_SCAN_EXTENSIONS.some((ext) => f.endsWith(ext))) files.push(f);
          }
        }
      }
    }
    if (files.length === 0) {
      return { ok: false, detail: `no files under modules/**/src/main to scan for '${id}'` };
    }
    for (const f of files) {
      let content;
      try { content = readFileSync(f, 'utf8'); } catch { continue; }
      if (content.includes(id)) {
        const rel = f.replace(root, '').replaceAll('\\', '/').replace(/^\//, '');
        return { ok: true, detail: `'${id}' appears in ${rel}` };
      }
    }
    return {
      ok: false,
      detail: `'${id}' appears in no file under modules/**/src/main (${files.length} scanned) — `
        + `if the metric is not built yet, say so with the tempdoc: form instead of naming it as if it were`,
    };
  };
}

function resolveTempdocInstrument(root, ref) {
  const hash = ref.indexOf('#');
  if (hash < 0) {
    return { ok: false, detail: "the tempdoc: form is 'tempdoc:<NNN>#<heading substring>'; no '#' found" };
  }
  const number = ref.slice(0, hash).trim();
  const needle = ref.slice(hash + 1).trim();
  if (!/^\d+$/.test(number) || needle === '') {
    return { ok: false, detail: "the tempdoc: form is 'tempdoc:<NNN>#<heading substring>'" };
  }
  const dir = resolve(root, 'docs/tempdocs');
  let entries = [];
  try { entries = readdirSync(dir); } catch {
    return { ok: false, detail: `docs/tempdocs/ does not exist, so tempdoc ${number} cannot be resolved` };
  }
  const match = entries.find((n) => n.startsWith(`${number}-`) && n.endsWith('.md'));
  if (!match) {
    return {
      ok: false,
      detail: `no docs/tempdocs/${number}-*.md — do not invent a tempdoc number; use 'none - <reason>' until the lane files one`,
    };
  }
  let content;
  try { content = readFileSync(join(dir, match), 'utf8'); } catch (e) {
    return { ok: false, detail: `docs/tempdocs/${match} could not be read: ${e.message}` };
  }
  const wanted = needle.toLowerCase();
  for (const line of content.split(/\r?\n/)) {
    const h = /^#{1,6}\s+(.*)$/.exec(line);
    if (h && h[1].toLowerCase().includes(wanted)) {
      return { ok: true, detail: `docs/tempdocs/${match} has heading '${h[1].trim()}'` };
    }
  }
  return {
    ok: false,
    detail: `docs/tempdocs/${match} has no heading containing '${needle}' — the owning section was renamed or removed`,
  };
}

/**
 * Resolve one `**Instrument:**` reference.
 *
 * Returns `{form}` plus either `{ok, detail}` for a checkable form, or `{statedReason}` for
 * the `none` form (which always "resolves" but is reported as a warning by its own rule).
 */
function resolveRiskInstrument(ref, root, scanMetric) {
  if (/^none\b/i.test(ref)) {
    const reason = ref.slice(4).replace(/^\s*[-–—:]\s*/, '').trim();
    return { form: 'none', statedReason: reason || null };
  }
  if (ref.startsWith('gate:')) return { form: 'gate', ...resolveGateInstrument(root, ref.slice(5).trim()) };
  if (ref.startsWith('check:')) {
    const rel = ref.slice(6).trim();
    if (!rel.startsWith('scripts/ci/') || !rel.endsWith('.mjs')) {
      return { form: 'check', ok: false, detail: `the check: form is 'check:scripts/ci/<name>.mjs'; got '${rel}'` };
    }
    return existsSync(resolve(root, rel))
      ? { form: 'check', ok: true, detail: `'${rel}' exists` }
      : { form: 'check', ok: false, detail: `'${rel}' does not exist` };
  }
  if (ref.startsWith('test:')) return { form: 'test', ...resolveTestInstrument(root, ref.slice(5).trim()) };
  if (ref.startsWith('metric:')) {
    const id = ref.slice(7).trim();
    if (id === '') return { form: 'metric', ok: false, detail: "the metric: form is 'metric:<id>'; id is empty" };
    return { form: 'metric', ...scanMetric(id) };
  }
  if (ref.startsWith('tempdoc:')) return { form: 'tempdoc', ...resolveTempdocInstrument(root, ref.slice(8).trim()) };
  return {
    form: 'unknown',
    ok: false,
    detail: 'unrecognised instrument form; expected gate:, check:, test:, metric:, tempdoc: or '
      + "'none - <reason>' (see docs/reference/architectural-risks.md § Instrument grammar)",
  };
}

export async function enforceAdrCoverage(options) {
  const { repoRoot, gate, baselineRef, fixtureMode=false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const adrDir = resolve(sourceRoot, gate.config?.adrDir ?? 'docs/decisions');
  const adrDirRel = (gate.config?.adrDir ?? 'docs/decisions').replaceAll('\\', '/');
  // The window has one authority: `governance/adr-probes.v1.json`'s `reviewStaleDays`, which
  // `world-state.mjs` also reads. `gate.config.reviewStaleDays` is a per-invocation override
  // (the tests use it); it is not a second place to declare the window.
  const reviewStaleDays =
    gate.config?.reviewStaleDays ?? loadReviewStaleDays(sourceRoot, gate.config?.probeRegister);
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

  // --- Architectural risk register (tempdoc 884 PART D) ---
  //
  // WHY THE PRESENCE RULE IS KEYED ON THE CONFIG (884 review S3). Declaring `riskRegister` in a
  // gate's registry config is the promise that the file exists; the promise is what this rule
  // enforces, so `governance/registry.v1.json` (which declares it) makes deleting
  // docs/reference/architectural-risks.md a build failure rather than a silent skip. Two
  // consequences are deliberate:
  //   - The rule is still exercisable in fixture mode — it keys on the gate object, not on
  //     "is this the real repo", so enforcer.test.mjs drives BOTH branches by passing a config
  //     with and without the register present. A rule that cannot fire in fixtures is a rule
  //     with no test.
  //   - The ~25 scaffolded trees in enforcer.test.mjs that declare no `riskRegister` keep
  //     evaluating a register only if one happens to be there, so they stay green without being
  //     given a register they are not about.
  // The remaining hole — deleting the config key instead of the file — is closed by the
  // "the shipped gate config declares a risk register" check in enforcer.test.mjs, and the
  // failure message below names that evasion explicitly.
  const declaredRiskRegister = gate.config?.riskRegister;
  const riskRegisterRel = (declaredRiskRegister ?? DEFAULT_RISK_REGISTER).replaceAll('\\', '/');
  const riskRegisterAbs = resolve(sourceRoot, riskRegisterRel);
  if (!existsSync(riskRegisterAbs)) {
    if (declaredRiskRegister) {
      emit(verdictForRiskRegisterPresence({ registerPath: riskRegisterRel, exists: false }), riskRegisterRel);
    }
  } else {
    // A throw here would take down every other gate in the same run.mjs invocation (the bug
    // PR 1 fixed for the probe register); a broken register reports a finding instead.
    try {
      const risks = parseRiskRegister(readFileSync(riskRegisterAbs, 'utf8'));
      const problem = riskRegisterProblem(risks);
      emit(verdictForRiskRegister({ registerPath: riskRegisterRel, problem }), riskRegisterRel);
      if (!problem) {
        const scanMetric = makeMetricScanner(sourceRoot);
        for (const risk of risks) {
          if (!risk.instrument) {
            emit(verdictForRiskInstrumentCoverage({ riskId: risk.id, instrument: null, statedReason: null }), riskRegisterRel);
            continue;
          }
          const r = resolveRiskInstrument(risk.instrument, sourceRoot, scanMetric);
          if (r.form === 'none') {
            emit(verdictForRiskInstrumentCoverage({
              riskId: risk.id, instrument: risk.instrument, statedReason: r.statedReason,
            }), riskRegisterRel);
            continue;
          }
          emit(verdictForRiskInstrument({
            riskId: risk.id, instrument: risk.instrument, ok: r.ok, detail: r.detail,
          }), riskRegisterRel);
        }
      }
    } catch (e) {
      emit(verdictForRiskRegister({
        registerPath: riskRegisterRel, problem: `it could not be read or parsed (${e.message})`,
      }), riskRegisterRel);
    }
  }

  return { toolName: 'justsearch-adr-coverage', toolVersion: '0.2.0', findings, verdict, ruleDescriptions: ADR_COVERAGE_RULE_DESCRIPTIONS };
}
