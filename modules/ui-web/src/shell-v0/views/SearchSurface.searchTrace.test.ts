// @vitest-environment happy-dom

/**
 * Tempdoc 549 (Phase D1) — query-level explain panel (G33).
 *
 * Asserts the gating of `SearchSurface.renderExplainPanel()`: the
 * `<jf-search-trace>` panel mounts only when a completed search carries the unified
 * `searchTrace`. Tests the private method directly (mirrors the multiSelect suite,
 * which drives `handleClick` directly — happy-dom doesn't reliably run Lit's
 * full render lifecycle).
 */

import { describe, it, expect } from 'vitest';
import './SearchSurface.ts';
import { SearchSurface } from './SearchSurface.js';
import { nothing } from 'lit';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import type { SearchSnapshot } from '../plugin-api/plugin-types.js';
import { traceChipsFor } from '../components/searchResults/whyThisResult.js';

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

function renderWhy(el: SearchSurface, hit: unknown): unknown {
  return (el as unknown as { renderWhy: (h: unknown) => unknown }).renderWhy(hit);
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

  // Tempdoc 549 Phase D1 — head-side + worker stages render inside <jf-search-trace>
  // from the single canonical searchTrace (the searchTraceExplain strategy renders the
  // full stage list). See searchTraceExplain.test.ts.

  // Tempdoc 549 (G111) — per-hit "Why this result?" disclosure.

  it('renders the Why disclosure from the unified per-hit trace (canonical path)', () => {
    const el = surfaceWith({ ...base, searchTrace: TRACE });
    const hit = {
      ...HIT,
      trace: [
        { id: 'sparse-retrieval', rank: 1, score: 5.5 },
        { id: 'cross-encoder', score: 0.1 },
      ],
    };
    expect(renderWhy(el, hit)).not.toBe(nothing);
  });

  it('omits the Why disclosure when the hit has no trace (provenance fallback retired in E2)', () => {
    const el = surfaceWith({ ...base, searchTrace: TRACE });
    const hit = { ...HIT, provenance: { bm25: { score: 1.5 } } };
    expect(renderWhy(el, hit)).toBe(nothing);
  });

  it('omits the Why disclosure when the hit has no provenance', () => {
    expect(renderWhy(surfaceWith({ ...base, searchTrace: TRACE }), { ...HIT })).toBe(nothing);
  });

  it('omits the Why disclosure when provenance has no recognized signals', () => {
    expect(renderWhy(surfaceWith({ ...base, searchTrace: TRACE }), { ...HIT, provenance: {} })).toBe(
      nothing,
    );
  });

  // Tempdoc 577 Phase 3 (Ext I) — the per-hit rationale is a user-tier grammar:
  // labeled chips from the one STAGE_LABELS vocabulary, separated values, worded
  // negative deltas — not the run-on `sparse-retrieval #2 3.32cross-encoder 0.34`.
  it('renders labeled, separated chips with worded negative deltas', () => {
    // Tempdoc 577 Goal 3 §3.9a — the chip mapping now lives in the shared
    // whyThisResult module (consumed by both SearchSurface and the retrieve tier).
    const chips = traceChipsFor({
      ...HIT,
      trace: [
        { id: 'sparse-retrieval', rank: 2, score: 3.32 },
        { id: 'cross-encoder', score: -0.2 },
      ],
    });
    expect(chips).toEqual(['Sparse (BM25) · #2 · 3.32', 'Cross-encoder · ranked down (-0.20)']);
  });
});

describe('SearchSurface.matchCountLabel (tempdoc 597 — funnel + mode-aware label)', () => {
  it('collapses to "M matches" when the whole match set is on screen', () => {
    expect(SearchSurface.matchCountLabel(8, 8)).toBe('8 matches');
    expect(SearchSurface.matchCountLabel(3, 50)).toBe('3 matches'); // shown >= matched
  });

  it('names both the shown slice and the matched total when not all are shown', () => {
    expect(SearchSurface.matchCountLabel(451, 50)).toBe('Top 50 of 451 matches');
  });

  it('§8.3 mode-aware: pure-dense (rankedOnly) uses "ranked", never "matches"', () => {
    // No lexical predicate ⇒ no match-set; the only honest cardinality is the ranked window.
    expect(SearchSurface.matchCountLabel(0, 50, true, 167)).toBe('Top 50 of 167 ranked');
    expect(SearchSurface.matchCountLabel(0, 50, true, 50)).toBe('Top 50 ranked'); // shown >= ranked
    expect(SearchSurface.matchCountLabel(0, 12, true, 0)).toBe('Top 12 ranked'); // unknown window
  });

  it('§16.2 (M+): renders the matched total as a lower bound when the scan was truncated', () => {
    // matched is `maxDocsScanned` (a floor), not exact ⇒ "M+", the ES `gte` / Lucene relation shape.
    // (Use a sub-1000 total so the assertion is locale-independent — `toLocaleString` separators vary.)
    expect(SearchSurface.matchCountLabel(200, 50, false, 0, true)).toBe('Top 50 of 200+ matches');
    // collapsed branch (shown >= matched) still carries the "+": the count is still a lower bound.
    expect(SearchSurface.matchCountLabel(8, 50, false, 0, true)).toBe('8+ matches');
    // not truncated ⇒ no "+", unchanged behavior.
    expect(SearchSurface.matchCountLabel(451, 50, false, 0, false)).toBe('Top 50 of 451 matches');
  });
});
