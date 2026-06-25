// SPDX-License-Identifier: Apache-2.0
/**
 * Intent resolution types and CatalogResolver (tempdoc 499 §4.1 + §4.6).
 *
 * The resolution layer sits between parsing and dispatch: it confirms a
 * target ID exists in the live catalog, or produces a structured failure
 * with diagnosis and ranked alternatives.
 */

export type RedirectReason = 'alias' | 'renamed';

export type FailureMode = 'typo' | 'stale-catalog' | 'renamed-removed' | 'unknown';

export interface UnresolvedDiagnosis {
  readonly mode: FailureMode;
  readonly detail: string;
}

export interface Suggestion {
  readonly id: string;
  readonly label: string;
  readonly confidence: number;
  readonly rationale: string;
}

export type ResolutionResult =
  | { readonly status: 'resolved'; readonly id: string }
  | { readonly status: 'redirected'; readonly id: string; readonly originalId: string; readonly reason: RedirectReason }
  | { readonly status: 'unresolved'; readonly attemptedId: string; readonly diagnosis: UnresolvedDiagnosis; readonly alternatives: Suggestion[] };

export interface AliasEntry {
  readonly target: string;
  readonly reason: RedirectReason;
}

export type AliasMap = Readonly<Record<string, AliasEntry>>;

/**
 * Damerau-Levenshtein distance between two strings. Counts adjacent
 * transpositions as a single edit.
 */
export function damerauLevenshtein(a: string, b: string): number {
  const lenA = a.length;
  const lenB = b.length;
  if (lenA === 0) return lenB;
  if (lenB === 0) return lenA;

  // Use a flat array to avoid TS strict-null indexing issues with 2D arrays
  const cols = lenB + 1;
  const d = new Int32Array((lenA + 1) * cols);
  const at = (i: number, j: number) => i * cols + j;

  for (let i = 0; i <= lenA; i++) d[at(i, 0)] = i;
  for (let j = 0; j <= lenB; j++) d[at(0, j)] = j;

  for (let i = 1; i <= lenA; i++) {
    for (let j = 1; j <= lenB; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      d[at(i, j)] = Math.min(
        d[at(i - 1, j)]! + 1,
        d[at(i, j - 1)]! + 1,
        d[at(i - 1, j - 1)]! + cost,
      );
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) {
        d[at(i, j)] = Math.min(d[at(i, j)]!, d[at(i - 2, j - 2)]! + cost);
      }
    }
  }
  return d[at(lenA, lenB)]!;
}

function tokenize(id: string): Set<string> {
  const tokens = new Set<string>();
  for (const part of id.split(/[.\-]/)) {
    if (part) tokens.add(part);
  }
  return tokens;
}

function tokenOverlapScore(a: string, b: string): number {
  const tokA = tokenize(a);
  const tokB = tokenize(b);
  if (tokA.size === 0 && tokB.size === 0) return 1;
  let intersection = 0;
  for (const t of tokA) { if (tokB.has(t)) intersection++; }
  const union = new Set([...tokA, ...tokB]).size;
  return union === 0 ? 0 : intersection / union;
}

function prefixScore(a: string, b: string): number {
  const maxLen = Math.max(a.length, b.length);
  if (maxLen === 0) return 1;
  const minLen = Math.min(a.length, b.length);
  let shared = 0;
  for (let i = 0; i < minLen; i++) {
    if (a[i] === b[i]) shared++;
    else break;
  }
  return shared / maxLen;
}

function computeConfidence(query: string, candidate: string): number {
  const dl = damerauLevenshtein(query, candidate);
  const maxLen = Math.max(query.length, candidate.length);
  const dlScore = maxLen === 0 ? 1 : Math.max(0, 1 - dl / maxLen);
  const tokScore = tokenOverlapScore(query, candidate);
  const pfxScore = prefixScore(query, candidate);
  return 0.50 * dlScore + 0.30 * tokScore + 0.20 * pfxScore;
}

const MIN_CONFIDENCE = 0.25;
const MAX_SUGGESTIONS = 3;

export interface CatalogEntry {
  readonly id: string;
  readonly label: string;
}

/**
 * Resolve an ID against a set of catalog entries. Returns a structured
 * ResolutionResult: exact match, alias redirect, or unresolved with suggestions.
 */
export type SynonymMap = Readonly<Record<string, string>>;

/**
 * Resolve an ID against a set of catalog entries. Returns a structured
 * ResolutionResult: exact match, alias redirect, or unresolved with suggestions.
 *
 * The pipeline: (1) exact match, (2) alias redirect, (3) synonym transform +
 * re-match, (4) approximate matching.
 */
export function resolveAgainstCatalog(
  rawId: string,
  entries: readonly CatalogEntry[],
  aliases: AliasMap = {},
  catalogName = 'catalog',
  synonyms: SynonymMap = {},
): ResolutionResult {
  // 1. Exact match
  const exact = entries.find(e => e.id === rawId);
  if (exact) return { status: 'resolved', id: exact.id };

  // 2. Alias lookup
  const alias = aliases[rawId];
  if (alias) {
    const aliasTarget = entries.find(e => e.id === alias.target);
    if (aliasTarget) {
      return { status: 'redirected', id: aliasTarget.id, originalId: rawId, reason: alias.reason };
    }
  }

  // 3. Synonym transform: token-based replacement, then re-try exact match
  const delimiters = rawId.match(/[.\-]/g) ?? [];
  const tokens = rawId.split(/[.\-]/);
  const replacedTokens = tokens.map(t => synonyms[t] ?? t);
  let transformed = '';
  for (let i = 0; i < replacedTokens.length; i++) {
    transformed += replacedTokens[i];
    if (i < delimiters.length) transformed += delimiters[i];
  }
  if (transformed !== rawId) {
    const synonymMatch = entries.find(e => e.id === transformed);
    if (synonymMatch) {
      return { status: 'redirected', id: synonymMatch.id, originalId: rawId, reason: 'renamed' };
    }
  }

  // 3. Approximate matching
  const scored: Suggestion[] = [];
  for (const entry of entries) {
    const confidence = computeConfidence(rawId, entry.id);
    if (confidence >= MIN_CONFIDENCE) {
      const dl = damerauLevenshtein(rawId, entry.id);
      scored.push({
        id: entry.id,
        label: entry.label,
        confidence,
        rationale: `edit-distance=${dl}, token-overlap=${tokenOverlapScore(rawId, entry.id).toFixed(2)}`,
      });
    }
  }
  scored.sort((a, b) => b.confidence - a.confidence);
  const alternatives = scored.slice(0, MAX_SUGGESTIONS);

  const mode: FailureMode = alternatives.length > 0 ? 'typo' : 'unknown';
  return {
    status: 'unresolved',
    attemptedId: rawId,
    diagnosis: { mode, detail: `No entry '${rawId}' in ${catalogName}` },
    alternatives,
  };
}
