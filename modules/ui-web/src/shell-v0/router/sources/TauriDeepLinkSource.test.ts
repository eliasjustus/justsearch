/**
 * @vitest-environment happy-dom
 *
 * TauriDeepLinkSource tests — slice 492 tier-1 substrate.
 *
 * In non-Tauri (vite dev / vitest) environments the source's start()
 * is a no-op: it returns a no-op teardown without subscribing or
 * reading any cold-launch state. Tauri-runtime behavior (cold-launch
 * read + warm event subscription) is covered by the existing
 * `installDeepLinkBridge` test surface this source replaces.
 */

import { describe, expect, it } from 'vitest';
import { createTauriDeepLinkSource } from './TauriDeepLinkSource.js';
import type { DispatchOptions } from '../intentRouter.js';
import type { Intent } from '../types.js';

describe('TauriDeepLinkSource — non-Tauri environment', () => {
  it('returns a no-op teardown and dispatches nothing', async () => {
    const source = createTauriDeepLinkSource();
    const dispatched: Intent[] = [];
    const dispatch = (intent: Intent, _options?: DispatchOptions) => {
      dispatched.push(intent);
    };
    const teardown = await source.start(dispatch);
    expect(typeof teardown).toBe('function');
    if (typeof teardown === 'function') teardown();
    expect(dispatched).toEqual([]);
  });

  it('has the canonical Manifest-tier ref', () => {
    const source = createTauriDeepLinkSource();
    expect(source.ref).toBe('core.os-tauri-deeplink');
  });
});
