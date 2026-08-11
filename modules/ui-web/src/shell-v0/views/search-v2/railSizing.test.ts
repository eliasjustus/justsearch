// @vitest-environment happy-dom

/**
 * The horizontal boundaries' arithmetic and memory (tempdoc 818 slice 5, L13).
 *
 * The same split `deckSizing.test.ts` makes on the vertical axis: happy-dom reports every rect as
 * 0×0, so the CLAMP is exercised as the pure function it is, and the rendered half (which element a
 * grip moves, and that a remembered width is restored on mount) is asserted in
 * `SearchV2View.presentation.test.ts`. What is additionally testable here — and is not on the
 * vertical axis — is the MEMORY: L13's remember/reset asymmetry says rails remember, and the storage
 * edge is small enough to assert directly.
 */
import { describe, it, expect, beforeEach } from 'vitest';
// The rendered-geometry authority these floors are derived from. Imported, not transcribed — the
// point of the guard below is that the link cannot rot into a stale copy.
import proportionBaseline from '../../../../../../governance/ui-proportion-baseline.v1.json';
import { reconcileBoundaries, type BoundaryInput } from './boundaryReconciler.js';

/** A measured, roomy window with both boundaries automatic — the base each case perturbs. */
const RECONCILE_BASE: BoundaryInput = {
  availableWidthPx: 1600,
  availableHeightPx: 900,
  sessionRailChosenPx: null,
  documentRailChosenPx: null,
  documentOpen: false,
  deckChosenPx: null,
  deckFloorPx: 120,
  shortViewport: false,
  hasList: true,
  hasFeed: false,
};
import {
  CENTRE_MIN_PX,
  DOCUMENT_RAIL_FLOOR_PX,
  RAIL_LEGIBLE_PX,
  SESSION_RAIL_CEILING_PX,
  SESSION_RAIL_DEFAULT_PX,
  SESSION_RAIL_FLOOR_PX,
  clampRailWidth,
  documentRailCeiling,
  forgetRailWidth,
  railDefaultPx,
  railFloorPx,
  railYields,
  readStoredRailWidth,
  sessionRailCeiling,
  storageKey,
  storeRailWidth,
} from './railSizing.js';

beforeEach(() => {
  localStorage.clear();
});

describe('818 railSizing — the clamp (L13)', () => {
  const base = { floorPx: SESSION_RAIL_FLOOR_PX, ceilingPx: SESSION_RAIL_CEILING_PX };

  it('grows and shrinks with the gesture', () => {
    expect(clampRailWidth({ ...base, startWidthPx: 224, deltaPx: 60 })).toBe(284);
    expect(clampRailWidth({ ...base, startWidthPx: 224, deltaPx: -60 })).toBe(164);
  });

  it('stops at the rail’s own collapsed strip going in, and at twice its default going out', () => {
    expect(clampRailWidth({ ...base, startWidthPx: 224, deltaPx: -5_000 })).toBe(
      SESSION_RAIL_FLOOR_PX,
    );
    expect(clampRailWidth({ ...base, startWidthPx: 224, deltaPx: 5_000 })).toBe(
      SESSION_RAIL_CEILING_PX,
    );
    expect(SESSION_RAIL_CEILING_PX).toBe(SESSION_RAIL_DEFAULT_PX * 2);
  });

  it('gives the FLOOR priority when a window cannot honour both minimums', () => {
    // A 500px window with the document pane open: the centre's reading floor alone eats it, so the
    // sessions ceiling computes BELOW the rail's floor. The floor wins — the rail keeps its
    // minimum honest form rather than being clamped out of existence.
    const ceilingPx = sessionRailCeiling(500, DOCUMENT_RAIL_FLOOR_PX);
    expect(ceilingPx).toBeLessThan(SESSION_RAIL_FLOOR_PX);
    expect(
      clampRailWidth({ floorPx: SESSION_RAIL_FLOOR_PX, ceilingPx, startWidthPx: 224, deltaPx: 0 }),
    ).toBe(SESSION_RAIL_FLOOR_PX);
  });
});

describe('818 railSizing — the centre column’s reading floor is the ceiling (L13)', () => {
  it('the sessions rail may never take the centre below its reading floor', () => {
    // 1400 wide, document pane closed: 1400 − 0 − 384 = 1016, so the rail's own cap binds first.
    expect(sessionRailCeiling(1400, 0)).toBe(SESSION_RAIL_CEILING_PX);
    // 900 wide with the document pane at its floor: 900 − 384 − 384 = 132 is all that is left.
    expect(sessionRailCeiling(900, DOCUMENT_RAIL_FLOOR_PX)).toBe(132);
    expect(
      clampRailWidth({
        floorPx: SESSION_RAIL_FLOOR_PX,
        ceilingPx: sessionRailCeiling(900, DOCUMENT_RAIL_FLOOR_PX),
        startWidthPx: 224,
        deltaPx: 5_000,
      }),
    ).toBe(132);
  });

  it('the document region may never take the centre below its reading floor either', () => {
    expect(documentRailCeiling(1400, SESSION_RAIL_DEFAULT_PX)).toBe(
      1400 - SESSION_RAIL_DEFAULT_PX - CENTRE_MIN_PX,
    );
    const width = clampRailWidth({
      floorPx: DOCUMENT_RAIL_FLOOR_PX,
      ceilingPx: documentRailCeiling(1400, SESSION_RAIL_DEFAULT_PX),
      startWidthPx: DOCUMENT_RAIL_FLOOR_PX,
      deltaPx: 5_000,
    });
    // Whatever the gesture asked for, the centre keeps its floor.
    expect(1400 - SESSION_RAIL_DEFAULT_PX - width).toBeGreaterThanOrEqual(CENTRE_MIN_PX);
  });

  it('each rail’s floor is the minimum honest form of what it holds', () => {
    expect(railFloorPx('sessions')).toBe(SESSION_RAIL_FLOOR_PX);
    expect(railFloorPx('document')).toBe(DOCUMENT_RAIL_FLOOR_PX);
    expect(railDefaultPx('sessions')).toBe(SESSION_RAIL_DEFAULT_PX);
    expect(railDefaultPx('document')).toBe(DOCUMENT_RAIL_FLOOR_PX);
  });
});

describe('818 railSizing — the rail’s minimum honest form (L13)', () => {
  it('yields to the collapsed strip below the legible width, and only below it', () => {
    expect(railYields(SESSION_RAIL_FLOOR_PX)).toBe(true);
    expect(railYields(RAIL_LEGIBLE_PX - 1)).toBe(true);
    expect(railYields(RAIL_LEGIBLE_PX)).toBe(false);
    expect(railYields(SESSION_RAIL_DEFAULT_PX)).toBe(false);
  });
});

describe('818 railSizing — rails REMEMBER, and forget when returned to automatic (L13)', () => {
  it('a stored width is restored, per rail, without the two boundaries crossing over', () => {
    storeRailWidth('sessions', 300);
    storeRailWidth('document', 520);
    expect(readStoredRailWidth('sessions')).toBe(300);
    expect(readStoredRailWidth('document')).toBe(520);
    expect(storageKey('sessions')).not.toBe(storageKey('document'));
  });

  it('returning a boundary to automatic FORGETS it — the width was a choice, withdrawn', () => {
    storeRailWidth('sessions', 300);
    forgetRailWidth('sessions');
    expect(readStoredRailWidth('sessions')).toBeNull();
  });

  it('a corrupt entry is not a preference — there is nothing to clamp', () => {
    localStorage.setItem(storageKey('sessions'), 'not-a-number');
    expect(readStoredRailWidth('sessions')).toBeNull();
    localStorage.setItem(storageKey('document'), '0');
    expect(readStoredRailWidth('document')).toBeNull();
  });

  it('a width today’s window cannot honour is RESTORED, and clamped where it is applied', () => {
    // Replaces the previous "…is discarded, never restored" contract, which was the shape of
    // §6c finding 7: the reader judged a remembered width against a STATIC range, so it knew
    // nothing about the window actually on screen and a 448px rail reopened at 448px on a narrow
    // one. Discarding was also the wrong remedy even when it fired — it threw the preference away
    // permanently, so widening the window never brought it back.
    localStorage.setItem(storageKey('sessions'), '448');
    expect(readStoredRailWidth('sessions')).toBe(448);

    // On a window too narrow to honour it, the APPLIED width is clamped…
    const narrow = reconcileBoundaries({
      ...RECONCILE_BASE,
      availableWidthPx: 700,
      sessionRailChosenPx: 448,
    });
    expect(narrow.sessionRailPx).toBeLessThan(448);

    // …the MEMORY is untouched, so the preference survives the narrow window…
    expect(readStoredRailWidth('sessions')).toBe(448);

    // …and comes back in full once there is room for it again.
    const roomy = reconcileBoundaries({
      ...RECONCILE_BASE,
      availableWidthPx: 1600,
      sessionRailChosenPx: 448,
    });
    expect(roomy.sessionRailPx).toBe(448);
  });

  it('no memory means automatic — the absence of a preference is itself the default', () => {
    expect(readStoredRailWidth('sessions')).toBeNull();
    expect(readStoredRailWidth('document')).toBeNull();
  });
});

/**
 * The register link, made checkable (tempdoc 818 §6g C1).
 *
 * This module's two floors are not local inventions — the docblock derives each from a row in
 * `governance/ui-proportion-baseline.v1.json`, the rendered-geometry authority. Until now that
 * derivation was a PROSE citation: the numbers were borrowed from the register while the surface
 * declared no rows, so nothing would have noticed the register moving underneath them. That is the
 * `catalog-verbatim` failure shape, and this is its guard — the same dual-projection discipline
 * tempdoc 816 §4a.3 states ("one authority renders AND judges").
 *
 * It asserts EQUALITY, not merely presence: a floor that silently drifted from the register it cites
 * would still be a plausible number, which is exactly why a human reading the docblock would not
 * catch it.
 */
describe('818 §6g C1 — the clamp floors equal the register rows they cite', () => {
  const step = proportionBaseline.steps.find(
    (s: { uiShotStep: string }) => s.uiShotStep === 'chat-occlusion',
  );

  it('the register still carries the step these floors are derived from', () => {
    // Anti-vacuity: without this, a renamed step would make every lookup below undefined and the
    // equality assertions would silently compare undefined to undefined.
    expect(step, 'the chat-occlusion step is the floors’ cited source').toBeDefined();
  });

  it.each([
    ['.conversation', CENTRE_MIN_PX, 'the centre column’s reading floor'],
    ['.document-pane', DOCUMENT_RAIL_FLOOR_PX, 'the readable document column'],
  ])('%s minWidthPx is %i — %s', (selector, constant) => {
    const row = step?.elements.find((e: { selector: string }) => e.selector === selector);
    expect(row, `${selector} is registered`).toBeDefined();
    expect((row as { minWidthPx?: number }).minWidthPx).toBe(constant);
  });
});
