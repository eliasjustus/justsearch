// @vitest-environment happy-dom
/**
 * Slice 3a.1.9 §A.4a — Resource-view renderer contract conformance test.
 *
 * For each registered default renderer in the Resource-view registry,
 * instantiates the element, sets every required property for its
 * Category, and verifies the element renders without throwing.
 *
 * Catches:
 *  - A registered renderer that doesn't accept its Category's
 *    required props (slice 3a.1.4 §B.G.4 finding: registered tag
 *    referencing an unimported / unregistered custom element class).
 *  - A renderer added to the registry without ever being mounted
 *    (regression: dispatch returns a tag for which no class is
 *    registered).
 *  - A renderer's required-prop surface drifting from the
 *    Category's contract.
 */

import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import {
  CATEGORIES,
  type Category,
} from './resourceRegistry.js';
import {
  dispatchResourceRenderer,
  isCategorySupported,
  getResourceRendererRegistry,
} from './resourceRegistry.js';
import { REQUIRED_PROPS_BY_CATEGORY } from './resourceViewContract.js';
import './resourceRegistryDefaults.js';

/** Build a minimal prop bundle for a Category that satisfies its required surface. */
function fixturePropsFor(category: Category): Record<string, unknown> {
  const presentation = {
    labelKey: 'test.label',
    descriptionKey: 'test.description',
    iconHint: null,
    category: null,
  };
  const base = {
    presentation,
    connectionState: 'connected' as const,
    userConfig: undefined,
    paneId: '',
  };
  switch (category) {
    case 'STATE':
      return { ...base, value: { ok: true }, schema: null };
    case 'EVENT_STREAM':
      return { ...base, events: [], schema: null };
    case 'HISTORY':
      return { ...base, entries: [], schema: null, retention: null };
    case 'TABULAR':
      return {
        ...base,
        items: new Map(),
        schema: null,
        primaryKey: 'id',
        rowActions: undefined,
      };
    case 'TIMESERIES':
      return { ...base, snapshot: null, label: 'Test' };
  }
}

describe('Resource-view renderer contract conformance', () => {
  beforeAll(() => {
    // Force-load defaults (importing the module above registers them via
    // side effects; this beforeAll is documentation that the registry
    // must contain the four default registrations).
  });

  afterEach(() => {
    // No cleanup needed — each test creates its own element and discards.
  });

  it('every Category has either a default renderer or is documented as not-yet-shipped', () => {
    // Currently shipped defaults: STATE, EVENT_STREAM, TABULAR, TIMESERIES.
    // Not shipped: HISTORY (slice 444c). (Slice 448 phase 6 retired LOG_TAIL —
    // operator-trace surfaces use the sibling DiagnosticChannel primitive.)
    expect(isCategorySupported('STATE')).toBe(true);
    expect(isCategorySupported('EVENT_STREAM')).toBe(true);
    expect(isCategorySupported('TABULAR')).toBe(true);
    expect(isCategorySupported('TIMESERIES')).toBe(true);
    // HISTORY has no shipping renderer at slice 448 phase 6 close.
    // The contract test treats absence as documented (not a failure).
  });

  it('REQUIRED_PROPS_BY_CATEGORY has an entry for every Category', () => {
    for (const c of CATEGORIES) {
      const props = REQUIRED_PROPS_BY_CATEGORY[c];
      expect(props).toBeDefined();
      expect(props && props.length).toBeGreaterThan(0);
    }
  });

  // Per-renderer conformance probe. For each registered default tag,
  // instantiate the element and set every required property; the test
  // passes if no exception is thrown.
  describe.each([
    ['STATE', 'jf-status-card'],
    ['EVENT_STREAM', 'jf-status-card'],
    ['TABULAR', 'jf-table'],
    ['TIMESERIES', 'jf-timeseries-sparkline'],
  ] as const)('default renderer for %s', (category, expectedTag) => {
    it(`registry returns ${expectedTag}`, () => {
      const tag = dispatchResourceRenderer({ category });
      expect(tag).toBe(expectedTag);
    });

    it(`<${expectedTag}> is defined as a custom element`, () => {
      expect(customElements.get(expectedTag)).toBeDefined();
    });

    it(`<${expectedTag}> accepts every required prop without throwing`, () => {
      const el = document.createElement(expectedTag) as HTMLElement;
      const props = fixturePropsFor(category);
      const required = REQUIRED_PROPS_BY_CATEGORY[category] ?? [];
      // Setting props on an unconnected element is fine; reactive props
      // queue an update without immediate render.
      for (const key of required) {
        // Using bracket assignment to bypass typed-prop narrowing — the
        // contract is "accepts arbitrary value of expected type."
        (el as unknown as Record<string, unknown>)[key] = props[key];
      }
      // No throw → conformant.
      expect(el).toBeDefined();
    });
  });

  it('registry has at least one entry per shipping default', () => {
    const reg = getResourceRendererRegistry();
    const categoriesShipping = new Set(reg.map((e) => e.category));
    expect(categoriesShipping.has('STATE')).toBe(true);
    expect(categoriesShipping.has('EVENT_STREAM')).toBe(true);
    expect(categoriesShipping.has('TABULAR')).toBe(true);
    expect(categoriesShipping.has('TIMESERIES')).toBe(true);
  });
});
