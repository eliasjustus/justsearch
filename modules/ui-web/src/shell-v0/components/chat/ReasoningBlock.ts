// SPDX-License-Identifier: Apache-2.0
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { copyToClipboard } from '../../utils/clipboardCopy.js';
import type { ReasoningController } from '../../controllers/ReasoningController.js';

import './MarkdownBlock.js';

export class ReasoningBlock extends JfElement {
  static properties = {
    controller: { attribute: false },
    text: { type: String },
    durationMs: { type: Number },
  };

  declare controller: ReasoningController | null;
  declare text: string;
  declare durationMs: number;

  private collapsed = true;
  private wasStreaming = false;

  constructor() {
    super();
    this.controller = null;
    this.text = '';
    this.durationMs = 0;
  }

  private get effectiveText(): string {
    if (this.controller) {
      if (this.controller.isThinking) return this.controller.reasoningText;
      const last = this.controller.reasoningBlocks[this.controller.reasoningBlocks.length - 1];
      return last?.text ?? '';
    }
    return this.text;
  }

  private get effectiveIsStreaming(): boolean {
    return this.controller?.isThinking ?? false;
  }

  private get effectiveDurationMs(): number {
    if (this.controller?.isThinking) return this.controller.elapsedSeconds * 1000;
    if (this.controller) {
      const last = this.controller.reasoningBlocks[this.controller.reasoningBlocks.length - 1];
      return last?.durationMs ?? 0;
    }
    return this.durationMs;
  }

  override updated(_changed: Map<string, unknown>): void {
    const streaming = this.effectiveIsStreaming;
    if (this.wasStreaming && !streaming && this.effectiveText) {
      this.collapsed = true;
      this.requestUpdate();
    }
    this.wasStreaming = streaming;
  }

  static styles = css`
    :host {
      display: block;
    }
    .container {
      border-left: 3px solid var(--border-muted);
      background: var(--surface-subtle);
      border-radius: 6px;
      padding: 0.5rem 0.75rem;
      font-size: var(--font-size-sm);
      color: var(--text-muted);
    }
    .header {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      cursor: pointer;
      user-select: none;
    }
    .header:hover {
      color: var(--text-secondary);
    }
    .header:focus-visible {
      outline: 2px solid var(--accent-primary);
      outline-offset: 2px;
      border-radius: 4px;
    }
    .chevron {
      transition: transform var(--duration-fast) var(--ease-standard);
      font-size: var(--font-size-xs);
    }
    .chevron.expanded {
      transform: rotate(90deg);
    }
    .label {
      font-weight: 500;
    }
    .copy-btn {
      margin-left: auto;
      background: none;
      border: none;
      color: var(--text-muted);
      cursor: pointer;
      font-size: var(--font-size-xs);
      padding: 0.125rem 0.25rem;
      border-radius: 3px;
    }
    .copy-btn:hover {
      color: var(--text-secondary);
      background: var(--surface-subtle);
    }
    .content {
      margin-top: 0.5rem;
      max-height: 300px;
      overflow-y: auto;
    }
    .content.hidden {
      display: none;
    }
    .content jf-markdown-block {
      --text-primary: var(--text-muted);
      font-size: var(--font-size-sm);
    }
    jf-pulse-dots {
      margin-left: 0.25rem;
    }
  `;

  private toggle(): void {
    this.collapsed = !this.collapsed;
    this.requestUpdate();
  }

  private handleKeydown(e: KeyboardEvent): void {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      this.toggle();
    }
  }

  private async copyText(): Promise<void> {
    const text = this.effectiveText;
    if (!text) return;
    // 574 B2 — the one clipboard authority (never throws; handles availability + failure).
    await copyToClipboard(text);
  }

  override render(): TemplateResult {
    const streaming = this.effectiveIsStreaming;
    const text = this.effectiveText;
    const durationMs = this.effectiveDurationMs;
    const showContent = !this.collapsed && text.length > 0;
    const seconds = Math.max(1, Math.round(durationMs / 1000));
    const label = streaming
      ? `Thinking (${this.controller?.elapsedSeconds ?? 0}s)`
      : `Thought for ${seconds}s`;

    return html`
      <div class="container">
        <div
          class="header"
          role="button"
          tabindex="0"
          aria-expanded="${showContent}"
          aria-label="Model reasoning trace"
          @click=${this.toggle}
          @keydown=${this.handleKeydown}
        >
          <span class="chevron ${showContent ? 'expanded' : ''}">&#x25B6;</span>
          <span class="label">${label}</span>
          ${streaming ? html`<jf-pulse-dots></jf-pulse-dots>` : nothing}
          ${!streaming && text
            ? html`<button
                class="copy-btn"
                @click=${(e: Event) => { e.stopPropagation(); void this.copyText(); }}
                title="Copy reasoning"
              >&#x1F4CB;</button>`
            : nothing}
        </div>
        <div class="content ${showContent ? '' : 'hidden'}">
          <jf-markdown-block
            .text=${text}
            ?is-streaming=${streaming}
          ></jf-markdown-block>
        </div>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-reasoning-block')) {
  customElements.define('jf-reasoning-block', ReasoningBlock);
}
