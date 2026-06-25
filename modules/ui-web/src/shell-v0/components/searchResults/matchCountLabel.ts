// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 597 — the truthful result-count label as a FUNNEL, not a single number. `matched` is
 * the TRUE matched-document total (the response's `matchCount`, ≥ every facet chip by
 * construction); `shown` is the rendered page (the surface shows one page, no load-more). When the
 * whole match set is on screen it collapses to "M matches"; otherwise it names both honestly so the
 * headline never implies browsability the surface lacks, and never contradicts the facets.
 *
 * §8.3 mode-aware label: pure-dense / ANN search has NO lexical predicate, so there is no match-set
 * — the only honest cardinality is the ranked window. `rankedOnly` (effectiveMode VECTOR) switches
 * the noun to "ranked" so the headline never claims documents "matched" when nothing was matched.
 * (Edge case for the UI, which always sends query text; reachable via the API / dense-only requests.)
 *
 * Tempdoc 597 R-1: extracted from `SearchSurface` into this shared helper so BOTH the dedicated
 * Search surface AND the Chat-surface retrieve tier project the SAME count label from the SAME
 * `searchState` — they cannot diverge by construction (the render single-authority goal; 602 R3).
 * `SearchSurface.matchCountLabel` is retained as a thin delegating static (preserves its tests).
 */
export function matchCountLabel(
  matched: number,
  shown: number,
  rankedOnly = false,
  ranked = 0,
  truncated = false,
): string {
  if (rankedOnly) {
    if (shown >= ranked || ranked <= 0) return `Top ${shown.toLocaleString()} ranked`;
    return `Top ${shown.toLocaleString()} of ${ranked.toLocaleString()} ranked`;
  }
  // §16.2 (M+): when the match scan hit its `maxDocsScanned` cap, `matched` is a lower bound, not
  // an exact count. Render "M+" (the Elasticsearch `track_total_hits` `gte` / Lucene
  // `TotalHits.Relation` convention) so the headline never presents a capped count as exact.
  const m = `${matched.toLocaleString()}${truncated ? '+' : ''}`;
  if (shown >= matched) return `${m} matches`;
  return `Top ${shown.toLocaleString()} of ${m} matches`;
}
