// @vitest-environment happy-dom

import { describe, it, expect } from 'vitest';
import { deriveRichLabel } from './deriveRichLabel.js';

describe('deriveRichLabel', () => {
  // Search Thread S5b — `core.search-surface` is retired (folded into `core.unified-chat-surface`,
  // which owns the retrieve tier and already carried a `query` state-key mapping of its own). These
  // cases port the append/truncate/empty-query enrichment coverage onto that surviving id. Note:
  // `deriveTitleFromSurfaceId` derives its BARE title purely from the id's own hyphenated shape
  // ("unified-chat" → "Unified Chat"), a separate structural fallback from the catalog's displayed
  // "Search" label (item 5's naming authority) — the two can read differently in this rare fallback
  // path (no catalog label at hand); tracked as a follow-up, not fixed here.
  it('returns surface title when no URL', () => {
    expect(deriveRichLabel('core.unified-chat-surface', '')).toBe('Unified Chat');
  });

  it('returns surface title when URL has no state key', () => {
    expect(deriveRichLabel('core.library-surface', 'justsearch://surface/core.library-surface')).toBe('Library');
  });

  it('appends query for the unified chat surface', () => {
    expect(deriveRichLabel('core.unified-chat-surface', 'justsearch://surface/core.unified-chat-surface?query=rust%20ownership')).toBe('Unified Chat: rust ownership');
  });

  it('truncates long queries', () => {
    const longQuery = 'a'.repeat(50);
    const label = deriveRichLabel('core.unified-chat-surface', `justsearch://surface/core.unified-chat-surface?query=${longQuery}`);
    expect(label).toBe(`Unified Chat: ${'a'.repeat(30)}...`);
  });

  it('returns title when query is empty', () => {
    expect(deriveRichLabel('core.unified-chat-surface', 'justsearch://surface/core.unified-chat-surface?query=')).toBe('Unified Chat');
  });

  it('appends query for ask surface', () => {
    expect(deriveRichLabel('core.ask-surface', 'justsearch://surface/core.ask-surface?query=what%20is%20rust')).toBe('Ask: what is rust');
  });

  it('returns fallback for unknown surface', () => {
    expect(deriveRichLabel('vendor.foo.bar-surface', 'justsearch://surface/vendor.foo.bar-surface')).toBe('Bar');
  });
});
