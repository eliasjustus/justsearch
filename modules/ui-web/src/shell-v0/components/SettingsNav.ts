// SPDX-License-Identifier: Apache-2.0
/**
 * `<jf-settings-nav>` — tempdoc 855 §9.5: the settings window's vertical grouped nav.
 *
 * Projects the ONE declared register (`views/settingsRegister.ts`) into: group headers + divider
 * rules, category rows, and — for the ACTIVE category only (accordion) — its indented sub-anchor
 * rows. The isolated danger group (Data) renders in the danger token role.
 *
 * Scroll-spy is two-way but this component does NOT own the scroll math: the host
 * (`SettingsSurface`, which owns the scrollable content pane) derives the in-view sub-anchor via
 * the house `deriveFocus()`/`anchorFractions()` (`primitives/navigation.ts` — the codebase
 * deliberately retired its only IntersectionObserver in favor of this derived-focus-on-scroll
 * math) and passes it down as `activeAnchor`; clicking a sub-anchor here dispatches `anchor-jump`
 * and the host scrolls (honoring `prefers-reduced-motion`, mirroring `NavigationController.jumpTo`).
 *
 * a11y (853 floor): real sibling `<button>` rows, never nested-interactive; roving tabindex over
 * every VISIBLE row (category rows + the active category's sub-anchors); `aria-current` marks the
 * active category/anchor; row hit areas are ≥32px tall (≥24px floor); every color is an existing
 * token role valid across all four palettes (no new colors).
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { localizeResourceKey } from '../../i18n/resourceCatalog.js';
import { present } from '../display/present.js';
import type { SettingsGroup, SettingsCategory } from '../views/settingsRegister.js';

export class SettingsNav extends JfElement {
  static properties = {
    register: { attribute: false },
    activeCategory: { type: String, attribute: 'active-category' },
    activeAnchor: { type: String, attribute: 'active-anchor' },
    footerVersion: { type: String, attribute: 'footer-version' },
  };

  declare register: readonly SettingsGroup[];
  declare activeCategory: string;
  declare activeAnchor: string | null;
  /** Optional app-version string for the nav footer (855 §4 — "footer: app version, click-to-copy").
   *  Omitted entirely when no clean source is available (see SettingsSurface for the source check). */
  declare footerVersion: string | null;

  constructor() {
    super();
    this.register = [];
    this.activeCategory = '';
    this.activeAnchor = null;
    this.footerVersion = null;
  }

  static styles = css`
    :host {
      display: flex;
      flex-direction: column;
      min-height: 0;
      height: 100%;
      width: 240px;
      flex: 0 0 240px;
      border-right: 1px solid var(--border-subtle);
      background: var(--surface-2, var(--surface-secondary));
    }
    .groups {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      padding: 0.75rem 0.5rem;
    }
    .group {
      padding-top: 0.75rem;
    }
    .group:first-child {
      padding-top: 0;
    }
    .group + .group {
      margin-top: 0.5rem;
      border-top: 1px solid var(--border-subtle);
    }
    .group-header {
      padding: 0.25rem 0.5rem;
      font-size: var(--font-size-sm);
      font-weight: 500;
      letter-spacing: 0.02em;
      color: var(--text-secondary);
    }
    .group.danger .group-header {
      color: var(--text-danger);
    }
    .category-list,
    .anchor-list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 0.125rem;
    }
    button.category-row,
    button.anchor-row {
      display: flex;
      align-items: center;
      width: 100%;
      min-height: 2rem;
      box-sizing: border-box;
      border: none;
      border-radius: 0.5rem;
      background: transparent;
      color: var(--text-secondary);
      font: inherit;
      font-size: var(--font-size-sm);
      text-align: left;
      cursor: pointer;
      padding: 0.375rem 0.5rem;
    }
    button.category-row:hover,
    button.anchor-row:hover {
      color: var(--text-primary);
      background: var(--surface-hover);
    }
    /* Discord-2025 measured spec (855 §2): a NEUTRAL translucent active pill, not the brand accent. */
    button.category-row.active,
    button.anchor-row.active {
      color: var(--text-primary);
      background: var(--surface-hover);
    }
    button.category-row.danger {
      color: var(--text-danger);
    }
    button.category-row.danger:hover,
    button.category-row.danger.active {
      background: var(--accent-danger-16);
    }
    button.anchor-row {
      min-height: 1.75rem;
      font-size: var(--font-size-xs);
      padding-left: 1.25rem;
      color: var(--text-tertiary, var(--text-secondary));
    }
    button.anchor-row.active {
      color: var(--text-primary);
    }
    button:focus-visible {
      outline: 2px solid var(--focus-ring-color, var(--accent-tint));
      outline-offset: -2px;
    }
    .footer {
      flex-shrink: 0;
      padding: 0.5rem 0.75rem;
      border-top: 1px solid var(--border-subtle);
      font-size: var(--font-size-xs);
      color: var(--text-tertiary, var(--text-secondary));
    }
  `;

  private select(id: string): void {
    if (id === this.activeCategory) return;
    this.dispatchEvent(
      new CustomEvent('category-select', { detail: { id }, bubbles: true, composed: true }),
    );
  }

  private jump(key: string): void {
    this.dispatchEvent(
      new CustomEvent('anchor-jump', { detail: { key }, bubbles: true, composed: true }),
    );
  }

  /** Roving tabindex (853/571-precedent pattern): Up/Down/Home/End across every VISIBLE row. */
  private onKeydown(e: KeyboardEvent): void {
    const rows = Array.from(
      this.shadowRoot?.querySelectorAll<HTMLButtonElement>('button[data-nav-row]') ?? [],
    );
    if (rows.length === 0) return;
    const activeEl = this.shadowRoot?.activeElement as HTMLButtonElement | null;
    const i = activeEl ? rows.indexOf(activeEl) : -1;
    let next: number;
    switch (e.key) {
      case 'ArrowDown':
        next = i < 0 ? 0 : (i + 1) % rows.length;
        break;
      case 'ArrowUp':
        next = i < 0 ? rows.length - 1 : (i - 1 + rows.length) % rows.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = rows.length - 1;
        break;
      default:
        return;
    }
    e.preventDefault();
    rows[next]?.focus();
  }

  private categoryLabel(category: SettingsCategory): string {
    if (category.kind === 'member' && category.memberSurfaceId) {
      return present({ kind: 'surface', id: category.memberSurfaceId }).label;
    }
    return category.labelKey ? localizeResourceKey(category.labelKey) : category.id;
  }

  private renderCategory(category: SettingsCategory, danger: boolean): TemplateResult {
    const isActive = category.id === this.activeCategory;
    const sections = (category.sections ?? []).filter((s) => !s.gate || s.gate());
    return html`
      <li>
        <button
          type="button"
          data-nav-row
          data-settings-category=${category.id}
          class="category-row ${isActive ? 'active' : ''} ${danger ? 'danger' : ''}"
          aria-current=${isActive ? 'true' : nothing}
          tabindex=${isActive ? '0' : '-1'}
          @click=${() => this.select(category.id)}
        >
          ${this.categoryLabel(category)}
        </button>
        ${isActive && sections.length > 0
          ? html`
              <ul class="anchor-list">
                ${sections.map((s) => {
                  const anchorActive = this.activeAnchor === s.key;
                  return html`
                    <li>
                      <button
                        type="button"
                        data-nav-row
                        data-settings-anchor=${s.key}
                        class="anchor-row ${anchorActive ? 'active' : ''}"
                        aria-current=${anchorActive ? 'true' : nothing}
                        tabindex=${anchorActive ? '0' : '-1'}
                        @click=${() => this.jump(s.key)}
                      >
                        ${localizeResourceKey(s.labelKey)}
                      </button>
                    </li>
                  `;
                })}
              </ul>
            `
          : nothing}
      </li>
    `;
  }

  private renderGroup(group: SettingsGroup): TemplateResult {
    return html`
      <div class="group ${group.danger ? 'danger' : ''}">
        <div class="group-header">${localizeResourceKey(group.labelKey)}</div>
        <ul class="category-list">
          ${group.categories.map((c) => this.renderCategory(c, group.danger === true))}
        </ul>
      </div>
    `;
  }

  override render(): TemplateResult {
    return html`
      <nav aria-label="Settings categories" @keydown=${(e: KeyboardEvent) => this.onKeydown(e)}>
        <div class="groups">${this.register.map((g) => this.renderGroup(g))}</div>
        ${this.footerVersion
          ? html`<div class="footer">${this.footerVersion}</div>`
          : nothing}
      </nav>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-settings-nav')) {
  customElements.define('jf-settings-nav', SettingsNav);
}
