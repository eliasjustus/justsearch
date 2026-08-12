// SPDX-License-Identifier: Apache-2.0
/**
 * SearchV3View — the Search v3 window host (tempdoc 822 slices 1 and 3).
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * A from-scratch window rebuilt on the T3 Code donor system: no presentation code is carried over
 * from `UnifiedChatView` or search-v2. This host owns four things and delegates the rest:
 *
 *  1. **The token sheet.** `sv3Tokens` is applied HERE, on the window host — never on `:root`. Custom
 *     properties inherit down through every nested shadow root, so one host-scoped declaration
 *     reaches the whole window while the shipped app's palette stays untouched.
 *  2. **The window grid.** A fixed `--sidebar-width` panel that does not flex, beside a main column
 *     of topbar → content surface → composer band.
 *  3. **The scroll policy.** The window region never scrolls: this host and the main column are
 *     clipped, and the ONE scroller is the content surface's inner scroller. Chrome therefore
 *     cannot be scrolled out of reach, and there is no scroller nested inside another.
 *  4. **The composer state, and the morph between its two forms** (slice 3). The state lives here
 *     rather than in the composer because it is a WINDOW layout: hero means the composer owns the
 *     content region and there are no results; docked means the results do. Three ways in, all
 *     through the same morph: the send control, `Escape` in the field, and the `composer-state`
 *     attribute (a dev-only handle for live measurement, which is why an external write is routed
 *     through the morph rather than applied straight).
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
import { COMPOSER_STATE_DEFAULT, WINDOW_TITLE, type Sv3ComposerState } from './fixtures.js';
import {
  adoptSv3MorphSheet,
  releaseSv3MorphSheet,
  runSv3ComposerMorph,
} from './sv3-composer-morph.js';
import { type Sv3ComposerStateRequest } from './Sv3Composer.js';
import './Sv3Topbar.js';
import './Sv3Sidebar.js';
import './Sv3Main.js';
import './Sv3Composer.js';

const COMPOSER_STATE_ATTR = 'composer-state';

const isComposerState = (value: string | null): value is Sv3ComposerState =>
  value === 'hero' || value === 'docked';

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
        /* The containing block for the hero composer, which leaves the flow to centre itself over
           the content region. */
        position: relative;
      }
    `,
  ];

  static properties = {
    composerState: { type: String, reflect: true, attribute: COMPOSER_STATE_ATTR },
  };

  declare composerState: Sv3ComposerState;

  constructor() {
    super();
    this.composerState = COMPOSER_STATE_DEFAULT;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    adoptSv3MorphSheet();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    releaseSv3MorphSheet();
  }

  override attributeChangedCallback(name: string, older: string | null, value: string | null): void {
    // An external write of the dev handle animates like every other route into the state. Lit's own
    // reflection also lands here, but by then the property already holds the value, so it falls
    // through to the default path and cannot loop.
    if (
      name === COMPOSER_STATE_ATTR &&
      isComposerState(value) &&
      this.hasUpdated &&
      value !== this.composerState
    ) {
      void this.setComposerState(value);
      return;
    }
    super.attributeChangedCallback(name, older, value);
  }

  /** The one way the state changes: applied inside the scoped view transition (donor §5.5). */
  async setComposerState(next: Sv3ComposerState): Promise<void> {
    if (next === this.composerState) return;
    const composer = this.shadowRoot?.querySelector('jf-sv3-composer');
    const apply = async (): Promise<void> => {
      this.composerState = next;
      await this.updateComplete;
      // The regions schedule their OWN updates off this render, and the API captures the "after"
      // state when this callback resolves. Waiting on a FRAME here would deadlock: the browser
      // suspends rendering until the callback settles, so a requested frame is never serviced and
      // the transition is skipped at the ~4s callback timeout (measured). Their update promises are
      // microtask-backed and settle regardless.
      await Promise.all(
        [...(this.shadowRoot?.querySelectorAll('jf-sv3-main, jf-sv3-composer') ?? [])].map(
          (region) => (region as HTMLElement & { updateComplete: Promise<unknown> }).updateComplete,
        ),
      );
    };
    if (composer === null || composer === undefined) {
      await apply();
      return;
    }
    await runSv3ComposerMorph(composer, apply);
  }

  private onStateRequest(event: Event): void {
    const detail = (event as CustomEvent<Sv3ComposerStateRequest>).detail;
    if (!isComposerState(detail?.state ?? null)) return;
    void this.setComposerState(detail.state);
  }

  render(): TemplateResult {
    return html`
      <jf-sv3-sidebar data-testid="sv3-sidebar"></jf-sv3-sidebar>
      <div
        class="column"
        data-testid="sv3-column"
        @sv3-composer-state-request=${this.onStateRequest}
      >
        <jf-sv3-topbar window-title=${WINDOW_TITLE} data-testid="sv3-topbar"></jf-sv3-topbar>
        <jf-sv3-main state=${this.composerState} data-testid="sv3-main"></jf-sv3-main>
        <jf-sv3-composer state=${this.composerState} data-testid="sv3-composer"></jf-sv3-composer>
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
