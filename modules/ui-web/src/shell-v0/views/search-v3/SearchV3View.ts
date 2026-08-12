// SPDX-License-Identifier: Apache-2.0
/**
 * SearchV3View — the Search v3 window host (tempdoc 822 slice 1).
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * A from-scratch window rebuilt on the T3 Code donor system: no presentation code is carried over
 * from `UnifiedChatView` or search-v2. This host owns three things and delegates the rest:
 *
 *  1. **The token sheet.** `sv3Tokens` is applied HERE, on the window host — never on `:root`. Custom
 *     properties inherit down through every nested shadow root, so one host-scoped declaration
 *     reaches the whole window while the shipped app's palette stays untouched.
 *  2. **The window grid.** A fixed `--sidebar-width` panel that does not flex, beside a main column
 *     of topbar → content surface → composer band.
 *  3. **The scroll policy.** The window region never scrolls: this host and the main column are
 *     clipped, and the ONE scroller is the content surface's inner scroller. Chrome therefore
 *     cannot be scrolled out of reach, and there is no scroller nested inside another.
 *
 * Mounted as a hidden DEEPLINK surface, dev audience, no rail entry:
 * `#justsearch://surface/core.search-v3-surface`.
 *
 * Side-effect registers <jf-sv3-window> and its four regions.
 */
import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { sv3Tokens } from './sv3-tokens.css.js';
import { sv3Shared } from './sv3-shared-styles.js';
import { WINDOW_TITLE } from './fixtures.js';
import './Sv3Topbar.js';
import './Sv3Sidebar.js';
import './Sv3Main.js';
import './Sv3Composer.js';

export class SearchV3View extends JfElement {
  static styles = [
    sv3Tokens,
    sv3Shared,
    css`
      :host {
        display: flex;
        height: 100%;
        min-height: 0;
        overflow: hidden;
        background: var(--background);
        color: var(--foreground);
        font-family: var(--font-sans);
        font-size: var(--font-size-sv3-sm);
      }
      jf-sv3-sidebar {
        flex: 0 0 var(--sidebar-width);
        width: var(--sidebar-width);
      }
      .column {
        display: flex;
        flex-direction: column;
        flex: 1 1 auto;
        min-width: 0;
        min-height: 0;
        overflow: hidden;
      }
    `,
  ];

  render(): TemplateResult {
    return html`
      <jf-sv3-sidebar data-testid="sv3-sidebar"></jf-sv3-sidebar>
      <div class="column" data-testid="sv3-column">
        <jf-sv3-topbar window-title=${WINDOW_TITLE} data-testid="sv3-topbar"></jf-sv3-topbar>
        <jf-sv3-main data-testid="sv3-main"></jf-sv3-main>
        <jf-sv3-composer data-testid="sv3-composer"></jf-sv3-composer>
      </div>
    `;
  }
}

customElements.define('jf-sv3-window', SearchV3View);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-window': SearchV3View;
  }
}
