// SPDX-License-Identifier: Apache-2.0
/**
 * Search-result projector — Tempdoc 543 §25.ζ#6 (closes §13.6 #6).
 *
 * First production projector. Projects an Addressable of kind
 * 'search-result' into flat-key facts that WhenExpressions + the
 * EvaluationContext layered projection consume.
 *
 * Closes §13.6 #6 "Per-resource UI fragmentation" from STRUCTURAL-
 * ENABLED to PRODUCTION-USED — the substrate ships with at least one
 * live projector AND a production consumer: SearchSurface.handleRowContextMenu
 * (§28.W2) opens the ContextMenu with an Addressable {kind: 'search-result'}
 * for every right-click on a search-result row. The projector fires
 * inside `listContextActions(context, payload, addressable)` →
 * `buildEvaluationContext({addressable})` so plugin-contributed
 * ContextActions can use when-clauses against the flat-key facts.
 *
 * Facts produced (per §13.2.1 confidence-pass naming convention —
 * flat keys with underscore prefix, never dotted paths):
 *   searchResult_id          - the hit id
 *   searchResult_title       - title text
 *   searchResult_path        - file path
 *   searchResult_hasSnippet  - boolean
 *   searchResult_score       - numeric score (0 if absent)
 *   searchResult_hasScore    - boolean
 *
 * Example when-clause that depends on these:
 *   when: 'searchResult_hasScore && searchResult_score > 0.5'
 *   when: 'searchResult_path =~ /\.md$/'   (renderer-engine permitting)
 */

import type { Addressable } from '../addressable.js';
import type { SearchHit } from '../../state/searchState.js';
import { registerProjector } from './index.js';

function isSearchHit(payload: unknown): payload is SearchHit {
  if (payload === null || typeof payload !== 'object') return false;
  const p = payload as Record<string, unknown>;
  return typeof p.id === 'string' && typeof p.title === 'string';
}

export function projectSearchResult(
  addressable: Addressable,
): Readonly<Record<string, unknown>> {
  if (!isSearchHit(addressable.payload)) {
    return {
      searchResult_id: addressable.id,
      searchResult_title: '',
      searchResult_path: '',
      searchResult_hasSnippet: false,
      searchResult_score: 0,
      searchResult_hasScore: false,
    };
  }
  const hit = addressable.payload;
  return {
    searchResult_id: hit.id,
    searchResult_title: hit.title,
    searchResult_path: hit.path,
    searchResult_hasSnippet:
      typeof hit.snippet === 'string' && hit.snippet.length > 0,
    searchResult_score: typeof hit.score === 'number' ? hit.score : 0,
    searchResult_hasScore: typeof hit.score === 'number',
  };
}

/**
 * One-time boot — register the projector. Idempotent: re-calling
 * replaces the prior registration with the same projector reference.
 * Shell.ts calls this at chrome boot.
 */
export function bootSearchResultProjector(): void {
  registerProjector('search-result', projectSearchResult);
}
