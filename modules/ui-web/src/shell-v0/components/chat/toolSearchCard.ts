// SPDX-License-Identifier: Apache-2.0
/**
 * toolSearchCard — Search Thread S7 (tempdoc decision 4) / tempdoc 867 (L1+L2 slice): projects an
 * agent search tool call's `structuredData` into the shape `ToolCallCard`'s own level-2 evidence
 * body renders — the tool card's SEARCH-specific body, no longer a nested `<jf-results-card>` mount.
 *
 * Backend contract (`SearchTool.buildSearchEvidence`, tempdocs S7 / 867): `structuredData` carries
 * top-level `query` + `resultCount` (+ `searchMode` since 867 — the RESOLVED pipeline preset the
 * call actually ran with, `SearchTool.resolveEffectiveSearchMode`) alongside the existing
 * `searchResults[]` ({title,path,excerpt,line,...}). Old records persisted before that change may
 * lack the top-level `query`/`resultCount`/`searchMode` keys — this module derives `query`/
 * `resultCount` (`searchResults.length`; the tool call's own `query` argument) rather than fabricate
 * anything the record doesn't actually carry, and leaves `mode` `''` (867 §2a's named gap: the
 * record simply does not say). If a query cannot be honestly derived at all,
 * {@link agentSearchCardProjection} returns `null` (no evidence card — the raw-output fallback
 * renders instead) rather than show a fabricated/empty provenance line.
 *
 * Tempdoc 865 §7.4 — this is the delegate plane's SECOND evidence surface, and the divergence from
 * the run's evidence-set mint (`AgentSessionController.groundingDeltas`) is DELIBERATE: this module
 * projects a RECEIPT OF ONE CALL (every hit that call returned, in that call's own order, addressable
 * identity or not); the run's evidence set is deduped across calls, identity-bearing only. Tempdoc
 * 867 adds a THIRD fact per row — `inEvidence` — which JOINS the two rather than merging them: it
 * asks "is this receipt row also a member of the run's evidence set", never re-deriving the set
 * itself. See `governance/execution-surfaces.v1.json`'s `agent-tool-search-card` row for the full
 * divergence rationale.
 */
import type { CardHit } from '../searchResults/ResultsCard.js';
import { filenameOf } from './evidenceProjection.js';

/** A search hit as carried on tool structuredData — the CardHit shape plus the row snippet text. */
export interface AgentSearchHit extends CardHit {
  readonly snippet: string;
}

/**
 * Tempdoc 867 — one level-2 evidence row: the receipt hit, joined against the run's evidence set
 * (`inEvidence`), with a human LOCATOR derived from whichever of `headingText` / `line` the backend
 * reported (`null` when neither is available — never fabricated).
 */
export interface AgentSearchCardRow extends AgentSearchHit {
  readonly inEvidence: boolean;
  readonly locator: string | null;
}

/**
 * Tempdoc 867 — the ONE search tool-card projection: query, scope (the `path_prefix` restriction
 * this call ran under, or `''` when none), mode (the RESOLVED pipeline preset the call actually ran
 * with, or `''` when the record predates that stamp — §2a's named gap), every hit this call returned
 * (joined against the run's evidence set), the call's own result count, and how many of its hits are
 * in the run's evidence set.
 */
export interface AgentSearchCardProjection {
  readonly query: string;
  readonly scope: string;
  readonly mode: string;
  /**
   * Tempdoc 871 §3b — the `limit` this call EXPLICITLY asked for, or `null` when it asked for none.
   * `null` is not "no limit": the effective one then comes from config (`SearchTool.DEFAULT_LIMIT`,
   * itself read from `ConfigStore`), which the record does not carry — the same shape as {@link mode}'s
   * named gap. The scope line therefore renders a limit only when the model actually chose one.
   */
  readonly limit: number | null;
  readonly hits: readonly AgentSearchCardRow[];
  readonly resultCount: number;
  readonly evidenceCount: number;
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

/**
 * Tempdoc 867 — the row's human locator: the backend's `headingText` when it reported one, else
 * `Line N` from the region's approximate line, else `null` (neither reported — never fabricated).
 */
function locatorOf(r: Record<string, unknown>): string | null {
  const heading = typeof r['headingText'] === 'string' ? r['headingText'].trim() : '';
  if (heading) return heading;
  const line = typeof r['line'] === 'number' ? r['line'] : 0;
  return line > 0 ? `Line ${line}` : null;
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
 * Tempdoc 867 — the `path_prefix` scope this call ran under, read off the tool call's OWN arguments
 * (the same argument `SearchTool.java` reads server-side). `''` when the call carried none — a
 * corpus-wide search, not an absent fact.
 */
function scopeFromArguments(argumentsJson: string | undefined): string {
  if (!argumentsJson) return '';
  try {
    const parsed = JSON.parse(argumentsJson) as unknown;
    const p = (parsed as Record<string, unknown> | null)?.['path_prefix'];
    return typeof p === 'string' && p.trim() ? p : '';
  } catch {
    return '';
  }
}

/** Tempdoc 871 §3b — the `limit` the call explicitly carried (see {@link AgentSearchCardProjection.limit}). */
function limitFromArguments(argumentsJson: string | undefined): number | null {
  if (!argumentsJson) return null;
  try {
    const parsed = JSON.parse(argumentsJson) as unknown;
    const n = (parsed as Record<string, unknown> | null)?.['limit'];
    return typeof n === 'number' && Number.isFinite(n) && n > 0 ? n : null;
  } catch {
    return null;
  }
}

/**
 * The ONE search-evidence tool-card projection (tempdoc 867): every hit this call returned, joined
 * against the run's evidence-path set for {@link AgentSearchCardRow.inEvidence}. Returns `null` when
 * there is no evidence at all (no `searchResults`), or — the honest old-record edge case — when a
 * query cannot be recovered from either the new top-level key or the tool call's own arguments (never
 * fabricates a query text).
 */
export function agentSearchCardProjection(
  structuredData: unknown,
  argumentsJson: string | undefined,
  evidencePaths: ReadonlySet<string>,
): AgentSearchCardProjection | null {
  const raw = rawSearchResults(structuredData);
  if (raw === null) return null;
  const sd = structuredData as Record<string, unknown>;
  const query =
    typeof sd['query'] === 'string' && (sd['query'] as string).trim()
      ? (sd['query'] as string)
      : queryFromArguments(argumentsJson);
  if (query == null) return null;
  const hits: AgentSearchCardRow[] = raw.map((r) => {
    const hit = toAgentSearchHit(r);
    return { ...hit, inEvidence: evidencePaths.has(hit.path), locator: locatorOf(r) };
  });
  const resultCount = typeof sd['resultCount'] === 'number' ? (sd['resultCount'] as number) : hits.length;
  const evidenceCount = hits.reduce((n, h) => (h.inEvidence ? n + 1 : n), 0);
  // Tempdoc 867 §2a — the RESOLVED preset (`SearchTool.resolveEffectiveSearchMode`), read verbatim;
  // absent on a record persisted before the stamp, which is why this is '' (never re-derived from
  // the call's OWN `mode`/`pipeline` arguments — those name what the LLM asked for, not what ran).
  const mode = typeof sd['searchMode'] === 'string' ? (sd['searchMode'] as string) : '';
  return {
    query,
    scope: scopeFromArguments(argumentsJson),
    mode,
    limit: limitFromArguments(argumentsJson),
    hits,
    resultCount,
    evidenceCount,
  };
}

/** Look up one hit by id (== path) for the reading-pane open path (`card-open`). */
export function findAgentSearchHit(structuredData: unknown, id: string): AgentSearchHit | undefined {
  const raw = rawSearchResults(structuredData);
  if (raw === null) return undefined;
  return raw.map(toAgentSearchHit).find((h) => h.id === id);
}
