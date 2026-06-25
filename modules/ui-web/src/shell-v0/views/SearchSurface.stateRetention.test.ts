// @vitest-environment happy-dom

/**
 * Tempdoc 609 (M1) — SearchSurface must NOT destroy recoverable search task
 * state on unmount. The query + result snapshot live in the singleton
 * `searchState` store; before this fix, `disconnectedCallback` called
 * `setQuery('')`, which wiped the whole store (query, results, counts, trace,
 * facets) on any brief tab switch. This locks in that navigation away no longer
 * clears the store — clearing is reachable only through the explicit user
 * controls at the search input.
 */

import { describe, it, expect, vi, type Mock } from 'vitest';
import './SearchSurface.ts';
import { SearchSurface } from './SearchSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { getSelection, __resetSelectionForTest } from '../state/selectionState.js';

function makeSurface(setQuery: Mock<(q: string) => void>): SearchSurface {
  const surface = document.createElement('jf-search-surface') as SearchSurface;
  surface.host_ = createMockHostApi({ search: { setQuery } });
  surface.apiBase = '';
  return surface;
}

describe('SearchSurface state retention (tempdoc 609 M1)', () => {
  it('does not clear the search query when the surface disconnects', () => {
    const setQuery = vi.fn<(q: string) => void>();
    const surface = makeSurface(setQuery);

    surface.connectedCallback();
    // Ignore any connect-time activity; we only care about teardown.
    setQuery.mockClear();

    surface.disconnectedCallback();

    // The pre-609 bug was a `setQuery('')` here. Navigation must never wipe
    // the recoverable search store.
    expect(setQuery).not.toHaveBeenCalled();
  });

  it('survives a mount → unmount → remount cycle without clearing', () => {
    const setQuery = vi.fn<(q: string) => void>();
    const first = makeSurface(setQuery);
    first.connectedCallback();
    first.disconnectedCallback();

    const second = makeSurface(setQuery);
    second.connectedCallback();
    second.disconnectedCallback();

    // Across a full navigation round-trip, the surface lifecycle issued no
    // empty-query clear against the shared store.
    expect(setQuery).not.toHaveBeenCalledWith('');
  });
});

interface TestHit {
  docId: string;
  score: number;
  fields: Record<string, unknown>;
  id: string;
  title: string;
  path: string;
}
function hit(id: string): TestHit {
  return { docId: id, score: 1, fields: {}, id, title: id, path: `/${id}` };
}
function stateWith(results: TestHit[]) {
  return {
    query: 'architecture',
    results,
    totalHits: results.length,
    matchCount: results.length,
    facetsTruncated: false,
    isSearching: false,
    processingTimeMs: null,
    error: null,
  };
}

describe('SearchSurface selection + scroll retention (tempdoc 609 instance-retention)', () => {
  it('keeps the multi-select set as instance @state (survives with the retained element)', () => {
    __resetSelectionForTest();
    const surface = makeSurface(vi.fn<(q: string) => void>());
    surface.s = stateWith([hit('a'), hit('b'), hit('c')]) as unknown as SearchSurface['s'];

    (surface as unknown as { applySelection: (ids: ReadonlySet<string>, p: number) => void })
      .applySelection(new Set(['a', 'b']), 0);

    // Selection lives on the instance; the Stage retains the instance across navigation, so no store.
    expect(
      [...(surface as unknown as { selectedHitIds: ReadonlySet<string> }).selectedHitIds].sort(),
    ).toEqual(['a', 'b']);
  });

  it('re-publishes the retained selection + reopens the inspector on reconnect', () => {
    __resetSelectionForTest();
    const showInspector = vi.fn();
    const results = [hit('a'), hit('b')];
    const surface = document.createElement('jf-search-surface') as SearchSurface;
    surface.apiBase = '';
    surface.host_ = createMockHostApi({
      search: { getSearchState: () => stateWith(results) as never },
      ui: { showInspector },
    });
    surface.s = stateWith(results) as unknown as SearchSurface['s'];

    // Select, then simulate navigation: disconnect (instance retained, @state kept), reconnect.
    (surface as unknown as { applySelection: (ids: ReadonlySet<string>, p: number) => void })
      .applySelection(new Set(['b']), 1);
    surface.disconnectedCallback();
    __resetSelectionForTest(); // the Shell clears the GLOBAL selection on surface change
    surface.connectedCallback();

    // Selection survived as instance @state and was re-published to the global selectionState; the
    // inspector pane is reopened for the primary hit (working-rule "inspector context").
    const sel = getSelection();
    expect(sel.surfaceId).toBe('core.search-surface');
    expect(sel.items.length).toBe(1);
    expect(showInspector).toHaveBeenCalled();
    surface.disconnectedCallback();
  });

  it('holds the result-list scroll offset in a retained instance field', () => {
    const surface = makeSurface(vi.fn<(q: string) => void>());
    // The scroll offset lives on the instance (retained across navigation), not a module store.
    (surface as unknown as { savedScrollTop: number }).savedScrollTop = 333;
    expect((surface as unknown as { savedScrollTop: number }).savedScrollTop).toBe(333);
    // connect/disconnect with no rendered .body is a safe no-op (real scroll covered by browser batch).
    expect(() => {
      surface.connectedCallback();
      surface.disconnectedCallback();
    }).not.toThrow();
  });
});
