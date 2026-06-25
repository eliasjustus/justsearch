// @vitest-environment happy-dom

/**
 * Tempdoc 609 (instance-retention, Phase B) — reconnect-safety lock.
 *
 * Under instance-retention the Stage REUSES a surface's element instance across navigation, so a
 * surface's `connectedCallback` can fire more than once on the same instance (connect → disconnect →
 * connect). Every subscription set up in connect must be released in disconnect so a reconnect
 * re-subscribes cleanly without leaking or double-firing. The Phase-B audit found ALL surfaces already
 * satisfy this (they were always unmounted on navigation, so symmetric teardown predates 609). This
 * test locks the invariant on a representative subscriber (BrowseSurface: subscribes `aiState` on
 * connect, releases + nulls on disconnect) so a future regression that drops a teardown is caught.
 */

import { describe, it, expect } from 'vitest';
import './BrowseSurface.ts';
import { BrowseSurface } from './BrowseSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';

describe('Surface reconnect-safety under instance-retention (tempdoc 609 Phase B)', () => {
  it('BrowseSurface re-subscribes on reconnect and releases on every disconnect', () => {
    const surface = document.createElement('jf-browse-surface') as BrowseSurface;
    surface.host_ = createMockHostApi();
    const aiUnsub = () => (surface as unknown as { aiUnsub: (() => void) | null }).aiUnsub;

    surface.connectedCallback();
    expect(typeof aiUnsub()).toBe('function'); // subscribed on first connect

    surface.disconnectedCallback();
    expect(aiUnsub()).toBeNull(); // released on disconnect

    // Reconnect of the SAME instance (the retention path) must re-subscribe, not throw or double up.
    expect(() => surface.connectedCallback()).not.toThrow();
    expect(typeof aiUnsub()).toBe('function');

    surface.disconnectedCallback();
    expect(aiUnsub()).toBeNull();
  });
});
