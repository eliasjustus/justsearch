// SPDX-License-Identifier: Apache-2.0
/**
 * otherSources.ts — tempdoc 811 C-2a (the held half): the sources in the index that are NOT a
 * watched folder.
 *
 * A document ingested through the MCP `justsearch_ingest` tool or `POST /api/knowledge/ingest`
 * from a path under no registered watched root is tagged with a collection
 * (`IngestCollectionPolicy.OUT_OF_ROOT` = `mcp-ingest`, or an explicitly requested one). Since #372
 * those documents are pill-labelled in results and since #380 they are removable via
 * `DELETE /api/indexing/collections` — but the Library listed only watched roots
 * (`/api/indexing-roots/substrate`), so the user could neither SEE what an agent had ingested nor
 * find the affordance that removes it. This module turns the enumeration response into the list the
 * Library's "Other sources" section renders.
 *
 * <h2>Why a search facet is the enumeration path</h2>
 *
 * There is no collection-enumeration endpoint: `IndexingRoutes` registers no list route beside the
 * DELETE, and `IndexStatusOps#countDefaultScopeDocs` explicitly defers the per-collection breakdown.
 * What DOES exist is the `facet` role `collection` was declared with in `fields.v1.json` — the
 * search wire accepts `facets: {include, maxDocsScanned, fields}` and answers with `facets` +
 * `facetsTruncated`, and `FacetingEngine` counts any keyword/docValues field over the WHOLE matched
 * set (not the returned page). So one facet request over a corpus-wide query enumerates the
 * collections, with counts. The request itself is issued by `fetchCollectionFacet` in
 * `searchState.ts` — the ONE `/api/knowledge/search` issuance site (the `search-issuance` gate);
 * this module only reads the answer.
 *
 * Two properties of that path shape what follows:
 *
 *  1. **The query cannot be blank.** `SearchPlanner.plan` short-circuits a blank query to
 *     `EmptyQueryDecision` before any facet is planned, so there is no "match everything" request
 *     shape on the wire — see `COLLECTION_FACET_QUERY` in `searchState.ts`. That is a wire-shape
 *     gap, not a design: a first-class `GET /api/indexing/collections` returning
 *     `{collection, docCount}[]` should replace this probe (logged as an observation).
 *  2. **Facet truncation omits, it does not undercount.** `FacetingEngine` breaks out of the
 *     segment loop once `maxDocsScanned` is exceeded, so a collection living only in a later
 *     segment disappears entirely rather than reporting a low count. Since this section exists to
 *     end an invisibility bug, the cap is sized from the observed document count
 *     ({@link facetScanCapFor}) and a truncated scan is RENDERED, never swallowed.
 *
 * The probe runs under the DEFAULT search scope, which already excludes `agent-history` and chunk
 * documents — so the counts are parent documents a default search can return, the same population
 * the removal route deletes from.
 */

import { COLLECTION_FACET_FIELD } from './searchState.js';
import { isKnown, type Maybe } from './known.js';

/** One non-root collection present in the index. */
export interface OtherSource {
  readonly collection: string;
  /** Parent (non-chunk) documents carrying this collection, as counted by the facet scan. */
  readonly docCount: number;
}

export interface OtherSourcesSnapshot {
  readonly sources: readonly OtherSource[];
  /**
   * The facet scan hit its cap, so `sources` may be MISSING collections entirely (see the header:
   * truncation omits rather than undercounts). Surfaced to the user; never treated as "none".
   */
  readonly truncated: boolean;
}

export const EMPTY_OTHER_SOURCES: OtherSourcesSnapshot = { sources: [], truncated: false };

/**
 * FE mirror of `IngestCollectionPolicy.RESERVED` — app-internal corpora. They are not listed here
 * because this section is the user's *addressable* sources: the DELETE route refuses both, and the
 * help corpus is bundled app content rather than something the user ingested.
 */
const RESERVED_COLLECTIONS: ReadonlySet<string> = new Set(['justsearch-help', 'agent-history']);

/** `IngestCollectionPolicy.DEFAULT_COLLECTION` — the untagged bucket, likewise not deletable. */
const DEFAULT_COLLECTION = 'default';

/** `FacetingEngine.DEFAULT_MAX_DOCS_SCANNED` — the floor when the doc count is unknown. */
const MIN_FACET_SCAN = 50_000;

/** Headroom over the last polled count: the status poll is up to ~10s old, so documents may have
 *  landed since. Cheap insurance against the omission failure mode. */
const SCAN_HEADROOM = 1_000;

/**
 * The facet scan cap for an index of this size. The probe runs under the default scope and excludes
 * chunks, so the scanned set is bounded by the default-scope document count; sizing the cap from it
 * keeps truncation (which omits whole collections) from happening at all.
 */
export function facetScanCapFor(defaultScopeDocuments: Maybe<number>): number {
  if (!isKnown(defaultScopeDocuments)) return MIN_FACET_SCAN;
  return Math.max(defaultScopeDocuments.value + SCAN_HEADROOM, MIN_FACET_SCAN);
}

interface FacetProbeResponse {
  facets?: Record<string, Record<string, unknown>> | null;
  facetsTruncated?: unknown;
}

/**
 * Reads the collection facet off a search response and subtracts everything that is not a
 * user-addressable non-root source: the untagged bucket, the reserved app-internal corpora, and any
 * collection a watched root owns (an ad-hoc ingest of a path UNDER a watched root inherits that
 * root's collection, so those documents belong to the folder row, not here).
 */
export function deriveOtherSources(
  payload: unknown,
  rootCollections: Iterable<string | undefined>,
): OtherSourcesSnapshot {
  const body = (payload ?? null) as FacetProbeResponse | null;
  const truncated = body?.facetsTruncated === true;
  const counts = body?.facets?.[COLLECTION_FACET_FIELD];
  if (!counts || typeof counts !== 'object') return { sources: [], truncated };

  const owned = new Set<string>();
  for (const raw of rootCollections) {
    const value = (raw ?? '').trim().toLowerCase();
    if (value) owned.add(value);
  }

  const sources: OtherSource[] = [];
  for (const [rawName, rawCount] of Object.entries(counts)) {
    const name = rawName.trim();
    if (!name) continue;
    const key = name.toLowerCase();
    if (key === DEFAULT_COLLECTION || RESERVED_COLLECTIONS.has(key) || owned.has(key)) continue;
    if (typeof rawCount !== 'number' || !Number.isFinite(rawCount)) continue;
    const docCount = Math.max(0, Math.trunc(rawCount));
    if (docCount <= 0) continue;
    sources.push({ collection: name, docCount });
  }
  sources.sort((a, b) => b.docCount - a.docCount || a.collection.localeCompare(b.collection));
  return { sources, truncated };
}
