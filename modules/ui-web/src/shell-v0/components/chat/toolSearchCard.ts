// SPDX-License-Identifier: Apache-2.0
/**
 * toolSearchCard — Search Thread S7 (tempdoc decision 4): projects an agent search tool call's
 * `structuredData` into the shared `<jf-results-card>` snapshot/provenance shape — the SAME card a
 * user-issued search commits to (`ResultsCard.ts`), so an agent search and a user search render
 * through the ONE card, not two. Replaces the retired bespoke evidence-card renderer
 * (`searchEvidence.ts`), which drew its own filename/excerpt cards.
 *
 * Backend contract (`SearchTool.buildSearchEvidence`, tempdoc S7): `structuredData` carries
 * top-level `query` + `resultCount` alongside the existing `searchResults[]`
 * ({title,path,excerpt,line,...}). Old records persisted before that change may lack the top-level
 * `query`/`resultCount` keys — this module derives them (`searchResults.length`; the tool call's own
 * `query` argument) rather than fabricate anything the record doesn't actually carry. If a query
 * cannot be honestly derived at all, {@link agentSearchCardData} returns `null` (no evidence card —
 * the raw-output fallback renders instead) rather than show a fabricated/empty provenance line.
 */
import type { CardHit, CardSnapshot, SearchProvenance } from '../searchResults/ResultsCard.js';
import { filenameOf } from './evidenceProjection.js';

/** A search hit as carried on tool structuredData — the CardHit shape plus the row snippet text. */
export interface AgentSearchHit extends CardHit {
  readonly snippet: string;
}

function rawSearchResults(structuredData: unknown): Array<Record<string, unknown>> | null {
  const sd = structuredData as Record<string, unknown> | undefined;
  const results = sd?.['searchResults'];
  return Array.isArray(results) && results.length > 0 ? (results as Array<Record<string, unknown>>) : null;
}

/** True when the structured data carries a non-empty agent search-evidence list. */
export function hasAgentSearchEvidence(structuredData: unknown): boolean {
  return rawSearchResults(structuredData) !== null;
}

/**
 * Project one raw `searchResults[]` entry into the shared card's hit shape. `path` IS the `id` — the
 * established convention (searchState's live `SearchHit.id` is likewise the response doc id, which
 * equals `path` for this corpus). Empty title falls back to the filename, never a raw path/blank.
 */
function toAgentSearchHit(r: Record<string, unknown>): AgentSearchHit {
  const path = typeof r['path'] === 'string' ? r['path'] : '';
  const rawTitle = typeof r['title'] === 'string' ? r['title'] : '';
  const title = rawTitle || filenameOf(path) || '(untitled)';
  const snippet = typeof r['excerpt'] === 'string' ? r['excerpt'] : '';
  return { id: path, title, path, snippet };
}

/** Best-effort `query` recovered from the tool call's OWN arguments JSON (old-record fallback). */
function queryFromArguments(argumentsJson: string | undefined): string | undefined {
  if (!argumentsJson) return undefined;
  try {
    const parsed = JSON.parse(argumentsJson) as unknown;
    const q = (parsed as Record<string, unknown> | null)?.['query'];
    return typeof q === 'string' && q.trim() ? q : undefined;
  } catch {
    return undefined;
  }
}

/**
 * The ONE search-evidence tool-card projection: snapshot (rows) + provenance (identity) for
 * `<jf-results-card variant="excerpt">`. Returns `null` when there is no evidence at all (no
 * `searchResults`), or — the honest old-record edge case — when a query cannot be recovered from
 * either the new top-level key or the tool call's own arguments (never fabricates a query text).
 */
export function agentSearchCardData(
  structuredData: unknown,
  argumentsJson: string | undefined,
  executedAt: string,
): { snapshot: CardSnapshot; provenance: SearchProvenance } | null {
  const raw = rawSearchResults(structuredData);
  if (raw === null) return null;
  const sd = structuredData as Record<string, unknown>;
  const query =
    typeof sd['query'] === 'string' && (sd['query'] as string).trim()
      ? (sd['query'] as string)
      : queryFromArguments(argumentsJson);
  if (query == null) return null;
  const hits = raw.map(toAgentSearchHit);
  const resultCount = typeof sd['resultCount'] === 'number' ? (sd['resultCount'] as number) : hits.length;
  const snapshot: CardSnapshot = {
    query,
    results: hits,
    matchCount: resultCount,
    totalHits: resultCount,
    facetsTruncated: false,
    isSearching: false,
    processingTimeMs: null,
    error: null,
  };
  const provenance: SearchProvenance = {
    actor: 'agent',
    query,
    // No retrieval-mode signal is carried on the agent tool's structuredData; retrievalModeLabel
    // returns null for an unrecognized string, so the mode segment simply omits (never fabricated).
    mode: '',
    matchCount: resultCount,
    resultCount,
    executedAt,
  };
  return { snapshot, provenance };
}

/** Look up one hit by id (== path) for the reading-pane open path (`card-open`). */
export function findAgentSearchHit(structuredData: unknown, id: string): AgentSearchHit | undefined {
  const raw = rawSearchResults(structuredData);
  if (raw === null) return undefined;
  return raw.map(toAgentSearchHit).find((h) => h.id === id);
}
