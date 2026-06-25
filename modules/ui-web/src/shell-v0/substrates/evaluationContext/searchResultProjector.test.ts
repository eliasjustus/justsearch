// @vitest-environment happy-dom

/**
 * Tempdoc 543 §25.ζ#6 — search-result projector tests.
 */

import { beforeEach, describe, expect, it } from 'vitest';
import {
  projectSearchResult,
  bootSearchResultProjector,
} from './searchResultProjector.js';
import {
  buildEvaluationContext,
  getProjector,
  __resetProjectorRegistryForTest,
} from './index.js';

beforeEach(() => {
  __resetProjectorRegistryForTest();
});

describe('projectSearchResult (§25.ζ#6)', () => {
  it('projects a SearchHit into flat keys', () => {
    const facts = projectSearchResult({
      kind: 'search-result',
      id: 'hit-1',
      payload: {
        id: 'hit-1',
        title: 'Hello world',
        path: '/docs/intro.md',
        snippet: 'Hello there',
        score: 0.87,
      },
    });
    expect(facts.searchResult_id).toBe('hit-1');
    expect(facts.searchResult_title).toBe('Hello world');
    expect(facts.searchResult_path).toBe('/docs/intro.md');
    expect(facts.searchResult_hasSnippet).toBe(true);
    expect(facts.searchResult_score).toBe(0.87);
    expect(facts.searchResult_hasScore).toBe(true);
  });

  it('handles missing snippet + score gracefully', () => {
    const facts = projectSearchResult({
      kind: 'search-result',
      id: 'hit-2',
      payload: {
        id: 'hit-2',
        title: 'Minimal',
        path: '/x',
      },
    });
    expect(facts.searchResult_hasSnippet).toBe(false);
    expect(facts.searchResult_score).toBe(0);
    expect(facts.searchResult_hasScore).toBe(false);
  });

  it('handles malformed payload by returning safe defaults', () => {
    const facts = projectSearchResult({
      kind: 'search-result',
      id: 'hit-3',
      payload: null,
    });
    expect(facts.searchResult_id).toBe('hit-3');
    expect(facts.searchResult_title).toBe('');
    expect(facts.searchResult_path).toBe('');
  });
});

describe('bootSearchResultProjector + buildEvaluationContext integration', () => {
  it('boot registers the projector under the search-result kind', () => {
    bootSearchResultProjector();
    expect(getProjector('search-result')).toBeDefined();
  });

  it('buildEvaluationContext layers projected facts over Scope', () => {
    bootSearchResultProjector();
    const ctx = buildEvaluationContext({
      addressable: {
        kind: 'search-result',
        id: 'hit-7',
        payload: {
          id: 'hit-7',
          title: 'T',
          path: '/p',
          score: 0.5,
        },
      },
    });
    expect(ctx.searchResult_id).toBe('hit-7');
    expect(ctx.searchResult_score).toBe(0.5);
  });

  it('idempotent — re-calling boot is a no-op (replaces projector with same reference)', () => {
    bootSearchResultProjector();
    bootSearchResultProjector();
    bootSearchResultProjector();
    expect(getProjector('search-result')).toBeDefined();
  });
});
