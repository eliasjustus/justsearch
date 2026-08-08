// SPDX-License-Identifier: Apache-2.0
/**
 * railSizing — the arithmetic of the window's HORIZONTAL boundaries (tempdoc 818 slice 5, L13).
 *
 * The sibling of `deckSizing.ts`, and deliberately its mirror: L13 is one rule ("every movable
 * boundary is clamped by the minimum honest forms on both sides") and the two axes are two
 * applications of it, not two policies. What differs is only what the minimums ARE, and each one
 * here is taken from an existing authority rather than invented:
 *
 *  - the session rail's FLOOR is the product's own collapsed rail strip — `--rail-width: 3.25rem`
 *    (`modules/ui-web/src/styles/tokens.css:282`), the ONE token `Shell.ts:571-595` lays its rail
 *    track from (expanding it to 11rem). Below {@link RAIL_LEGIBLE_PX} the rail therefore takes its
 *    minimum honest form — the collapsed strip — exactly as the deck's list yields to its count line
 *    rather than rendering as a two-pixel scroller.
 *  - the document region's FLOOR is the readable document column the proportion register already
 *    pins: `.document-pane` `minWidthPx: 384` in `governance/ui-proportion-baseline.v1.json`
 *    (`chat-occlusion` step; the register's own `occlusionNote` derives it — "the track floor is now
 *    24rem, so the floor here is 384px").
 *  - both CEILINGS are the OTHER side's minimum honest form: whatever keeps the centre column at or
 *    above its reading floor, which is the same register's `.conversation` `minWidthPx: 384`. The
 *    816 role register's `prose` measure (`--measure-prose: 88ch`) is the reading column's CEILING,
 *    so it is not the number a floor can be derived from; `pane` is the role that governs a
 *    space-sharing region, and 816 declares it `documentational` precisely because the physical
 *    `minWidthPx` rows above already express it.
 *  - the floor WINS when the two cross, for the same reason as the deck: a window too narrow to
 *    satisfy both minimums must degrade the region that CAN degrade honestly.
 *
 * Why this window declares no rows in `governance/ui-proportion-baseline.v1.json`: that register is
 * keyed by ui-shot STEP, and Search v2 is a DEEPLINK/DEVELOPER surface with no rail entry and no
 * capture step (the same reason `governance/sandbox-coverage.v1.json` carries it as `tier: exempt`).
 * The §5 cutover moves both rows together — see the note in {@link SearchV2View}'s docblock.
 *
 * The one impure edge lives at the bottom: rails REMEMBER (L13's remember/reset asymmetry — a width
 * is a preference, a deck height is a per-session shape), so the chosen widths are persisted.
 */

/** Which boundary a clamp or a memory is about. Two rails, one rule. */
export type RailId = 'sessions' | 'document';

/** The session rail's automatic width — the `.rail` flex basis (14rem), stated once. */
export const SESSION_RAIL_DEFAULT_PX = 224;

/** The product's collapsed rail strip (`--rail-width: 3.25rem`) — the rail's minimum honest form. */
export const SESSION_RAIL_FLOOR_PX = 52;

/** Twice the automatic width: a rail wider than that is no longer periphery. */
export const SESSION_RAIL_CEILING_PX = SESSION_RAIL_DEFAULT_PX * 2;

/**
 * Below this the rail stops being a list of wrapping titles and becomes one word per line, so it
 * takes its collapsed form instead — 8rem, about a short line at the rail's own font size. This is
 * the horizontal twin of {@link listYields}: a region at its floor renders its minimum honest form,
 * never a squashed version of its full one.
 */
export const RAIL_LEGIBLE_PX = 128;

/** The readable document column (`.document-pane` `minWidthPx: 384` — 24rem). */
export const DOCUMENT_RAIL_FLOOR_PX = 384;

/** The centre column's reading floor (`.conversation` `minWidthPx: 384` — the same register). */
export const CENTRE_MIN_PX = 384;

/** One keyboard nudge of a horizontal grip — the same step the deck's grip takes vertically. */
export const RAIL_KEY_STEP_PX = 24;

export interface RailClampInput {
  /** The rail's width when the gesture began. */
  readonly startWidthPx: number;
  /** Growth is positive, whichever direction the pointer had to travel to produce it. */
  readonly deltaPx: number;
  readonly floorPx: number;
  readonly ceilingPx: number;
}

/**
 * The clamped width for a gesture. Pure: the view supplies the measurements, and the floor wins the
 * crossing case (`Math.max(floor, Math.min(ceiling, …))` is that priority, not an ordering accident).
 */
export function clampRailWidth(input: RailClampInput): number {
  return Math.round(
    Math.max(input.floorPx, Math.min(input.ceilingPx, input.startWidthPx + input.deltaPx)),
  );
}

/**
 * The session rail's ceiling: its own "still periphery" cap, or whatever leaves the centre column
 * its reading floor beside the document region — whichever binds first.
 */
export function sessionRailCeiling(availablePx: number, documentPx: number): number {
  return Math.min(SESSION_RAIL_CEILING_PX, availablePx - documentPx - CENTRE_MIN_PX);
}

/** The document region's ceiling: whatever leaves the centre column its reading floor. */
export function documentRailCeiling(availablePx: number, sessionRailPx: number): number {
  return availablePx - sessionRailPx - CENTRE_MIN_PX;
}

/** Is the session rail too narrow to render as rows? True ⟹ it takes its collapsed strip form. */
export function railYields(widthPx: number): boolean {
  return widthPx < RAIL_LEGIBLE_PX;
}

/** The floor in force for a rail — stated in one place so the view never re-derives it. */
export function railFloorPx(rail: RailId): number {
  return rail === 'sessions' ? SESSION_RAIL_FLOOR_PX : DOCUMENT_RAIL_FLOOR_PX;
}

/**
 * The AUTOMATIC width a rail opens at — the stylesheet's own flex basis, restated here so a gesture
 * that begins before the browser has measured anything still starts from the width on screen rather
 * than from zero.
 */
export function railDefaultPx(rail: RailId): number {
  return rail === 'sessions' ? SESSION_RAIL_DEFAULT_PX : DOCUMENT_RAIL_FLOOR_PX;
}

/**
 * The remembered width, or null for automatic. Storage keys are versioned per rail so the two
 * boundaries cannot overwrite each other, and a stored value outside today's clamps is DISCARDED
 * rather than restored — a remembered width from a wider window must not reopen the window in a
 * shape its own floors reject.
 *
 * Promotion path, deliberately not taken yet: a rail width is a user preference, so its durable home
 * is `UserStateDocument` (a `searchV2.railWidths` slice projected through a `railWidthState.ts`
 * mirroring `pinnedSearchState.ts`), which is what gives it profile-scoping and cross-device sync.
 * `localStorage` is used here because Search v2 is a dev-gated window in a two-window comparison
 * phase (§5): adding a slice to the shared user document for a surface that may be deleted would put
 * a schema migration behind a decision that has not been made. At the §5 cutover this module's three
 * accessors are the only things that change.
 */
export function readStoredRailWidth(rail: RailId): number | null {
  try {
    const raw = localStorage.getItem(storageKey(rail));
    if (raw === null) return null;
    const px = Number.parseInt(raw, 10);
    if (!Number.isFinite(px)) return null;
    return px >= railFloorPx(rail) && px <= SESSION_RAIL_CEILING_PX * 4 ? px : null;
  } catch {
    // localStorage unavailable (private mode / SSR / quota) — the rail simply opens automatic.
    return null;
  }
}

/** Remember a chosen width. */
export function storeRailWidth(rail: RailId, px: number): void {
  try {
    localStorage.setItem(storageKey(rail), String(Math.round(px)));
  } catch {
    // Unavailable storage costs the memory, never the gesture.
  }
}

/** L13 — returning a boundary to automatic FORGETS it: the width was a choice the user withdrew. */
export function forgetRailWidth(rail: RailId): void {
  try {
    localStorage.removeItem(storageKey(rail));
  } catch {
    // As above.
  }
}

export function storageKey(rail: RailId): string {
  return `justsearch.searchV2.railWidth.${rail}.v1`;
}
