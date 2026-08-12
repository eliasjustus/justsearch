// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-main — the Search v3 window's content surface (tempdoc 822 slice 1; wired in Phase A1).
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * The ONE scroller in the window. The host itself is clipped; only `.scroller` inside it scrolls,
 * so the window's frame (topbar, sidebar, composer) can never be scrolled out of reach.
 *
 * The region is EMPTY in the composer's hero state (slice 3): nothing has been asked yet, so the
 * hero composer is the region's only subject. What it holds once docked is whatever the window's
 * read of the shared search store says (`sv3-results.ts`) — the region owns no store subscription
 * and no client of its own, so it cannot render a result the window did not receive.
 *
 * The count line is computed HERE, off the same array the rows are mapped from, because that is the
 * only construction in which the number cannot come to describe a different set than the one on
 * screen. It is the shipped `matchCountLabel`, not a second count authority.
 *
 * Side-effect registers <jf-sv3-main>.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { matchCountLabel } from '../../components/searchResults/matchCountLabel.js';
import { sv3Shared } from './sv3-shared-styles.js';
import './Sv3Empty.js';
import { COMPOSER_STATE_DEFAULT, MAIN_EMPTY, MAIN_UNREACHABLE } from './fixtures.js';
import type { Sv3ComposerState } from './fixtures.js';
import { SV3_RESULTS_IDLE, type Sv3ResultsView } from './sv3-results.js';

/** Enough bars to fill the region's first screen without claiming a result count it cannot know. */
const SKELETON_ROWS = 6;

export class Sv3Main extends JfElement {
  static styles = [
    sv3Shared,
    css`
      :host {
        display: flex;
        flex-direction: column;
        flex: 1 1 auto;
        min-height: 0;
        overflow: hidden;
        background: var(--background);
        color: var(--foreground);
        font-family: var(--font-sans);
      }
      .scroller {
        flex: 1 1 auto;
        min-height: 0;
        padding: var(--floating-content-inset);
        display: flex;
        flex-direction: column;
        gap: var(--space-1);
      }
      h2 {
        margin: 0 0 var(--space-2);
        font-size: var(--font-size-sv3-sm);
        font-weight: 600;
      }
      .row {
        display: flex;
        align-items: baseline;
        gap: var(--space-2);
        padding: var(--space-2) var(--space-3);
        border-radius: var(--radius-md);
        font-size: var(--font-size-sv3-sm);
        /* A long list stays cheap: the browser skips rendering work for rows outside the
           viewport, and the intrinsic size keeps the scrollbar honest while they are skipped. */
        content-visibility: auto;
        contain-intrinsic-size: auto 36px;
      }
      .row-title {
        font-weight: 500;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .row-path {
        margin-left: auto;
        flex-shrink: 0;
        font-size: var(--font-size-sv3-xs);
        color: var(--secondary-label);
        font-family: var(--font-mono);
      }

      /* The pending state is the row rhythm with the content withheld — same height, same radius,
         same gap — so the list does not jump when the answer replaces it. The sweep is the shared
         sheet's duty-cycled keyframe (transform-only, long hold), not a continuous shimmer. */
      .skeleton-row {
        position: relative;
        overflow: hidden;
        height: var(--space-9);
        border-radius: var(--radius-md);
        background: var(--muted);
      }
      .skeleton-sheen {
        position: absolute;
        inset: 0;
        background: linear-gradient(
          90deg,
          transparent,
          color-mix(in srgb, var(--foreground) 8%, transparent),
          transparent
        );
      }

      /* The store's own failure text, kept at diagnostic altitude: the state is said in words above
         it, and this is the detail that makes the words checkable. */
      .failure-detail {
        color: var(--secondary-label);
        font-family: var(--font-mono);
        font-size: var(--font-size-sv3-xs);
      }
    `,
  ];

  static properties = {
    state: { type: String, reflect: true },
    view: { attribute: false },
  };

  declare state: Sv3ComposerState;
  declare view: Sv3ResultsView;

  constructor() {
    super();
    this.state = COMPOSER_STATE_DEFAULT;
    this.view = SV3_RESULTS_IDLE;
  }

  render(): TemplateResult {
    const view = this.view ?? SV3_RESULTS_IDLE;
    // Nothing but the hero composer belongs in the region until the window has docked: an untouched
    // window's emptiness is the composer's to speak for, not a state to announce.
    if (this.state !== 'docked' || view.status === 'idle') {
      return html`<div class="scroller sv3-scroller" data-testid="sv3-main-scroller"></div>`;
    }
    if (view.status === 'loading') return this.pending();
    if (view.status === 'unreachable') return this.unreachable(view.failure);
    if (view.status === 'empty') {
      return html`
        <jf-sv3-empty
          roomy
          data-testid="sv3-main-empty"
          glyph="&#9634;"
          heading=${MAIN_EMPTY.title}
          description=${MAIN_EMPTY.description}
        ></jf-sv3-empty>
      `;
    }
    const rows = view.rows;
    return html`
      <div class="scroller sv3-scroller" data-testid="sv3-main-scroller">
        <h2 data-testid="sv3-main-count">
          ${matchCountLabel(view.matched, rows.length, false, view.ranked, view.truncated)}
        </h2>
        ${rows.map(
          (row) => html`
            <div class="row" data-testid="sv3-main-row">
              <span class="row-title">${row.title}</span>
              <span class="row-path">${row.path}</span>
            </div>
          `,
        )}
      </div>
    `;
  }

  private pending(): TemplateResult {
    return html`
      <div
        class="scroller sv3-scroller"
        data-testid="sv3-main-scroller"
        aria-busy="true"
        aria-label="Searching"
      >
        ${Array.from(
          { length: SKELETON_ROWS },
          () => html`
            <div class="skeleton-row" data-testid="sv3-main-skeleton" aria-hidden="true">
              <span class="skeleton-sheen sv3-anim-skeleton"></span>
            </div>
          `,
        )}
      </div>
    `;
  }

  /**
   * The request never reached the backend, so NOTHING is known about the corpus — which is why this
   * is its own state rather than the zero-results one wearing different words.
   */
  private unreachable(failure: string): TemplateResult {
    return html`
      <jf-sv3-empty
        roomy
        data-testid="sv3-main-unreachable"
        glyph="&#9634;"
        heading=${MAIN_UNREACHABLE.title}
        description=${MAIN_UNREACHABLE.description}
      >
        ${failure === ''
          ? nothing
          : html`<span class="failure-detail" data-testid="sv3-main-failure-detail"
              >${failure}</span
            >`}
      </jf-sv3-empty>
    `;
  }
}

customElements.define('jf-sv3-main', Sv3Main);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-main': Sv3Main;
  }
}
