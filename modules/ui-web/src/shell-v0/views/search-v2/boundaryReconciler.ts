// SPDX-License-Identifier: Apache-2.0
/**
 * boundaryReconciler — the ONE place every movable boundary is resolved (tempdoc 818 §6g C3, L13).
 *
 * L13 says every movable boundary is clamped by the minimum honest forms on both sides. `deckSizing`
 * and `railSizing` are that rule's ARITHMETIC; this module is its LIFECYCLE, and the distinction is
 * what §6c findings 3 and 7 were made of. Both clamps existed and both were correct — they were just
 * only ever evaluated inside a gesture. A width remembered from a wider window was applied unclamped
 * at mount, and neither axis was re-evaluated when the window itself changed, so the centre column
 * could sit below its own reading floor with every clamp in the codebase agreeing it was fine.
 *
 * The remedy is structural rather than a third call to the same maths: reconciliation has ONE entry
 * point ({@link reconcileBoundaries}) and exactly three callers — MOUNT (restore a remembered
 * width), RESIZE (the window changed under us) and GESTURE END (adopt a chosen one). "Clamped only
 * at gesture time" is then not a bug that was fixed but a state that cannot be expressed.
 *
 * Two properties this module is shaped to keep:
 *
 *  - **Pure.** Every measurement arrives as an argument, so the whole policy is testable where
 *    geometry is fake. This is not tidiness: `ResizeObserver` exists in happy-dom but never fires
 *    (measured, 818 §6f(f)), so a controller-driven test would silently assert nothing. The
 *    controller that owns the observer is therefore a trigger with no judgment in it, and every
 *    decision lives here.
 *  - **Clamp-on-apply, never clamp-on-store.** A remembered width is a PREFERENCE; the clamp is a
 *    fact about the window currently on screen. {@link reconcileBoundaries} returns what to RENDER
 *    and never rewrites what is remembered, so a rail narrowed to fit a small window returns to its
 *    chosen width when the window grows again. Discarding the memory instead — the behaviour this
 *    replaces — loses the preference permanently for being briefly un-honourable.
 */

import {
  clampDeckHeight,
  evictionFor,
  listYields,
  transcriptMinPx,
  type Eviction,
} from './deckSizing.js';
import {
  clampRailWidth,
  documentRailCeiling,
  railFloorPx,
  railYields,
  sessionRailCeiling,
} from './railSizing.js';

/**
 * Everything reconciliation needs to know, measured by the caller. Widths and heights are the box
 * the regions actually share — the caller subtracts the chrome (grips and the track's gaps) before
 * handing it over, because a ceiling computed against a box the regions do not get is a ceiling that
 * lets the centre column fall under its floor by exactly the chrome (§6c finding 13b).
 */
export interface BoundaryInput {
  /** The horizontal track's width, MINUS its grips and inter-region gaps. */
  readonly availableWidthPx: number;
  /** The centre column's height — what the transcript and the deck share. */
  readonly availableHeightPx: number;
  /** The user's chosen session-rail width, or null for automatic. Never mutated here. */
  readonly sessionRailChosenPx: number | null;
  /** The user's chosen document-region width, or null for automatic. Never mutated here. */
  readonly documentRailChosenPx: number | null;
  /** Is the document region mounted at all? Its width is only a claim on the track when it is. */
  readonly documentOpen: boolean;
  /** The user's chosen deck height, or null for automatic. */
  readonly deckChosenPx: number | null;
  /** The deck's incompressible occupants, measured (`deckFloorFrom` over the live deck). */
  readonly deckFloorPx: number;
  /** Tempdoc 814's block-axis breakpoint verdict, which selects the transcript's floor. */
  readonly shortViewport: boolean;
  readonly hasList: boolean;
  readonly hasFeed: boolean;
}

/** What to RENDER. Null keeps a boundary automatic; a number is a clamped, applied width/height. */
export interface BoundaryState {
  readonly sessionRailPx: number | null;
  readonly documentRailPx: number | null;
  readonly deckHeightPx: number | null;
  /** L13 — below its legible width the session rail takes its collapsed strip form. */
  readonly railCollapsed: boolean;
  /** L7 (amended) — which deck bodies are evicted to their minimum honest form. */
  readonly eviction: Eviction;
}

/** Apply a chosen width against live bounds, or leave the boundary automatic. */
function applyRail(
  chosenPx: number | null,
  floorPx: number,
  ceilingPx: number,
): number | null {
  if (chosenPx === null) return null;
  // `deltaPx: 0` is the gesture clamp evaluated at rest: the same arithmetic, no second policy.
  return clampRailWidth({ startWidthPx: chosenPx, deltaPx: 0, floorPx, ceilingPx });
}

/**
 * Resolve every boundary against the window as it is right now. Pure; the caller supplies the
 * measurements and decides what to do with the result.
 *
 * Order matters and is not arbitrary: the document region is resolved FIRST because the session
 * rail's ceiling is "whatever leaves the centre column its reading floor beside the region on the
 * far side" — so the sessions rail must be clamped against the document width that will actually be
 * applied, not the one that was merely remembered. Resolving them in the other order lets two
 * individually-legal widths sum to a starved centre column.
 */
export function reconcileBoundaries(input: BoundaryInput): BoundaryState {
  // An UNMEASURED box is not a small one. Before first layout, inside a `display: none` ancestor, or
  // in any environment that reports no geometry, every rect is 0 — and treating that as "no room"
  // would clamp every rail to its floor and evict every body, i.e. do maximum damage on the strength
  // of no information at all. The honest answer to an absent measurement is to change nothing, the
  // same rule `projectContextHorizon` follows when its denominator is missing. Each axis is judged
  // separately because they are measured separately.
  const widthKnown = input.availableWidthPx > 0;
  const heightKnown = input.availableHeightPx > 0;

  const documentPx = input.documentOpen && widthKnown
    ? applyRail(
        input.documentRailChosenPx,
        railFloorPx('document'),
        documentRailCeiling(input.availableWidthPx, 0),
      )
    : input.documentRailChosenPx;

  // What the document region actually claims on the track. An automatic (null) width still occupies
  // its floor, so the sessions rail's ceiling must account for it either way.
  const documentClaimPx = input.documentOpen ? (documentPx ?? railFloorPx('document')) : 0;

  const sessionPx = widthKnown
    ? applyRail(
        input.sessionRailChosenPx,
        railFloorPx('sessions'),
        sessionRailCeiling(input.availableWidthPx, documentClaimPx),
      )
    : input.sessionRailChosenPx;

  const deckHeightPx =
    input.deckChosenPx === null || !heightKnown
      ? input.deckChosenPx
      : clampDeckHeight({
          startHeightPx: input.deckChosenPx,
          deltaPx: 0,
          floorPx: input.deckFloorPx,
          availablePx: input.availableHeightPx,
          transcriptMinPx: transcriptMinPx(input.shortViewport),
        });

  return {
    sessionRailPx: sessionPx,
    documentRailPx: documentPx,
    deckHeightPx,
    // The collapsed strip follows the APPLIED width, not the remembered one: a rail clamped narrow
    // by a small window is narrow on screen, and rendering wrapping rows into it would be the
    // squashed form L13 exists to forbid.
    railCollapsed: sessionPx !== null && railYields(sessionPx),
    eviction: mergeEviction(
      // The deck the user SIZED is too short to hold rows. This is a different question from the one
      // `evictionFor` answers — it is about the deck's own two numbers, not about the column's
      // budget — and it must be asked even where the column is unmeasured, because the user's chosen
      // height is a real measurement whatever the surrounding box reports.
      deckHeightPx !== null && listYields(deckHeightPx, input.deckFloorPx),
      heightKnown
        ? evictionFor({
          availablePx: input.availableHeightPx,
          // A user-chosen deck height raises the floor eviction reasons about: the deck is not going
          // to shrink below what the user pinned it to, so its bodies are what must give.
          deckFloorPx: Math.max(input.deckFloorPx, deckHeightPx ?? 0),
          transcriptMinPx: transcriptMinPx(input.shortViewport),
          hasList: input.hasList,
          hasFeed: input.hasFeed,
        })
        : { listYields: false, feedYields: false },
    ),
  };
}

/** The list yields if EITHER pressure says so; the feed only answers to the column's budget. */
function mergeEviction(deckTooShortForList: boolean, byBudget: Eviction): Eviction {
  return {
    listYields: deckTooShortForList || byBudget.listYields,
    feedYields: byBudget.feedYields,
  };
}
