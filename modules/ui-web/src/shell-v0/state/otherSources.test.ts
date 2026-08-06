// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 811 C-2a — the "Other sources" derivation.
 *
 * The Library listed watched roots only, so documents an agent ingested from a path under no root
 * were searchable, pill-labelled, countable in "Searching N documents" — and invisible on the one
 * surface that offers removal. These tests pin what the section may list: user-addressable non-root
 * collections, never the app-internal corpora, never a folder row's own collection, and never a
 * truncated scan reported as "none".
 */

import { describe, expect, it } from 'vitest';
import { deriveOtherSources, facetScanCapFor } from './otherSources.js';
import { UNKNOWN, known } from './known.js';

function facetResponse(
  counts: Record<string, unknown>,
  truncated = false,
): Record<string, unknown> {
  return { facets: { collection: counts }, facetsTruncated: truncated };
}

describe('deriveOtherSources — what the section may list (811 C-2a)', () => {
  it('lists each non-root collection with its document count, largest first', () => {
    const { sources } = deriveOtherSources(
      facetResponse({ 'mcp-ingest': 12, 'work-notes': 40 }),
      [],
    );
    expect(sources).toEqual([
      { collection: 'work-notes', docCount: 40 },
      { collection: 'mcp-ingest', docCount: 12 },
    ]);
  });

  it('never lists the reserved app-internal corpora or the untagged default bucket', () => {
    // `justsearch-help` really does come back from a default-scope facet (the help corpus is
    // searchable by design, UIX-015) — it is filtered here, not upstream.
    const { sources } = deriveOtherSources(
      facetResponse({
        'justsearch-help': 240,
        'agent-history': 88,
        default: 5000,
        'mcp-ingest': 3,
      }),
      [],
    );
    expect(sources.map((s) => s.collection)).toEqual(['mcp-ingest']);
  });

  it('never lists a collection a watched folder already owns (case-insensitively)', () => {
    // An ad-hoc ingest of a path UNDER a watched root inherits that root's collection
    // (IngestCollectionPolicy), so those documents belong to the folder row, not to this section.
    const { sources } = deriveOtherSources(
      facetResponse({ 'Work-Notes': 40, 'mcp-ingest': 3 }),
      ['work-notes', undefined, '  '],
    );
    expect(sources.map((s) => s.collection)).toEqual(['mcp-ingest']);
  });

  it('reports a truncated scan instead of swallowing it', () => {
    // FacetingEngine breaks out of the segment loop at the cap, so truncation OMITS collections
    // rather than undercounting them — reporting "none" here would be the invisibility bug again.
    const snapshot = deriveOtherSources(facetResponse({}, true), []);
    expect(snapshot.sources).toEqual([]);
    expect(snapshot.truncated).toBe(true);
  });

  it('treats an absent, empty or malformed facet payload as "no other sources", not a crash', () => {
    expect(deriveOtherSources(null, []).sources).toEqual([]);
    expect(deriveOtherSources({}, []).sources).toEqual([]);
    expect(deriveOtherSources({ facets: {} }, []).sources).toEqual([]);
    expect(deriveOtherSources(facetResponse({ 'mcp-ingest': 'twelve' }), []).sources).toEqual([]);
    expect(deriveOtherSources(facetResponse({ '  ': 4, 'zero-docs': 0 }), []).sources).toEqual([]);
    expect(deriveOtherSources(null, []).truncated).toBe(false);
  });
});

describe('facetScanCapFor — sizing the scan so it cannot omit (811 C-2a)', () => {
  it('scans past the observed document count', () => {
    expect(facetScanCapFor(known(400_000))).toBeGreaterThan(400_000);
  });

  it('falls back to the engine default when the count has not been observed', () => {
    expect(facetScanCapFor(UNKNOWN)).toBe(50_000);
    // A tiny index still gets the engine's own floor, never a cap below it.
    expect(facetScanCapFor(known(12))).toBe(50_000);
  });
});
