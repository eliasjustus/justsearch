// @vitest-environment happy-dom

import { describe, it, expect } from 'vitest';
import { deriveRichLabel } from './deriveRichLabel.js';

describe('deriveRichLabel', () => {
  it('returns surface title when no URL', () => {
    expect(deriveRichLabel('core.search-surface', '')).toBe('Search');
  });

  it('returns surface title when URL has no state key', () => {
    expect(deriveRichLabel('core.library-surface', 'justsearch://surface/core.library-surface')).toBe('Library');
  });

  it('appends query for search surface', () => {
    expect(deriveRichLabel('core.search-surface', 'justsearch://surface/core.search-surface?query=rust%20ownership')).toBe('Search: rust ownership');
  });

  it('truncates long queries', () => {
    const longQuery = 'a'.repeat(50);
    const label = deriveRichLabel('core.search-surface', `justsearch://surface/core.search-surface?query=${longQuery}`);
    expect(label).toBe(`Search: ${'a'.repeat(30)}...`);
  });

  it('returns title when query is empty', () => {
    expect(deriveRichLabel('core.search-surface', 'justsearch://surface/core.search-surface?query=')).toBe('Search');
  });

  it('appends query for ask surface', () => {
    expect(deriveRichLabel('core.ask-surface', 'justsearch://surface/core.ask-surface?query=what%20is%20rust')).toBe('Ask: what is rust');
  });

  it('returns fallback for unknown surface', () => {
    expect(deriveRichLabel('vendor.foo.bar-surface', 'justsearch://surface/vendor.foo.bar-surface')).toBe('Bar');
  });
});
