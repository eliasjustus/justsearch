// SPDX-License-Identifier: Apache-2.0
/**
 * Peek — Tempdoc 508-followup §δ2.
 *
 * Hover-preview overlay. Mounted once inside <jf-shell>. Listens for
 * `jf-peek-request` custom events from the palette (or any other
 * surface that wants to offer a hover-preview). On request: look up
 * the target surface in SurfaceCatalogClient, render it via
 * `mountSurface`, show centered popover. Dismissed on `jf-peek-dismiss`
 * (palette fires this on mouseleave or Alt-keyup).
 *
 * The hosted surface receives `data-preview-mode="true"` on its
 * mounted element so surfaces that opt in can render compactly.
 * Most surfaces ignore the attribute and render at their native
 * size, which the overlay's max-width / max-height bounds clip.
 */

import { html, css, type TemplateResult, nothing } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { TransientController } from '../primitives/transientController.js';
import { getSurface, mountSurface } from '../../api/registry/SurfaceCatalogClient.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

/**
 * Detail payload for the `jf-peek-request` custom event. The peek
 * element listens for this on its root element.
 */
export interface PeekRequestDetail {
  /** Target surface id (e.g., 'core.settings-surface'). */
  readonly surfaceId: string;
}

export class Peek extends JfElement {
  /** 574 §22.F — single-open arbitration by construction (the dismiss-triad sibling of ModalityController). */
  private readonly transient = new TransientController(this, {
    layer: 'transient',
    id: 'peek',
    // 574 §25 Edge 5 — popover-mode: native popover=auto gives single-open + Esc/outside-click dismiss.
    popoverEl: () => (this.shadowRoot?.querySelector('.panel') as HTMLElement | null) ?? null,
    close: () => {
      this.surfaceId = null;
    },
  });

  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
    host_: { attribute: false },
    surfaceId: { state: true },
  };

  declare apiBase: string;
  declare host_: PluginHostApi | undefined;
  declare surfaceId: string | null;

  constructor() {
    super();
    this.apiBase = '';
    this.host_ = undefined;
    this.surfaceId = null;
  }

  static styles = css`
    :host {
      display: contents;
    }
    .overlay {
      position: fixed;
      inset: 0;
      z-index: var(--z-overlay-transient);
      display: flex;
      align-items: center;
      justify-content: center;
      pointer-events: none;
    }
    .panel {
      /* 574 §25 Edge 5 — popover=auto Top-Layers the panel out of .overlay's flex, so center it explicitly.
         The UA gives a popover position:fixed; we override its inset:0/margin:auto with a translate-center. */
      inset: auto;
      top: 50%;
      left: 50%;
      pointer-events: auto;
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      box-shadow: var(--shadow-float);
      width: min(640px, 70vw);
      max-height: 60vh;
      overflow: hidden;
      transform: translate(-50%, -50%) scale(0.97);
      opacity: 0.97;
    }
    .label {
      padding: 0.375rem 0.75rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      border-bottom: 1px solid var(--border-subtle);
    }
  `;

  connectedCallback(): void {
    super.connectedCallback();
    document.addEventListener('jf-peek-request', this.handleRequest as EventListener);
    document.addEventListener('jf-peek-dismiss', this.handleDismiss as EventListener);
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    document.removeEventListener('jf-peek-request', this.handleRequest as EventListener);
    document.removeEventListener('jf-peek-dismiss', this.handleDismiss as EventListener);
  }

  private handleRequest = (e: Event): void => {
    const detail = (e as CustomEvent<PeekRequestDetail>).detail;
    if (!detail?.surfaceId) return;
    // 574 §25 Edge 5 — set state; `updated()` calls transient.open() AFTER the panel renders (popover-mode
    // needs the element to exist for showPopover()).
    this.surfaceId = detail.surfaceId;
  };

  private handleDismiss = (): void => {
    this.surfaceId = null; // updated() runs transient.close() once the panel is gone
  };

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('surfaceId')) {
      if (this.surfaceId) this.transient.open();
      else this.transient.close();
    }
  }

  override render(): TemplateResult {
    // 574 §25 Edge 5 — the popover panel is ALWAYS rendered (a popover element must persist; the controller
    // toggles it via showPopover/hidePopover). surfaceId drives the CONTENT, not whether the panel exists —
    // conditionally removing the panel would break the Popover API lifecycle (re-open would never fire).
    const surface = this.surfaceId ? getSurface(this.surfaceId) : null;
    let body: TemplateResult | typeof nothing = nothing;
    if (this.surfaceId && !surface) {
      body = html`
        <div class="label">Peek</div>
        <div class="label">Surface "${this.surfaceId}" not found in catalog.</div>
      `;
    } else if (this.surfaceId && surface) {
      let mounted: HTMLElement | null = null;
      try {
        mounted = mountSurface(surface, { apiBase: this.apiBase, host_: this.host_ });
        if (mounted) mounted.setAttribute('data-preview-mode', 'true');
      } catch {
        mounted = null;
      }
      body = html`
        <div class="label">Preview · ${surface.id}</div>
        ${mounted !== null ? html`${mounted}` : nothing}
      `;
    }
    return html`
      <div class="overlay">
        <div
          class="panel"
          popover="auto"
          role="dialog"
          aria-label=${this.surfaceId ? `Preview of ${this.surfaceId}` : 'Preview'}
        >
          ${body}
        </div>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-peek')) {
  customElements.define('jf-peek', Peek);
}
