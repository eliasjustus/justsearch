// SPDX-License-Identifier: Apache-2.0
/**
 * LitWidget — a Lumino `Widget` that hosts a Lit-rendered (or any
 * other) HTMLElement as its content.
 *
 * Per slice 3a.1 §"Slice 3a.1" Phase 6: Lumino is the layout engine
 * (DockPanel + drag-drop docking + JSON-saveable layout state). The
 * shell-v0 substrate owns the Lit-side rendering. LitWidget is the
 * adapter: it lets Lit content live inside Lumino's docking system
 * without coupling either side to the other.
 *
 * The widget owns the lifecycle of its content node: on dispose, the
 * content node is removed from the DOM. The Lit element retains its
 * own state across detach/attach cycles (Lumino re-attaches the same
 * widget node when restoring a layout).
 */

import { Widget } from '@lumino/widgets';

/** Options for constructing a LitWidget. */
export interface LitWidgetOptions {
  /** Stable identifier; used by the shell's pane registry. */
  id: string;
  /** Tab title (defaults to `id`). */
  title?: string;
  /** The HTMLElement to display inside the widget. */
  content: HTMLElement;
  /** True if the user can close the tab. Default: true. */
  closable?: boolean;
  /** Optional caption / tooltip on the tab. */
  caption?: string;
}

export class LitWidget extends Widget {
  /** The HTMLElement supplied at construction time. */
  readonly content: HTMLElement;

  constructor(options: LitWidgetOptions) {
    super();
    this.id = options.id;
    this.title.label = options.title ?? options.id;
    this.title.closable = options.closable ?? true;
    if (options.caption) {
      this.title.caption = options.caption;
    }
    this.content = options.content;
    // The widget's node is the host for the Lit content. Lumino
    // manages this node's parentage; we just append our content to it.
    this.node.appendChild(this.content);
    this.node.classList.add('jf-lit-widget');
  }

  /**
   * On dispose, drop the content reference. Lumino removes the
   * widget node from the DOM via Widget.dispose; we just clean up
   * our own reference so the GC can collect.
   */
  override dispose(): void {
    if (this.isDisposed) {
      return;
    }
    super.dispose();
    // The content node is now detached. If the consumer wants to
    // re-use it, they kept their own reference; we drop ours.
  }
}
