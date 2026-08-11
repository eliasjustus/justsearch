// @vitest-environment happy-dom

/**
 * The reconciliation seam (tempdoc 818 §6g C3) — the LIFECYCLE half of L13.
 *
 * `deckSizing` and `railSizing` already tested the arithmetic, and it was never wrong. §6c findings
 * 3 and 7 were that the arithmetic only ever ran inside a gesture, so these assert the property the
 * gesture-time clamps could not: that a boundary is resolved against the window as it is NOW,
 * whatever caused it to be asked.
 */
import { describe, it, expect } from 'vitest';
import { reconcileBoundaries, type BoundaryInput } from './boundaryReconciler.js';
import { CENTRE_MIN_PX, SESSION_RAIL_FLOOR_PX, DOCUMENT_RAIL_FLOOR_PX } from './railSizing.js';
import { LIST_BODY_MIN_PX, TRANSCRIPT_MIN_PX } from './deckSizing.js';

const BASE: BoundaryInput = {
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

describe('818 boundaryReconciler — a boundary is resolved against the window as it is now', () => {
  it('automatic stays automatic: reconciliation never invents a width', () => {
    const r = reconcileBoundaries(BASE);
    expect(r.sessionRailPx).toBeNull();
    expect(r.documentRailPx).toBeNull();
    expect(r.deckHeightPx).toBeNull();
  });

  it('§6c finding 7 — a remembered width is clamped so the centre column keeps its floor', () => {
    // The exact shape of the finding: 448px chosen on a wide monitor, reopened at 700px.
    const r = reconcileBoundaries({
      ...BASE,
      availableWidthPx: 700,
      sessionRailChosenPx: 448,
    });
    expect(r.sessionRailPx).not.toBeNull();
    expect(700 - (r.sessionRailPx as number)).toBeGreaterThanOrEqual(CENTRE_MIN_PX);
  });

  it('the floor wins the crossing case — a window too narrow for both degrades the rail', () => {
    const r = reconcileBoundaries({
      ...BASE,
      availableWidthPx: 300,
      sessionRailChosenPx: 400,
    });
    expect(r.sessionRailPx).toBe(SESSION_RAIL_FLOOR_PX);
    // …and at its floor the rail takes its collapsed strip form rather than squashed rows.
    expect(r.railCollapsed).toBe(true);
  });

  it('the document region is resolved FIRST, so two legal widths cannot starve the centre', () => {
    // Each width is individually honourable; together they are not. Resolving the sessions rail
    // against the document width that will actually APPLY is what catches it.
    const r = reconcileBoundaries({
      ...BASE,
      availableWidthPx: 1000,
      documentOpen: true,
      documentRailChosenPx: 500,
      sessionRailChosenPx: 400,
    });
    const session = r.sessionRailPx as number;
    const document = r.documentRailPx as number;
    expect(1000 - session - document).toBeGreaterThanOrEqual(CENTRE_MIN_PX);
  });

  it('a closed document region makes no claim on the track', () => {
    const closed = reconcileBoundaries({ ...BASE, availableWidthPx: 900, sessionRailChosenPx: 448 });
    const open = reconcileBoundaries({
      ...BASE,
      availableWidthPx: 900,
      sessionRailChosenPx: 448,
      documentOpen: true,
    });
    // Opening the document region tightens what the sessions rail may take, without either
    // boundary knowing about the other.
    expect(open.sessionRailPx as number).toBeLessThan(closed.sessionRailPx as number);
  });

  it('an automatic document region still occupies its floor when the rail is clamped', () => {
    const r = reconcileBoundaries({
      ...BASE,
      availableWidthPx: 1000,
      documentOpen: true,
      documentRailChosenPx: null,
      sessionRailChosenPx: 448,
    });
    expect(1000 - (r.sessionRailPx as number) - DOCUMENT_RAIL_FLOOR_PX).toBeGreaterThanOrEqual(
      CENTRE_MIN_PX,
    );
  });
});

describe('818 boundaryReconciler — an UNMEASURED box is not a small one', () => {
  const unmeasured = { ...BASE, availableWidthPx: 0, availableHeightPx: 0 };

  it('changes nothing rather than clamping everything to its floor', () => {
    const r = reconcileBoundaries({
      ...unmeasured,
      sessionRailChosenPx: 448,
      documentRailChosenPx: 600,
      documentOpen: true,
      deckChosenPx: 500,
    });
    expect(r.sessionRailPx).toBe(448);
    expect(r.documentRailPx).toBe(600);
    expect(r.deckHeightPx).toBe(500);
  });

  it('evicts nothing — maximum damage on no information is the failure this guards', () => {
    const r = reconcileBoundaries({ ...unmeasured, hasList: true, hasFeed: true });
    expect(r.eviction).toEqual({ listYields: false, feedYields: false });
  });

  it('judges the two axes separately, because they are measured separately', () => {
    const r = reconcileBoundaries({
      ...BASE,
      availableWidthPx: 700,
      availableHeightPx: 0,
      sessionRailChosenPx: 448,
      hasFeed: true,
    });
    // Width is known, so the rail is clamped…
    expect(r.sessionRailPx).toBeLessThan(448);
    // …while the unmeasured height evicts nothing.
    expect(r.eviction).toEqual({ listYields: false, feedYields: false });
  });
});

describe('818 boundaryReconciler — eviction reaches the deck the user sized', () => {
  it('a deck pinned too short for rows yields its list, even where the column is unmeasured', () => {
    // The deck's own two numbers are a real measurement whatever the surrounding box reports, so
    // this question is asked independently of the column's budget.
    const r = reconcileBoundaries({
      ...BASE,
      availableHeightPx: 0,
      deckChosenPx: 120 + LIST_BODY_MIN_PX - 1,
      deckFloorPx: 120,
    });
    expect(r.eviction.listYields).toBe(true);
  });

  it('a user-chosen deck height is itself clamped so the transcript keeps its floor', () => {
    const r = reconcileBoundaries({ ...BASE, availableHeightPx: 600, deckChosenPx: 5000 });
    expect(600 - (r.deckHeightPx as number)).toBeGreaterThanOrEqual(TRANSCRIPT_MIN_PX);
  });
});
