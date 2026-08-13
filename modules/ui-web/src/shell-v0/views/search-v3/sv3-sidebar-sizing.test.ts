// @vitest-environment happy-dom

/**
 * The Search v3 sidebar boundary's arithmetic (tempdoc 822 Phase F5).
 *
 * The donor ships its own table for this (`apps/web/src/components/threadSidebarWidth.test.ts`) and
 * these are the same five questions, re-asked against our numbers — including the one that is the
 * whole point of the rule: the ceiling is the MAIN pane's minimum subtracted from the shared box,
 * not a number the sidebar chose for itself.
 *
 * happy-dom rather than no-DOM only because the persistence half needs a `localStorage`.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import {
  clampSv3SidebarWidth,
  forgetSv3SidebarWidth,
  readStoredSv3SidebarCollapsed,
  readStoredSv3SidebarWidth,
  resolveInitialSv3SidebarWidth,
  storeSv3SidebarCollapsed,
  storeSv3SidebarWidth,
  sv3SidebarMaxWidth,
  sv3SidebarStorageKeys,
  SV3_MAIN_MIN_PX,
  SV3_SIDEBAR_COLLAPSED_PX,
  SV3_SIDEBAR_DEFAULT_PX,
  SV3_SIDEBAR_KEY_STEP_PX,
  SV3_SIDEBAR_MIN_PX,
} from './sv3-sidebar-sizing.js';

describe('sv3 sidebar sizing — the donor numbers', () => {
  it('carries the donor constants exactly', () => {
    // 16rem / 13rem / 40rem / 3rem at the donor's own 16px root (sidebar.tsx:27,29;
    // threadSidebarWidth.ts:2-4).
    expect(SV3_SIDEBAR_DEFAULT_PX).toBe(16 * 16);
    expect(SV3_SIDEBAR_MIN_PX).toBe(13 * 16);
    expect(SV3_MAIN_MIN_PX).toBe(40 * 16);
    expect(SV3_SIDEBAR_COLLAPSED_PX).toBe(3 * 16);
  });

  it('derives the ceiling from the MAIN pane minimum, not from the sidebar', () => {
    // The load-bearing case: at 1568 the sidebar may reach 928 and no further, because 640 belongs
    // to the main pane. A ceiling written as its own constant would pass every other case here.
    expect(sv3SidebarMaxWidth(1568)).toBe(1568 - SV3_MAIN_MIN_PX);
    expect(clampSv3SidebarWidth(2000, 1568)).toBe(928);
    expect(clampSv3SidebarWidth(1200, 1000)).toBe(360);
  });

  it('keeps the sidebar legible when the box cannot hold both minimums', () => {
    // Donor `threadSidebarWidth.ts:7-10` — the floor is on the OUTSIDE of the min, so it wins.
    expect(sv3SidebarMaxWidth(700)).toBe(SV3_SIDEBAR_MIN_PX);
    expect(clampSv3SidebarWidth(900, 700)).toBe(SV3_SIDEBAR_MIN_PX);
  });

  it('clamps a drag to the floor and to the ceiling', () => {
    expect(clampSv3SidebarWidth(10, 1568)).toBe(SV3_SIDEBAR_MIN_PX);
    expect(clampSv3SidebarWidth(400, 1568)).toBe(400);
    expect(clampSv3SidebarWidth(100_000, 1568)).toBe(928);
  });

  it('opens at the default, at a remembered width, or at whatever the box allows', () => {
    expect(resolveInitialSv3SidebarWidth(null, 1568)).toBe(SV3_SIDEBAR_DEFAULT_PX);
    expect(resolveInitialSv3SidebarWidth(360, 1568)).toBe(360);
    expect(resolveInitialSv3SidebarWidth(120, 1568)).toBe(SV3_SIDEBAR_MIN_PX);
    // A width remembered from a wider window must not reopen this one in a shape it rejects.
    expect(resolveInitialSv3SidebarWidth(900, 1000)).toBe(1000 - SV3_MAIN_MIN_PX);
    expect(resolveInitialSv3SidebarWidth(900, 700)).toBe(SV3_SIDEBAR_MIN_PX);
    // Even the DEFAULT yields to the main pane's minimum on a box that cannot hold both.
    expect(resolveInitialSv3SidebarWidth(null, 800)).toBe(SV3_SIDEBAR_MIN_PX);
  });

  it('nudges by the repo grip step', () => {
    expect(SV3_SIDEBAR_KEY_STEP_PX).toBe(24);
  });
});

describe('sv3 sidebar sizing — the remembered preference', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('remembers a width and reads it back', () => {
    expect(readStoredSv3SidebarWidth()).toBeNull();
    storeSv3SidebarWidth(333.6);
    expect(localStorage.getItem(sv3SidebarStorageKeys.width)).toBe('334');
    expect(readStoredSv3SidebarWidth()).toBe(334);
  });

  it('FORGETS rather than storing the default (818 L13)', () => {
    storeSv3SidebarWidth(400);
    forgetSv3SidebarWidth();
    expect(localStorage.getItem(sv3SidebarStorageKeys.width)).toBeNull();
    expect(readStoredSv3SidebarWidth()).toBeNull();
  });

  it('treats an unparseable stored width as no memory at all', () => {
    localStorage.setItem(sv3SidebarStorageKeys.width, 'wide');
    expect(readStoredSv3SidebarWidth()).toBeNull();
  });

  it('remembers the collapsed rail beside the width', () => {
    expect(readStoredSv3SidebarCollapsed()).toBe(false);
    storeSv3SidebarCollapsed(true);
    expect(readStoredSv3SidebarCollapsed()).toBe(true);
    storeSv3SidebarCollapsed(false);
    expect(readStoredSv3SidebarCollapsed()).toBe(false);
  });

  it('costs the memory, never the gesture, when storage throws', () => {
    const original = Object.getOwnPropertyDescriptor(window, 'localStorage');
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      get() {
        throw new Error('storage disabled');
      },
    });
    try {
      expect(() => storeSv3SidebarWidth(300)).not.toThrow();
      expect(() => storeSv3SidebarCollapsed(true)).not.toThrow();
      expect(() => forgetSv3SidebarWidth()).not.toThrow();
      expect(readStoredSv3SidebarWidth()).toBeNull();
      expect(readStoredSv3SidebarCollapsed()).toBe(false);
    } finally {
      if (original !== undefined) Object.defineProperty(window, 'localStorage', original);
    }
  });
});
