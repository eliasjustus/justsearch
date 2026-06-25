// SPDX-License-Identifier: Apache-2.0
/**
 * HelpSurface — Lit-side Help surface (slice 451 phase 9).
 *
 * Self-mounting Surface. Mostly static content (FAQ, shortcuts,
 * privacy, network info). Single backend interaction:
 * `core.export-diagnostics`, mounted via the aggregate substrate's
 * `<jf-operation>` per tempdoc 511 §511-followup-B. Export-path
 * feedback is captured from the `op-success` event's
 * `structuredData.path`; errors from `op-error.detail.message`.
 *
 * Side-effect registers `<jf-help-surface>` for the chrome dispatcher.
 * The shell-v0 barrel (loaded eagerly via main.jsx per slice 450 §2.5)
 * pulls this file into the boot graph automatically.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { surfaceScrollLayoutStyles } from '../primitives/surfaceLayout.js';
import { icon } from '../components/Icon.js';
import '../aggregate-substrate/components/JfOperation.js';
// 569 §15 — render the Help reference content through the projection engine (the 3rd real surface).
import { activeBodyFor, subscribePresentation } from '../state/presentationRuntime.js';
import { HELP_REFERENCE_REGION } from '../themes/builtinPresentations.js';
import '../components/DeclaredSurface.js';
import type {
  OpSuccessEventDetail,
  OpErrorEventDetail,
} from '../components/OpButton.js';

interface FaqItem {
  q: string;
  a: string;
}

interface ShortcutRow {
  keys: string;
  desc: string;
}

const FAQ: FaqItem[] = [
  {
    q: 'How do I add files to search?',
    a: 'Go to Library and click "Add Folder" to index a directory. JustSearch will automatically scan and index all supported files.',
  },
  {
    q: 'What file types are supported?',
    a: 'JustSearch supports PDFs, Markdown, plain text, code files (JS, TS, Python, etc.), images (with OCR), and many more. Check the Library view for the full list.',
  },
  {
    q: 'How does AI summarization work?',
    a: 'Select a file and click "Summarize" in the Inspector panel. The AI runs locally on your machine using the configured model. No data is sent externally.',
  },
  {
    q: 'Why is indexing slow?',
    a: 'Initial indexing can take time for large folders. Once complete, incremental updates are fast. Check the Health view for indexing status.',
  },
  {
    q: 'How do I use the AI chat feature?',
    a: 'Type ?? followed by your question in the search bar to ask the AI about your selected files or general questions.',
  },
];

// Tempdoc 586 P-3 — only shortcuts that ACTUALLY fire are listed. The previous table advertised
// `/` (focus search) and `/` (command mode) — both unimplemented V1 deferrals (no global `/`
// binding; the only registered keybindings are mod+k / mod+z / mod+shift+z, see Shell.ts) — plus
// `??` (a documented search-box query *prefix* for the AI, not a keycap) and `Alt+1–4` agent tabs
// (the standalone agent surface was retired into the unified chat window). Listing shortcuts that
// don't work is worse than a short list, so the table now mirrors the real bindings.
const SHORTCUTS: ShortcutRow[] = [
  { keys: 'Ctrl / ⌘ + K', desc: 'Open the command palette' },
  { keys: 'Enter', desc: 'Run the search (when the search box is focused)' },
  { keys: 'Esc', desc: 'Clear the search box · close an open panel or drawer' },
  { keys: 'Ctrl / ⌘ + Z', desc: 'Undo' },
  { keys: 'Ctrl / ⌘ + Shift + Z', desc: 'Redo' },
];

const TROUBLESHOOTING: string[] = [
  'If search results look stale, use "Reindex" in Library (Advanced) or Health.',
  'If indexing seems stuck, try "Restart worker" in Health.',
  'Use "Export diagnostics" above before reporting a bug.',
];

const NETWORK: string[] = [
  'Local app traffic uses loopback (not a network service).',
  'AI model downloads occur only when you explicitly install models.',
  'Future "Online" features will be clearly labeled and opt-in.',
];

export class HelpSurface extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    expanded: { state: true },
    exportPath: { state: true },
    exportError: { state: true },
  };

  declare apiBase: string;
  declare expanded: number | null;
  declare exportPath: string | null;
  declare exportError: string | null;

  constructor() {
    super();
    this.apiBase = '';
    this.expanded = null;
    this.exportPath = null;
    this.exportError = null;
  }

  // 569 §15 — re-render when the active presentation changes (the declared reference region appears
  // when CORE_DECLARED is applied, reverts when cleared/quarantined — degrade-never-fail).
  private presentationUnsub: (() => void) | null = null;

  override connectedCallback(): void {
    super.connectedCallback();
    this.presentationUnsub = subscribePresentation(() => this.requestUpdate());
  }

  /** Tempdoc 609 §R (S1) — declarative transient reset (JfElement default applies it on hide): the
   *  diagnostics-export result path + error. The `expanded` FAQ choice is recoverable, KEPT. */
  static override transientState = { exportPath: null, exportError: null };

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.presentationUnsub?.();
    this.presentationUnsub = null;
  }

  /**
   * 569 §15 — the Help reference content, rendered through the engine when CORE_DECLARED declares it
   * (the 3rd real surface inverted); otherwise the built-in Lit render (degrade-never-fail). The
   * shortcuts/troubleshooting/network data flows in as the declared body's `data`.
   */
  private renderReferenceRegion(): TemplateResult {
    const body = activeBodyFor(HELP_REFERENCE_REGION);
    if (!body) {
      return html`
        ${this.renderShortcuts()}
        <section style="margin-top: 1rem">
          <div class="panel">
            <div class="panel-title">Quick troubleshooting</div>
            <ul>
              ${TROUBLESHOOTING.map((t) => html`<li>${t}</li>`)}
            </ul>
          </div>
        </section>
        <section style="margin-top: 1rem">
          <div class="panel">
            <div class="panel-title">${icon({ name: 'wifi', size: 16 })} Network activity</div>
            <ul>
              ${NETWORK.map((n) => html`<li>${n}</li>`)}
            </ul>
          </div>
        </section>
      `;
    }
    return html`<jf-declared-surface
      .declaration=${body}
      .data=${{ shortcuts: SHORTCUTS, troubleshooting: TROUBLESHOOTING, network: NETWORK }}
      .enabled=${true}
    ></jf-declared-surface>`;
  }

  static styles = [
    surfaceScrollLayoutStyles,
    css`
    .header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;
      padding-bottom: 1rem;
      border-bottom: 1px solid var(--border-subtle);
      margin-bottom: 1rem;
    }
    .header h2 {
      margin: 0;
      font-size: var(--font-size-lg);
      font-weight: 600;
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    .header .subtitle {
      margin: 0.25rem 0 0 0;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    button {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      background: var(--surface-primary);
      color: var(--text-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      padding: 0.5rem 0.75rem;
      cursor: pointer;
      font-size: var(--font-size-xs);
      font-weight: 500;
      transition: background var(--duration-fast) var(--ease-standard);
    }
    button:hover:not(:disabled) {
      background: var(--surface-hover);
    }
    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .export-status {
      margin-top: 0.5rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .export-error {
      color: var(--text-danger);
    }
    .export-status .path {
      font-family: monospace;
    }
    .grid {
      display: grid;
      grid-template-columns: 1fr;
      gap: 1.5rem;
    }
    @media (min-width: 64rem) {
      .grid {
        grid-template-columns: 1fr 1fr;
      }
    }
    section {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    h3 {
      margin: 0 0 0.25rem 0;
      font-size: var(--font-size-xs);
      font-weight: 600;
      letter-spacing: 0.05em;
      text-transform: uppercase;
      color: var(--text-secondary);
      display: flex;
      align-items: center;
      gap: 0.4rem;
    }
    .faq-item {
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      overflow: hidden;
    }
    .faq-q {
      width: 100%;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      padding: 0.75rem 1rem;
      background: transparent;
      border: none;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
      cursor: pointer;
      text-align: left;
    }
    .faq-q:hover {
      background: var(--surface-hover);
    }
    .faq-a {
      padding: 0 1rem 0.75rem 1rem;
      font-size: var(--font-size-xs);
      line-height: 1.5;
      color: var(--text-secondary);
    }
    .chev {
      transition: transform var(--duration-fast) var(--ease-standard);
    }
    .chev.open {
      transform: rotate(180deg);
    }
    .panel {
      padding: 1rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
    }
    .panel-title {
      font-size: var(--font-size-sm);
      font-weight: 500;
      color: var(--text-secondary);
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 0.5rem;
    }
    .panel ul {
      margin: 0;
      padding-left: 1.25rem;
      font-size: var(--font-size-xs);
      line-height: 1.6;
      color: var(--text-secondary);
    }
    .panel ul li + li {
      margin-top: 0.25rem;
    }
    .shortcuts {
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      overflow: hidden;
    }
    .shortcut-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 0.5rem 1rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .shortcut-row + .shortcut-row {
      border-top: 1px solid var(--border-subtle);
    }
    kbd {
      padding: 0.125rem 0.4rem;
      background: var(--surface-tertiary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      font-family: monospace;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .jf-icon-spin {
      animation: jf-spin 1s linear infinite;
    }
  `,
  ];

  private handleOpSuccess(e: CustomEvent<OpSuccessEventDetail>): void {
    this.exportError = null;
    const path = e.detail?.structuredData?.['path'];
    this.exportPath = typeof path === 'string' ? path : '';
  }

  private handleOpError(e: CustomEvent<OpErrorEventDetail>): void {
    this.exportPath = null;
    const msg = e.detail?.message;
    this.exportError = typeof msg === 'string' ? msg : 'Diagnostics export failed.';
  }

  private renderFaq(): TemplateResult {
    return html`
      <section>
        <h3>Frequently asked questions</h3>
        <div>
          ${FAQ.map(
            (item, idx) => html`
              <div class="faq-item">
                <button
                  class="faq-q"
                  @click=${() => (this.expanded = this.expanded === idx ? null : idx)}
                >
                  <span>${item.q}</span>
                  <span class="chev ${this.expanded === idx ? 'open' : ''}">
                    ${icon({ name: 'chevron-down', size: 16 })}
                  </span>
                </button>
                ${this.expanded === idx
                  ? html`<div class="faq-a">${item.a}</div>`
                  : nothing}
              </div>
            `,
          )}
        </div>
      </section>
    `;
  }

  private renderShortcuts(): TemplateResult {
    return html`
      <section>
        <h3>${icon({ name: 'keyboard', size: 14 })} Keyboard shortcuts</h3>
        <div class="shortcuts">
          ${SHORTCUTS.map(
            (s) => html`
              <div class="shortcut-row">
                <span>${s.desc}</span>
                <kbd>${s.keys}</kbd>
              </div>
            `,
          )}
        </div>
      </section>
    `;
  }

  override render(): TemplateResult {
    return html`
      <div class="header">
        <div>
          <h2>${icon({ name: 'help-circle', size: 18 })} Help &amp; Support</h2>
          <p class="subtitle">Diagnostics export and local-first transparency.</p>
        </div>
        <div
          @op-success=${(e: CustomEvent<OpSuccessEventDetail>) =>
            this.handleOpSuccess(e)}
          @op-error=${(e: CustomEvent<OpErrorEventDetail>) =>
            this.handleOpError(e)}
        >
          <jf-operation
            operation-id="core.export-diagnostics"
            context="button"
            api-base=${this.apiBase}
          ></jf-operation>
          ${this.exportPath !== null
            ? html`<div class="export-status">
                Saved to: <span class="path">${this.exportPath}</span>
              </div>`
            : nothing}
          ${this.exportError
            ? html`<div class="export-status export-error">${this.exportError}</div>`
            : nothing}
        </div>
      </div>

      <div class="grid">
        <div>
          ${this.renderFaq()}

          <section style="margin-top: 1.5rem">
            <div class="panel">
              <div class="panel-title">
                ${icon({ name: 'shield', size: 16 })} Local-first
              </div>
              <p style="margin: 0; font-size: var(--font-size-xs); line-height: 1.6; color: var(--text-secondary)">
                Your files stay on this machine. Search and indexing run locally,
                and the UI only talks to the local backend over loopback.
              </p>
            </div>
          </section>
        </div>

        <div>
          <!-- 569 §15 — the reference content (shortcuts + troubleshooting + network) renders
               THROUGH the projection engine; the FAQ + local-first + export op stay surface-owned. -->
          ${this.renderReferenceRegion()}
        </div>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-help-surface')) {
  customElements.define('jf-help-surface', HelpSurface);
}
