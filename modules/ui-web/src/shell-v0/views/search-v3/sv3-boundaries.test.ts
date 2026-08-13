// @vitest-environment happy-dom

/**
 * The Search v3 window's boundary arithmetic (tempdoc 822 Phase F5 for the sidebar, F8 for the pane).
 *
 * The donor ships its own table for the sidebar half (`apps/web/src/components/
 * threadSidebarWidth.test.ts`) and those are the same questions, re-asked against our numbers —
 * including the one that is the whole point of the rule: a ceiling is the MAIN pane's minimum
 * subtracted from the shared box, not a number the region chose for itself.
 *
 * The donor ships NO table for the pane half, because the donor has no main-column clamp for its
 * right panel at all. The BOTH-OPEN cases below are therefore the F8 improvement's only proof, and
 * they are written so that dropping the pane term from the sidebar's ceiling (or the sidebar term
 * from the pane's) fails them — a clamp that merely "looks right" passes every single-boundary case.
 *
 * happy-dom rather than no-DOM only because the persistence half needs a `localStorage`.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import {
  clampSv3PaneWidth,
  clampSv3SidebarWidth,
  forgetSv3PaneWidth,
  forgetSv3SidebarWidth,
  readStoredSv3PaneWidth,
  readStoredSv3SidebarCollapsed,
  readStoredSv3SidebarWidth,
  resolveInitialSv3PaneWidth,
  resolveInitialSv3SidebarWidth,
  storeSv3PaneWidth,
  storeSv3SidebarCollapsed,
  storeSv3SidebarWidth,
  sv3BoundaryStorageKeys,
  sv3PaneIsInline,
  sv3PaneMaxWidth,
  sv3PaneOccupiedWidth,
  sv3SidebarMaxWidth,
  SV3_GRIP_KEY_STEP_PX,
  SV3_MAIN_MIN_PX,
  SV3_PANE_DEFAULT_PX,
  SV3_PANE_INLINE_MIN_PX,
  SV3_PANE_MAX_SHARE,
  SV3_PANE_MIN_PX,
  SV3_SIDEBAR_COLLAPSED_PX,
  SV3_SIDEBAR_DEFAULT_PX,
  SV3_SIDEBAR_MIN_PX,
} from './sv3-boundaries.js';

describe('sv3 boundaries — the donor numbers', () => {
  it('carries the donor constants exactly', () => {
    // 16rem / 13rem / 40rem / 3rem at the donor's own 16px root (sidebar.tsx:27,29;
    // threadSidebarWidth.ts:2-4).
    expect(SV3_SIDEBAR_DEFAULT_PX).toBe(16 * 16);
    expect(SV3_SIDEBAR_MIN_PX).toBe(13 * 16);
    expect(SV3_MAIN_MIN_PX).toBe(40 * 16);
    expect(SV3_SIDEBAR_COLLAPSED_PX).toBe(3 * 16);
    // The right panel's own three (PreviewPanelShell.tsx:12-19,42-49) and its 980px switch
    // (rightPanelLayout.ts:1).
    expect(SV3_PANE_DEFAULT_PX).toBe(540);
    expect(SV3_PANE_MIN_PX).toBe(360);
    expect(SV3_PANE_MAX_SHARE).toBe(0.7);
    expect(SV3_PANE_INLINE_MIN_PX).toBe(980);
  });

  it('nudges both grips by the repo step', () => {
    expect(SV3_GRIP_KEY_STEP_PX).toBe(24);
  });
});

describe('sv3 boundaries — the sidebar ceiling', () => {
  it('derives the ceiling from the MAIN pane minimum, not from the sidebar', () => {
    // The load-bearing case: at 1568 the sidebar may reach 928 and no further, because 640 belongs
    // to the main pane. A ceiling written as its own constant would pass every other case here.
    expect(sv3SidebarMaxWidth(1568, 0)).toBe(1568 - SV3_MAIN_MIN_PX);
    expect(clampSv3SidebarWidth(2000, 1568, 0)).toBe(928);
    expect(clampSv3SidebarWidth(1200, 1000, 0)).toBe(360);
  });

  it('keeps the sidebar legible when the box cannot hold both minimums', () => {
    // Donor `threadSidebarWidth.ts:7-10` — the floor is on the OUTSIDE of the min, so it wins.
    expect(sv3SidebarMaxWidth(700, 0)).toBe(SV3_SIDEBAR_MIN_PX);
    expect(clampSv3SidebarWidth(900, 700, 0)).toBe(SV3_SIDEBAR_MIN_PX);
  });

  it('clamps a drag to the floor and to the ceiling', () => {
    expect(clampSv3SidebarWidth(10, 1568, 0)).toBe(SV3_SIDEBAR_MIN_PX);
    expect(clampSv3SidebarWidth(400, 1568, 0)).toBe(400);
    expect(clampSv3SidebarWidth(100_000, 1568, 0)).toBe(928);
  });

  it('opens at the default, at a remembered width, or at whatever the box allows', () => {
    expect(resolveInitialSv3SidebarWidth(null, 1568, 0)).toBe(SV3_SIDEBAR_DEFAULT_PX);
    expect(resolveInitialSv3SidebarWidth(360, 1568, 0)).toBe(360);
    expect(resolveInitialSv3SidebarWidth(120, 1568, 0)).toBe(SV3_SIDEBAR_MIN_PX);
    // A width remembered from a wider window must not reopen this one in a shape it rejects.
    expect(resolveInitialSv3SidebarWidth(900, 1000, 0)).toBe(1000 - SV3_MAIN_MIN_PX);
    expect(resolveInitialSv3SidebarWidth(900, 700, 0)).toBe(SV3_SIDEBAR_MIN_PX);
    // Even the DEFAULT yields to the main pane's minimum on a box that cannot hold both.
    expect(resolveInitialSv3SidebarWidth(null, 800, 0)).toBe(SV3_SIDEBAR_MIN_PX);
  });
});

describe('sv3 boundaries — the pane ceiling', () => {
  it('takes the donor 70% share on a box wide enough that the share is the binding term', () => {
    // The two terms cross at 0.3 × host = sidebar + 640; above that the DONOR's own 70% is what
    // holds the pane back, and the improvement adds nothing. At 2400 with no sidebar: share 1680,
    // main-safe remainder 1760.
    expect(sv3PaneMaxWidth(2400, 0)).toBe(Math.floor(2400 * SV3_PANE_MAX_SHARE));
    expect(clampSv3PaneWidth(5000, 2400, 0)).toBe(1680);
  });

  it('clamps a drag to the donor floor and to the ceiling', () => {
    expect(clampSv3PaneWidth(10, 1568, 256)).toBe(SV3_PANE_MIN_PX);
    expect(clampSv3PaneWidth(500, 1568, 256)).toBe(500);
    expect(clampSv3PaneWidth(100_000, 1568, 256)).toBe(1568 - 256 - SV3_MAIN_MIN_PX);
  });

  it('opens at the donor default, at a remembered width, or at whatever the box allows', () => {
    expect(resolveInitialSv3PaneWidth(null, 1568, 256)).toBe(SV3_PANE_DEFAULT_PX);
    expect(resolveInitialSv3PaneWidth(700, 1920, 256)).toBe(700);
    expect(resolveInitialSv3PaneWidth(200, 1920, 256)).toBe(SV3_PANE_MIN_PX);
    // Remembered from a wider window: 1400 − 256 − 640 = 504.
    expect(resolveInitialSv3PaneWidth(900, 1400, 256)).toBe(504);
  });

  it('hands the pane the collapsed rail back', () => {
    // The clamp reads the RENDERED sidebar width, so collapsing it widens what the pane may take by
    // exactly the difference between the panel and the icon rail.
    expect(sv3PaneMaxWidth(1400, SV3_SIDEBAR_COLLAPSED_PX)).toBe(
      sv3PaneMaxWidth(1400, 256) + (256 - SV3_SIDEBAR_COLLAPSED_PX),
    );
  });

  it('keeps the pane legible when the box cannot hold every minimum', () => {
    // 1000 − 900 − 640 is negative; the floor on the outside wins, exactly as the sidebar's does.
    expect(sv3PaneMaxWidth(1000, 900)).toBe(SV3_PANE_MIN_PX);
    expect(clampSv3PaneWidth(800, 1000, 900)).toBe(SV3_PANE_MIN_PX);
  });
});

describe('sv3 boundaries — BOTH open (the F8 improvement the donor does not have)', () => {
  it('leaves the main column its 640 with the sidebar at its ceiling beside an open pane', () => {
    // THE PROBE. Drop the pane term from `sv3SidebarMaxWidth` and this reads 928 instead of 388 —
    // which is precisely the donor's defect: a sidebar dragged to 928 beside a 540 pane leaves the
    // main column 100px in a 1568 box.
    const paneOpen = sv3PaneOccupiedWidth(SV3_PANE_DEFAULT_PX, 1568);
    expect(sv3SidebarMaxWidth(1568, paneOpen)).toBe(1568 - SV3_PANE_DEFAULT_PX - SV3_MAIN_MIN_PX);
    expect(sv3SidebarMaxWidth(1568, paneOpen)).toBe(388);
    const sidebar = clampSv3SidebarWidth(10_000, 1568, paneOpen);
    expect(1568 - sidebar - SV3_PANE_DEFAULT_PX).toBe(SV3_MAIN_MIN_PX);
  });

  it('leaves the main column its 640 with the pane at its ceiling beside an open sidebar', () => {
    // The mirror probe: drop the sidebar term from `sv3PaneMaxWidth` and this reads 1097 (the raw
    // 70% share) instead of 672.
    expect(sv3PaneMaxWidth(1568, 256)).toBe(1568 - 256 - SV3_MAIN_MIN_PX);
    expect(sv3PaneMaxWidth(1568, 256)).toBe(672);
    const pane = clampSv3PaneWidth(10_000, 1568, 256);
    expect(1568 - 256 - pane).toBe(SV3_MAIN_MIN_PX);
  });

  it('gives BOTH boundaries back their room when the other one is not in the flow', () => {
    // An OVERLAID pane occupies nothing, so a narrow window does not squeeze its own sidebar over a
    // pane that is painted on top of it.
    expect(sv3PaneOccupiedWidth(SV3_PANE_DEFAULT_PX, 900)).toBe(0);
    expect(sv3PaneOccupiedWidth(SV3_PANE_DEFAULT_PX, 1568)).toBe(SV3_PANE_DEFAULT_PX);
    // A CLOSED pane occupies nothing at any width.
    expect(sv3PaneOccupiedWidth(null, 1568)).toBe(0);
    expect(sv3SidebarMaxWidth(1568, sv3PaneOccupiedWidth(null, 1568))).toBe(928);
  });

  it('treats an unmeasured box as unknown rather than narrow', () => {
    // The window before its first paint: no ceiling, floors still apply, and the pane presents
    // inline rather than flashing an overlay it will immediately leave.
    expect(sv3PaneIsInline(Number.POSITIVE_INFINITY)).toBe(true);
    expect(clampSv3SidebarWidth(900, Number.POSITIVE_INFINITY, 0)).toBe(900);
    expect(clampSv3PaneWidth(900, Number.POSITIVE_INFINITY, 0)).toBe(900);
    expect(resolveInitialSv3PaneWidth(null, Number.POSITIVE_INFINITY, 0)).toBe(SV3_PANE_DEFAULT_PX);
  });

  it('switches presentation at the donor 980', () => {
    expect(sv3PaneIsInline(979)).toBe(false);
    expect(sv3PaneIsInline(SV3_PANE_INLINE_MIN_PX)).toBe(true);
  });
});

describe('sv3 boundaries — the remembered preferences', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('remembers each width under its own key and reads it back', () => {
    expect(readStoredSv3SidebarWidth()).toBeNull();
    storeSv3SidebarWidth(333.6);
    expect(localStorage.getItem(sv3BoundaryStorageKeys.sidebarWidth)).toBe('334');
    expect(readStoredSv3SidebarWidth()).toBe(334);

    expect(readStoredSv3PaneWidth()).toBeNull();
    storeSv3PaneWidth(612.4);
    expect(sv3BoundaryStorageKeys.paneWidth).toBe('justsearch.searchV3.pane.width.v1');
    expect(localStorage.getItem(sv3BoundaryStorageKeys.paneWidth)).toBe('612');
    expect(readStoredSv3PaneWidth()).toBe(612);
    // Two boundaries, two memories: forgetting one must not forget the other.
    forgetSv3PaneWidth();
    expect(readStoredSv3PaneWidth()).toBeNull();
    expect(readStoredSv3SidebarWidth()).toBe(334);
  });

  it('FORGETS rather than storing the default (818 L13)', () => {
    storeSv3SidebarWidth(400);
    forgetSv3SidebarWidth();
    expect(localStorage.getItem(sv3BoundaryStorageKeys.sidebarWidth)).toBeNull();
    expect(readStoredSv3SidebarWidth()).toBeNull();
    storeSv3PaneWidth(700);
    forgetSv3PaneWidth();
    expect(localStorage.getItem(sv3BoundaryStorageKeys.paneWidth)).toBeNull();
    expect(readStoredSv3PaneWidth()).toBeNull();
  });

  it('treats an unparseable stored width as no memory at all', () => {
    localStorage.setItem(sv3BoundaryStorageKeys.sidebarWidth, 'wide');
    localStorage.setItem(sv3BoundaryStorageKeys.paneWidth, 'wide');
    expect(readStoredSv3SidebarWidth()).toBeNull();
    expect(readStoredSv3PaneWidth()).toBeNull();
  });

  it('remembers the collapsed rail beside the widths', () => {
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
      expect(() => storeSv3PaneWidth(600)).not.toThrow();
      expect(() => storeSv3SidebarCollapsed(true)).not.toThrow();
      expect(() => forgetSv3SidebarWidth()).not.toThrow();
      expect(() => forgetSv3PaneWidth()).not.toThrow();
      expect(readStoredSv3SidebarWidth()).toBeNull();
      expect(readStoredSv3PaneWidth()).toBeNull();
      expect(readStoredSv3SidebarCollapsed()).toBe(false);
    } finally {
      if (original !== undefined) Object.defineProperty(window, 'localStorage', original);
    }
  });
});
