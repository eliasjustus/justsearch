/**
 * @vitest-environment happy-dom
 *
 * URLSource tests — slice 492 tier-1 substrate.
 *
 * Covers the boot-read + popstate listener mechanics. The source emits
 * Intent envelopes into the supplied dispatcher; parsing is delegated to
 * the pure `parseUrl` (covered by parser.test.ts).
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createURLSource } from './URLSource.js';
import type { DispatchOptions } from '../intentRouter.js';
import type { Intent } from '../types.js';

interface DispatchCall {
  intent: Intent;
  options?: DispatchOptions;
}

function makeDispatch(): {
  dispatch: (intent: Intent, options?: DispatchOptions) => void;
  calls: DispatchCall[];
} {
  const calls: DispatchCall[] = [];
  const dispatch = (intent: Intent, options?: DispatchOptions) => {
    calls.push({ intent, options });
  };
  return { dispatch, calls };
}

beforeEach(() => {
  window.location.hash = '';
});

afterEach(() => {
  window.location.hash = '';
});

describe('URLSource — boot read', () => {
  it('dispatches the parsed Intent when hash starts with #justsearch://', () => {
    window.location.hash = '#justsearch://surface/core.search-surface?query=hello';
    const source = createURLSource();
    const { dispatch, calls } = makeDispatch();
    source.start(dispatch);
    expect(calls).toHaveLength(1);
    const first = calls[0]!;
    expect(first.intent.transport).toBe('URL_BAR');
    expect(first.intent.address.kind).toBe('navigate');
    if (first.intent.address.kind === 'navigate') {
      expect(first.intent.address.target).toBe('core.search-surface');
      expect(first.intent.address.state).toEqual({ query: 'hello' });
    }
    expect(first.options).toEqual({ pushHistory: true });
  });

  it('emits nothing when no hash is present', () => {
    const source = createURLSource();
    const { dispatch, calls } = makeDispatch();
    source.start(dispatch);
    expect(calls).toEqual([]);
  });

  it('emits nothing when hash is unrelated (no justsearch:// prefix)', () => {
    window.location.hash = '#some-anchor';
    const source = createURLSource();
    const { dispatch, calls } = makeDispatch();
    source.start(dispatch);
    expect(calls).toEqual([]);
  });

  it('emits nothing on malformed justsearch URLs', () => {
    window.location.hash = '#justsearch://gibberish-with-no-segment';
    const source = createURLSource();
    const { dispatch, calls } = makeDispatch();
    source.start(dispatch);
    expect(calls).toEqual([]);
  });
});

describe('URLSource — popstate', () => {
  it('dispatches with pushHistory: false on popstate (browser already moved history)', () => {
    const source = createURLSource();
    const { dispatch, calls } = makeDispatch();
    const teardown = source.start(dispatch);
    // Now simulate a popstate with a new hash.
    window.location.hash = '#justsearch://surface/core.library-surface';
    window.dispatchEvent(new PopStateEvent('popstate'));
    expect(calls.length).toBeGreaterThan(0);
    const last = calls[calls.length - 1]!;
    expect(last.options).toEqual({ pushHistory: false });
    expect(last.intent.address.kind).toBe('navigate');
    if (typeof teardown === 'function') teardown();
  });

  it('teardown removes the popstate listener', () => {
    const source = createURLSource();
    const { dispatch, calls } = makeDispatch();
    const teardown = source.start(dispatch);
    expect(typeof teardown).toBe('function');
    if (typeof teardown === 'function') teardown();
    const before = calls.length;
    window.location.hash = '#justsearch://surface/core.library-surface';
    window.dispatchEvent(new PopStateEvent('popstate'));
    expect(calls.length).toBe(before);
  });
});

describe('URLSource — SSR safety', () => {
  it('returns a no-op teardown when no window is available', () => {
    const source = createURLSource({ windowImpl: undefined as unknown as Window });
    const { dispatch, calls } = makeDispatch();
    const teardown = source.start(dispatch);
    expect(calls).toEqual([]);
    expect(typeof teardown).toBe('function');
    if (typeof teardown === 'function') teardown();
  });
});
