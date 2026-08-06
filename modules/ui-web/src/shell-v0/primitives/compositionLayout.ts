// SPDX-License-Identifier: Apache-2.0
import { css, unsafeCSS, type CSSResult } from 'lit';

/**
 * Tempdoc 565 §13 Pillar B — the Composition primitive.
 *
 * A surface DECLARES its multi-zone composition as a set of {@link ZoneDecl}s; this GENERATES the grid
 * frame (the responsive `grid-template-columns` + per-zone placement) as a total function of the
 * declaration. Because the only way to compose the governed content is to declare zones and let this
 * emit the grid, a SECOND hand-authored multi-zone grid is unrepresentable for that content (564's
 * "Gate is the floor, not the mechanism" — generation, not a grep gate, is the anti-drift).
 *
 * The primitive owns the zone FRAME — which zones exist, their placement, the margin balance, and the
 * empty-collapse (a zone whose element renders nothing yields a `minmax(0,…)` track that collapses by
 * construction). It does NOT own the zone CONTENTS — a column's internal layout, its scroll, its
 * reading measure stay the component's own CSS (the 559 §8 frame/contents cut). This is a primitive,
 * NOT a `jf-*-surface`: it nests inside a view/surface body (gate-permitted — see §13.6).
 */
export interface ZoneDecl {
  /** The zone element's CSS class (the selector the grid places). Omit for a pure-margin track. */
  readonly selector?: string;
  /** The wide-viewport track width policy, e.g. `'minmax(0, 40rem)'`. */
  readonly track: string;
  /** The wide-viewport grid column (1-based). Omit for a pure-margin track (no element to place). */
  readonly col?: number;
  /** True → the zone participates only at the wide breakpoint (it collapses out of the narrow stack). */
  readonly wideOnly?: boolean;
}

export interface ComposeOpts {
  /** The grid container's CSS class (e.g. `'.conversation-zone'`). */
  readonly container: string;
  /**
   * The `container-name` of the SURFACE box the breakpoint is evaluated against — an ancestor of
   * {@link container} that declares `container-type: inline-size` (a box cannot query itself).
   */
  readonly containerName: string;
  /** The wide breakpoint, e.g. `'64rem'`. */
  readonly breakpoint: string;
  /** The inter-zone gap (a token reference or a literal). */
  readonly gap: string;
}

/**
 * Tempdoc 814 §D6 — THE single BLOCK-axis breakpoint authority.
 *
 * The inline axis is owned above ({@link composeGridStyles} composes the one `@container` query the
 * surface's grid frame commits on). Height had no owner at all — no height-based `@media`/`@container`
 * query existed anywhere in the chat-surface layout — which is how chrome accreted to ~60% of a
 * ~790px window with every band individually justified. This constant is that owner: every block-axis
 * yield (the Detailed-banner gate, the document-pane floor) reads THIS number, so "what counts as a
 * short window" is one decision, not one per band.
 *
 * VIEWPORT `@media`, not `@container` (§B.12): the surface box declares `container-type: inline-size`,
 * so it cannot query its own block size, and `container-type: size` collapses a height-indeterminate
 * box — strictly riskier here for no benefit, because the surface fills the viewport minus fixed Shell
 * chrome, making the viewport height a faithful proxy.
 */
export const SHORT_VIEWPORT_MAX_HEIGHT_PX = 820;

/** The block-axis breakpoint as a media condition — the JS half (`matchMedia`) of the same decision. */
export const SHORT_VIEWPORT_QUERY = `(max-height: ${SHORT_VIEWPORT_MAX_HEIGHT_PX}px)`;

/** The `@media` prelude, for splicing the block-axis breakpoint into a surface stylesheet's `css`. */
export const shortViewportMedia: CSSResult = unsafeCSS(`@media ${SHORT_VIEWPORT_QUERY}`);

type ShortViewportListener = (short: boolean) => void;
const shortViewportListeners = new Set<ShortViewportListener>();
let shortViewportMql: MediaQueryList | null = null;

/**
 * Is the window below the block-axis breakpoint? FALSE when `matchMedia` is unavailable (SSR, unit
 * tests): the roomy branch is the safe default — an unknown viewport keeps every band's full form
 * rather than silently collapsing chrome the user asked for. Mirrors `isWideLayout`'s own
 * unavailable-means-roomy fallback in `state/responsiveState.ts`.
 */
export function isShortViewport(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  return window.matchMedia(SHORT_VIEWPORT_QUERY).matches;
}

/**
 * Subscribe to block-axis breakpoint crossings. Fires once immediately with the current value (the
 * `subscribeWide` contract), so a consumer needs no separate initial read.
 */
export function subscribeShortViewport(listener: ShortViewportListener): () => void {
  if (
    shortViewportMql === null &&
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function'
  ) {
    shortViewportMql = window.matchMedia(SHORT_VIEWPORT_QUERY);
    shortViewportMql.addEventListener('change', () => {
      const short = isShortViewport();
      for (const l of shortViewportListeners) l(short);
    });
  }
  shortViewportListeners.add(listener);
  listener(isShortViewport());
  return () => {
    shortViewportListeners.delete(listener);
  };
}

/** The narrow / wide `grid-template-columns` track list for a zone-set (exported for the unit assertion). */
export function trackTemplate(zones: readonly ZoneDecl[], viewport: 'narrow' | 'wide'): string {
  if (viewport === 'narrow') {
    // Only non-wideOnly zones participate narrow; each stacks in a single column.
    const live = zones.filter((z) => !z.wideOnly);
    return live.length > 0 ? live.map(() => 'minmax(0, 1fr)').join(' ') : 'minmax(0, 1fr)';
  }
  return zones.map((z) => z.track).join(' ');
}

/**
 * The FRAME half of a zone-set: the container's responsive track list, its gap, and the §13.9
 * centering decision — everything that fixes WHERE the columns fall, with no per-zone placement.
 *
 * Shared by {@link composeGridStyles} (which adds the zone placements) and
 * {@link alignToZoneStyles} (which places a SECOND element's children in one of those columns,
 * tempdoc 816 §5). Two consumers, one track authority: a container that wants to line up with the
 * zones cannot hand-copy a track list that has since changed.
 *
 * §13.9 — the outer margins are CAPPED (`minmax(0, 8rem)`) and the spine/rail tracks are
 * content-sized (they collapse when unmounted), so the track group is narrower than the container at
 * a wide viewport; `justify-content: center` centres that bounded group rather than leaving it
 * left-shifted against an unbounded gutter. This lived as a hand-authored `.conversation-zone` rule
 * until 816 — where it was a second authority on column position, invisible to any consumer trying
 * to align with the zones.
 */
function frameStyles(zones: readonly ZoneDecl[], opts: ComposeOpts): CSSResult {
  const container = unsafeCSS(opts.container);
  const containerName = unsafeCSS(opts.containerName);
  const narrow = unsafeCSS(trackTemplate(zones, 'narrow'));
  const wide = unsafeCSS(trackTemplate(zones, 'wide'));
  const gap = unsafeCSS(opts.gap);
  const bp = unsafeCSS(opts.breakpoint);
  return css`
    ${container} {
      display: grid;
      grid-template-columns: ${narrow};
      gap: ${gap};
    }
    @container ${containerName} (min-width: ${bp}) {
      ${container} {
        grid-template-columns: ${wide};
        width: 100%;
        justify-content: center;
      }
    }
  `;
}

/**
 * §13 Pillar B — generate the grid-frame {@link CSSResult} for a declared zone-set. Faithful to the
 * de-risk Probe S2 (reproduces the prior hand-authored grid exactly). Empty-collapse needs no branch:
 * a zone whose element is unmounted leaves its `minmax(0,…)` track to collapse to zero width.
 *
 * 798 round 8 — the breakpoint is a `@container` query against the SURFACE box, not a `@media` query
 * against the viewport. A zone-set's declared track minimums are a budget on the width the CONTAINER
 * gets; the viewport is a different, always-larger number (it still contains the Shell rail and the
 * surface's own padding), so a media query committed the grid to multi-column at widths where the
 * tracks provably did not fit and the surface overflowed by the difference. Querying the box the tracks
 * are actually laid out in makes that class of error unrepresentable rather than re-tuned.
 */
export function composeGridStyles(zones: readonly ZoneDecl[], opts: ComposeOpts): CSSResult {
  const containerName = unsafeCSS(opts.containerName);
  const bp = unsafeCSS(opts.breakpoint);
  // Lit's `css` tag accepts a CSSResult or number per interpolation (NOT an array, unlike `html`), so
  // the per-zone placements are built as one trusted string (the zone selectors are module constants,
  // not user input) and spliced via unsafeCSS.
  const placements = unsafeCSS(
    zones
      .filter((z) => z.selector !== undefined && z.col !== undefined)
      .map((z) => `${z.selector} { grid-column: ${z.col}; }`)
      .join('\n      '),
  );
  return css`
    ${frameStyles(zones, opts)}
    @container ${containerName} (min-width: ${bp}) {
      ${placements}
    }
  `;
}

export interface AlignOpts extends ComposeOpts {
  /**
   * The zone whose column the aligned children adopt — a `selector` that appears in `zones` WITH a
   * declared `col` (e.g. `'.conversation'`). Resolved from the zone declaration, never passed as a
   * number, so a column re-ordering moves both consumers together.
   */
  readonly alignTo: string;
  /** The selector for the children to place in that column (e.g. `'.answer-plane > .composer > *'`). */
  readonly alignedChildren: string;
}

/**
 * Tempdoc 816 §5 — lay a SECOND container on the same zone frame and put its children in one zone's
 * column.
 *
 * The chat surface's docked composer, its escalation strip and the conversation column are three
 * rows of ONE reading column, but the composer is a stable DOM slot that must never re-parent (a
 * re-parented textarea drops keystrokes — `UnifiedChatView.renderAnswerPlane`), so it cannot become a
 * child of the conversation zone. Instead it is given the SAME generated frame and its children are
 * placed in the conversation zone's track: identical tracks, identical gap, identical centering, so
 * the two containers' columns coincide by construction rather than by a copied `max-width`.
 *
 * Throws when `alignTo` names no placed zone — a silently unplaced child would inherit column 1 and
 * look "nearly right", which is the failure mode this generator exists to remove.
 */
export function alignToZoneStyles(zones: readonly ZoneDecl[], opts: AlignOpts): CSSResult {
  const zone = zones.find((z) => z.selector === opts.alignTo);
  if (zone?.col === undefined) {
    throw new Error(
      `alignToZoneStyles: no zone '${opts.alignTo}' with a declared col in the zone-set`,
    );
  }
  const containerName = unsafeCSS(opts.containerName);
  const bp = unsafeCSS(opts.breakpoint);
  const children = unsafeCSS(opts.alignedChildren);
  return css`
    ${frameStyles(zones, opts)}
    @container ${containerName} (min-width: ${bp}) {
      ${children} {
        grid-column: ${zone.col};
      }
    }
  `;
}
