// @vitest-environment happy-dom

/**
 * Tempdoc 598 R1 (§34.1) — the glanceable retrieval-mode indicator.
 *
 * The ONE honest signal of WHICH retrieval actually ran, projected from the response's
 * effective mode (`searchTrace.effectiveMode`) — never a client-side guess. After the
 * capability-derived default lands it reads "Semantic + keyword" when the dense leg ran and
 * "Keyword" when the engine degraded to keyword, so the surface never silently presents
 * keyword results as semantic.
 *
 * Drives the private `renderRetrievalMode()` directly (mirrors SearchSurface.searchTrace.test —
 * happy-dom doesn't reliably run Lit's full render lifecycle), rendering the returned template
 * into a detached container to assert the surfaced text.
 */
import { describe, it, expect } from 'vitest';
import './SearchSurface.ts';
import { SearchSurface } from './SearchSurface.js';
import { nothing, render } from 'lit';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import type { SearchSnapshot } from '../plugin-api/plugin-types.js';

const HIT = { docId: 'a', score: 1, fields: {}, id: 'a', title: 'A', path: '/a' };

const base: SearchSnapshot = {
  query: 'x',
  results: [HIT],
  totalHits: 1,
  matchCount: 1,
  facetsTruncated: false,
  isSearching: false,
  processingTimeMs: 12,
  error: null,
};

function trace(effectiveMode: string) {
  return { version: 1, decisionKind: 'multi_leg', effectiveMode, stages: [] };
}

function surfaceWith(s: SearchSnapshot): SearchSurface {
  const el = document.createElement('jf-search-surface') as SearchSurface;
  el.host_ = createMockHostApi({});
  el.s = s;
  return el;
}

function renderMode(el: SearchSurface): unknown {
  return (el as unknown as { renderRetrievalMode: () => unknown }).renderRetrievalMode();
}

function modeText(s: SearchSnapshot): string {
  const tpl = renderMode(surfaceWith(s));
  if (tpl === nothing) return '';
  const div = document.createElement('div');
  render(tpl as never, div);
  return div.textContent ?? '';
}

describe('SearchSurface — retrieval-mode indicator (tempdoc 598 R1)', () => {
  it('reads HYBRID as "Semantic + keyword"', () => {
    expect(modeText({ ...base, searchTrace: trace('HYBRID') })).toContain('Semantic + keyword');
  });

  it('reads VECTOR as "Semantic"', () => {
    expect(modeText({ ...base, searchTrace: trace('VECTOR') })).toContain('Semantic');
  });

  it('reads TEXT as "Keyword" (honest keyword fallback, not semantic)', () => {
    const text = modeText({ ...base, searchTrace: trace('TEXT') });
    expect(text).toContain('Keyword');
    expect(text).not.toContain('Semantic');
  });

  it('renders nothing when no trace is present', () => {
    expect(renderMode(surfaceWith({ ...base }))).toBe(nothing);
  });
});
