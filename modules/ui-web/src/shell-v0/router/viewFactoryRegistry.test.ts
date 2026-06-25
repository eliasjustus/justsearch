/**
 * @vitest-environment happy-dom
 *
 * Slice 491 §9.D Phase E (C0) — viewFactoryRegistry tests.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  __resetViewFactoryRegistryForTest,
  getViewFactory,
  listViewFactories,
  onViewFactoryRegistryChange,
  registerViewFactory,
  unregisterViewFactory,
} from './viewFactoryRegistry.js';

class FakeView extends HTMLElement {}

beforeEach(() => {
  __resetViewFactoryRegistryForTest();
  if (!customElements.get('jf-fake-view-c0-registry')) {
    customElements.define('jf-fake-view-c0-registry', FakeView);
  }
});

afterEach(() => {
  __resetViewFactoryRegistryForTest();
});

describe('viewFactoryRegistry', () => {
  it('registers + retrieves a view factory by shape ref', () => {
    registerViewFactory('core.fake-shape', 'jf-fake-view-c0-registry');
    const factory = getViewFactory('core.fake-shape');
    expect(factory).toBeDefined();
    expect(factory?.shapeRef).toBe('core.fake-shape');
  });

  it('returns undefined for unregistered shape', () => {
    expect(getViewFactory('core.unknown')).toBeUndefined();
  });

  it('listViewFactories returns all registered pairs', () => {
    registerViewFactory('core.a', 'jf-fake-view-c0-registry');
    registerViewFactory('core.b', 'jf-fake-view-c0-registry');
    const refs = listViewFactories()
      .map(([ref]) => ref)
      .sort();
    expect(refs).toEqual(['core.a', 'core.b']);
  });

  it('re-registering the same shape replaces the prior factory', () => {
    const first = registerViewFactory('core.fake-shape', 'jf-fake-view-c0-registry');
    const second = registerViewFactory('core.fake-shape', 'jf-fake-view-c0-registry');
    expect(getViewFactory('core.fake-shape')).toBe(second);
    expect(getViewFactory('core.fake-shape')).not.toBe(first);
  });

  it('fires listeners on register', () => {
    const fired: number[] = [];
    onViewFactoryRegistryChange(() => fired.push(Date.now()));
    registerViewFactory('core.fake-shape', 'jf-fake-view-c0-registry');
    expect(fired.length).toBe(1);
  });

  it('unsubscribe stops further notifications', () => {
    const fired: number[] = [];
    const unsub = onViewFactoryRegistryChange(() => fired.push(Date.now()));
    registerViewFactory('core.a', 'jf-fake-view-c0-registry');
    expect(fired.length).toBe(1);
    unsub();
    registerViewFactory('core.b', 'jf-fake-view-c0-registry');
    expect(fired.length).toBe(1);
  });

  // ── Ownership + teardown (tempdoc 560 §28.G) ────────────────────────────────────────────────────

  it('refuses a register by a DIFFERENT owner — the incumbent factory is preserved (no view hijack)', () => {
    const victim = registerViewFactory(
      'vendor.alpha.shape',
      'jf-fake-view-c0-registry',
      'vendor.alpha',
    );
    const returned = registerViewFactory(
      'vendor.alpha.shape',
      'jf-fake-view-c0-registry',
      'vendor.evil',
    );
    // The colliding register returns the incumbent and does NOT replace it.
    expect(returned).toBe(victim);
    expect(getViewFactory('vendor.alpha.shape')).toBe(victim);
  });

  it('allows the SAME owner to re-register (HMR / approve→reload replace)', () => {
    const first = registerViewFactory('vendor.alpha.shape', 'jf-fake-view-c0-registry', 'vendor.alpha');
    const second = registerViewFactory(
      'vendor.alpha.shape',
      'jf-fake-view-c0-registry',
      'vendor.alpha',
    );
    expect(second).not.toBe(first);
    expect(getViewFactory('vendor.alpha.shape')).toBe(second);
  });

  it('unregisterViewFactory withdraws the factory (ownership-checked) and notifies', () => {
    const fired: number[] = [];
    registerViewFactory('vendor.alpha.shape', 'jf-fake-view-c0-registry', 'vendor.alpha');
    onViewFactoryRegistryChange(() => fired.push(Date.now()));
    // A non-owner cannot withdraw it.
    expect(unregisterViewFactory('vendor.alpha.shape', 'vendor.evil')).toBe(false);
    expect(getViewFactory('vendor.alpha.shape')).toBeDefined();
    expect(fired.length).toBe(0);
    // The owner can.
    expect(unregisterViewFactory('vendor.alpha.shape', 'vendor.alpha')).toBe(true);
    expect(getViewFactory('vendor.alpha.shape')).toBeUndefined();
    expect(fired.length).toBe(1);
    // After withdrawal the shape id is free for a new owner.
    registerViewFactory('vendor.alpha.shape', 'jf-fake-view-c0-registry', 'vendor.beta');
    expect(getViewFactory('vendor.alpha.shape')).toBeDefined();
  });
});
