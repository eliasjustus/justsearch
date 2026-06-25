// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-sources-pane> — Tempdoc 565 §3.A (the grounded-answer authority's evidence surface).
 *
 * The right-drawer that shows the LATEST agent answer's grounding sources — each a verifiable LOCAL
 * passage. Reads the ONE shared `AgentSessionController` (the same `answerSources` the done event
 * carried) and renders clickable cards (filename · line · excerpt). Clicking dispatches the existing
 * `citation-select` event → `Shell.onCitationSelect` → `InspectorPane.highlightCitation`, opening the
 * exact local file at the highlighted line — the differentiator web-grounded tools cannot do. Mirrors
 * the retrospective drawer's mount pattern (slot="right-drawer" + an open-store).
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import { TransientController } from '../primitives/transientController.js';
import {
  getAgentSessionController,
  subscribeAgentSession,
} from '../state/agentSessionStore.js';
import { isSourcesOpen, setSourcesOpen, subscribeSources } from '../state/sourcesDrawer.js';
import {
  getSelectedSource,
  setSelectedSource,
  subscribeSelectedSource,
  sourceKey,
} from '../state/selectedSource.js';
import { filenameOf } from './chat/evidenceProjection.js';
import { activateOnKey } from '../utils/keyboardHandler.js';
// Tempdoc 610 §J.3 — the shared hidden-source store + the active conversation id, so the rail offers the
// same hide/restore control as the inline chips and dims consistently.
import {
  getExcludedSources,
  toggleExcludedSource,
  subscribeExcludedSources,
  sourceExcludeKey,
} from '../state/excludedSources.js';
import {
  getConversationListState,
  subscribeConversationList,
} from '../state/conversationListStore.js';
import type { AgentSource } from '../controllers/AgentSessionController.js';
import type { CitationSelectDetail } from './chat/citationTypes.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

export class SourcesPane extends JfElement {
  static properties = {
    open: { type: Boolean, reflect: true },
    // Tempdoc 565 §12.3.E — docked mode: the persistent evidence rail (always-visible, inline in the
    // three-zone conversation layout, OUTSIDE the single-drawer arbiter) rather than the toggle drawer.
    docked: { type: Boolean, reflect: true },
    apiBase: { type: String, attribute: 'api-base' },
    host_: { attribute: false },
  };

  declare open: boolean;
  declare docked: boolean;
  declare apiBase: string;
  declare host_: PluginHostApi | undefined;

  private unsubs: Array<() => void> = [];

  /** 574 §23.B — single-open arbitration by construction. Reflects the `sourcesDrawer` store; the controller
   *  closes peer drawers on open and is closed by a peer opening. No outside-click dismiss (Close button / Esc). */
  private readonly transient = new TransientController(this, {
    layer: 'right-drawer',
    id: 'sources',
    close: () => setSourcesOpen(false),
  });

  constructor() {
    super();
    this.open = isSourcesOpen();
    this.docked = false;
    this.apiBase = '';
  }

  connectedCallback(): void {
    super.connectedCallback();
    this.unsubs = [
      subscribeAgentSession(() => this.requestUpdate()),
      subscribeSources(() => {
        this.open = isSourcesOpen();
        this.requestUpdate();
      }),
      // Tempdoc 565 §12.3.E — re-render the highlight when the cross-surface selection changes.
      subscribeSelectedSource(() => this.requestUpdate()),
      // Tempdoc 610 §J.3 — re-render dim/toggle state when a source is hidden/restored (either view).
      subscribeExcludedSources(() => this.requestUpdate()),
      subscribeConversationList(() => this.requestUpdate()),
    ];
  }

  /** Tempdoc 610 §J.3 — toggle a retrieved source's hidden state for the active conversation. */
  private async onToggleHidden(s: AgentSource): Promise<void> {
    const sessionId = getConversationListState().activeId;
    if (!sessionId) return;
    const key = sourceExcludeKey(s.parentDocId, s.chunkIndex);
    await toggleExcludedSource(sessionId, key, !getExcludedSources().has(key));
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubs.forEach((u) => u());
    this.unsubs = [];
  }

  override updated(changed: Map<string, unknown>): void {
    // 574 §23.B — the open-state (reflected from the store) drives the single-open controller.
    if (changed.has('open')) {
      if (this.open) this.transient.open();
      else this.transient.close();
    }
  }

  private sources(): AgentSource[] {
    return getAgentSessionController(this.apiBase, this.host_).answerSources;
  }

  private onSelect(s: AgentSource): void {
    // Tempdoc 565 §12.3.E — mark this source the cross-surface selection (highlights the matching inline
    // [n] mark + this card) BEFORE the existing deep-link dispatch (open the local passage in the inspector).
    setSelectedSource(sourceKey(s.parentDocId, s.startLine));
    const detail: CitationSelectDetail = {
      parentDocId: s.parentDocId,
      startLine: s.startLine,
      endLine: s.endLine,
      startChar: 0,
      endChar: 0,
      excerpt: s.excerpt,
    };
    this.dispatchEvent(
      new CustomEvent<CitationSelectDetail>('citation-select', {
        detail,
        bubbles: true,
        composed: true,
      }),
    );
  }

  private onKeydown = (ev: KeyboardEvent): void => {
    if (ev.key === 'Escape') setSourcesOpen(false);
  };

  static styles = css`
    :host(:not([open]):not([docked])) {
      display: none;
    }
    /* Tempdoc 565 §12.3.E — docked rail: fills its three-zone column inline (no floating drawer chrome). */
    :host([docked]) {
      display: block;
      height: 100%;
    }
    .panel {
      position: relative;
      height: 100%;
      width: 24rem;
      max-width: 90vw;
      background: var(--surface-1);
      border-left: 1px solid var(--border-default);
      box-shadow: -4px 0 16px rgba(0, 0, 0, 0.35);
      color: var(--text-primary);
      display: flex;
      flex-direction: column;
    }
    :host([docked]) .panel {
      width: 100%;
      max-width: none;
      box-shadow: none;
      border-left: none;
      border-radius: 0.5rem;
      border: 1px solid var(--border-subtle);
      background: var(--surface-2);
    }
    .head {
      flex-shrink: 0;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--border-default);
    }
    .title {
      font-weight: 600;
      font-size: var(--font-size-sm);
    }
    /* 574 critical-analysis F2 — the close is a jf-button(sm) atom; it skins itself.
       The old class-only .close skin was deleted (it leaked onto the jf-button host). */
    .scroll {
      flex: 1;
      overflow-y: auto;
      padding: 1rem;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    .empty-state {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 2rem;
      text-align: center;
      color: var(--text-secondary);
    }
    .source {
      padding: 0.625rem 0.75rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      cursor: pointer;
    }
    .source:hover,
    .source:focus-visible {
      background: var(--surface-hover);
      border-color: var(--accent-command);
      outline: none;
    }
    /* Tempdoc 565 §12.3.E — the cross-surface selection: this card is the source the user focused
       (via an inline [n] mark or this card), highlighted in sync with the matching answer citation. */
    .source.selected {
      background: var(--surface-hover);
      border-color: var(--accent-command);
    }
    .source-head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 0.5rem;
    }
    .source-name {
      font-size: var(--font-size-sm);
      font-weight: 600;
      color: var(--text-primary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    /* Tempdoc 610 §J.3 — the per-source hide/restore toggle + the hidden treatment. */
    .source-hide {
      all: unset;
      cursor: pointer;
      flex-shrink: 0;
      padding: 0 0.3rem;
      font-size: var(--font-size-xs);
      line-height: 1;
      color: var(--text-muted);
      border-radius: 0.2rem;
    }
    .source-hide:hover,
    .source-hide:focus-visible {
      color: var(--text-primary);
      background: var(--surface-hover);
      outline: none;
    }
    .source.hidden-source {
      opacity: 0.5;
    }
    .source.hidden-source .source-name {
      text-decoration: line-through;
    }
    .source-loc {
      flex-shrink: 0;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      font-family: var(--font-mono);
    }
    .source-excerpt {
      margin-top: 0.25rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      display: -webkit-box;
      -webkit-line-clamp: 3;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }
  `;

  override render(): TemplateResult {
    const sources = this.sources();
    return html`
      <div class="panel" role="region" aria-label="Answer sources" @keydown=${this.onKeydown}>
        <div class="head">
          <span class="title">Sources${sources.length > 0 ? ` · ${sources.length}` : ''}</span>
          ${this.docked
            ? nothing
            : html`<jf-button class="close" size="sm" label="Close" .onActivate=${() => setSourcesOpen(false)}>Close</jf-button>`}
        </div>
        ${sources.length === 0
          ? html`<div class="empty-state">No grounded sources for the latest answer.</div>`
          : html`<div class="scroll">
              ${sources.map((s) => {
                const selected = getSelectedSource() === sourceKey(s.parentDocId, s.startLine);
                // Tempdoc 603 D-4 — a document-level source (no chunk identity) carries the -1 sentinel
                // for its line: show no precise locator (never "line -1"/"line 0"), and the open hint
                // omits the line. The card still deep-links to the file (opened at top, no false highlight).
                const hasLine = s.startLine >= 0;
                const name = s.title || filenameOf(s.path);
                const isHidden = getExcludedSources().has(
                  sourceExcludeKey(s.parentDocId, s.chunkIndex),
                );
                return html`
                  <div
                    class="source ${selected ? 'selected' : ''} ${isHidden ? 'hidden-source' : ''}"
                    role="button"
                    tabindex="0"
                    aria-current=${selected ? 'true' : 'false'}
                    title=${hasLine ? `Open ${s.path} at line ${s.startLine}` : `Open ${s.path}`}
                    @click=${() => this.onSelect(s)}
                    @keydown=${(e: KeyboardEvent) => activateOnKey(e, () => this.onSelect(s))}
                  >
                    <div class="source-head">
                      <span class="source-name">${name}</span>
                      ${hasLine
                        ? html`<span class="source-loc">line ${s.startLine}</span>`
                        : nothing}
                      <button
                        class="source-hide"
                        aria-label=${isHidden
                          ? `Restore ${name} to the assistant's retrieval`
                          : `Hide ${name} from the assistant's retrieval`}
                        title=${isHidden ? 'Restore to retrieval' : 'Hide from retrieval'}
                        @click=${(e: Event) => {
                          e.stopPropagation();
                          void this.onToggleHidden(s);
                        }}
                      >
                        ${isHidden ? '↺' : '×'}
                      </button>
                    </div>
                    ${s.excerpt ? html`<div class="source-excerpt">${s.excerpt}</div>` : nothing}
                  </div>
                `;
              })}
            </div>`}
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-sources-pane')) {
  customElements.define('jf-sources-pane', SourcesPane);
}
