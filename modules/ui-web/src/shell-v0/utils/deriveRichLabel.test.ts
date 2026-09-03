// @vitest-environment happy-dom

import { describe, it, expect } from 'vitest';
import { deriveRichLabel } from './deriveRichLabel.js';

describe('deriveRichLabel', () => {
  // Search Thread S5b — `core.search-surface` is retired (folded into `core.unified-chat-surface`,
  // which owns the retrieve tier and already carried a `query` state-key mapping of its own). These
  // cases port the append/truncate/empty-query enrichment coverage onto that surviving id. The
  // surface title is supplied by the presentation authority, so enrichment cannot revive an old
  // id-derived name after the catalog renames a place.
  it('returns surface title when no URL', () => {
    expect(deriveRichLabel('core.unified-chat-surface', '', 'Search')).toBe('Search');
  });

  it('returns surface title when URL has no state key', () => {
    expect(deriveRichLabel('core.library-surface', 'justsearch://surface/core.library-surface', 'Library')).toBe('Library');
  });

  it('appends query for the unified chat surface', () => {
    expect(deriveRichLabel('core.unified-chat-surface', 'justsearch://surface/core.unified-chat-surface?query=rust%20ownership', 'Search')).toBe('Search: rust ownership');
  });

  it('truncates long queries', () => {
    const longQuery = 'a'.repeat(50);
    const label = deriveRichLabel('core.unified-chat-surface', `justsearch://surface/core.unified-chat-surface?query=${longQuery}`, 'Search');
    expect(label).toBe(`Search: ${'a'.repeat(30)}...`);
  });

  it('returns title when query is empty', () => {
    expect(deriveRichLabel('core.unified-chat-surface', 'justsearch://surface/core.unified-chat-surface?query=', 'Search')).toBe('Search');
  });

  it('appends query for ask surface', () => {
    expect(deriveRichLabel('core.ask-surface', 'justsearch://surface/core.ask-surface?query=what%20is%20rust', 'Ask')).toBe('Ask: what is rust');
  });

  it('returns fallback for unknown surface', () => {
    expect(deriveRichLabel('vendor.foo.bar-surface', 'justsearch://surface/vendor.foo.bar-surface', 'Bar')).toBe('Bar');
  });
});
