// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-sidebar — the Search v3 window's left panel (tempdoc 822 slice 1).
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * Two-level inset: the panel insets its rows by `--sidebar-content-inset`, then each row insets its
 * own content by `--sidebar-row-content-inset`. The hover fill therefore starts at the panel inset
 * and reads as a pill, not as a full-bleed band — which is the whole point of the second level.
 *
 * One surface model for every row: surface encodes INTERACTION (hover, selection), content encodes
 * status. Slice 2 specs the row's content; slice 1 fixes only its geometry.
 *
 * Side-effect registers <jf-sv3-sidebar>.
 */
import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { sv3Shared } from './sv3-shared-styles.js';
import { SIDEBAR_GROUP_LABEL, SIDEBAR_ROWS } from './fixtures.js';

export class Sv3Sidebar extends JfElement {
  static styles = [
    sv3Shared,
    css`
      :host {
        display: flex;
        flex-direction: column;
        min-height: 0;
        overflow: hidden;
        padding: var(--sidebar-content-inset);
        background: var(--sidebar);
        color: var(--sidebar-foreground);
        border-right: 1px solid var(--sidebar-border);
        font-family: var(--font-sans);
      }
      .group-label {
        display: flex;
        align-items: center;
        height: var(--space-8);
        padding-inline: var(--space-2);
        font-size: var(--font-size-sv3-xs);
        font-weight: 500;
        color: var(--sidebar-muted-foreground);
      }
      .rows {
        display: flex;
        flex-direction: column;
        gap: var(--space-1);
        min-height: 0;
      }
      button.row {
        display: flex;
        align-items: center;
        gap: var(--sidebar-control-gap);
        width: 100%;
        height: var(--space-8);
        padding-inline: var(--sidebar-row-content-inset);
        padding-block: 0.375rem;
        border: 0;
        border-radius: var(--control-radius);
        background: transparent;
        color: var(--sidebar-foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-sm);
        font-weight: 400;
        text-align: left;
        cursor: pointer;
        overflow: hidden;
        transition: background-color var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      button.row:hover {
        background: var(--sidebar-row-hover);
      }
      button.row:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: -2px;
      }
      .row-label {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      @media (prefers-reduced-motion: reduce) {
        button.row {
          transition: none;
        }
      }
    `,
  ];

  render(): TemplateResult {
    return html`
      <div class="group-label" data-testid="sv3-sidebar-group-label">${SIDEBAR_GROUP_LABEL}</div>
      <div class="rows">
        ${SIDEBAR_ROWS.map(
          (row) => html`
            <button type="button" class="row" data-testid="sv3-sidebar-row">
              <span class="row-label">${row.label}</span>
            </button>
          `,
        )}
      </div>
    `;
  }
}

customElements.define('jf-sv3-sidebar', Sv3Sidebar);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-sidebar': Sv3Sidebar;
  }
}
