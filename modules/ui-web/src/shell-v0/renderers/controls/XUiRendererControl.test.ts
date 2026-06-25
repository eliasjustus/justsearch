// @vitest-environment happy-dom

/**
 * XUiRendererControl unit tests — Tempdoc 543 §13.3.1 Form primitive.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  registerXUiRenderer,
  unregisterXUiRenderer,
  getXUiRendererTag,
  listXUiRenderers,
  subscribeXUiRenderers,
  xUiRendererTester,
  __resetXUiRendererRegistryForTest,
} from './XUiRendererControl.js';

beforeEach(() => {
  __resetXUiRendererRegistryForTest();
});

describe('Hint-keyed renderer registry (§13.3.1)', () => {
  it('register/get/unregister round-trip', () => {
    registerXUiRenderer('corpus-picker', 'jf-corpus-picker');
    expect(getXUiRendererTag('corpus-picker')).toBe('jf-corpus-picker');
    expect(unregisterXUiRenderer('corpus-picker')).toBe(true);
    expect(getXUiRendererTag('corpus-picker')).toBeUndefined();
  });

  it('re-registering same hint replaces tag (HMR idempotency)', () => {
    registerXUiRenderer('h', 'jf-tag-1');
    registerXUiRenderer('h', 'jf-tag-2');
    expect(getXUiRendererTag('h')).toBe('jf-tag-2');
  });

  it('listXUiRenderers returns registered hints sorted', () => {
    registerXUiRenderer('zeta', 'jf-z');
    registerXUiRenderer('alpha', 'jf-a');
    expect(listXUiRenderers()).toEqual(['alpha', 'zeta']);
  });

  it('subscribeXUiRenderers fires on register/unregister', () => {
    const listener = vi.fn();
    subscribeXUiRenderers(listener);
    registerXUiRenderer('h', 'jf-tag');
    expect(listener).toHaveBeenCalledTimes(1);
    unregisterXUiRenderer('h');
    expect(listener).toHaveBeenCalledTimes(2);
  });

  it('lookup is case-sensitive', () => {
    registerXUiRenderer('corpus-picker', 'jf-corpus-picker');
    expect(getXUiRendererTag('Corpus-Picker')).toBeUndefined();
  });
});

describe('xUiRendererTester (§13.3.1)', () => {
  it('non-Control uischema → -1', () => {
    expect(
      xUiRendererTester(
        { type: 'VerticalLayout' } as unknown as Parameters<
          typeof xUiRendererTester
        >[0],
        { type: 'string', 'x-ui-renderer': 'corpus-picker' } as Parameters<
          typeof xUiRendererTester
        >[1],
      ),
    ).toBe(-1);
  });

  it('Control without x-ui-renderer → -1', () => {
    expect(
      xUiRendererTester(
        { type: 'Control' } as unknown as Parameters<
          typeof xUiRendererTester
        >[0],
        { type: 'string' },
      ),
    ).toBe(-1);
  });

  it('Control with x-ui-renderer hint → rank 100', () => {
    expect(
      xUiRendererTester(
        { type: 'Control' } as unknown as Parameters<
          typeof xUiRendererTester
        >[0],
        { type: 'string', 'x-ui-renderer': 'corpus-picker' } as Parameters<
          typeof xUiRendererTester
        >[1],
      ),
    ).toBe(100);
  });

  it('empty-string hint → -1 (defensive)', () => {
    expect(
      xUiRendererTester(
        { type: 'Control' } as unknown as Parameters<
          typeof xUiRendererTester
        >[0],
        { type: 'string', 'x-ui-renderer': '' } as unknown as Parameters<
          typeof xUiRendererTester
        >[1],
      ),
    ).toBe(-1);
  });
});
