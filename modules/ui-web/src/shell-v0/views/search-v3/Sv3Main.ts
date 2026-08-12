// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-main — the Search v3 window's content surface (tempdoc 822 slice 1).
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * The ONE scroller in the window. The host itself is clipped; only `.scroller` inside it scrolls,
 * so the window's frame (topbar, sidebar, composer) can never be scrolled out of reach.
 *
 * The region is EMPTY in the composer's hero state (slice 3): nothing has been asked yet, so the
 * hero composer is the region's only subject. Results arrive with the docked state. The region's
 * empty-state treatment proper is slice 4.
 *
 * Side-effect registers <jf-sv3-main>.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { sv3Shared } from './sv3-shared-styles.js';
import { COMPOSER_STATE_DEFAULT, MAIN_HEADING, MAIN_ROWS } from './fixtures.js';
import type { Sv3ComposerState } from './fixtures.js';

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
    `,
  ];

  static properties = {
    state: { type: String, reflect: true },
  };

  declare state: Sv3ComposerState;

  constructor() {
    super();
    this.state = COMPOSER_STATE_DEFAULT;
  }

  render(): TemplateResult {
    const showResults = this.state === 'docked';
    return html`
      <div class="scroller sv3-scroller" data-testid="sv3-main-scroller">
        ${showResults
          ? html`
              <h2>${MAIN_HEADING}</h2>
              ${MAIN_ROWS.map(
                (row) => html`
                  <div class="row" data-testid="sv3-main-row">
                    <span class="row-title">${row.title}</span>
                    <span class="row-path">${row.path}</span>
                  </div>
                `,
              )}
            `
          : nothing}
      </div>
    `;
  }
}

customElements.define('jf-sv3-main', Sv3Main);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-main': Sv3Main;
  }
}
