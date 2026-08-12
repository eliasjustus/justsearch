// SPDX-License-Identifier: Apache-2.0
/**
 * The Search v3 window's read of the SHARED search store (tempdoc 822 Phase A1).
 *
 * The charter's guardrail is "from-scratch components, shared authorities": this window authors its
 * own presentation and NO search client. Every query it runs goes through `state/searchState.ts` —
 * the same store the shipped windows read (`views/search-v2/SearchV2View.ts:105-115`,
 * `views/UnifiedChatView.ts:936-944`) — and this module is the read half: a store snapshot in, a
 * rendering-ready view out.
 *
 * It reads the RESPONSE half of the snapshot only (`results`, `matchCount`, `totalHits`,
 * `facetsTruncated`, `isSearching`, `error`) and deliberately not `searchTrace`. A trace referencer
 * would make this window a registered execution surface (`governance/execution-surfaces.v1.json`),
 * which is a decision about explain-tier work — not something plain search should trigger on its way
 * past.
 *
 * FOUR OUTCOMES, ALL DISTINCT. A window that renders "nothing matched" when the request never
 * reached the backend is making a claim about the corpus it has no evidence for; `unreachable` and
 * `empty` are therefore separate states with separate copy, and the tests assert they differ.
 */
import type { SearchHit, SearchState } from '../../state/searchState.js';

/**
 * `idle` — nothing has been asked in THIS window yet (the store is a process-wide singleton, so a
 * search another surface ran is not this window's to render).
 */
export type Sv3ResultsStatus = 'idle' | 'loading' | 'ready' | 'empty' | 'unreachable';

/**
 * The row as this window renders it. Phase A1 keeps the slice-1 markup — a title and a trailing
 * path — so the wiring can be judged on its own; the donor's 78px card variant is Phase B's subject.
 */
export interface Sv3ResultRow {
  readonly id: string;
  readonly title: string;
  readonly path: string;
}

export interface Sv3ResultsView {
  readonly status: Sv3ResultsStatus;
  readonly rows: readonly Sv3ResultRow[];
  /** The TRUE matched-document total (`SearchState.matchCount`) — the M in "Top N of M matches". */
  readonly matched: number;
  /** The bounded ranked window (`SearchState.totalHits`), which the label needs for its dense mode. */
  readonly ranked: number;
  /** The match scan hit its cap, so `matched` is a lower bound and the label renders "M+". */
  readonly truncated: boolean;
  /** The store's own failure text (`HTTP 502`, `Failed to fetch`) — shown as detail, never as prose. */
  readonly failure: string;
}

export const SV3_RESULTS_IDLE: Sv3ResultsView = {
  status: 'idle',
  rows: [],
  matched: 0,
  ranked: 0,
  truncated: false,
  failure: '',
};

/**
 * `SearchHit` already carries the display identity the store derived from the response's
 * `fields.path` (`state/searchState.ts:652-673` — the filename as title, the chunk-id scheme
 * suppressed). This is a projection of those fields, not a second derivation of them.
 */
const toRow = (hit: SearchHit): Sv3ResultRow => ({
  id: hit.id,
  title: hit.title,
  path: hit.path,
});

/**
 * @param snapshot the latest store emission, or null before the first one arrives
 * @param asked whether THIS window has issued a search (see `idle` above)
 */
export function projectSv3Results(snapshot: SearchState | null, asked: boolean): Sv3ResultsView {
  if (!asked || snapshot === null) return SV3_RESULTS_IDLE;
  // In flight beats every stale conclusion: the store clears `error` when a pass starts, so an
  // in-flight pass can never be reported as the previous pass's failure.
  if (snapshot.isSearching === true) return { ...SV3_RESULTS_IDLE, status: 'loading' };
  if (typeof snapshot.error === 'string' && snapshot.error !== '') {
    return { ...SV3_RESULTS_IDLE, status: 'unreachable', failure: snapshot.error };
  }
  const rows = snapshot.results.map(toRow);
  return {
    status: rows.length === 0 ? 'empty' : 'ready',
    rows,
    matched: snapshot.matchCount,
    ranked: snapshot.totalHits,
    truncated: snapshot.facetsTruncated,
    failure: '',
  };
}
