// SPDX-License-Identifier: Apache-2.0
/**
 * deckSizing — the arithmetic of the deck's movable boundary (tempdoc 818 slice 4, L7/L13).
 *
 * L13 states the rule the grip obeys: *every movable boundary is clamped by the minimum honest forms
 * on both sides*. This module is that rule as pure functions, deliberately separate from the view:
 *
 *  - the FLOOR is content-derived, computed at drag time from the deck's own occupants. Every occupant
 *    except the one compressible body (the results list) is incompressible, so while a run is in
 *    flight the run CONTROLS are part of the floor — a held decision can never be dragged off screen,
 *    which is the same guarantee {@link SearchV2View.runControls}'s DOM position gives against
 *    scrolling. {@link collectIncompressibleHeights} walks the real deck and states which children are
 *    which; it takes the measure function as a parameter so the walk is testable where geometry is
 *    fake (jsdom/happy-dom report every rect as 0×0).
 *  - the CEILING is the OTHER side's minimum honest form: the transcript never drops below
 *    {@link TRANSCRIPT_MIN_PX} — the 818 window's own reading floor, yielding to
 *    {@link TRANSCRIPT_MIN_SHORT_PX} below the shared 814 block-axis breakpoint (a short window has
 *    less to give, and starving the deck to protect a floor the viewport cannot honour would leave
 *    both regions unusable).
 *  - the floor WINS when the two cross (a window too short to satisfy both): decisions are
 *    incompressible, so the transcript is what yields. `Math.max(floor, Math.min(ceiling, …))` is that
 *    priority, not an accident of ordering.
 */

/** The transcript's minimum honest form: enough to read the last committed exchange. */
export const TRANSCRIPT_MIN_PX = 260;

/**
 * The same floor below the 814 block-axis breakpoint. A ~790px window has ~430px of centre column
 * after the surface chrome; holding the roomy floor there would leave the deck at its own floor with
 * no list at all, so the reading floor yields first — the transcript still keeps priority, it just
 * keeps less of it.
 */
export const TRANSCRIPT_MIN_SHORT_PX = 160;

/** Breathing room so the floor never lands exactly on a sub-pixel-rounded sum and clips a border. */
const DECK_SLACK_PX = 6;

/** A deck with no measurable occupant yet (first paint) still gets a grippable height. */
export const DECK_MIN_PX = 40;

/** One keyboard nudge of the grip — the keyboard path of the same boundary, not a second policy. */
export const DECK_KEY_STEP_PX = 24;

/**
 * The room the list BODY needs to be worth rendering as a list: the card's meta line plus one row.
 * Below it the list has no honest rendering as rows, so it takes its minimum honest form instead —
 * the one-line derived count ({@link SearchV2View.liveCountLabel}), which is the same count authority
 * the card's own header projects (L6), not a second one.
 */
export const LIST_BODY_MIN_PX = 72;

/** The transcript floor in force for this window height (the ONE place the breakpoint changes it). */
export function transcriptMinPx(shortViewport: boolean): number {
  return shortViewport ? TRANSCRIPT_MIN_SHORT_PX : TRANSCRIPT_MIN_PX;
}

/** The deck's floor: the sum of its incompressible occupants, plus slack. */
export function deckFloorFrom(heights: readonly number[]): number {
  let sum = DECK_SLACK_PX;
  for (const h of heights) {
    if (Number.isFinite(h) && h > 0) sum += h;
  }
  return Math.max(Math.round(sum), DECK_MIN_PX);
}

/**
 * The deck's incompressible occupants, measured. Two children are treated specially and both are
 * deliberate:
 *  - `.list` — the ONE compressible occupant (L7), excluded from the floor entirely.
 *  - `.run`  — a composite: its FEED is attention (compressible, and it scrolls), its CONTROLS are
 *              decisions. Only the controls enter the floor, which is what makes the floor grow the
 *              moment a run starts.
 * The grip itself is absolutely positioned chrome and contributes nothing.
 */
export function collectIncompressibleHeights(
  deck: Element,
  measure: (el: Element) => number,
): number[] {
  const heights: number[] = [];
  for (const child of Array.from(deck.children)) {
    if (child.classList.contains('grip') || child.classList.contains('list')) continue;
    if (child.classList.contains('run')) {
      const controls = child.querySelector('.run-controls');
      if (controls) heights.push(measure(controls));
      continue;
    }
    heights.push(measure(child));
  }
  return heights;
}

export interface DeckClampInput {
  /** The deck's height when the drag began. */
  readonly startHeightPx: number;
  /** Upward drag is positive — the deck grows as the pointer rises (`startY - clientY`). */
  readonly deltaPx: number;
  /** The content-derived floor from {@link deckFloorFrom}. */
  readonly floorPx: number;
  /** The centre column's height — everything the transcript and the deck share. */
  readonly availablePx: number;
  /** The transcript's minimum honest form from {@link transcriptMinPx}. */
  readonly transcriptMinPx: number;
}

/** The clamped deck height for a drag. Pure: the view supplies the measurements. */
export function clampDeckHeight(input: DeckClampInput): number {
  const ceiling = input.availablePx - input.transcriptMinPx;
  const wanted = input.startHeightPx + input.deltaPx;
  return Math.round(Math.max(input.floorPx, Math.min(ceiling, wanted)));
}

/**
 * Is the deck too short for the list to render as rows? True ⟹ the list takes its minimum honest
 * form (the count line) rather than a scroller two pixels tall.
 */
export function listYields(heightPx: number, floorPx: number): boolean {
  return heightPx - floorPx < LIST_BODY_MIN_PX;
}
