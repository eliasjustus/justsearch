// SPDX-License-Identifier: Apache-2.0
/**
 * MemorySurface — `<jf-memory-surface>` (tempdoc 561 P-E; tempdoc 565 §26.D).
 *
 * The user-facing projection of the agent's learned MEMORY — the §P-E canonical record: inspect "what
 * it knows", record a durable fact, FORGET an item. This is the "what it knows" half.
 *
 * Tempdoc 565 §26.D — the ACTIVITY half (the presence inbox + the run-in-background launcher) was
 * FOLDED into the one interaction window: a conversation-linked background run renders as a `background`
 * run-segment in the thread (§26.A), and the cross-conversation inbox is a tab of the retrospective
 * drawer (`RetrospectivePanel`). So this surface no longer reads `/api/presence` — "what it did" lives
 * with the runs, not beside the durable facts ("what it knows" ≠ "what it did" — §14/§24, the ChatGPT
 * split). The durable-facts surface deliberately STAYS a separate peer (the §26.E fork-deletion keeps
 * one run-activity renderer: the unified projection).
 *
 * Self-contained: reads/writes the backend (`/api/memory`) via the forwarded `api-base`. Composes the
 * one SurfaceLayout authority (layout-purity), no own <h1> — the shell topbar owns the page heading,
 * this surface uses <h2> so it is embeddable as a host member (a11y-closure), native controls
 * (controls-a11y), no bare colored literals (color-tokens).
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import '../components/Button.js';
import { surfaceScrollLayoutStyles } from '../primitives/surfaceLayout.js';

interface MemoryItem {
  readonly id: string;
  readonly kind: string;
  readonly content: string;
  readonly actor: string;
  readonly createdAt: string;
}

export class MemorySurface extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    memories: { state: true },
    rememberDraft: { state: true },
    busy: { state: true },
  };

  declare apiBase: string;
  declare memories: MemoryItem[];
  declare rememberDraft: string;
  declare busy: boolean;

  constructor() {
    super();
    this.apiBase = '';
    this.memories = [];
    this.rememberDraft = '';
    this.busy = false;
  }

  connectedCallback(): void {
    super.connectedCallback();
    void this.loadMemories();
  }

  /** Tempdoc 609 — settle transient state on hide (the in-flight save flag) so a return doesn't show a
   *  stale spinner. The `rememberDraft` is an in-progress user input (recoverable) and is kept;
   *  `memories` re-loads on reconnect. Auto-invoked via JfElement.disconnectedCallback. */
  static override transientState = { busy: false };

  private base(): string {
    return this.apiBase || '';
  }

  private async loadMemories(): Promise<void> {
    try {
      const r = await fetch(`${this.base()}/api/memory`);
      if (!r.ok) return;
      const j = (await r.json()) as { memories?: MemoryItem[] };
      this.memories = Array.isArray(j.memories) ? j.memories : [];
    } catch {
      /* offline — keep prior */
    }
  }

  private async remember(): Promise<void> {
    const content = this.rememberDraft.trim();
    if (!content || this.busy) return;
    this.busy = true;
    try {
      await fetch(`${this.base()}/api/memory`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content, kind: 'fact' }),
      });
      this.rememberDraft = '';
      await this.loadMemories();
    } catch {
      /* offline */
    } finally {
      this.busy = false;
    }
  }

  private async forget(id: string): Promise<void> {
    try {
      await fetch(`${this.base()}/api/memory/${encodeURIComponent(id)}`, { method: 'DELETE' });
      await this.loadMemories();
    } catch {
      /* offline */
    }
  }

  render(): TemplateResult {
    return html`
      <div class="surface-scroll memory-surface">
        <!-- Tempdoc 571 §11 / 578: the page <h1> is owned by the shell topbar (surface title); this
             surface renders <h2> so it does not emit a second <h1> when embedded as a host member. -->
        <h2 class="surface-title-h">Memory</h2>

        <section>
          <h2>What it knows</h2>
          ${this.memories.length === 0
            ? html`<p class="empty">No learned memory yet.</p>`
            : html`<ul class="list">
                ${this.memories.map(
                  (m) => html`<li>
                    <span class="kind">${m.kind}</span>
                    <span class="content">${m.content}</span>
                    <jf-button
                      size="sm"
                      label="Forget"
                      title="Forget this"
                      .onActivate=${() => void this.forget(m.id)}
                    >
                      Forget
                    </jf-button>
                  </li>`,
                )}
              </ul>`}
          <div class="add-row">
            <input
              class="draft"
              .value=${this.rememberDraft}
              placeholder="Teach it a durable fact…"
              @input=${(e: Event) => (this.rememberDraft = (e.target as HTMLInputElement).value)}
            />
            <jf-button
              variant="primary"
              label="Remember"
              ?disabled=${this.busy || !this.rememberDraft.trim()}
              .onActivate=${() => void this.remember()}
            >
              Remember
            </jf-button>
          </div>
        </section>
      </div>
    `;
  }

  static styles = [
    surfaceScrollLayoutStyles,
    css`
      .memory-surface {
        padding: 1rem 1.25rem;
        display: flex;
        flex-direction: column;
        gap: 1.5rem;
      }
      section {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
      }
      h2 {
        font-size: var(--font-size-md);
        margin: 0;
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }
      .list {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
      }
      .list li {
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }
      .kind {
        font-size: var(--font-size-xs);
        text-transform: uppercase;
        letter-spacing: 0.05em;
        opacity: 0.7;
      }
      .content {
        flex: 1;
      }
      .empty {
        opacity: 0.6;
        margin: 0;
      }
      .add-row {
        display: flex;
        gap: 0.5rem;
        margin-top: 0.25rem;
      }
      .draft {
        flex: 1;
        padding: 0.35rem 0.5rem;
      }
      /* 574 B1 — the action buttons are jf-button atoms now; the base button{} fork is gone. */
    `,
  ];
}

customElements.define('jf-memory-surface', MemorySurface);
