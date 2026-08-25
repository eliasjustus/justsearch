/**
 * Grouped observations store — tempdoc 680.
 *
 * `docs/observations.md` holds CONDITIONS (grouped observations), not a flat inbox:
 * writers stay flat one-liners in per-writer shards (618 Seam C, keyed by the
 * writing tree since 862 — unchanged from this module's point of view);
 * identity is resolved here, at the store — a shard entry either merges into an
 * existing condition (occurrence appended, `seen` incremented, `last` updated) or
 * opens a new condition with a *proposed* kind (trailing `?`) for triage to confirm.
 *
 * Store grammar (markdown, diffable, regex-parseable):
 *
 *   ## Conditions
 *
 *   ### obs:<slug> — <title>
 *   `kind: defect` `anchor: <primary>` `seen: 3` `first: 2026-05-19` `last: 2026-07-02` `probe: <cmd>` `status: ...`
 *   - [ ] original entry text (2026-05-19)
 *   - [ ] later occurrence (2026-07-02)
 *
 *   ## Parked
 *   (same shape; groups whose `status` starts with `parked`)
 *
 * Identity (confidence-pass corrected, 680 §Confidence pass): fingerprint =
 * PRIMARY anchor + symptom class. Never transitive — a dry run showed union-find
 * over shared anchors chains unrelated entries into hairballs.
 *
 * Kinds: defect | environment | lesson | follow-up. A trailing `?` marks a
 * fold-proposed kind awaiting triage confirmation (~70% heuristic agreement
 * measured, so proposals only — never silently authoritative).
 */

import fs from 'node:fs';

export const KINDS = ['defect', 'environment', 'lesson', 'follow-up'];

const HEADING_RE = /^### obs:([a-z0-9][a-z0-9-]*) — (.*)$/;
const FIELD_RE = /`([a-z-]+):\s*([^`]*)`/g;
const OCCURRENCE_RE = /^- \[[ xX]\] /;

/** Trailing observation date `(YYYY-MM-DD)` of an entry line, or null. */
export function entryDate(line) {
  const m = String(line).match(/\((\d{4}-\d{2}-\d{2})\)\s*$/);
  return m ? m[1] : null;
}

// ---------------------------------------------------------------------------
// Anchor extraction — primary anchor = most specific mention.
// ---------------------------------------------------------------------------

/** Anchors too generic to identify a condition (would conflate unrelated entries). */
const ANCHOR_STOPLIST = new Set([
  'observations.md', 'claude.md', 'readme.md', 'package.json', 'package-lock.json',
  'build.gradle.kts', 'settings.json', 'settings.local.json', 'main', 'ci.yml',
  'gradlew.bat', 'skill.md', 'tsconfig.json', 'gitignore', '.gitignore',
]);

function cleanToken(raw) {
  return String(raw)
    .trim()
    .replace(/^[('"`]+/, '')
    .replace(/[()`'".,;]+$/, '')
    .replace(/:[\d,~$-]+$/, ''); // strip trailing :line refs
}

/**
 * Ranked anchor candidates for an entry line, most specific first:
 *   1. backticked repo path with an extension (kept as full relative path)
 *   2. a *Test class name
 *   3. a check-* script name
 *   4. a bare filename with a source extension
 * Returns [] when nothing identifying is found.
 */
export function extractAnchors(line) {
  const text = String(line);
  const out = [];
  const push = (v) => {
    const key = v.toLowerCase();
    if (ANCHOR_STOPLIST.has(key) || ANCHOR_STOPLIST.has(key.split('/').pop())) return;
    if (!out.some((o) => o.toLowerCase() === key)) out.push(v);
  };
  for (const m of text.matchAll(/`([^`]{3,160})`/g)) {
    const t = cleanToken(m[1]);
    if (/^[\w.@-]+([\\/][\w.@-]+)+\.[a-z]{2,6}$/i.test(t)) push(t.replace(/\\/g, '/'));
  }
  for (const m of text.matchAll(/\b([A-Z][A-Za-z0-9]{3,}Test)\b/g)) push(m[1]);
  for (const m of text.matchAll(/\b(check-[a-z0-9-]{3,})\b/g)) push(m[1]);
  for (const m of text.matchAll(/\b([A-Za-z][\w.-]{2,60}\.(?:ts|tsx|java|mjs|cjs|py|proto|json|css))\b/g)) {
    push(cleanToken(m[1]));
  }
  return out;
}

/** Coarse symptom class — the fingerprint's second half (splits same-anchor conditions). */
export function symptomClass(line) {
  const t = String(line);
  if (/\bflak(y|e|iness)\b/i.test(t)) return 'flake';
  if (/\bgate\b.{0,40}\b(fail|red)|(fail|red).{0,40}\bgate\b|baseline/i.test(t)) return 'gate-red';
  if (/(test|suite).{0,40}\b(fail|red|broken)|\bfails?\b.{0,30}\b(test|CI)\b/i.test(t)) return 'red-test';
  if (/missing|absent|does not exist|doesn't exist|not found|no such/i.test(t)) return 'missing';
  if (/stale|drift|outdated|superseded|no longer|contradicts/i.test(t)) return 'drift';
  if (/leak|NPE|crash|throws|exception|error\b/i.test(t)) return 'error';
  return 'general';
}

/** Proposed kind for a raw entry line (heuristics — ~70% agreement; proposals only). */
export function proposeKind(line) {
  const t = String(line);
  if (/pre-existing|unrelated to (my|tempdoc|this|any)|on (unmodified |origin\/)?main\b.{0,40}(fail|red|broken)|fails? on main|red on main|(this|local) (machine|env)\b|local-only|environment-caused|flak(y|e)|sandbox/i.test(t)) {
    return 'environment';
  }
  if (/gotcha|lesson|pitfall|agent pitfall|hazard|worth recording|LESSON|workaround/i.test(t)) return 'lesson';
  if (/\bconsider\b|follow-up|deferred|revisit|worth a\b|candidate\b|\bdecide\b|verify whether|investigate\b|open question|design-feature/i.test(t)) {
    return 'follow-up';
  }
  return 'defect';
}

/** Kebab slug for a condition, unique within `existing` (a Set of slugs). */
export function slugFor(anchor, symptom, existing = new Set()) {
  const base = String(anchor || 'unanchored')
    .toLowerCase()
    .split('/').pop()
    .replace(/\.[a-z]{2,6}$/, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48) || 'unanchored';
  let slug = existing.has(base) ? `${base}-${symptom}` : base;
  let i = 2;
  while (existing.has(slug)) slug = `${base}-${symptom}-${i++}`;
  return slug;
}

// ---------------------------------------------------------------------------
// Parse / serialize
// ---------------------------------------------------------------------------

function parseFields(line) {
  const fields = {};
  for (const m of String(line).matchAll(FIELD_RE)) fields[m[1]] = m[2].trim();
  return fields;
}

function serializeFields(fields) {
  const order = ['kind', 'anchor', 'seen', 'first', 'last', 'probe', 'status'];
  const keys = [...order.filter((k) => fields[k] !== undefined && fields[k] !== ''),
    ...Object.keys(fields).filter((k) => !order.includes(k) && fields[k] !== undefined && fields[k] !== '')];
  return keys.map((k) => `\`${k}: ${fields[k]}\``).join(' ');
}

/**
 * Parse a grouped store. Returns { pre, groups } where `pre` is everything before
 * the `## Conditions` heading (frontmatter + Rules, preserved verbatim) and each
 * group is { slug, title, fields, occurrences }. Groups under `## Parked` are
 * included; their placement is derived from `fields.status` on serialize.
 * Returns null when the file has no `## Conditions` section (pre-migration store).
 */
export function parseStore(text) {
  const lines = String(text).split(/\r?\n/);
  const condIdx = lines.findIndex((l) => /^##\s+Conditions\b/.test(l));
  if (condIdx === -1) return null;
  const pre = lines.slice(0, condIdx).join('\n');
  const groups = [];
  let cur = null;
  for (let i = condIdx; i < lines.length; i++) {
    const l = lines[i];
    const h = l.match(HEADING_RE);
    if (h) {
      cur = { slug: h[1], title: h[2].trim(), fields: {}, occurrences: [] };
      groups.push(cur);
      // next non-empty line with backtick fields is the field line
      continue;
    }
    if (!cur) continue;
    if (/^##\s+/.test(l)) { cur = null; continue; } // section boundary (## Parked)
    if (OCCURRENCE_RE.test(l)) { cur.occurrences.push(l.replace(/\s+$/, '')); continue; }
    if (/`[a-z-]+:/.test(l) && Object.keys(cur.fields).length === 0) cur.fields = parseFields(l);
  }
  return { pre: pre.replace(/\s+$/, ''), groups };
}

function isParked(g) {
  return /^parked/i.test(g.fields.status || '');
}

/** Serialize { pre, groups } back to store text (Conditions, then Parked). */
export function serializeStore({ pre, groups }) {
  const active = groups.filter((g) => !isParked(g));
  const parked = groups.filter(isParked);
  const block = (g) => [
    `### obs:${g.slug} — ${g.title}`,
    serializeFields(g.fields),
    ...g.occurrences,
    '',
  ].join('\n');
  const parts = [pre, '', '## Conditions', '', ...active.map(block)];
  if (parked.length) parts.push('## Parked', '', ...parked.map(block));
  return parts.join('\n').replace(/\n{3,}/g, '\n\n').replace(/\s+$/, '') + '\n';
}

export function readStore(file) {
  return parseStore(fs.readFileSync(file, 'utf8'));
}

// ---------------------------------------------------------------------------
// Matching / merging (the fold's intelligence)
// ---------------------------------------------------------------------------

/**
 * Significant-token set for the anchorless title-similarity fallback. Keeps the
 * *contents* of backtick-quoted identifiers (a file/symbol name is the most
 * discriminating part of a note — stripping it made different-artifact notes with
 * a shared template collide, tempdoc 721 review); drops dates, tempdoc/issue
 * numbers, §refs, and sub-4-char tokens as noise.
 */
function sigTokens(s) {
  return new Set(
    String(s)
      .toLowerCase()
      .replace(/\(\d{4}-\d{2}-\d{2}\)|tempdoc\s*\d+|#?\d{2,4}|§[\w.\d-]+/g, ' ')
      .replace(/[^a-z0-9]+/g, ' ')
      .split(/\s+/)
      .filter((w) => w.length >= 4),
  );
}
/**
 * Whole backtick-quoted identifiers (normalized), the discriminating nouns of a
 * note (`package.json`, `SqliteJobQueue.foo`). The anchorless fuzzy-merge path
 * activates ONLY when an entry names such an identifier AND shares one with the
 * candidate — so free-prose notes that share a template but differ in a content
 * word (e.g. "ingest" vs "summary" pipeline) can never over-merge on boilerplate
 * Jaccard alone (tempdoc 721 independent review).
 */
function identTokens(s) {
  const out = new Set();
  for (const m of String(s).matchAll(/`([^`]{2,})`/g)) {
    const norm = m[1].toLowerCase().replace(/[^a-z0-9]+/g, '');
    if (norm.length >= 3) out.add(norm);
  }
  return out;
}
function shares(a, b) {
  for (const x of a) if (b.has(x)) return true;
  return false;
}
function jaccard(a, b) {
  let inter = 0;
  for (const x of a) if (b.has(x)) inter += 1;
  return inter / (a.size + b.size - inter || 1);
}
export const ANCHORLESS_MERGE_THRESHOLD = 0.6;

/**
 * Find the condition a raw entry line belongs to, or null.
 * Conservative by design: primary-anchor equality, and when several groups share
 * that anchor, the entry's symptom class must match too (no transitive merging).
 *
 * Anchorless fallback (tempdoc 721): an entry with no extractable anchor used to
 * ALWAYS open a new condition, so every re-observation of the same anchorless note
 * minted a fresh `unanchored-*` slug instead of bumping `seen` — the backlog-inflating
 * fold leak. Such an entry now merges into an existing *anchorless* condition of the
 * same symptom class when their significant-token Jaccard clears the threshold (single
 * best match only — never transitive, mirroring the same-anchor guard).
 */
export function matchGroup(groups, entryLine) {
  const anchors = extractAnchors(entryLine);
  if (anchors.length === 0) {
    const idents = identTokens(entryLine);
    // No backtick identifier to key on → don't fuzzy-merge free prose. Two notes that
    // share a boilerplate template but differ only in a content word ("ingest" vs
    // "summary" pipeline) clear a high Jaccard yet are different conditions; with no
    // shared named artifact we cannot tell them apart, so open a new condition rather
    // than risk collapsing distinct signal (tempdoc 721 review). Fragmenting an
    // identifier-less re-observation is recoverable noise; over-merging is signal loss.
    if (idents.size === 0) return null;
    const sym = symptomClass(entryLine);
    const toks = sigTokens(entryLine);
    if (toks.size < 3) return null; // too little signal to match safely
    let best = null;
    let bestScore = 0;
    for (const g of groups) {
      if (isParked(g)) continue; // never absorb a recurrence into a dismissed (parked) condition — let it resurface (tempdoc 721 review)
      const a = String(g.fields.anchor || '').toLowerCase();
      if (a && a !== 'none') continue; // only merge into other anchorless conditions
      if ((g.fields.symptom || symptomClass(g.title)) !== sym) continue;
      const gText = `${g.title} ${g.occurrences[0] || ''}`;
      if (!shares(idents, identTokens(gText))) continue; // require a SHARED named artifact before a fuzzy text match
      const score = jaccard(toks, sigTokens(gText));
      if (score > bestScore) { bestScore = score; best = g; }
    }
    return bestScore >= ANCHORLESS_MERGE_THRESHOLD ? best : null;
  }
  const primary = anchors[0].toLowerCase();
  const short = primary.split('/').pop();
  const candidates = groups.filter((g) => {
    const a = String(g.fields.anchor || '').toLowerCase();
    return a === primary || a === short || a.split('/').pop() === short;
  });
  if (candidates.length === 0) return null;
  if (candidates.length === 1) return candidates[0];
  const sym = symptomClass(entryLine);
  return candidates.find((g) => (g.fields.symptom || symptomClass(g.title)) === sym) ?? null;
}

/**
 * Merge one raw entry line into a group (exact-occurrence dedupe; seen++ and
 * last-seen update only when the occurrence is new). Returns true if merged new.
 */
export function mergeOccurrence(group, entryLine) {
  const line = String(entryLine).replace(/\s+$/, '');
  if (group.occurrences.includes(line)) return false;
  group.occurrences.push(line);
  group.fields.seen = String((parseInt(group.fields.seen, 10) || group.occurrences.length - 1) + 1);
  const d = entryDate(line);
  if (d && (!group.fields.last || d > group.fields.last)) group.fields.last = d;
  if (d && (!group.fields.first || d < group.fields.first)) group.fields.first = d;
  return true;
}

/** Open a new condition from a raw entry line, with a PROPOSED (`?`) kind. */
export function newGroupFrom(entryLine, existingSlugs = new Set()) {
  const line = String(entryLine).replace(/\s+$/, '');
  const anchors = extractAnchors(line);
  const anchor = anchors[0] ?? '';
  const sym = symptomClass(line);
  const d = entryDate(line) ?? '';
  const title = line
    .replace(OCCURRENCE_RE, '')
    .replace(/\s*\(\d{4}-\d{2}-\d{2}\)\s*$/, '')
    .replace(/\s+/g, ' ')
    .slice(0, 100);
  return {
    slug: slugFor(anchor, sym, existingSlugs),
    title,
    fields: {
      kind: `${proposeKind(line)}?`,
      anchor: anchor || 'none',
      seen: '1',
      ...(d ? { first: d, last: d } : {}),
    },
    occurrences: [line],
  };
}
