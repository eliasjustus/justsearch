#!/usr/bin/env node

/**
 * URL-emission scorer for the Agent Battery (slice 487 §3.1 probe).
 *
 * The Qwen3.5-9B `url-emitter` profile responds in plain text containing
 * `justsearch://...` URLs. This module parses those URLs out of the
 * response, validates them against the live `OperationCatalog` /
 * `CoreSurfaceCatalog`, and produces a five-axis score per scenario.
 *
 * Pure logic — no battery / SSE / I/O dependencies. Imported by
 * `agent-battery-core.mjs::evaluateTranscript` when a scenario declares
 * `expectedURL` or `expectedURLs`.
 */

import Ajv from 'ajv';
import addFormats from 'ajv-formats';

// ---------------------------------------------------------------------------
// Extraction
// ---------------------------------------------------------------------------

/**
 * Extract every `justsearch://...` URL from a response string.
 *
 * Recognizes both Markdown-link form `[label](justsearch://...)` and bare
 * `justsearch://...` URLs. Terminates each URL at whitespace, closing
 * paren/bracket, angle bracket, or quote.
 *
 * Returns an array of `{raw, url, span}` records, in document order.
 */
export function extractUrls(text) {
  if (typeof text !== 'string' || text.length === 0) return [];
  const re = /justsearch:\/\/[^\s)\]<>"'`]+/g;
  // Trailing-punctuation strip: prose commonly ends URLs with .,;:! etc.
  // We strip these from the captured URL but the span still reflects the
  // raw regex hit so debugging is straightforward.
  const trailingPunctRe = /[.,;:!?”'"]+$/;
  const out = [];
  let m;
  while ((m = re.exec(text)) !== null) {
    let url = m[0];
    const stripped = url.replace(trailingPunctRe, '');
    if (stripped.length > 0) url = stripped;
    out.push({
      raw: url,
      url,
      span: [m.index, m.index + m[0].length],
    });
  }
  return out;
}

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

/**
 * Parse a `justsearch://` URL into `{kind, id, args}`.
 *
 * - `kind`: 'op' | 'surface'
 * - `id`: single-segment catalog identifier (validated URL-safe)
 * - `args`: plain object of decoded args (repeated keys → array)
 *
 * Returns null for malformed URLs.
 */
export function parseUrl(rawUrl) {
  if (typeof rawUrl !== 'string') return null;
  let parsed;
  try {
    parsed = new URL(rawUrl);
  } catch {
    return null;
  }
  if (parsed.protocol !== 'justsearch:') return null;
  const kind = parsed.host;
  if (kind !== 'op' && kind !== 'surface' && kind !== 'query' && kind !== 'answer') return null;
  if (kind === 'answer') {
    // 548 §4.5: justsearch://answer?q=<prompt>[&shape=<id>] — free-text answer verb. Prompt in
    // `q`, optional `shape` (default core.rag-ask); path must be empty.
    const answerPath = parsed.pathname.replace(/^\/+/, '').replace(/\/+$/, '');
    if (answerPath.length !== 0) return null;
    const q = parsed.searchParams.get('q');
    if (q === null || q.trim().length === 0) return null;
    const shape = parsed.searchParams.get('shape') || 'core.rag-ask';
    const answerArgs = {};
    for (const [key, value] of parsed.searchParams.entries()) {
      if (key === 'q' || key === 'shape') continue;
      if (key in answerArgs) {
        const existing = answerArgs[key];
        answerArgs[key] = Array.isArray(existing) ? [...existing, value] : [existing, value];
      } else {
        answerArgs[key] = value;
      }
    }
    return { kind: 'answer', prompt: q, shape, args: answerArgs };
  }
  if (kind === 'query') {
    // 548 S4-A: justsearch://query?q=<text>[&k=v] — free-text search verb. The query text
    // is not a catalog id, so it lives in `q` and the path must be empty.
    const queryPath = parsed.pathname.replace(/^\/+/, '').replace(/\/+$/, '');
    if (queryPath.length !== 0) return null;
    const q = parsed.searchParams.get('q');
    if (q === null || q.trim().length === 0) return null;
    const queryArgs = {};
    for (const [key, value] of parsed.searchParams.entries()) {
      if (key === 'q') continue;
      if (key in queryArgs) {
        const existing = queryArgs[key];
        queryArgs[key] = Array.isArray(existing) ? [...existing, value] : [existing, value];
      } else {
        queryArgs[key] = value;
      }
    }
    return { kind: 'query', query: q, args: queryArgs };
  }
  // pathname is /<id>; strip leading slash, require single segment
  const path = parsed.pathname.replace(/^\/+/, '').replace(/\/+$/, '');
  if (path.length === 0 || path.includes('/')) return null;
  if (!/^[A-Za-z0-9_.\-]+$/.test(path)) return null;
  const args = {};
  for (const [key, value] of parsed.searchParams.entries()) {
    if (key in args) {
      const existing = args[key];
      args[key] = Array.isArray(existing) ? [...existing, value] : [existing, value];
    } else {
      args[key] = value;
    }
  }
  return { kind, id: path, args };
}

// ---------------------------------------------------------------------------
// Canonicalization
// ---------------------------------------------------------------------------

/**
 * Canonicalize a parsed `args` object: sort keys, stable-stringify arrays.
 * Used for byte-stable comparison against `expectedURL`.
 */
export function canonicalizeArgs(args) {
  if (!args || typeof args !== 'object') return '';
  const keys = Object.keys(args).sort();
  const parts = [];
  for (const key of keys) {
    const value = args[key];
    if (Array.isArray(value)) {
      for (const v of value) {
        parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(v))}`);
      }
    } else {
      parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`);
    }
  }
  return parts.join('&');
}

/**
 * Build the canonical URL string from a parsed `{kind, id, args}`.
 */
export function canonicalUrlString(parsed) {
  if (!parsed) return '';
  if (parsed.kind === 'query') {
    // 548 S4-A: q + refinements, keys sorted (q sorts among them) — mirrors parser.ts.
    return `justsearch://query?${canonicalizeArgs({ ...parsed.args, q: parsed.query })}`;
  }
  if (parsed.kind === 'answer') {
    // 548 §4.5: q (prompt) + shape (only when non-default) + refinements, keys sorted.
    const bag = { ...parsed.args, q: parsed.prompt };
    if (parsed.shape && parsed.shape !== 'core.rag-ask') bag.shape = parsed.shape;
    return `justsearch://answer?${canonicalizeArgs(bag)}`;
  }
  const args = canonicalizeArgs(parsed.args);
  return args.length > 0
    ? `justsearch://${parsed.kind}/${parsed.id}?${args}`
    : `justsearch://${parsed.kind}/${parsed.id}`;
}

// ---------------------------------------------------------------------------
// Catalog resolution
// ---------------------------------------------------------------------------

/**
 * Look up a parsed URL's id in the catalog.
 *
 * `catalog` shape:
 *   { operations: [{id, intf?: {inputs?}, presentation?, ...}, ...],
 *     surfaces:   [{id, ...}, ...] }
 *
 * Returns the catalog entry object, or null if unknown.
 */
export function resolveAgainstCatalog(parsed, catalog) {
  if (!parsed || !catalog) return null;
  const collection = parsed.kind === 'op'
    ? (Array.isArray(catalog.operations) ? catalog.operations : [])
    : (Array.isArray(catalog.surfaces) ? catalog.surfaces : []);
  for (const entry of collection) {
    if (!entry || typeof entry !== 'object') continue;
    if (String(entry.id) === parsed.id) return entry;
  }
  return null;
}

// ---------------------------------------------------------------------------
// Args validation against catalog inputSchema
// ---------------------------------------------------------------------------

const ajv = new Ajv({
  coerceTypes: 'array',
  allErrors: true,
  strict: false,
  // URL args arrive as strings even for typed fields; coerce to declared types.
  // 'array' mode additionally wraps single values into single-element arrays
  // for array-typed schemas — matches URL semantics where `?ids=a` is a valid
  // single-value array (vs `?ids=a&ids=b` for multi-value).
});
addFormats(ajv);

const schemaCache = new WeakMap();

function extractInputSchema(catalogEntry) {
  if (!catalogEntry || typeof catalogEntry !== 'object') return null;
  // Preferred shape: { intf: { inputs: <JSON Schema object> } }
  const intf = catalogEntry.intf;
  if (intf && typeof intf === 'object') {
    const inputs = intf.inputs;
    if (inputs && typeof inputs === 'object' && !Array.isArray(inputs)) return inputs;
    if (typeof inputs === 'string') {
      try {
        const parsed = JSON.parse(inputs);
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed;
      } catch {
        return null;
      }
    }
  }
  // Fallback: direct `inputSchema` field (older shape)
  if (catalogEntry.inputSchema && typeof catalogEntry.inputSchema === 'object') {
    return catalogEntry.inputSchema;
  }
  return null;
}

/**
 * Validate a parsed URL's args against the catalog entry's inputSchema.
 *
 * Returns:
 *   { ok: true }                            — schema accepts args
 *   { ok: false, errors: [...] }            — schema rejects args
 *   { ok: null, reason: 'no_schema' }       — entry has no declared schema (not failure)
 */
export function validateArgs(parsed, catalogEntry) {
  if (!parsed) return { ok: false, errors: [{ message: 'no_parsed_url' }] };
  const schema = extractInputSchema(catalogEntry);
  if (!schema) return { ok: null, reason: 'no_schema' };
  let validate;
  if (schemaCache.has(catalogEntry)) {
    validate = schemaCache.get(catalogEntry);
  } else {
    try {
      validate = ajv.compile(schema);
      schemaCache.set(catalogEntry, validate);
    } catch (err) {
      return { ok: null, reason: `compile_error:${err?.message || 'unknown'}` };
    }
  }
  // Shallow-copy args so ajv's coerceTypes can mutate without poisoning the original.
  const argsCopy = { ...parsed.args };
  const valid = validate(argsCopy);
  return valid
    ? { ok: true }
    : { ok: false, errors: (validate.errors || []).map((e) => ({
        path: e.instancePath || e.dataPath || '',
        message: e.message || '',
        keyword: e.keyword || '',
      })) };
}

// ---------------------------------------------------------------------------
// Expected-URL comparison
// ---------------------------------------------------------------------------

/**
 * Compare a parsed URL to an `expectedURL` string.
 * Match = same kind + same id (or, for `query`, same query text) + identical
 * canonicalized args.
 */
export function compareToExpected(parsed, expectedUrl) {
  if (!parsed || typeof expectedUrl !== 'string') return false;
  const expectedParsed = parseUrl(expectedUrl);
  if (!expectedParsed) return false;
  if (parsed.kind !== expectedParsed.kind) return false;
  // 548 S4-A/§4.5: query/answer have no catalog id — match on the free-text prompt (+ shape).
  if (parsed.kind === 'query') {
    if (parsed.query !== expectedParsed.query) return false;
  } else if (parsed.kind === 'answer') {
    if (parsed.prompt !== expectedParsed.prompt) return false;
    if (parsed.shape !== expectedParsed.shape) return false;
  } else if (parsed.id !== expectedParsed.id) {
    return false;
  }
  return canonicalizeArgs(parsed.args) === canonicalizeArgs(expectedParsed.args);
}

/**
 * Compare an array of parsed URLs to an array of expected URLs.
 * Match = each expected URL has at least one matching emitted URL,
 * preserving multiplicity (one emitted URL satisfies one expected URL).
 */
export function compareToExpectedList(parsedList, expectedUrls) {
  if (!Array.isArray(parsedList) || !Array.isArray(expectedUrls)) return false;
  if (expectedUrls.length === 0) return true;
  const remaining = [...parsedList];
  for (const expected of expectedUrls) {
    const idx = remaining.findIndex((p) => compareToExpected(p, expected));
    if (idx < 0) return false;
    remaining.splice(idx, 1);
  }
  return true;
}

// ---------------------------------------------------------------------------
// Top-level evaluator
// ---------------------------------------------------------------------------

/**
 * Score a scenario's URL-emission performance.
 *
 * Returns the five-axis result:
 *   {
 *     urlPresent: boolean,            // ≥1 parseable justsearch:// URL extracted
 *     schemeValid: boolean,           // first extracted URL parses cleanly
 *     opIdResolves: boolean,          // first URL's id ∈ catalog
 *     argsValidate: boolean | null,   // first URL's args pass inputSchema (null = no schema)
 *     semanticMatch: boolean | null,  // matches expectedURL/expectedURLs (null = no expectation)
 *     extractedUrls: [{raw, parsed}], // every URL extracted, parsed where possible
 *     failures: [string]              // human-readable failure descriptions
 *   }
 *
 * The five axes are independent — `argsValidate: false` does not block
 * `semanticMatch: true`. Callers downstream (scorecard, slice probe results)
 * decide which axes gate which decisions.
 */
export function evaluateUrlEmission(scenario, transcript, catalog) {
  const response = String(transcript?.finalResponse || '');
  const failures = [];
  const extracted = extractUrls(response);

  const urlPresent = extracted.length > 0;
  if (!urlPresent) failures.push('no_url_extracted');

  // First-URL axes — measure on the first extracted URL. If a scenario expects
  // multiple URLs, semanticMatch (below) evaluates the full set; the first-URL
  // axes are diagnostics for "does the model emit *anything* well-formed?".
  const firstRaw = extracted[0]?.raw ?? null;
  const firstParsed = firstRaw ? parseUrl(firstRaw) : null;
  const schemeValid = firstParsed !== null;
  if (urlPresent && !schemeValid) failures.push(`scheme_invalid:${firstRaw}`);

  let firstCatalogEntry = null;
  let opIdResolves = false;
  if (firstParsed) {
    if (firstParsed.kind === 'query' || firstParsed.kind === 'answer') {
      // 548 S4-A/§4.5: query/answer verbs have no catalog id — they target a fixed
      // surface/shape, so id-resolution is N/A and always considered to resolve. argsValidate
      // stays null (no inputSchema); semanticMatch checks the prompt text below.
      opIdResolves = true;
    } else {
      firstCatalogEntry = resolveAgainstCatalog(firstParsed, catalog);
      opIdResolves = firstCatalogEntry !== null;
      if (!opIdResolves) failures.push(`unknown_id:${firstParsed.kind}/${firstParsed.id}`);
    }
  }

  let argsValidate = null;
  if (firstParsed && firstCatalogEntry) {
    const validation = validateArgs(firstParsed, firstCatalogEntry);
    if (validation.ok === true) argsValidate = true;
    else if (validation.ok === false) {
      argsValidate = false;
      const errSummary = (validation.errors || [])
        .slice(0, 3)
        .map((e) => `${e.path || '(root)'}: ${e.message}`)
        .join(' | ');
      failures.push(`args_invalid:${errSummary}`);
    }
    // null → no schema; argsValidate stays null
  }

  // Parse all extracted URLs (for the extractedUrls field + semantic match)
  const allParsed = extracted
    .map((e) => ({ raw: e.raw, parsed: parseUrl(e.raw) }))
    .filter((e) => e.parsed !== null)
    .map((e) => e.parsed);

  let semanticMatch = null;
  if (typeof scenario?.expectedURL === 'string' && scenario.expectedURL.length > 0) {
    semanticMatch = allParsed.some((p) => compareToExpected(p, scenario.expectedURL));
    if (!semanticMatch) failures.push(`semantic_mismatch:expected=${scenario.expectedURL}`);
  } else if (Array.isArray(scenario?.expectedURLs) && scenario.expectedURLs.length > 0) {
    semanticMatch = compareToExpectedList(allParsed, scenario.expectedURLs);
    if (!semanticMatch) {
      failures.push(`semantic_mismatch:expected=[${scenario.expectedURLs.join(', ')}]`);
    }
  }

  return {
    urlPresent,
    schemeValid,
    opIdResolves,
    argsValidate,
    semanticMatch,
    extractedUrls: extracted.map((e) => ({
      raw: e.raw,
      parsed: parseUrl(e.raw),
    })),
    failures,
  };
}
