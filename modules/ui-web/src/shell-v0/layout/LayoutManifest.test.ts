// @vitest-environment happy-dom

/**
 * Tempdoc 508-followup §β3 — Layout zone wiring tests. Verifies that
 * the FOCUS_LAYOUT hides both rail and status deck (the layout-switching
 * change the §β3 plan made visibly distinct from default).
 *
 * Shell.isRailVisible / isStatusDeckVisible are private, so this test
 * exercises the public layout manifest contract — the zone semantics
 * the chrome reads.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import {
  DEFAULT_LAYOUT,
  FOCUS_LAYOUT,
  ZEN_LAYOUT,
  SPLIT_LAYOUT,
  initLayoutCatalog,
  getLayout,
  listLayouts,
  __resetLayoutCatalogForTest,
} from './LayoutManifest.js';

beforeEach(() => {
  __resetLayoutCatalogForTest();
  initLayoutCatalog();
});

describe('DEFAULT_LAYOUT', () => {
  it('rail is visible', () => {
    expect(DEFAULT_LAYOUT.zones.rail?.visible).not.toBe(false);
  });
  it('status bar is visible', () => {
    expect(DEFAULT_LAYOUT.zones.statusBar?.visible).not.toBe(false);
  });
  it('stage is exclusive', () => {
    expect(DEFAULT_LAYOUT.zones.stage?.exclusive).toBe(true);
  });
});

describe('FOCUS_LAYOUT (tempdoc 508-followup §β3)', () => {
  it('rail is hidden', () => {
    expect(FOCUS_LAYOUT.zones.rail?.visible).toBe(false);
  });
  it('status bar is hidden', () => {
    expect(FOCUS_LAYOUT.zones.statusBar?.visible).toBe(false);
  });
  it('stage is exclusive', () => {
    expect(FOCUS_LAYOUT.zones.stage?.exclusive).toBe(true);
  });
});

describe('ZEN_LAYOUT (tempdoc 521 §16.7)', () => {
  it('rail is hidden', () => {
    expect(ZEN_LAYOUT.zones.rail?.visible).toBe(false);
  });
  it('status bar is hidden', () => {
    expect(ZEN_LAYOUT.zones.statusBar?.visible).toBe(false);
  });
  it('stage is exclusive', () => {
    expect(ZEN_LAYOUT.zones.stage?.exclusive).toBe(true);
  });
  it('has a distinct id from FOCUS_LAYOUT', () => {
    expect(ZEN_LAYOUT.id).toBe('core.zen');
    expect(ZEN_LAYOUT.id).not.toBe(FOCUS_LAYOUT.id);
  });
});

describe('SPLIT_LAYOUT (tempdoc 521 §16.7 deeper)', () => {
  it('stage zone declares exclusive=false (split mode)', () => {
    expect(SPLIT_LAYOUT.zones.stage?.exclusive).toBe(false);
  });
  it('splitAxis defaults to horizontal', () => {
    expect(SPLIT_LAYOUT.zones.stage?.splitAxis).toBe('horizontal');
  });
  it('rail and status bar remain visible (only stage changes vs default)', () => {
    expect(SPLIT_LAYOUT.zones.rail?.visible).not.toBe(false);
    expect(SPLIT_LAYOUT.zones.statusBar?.visible).not.toBe(false);
  });
});

describe('initLayoutCatalog', () => {
  it('registers all four built-in layouts', () => {
    expect(getLayout('core.default')).toBe(DEFAULT_LAYOUT);
    expect(getLayout('core.focus')).toBe(FOCUS_LAYOUT);
    expect(getLayout('core.zen')).toBe(ZEN_LAYOUT);
    expect(getLayout('core.split')).toBe(SPLIT_LAYOUT);
  });
  it('listLayouts returns all four built-ins', () => {
    const ids = listLayouts().map((l) => l.id).sort();
    expect(ids).toEqual(['core.default', 'core.focus', 'core.split', 'core.zen']);
  });
});
