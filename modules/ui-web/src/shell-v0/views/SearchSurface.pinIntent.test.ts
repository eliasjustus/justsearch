// @vitest-environment happy-dom

/**
 * 548 §4.5 — clicking a pinned-search chip must route the query through the intent pipeline
 * (the single authority for "the user asked to search"), not via a direct `setQuery` bypass.
 * Mirrors the S4-B palette collapse. Guards the regression where the chip re-introduced a
 * second search-initiation authority.
 */

import { describe, it, expect } from 'vitest';
import './SearchSurface.ts';
import { SearchSurface } from './SearchSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';

describe('SearchSurface — pinned-chip routes through the intent pipeline (548 §4.5)', () => {
  it('dispatches navigate-with-context for the pinned query, not a direct setQuery', () => {
    let directSetQuery: string | null = null;
    const filterCalls: Array<[number | undefined, number | undefined]> = [];
    const surface = document.createElement('jf-search-surface') as SearchSurface;
    surface.host_ = createMockHostApi({
      search: {
        setQuery: (q: string) => {
          directSetQuery = q;
        },
        setFilterRange: (from?: number, to?: number) => {
          filterCalls.push([from, to]);
        },
      },
    });

    const seen: Array<{ target: string; state: unknown }> = [];
    surface.addEventListener('navigate-with-context', (e) => {
      seen.push((e as CustomEvent).detail);
    });

    (
      surface as unknown as { handleChipClick: (p: unknown) => void }
    ).handleChipClick({ id: 'p1', query: 'rust ownership', filterSpec: { modifiedFromMs: 5 } });

    // The query travelled through the intent pipeline...
    expect(seen).toEqual([{ target: 'core.search-surface', state: { query: 'rust ownership' } }]);
    // ...and NOT through the direct setQuery search bypass.
    expect(directSetQuery).toBeNull();
    // Filter range (per-surface state, not the search-initiation authority) is still set directly.
    expect(filterCalls).toEqual([[5, undefined]]);
  });
});
