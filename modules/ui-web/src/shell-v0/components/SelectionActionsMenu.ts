// SPDX-License-Identifier: Apache-2.0
/**
 * SelectionActionsMenu — Tempdoc 526 §4.3 / §4.5 — the F9 "typed actions
 * menu" rendered as a floating popover near the current selection.
 *
 * Self-mounting Lit element (`<jf-selection-actions-menu>`). Subscribes to
 * `selectionState`; when a text-range / citation / result-set selection
 * arrives, it shows the registry-derived list of applicable actions sorted by
 * floating-presentation priority. Picking an action runs `compose()` with the
 * action's operation and the current selection.
 *
 * Per tempdoc 526 §4.3 property 1, "the registry IS the menu's data" — this
 * component does NOT hard-code labels or operations; it projects whatever the
 * `SelectionActionRegistry` exposes today plus whatever plugins register
 * tomorrow.
 *
 * Positioning: when a `text-range` selection is active, anchors above the
 * bounding rect of the live DOM selection (`window.getSelection().getRangeAt(0)`).
 * For non-text-range kinds (citation, result-set), the menu can be triggered
 * programmatically via `openAt(rect)`; without an anchor it centers on screen.
 *
 * Dismissal: Esc key, click outside, selection cleared, or after an action
 * fires.
 */

import { html, css, nothing } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { TransientController } from '../primitives/transientController.js';
import {
  listSelectionActions,
  onSelectionActionChange,
  type SelectionActionContribution,
} from '../commands/SelectionActionRegistry.js';
import {
  subscribeSelection,
  getSelection,
  clearSelection,
  type SelectionItem,
} from '../state/selectionState.js';
import { compose, type ComposeArgs } from '../utils/compose.js';
import {
  peekMenuAnchor,
  subscribeMenuAnchor,
  setMenuAnchor,
} from '../utils/selectionAnchor.js';
import type { SelectionPayload } from '../../api/types/selection.js';

interface AnchorRect {
  readonly top: number;
  readonly left: number;
  readonly bottom: number;
  readonly right: number;
}

export class SelectionActionsMenu extends JfElement {
  /** 574 §22.F — single-open arbitration by construction (the dismiss-triad sibling of ModalityController). */
  private readonly transient = new TransientController(this, {
    layer: 'transient',
    id: 'selection-menu',
    managesDismiss: true, // 574 §22.G — outside-click + Esc dismiss by construction
    close: () => this.hide(),
  });

  static properties = {
    visible: { state: true },
    actions: { state: true },
    anchorRect: { state: true },
  };

  declare visible: boolean;
  declare actions: ReadonlyArray<SelectionActionContribution>;
  declare private anchorRect: AnchorRect | null;

  private unsubSelection: (() => void) | null = null;
  private unsubRegistry: (() => void) | null = null;
  private unsubAnchor: (() => void) | null = null;
  constructor() {
    super();
    this.visible = false;
    this.actions = [];
    this.anchorRect = null;
  }

  static styles = css`
    :host {
      position: fixed;
      z-index: var(--z-overlay-modal);
      pointer-events: none;
    }
    .menu {
      pointer-events: auto;
      background: var(--surface-secondary);
      border: 1px solid var(--border-strong);
      border-radius: 0.5rem;
      box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45);
      padding: 0.25rem;
      display: flex;
      flex-direction: row;
      gap: 0.25rem;
      font-family: system-ui, sans-serif;
      font-size: var(--font-size-sm);
    }
    button {
      background: transparent;
      border: 1px solid transparent;
      color: var(--text-primary);
      padding: 0.375rem 0.625rem;
      border-radius: 0.375rem;
      cursor: pointer;
      white-space: nowrap;
    }
    button:hover {
      background: var(--surface-tertiary);
      border-color: var(--border-subtle);
    }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    this.unsubSelection = subscribeSelection(() => this.refresh());
    this.unsubRegistry = onSelectionActionChange(() => this.refresh());
    // Tempdoc 526 §14.5 T1 — producer-side anchor register. Producers
    // (InspectorPane, citation panel, etc.) publish the selection's
    // bounding rect; the menu repositions when the anchor changes.
    this.unsubAnchor = subscribeMenuAnchor(() => this.refresh());
    // 574 §22.G — outside-click + Esc dismiss is owned by the TransientController (installed on show()).
    this.refresh();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubSelection?.();
    this.unsubRegistry?.();
    this.unsubAnchor?.();
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('visible')) {
      if (this.visible) {
        this.transient.open(); // 574 §22.F — single-open by construction (register + close peers)
      } else {
        this.transient.close();
      }
    }
  }

  private refresh(): void {
    const sel = getSelection();
    const item = sel.items[0];
    const floatingActions = listSelectionActions().filter(
      (a) => a.presentation.floating !== undefined,
    );
    if (!item || floatingActions.length === 0) {
      this.visible = false;
      this.actions = [];
      return;
    }
    // Tempdoc 526 §16 F8 — anchor is mandatory. Producers (InspectorPane at
    // mouseup) publish the rect; the legacy DOM-selection fallback covers
    // producers that haven't migrated. Without an anchor, the menu hides —
    // no center-on-screen fallback (that was confusing UX for kinds whose
    // visual location isn't a text rect).
    const producerRect = peekMenuAnchor();
    const rect: AnchorRect | null =
      producerRect ?? (item.kind === 'text-range' ? readDomSelectionRect() : null);
    if (!rect) {
      this.visible = false;
      return;
    }
    this.anchorRect = rect;
    this.actions = floatingActions;
    this.visible = true;
    this.position();
  }

  /** Programmatic open (used by non-text-range trigger sites). */
  openAt(rect: AnchorRect): void {
    this.anchorRect = rect;
    this.actions = listSelectionActions().filter((a) => a.presentation.floating !== undefined);
    if (this.actions.length === 0) {
      this.visible = false;
      return;
    }
    this.visible = true;
    this.position();
  }

  hide(): void {
    this.visible = false;
    this.anchorRect = null;
  }

  private position(): void {
    if (!this.anchorRect) {
      // Tempdoc 526 §16 F8 — refresh() now guards on null anchor; this is
      // defensive only.
      this.visible = false;
      return;
    }
    const top = Math.max(8, this.anchorRect.top - 48);
    const left = Math.max(8, Math.min(window.innerWidth - 280, this.anchorRect.left));
    this.style.top = `${top}px`;
    this.style.left = `${left}px`;
    this.style.transform = '';
  }

  private runAction(action: SelectionActionContribution): void {
    const sel = getSelection();
    const item = sel.items[0];
    if (!item) return;
    const payload = itemToWirePayload(item);
    if (!payload) return;
    const args: ComposeArgs = {
      operation: action.operation,
      source: 'FLOATING_MENU',
      selection: payload,
    };
    compose(args);
    this.hide();
    // After running, the selection has done its job in this UX; clear so the
    // floating menu doesn't reappear if the user clicks elsewhere. Also
    // clear the anchor register so a re-publish from a future selection
    // starts fresh.
    clearSelection();
    setMenuAnchor(null);
  }

  override render(): unknown {
    if (!this.visible || this.actions.length === 0) return nothing;
    return html`<div class="menu" role="menu">
      ${this.actions.map(
        (a) =>
          html`<button
            role="menuitem"
            @click=${() => this.runAction(a)}
            title=${a.presentation.palette?.description ?? a.presentation.floating?.label ?? a.id}
          >
            ${a.presentation.floating?.label ?? a.id}
          </button>`,
      )}
    </div>`;
  }
}

function readDomSelectionRect(): AnchorRect | null {
  const sel = window.getSelection();
  if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
  const range = sel.getRangeAt(0);
  const rect = range.getBoundingClientRect();
  if (!rect || (rect.width === 0 && rect.height === 0)) return null;
  return { top: rect.top, left: rect.left, bottom: rect.bottom, right: rect.right };
}

/**
 * Build the wire SelectionPayload from the internal SelectionItem. Mirrors
 * the wire variants — this is what `compose()` forwards as body.selection.
 */
function itemToWirePayload(item: SelectionItem): SelectionPayload | null {
  switch (item.kind) {
    case 'text-range':
      return {
        kind: 'text-range',
        address: item.address,
        selectionText: item.selectionText,
        hostEntity: item.hostEntity,
      };
    case 'citation':
      return { kind: 'citation', citation: item.citation, promotedFrom: item.promotedFrom };
    case 'result-set':
      return { kind: 'result-set', items: item.items, query: item.query };
    case 'health-condition':
      return {
        kind: 'health-condition',
        conditionId: item.conditionId,
        severity: item.severity,
        summary: item.summary,
      };
    case 'search-hit':
      return {
        kind: 'item',
        itemKind: 'search-hit',
        itemId: item.hitId,
        label: item.title,
      };
    case 'browse-node':
      return {
        kind: 'item',
        itemKind: 'browse-node',
        itemId: item.path,
      };
    case 'plugin-item':
      return {
        kind: 'item',
        itemKind: 'plugin-item',
        itemId: item.itemId,
        label: item.label,
      };
  }
}

customElements.define('jf-selection-actions-menu', SelectionActionsMenu);
