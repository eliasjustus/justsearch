// SPDX-License-Identifier: Apache-2.0
/**
 * Shell — the V0 docking shell. Wraps Lumino's `DockPanel` with an
 * id-keyed pane registry and serializable layout state.
 *
 * Per slice 3a.1 §"Slice 3a.1" Phase 6: Lumino is the layout substrate
 * (drag-drop dock, JSON-saveable). The shell owns:
 *  - A `DockPanel` instance.
 *  - A map from pane id → `LitWidget`.
 *  - A serialization layer that converts `DockLayout.ILayoutConfig`
 *    (which references `Widget` objects directly) to/from a JSON
 *    layout that references panes by id.
 *
 * Why a custom serialization layer: the consumer wants to persist
 * the layout to localStorage / disk, then restore it on next load
 * by re-creating panes via `addPane()` and finally calling
 * `restoreLayout(savedJson)`. Lumino's native layout config holds
 * Widget references which can't be JSON.stringify'd and which would
 * tightly couple the persisted shape to Lumino's internal types.
 *
 * Lumino CSS is side-effect imported by `lumino-styles.ts` so
 * consumers don't need to know about Lumino's bundled CSS.
 */

import { DockLayout, DockPanel, Widget } from '@lumino/widgets';
import { MessageLoop } from '@lumino/messaging';
import { LitWidget, type LitWidgetOptions } from './LitWidget.js';
import './lumino-styles.js';

/** Public-facing pane descriptor. */
export interface PaneDescriptor extends LitWidgetOptions {}

/** Add-pane positioning options. */
export interface AddPaneOptions {
  /**
   * Insertion mode relative to `refId`. Defaults to 'tab-after'.
   * See `DockLayout.InsertMode` for the full list (split-top,
   * split-left, etc.).
   */
  mode?: DockLayout.InsertMode;
  /**
   * Reference pane id for the insertion. When null/undefined the
   * pane is added to a sensible default location (typically a new
   * top-level tab or a split off the root).
   */
  refId?: string | null;
}

// ===================================================================
// Serializable layout types
// ===================================================================

/**
 * The shell's serializable layout shape. Mirrors
 * `DockLayout.ILayoutConfig` but every Widget reference is replaced
 * with a pane id (string). JSON-safe.
 */
export interface SerializedLayout {
  main: SerializedAreaConfig | null;
}

/** Tab area: list of pane ids + the currently-active index. */
export interface SerializedTabAreaConfig {
  type: 'tab-area';
  widgets: string[];
  currentIndex: number;
}

/** Split area: orientation + children + relative sizes. */
export interface SerializedSplitAreaConfig {
  type: 'split-area';
  orientation: 'horizontal' | 'vertical';
  children: SerializedAreaConfig[];
  sizes: number[];
}

export type SerializedAreaConfig =
  | SerializedTabAreaConfig
  | SerializedSplitAreaConfig;

// ===================================================================
// Shell class
// ===================================================================

export class Shell {
  private readonly dockPanel: DockPanel;
  private readonly widgetsById = new Map<string, LitWidget>();
  private attached = false;
  private readonly resizeListener: () => void;

  constructor() {
    this.dockPanel = new DockPanel();
    this.dockPanel.id = 'jf-shell';
    this.dockPanel.addClass('jf-shell-dock');
    // Lumino's `.lm-Widget` CSS sets `position: relative` and the
    // DockPanel's children are absolutely positioned within it. The
    // root dock therefore needs `position: absolute; inset: 0` to
    // span its host, otherwise it collapses to height: 0 and the
    // panel renders blank. Inline styles keep this behaviour
    // independent of consumer-side CSS load order.
    this.dockPanel.node.style.position = 'absolute';
    this.dockPanel.node.style.inset = '0';
    // Window-resize handler so the dock re-fits when the viewport
    // changes. Bound once per Shell so detach can remove it cleanly.
    this.resizeListener = () => this.dockPanel.update();
  }

  /**
   * Add a pane. The pane's id must be unique within the shell.
   * Throws if the id is already registered.
   */
  addPane(pane: PaneDescriptor, options?: AddPaneOptions): void {
    if (this.widgetsById.has(pane.id)) {
      throw new Error(
        `Shell: a pane with id '${pane.id}' is already registered.`,
      );
    }
    const widget = new LitWidget(pane);
    this.widgetsById.set(pane.id, widget);
    const ref = options?.refId
      ? (this.widgetsById.get(options.refId) ?? null)
      : null;
    this.dockPanel.addWidget(widget, { mode: options?.mode, ref });
  }

  /**
   * Remove a pane by id. Returns true if the pane was present and
   * removed; false if no pane with that id existed.
   *
   * The pane's content node is detached from the DOM. The consumer's
   * Lit element retains its state if they kept a reference to it.
   */
  removePane(id: string): boolean {
    const widget = this.widgetsById.get(id);
    if (!widget) {
      return false;
    }
    widget.parent = null;
    widget.dispose();
    this.widgetsById.delete(id);
    return true;
  }

  /** True if a pane with the given id is registered. */
  hasPane(id: string): boolean {
    return this.widgetsById.has(id);
  }

  /** Snapshot of currently-registered pane ids (insertion order). */
  paneIds(): readonly string[] {
    return Array.from(this.widgetsById.keys());
  }

  /**
   * Activate (focus) the pane with the given id. No-op if the id
   * isn't registered.
   */
  activatePane(id: string): void {
    const widget = this.widgetsById.get(id);
    if (widget) {
      this.dockPanel.activateWidget(widget);
    }
  }

  /**
   * Attach the shell's DockPanel to a host DOM element. Idempotent;
   * a second call while already attached is a no-op (Lumino throws
   * if you call `Widget.attach` on an already-attached widget).
   *
   * After attach, sends an UnknownSize resize message so the dock
   * panel measures its own node and lays out children. Without this,
   * Lumino keeps zero-size internal layout and the panel renders
   * blank until the first window resize event.
   */
  attachTo(host: HTMLElement): void {
    if (this.attached) {
      return;
    }
    Widget.attach(this.dockPanel, host);
    this.attached = true;
    // Trigger initial layout pass.
    MessageLoop.sendMessage(this.dockPanel, Widget.ResizeMessage.UnknownSize);
    // Subscribe to window resize so the dock re-fits.
    if (typeof window !== 'undefined') {
      window.addEventListener('resize', this.resizeListener);
    }
  }

  /** Detach the DockPanel from the DOM. Idempotent. */
  detach(): void {
    if (!this.attached) {
      return;
    }
    if (typeof window !== 'undefined') {
      window.removeEventListener('resize', this.resizeListener);
    }
    Widget.detach(this.dockPanel);
    this.attached = false;
  }

  /**
   * Serialize the current layout to a JSON-safe shape. Widget
   * references in `DockLayout.ILayoutConfig` are replaced with pane
   * ids. The result can be JSON.stringify'd and persisted.
   */
  saveLayout(): SerializedLayout {
    const config = this.dockPanel.saveLayout();
    return { main: serializeArea(config.main) };
  }

  /**
   * Restore a previously-saved layout. The consumer must have
   * already added every pane referenced by the saved layout via
   * `addPane()` before calling restoreLayout — pane ids that don't
   * resolve are silently dropped from the restored layout.
   */
  restoreLayout(layout: SerializedLayout): void {
    const config: DockLayout.ILayoutConfig = {
      main: deserializeArea(layout.main, this.widgetsById),
    };
    this.dockPanel.restoreLayout(config);
  }

  /**
   * Tear down the shell. Disposes the DockPanel and all registered
   * widgets. After dispose, the shell is unusable.
   */
  dispose(): void {
    if (this.attached) {
      this.detach();
    }
    this.dockPanel.dispose();
    this.widgetsById.clear();
  }
}

// ===================================================================
// Serialization helpers
// ===================================================================

// Exported for round-trip testing (Shell.layoutRoundTrip.test.ts). These
// encode the non-trivial layout serialize/deserialize logic and warrant
// direct coverage independent of the live Lumino DockPanel.
export function serializeArea(
  area: DockLayout.AreaConfig | null,
): SerializedAreaConfig | null {
  if (!area) {
    return null;
  }
  if (area.type === 'tab-area') {
    return {
      type: 'tab-area',
      widgets: area.widgets.map((w) => w.id),
      currentIndex: area.currentIndex,
    };
  }
  // split-area
  const children = area.children
    .map((c) => serializeArea(c))
    .filter((c): c is SerializedAreaConfig => c !== null);
  return {
    type: 'split-area',
    orientation: area.orientation,
    children,
    sizes: [...area.sizes],
  };
}

export function deserializeArea(
  area: SerializedAreaConfig | null,
  widgetsById: Map<string, LitWidget>,
): DockLayout.AreaConfig | null {
  if (!area) {
    return null;
  }
  if (area.type === 'tab-area') {
    const widgets = area.widgets
      .map((id) => widgetsById.get(id))
      .filter((w): w is LitWidget => w !== undefined);
    if (widgets.length === 0) {
      return null;
    }
    return {
      type: 'tab-area',
      widgets,
      currentIndex: Math.min(
        Math.max(area.currentIndex, 0),
        widgets.length - 1,
      ),
    };
  }
  // F14: deserialize each child while keeping its position-aligned size.
  // A child whose pane id no longer resolves drops out together with its
  // own size entry, so the SURVIVING children retain their saved
  // proportions. (The prior code reset every size to equal weight the
  // moment any one child dropped — discarding the survivors' sizing too.)
  // A missing size entry pads to equal weight (1).
  const pairs = area.children
    .map((child, i) => ({
      area: deserializeArea(child, widgetsById),
      size: typeof area.sizes[i] === 'number' ? area.sizes[i]! : 1,
    }))
    .filter(
      (p): p is { area: DockLayout.AreaConfig; size: number } =>
        p.area !== null,
    );
  if (pairs.length === 0) {
    return null;
  }
  return {
    type: 'split-area',
    orientation: area.orientation,
    children: pairs.map((p) => p.area),
    sizes: pairs.map((p) => p.size),
  };
}
