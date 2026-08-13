// SPDX-License-Identifier: Apache-2.0
/**
 * The Search v3 sidebar's movable boundary (tempdoc 822 Phase F5).
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory. Every
 * number below is the donor's own, cited to the clone at `b73232bd`:
 *
 *  - default 16rem (`apps/web/src/components/ui/sidebar.tsx:27`, `threadSidebarWidth.ts:2`)
 *  - drag floor 13rem (`threadSidebarWidth.ts:3`)
 *  - collapsed icon rail 3rem (`sidebar.tsx:29`)
 *  - **the ceiling is not the sidebar's own number.** `threadSidebarWidth.ts:4,6-11` derives it from
 *    the MAIN pane's 40rem minimum: `max(sidebarMin, available - 640)`. The sidebar may take
 *    everything that is not the main pane's honest reading width, and no more — the same shape as
 *    818's L13 ("every movable boundary is clamped by the minimum honest forms on both sides"), which
 *    is why the two windows' rules agree without either importing the other.
 *
 * ONE DELIBERATE DEVIATION from the donor's arithmetic, and it is the donor's own rule applied
 * honestly: the donor measures against `window.innerWidth` because its sidebar+main wrapper IS the
 * viewport (`AppSidebarLayout.tsx:148-149` beside `shouldAcceptWidth`'s `wrapper.clientWidth`, which
 * is the same box there). Search v3 is a surface mounted INSIDE the shipped shell, so the viewport is
 * wider than the box the two regions share. Deriving from the viewport would therefore let the main
 * pane fall under 640px — the exact thing the donor's rule exists to prevent. The caller passes the
 * WINDOW HOST's own width, which is the donor's `wrapper`.
 *
 * The impure edge at the bottom is 818's L13 remember/reset asymmetry, and it is NOT a breach of
 * `sv3-sessions.ts`'s no-persistence rule: that rule is about SESSION RECORDS, whose authority is the
 * open Phase-D question. A sidebar width and a collapsed rail are UI PREFERENCES about this window's
 * chrome — they describe no domain object, fork no authority, and have exactly the durable home
 * `views/search-v2/railSizing.ts:117-129` already chose for the same kind of value.
 */

/** Donor default — 16rem (`ui/sidebar.tsx:27`, `threadSidebarWidth.ts:2`). */
export const SV3_SIDEBAR_DEFAULT_PX = 256;

/** Donor drag floor — 13rem (`threadSidebarWidth.ts:3`). */
export const SV3_SIDEBAR_MIN_PX = 208;

/** Donor main-pane minimum — 40rem (`threadSidebarWidth.ts:4`). The sidebar's ceiling comes FROM it. */
export const SV3_MAIN_MIN_PX = 640;

/** Donor icon rail — 3rem (`ui/sidebar.tsx:29`); the collapsed sidebar's whole width. */
export const SV3_SIDEBAR_COLLAPSED_PX = 48;

/**
 * One keyboard nudge. The donor's rail is `tabIndex={-1}` — it has no keyboard half at all
 * (`ui/sidebar.tsx:618`), so this is the repo's own answer to the same boundary: the step
 * `views/search-v2/railSizing.ts:64` already uses for a horizontal grip.
 */
export const SV3_SIDEBAR_KEY_STEP_PX = 24;

/**
 * The widest the sidebar may be beside a main pane that still has its 640px. The floor WINS when the
 * two cross (`Math.max` on the outside), which is the donor's own resolution
 * (`threadSidebarWidth.ts:7-10`): a box too narrow for both minimums keeps the sidebar legible rather
 * than collapsing it to a sliver.
 */
export function sv3SidebarMaxWidth(availableWidth: number): number {
  return Math.max(SV3_SIDEBAR_MIN_PX, Math.floor(availableWidth) - SV3_MAIN_MIN_PX);
}

/** The donor's `clampSidebarWidth` (`ui/sidebar.tsx:346-348`), with the ceiling derived per above. */
export function clampSv3SidebarWidth(width: number, availableWidth: number): number {
  return Math.round(
    Math.max(SV3_SIDEBAR_MIN_PX, Math.min(width, sv3SidebarMaxWidth(availableWidth))),
  );
}

/**
 * The width the window opens at: the remembered one if there is one, the default otherwise, and in
 * both cases clamped into the box actually available. Donor `resolveInitialThreadSidebarWidth`
 * (`threadSidebarWidth.ts:13-22`) — a remembered width from a wider window must not reopen this one
 * in a shape its own minimums reject.
 */
export function resolveInitialSv3SidebarWidth(
  storedWidth: number | null,
  availableWidth: number,
): number {
  const preferred =
    storedWidth === null ? SV3_SIDEBAR_DEFAULT_PX : Math.max(SV3_SIDEBAR_MIN_PX, storedWidth);
  return Math.min(preferred, sv3SidebarMaxWidth(availableWidth));
}

const WIDTH_KEY = 'justsearch.searchV3.sidebar.width.v1';
const COLLAPSED_KEY = 'justsearch.searchV3.sidebar.collapsed.v1';

/** The remembered width, or null for automatic. A stored non-number is treated as no memory at all. */
export function readStoredSv3SidebarWidth(): number | null {
  try {
    const raw = localStorage.getItem(WIDTH_KEY);
    if (raw === null) return null;
    const px = Number.parseInt(raw, 10);
    return Number.isFinite(px) ? px : null;
  } catch {
    // Storage unavailable (private mode, quota) costs the memory, never the gesture.
    return null;
  }
}

export function storeSv3SidebarWidth(px: number): void {
  try {
    localStorage.setItem(WIDTH_KEY, String(Math.round(px)));
  } catch {
    // As above.
  }
}

/** L13 — returning the boundary to automatic FORGETS it: the width was a choice the reader withdrew. */
export function forgetSv3SidebarWidth(): void {
  try {
    localStorage.removeItem(WIDTH_KEY);
  } catch {
    // As above.
  }
}

/**
 * Whether the rail was left collapsed. The donor keeps this in a cookie (`sidebar_state`,
 * `ui/sidebar.tsx:25-26,120-130`) because its shell renders server-side and needs the state before
 * hydration; this app has no server render, so the preference lives beside the width instead of
 * introducing a second storage medium for one boolean.
 */
export function readStoredSv3SidebarCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_KEY) === '1';
  } catch {
    return false;
  }
}

export function storeSv3SidebarCollapsed(collapsed: boolean): void {
  try {
    localStorage.setItem(COLLAPSED_KEY, collapsed ? '1' : '0');
  } catch {
    // As above.
  }
}

export const sv3SidebarStorageKeys = { width: WIDTH_KEY, collapsed: COLLAPSED_KEY } as const;
