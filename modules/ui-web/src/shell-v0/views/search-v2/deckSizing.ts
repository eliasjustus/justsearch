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
 * The same floor below the 814 block-axis breakpoint. A short window has less to give, so the
 * reading floor yields first — the transcript still keeps priority, it just keeps less of it.
 *
 * The measured figure, since a wrong one lived here for two slices: at a 1366×790 viewport the
 * centre column is 642px (ui-shot `search-v2-small`, tempdoc 818 §6g C2), not the ~430px this
 * comment used to assert. That mis-estimate is what made §6c finding 3 read as a cliff at 790px
 * when it is really a continuum — a deck carrying the run's occupants reaches ~625px, which fits
 * 642px while starving the transcript, and only clips below ~740px of viewport height.
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

/**
 * The room the run FEED needs to be worth rendering as a feed: one entry line plus the step line's
 * neighbour. Below it the feed has no honest rendering as a list of events, so it takes its own
 * minimum honest form — a count of what the run has done — exactly as the list takes its count line.
 */
export const FEED_BODY_MIN_PX = 64;

export interface EvictionInput {
  /** The centre column's height — everything the transcript and the deck share. */
  readonly availablePx: number;
  /** The deck's incompressible occupants (band, chips, meters, and any run CONTROLS). */
  readonly deckFloorPx: number;
  /** The transcript's minimum honest form for this window height. */
  readonly transcriptMinPx: number;
  readonly hasList: boolean;
  readonly hasFeed: boolean;
}

/** Which deck bodies have been evicted to their minimum honest form, in the declared order. */
export interface Eviction {
  readonly listYields: boolean;
  readonly feedYields: boolean;
}

/**
 * L7 (as amended, tempdoc 818 §6e.4) — a region under pressure first COMPRESSES and then EVICTS,
 * in a DECLARED order, and decisions never do either.
 *
 * The order is authored here rather than emerging from flexbox because flexbox's own answer is the
 * wrong one: the deck cannot shrink (its floor is its incompressible occupants) and `.centre` clips,
 * so without eviction the excess leaves the screen — which for a live run means a held decision
 * leaves the screen, the one thing L7 forbids. Eviction is what lets the deck give room back.
 *
 * The list yields before the feed because a search list is the deck's oldest and most re-derivable
 * occupant — its count line states the same fact the rows do (L6) — whereas the feed is the only
 * live account of a run in progress. The transcript yields last and by flexbox (it is the one
 * `flex: 1 1 auto` occupant), which is the same priority `clampDeckHeight` gives it on the manual
 * boundary: the reading floor is a floor, not a guarantee, and it is the last thing to bend.
 */
export function evictionFor(input: EvictionInput): Eviction {
  // What the deck may occupy while the transcript still keeps its own minimum honest form.
  const budgetPx = input.availablePx - input.transcriptMinPx;
  const feedNeed = input.hasFeed ? FEED_BODY_MIN_PX : 0;
  const listNeed = input.hasList ? LIST_BODY_MIN_PX : 0;

  if (input.deckFloorPx + listNeed + feedNeed <= budgetPx) {
    return { listYields: false, feedYields: false };
  }
  if (input.deckFloorPx + feedNeed <= budgetPx) {
    return { listYields: input.hasList, feedYields: false };
  }
  return { listYields: input.hasList, feedYields: input.hasFeed };
}
