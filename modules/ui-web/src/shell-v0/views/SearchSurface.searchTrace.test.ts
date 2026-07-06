// @vitest-environment happy-dom

/**
 * Tempdoc 549 (Phase D1) — query-level explain panel (G33).
 *
 * Asserts the gating of `SearchSurface.renderExplainPanel()`: the
 * `<jf-search-trace>` panel mounts only when a completed search carries the unified
 * `searchTrace`. Tests the private method directly (mirrors the multiSelect suite,
 * which drove `handleClick` directly — happy-dom doesn't reliably run Lit's
 * full render lifecycle for private-method-only assertions).
 *
 * Search Thread S1 rework: the per-hit "Why this result?" disclosure assertions
 * and the `traceChipsFor` chip-formatting test moved to
 * ResultsCard.searchTrace.test.ts — `SearchSurface.renderWhy` no longer exists;
 * ResultsCard.renderRow inlines the shared `renderWhyDisclosure` directly. The
 * `matchCountLabel` describe block moved to the dedicated
 * `components/searchResults/matchCountLabel.test.ts` (testing the shared helper
 * directly rather than SearchSurface's thin delegating static).
 *
 * SearchSurface's OWN query-level explain panel (this file's actual subject) is a
 * DIFFERENT feature, untouched by Search Thread S1, and stays covered below,
 * unchanged. A slim wiring check confirms the surface's snapshot (searchTrace
 * included) reaches the mounted `jf-results-card`.
 */

import { describe, it, expect } from 'vitest';
import './SearchSurface.js';
import { SearchSurface } from './SearchSurface.js';
import { nothing } from 'lit';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import type { SearchSnapshot } from '../plugin-api/plugin-types.js';

const HIT = { docId: 'a', score: 1, fields: {}, id: 'a', title: 'A', path: '/a' };
const TRACE = {
  version: 1,
  decisionKind: 'sparse_shortcut',
  effectiveMode: 'TEXT',
  stages: [{ id: 'sparse-retrieval', status: 'executed' }],
};

function surfaceWith(s: SearchSnapshot): SearchSurface {
  const el = document.createElement('jf-search-surface') as SearchSurface;
  el.host_ = createMockHostApi({});
  el.s = s;
  return el;
}

function renderPanel(el: SearchSurface): unknown {
  return (el as unknown as { renderExplainPanel: () => unknown }).renderExplainPanel();
}

const base: SearchSnapshot = {
  query: 'x',
  results: [HIT],
  totalHits: 1,
  matchCount: 1,
  facetsTruncated: false,
  isSearching: false,
  processingTimeMs: null,
  error: null,
};

describe('SearchSurface explain panel (tempdoc 549 Slice 1, G33)', () => {
  it('renders the panel when a completed search carries a trace', () => {
    expect(renderPanel(surfaceWith({ ...base, searchTrace: TRACE }))).not.toBe(nothing);
  });

  it('omits the panel when the response carried no trace', () => {
    expect(renderPanel(surfaceWith({ ...base }))).toBe(nothing);
  });

  it('omits the panel while a search is in flight', () => {
    expect(renderPanel(surfaceWith({ ...base, isSearching: true, searchTrace: TRACE }))).toBe(
      nothing,
    );
  });

  it('omits the panel when there are zero results', () => {
    expect(
      renderPanel(surfaceWith({ ...base, results: [], totalHits: 0, searchTrace: TRACE })),
    ).toBe(nothing);
  });
});

describe('SearchSurface → jf-results-card wiring (Search Thread S1)', () => {
  it('passes the searchTrace-bearing snapshot through to the mounted card', async () => {
    const el = document.createElement('jf-search-surface') as SearchSurface;
    el.host_ = createMockHostApi({});
    document.body.appendChild(el);
    el.s = { ...base, searchTrace: TRACE };
    await el.updateComplete;

    const card = el.shadowRoot?.querySelector('jf-results-card') as unknown as {
      snapshot: SearchSnapshot;
    } | null;
    expect(card).toBeTruthy();
    expect(card!.snapshot).toBe(el.s);
    expect(card!.snapshot.searchTrace).toBe(TRACE);
  });
});
