// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-topbar — the Search v3 window's title band (tempdoc 822 slice 1).
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * Fixed at `--workspace-topbar-height` and non-shrinking, so the band is a layout constant a panel
 * can re-point (token override as a layout API) rather than a number each region re-guesses.
 *
 * Side-effect registers <jf-sv3-topbar>.
 */
import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { sv3Shared } from './sv3-shared-styles.js';

/** Asks the window to open the palette; the window owns it, because the palette covers the window. */
export const SV3_PALETTE_REQUEST = 'sv3-palette-request';

export class Sv3Topbar extends JfElement {
  static styles = [
    sv3Shared,
    css`
      :host {
        display: flex;
        height: var(--workspace-topbar-height);
        min-height: var(--workspace-topbar-height);
        flex-shrink: 0;
        align-items: center;
        gap: var(--space-2);
        padding-left: var(--workspace-controls-left);
        padding-right: var(--workspace-controls-right);
        background: var(--toolbar-background);
        color: var(--toolbar-foreground);
        border-bottom: 1px solid var(--toolbar-border);
        font-family: var(--font-sans);
      }
      .title {
        font-size: var(--font-size-sv3-sm);
        font-weight: 500;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .controls {
        margin-left: auto;
        display: flex;
        align-items: center;
        gap: var(--space-2);
      }
      button.icon {
        inline-size: var(--space-8);
        block-size: var(--space-8);
        display: inline-flex;
        align-items: center;
        justify-content: center;
        padding: 0;
        border: 1px solid transparent;
        border-radius: var(--control-radius);
        background: transparent;
        color: var(--icon-muted);
        font-size: var(--font-size-sv3-xs);
        cursor: pointer;
        transition: background-color var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      button.icon:hover {
        background: var(--toolbar-control-hover);
        color: var(--toolbar-control-foreground);
      }
      button.icon:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }
      @media (prefers-reduced-motion: reduce) {
        button.icon {
          transition: none;
        }
      }
    `,
  ];

  /** The window title text. */
  static properties = { windowTitle: { type: String, attribute: 'window-title' } };

  declare windowTitle: string;

  constructor() {
    super();
    this.windowTitle = '';
  }

  /** Raised from the BUTTON, not the host: the window reads the invoker off the composed path and
      focus has to come back to the control that was pressed, not to the band around it. */
  private requestPalette(event: Event): void {
    (event.currentTarget as HTMLElement).dispatchEvent(
      new CustomEvent(SV3_PALETTE_REQUEST, { bubbles: true, composed: true }),
    );
  }

  render(): TemplateResult {
    return html`
      <span class="title" data-testid="sv3-topbar-title">${this.windowTitle}</span>
      <span class="controls">
        <button
          type="button"
          class="icon"
          aria-label="Open command palette"
          data-testid="sv3-topbar-palette"
          @click=${this.requestPalette}
        >
          &#8984;
        </button>
        <button
          type="button"
          class="icon"
          aria-label="Window settings"
          data-testid="sv3-topbar-control"
        >
          &#8942;
        </button>
        <button
          type="button"
          class="icon"
          aria-label="Window layout"
          data-testid="sv3-topbar-control"
        >
          &#9707;
        </button>
      </span>
    `;
  }
}

customElements.define('jf-sv3-topbar', Sv3Topbar);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-topbar': Sv3Topbar;
  }
}
