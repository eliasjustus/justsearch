/**
 * ADR premise probes — tempdoc 884 §Design decision 1/2.
 *
 * An ADR's load-bearing premise is a claim about the code ("the Head never touches
 * Lucene", "there are 6 MCP tools", "this flag does not exist"). A probe is the cheapest
 * mechanical restatement of that claim that fails when the code drifts away from it.
 * The register is `governance/adr-probes.v1.json`; this module evaluates one probe.
 *
 * Kinds, in the contract's preference order (884 §2):
 *   1. `test`       — a named test/ArchUnit rule already pins the premise. Verifies the
 *                     file exists and still declares the named member.
 *      `gate`       — an existing kernel gate id (`governance/registry.v1.json`) or a
 *                     `scripts/ci/<name>.mjs` check owns the premise.
 *   2. `grep-absent`  / `grep-present` — the symbol/flag/file must (not) exist.
 *   3. `json-path`  — a value in a JSON register is the premise.
 *      `file-set`   — every file in a directory is registered or a reasoned exception.
 *
 * Counts (`expect`) are permitted only where the premise IS the count (ADR-0015); they
 * are never a general growth ratchet.
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve, join, extname } from 'node:path';

export const PROBE_KINDS = new Set([
  'test', 'gate', 'grep-absent', 'grep-present', 'json-path', 'file-set',
]);

const SKIP_DIRS = new Set(['.git', 'node_modules', 'build', 'tmp', '.gradle', 'dist']);

function collectFiles(root, rel, include, out) {
  const abs = resolve(root, rel);
  let st;
  try { st = statSync(abs); } catch { return out; }
  if (st.isFile()) { out.push(abs); return out; }
  if (!st.isDirectory()) return out;
  for (const e of readdirSync(abs, { withFileTypes: true })) {
    if (SKIP_DIRS.has(e.name)) continue;
    const childRel = `${rel}/${e.name}`;
    if (e.isDirectory()) collectFiles(root, childRel, include, out);
    else if (e.isFile() && (!include || include.includes(extname(e.name)))) {
      out.push(join(abs, e.name));
    }
  }
  return out;
}

function countMatches(root, probe) {
  const files = [];
  for (const p of probe.paths ?? []) collectFiles(root, p, probe.include ?? null, files);
  let total = 0;
  const hits = [];
  for (const f of files) {
    let content;
    try { content = readFileSync(f, 'utf8'); } catch { continue; }
    const re = new RegExp(probe.pattern, 'g');
    const m = content.match(re);
    if (m && m.length > 0) {
      total += m.length;
      hits.push(`${f.replace(root, '').replaceAll('\\', '/').replace(/^\//, '')} (${m.length})`);
    }
  }
  return { total, hits, scanned: files.length };
}

/** Resolve a JSON-Pointer-ish `/a/b/0` path against a parsed document. */
function resolvePointer(doc, pointer) {
  if (!pointer || pointer === '/' || pointer === '') return doc;
  let cur = doc;
  for (const rawSeg of pointer.replace(/^\//, '').split('/')) {
    const seg = rawSeg.replaceAll('~1', '/').replaceAll('~0', '~');
    if (cur === null || cur === undefined) return undefined;
    cur = Array.isArray(cur) ? cur[Number(seg)] : cur[seg];
  }
  return cur;
}

function evaluateTest(root, probe) {
  const file = probe.file;
  if (!file || !existsSync(resolve(root, file))) {
    return { ok: false, detail: `named test file '${file}' does not exist` };
  }
  const member = probe.symbol ?? (probe.test?.includes('#') ? probe.test.split('#')[1] : null);
  if (!member) return { ok: true, detail: `test file '${file}' exists` };
  const content = readFileSync(resolve(root, file), 'utf8');
  if (!content.includes(member)) {
    return { ok: false, detail: `'${file}' no longer declares '${member}'` };
  }
  return { ok: true, detail: `'${file}' still declares '${member}'` };
}

function evaluateGate(root, probe) {
  if (probe.script) {
    return existsSync(resolve(root, probe.script))
      ? { ok: true, detail: `check '${probe.script}' exists` }
      : { ok: false, detail: `check '${probe.script}' no longer exists` };
  }
  const registryPath = resolve(root, probe.registry ?? 'governance/registry.v1.json');
  if (!existsSync(registryPath)) {
    return { ok: false, detail: `gate registry '${probe.registry ?? 'governance/registry.v1.json'}' not found` };
  }
  const registry = JSON.parse(readFileSync(registryPath, 'utf8'));
  const ids = (registry.gates ?? []).map((g) => g.id);
  return ids.includes(probe.gate)
    ? { ok: true, detail: `kernel gate '${probe.gate}' is registered` }
    : { ok: false, detail: `kernel gate '${probe.gate}' is no longer registered` };
}

function evaluateGrep(root, probe, mustBePresent) {
  const { total, hits, scanned } = countMatches(root, probe);
  if (scanned === 0) {
    return { ok: false, detail: `probe paths matched no files: ${(probe.paths ?? []).join(', ')}` };
  }
  if (typeof probe.expect === 'number') {
    return total === probe.expect
      ? { ok: true, detail: `${total} match(es) for /${probe.pattern}/, as declared` }
      : { ok: false, detail: `expected ${probe.expect} match(es) for /${probe.pattern}/, found ${total}${hits.length ? ` in ${hits.join('; ')}` : ''}` };
  }
  if (mustBePresent) {
    return total > 0
      ? { ok: true, detail: `${total} match(es) for /${probe.pattern}/` }
      : { ok: false, detail: `no match for /${probe.pattern}/ under ${(probe.paths ?? []).join(', ')}` };
  }
  return total === 0
    ? { ok: true, detail: `/${probe.pattern}/ is absent from ${(probe.paths ?? []).join(', ')}` }
    : { ok: false, detail: `/${probe.pattern}/ now appears ${total} time(s): ${hits.join('; ')}` };
}

function evaluateJsonPath(root, probe) {
  const abs = resolve(root, probe.file);
  if (!existsSync(abs)) return { ok: false, detail: `'${probe.file}' does not exist` };
  let doc;
  try { doc = JSON.parse(readFileSync(abs, 'utf8')); } catch (e) {
    return { ok: false, detail: `'${probe.file}' is not parseable JSON: ${e.message}` };
  }
  const value = resolvePointer(doc, probe.pointer);
  const expect = probe.expect ?? {};
  if (Object.hasOwn(expect, 'count')) {
    const actual = Array.isArray(value) ? value.length : undefined;
    return actual === expect.count
      ? { ok: true, detail: `${probe.file}${probe.pointer} has ${actual} entr(ies), as declared` }
      : { ok: false, detail: `${probe.file}${probe.pointer} has ${actual ?? 'no array'} entr(ies), declared ${expect.count}` };
  }
  if (Object.hasOwn(expect, 'equals')) {
    return value === expect.equals
      ? { ok: true, detail: `${probe.file}${probe.pointer} === ${JSON.stringify(expect.equals)}` }
      : { ok: false, detail: `${probe.file}${probe.pointer} is ${JSON.stringify(value)}, declared ${JSON.stringify(expect.equals)}` };
  }
  if (Object.hasOwn(expect, 'contains')) {
    const hay = JSON.stringify(value ?? null);
    return hay.includes(expect.contains)
      ? { ok: true, detail: `${probe.file}${probe.pointer} contains ${JSON.stringify(expect.contains)}` }
      : { ok: false, detail: `${probe.file}${probe.pointer} no longer contains ${JSON.stringify(expect.contains)}` };
  }
  return { ok: false, detail: `json-path probe declares no expect {count|equals|contains}` };
}

function evaluateFileSet(root, probe) {
  const dirAbs = resolve(root, probe.dir);
  if (!existsSync(dirAbs)) return { ok: false, detail: `'${probe.dir}' does not exist` };
  const ext = probe.extension ?? '.ts';
  const present = readdirSync(dirAbs, { withFileTypes: true })
    .filter((e) => e.isFile() && e.name.endsWith(ext))
    .map((e) => `${probe.dir}/${e.name}`);
  let registeredText = '';
  if (probe.registeredIn && existsSync(resolve(root, probe.registeredIn))) {
    registeredText = readFileSync(resolve(root, probe.registeredIn), 'utf8');
  }
  const exceptions = new Set((probe.exceptions ?? []).map((x) => x.file));
  // Match the full repo-relative path, never the basename: a basename match would call
  // a new hand-mirror "registered" because some unrelated entry shares its filename.
  const unaccounted = present.filter((f) => !exceptions.has(f) && !registeredText.includes(f));
  if (unaccounted.length > 0) {
    return {
      ok: false,
      detail: `${unaccounted.join(', ')} under '${probe.dir}' is neither registered in `
        + `'${probe.registeredIn}' nor a declared exception in governance/adr-probes.v1.json`,
    };
  }
  const stale = [...exceptions].filter((f) => !present.includes(f));
  if (stale.length > 0) {
    return { ok: false, detail: `declared exception(s) ${stale.join(', ')} no longer exist — drop them from the register` };
  }
  return { ok: true, detail: `${present.length} file(s) under '${probe.dir}', all registered or declared exceptions` };
}

/**
 * Evaluate one probe against a source tree.
 *
 * @param {object} probe   a `governance/adr-probes.v1.json` entry
 * @param {string} root    the tree to evaluate against (repo root, or a fixture root)
 * @returns {{ok: boolean, detail: string}}
 */
export function evaluateProbe(probe, root) {
  if (!PROBE_KINDS.has(probe.kind)) {
    return { ok: false, detail: `unknown probe kind '${probe.kind}'` };
  }
  switch (probe.kind) {
    case 'test': return evaluateTest(root, probe);
    case 'gate': return evaluateGate(root, probe);
    case 'grep-present': return evaluateGrep(root, probe, true);
    case 'grep-absent': return evaluateGrep(root, probe, false);
    case 'json-path': return evaluateJsonPath(root, probe);
    case 'file-set': return evaluateFileSet(root, probe);
    default: return { ok: false, detail: `unhandled probe kind '${probe.kind}'` };
  }
}

/**
 * Load the probe register. Returns `{version, probes}` with probes indexed by id,
 * or `null` when the register is absent (a tree with no probes is not a failure —
 * the `no-probe` rule is what notices that).
 */
export function loadProbeRegister(root, registerPath = 'governance/adr-probes.v1.json') {
  const abs = resolve(root, registerPath);
  if (!existsSync(abs)) return null;
  const doc = JSON.parse(readFileSync(abs, 'utf8'));
  const byId = new Map();
  for (const p of doc.probes ?? []) byId.set(p.id, p);
  return { ...doc, byId, registerPath };
}
