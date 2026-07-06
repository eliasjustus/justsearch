// SPDX-License-Identifier: Apache-2.0
/**
 * routeHeuristic.ts — Search Thread tempdoc D3: the per-turn ROUTE heuristic.
 *
 * Every input already feeds instant search (the floor); Enter either commits a search
 * (`'search'`) or sends to the AI (`'ask'`). This module is the PURE, deterministic guess of
 * which one the user means, computed from composer text alone — no store reads, no async, no
 * side effects, so it can be unit-tested exhaustively and reused by both the route chip and the
 * Enter-key dispatcher without either owning the policy.
 *
 * Tradeoff (tempdoc D3): a misroute is CHEAP, not silent. Because the floor already ran an
 * instant search on every keystroke, guessing `'search'` when the user meant `'ask'` still shows
 * useful results — the miss is "you got search results instead of an answer", not "nothing
 * happened". And a single keystroke (the visible route chip, or Ctrl+Enter) overrides the guess
 * either way. That asymmetry is why this stays a cheap heuristic instead of a classifier: the
 * failure mode is recoverable in one keystroke, so precision is not worth the complexity/latency
 * of a real intent model.
 */

/** What Enter will do for the current turn. */
export type TurnRoute = 'search' | 'ask';

/**
 * Interrogative/imperative-at-assistant starter words (case-insensitive, word-boundary — so
 * `whatsapp` does not match `what`, `listing` does not match `list`, `canary` does not match
 * `can`). `find out` is the one deliberate multiword entry.
 */
const STARTER_WORDS = [
  'who',
  'what',
  'when',
  'where',
  'why',
  'how',
  'which',
  'can',
  'could',
  'should',
  'would',
  'will',
  'is',
  'are',
  'does',
  'do',
  'did',
  'explain',
  'summarize',
  'summarise',
  'compare',
  'list',
  'tell',
  'describe',
  'write',
  'find out',
] as const;

const STARTER_PATTERN = new RegExp(
  `^(${STARTER_WORDS.map((w) => w.replace(' ', '\\s+')).join('|')})\\b`,
  'i',
);

/** ≥ this many words in the trimmed text reads as verbose natural language → `'ask'`. */
const VERBOSE_WORD_COUNT = 8;

/**
 * Infer the route Enter should take for the given composer text. Deterministic, pure, and
 * order-sensitive: empty/whitespace wins first (so a stray newline in blank text still reads as
 * `'search'`), then multiline, then `?`, then the starter-word match, then the verbose-length
 * fallback.
 */
export function inferRoute(text: string): TurnRoute {
  const trimmed = text.trim();
  if (trimmed.length === 0) return 'search';
  if (text.includes('\n')) return 'ask';
  if (trimmed.includes('?')) return 'ask';
  if (STARTER_PATTERN.test(trimmed)) return 'ask';
  const wordCount = trimmed.split(/\s+/).filter(Boolean).length;
  if (wordCount >= VERBOSE_WORD_COUNT) return 'ask';
  return 'search';
}

/** Human-facing label for a route (used by the route chip and its aria-label). */
export function describeRoute(route: TurnRoute): string {
  return route === 'search' ? 'Search' : 'Ask';
}
