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
 * Groups are VISUAL only and their order is fixed: activity never reorders the list, so a row does
 * not move out from under the pointer while you are reading it.
 *
 * Side-effect registers <jf-sv3-sidebar>.
 */
import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { sv3Shared } from './sv3-shared-styles.js';
import './Sv3SessionRow.js';
import './Sv3Empty.js';
import {
  FIXTURE_SET_DEFAULT,
  SIDEBAR_EMPTY,
  sidebarGroupsFor,
  type Sv3FixtureSet,
} from './fixtures.js';

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
    `,
  ];

  static properties = {
    fixtureSet: { type: String, attribute: 'fixtures', reflect: true },
  };

  declare fixtureSet: Sv3FixtureSet;

  constructor() {
    super();
    this.fixtureSet = FIXTURE_SET_DEFAULT;
  }

  render(): TemplateResult {
    const groups = sidebarGroupsFor(this.fixtureSet);
    if (groups.length === 0) {
      return html`
        <jf-sv3-empty
          data-testid="sv3-sidebar-empty"
          glyph="&#9634;"
          heading=${SIDEBAR_EMPTY.title}
          description=${SIDEBAR_EMPTY.description}
        ></jf-sv3-empty>
      `;
    }
    return html`
      <div class="groups">
        ${groups.map(
          (group) => html`
            <div class="group" data-testid="sv3-sidebar-group">
              <div class="group-label" data-testid="sv3-sidebar-group-label">${group.label}</div>
              <div class="rows">
                ${group.rows.map(
                  (row) => html`
                    <jf-sv3-session-row
                      data-testid="sv3-sidebar-row"
                      .label=${row.label}
                      .meta=${row.meta}
                      status=${row.status}
                      ?active=${row.active ?? false}
                      ?selected=${row.selected ?? false}
                      ?receded=${row.receded ?? false}
                      ?unread=${row.unread ?? false}
                      ?inflight=${row.inFlight ?? false}
                    ></jf-sv3-session-row>
                  `,
                )}
              </div>
            </div>
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
