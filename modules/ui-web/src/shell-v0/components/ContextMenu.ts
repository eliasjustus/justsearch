// SPDX-License-Identifier: Apache-2.0
/**
 * ContextMenu — Lit primitive for right-click action menus (slice 458).
 *
 * Replaces the React `<ContextMenu>` for Lit-side surfaces (Browse,
 * Library, future search results). Works with `confirmAsync()` (slice
 * 457) for HIGH-risk actions via `requiresConfirmation`.
 *
 * Two parts:
 *  - `<jf-context-menu>`: presentational menu with positioning,
 *    grouping, keyboard nav.
 *  - `openContextMenu(actions, anchor)`: helper that creates the
 *    element on body, returns a Promise that resolves with the
 *    invoked action id (or null on cancel).
 */

import { html, css, type TemplateResult, nothing } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { TransientController } from '../primitives/transientController.js';
import { icon, type IconName } from './Icon.js';
import { confirmAsync } from './ConfirmDialog.js';
import { listContextActions } from '../commands/ContextActionRegistry.js';

export type ContextActionCategory = 'file' | 'ai' | 'index' | 'system' | 'plugin';

export interface ContextMenuAction {
  id: string;
  label: string;
  icon?: IconName;
  shortcut?: string;
  category: ContextActionCategory;
  enabled: boolean;
  requiresConfirmation?: {
    title: string;
    message: string;
    variant?: 'danger' | 'warning' | 'info';
    confirmLabel?: string;
  };
}

interface MenuItem {
  kind: 'action' | 'separator';
  action?: ContextMenuAction;
}

const CATEGORY_ORDER: ContextActionCategory[] = [
  'file',
  'ai',
  'index',
  'system',
  'plugin',
];

function buildItems(actions: ContextMenuAction[]): MenuItem[] {
  const items: MenuItem[] = [];
  for (const cat of CATEGORY_ORDER) {
    const grouped = actions.filter((a) => a.category === cat);
    if (grouped.length === 0) continue;
    if (items.length > 0) items.push({ kind: 'separator' });
    for (const a of grouped) items.push({ kind: 'action', action: a });
  }
  return items;
}

export class ContextMenu extends JfElement {
  static properties = {
    actions: { type: Array },
    anchor: { type: Object },
    focusedIndex: { state: true },
  };

  declare actions: ContextMenuAction[];
  declare anchor: { x: number; y: number } | null;
  declare focusedIndex: number;

  private boundDocClick = (e: Event) => this.onDocClick(e);
  private boundKey = (e: KeyboardEvent) => this.onKey(e);

  constructor() {
    super();
    this.actions = [];
    this.anchor = null;
    this.focusedIndex = 0;
  }

  static styles = css`
    :host {
      display: contents;
    }
    .menu {
      position: fixed;
      z-index: var(--z-overlay-transient);
      min-width: 12rem;
      max-width: 18rem;
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      box-shadow: 0 12px 30px rgba(0, 0, 0, 0.4);
      padding: 0.25rem 0;
      color: var(--text-primary);
      font-size: var(--font-size-sm);
    }
    button.item {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      width: 100%;
      padding: 0.4rem 0.75rem;
      background: transparent;
      border: none;
      color: inherit;
      cursor: pointer;
      text-align: left;
    }
    button.item:hover:not(:disabled),
    button.item.focused:not(:disabled) {
      background: var(--surface-secondary);
    }
    button.item:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }
    button.item .icon {
      color: var(--text-secondary);
      width: 1rem;
      display: inline-flex;
      justify-content: center;
    }
    button.item .label {
      flex: 1;
    }
    button.item .shortcut {
      color: var(--text-muted);
      font-family: monospace;
      font-size: var(--font-size-xs);
    }
    .sep {
      height: 1px;
      background: var(--border-subtle);
      margin: 0.25rem 0;
    }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    document.addEventListener('mousedown', this.boundDocClick, true);
    document.addEventListener('keydown', this.boundKey, true);
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    document.removeEventListener('mousedown', this.boundDocClick, true);
    document.removeEventListener('keydown', this.boundKey, true);
  }

  override updated(): void {
    // Auto-adjust position to stay in viewport.
    const menu = this.shadowRoot?.querySelector<HTMLElement>('.menu');
    if (!menu || !this.anchor) return;
    const rect = menu.getBoundingClientRect();
    let left = this.anchor.x;
    let top = this.anchor.y;
    if (left + rect.width > window.innerWidth - 8) {
      left = window.innerWidth - rect.width - 8;
    }
    if (top + rect.height > window.innerHeight - 8) {
      top = window.innerHeight - rect.height - 8;
    }
    menu.style.left = `${Math.max(8, left)}px`;
    menu.style.top = `${Math.max(8, top)}px`;
  }

  private items(): MenuItem[] {
    return buildItems(this.actions);
  }

  private enabledIndices(): number[] {
    const items = this.items();
    const out: number[] = [];
    items.forEach((i, idx) => {
      if (i.kind === 'action' && i.action?.enabled) out.push(idx);
    });
    return out;
  }

  private onDocClick(e: Event): void {
    const path = (e as MouseEvent).composedPath();
    if (!path.includes(this)) {
      this.cancel();
    }
  }

  private onKey(e: KeyboardEvent): void {
    if (e.key === 'Escape') {
      e.preventDefault();
      this.cancel();
      return;
    }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const indices = this.enabledIndices();
      if (indices.length === 0) return;
      const cur = indices.indexOf(this.focusedIndex);
      const delta = e.key === 'ArrowDown' ? 1 : -1;
      const nextPos = (cur + delta + indices.length) % indices.length;
      this.focusedIndex = indices[nextPos] ?? indices[0]!;
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      const items = this.items();
      const item = items[this.focusedIndex];
      if (item?.kind === 'action' && item.action?.enabled) {
        void this.invoke(item.action);
      }
    }
  }

  private async invoke(action: ContextMenuAction): Promise<void> {
    if (action.requiresConfirmation) {
      const ok = await confirmAsync({
        title: action.requiresConfirmation.title,
        message: action.requiresConfirmation.message,
        variant: action.requiresConfirmation.variant ?? 'danger',
        confirmLabel: action.requiresConfirmation.confirmLabel,
      });
      if (!ok) {
        this.cancel();
        return;
      }
    }
    this.dispatchEvent(
      new CustomEvent('context-action', {
        detail: { id: action.id },
        bubbles: true,
        composed: true,
      }),
    );
  }

  private cancel(): void {
    this.dispatchEvent(new CustomEvent('cancel', { bubbles: true, composed: true }));
  }

  override render(): TemplateResult | typeof nothing {
    if (!this.anchor || this.actions.length === 0) return nothing;
    const items = this.items();
    return html`
      <div class="menu" role="menu">
        ${items.map((item, idx) => {
          if (item.kind === 'separator') {
            return html`<div class="sep" role="separator"></div>`;
          }
          const a = item.action!;
          return html`
            <button
              class="item ${idx === this.focusedIndex ? 'focused' : ''}"
              role="menuitem"
              ?disabled=${!a.enabled}
              @click=${() => void this.invoke(a)}
              @mouseenter=${() => (this.focusedIndex = idx)}
            >
              <span class="icon"
                >${a.icon ? icon({ name: a.icon, size: 14 }) : ''}</span
              >
              <span class="label">${a.label}</span>
              ${a.shortcut
                ? html`<span class="shortcut">${a.shortcut}</span>`
                : nothing}
            </button>
          `;
        })}
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-context-menu')) {
  customElements.define('jf-context-menu', ContextMenu);
}

// ---------- Promise-based opener ----------

export interface OpenContextMenuOptions {
  actions: ContextMenuAction[];
  anchor: { x: number; y: number };
  /**
   * Tempdoc 508 §4 — context name for plugin-contributed actions.
   * When set, ContextActionRegistry entries matching this context are
   * appended to the action list and invoked directly when chosen.
   */
  context?: string;
  /** Payload passed to plugin action handlers and `enabled` filters. */
  payload?: unknown;
  /**
   * §28.W2 — when provided, the Addressable is threaded through
   * `listContextActions(..., addressable)` so the EvaluationContext
   * layer (Scope ∪ TargetFacts) applies the registered projector for
   * `addressable.kind`. The when-clauses on contributed ContextActions
   * see the flat-key facts the projector produces.
   */
  addressable?: import('../substrates/addressable.js').Addressable | null;
}

/**
 * Open a context menu at the given coordinate. Returns the invoked
 * action id, or null if cancelled. When `context` is provided, plugin
 * actions registered for that context are appended; if the user picks
 * one, the plugin handler is invoked here (the returned id is still
 * the action id for callers that branch on it).
 */
let ctxMenuSeq = 0;

export function openContextMenu(opts: OpenContextMenuOptions): Promise<string | null> {
  // Tempdoc 508 §4 — merge plugin-contributed actions for this context.
  type PluginAction = {
    id: string;
    label: string;
    icon?: string;
    handler: (payload: unknown) => void | Promise<void>;
  };
  let pluginActions: PluginAction[] = [];
  if (opts.context) {
    // §28.W2 — pass addressable through so registered projectors fire
    // and `when`-clauses see flat-key facts.
    pluginActions = listContextActions(opts.context, opts.payload, opts.addressable ?? null)
      .filter((a) => !a.enabled || a.enabled(opts.payload))
      .map((a) => ({
        id: a.id,
        label: a.label,
        icon: a.icon,
        handler: a.handler,
      }));
  }

  const mergedActions: ContextMenuAction[] = [
    ...opts.actions,
    ...pluginActions.map((a) => ({
      id: a.id,
      label: a.label,
      icon: a.icon as IconName | undefined,
      category: 'plugin' as ContextActionCategory,
      enabled: true,
    })),
  ];

  return new Promise<string | null>((resolve) => {
    const el = document.createElement('jf-context-menu') as ContextMenu;
    el.actions = mergedActions;
    el.anchor = opts.anchor;
    el.focusedIndex = 0;
    const menuId = `context-menu-${++ctxMenuSeq}`;
    const cleanup = (id: string | null) => {
      transient.close();
      el.remove();
      // Invoke plugin handler if a plugin action was chosen.
      if (id) {
        const pluginAction = pluginActions.find((a) => a.id === id);
        if (pluginAction) {
          void Promise.resolve(pluginAction.handler(opts.payload));
        }
      }
      resolve(id);
    };
    // 574 §22.F — arbitration by construction: a per-menu TransientController on the freshly-created host.
    const transient = new TransientController(el, {
      layer: 'transient',
      id: menuId,
      close: () => cleanup(null),
    });
    el.addEventListener('context-action', (e) => {
      const detail = (e as CustomEvent<{ id: string }>).detail;
      cleanup(detail.id);
    }, { once: true });
    el.addEventListener('cancel', () => cleanup(null), { once: true });
    document.body.appendChild(el);
    transient.open(); // 574 §22.F — single-open by construction (register + close peers)
  });
}
