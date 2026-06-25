// @vitest-environment happy-dom

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  getSavedViews,
  subscribeSavedViews,
  saveView,
  removeView,
  isViewSaved,
  renameView,
} from './savedViewState.js';
import { __resetUserStateForTest } from './UserStateDocument.js';

describe('savedViewState', () => {
  beforeEach(() => {
    __resetUserStateForTest();
  });

  it('returns empty list on first read', () => {
    expect(getSavedViews()).toEqual([]);
  });

  it('saveView creates a new entry', () => {
    const view = saveView('Search', 'justsearch://surface/core.search-surface?query=foo', 'core.search-surface');
    expect(view).not.toBeNull();
    expect(view!.label).toBe('Search');
    expect(view!.url).toBe('justsearch://surface/core.search-surface?query=foo');
    expect(view!.surfaceId).toBe('core.search-surface');
    expect(typeof view!.savedAt).toBe('number');
    expect(getSavedViews()).toHaveLength(1);
  });

  it('saveView de-dupes by URL', () => {
    const first = saveView('Search 1', 'justsearch://surface/core.search-surface?query=foo', 'core.search-surface');
    const second = saveView('Search 2', 'justsearch://surface/core.search-surface?query=foo', 'core.search-surface');
    expect(first!.id).toBe(second!.id);
    expect(getSavedViews()).toHaveLength(1);
  });

  it('saveView returns null for empty url', () => {
    expect(saveView('Label', '', 'core.search-surface')).toBeNull();
  });

  it('removeView removes by id', () => {
    const view = saveView('Search', 'url1', 'core.search-surface');
    expect(getSavedViews()).toHaveLength(1);
    const removed = removeView(view!.id);
    expect(removed).toBe(true);
    expect(getSavedViews()).toHaveLength(0);
  });

  it('removeView returns false for unknown id', () => {
    expect(removeView('nonexistent')).toBe(false);
  });

  it('isViewSaved returns true for saved URL', () => {
    saveView('Search', 'url1', 'core.search-surface');
    expect(isViewSaved('url1')).toBe(true);
    expect(isViewSaved('url2')).toBe(false);
  });

  it('renameView updates the label', () => {
    const view = saveView('Old Name', 'url1', 'core.search-surface');
    const renamed = renameView(view!.id, 'New Name');
    expect(renamed).toBe(true);
    expect(getSavedViews()[0]!.label).toBe('New Name');
  });

  it('renameView returns false for unknown id', () => {
    expect(renameView('nonexistent', 'Name')).toBe(false);
  });

  it('subscribeSavedViews fires on mutation', () => {
    const listener = vi.fn();
    subscribeSavedViews(listener);
    expect(listener).toHaveBeenCalledTimes(1);
    listener.mockClear();
    saveView('Search', 'url1', 'core.search-surface');
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith(expect.arrayContaining([
      expect.objectContaining({ url: 'url1' }),
    ]));
  });

  it('subscribeSavedViews does not fire on no-op save (dedup)', () => {
    saveView('Search', 'url1', 'core.search-surface');
    const listener = vi.fn();
    subscribeSavedViews(listener);
    listener.mockClear();
    saveView('Search Again', 'url1', 'core.search-surface');
    expect(listener).not.toHaveBeenCalled();
  });

  it('persists through localStorage round-trip', () => {
    saveView('Search', 'url1', 'core.search-surface');
    // Post-merge: storage key bumped to v2 (UserStateDocument Profile
    // refactor lives at justsearch.userState.v2; the v1 key remains
    // read-on-boot for legacy migration).
    const raw = localStorage.getItem('justsearch.userState.v2');
    expect(raw).not.toBeNull();

    __resetUserStateForTest();
    localStorage.setItem('justsearch.userState.v2', raw!);

    const views = getSavedViews();
    expect(views).toHaveLength(1);
    expect(views[0]!.url).toBe('url1');
    expect(views[0]!.label).toBe('Search');
  });
});
