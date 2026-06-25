// @vitest-environment happy-dom

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  getFilters,
  subscribeFilters,
  setFilterRange,
  hasActiveFilter,
  __resetSearchFiltersForTest,
  type SearchFilterSpec,
} from './searchFiltersState.js';

describe('searchFiltersState — defaults + setFilterRange', () => {
  beforeEach(() => {
    __resetSearchFiltersForTest();
  });

  it('defaults to empty (no bounds)', () => {
    expect(getFilters()).toEqual({});
    expect(hasActiveFilter(getFilters())).toBe(false);
  });

  it('setFilterRange with both bounds populates spec', () => {
    setFilterRange(1000, 2000);
    expect(getFilters()).toEqual({ modifiedFromMs: 1000, modifiedToMs: 2000 });
    expect(hasActiveFilter(getFilters())).toBe(true);
  });

  it('setFilterRange with only one bound populates that bound only', () => {
    setFilterRange(1000, undefined);
    expect(getFilters()).toEqual({ modifiedFromMs: 1000 });
    setFilterRange(undefined, 2000);
    expect(getFilters()).toEqual({ modifiedToMs: 2000 });
  });

  it('setFilterRange(undefined, undefined) clears the filter', () => {
    setFilterRange(1000, 2000);
    expect(hasActiveFilter(getFilters())).toBe(true);
    setFilterRange(undefined, undefined);
    expect(getFilters()).toEqual({});
    expect(hasActiveFilter(getFilters())).toBe(false);
  });

  it('rejects NaN / non-finite numbers (treats as undefined)', () => {
    setFilterRange(Number.NaN, Number.POSITIVE_INFINITY);
    expect(getFilters()).toEqual({});
  });

  it('does NOT write to localStorage (ephemeral)', () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    setFilterRange(1000, 2000);
    expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });
});

describe('searchFiltersState — subscription', () => {
  beforeEach(() => {
    __resetSearchFiltersForTest();
  });

  it('subscribeFilters fires once with current value on subscribe', () => {
    const listener = vi.fn();
    subscribeFilters(listener);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith({});
  });

  it('listener fires on filter change', () => {
    const listener = vi.fn();
    subscribeFilters(listener);
    listener.mockClear();
    setFilterRange(1000, undefined);
    expect(listener).toHaveBeenCalledTimes(1);
    expect((listener.mock.calls[0]![0] as SearchFilterSpec).modifiedFromMs).toBe(
      1000,
    );
  });

  it('listener does NOT fire on no-op (same value re-set)', () => {
    setFilterRange(1000, 2000);
    const listener = vi.fn();
    subscribeFilters(listener);
    listener.mockClear();
    setFilterRange(1000, 2000); // same
    expect(listener).not.toHaveBeenCalled();
  });

  it('unsubscribe stops further notifications', () => {
    const listener = vi.fn();
    const off = subscribeFilters(listener);
    listener.mockClear();
    off();
    setFilterRange(1000, undefined);
    expect(listener).not.toHaveBeenCalled();
  });
});
