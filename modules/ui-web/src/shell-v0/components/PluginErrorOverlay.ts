// SPDX-License-Identifier: Apache-2.0
/**
 * PluginErrorOverlay — Tempdoc 508 §6.2 — dev-mode error overlay for
 * plugin load failures.
 *
 * Listens for `jf-plugin-load-error` custom events on document. Shows
 * a stack of dismissible error cards. SettingsSurface's Load-from-URL
 * dispatches this event when loadPluginFromUrl throws.
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import { repeat } from 'lit/directives/repeat.js';

export interface PluginErrorSpec {
  readonly id: string;
  readonly pluginUrl?: string;
  readonly pluginId?: string;
  readonly message: string;
  readonly stack?: string;
  readonly timestamp: number;
}

const ERROR_EVENT = 'jf-plugin-load-error';

export interface PluginErrorEventDetail {
  pluginUrl?: string;
  pluginId?: string;
  message: string;
  stack?: string;
}

export function dispatchPluginError(detail: PluginErrorEventDetail): void {
  document.dispatchEvent(new CustomEvent(ERROR_EVENT, { detail, bubbles: true }));
}

export class PluginErrorOverlay extends JfElement {
  static properties = {
    errors: { state: true },
  };

  declare errors: PluginErrorSpec[];

  private listener: ((e: Event) => void) | null = null;

  constructor() {
    super();
    this.errors = [];
  }

  static styles = css`
    :host {
      /* 559 Authority I: placement owned by the OverlayHost top-right slot. */
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      max-width: 480px;
      pointer-events: none;
    }
    .error-card {
      pointer-events: auto;
      background: var(--surface-1);
      color: var(--text-primary);
      border: 1px solid var(--danger-tint);
      border-left: 4px solid var(--danger-tint);
      border-radius: 0.5rem;
      padding: 0.75rem 1rem;
      box-shadow: var(--shadow-float);
      font-size: var(--font-size-sm);
    }
    .header {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 0.5rem;
      margin-bottom: 0.375rem;
    }
    .title {
      font-weight: 600;
      color: var(--danger-tint);
    }
    .source {
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      font-family: var(--font-mono);
    }
    .message {
      margin: 0.25rem 0;
      white-space: pre-wrap;
    }
    .stack {
      margin-top: 0.5rem;
      padding: 0.375rem 0.5rem;
      background: var(--surface-2);
      border-radius: 0.25rem;
      font-family: var(--font-mono);
      font-size: var(--font-size-xs);
      max-height: 8rem;
      overflow: auto;
      white-space: pre-wrap;
    }
    .actions {
      margin-top: 0.5rem;
      display: flex;
      gap: 0.375rem;
      justify-content: flex-end;
    }
    /* 574 B (remediation) — the copy/dismiss actions are jf-button atoms now. */
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    this.listener = (e: Event) => this.handleEvent(e as CustomEvent<PluginErrorEventDetail>);
    document.addEventListener(ERROR_EVENT, this.listener);
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    if (this.listener) {
      document.removeEventListener(ERROR_EVENT, this.listener);
      this.listener = null;
    }
  }

  private handleEvent(e: CustomEvent<PluginErrorEventDetail>): void {
    const detail = e.detail;
    if (!detail) return;
    const spec: PluginErrorSpec = {
      id: `err-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      pluginUrl: detail.pluginUrl,
      pluginId: detail.pluginId,
      message: detail.message,
      stack: detail.stack,
      timestamp: Date.now(),
    };
    this.errors = [...this.errors, spec];
  }

  private dismiss(id: string): void {
    this.errors = this.errors.filter((e) => e.id !== id);
  }

  private copy(spec: PluginErrorSpec): void {
    const text = [
      spec.pluginUrl ? `URL: ${spec.pluginUrl}` : '',
      spec.pluginId ? `ID: ${spec.pluginId}` : '',
      `Error: ${spec.message}`,
      spec.stack ? `\n${spec.stack}` : '',
    ].filter(Boolean).join('\n');
    void navigator.clipboard?.writeText(text);
  }

  override render(): TemplateResult {
    if (this.errors.length === 0) return html``;
    return html`
      ${repeat(this.errors, (e) => e.id, (e) => html`
        <div class="error-card">
          <div class="header">
            <span class="title">Plugin load failed</span>
            <span class="source">${e.pluginId ?? e.pluginUrl ?? 'unknown'}</span>
          </div>
          <div class="message">${e.message}</div>
          ${e.stack ? html`<pre class="stack">${e.stack}</pre>` : ''}
          <div class="actions">
            <jf-button size="sm" label="Copy details" .onActivate=${() => this.copy(e)}>Copy details</jf-button>
            <jf-button size="sm" label="Dismiss" .onActivate=${() => this.dismiss(e.id)}>Dismiss</jf-button>
          </div>
        </div>
      `)}
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-plugin-error-overlay')) {
  customElements.define('jf-plugin-error-overlay', PluginErrorOverlay);
}
