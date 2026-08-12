// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-composer — the Search v3 window's bottom input band (tempdoc 822 slice 1).
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * Shell only: the band's inset, its bordered container and the control geometry. The morph
 * choreography and the glass recipe are slice 3, and the field is inert until then.
 *
 * Side-effect registers <jf-sv3-composer>.
 */
import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { sv3Shared } from './sv3-shared-styles.js';
import { COMPOSER_PLACEHOLDER } from './fixtures.js';

export class Sv3Composer extends JfElement {
  static styles = [
    sv3Shared,
    css`
      :host {
        display: block;
        flex-shrink: 0;
        padding: var(--floating-content-inset);
        background: var(--background);
        font-family: var(--font-sans);
      }
      .shell {
        display: flex;
        align-items: flex-end;
        gap: var(--space-2);
        padding: var(--control-pad-3);
        border: 1px solid var(--input);
        border-radius: var(--radius-xl);
        background: var(--card);
      }
      .shell:focus-within {
        border-color: var(--ring);
      }
      textarea {
        flex: 1 1 auto;
        min-width: 0;
        border: 0;
        outline: none;
        resize: none;
        background: transparent;
        color: var(--foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-sm);
        line-height: 1.5;
        field-sizing: content;
        max-height: 8rem;
      }
      button.send {
        inline-size: var(--space-8);
        block-size: var(--space-8);
        flex-shrink: 0;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        padding: 0;
        border: 1px solid transparent;
        border-radius: var(--control-radius);
        background: var(--primary);
        color: var(--primary-foreground);
        font-size: var(--font-size-sv3-sm);
        font-weight: 500;
        cursor: pointer;
        transition: opacity var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      button.send:hover {
        opacity: 0.9;
      }
      button.send:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }
      @media (prefers-reduced-motion: reduce) {
        button.send {
          transition: none;
        }
      }
    `,
  ];

  render(): TemplateResult {
    return html`
      <div class="shell" data-testid="sv3-composer-shell">
        <textarea
          rows="1"
          placeholder=${COMPOSER_PLACEHOLDER}
          aria-label=${COMPOSER_PLACEHOLDER}
          data-testid="sv3-composer-input"
        ></textarea>
        <button type="button" class="send" aria-label="Send" data-testid="sv3-composer-send">
          &#8593;
        </button>
      </div>
    `;
  }
}

customElements.define('jf-sv3-composer', Sv3Composer);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-composer': Sv3Composer;
  }
}
