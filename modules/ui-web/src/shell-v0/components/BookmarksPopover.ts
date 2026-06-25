// SPDX-License-Identifier: Apache-2.0
/**
 * BookmarksPopover — slice 501 §3.5.
 *
 * Dropdown listing saved views. Anchored to the bookmark star button.
 * Uses Popover API when available; falls back to position:fixed + display toggle.
 * Click → dispatch Navigation intent to restore the saved view.
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { TransientController } from '../primitives/transientController.js';
import { icon } from './Icon.js';
import { surfaceIcon } from '../utils/surfaceIcons.js';
import { formatRelative } from '../utils/relativeTime.js';
import {
  getSavedViews,
  subscribeSavedViews,
  removeView,
  type SavedView,
} from '../state/savedViewState.js';

const POPOVER_SUPPORTED =
  typeof HTMLElement !== 'undefined' &&
  'popover' in HTMLElement.prototype;

export class BookmarksPopover extends JfElement {
  /** 574 §22.F — single-open arbitration by construction (the dismiss-triad sibling of ModalityController). */
  private readonly transient = new TransientController(this, {
    layer: 'transient',
    id: 'bookmarks',
    close: () => {
      this.open = false;
    },
  });

  static properties = {
    open: { type: Boolean, reflect: true },
    views: { state: true },
  };

  declare open: boolean;
  declare views: readonly SavedView[];

  private unsubscribe: (() => void) | null = null;
  private boundDocClick = (e: Event) => this.onDocClick(e);
  private boundKey = (e: KeyboardEvent) => this.onKey(e);
  private focusedIndex = 0;
  private listenersActive = false;

  static styles = css`
    :host {
      position: fixed;
      top: 2.5rem;
      right: 3rem;
      z-index: var(--z-overlay-transient);
      min-width: 16rem;
      max-width: 22rem;
      max-height: 18rem;
      overflow-y: auto;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
      padding: 0.25rem 0;
      font-size: var(--font-size-xs);
      color: var(--text-primary);
      display: none;
    }
    :host([open]) {
      display: block;
    }
    .empty {
      padding: 0.75rem 1rem;
      color: var(--text-secondary);
      text-align: center;
    }
    .item {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.375rem 0.75rem;
      cursor: pointer;
      transition: background var(--duration-instant) var(--ease-standard);
    }
    .item:hover,
    .item.focused {
      background: var(--surface-2);
    }
    .item-icon {
      flex-shrink: 0;
      color: var(--text-secondary);
    }
    .item-body {
      flex: 1;
      min-width: 0;
    }
    .item-label {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .item-time {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.0625rem;
    }
    .item-remove {
      display: inline-flex;
      align-items: center;
      flex-shrink: 0;
      opacity: 0;
      color: var(--text-secondary);
      cursor: pointer;
      transition: opacity var(--duration-instant) var(--ease-standard);
    }
    .item:hover .item-remove {
      opacity: 1;
    }
    .item-remove:hover {
      color: var(--text-danger);
    }
  `;

  constructor() {
    super();
    this.open = false;
    this.views = [];
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.views = getSavedViews();
    this.unsubscribe = subscribeSavedViews((v) => {
      this.views = v;
    });
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubscribe?.();
    this.unsubscribe = null;
    this.removeDocListeners();
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('open')) {
      if (this.open) {
        this.transient.open(); // 574 §22.F — single-open by construction (register + close peers)
        requestAnimationFrame(() => {
          if (this.open) this.addDocListeners();
        });
        this.tryShowPopover();
      } else {
        this.transient.close();
        this.removeDocListeners();
        this.tryHidePopover();
      }
    }
  }

  private tryShowPopover(): void {
    if (!POPOVER_SUPPORTED) return;
    const el = this.shadowRoot?.getElementById('popover-panel') as HTMLElement & {
      showPopover?: () => void;
      matches?: (s: string) => boolean;
    } | null;
    if (!el || typeof el.showPopover !== 'function') return;
    try {
      const isOpen = typeof el.matches === 'function' && el.matches(':popover-open');
      if (!isOpen) el.showPopover();
    } catch {
      // WebViews where showPopover throws
    }
  }

  private tryHidePopover(): void {
    if (!POPOVER_SUPPORTED) return;
    const el = this.shadowRoot?.getElementById('popover-panel') as HTMLElement & {
      hidePopover?: () => void;
      matches?: (s: string) => boolean;
    } | null;
    if (!el || typeof el.hidePopover !== 'function') return;
    try {
      const isOpen = typeof el.matches === 'function' && el.matches(':popover-open');
      if (isOpen) el.hidePopover();
    } catch {
      // Tolerated
    }
  }

  private addDocListeners(): void {
    if (this.listenersActive) return;
    this.listenersActive = true;
    document.addEventListener('mousedown', this.boundDocClick, true);
    document.addEventListener('keydown', this.boundKey, true);
  }

  private removeDocListeners(): void {
    if (!this.listenersActive) return;
    this.listenersActive = false;
    document.removeEventListener('mousedown', this.boundDocClick, true);
    document.removeEventListener('keydown', this.boundKey, true);
  }

  private onDocClick(e: Event): void {
    const path = (e as MouseEvent).composedPath();
    if (!path.includes(this)) {
      this.open = false;
    }
  }

  private onKey(e: KeyboardEvent): void {
    if (e.key === 'Escape') {
      e.preventDefault();
      this.open = false;
      return;
    }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (this.views.length === 0) return;
      const delta = e.key === 'ArrowDown' ? 1 : -1;
      this.focusedIndex = ((this.focusedIndex + delta) % this.views.length + this.views.length) % this.views.length;
      this.requestUpdate();
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      const view = this.views[this.focusedIndex];
      if (view) this.handleClick(view);
      return;
    }
    // 559 Authority V — keyboard parity for the mouse-only "×" remove affordance.
    if (e.key === 'Delete' || e.key === 'Backspace') {
      e.preventDefault();
      const view = this.views[this.focusedIndex];
      if (view) removeView(view.id);
    }
  }

  toggle(): void {
    this.open = !this.open;
    if (this.open) this.focusedIndex = 0;
  }

  override render(): TemplateResult {
    if (!this.open) return html``;
    if (this.views.length === 0) {
      return html`<div class="empty">No saved views yet.<br/>Press Ctrl+D to bookmark.</div>`;
    }
    // 559 Authority V — the saved-views list is a keyboard-navigable listbox
    // (arrow keys + Enter to open + Delete to remove, handled at document level);
    // each row is a role="option" the container drives via aria-activedescendant.
    return html`
      <div
        class="list"
        role="listbox"
        aria-label="Saved views"
        aria-activedescendant=${`bm-opt-${this.focusedIndex}`}
      >
        ${this.views.map(
          (v, idx) => html`
            <div
              id=${`bm-opt-${idx}`}
              class=${`item${idx === this.focusedIndex ? ' focused' : ''}`}
              role="option"
              aria-selected=${idx === this.focusedIndex}
              title=${v.url}
              @click=${() => this.handleClick(v)}
              @contextmenu=${(e: Event) => { e.preventDefault(); removeView(v.id); }}
              @mouseenter=${() => { this.focusedIndex = idx; this.requestUpdate(); }}
            >
              <span class="item-icon">${icon({ name: surfaceIcon(v.surfaceId), size: 14 })}</span>
              <div class="item-body">
                <div class="item-label">${v.label}</div>
                <div class="item-time">${formatRelative(v.savedAt)}</div>
              </div>
              <span
                class="item-remove"
                title="Remove (Delete)"
                aria-hidden="true"
                @click=${(e: Event) => {
                  e.stopPropagation();
                  removeView(v.id);
                }}
              >${icon({ name: 'x', size: 12 })}</span>
            </div>
          `,
        )}
      </div>
    `;
  }

  private handleClick(view: SavedView): void {
    this.dispatchEvent(
      new CustomEvent('bookmark-navigate', {
        detail: { url: view.url },
        bubbles: true,
        composed: true,
      }),
    );
    this.open = false;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-bookmarks-popover')) {
  customElements.define('jf-bookmarks-popover', BookmarksPopover);
}
