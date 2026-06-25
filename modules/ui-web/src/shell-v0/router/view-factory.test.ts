/**
 * @vitest-environment happy-dom
 *
 * Slice 491 §9.D Phase E (C0) — view-factory brand tests.
 *
 * Asserts the two-layer brand model (Symbol + WeakSet) holds against forgery
 * attempts. Mirrors the SurfaceFactory brand discipline (slice 478 §4.A).
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  __getViewBrandForTest,
  __isInValidViewFactoriesForTest,
  type ViewFactory,
  mountView,
  stampViewFactory,
} from './view-factory.js';

class FakeView extends HTMLElement {
  static observedAttributes = ['api-base'];
}

beforeEach(() => {
  if (!customElements.get('jf-fake-view-c0')) {
    customElements.define('jf-fake-view-c0', FakeView);
  }
});

afterEach(() => {
  // No-op — custom-element registry is global; reuse across tests.
});

describe('ViewFactory brand model', () => {
  it('stampViewFactory mints a factory with the brand symbol + WeakSet membership', () => {
    const factory = stampViewFactory('core.fake-shape', 'jf-fake-view-c0');
    expect(factory.shapeRef).toBe('core.fake-shape');
    expect(factory.__viewBrand).toBe(__getViewBrandForTest());
    expect(__isInValidViewFactoriesForTest(factory)).toBe(true);
  });

  it('mountView verifies the brand and returns a fresh HTMLElement', () => {
    const factory = stampViewFactory('core.fake-shape', 'jf-fake-view-c0');
    const el = mountView(factory);
    expect(el).toBeInstanceOf(HTMLElement);
    expect(el.tagName.toLowerCase()).toBe('jf-fake-view-c0');
  });

  it('mountView passes apiBase as attribute when provided', () => {
    const factory = stampViewFactory('core.fake-shape', 'jf-fake-view-c0');
    const el = mountView(factory, { apiBase: 'http://test' });
    expect(el.getAttribute('api-base')).toBe('http://test');
  });

  it('mountView rejects forged factory with stolen brand symbol (WeakSet check)', () => {
    const real = stampViewFactory('core.fake-shape', 'jf-fake-view-c0');
    const stolenBrand = real.__viewBrand;
    const forged = {
      __viewBrand: stolenBrand,
      shapeRef: 'core.evil',
      mount: () => document.createElement('div'),
    } as unknown as ViewFactory<string>;
    expect(() => mountView(forged)).toThrow(/not in the catalog WeakSet/);
  });

  it('mountView rejects factory with wrong brand symbol', () => {
    const wrongBrand = Symbol('wrong-brand');
    const forged = {
      __viewBrand: wrongBrand,
      shapeRef: 'core.fake-shape',
      mount: () => document.createElement('div'),
    } as unknown as ViewFactory<string>;
    expect(() => mountView(forged)).toThrow(/__viewBrand symbol mismatch/);
  });

  it('mountView throws if the custom element tag is not registered', () => {
    const factory = stampViewFactory('core.fake-shape', 'jf-unregistered-tag');
    expect(() => mountView(factory)).toThrow(/not registered/);
  });
});
