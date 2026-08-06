#!/usr/bin/env node
/**
 * offline-single-sense copy lint — tempdoc 813 §6 (the regression home for human-validation
 * finding 11).
 *
 * "Offline" carried TWO meanings in the product's own copy: the AI engine being down ("AI Offline",
 * "The local AI model is offline") and a *mode of work* ("Run Offline Processing" — a button whose
 * work is enrichment and which in fact requires the AI engine to be UP). 813 §6 retires the second
 * sense: enrichment copy uses the phase nouns Indexing / Enriching, and "offline" is reserved for
 * the one engine/connectivity sense.
 *
 * This lint keeps it retired. It scans the user-facing copy surfaces — the ui-web sources and the
 * Head's message catalogs — and fails on any occurrence of the token "offline" that is not in the
 * allow-list below. The allow-list is an enumeration of today's legitimate uses (engine/connectivity
 * wording, plus the stable identifiers that carry the historical operation id), so the gate's bite is
 * on NEW occurrences: a fresh "offline" in copy fails until someone justifies it here.
 *
 * Honest limit: this is a token lint, not a semantics checker — it cannot tell that a NEW
 * engine-sense string is fine while a new work-mode string is not. It forces the question to be
 * answered in review instead of silently re-splitting the word.
 *
 * Usage:
 *   node scripts/ci/check-offline-single-sense.mjs            # gate (exit 1 on a new occurrence)
 *   node scripts/ci/check-offline-single-sense.mjs --print    # enumerate what is present today
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { stripComments as stripCommentsShared } from '../lib/strip-comments.mjs';

const TS_ROOT = 'modules/ui-web/src';
const MESSAGES_DIR = 'modules/app-api/src/main/resources/messages';

const norm = (p) => p.replace(/\\/g, '/');

/**
 * Today's sanctioned occurrences, per file. Each entry is the PHRASE around the token (the maximal
 * run of word / dot / hyphen / space characters containing it), which is what {@link phrasesIn}
 * extracts. Two kinds are listed:
 *   - the ONE sanctioned copy sense — the AI engine / a service being offline;
 *   - identifiers that are not copy at all (the `core.trigger-offline-processing` operation id, the
 *     `inference.offline` reason code, the `offline` engine-state literal, `WorkerOffline`), which
 *     stay as they are because renaming a wire identifier is not a copy change.
 */
const ALLOW = {
  // --- the engine / service sense (the ONE sanctioned copy meaning) ---
  'modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.ts': [
    'the embedding service is offline',
  ],
  'modules/ui-web/src/shell-v0/chrome/Shell.ts': ['AI offline'],
  'modules/ui-web/src/shell-v0/views/UnifiedChatView.ts': [
    'the model may be offline',
    'The local AI model is offline',
    'AI Offline',
  ],
  'modules/ui-web/src/shell-v0/state/readinessNotice.ts': [
    'The local AI model is offline',
    // Wire identifiers, not copy: the reason codes and the operation id keep their historical names.
    'inference.offline',
    'vdu.ai_offline',
    'core.trigger-offline-processing',
  ],
  // --- engine-state / reason-code identifiers and their tone-map keys (not copy) ---
  'modules/ui-web/src/shell-v0/components/StatusDeck.ts': ['offline'],
  'modules/ui-web/src/shell-v0/components/advisory/AdvisoryRailBadge.ts': ['offline'],
  'modules/ui-web/src/shell-v0/demo/shell-demo.ts': ['WorkerOffline'],
  'modules/ui-web/src/shell-v0/state/aiStateStore.ts': ['offline'],
  'modules/ui-web/src/shell-v0/state/aiVerdict.ts': ['offline', 'Offline'],
  'modules/ui-web/src/shell-v0/state/availability.ts': ['inference.offline'],
  'modules/ui-web/src/shell-v0/utils/inferencePoll.ts': ['offline'],
  'modules/ui-web/src/shell-v0/views/LibrarySurface.ts': ['core.trigger-offline-processing'],
  'modules/ui-web/src/shell-v0/views/BrainSurface.ts': [
    'offline',
    'AI Offline',
    // The no-internet INSTALL sense (importing a model pack on a disconnected machine). Adjacent to
    // the sanctioned connectivity meaning rather than the retired work-mode one; left as-is by 813,
    // listed here so a future naming pass can find it.
    'Offline pack import',
  ],
  'modules/app-api/src/main/resources/messages/registry-surface.en.properties': [
    // Same no-internet install sense as BrainSurface's heading above.
    'and import offline AI packs.',
  ],
};

const stripComments = (s) => stripCommentsShared(s, { withHtml: false });

/**
 * Every string / template literal in already-comment-stripped TS source. A template literal's
 * `${…}` interpolations are dropped: they are code (identifiers, calls), not copy.
 */
function literalsIn(src) {
  const re = /'(?:[^'\\\n]|\\.)*'|"(?:[^"\\\n]|\\.)*"|`(?:[^`\\]|\\.)*`/g;
  const out = [];
  let m;
  while ((m = re.exec(src)) !== null) {
    const body = m[0].slice(1, -1);
    out.push(m[0].startsWith('`') ? body.replace(/\$\{[^}]*\}/g, ' ') : body);
  }
  return out;
}

/**
 * The allow-list KEY for each "offline" occurrence in a piece of copy: the maximal run of
 * word / dot / hyphen / space characters containing the token, whitespace-collapsed. Stable enough to
 * survive edits elsewhere in the same sentence, specific enough to name what was sanctioned.
 */
export function phrasesIn(text) {
  const out = [];
  const re = /offline/gi;
  const allowed = /[A-Za-z0-9_.\- ]/;
  let m;
  while ((m = re.exec(text)) !== null) {
    let start = m.index;
    let end = m.index + m[0].length;
    while (start > 0 && allowed.test(text[start - 1])) start -= 1;
    while (end < text.length && allowed.test(text[end])) end += 1;
    const phrase = text.slice(start, end).replace(/\s+/g, ' ').trim();
    if (phrase) out.push(phrase);
  }
  return out;
}

function walkTs(dir, acc) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      if (name === 'generated') continue; // wire types, not copy
      walkTs(p, acc);
    } else if (/\.(ts|tsx)$/.test(name) && !/\.test\.(ts|tsx)$/.test(name)) {
      acc.push(norm(p));
    }
  }
  return acc;
}

/** file → the phrases present today. */
function scan() {
  const found = new Map();
  const add = (file, phrases) => {
    if (phrases.length === 0) return;
    const seen = found.get(file) ?? new Set();
    for (const p of phrases) seen.add(p);
    found.set(file, seen);
  };

  for (const file of walkTs(TS_ROOT, [])) {
    const src = stripComments(readFileSync(file, 'utf8'));
    const phrases = [];
    for (const lit of literalsIn(src)) phrases.push(...phrasesIn(lit));
    add(file, phrases);
  }

  for (const name of readdirSync(MESSAGES_DIR)) {
    if (!name.endsWith('.properties')) continue;
    const file = norm(join(MESSAGES_DIR, name));
    const phrases = [];
    for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
      if (/^\s*[#!]/.test(line)) continue; // catalog comments are not copy
      const eq = line.indexOf('=');
      if (eq < 0) continue;
      // The VALUE is the user-facing half; a key may legitimately carry the historical id.
      phrases.push(...phrasesIn(line.slice(eq + 1)));
    }
    add(file, phrases);
  }
  return found;
}

const found = scan();

if (process.argv.includes('--print')) {
  for (const [file, phrases] of [...found].sort()) {
    console.log(`${file}: ${JSON.stringify([...phrases])}`);
  }
  process.exit(0);
}

const failures = [];
const stale = [];
for (const [file, phrases] of found) {
  const allowed = new Set(ALLOW[file] ?? []);
  for (const phrase of phrases) {
    if (!allowed.has(phrase)) failures.push(`${file}: "${phrase}"`);
  }
}
for (const [file, phrases] of Object.entries(ALLOW)) {
  const present = found.get(norm(file)) ?? new Set();
  for (const phrase of phrases) {
    if (!present.has(phrase)) stale.push(`${file}: "${phrase}"`);
  }
}

if (failures.length > 0) {
  console.error(
    '✗ offline-single-sense lint FAILED — "offline" appears in copy outside its one sanctioned\n' +
      '  sense (the AI engine / a service being down; tempdoc 813 §6). Enrichment and indexing copy\n' +
      '  uses the phase nouns Indexing / Enriching instead — e.g. "Finish enrichment now", not\n' +
      '  "Run Offline Processing". If the occurrence really is the engine sense (or a wire\n' +
      '  identifier, not copy), add it to ALLOW in scripts/ci/check-offline-single-sense.mjs:\n' +
      failures.map((x) => '  - ' + x).join('\n'),
  );
  process.exit(1);
}
if (stale.length > 0) {
  console.log(
    'note: ALLOW entries no longer present (safe to delete from the lint):\n' +
      stale.map((x) => '  - ' + x).join('\n'),
  );
}
console.log(
  `✓ offline-single-sense lint OK — "offline" is used in one sense only across ${TS_ROOT} and ` +
    `${MESSAGES_DIR} (813 §6).`,
);
