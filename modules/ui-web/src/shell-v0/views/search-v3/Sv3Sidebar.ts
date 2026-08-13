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
 * status — the row itself is <jf-sv3-session-row>; this panel owns only the grouping.
 *
 * Groups are the SHELVES the window projected (Active / Pinned / Recent — tempdoc 822 Phase F3) and
 * their order is fixed: activity never reorders rows inside a shelf, so a row does not move out from
 * under the pointer while you are reading it.
 *
 * The panel renders what it is GIVEN (`groups`) and decides nothing: the sessions are the window's
 * (`sv3-sessions.ts`), and a click is announced upward rather than acted on here — the window owns
 * the one search issuance, so the panel must not grow a second one.
 *
 * Side-effect registers <jf-sv3-sidebar>.
 */
import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { icon } from '../../components/Icon.js';
import { sv3Shared } from './sv3-shared-styles.js';
import './Sv3SessionRow.js';
import './Sv3Empty.js';
import { SIDEBAR_EMPTY } from './fixtures.js';
import type { Sv3SessionGroup } from './sv3-sessions.js';

/** Asks the window to re-run a session; the window owns the search, so the panel only names one. */
export const SV3_SESSION_SELECT = 'sv3-session-select';

export interface Sv3SessionSelect {
  readonly id: string;
}

/** Asks the window to pin or unpin a session — the row raises it, the panel says which row. */
export const SV3_SESSION_PIN = 'sv3-session-pin';

export interface Sv3SessionPin {
  readonly id: string;
}

/** Asks the window to start a fresh session — back to the hero, previous sessions kept. */
export const SV3_SESSION_NEW = 'sv3-session-new';

const NEW_SESSION_LABEL = 'New search';

/** Donor §3.2's menu-button glyph size (`size-4`), which is what the header control is. */
const NEW_SESSION_GLYPH_SIZE = 16;

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
      .groups {
        display: flex;
        flex-direction: column;
        min-height: 0;
      }
      .rows {
        display: flex;
        flex-direction: column;
        gap: var(--space-1);
        min-height: 0;
      }
      /* Donor §3.2's menu-button DEFAULT rung (h-8, --control-radius, the row content inset,
         text-sm) — a header control is a menu button, not a session row (§6.1's 36px). */
      button.new-session {
        display: flex;
        align-items: center;
        gap: var(--sidebar-control-gap);
        width: 100%;
        height: var(--space-8);
        margin-bottom: var(--space-1);
        padding-inline: var(--sidebar-row-content-inset);
        padding-block: 0;
        border: 0;
        border-radius: var(--control-radius);
        background: transparent;
        color: var(--sidebar-foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-sm);
        font-weight: 500;
        text-align: left;
        cursor: pointer;
        flex-shrink: 0;
        transition: background-color var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      button.new-session:hover {
        background: var(--sidebar-row-hover);
      }
      button.new-session:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: -2px;
      }
      /* Donor §6.3 technique 4: the glyph dims while the label stays full contrast. */
      button.new-session svg {
        flex-shrink: 0;
        color: var(--icon-muted);
      }
      @media (prefers-reduced-motion: reduce) {
        button.new-session {
          transition: none;
        }
      }
    `,
  ];

  static properties = {
    groups: { attribute: false },
  };

  /** The window's projected session groups; empty means nothing has been searched in this window. */
  declare groups: readonly Sv3SessionGroup[];

  constructor() {
    super();
    this.groups = [];
  }

  private startNew(): void {
    this.dispatchEvent(new CustomEvent(SV3_SESSION_NEW, { bubbles: true, composed: true }));
  }

  private select(id: string): void {
    this.dispatchEvent(
      new CustomEvent<Sv3SessionSelect>(SV3_SESSION_SELECT, {
        detail: { id },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /** The row's pin request, named. The panel decides nothing about it — the list is the window's. */
  private pin(id: string, event: Event): void {
    event.stopPropagation();
    this.dispatchEvent(
      new CustomEvent<Sv3SessionPin>(SV3_SESSION_PIN, {
        detail: { id },
        bubbles: true,
        composed: true,
      }),
    );
  }

  render(): TemplateResult {
    return html`
      <button
        type="button"
        class="new-session"
        data-testid="sv3-sidebar-new"
        @click=${this.startNew}
      >
        ${icon({ name: 'search', size: NEW_SESSION_GLYPH_SIZE })}
        <span>${NEW_SESSION_LABEL}</span>
      </button>
      ${this.groups.length === 0
        ? html`
            <jf-sv3-empty
              data-testid="sv3-sidebar-empty"
              glyph="&#9634;"
              heading=${SIDEBAR_EMPTY.title}
              description=${SIDEBAR_EMPTY.description}
            ></jf-sv3-empty>
          `
        : html`
            <div class="groups">
              ${this.groups.map(
                (group) => html`
                  <div class="group" data-testid="sv3-sidebar-group">
                    <div class="group-label" data-testid="sv3-sidebar-group-label">
                      ${group.label}
                    </div>
                    <div class="rows">
                      ${group.rows.map(
                        (row) => html`
                          <jf-sv3-session-row
                            data-testid="sv3-sidebar-row"
                            .label=${row.label}
                            .meta=${row.meta}
                            status=${row.status}
                            ?active=${row.active}
                            ?pinned=${row.pinned}
                            ?unread=${row.unread}
                            @click=${() => this.select(row.id)}
                            @sv3-session-pin-toggle=${(event: Event) => this.pin(row.id, event)}
                          ></jf-sv3-session-row>
                        `,
                      )}
                    </div>
                  </div>
                `,
              )}
            </div>
          `}
    `;
  }
}

customElements.define('jf-sv3-sidebar', Sv3Sidebar);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-sidebar': Sv3Sidebar;
  }
}
