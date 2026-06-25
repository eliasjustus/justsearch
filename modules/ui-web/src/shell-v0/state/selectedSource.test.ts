import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  getSelectedSource,
  setSelectedSource,
  subscribeSelectedSource,
  sourceKey,
  __resetSelectedSource,
} from './selectedSource.js';

afterEach(() => __resetSelectedSource());

describe('selectedSource store (565 §12.3.E cross-highlight)', () => {
  it('sourceKey composes a stable cross-surface identity from parentDocId + startLine', () => {
    // The same key the inline MarkdownCitation.detail and the rail AgentSource both produce.
    expect(sourceKey('docs/a.md', 42)).toBe('docs/a.md:42');
    expect(sourceKey('docs/a.md', 42)).toBe(sourceKey('docs/a.md', 42));
    expect(sourceKey('docs/a.md', 42)).not.toBe(sourceKey('docs/a.md', 7));
  });

  it('gets/sets the selection and notifies subscribers on change only', () => {
    const fn = vi.fn();
    const unsub = subscribeSelectedSource(fn);
    expect(getSelectedSource()).toBeNull();

    setSelectedSource('docs/a.md:42');
    expect(getSelectedSource()).toBe('docs/a.md:42');
    expect(fn).toHaveBeenCalledTimes(1);

    // No-op when unchanged — no spurious notify (avoids re-paint churn across the surfaces).
    setSelectedSource('docs/a.md:42');
    expect(fn).toHaveBeenCalledTimes(1);

    setSelectedSource(null);
    expect(getSelectedSource()).toBeNull();
    expect(fn).toHaveBeenCalledTimes(2);
    unsub();
  });

  it('stops notifying after unsubscribe', () => {
    const fn = vi.fn();
    const unsub = subscribeSelectedSource(fn);
    unsub();
    setSelectedSource('x:1');
    expect(fn).not.toHaveBeenCalled();
  });
});
