// @vitest-environment happy-dom

/**
 * The movable boundary's arithmetic (tempdoc 818 slice 4, L7/L13).
 *
 * These are the assertions a rendered test cannot make: happy-dom (like jsdom) reports every rect as
 * 0×0, so the CLAMP has to be exercised as the pure function it is. The DOM half — which children of
 * the real deck are incompressible — is asserted in `SearchV2View.presentation.test.ts` by walking the
 * live shadow DOM with a measure function of the test's own, which is the honest split: the structure
 * is real, only the pixels are the test's.
 */
import { describe, it, expect } from 'vitest';
import {
  DECK_MIN_PX,
  LIST_BODY_MIN_PX,
  TRANSCRIPT_MIN_PX,
  TRANSCRIPT_MIN_SHORT_PX,
  FEED_BODY_MIN_PX,
  clampDeckHeight,
  collectIncompressibleHeights,
  deckFloorFrom,
  evictionFor,
  listYields,
  transcriptMinPx,
} from './deckSizing.js';

describe('818 deckSizing — the floor (L7)', () => {
  it('sums every measured occupant plus slack', () => {
    // 40 (band) + 24 (chips) + 96 (run controls) + 6 slack.
    expect(deckFloorFrom([40, 24, 96])).toBe(166);
  });

  it('ignores unmeasurable occupants rather than poisoning the sum with NaN', () => {
    expect(deckFloorFrom([40, Number.NaN, -12])).toBe(46);
  });

  it('never falls below the minimum grippable deck', () => {
    expect(deckFloorFrom([])).toBe(DECK_MIN_PX);
  });

  it('excludes the list body and includes the run CONTROLS — the decision cannot be dragged away', () => {
    const deck = document.createElement('div');
    deck.innerHTML = `
      <button class="grip"></button>
      <div class="band"></div>
      <div class="list"></div>
      <div class="run"><div class="feed"></div><div class="run-controls"></div></div>`;
    const heights = new Map<string, number>([
      ['grip', 10],
      ['band', 48],
      ['list', 400],
      ['feed', 300],
      ['run-controls', 96],
    ]);
    const measure = (el: Element): number => heights.get(el.className) ?? 0;

    expect(collectIncompressibleHeights(deck, measure)).toEqual([48, 96]);
    // The floor is ≥ the controls' own height by construction, whatever else the deck holds.
    expect(deckFloorFrom(collectIncompressibleHeights(deck, measure))).toBeGreaterThanOrEqual(96);
  });
});

describe('818 deckSizing — the clamp (L13)', () => {
  const base = { floorPx: 120, availablePx: 800, transcriptMinPx: TRANSCRIPT_MIN_PX };

  it('grows the deck as the pointer rises and shrinks it as the pointer falls', () => {
    expect(clampDeckHeight({ ...base, startHeightPx: 300, deltaPx: 80 })).toBe(380);
    expect(clampDeckHeight({ ...base, startHeightPx: 300, deltaPx: -80 })).toBe(220);
  });

  it('stops at the transcript’s minimum honest form — the other side of the boundary', () => {
    // 800 available − 260 transcript floor = 540 is everything the deck may ever take.
    expect(clampDeckHeight({ ...base, startHeightPx: 300, deltaPx: 5_000 })).toBe(540);
  });

  it('stops at the content-derived floor going the other way', () => {
    expect(clampDeckHeight({ ...base, startHeightPx: 300, deltaPx: -5_000 })).toBe(120);
  });

  it('gives the floor priority when the window cannot honour both minimums', () => {
    // A 300px column with a 120px deck floor: the ceiling (300 − 260 = 40) is BELOW the floor.
    // Decisions are incompressible, so the transcript is what yields.
    expect(
      clampDeckHeight({ ...base, availablePx: 300, startHeightPx: 200, deltaPx: -5_000 }),
    ).toBe(120);
  });

  it('814 §D6 — a short window yields the reading floor, and yields it only there', () => {
    expect(transcriptMinPx(false)).toBe(TRANSCRIPT_MIN_PX);
    expect(transcriptMinPx(true)).toBe(TRANSCRIPT_MIN_SHORT_PX);
    const short = clampDeckHeight({
      ...base,
      transcriptMinPx: transcriptMinPx(true),
      availablePx: 430,
      startHeightPx: 200,
      deltaPx: 5_000,
    });
    expect(short).toBe(430 - TRANSCRIPT_MIN_SHORT_PX);
  });
});

describe('818 deckSizing — the list’s minimum honest form (L7)', () => {
  it('yields when the deck has no room left for a row', () => {
    expect(listYields(120, 120)).toBe(true);
    expect(listYields(120 + LIST_BODY_MIN_PX - 1, 120)).toBe(true);
  });

  it('does not yield while a row still fits', () => {
    expect(listYields(120 + LIST_BODY_MIN_PX, 120)).toBe(false);
    expect(listYields(400, 120)).toBe(false);
  });
});

/**
 * L7 as amended (tempdoc 818 §6e.4): under pressure a region first COMPRESSES and then EVICTS, in a
 * DECLARED order, and decisions do neither. These assert the ORDER, which is the part that cannot be
 * left to flexbox — the deck's floor is its incompressible occupants, so without eviction the excess
 * leaves the screen, and for a live run that means a held decision leaves the screen.
 */
describe('818 deckSizing — the eviction order (L7 amended)', () => {
  const base = {
    availablePx: 900,
    deckFloorPx: 120,
    transcriptMinPx: TRANSCRIPT_MIN_PX,
    hasList: true,
    hasFeed: true,
  };

  it('nothing yields while the column can hold every body', () => {
    expect(evictionFor(base)).toEqual({ listYields: false, feedYields: false });
  });

  it('the LIST yields first — its count line states the same fact its rows do', () => {
    // Room for the floor + the feed, but not for the list as well.
    const availablePx = TRANSCRIPT_MIN_PX + base.deckFloorPx + FEED_BODY_MIN_PX + 1;
    expect(evictionFor({ ...base, availablePx })).toEqual({
      listYields: true,
      feedYields: false,
    });
  });

  it('the FEED yields only once the list already has, never before it', () => {
    const availablePx = TRANSCRIPT_MIN_PX + base.deckFloorPx;
    expect(evictionFor({ ...base, availablePx })).toEqual({
      listYields: true,
      feedYields: true,
    });
  });

  it('a body that is not mounted is not evicted — absence is not a yield', () => {
    const availablePx = TRANSCRIPT_MIN_PX;
    expect(evictionFor({ ...base, availablePx, hasFeed: false })).toEqual({
      listYields: true,
      feedYields: false,
    });
    expect(evictionFor({ ...base, availablePx, hasList: false })).toEqual({
      listYields: false,
      feedYields: true,
    });
  });

  it('the DECISIONS are never what yields: the floor is spent before any body is', () => {
    // Even with nothing left at all, eviction only ever reports on the two BODIES — the floor (which
    // carries the run controls) is an input it never proposes to reduce.
    const verdict = evictionFor({ ...base, availablePx: 0 });
    expect(Object.keys(verdict).sort()).toEqual(['feedYields', 'listYields']);
    expect(verdict).toEqual({ listYields: true, feedYields: true });
  });

  it('a short window yields sooner, because its transcript floor is smaller', () => {
    // The same column height, judged against the two different reading floors: the short floor
    // leaves more for the deck, so a state that evicts under the roomy floor need not under it.
    const availablePx = TRANSCRIPT_MIN_SHORT_PX + base.deckFloorPx + FEED_BODY_MIN_PX + LIST_BODY_MIN_PX;
    expect(evictionFor({ ...base, availablePx, transcriptMinPx: TRANSCRIPT_MIN_SHORT_PX })).toEqual({
      listYields: false,
      feedYields: false,
    });
    expect(evictionFor({ ...base, availablePx, transcriptMinPx: TRANSCRIPT_MIN_PX }).listYields).toBe(
      true,
    );
  });
});
