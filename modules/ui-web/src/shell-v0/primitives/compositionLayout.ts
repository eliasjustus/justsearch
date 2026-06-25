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
  /** The wide breakpoint, e.g. `'64rem'`. */
  readonly breakpoint: string;
  /** The inter-zone gap (a token reference or a literal). */
  readonly gap: string;
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
 * §13 Pillar B — generate the grid-frame {@link CSSResult} for a declared zone-set. Faithful to the
 * de-risk Probe S2 (reproduces the prior hand-authored grid exactly). Empty-collapse needs no branch:
 * a zone whose element is unmounted leaves its `minmax(0,…)` track to collapse to zero width.
 */
export function composeGridStyles(zones: readonly ZoneDecl[], opts: ComposeOpts): CSSResult {
  const container = unsafeCSS(opts.container);
  const narrow = unsafeCSS(trackTemplate(zones, 'narrow'));
  const wide = unsafeCSS(trackTemplate(zones, 'wide'));
  const gap = unsafeCSS(opts.gap);
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
    ${container} {
      display: grid;
      grid-template-columns: ${narrow};
      gap: ${gap};
    }
    @media (min-width: ${bp}) {
      ${container} {
        grid-template-columns: ${wide};
      }
      ${placements}
    }
  `;
}
