// SPDX-License-Identifier: Apache-2.0
/**
 * SearchV2View — the Search v2 window skeleton (tempdoc 818 slice 1).
 *
 * A from-scratch sibling of the shipped search window, built to test the twelve laws of the 818
 * prototype against a real backend. Nothing here is copied from `UnifiedChatView.ts`: the point of
 * building beside rather than editing is that a copy would import the model-level defects (three
 * parallel conversation representations, authored counts, state-gated affordances).
 *
 * The window is one records array plus projections:
 *  - the transcript, the session index (rail mode B) and the session name are all PROJECTIONS of
 *    `records` (`records.ts`) — no region authors its own copy (L11), and the name/index appear at
 *    the first commit because the projection says so, not because a flag was flipped (L8).
 *  - the live search is NOT a record. It is fed by the shared `searchState` store (the ONE search
 *    issuance seam — this surface never posts a search itself) and becomes a record only by commit.
 *  - every count on screen is derived: the live line reads the store's true `matchCount`, the
 *    frozen block header reads |captured set|, the index header reads Σ node sizes (L6).
 *  - the destination pill is `route()` over the draft, with the ⇥ flip as a one-shot lens that
 *    dies on Escape or on commit — never a stored destination (L1/L2/L10).
 *
 * Slice 1 leaves agent-run hosting, the context ledger, lock semantics and the material rail as
 * labelled placeholders (slice 2+). Mounted as a hidden DEEPLINK surface, dev audience, no rail
 * entry: `#justsearch://surface/core.search-v2-surface`.
 *
 * Side-effect registers <jf-search-v2> for the chrome dispatcher.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { surfaceLayoutStyles } from '../../primitives/surfaceLayout.js';
import {
  setQuery,
  submitSearch,
  subscribeSearch,
  type SearchState,
} from '../../state/searchState.js';
import {
  loadConversations,
  subscribeConversationList,
} from '../../state/conversationListStore.js';
import { RUNGS, applyFlip, route, type RouteContext, type Rung } from './route.js';
import {
  NO_RECORDS,
  commitSearch,
  projectIndex,
  projectSessionName,
  projectTranscript,
  type SearchCapture,
  type SessionRecord,
  type TranscriptItem,
} from './records.js';

/** A rail mode-A row: one prior session, named by what the user can recognise. */
interface SessionRow {
  readonly id: string;
  readonly label: string;
}

export class SearchV2View extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
  } as const;

  declare apiBase: string;

  /** THE records array. Every region below is a projection of exactly this. */
  private records: readonly SessionRecord[] = NO_RECORDS;
  private draft = '';
  /** The one-shot ⇥ lens (L1). Never persisted: cleared by Escape and by every commit. */
  private flipped = false;
  /** The live deck occupant — the shared store's snapshot, never a local re-derivation. */
  private live: SearchState | null = null;
  private sessions: readonly SessionRow[] = [];
  private unsubscribeSearch: (() => void) | null = null;
  private unsubscribeSessions: (() => void) | null = null;

  constructor() {
    super();
    this.apiBase = '';
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.unsubscribeSearch = subscribeSearch((s) => {
      this.live = s;
      this.requestUpdate();
    });
    this.unsubscribeSessions = subscribeConversationList((s) => {
      this.sessions = s.conversations.map((c) => ({
        id: c.id,
        label: (c.title ?? c.firstUserMessage ?? '').trim() || 'Untitled session',
      }));
      this.requestUpdate();
    });
    void loadConversations();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubscribeSearch?.();
    this.unsubscribeSearch = null;
    this.unsubscribeSessions?.();
    this.unsubscribeSessions = null;
  }

  static styles = [
    surfaceLayoutStyles,
    css`
      :host {
        color: var(--text-primary);
      }
      .win {
        display: flex;
        gap: var(--density-inner-pad-x);
        min-height: 0;
      }
      .rail {
        flex: 0 0 14rem;
        display: flex;
        flex-direction: column;
        gap: var(--density-inner-pad-y);
        border-right: 1px solid var(--border-subtle);
        padding-right: var(--density-inner-pad-x);
        overflow-y: auto;
      }
      .centre {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
        gap: var(--density-inner-pad-y);
        overflow-y: auto;
      }
      .name {
        font-size: var(--font-size-md);
        font-weight: 600;
        color: var(--text-primary);
      }
      h2 {
        font-size: var(--font-size-xs);
        font-weight: 600;
        letter-spacing: 0.06em;
        text-transform: uppercase;
        color: var(--text-muted);
        margin: 0;
      }
      .rowlist {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
      }
      .rowlist li {
        font-size: var(--font-size-sm);
        color: var(--text-secondary);
        overflow-wrap: anywhere;
      }
      .count {
        font-size: var(--font-size-xs);
        color: var(--text-muted);
        font-variant-numeric: tabular-nums;
      }
      button {
        font: inherit;
        font-size: var(--font-size-sm);
        color: var(--text-primary);
        background: var(--surface-2);
        border: 1px solid var(--border-subtle);
        border-radius: 0.4rem;
        padding: 0.3rem 0.6rem;
        cursor: pointer;
        text-align: left;
      }
      button:hover {
        background: var(--surface-hover);
      }
      .band {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.5rem;
        border: 1px solid var(--border-subtle);
        border-radius: 0.5rem;
        padding: var(--density-inner-pad-y) var(--density-inner-pad-x);
        background: var(--surface-2);
      }
      .band input {
        flex: 1;
        min-width: 12rem;
        font: inherit;
        font-size: var(--font-size-sm);
        color: var(--text-primary);
        background: var(--surface-1);
        border: 1px solid var(--border-subtle);
        border-radius: 0.4rem;
        padding: 0.4rem 0.6rem;
      }
      .rung-pill {
        font-size: var(--font-size-xs);
        font-family: var(--jf-font-mono);
        letter-spacing: 0.06em;
        border: 1px solid var(--border-strong);
        border-radius: 0.4rem;
        padding: 0.2rem 0.5rem;
        color: var(--text-primary);
        background: var(--surface-3);
        white-space: nowrap;
      }
      .rung-pill.alt {
        background: transparent;
        color: var(--text-secondary);
      }
      .rung-pill.off {
        color: var(--text-muted);
        background: transparent;
        border-style: dashed;
      }
      .rung-pill.flip {
        border-color: var(--accent-tint);
        text-decoration: underline dashed 1px;
        text-underline-offset: 3px;
      }
      .frozen {
        border: 1px solid var(--border-subtle);
        border-left: 3px solid var(--accent-tint-45);
        border-radius: 0.4rem;
        padding: var(--density-inner-pad-y) var(--density-inner-pad-x);
        background: var(--surface-2);
      }
      .turn {
        color: var(--text-primary);
        font-size: var(--font-size-sm);
      }
      .pending {
        color: var(--text-muted);
        font-size: var(--font-size-sm);
      }
      .placeholders {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem;
      }
      .placeholder {
        flex: 1 1 10rem;
        border: 1px dashed var(--border-subtle);
        border-radius: 0.4rem;
        padding: var(--density-inner-pad-y) var(--density-inner-pad-x);
        color: var(--text-muted);
        font-size: var(--font-size-xs);
      }
      .stack {
        display: flex;
        flex-direction: column;
        gap: var(--density-inner-pad-y);
      }
    `,
  ];

  /** The visible facts routing may read. Slice 1 pins schema/run/scope until slice 2 hosts them. */
  private routeContext(): RouteContext {
    return { scopePinned: false, schemaAttached: false, runInFlight: false };
  }

  /** The pill's two slots — `route()` plus the one-shot flip lens. Never stored (L1). */
  private slots(): { primary: Rung; alt: Rung; dimmed: boolean } {
    const ctx = this.routeContext();
    const r = route(this.draft, ctx);
    if (r.empty) {
      // L10 — an empty draft submits nowhere; the pill PREVIEWS the default, dimmed.
      const preview = route('x', ctx);
      if (preview.empty) return { primary: 'search', alt: 'ask', dimmed: true };
      return { primary: preview.primary, alt: preview.alt, dimmed: true };
    }
    const lensed = applyFlip(r, this.flipped);
    return { primary: lensed.primary, alt: lensed.alt, dimmed: false };
  }

  private onInput(e: Event): void {
    this.draft = (e.target as HTMLInputElement).value;
    setQuery(this.draft);
    this.requestUpdate();
  }

  private onKeydown(e: KeyboardEvent): void {
    if (e.key === 'Tab') {
      // The flip only exists while a draft does; an empty input keeps native focus movement.
      if (route(this.draft, this.routeContext()).empty) return;
      e.preventDefault();
      this.flipped = !this.flipped;
      this.requestUpdate();
      return;
    }
    if (e.key === 'Escape') {
      this.flipped = false;
      this.requestUpdate();
      return;
    }
    if (e.key === 'Enter' && e.shiftKey) {
      e.preventDefault();
      this.commit();
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      if (this.draft.trim()) submitSearch();
    }
  }

  /**
   * The commit (L4/L5/L8): the live set freezes into the transcript as the retrieval scope, the
   * turn lands, the answer slot opens — and the name + index appear because the projections now
   * have a first committed record, not because this method touched them.
   */
  private commit(): void {
    const live = this.live;
    const turnText = this.draft.trim() || (live?.query ?? '').trim();
    if (!turnText) return; // L10 — nothing to commit.
    const capture: SearchCapture = {
      query: live?.query ?? '',
      hits: live?.results ?? [],
      total: live?.matchCount ?? 0,
      mode: live?.passStage ?? 'unknown',
      tookMs: live?.processingTimeMs ?? null,
    };
    this.records = commitSearch(this.records, capture, turnText);
    this.draft = '';
    this.flipped = false;
    this.requestUpdate();
  }

  /** Back to the sessions sidebar — an explicit user intent, never a lifecycle side effect. */
  private clearRecords(): void {
    this.records = NO_RECORDS;
    this.draft = '';
    this.flipped = false;
    this.requestUpdate();
  }

  override render(): TemplateResult {
    return html`
      <div class="header">
        <div class="name" data-testid="session-name">${projectSessionName(this.records)}</div>
      </div>
      <div class="body win">
        <nav class="rail" data-testid="rail" aria-label="Session rail">
          ${this.records.length === 0 ? this.sidebar() : this.sessionIndex()}
        </nav>
        <div class="centre">
          ${this.transcript()} ${this.deck()} ${this.placeholders()}
        </div>
      </div>
    `;
  }

  /** Rail mode A (L12): the conventional session sidebar — identical in every pre-session state. */
  private sidebar(): TemplateResult {
    return html`
      <div class="stack" data-testid="rail-sidebar">
        <button type="button" data-testid="new-session" @click=${this.clearRecords}>
          New session
        </button>
        <h2>Search sessions</h2>
        ${this.sessions.length === 0
          ? html`<p class="count" data-testid="session-empty">No earlier sessions</p>`
          : html`<ul class="rowlist" data-testid="session-list">
              ${this.sessions.map(
                (s) => html`<li data-testid="session-row" data-session-id=${s.id}>${s.label}</li>`,
              )}
            </ul>`}
      </div>
    `;
  }

  /** Rail mode B (L12 / L8 corollary): the session index, projected from the records array. */
  private sessionIndex(): TemplateResult {
    const index = projectIndex(this.records);
    return html`
      <div class="stack" data-testid="rail-index">
        <button type="button" data-testid="rail-back" @click=${this.clearRecords}>
          ‹ All sessions
        </button>
        <h2>This session</h2>
        <p class="count" data-testid="index-count">${index.headerCount} entries</p>
        <ul class="rowlist">
          ${index.nodes.map(
            (n) => html`<li data-testid="index-node" data-node-id=${n.id}>
              ${n.label} <span class="count">${n.size}</span>
            </li>`,
          )}
        </ul>
      </div>
    `;
  }

  private transcript(): TemplateResult | typeof nothing {
    const items = projectTranscript(this.records);
    if (items.length === 0) return nothing;
    return html`
      <section class="stack" data-testid="transcript">
        ${items.map((item) => this.transcriptItem(item))}
      </section>
    `;
  }

  private transcriptItem(item: TranscriptItem): TemplateResult {
    if (item.kind === 'frozen-search') {
      return html`
        <div class="frozen" data-testid="frozen-block">
          <div class="count">
            <span data-testid="frozen-query">${item.query}</span> ·
            <span data-testid="frozen-count">${item.headerLabel}</span>
          </div>
          <ul class="rowlist">
            ${item.hits.map(
              (h) => html`<li data-testid="frozen-hit">${h.title} <span class="count">${h.path}</span></li>`,
            )}
          </ul>
        </div>
      `;
    }
    if (item.kind === 'user-turn') {
      return html`<p class="turn" data-testid="turn">${item.text}</p>`;
    }
    return html`<p class="pending" data-testid="pending-answer">${item.label}</p>`;
  }

  /** The deck: the input band + the live search, the two things that are not yet records. */
  private deck(): TemplateResult {
    const live = this.live;
    const results = live?.results ?? [];
    const { primary, alt, dimmed } = this.slots();
    const askLabel =
      results.length > 0
        ? `Ask about these ${results.length}`
        : 'Ask anyway — the model retrieves at answer time';
    return html`
      <section class="stack" data-testid="deck">
        <div class="band" data-testid="input-band">
          <input
            type="text"
            data-testid="draft"
            aria-label="Search or ask about your documents"
            .value=${this.draft}
            @input=${this.onInput}
            @keydown=${this.onKeydown}
          />
          <span
            class="rung-pill ${dimmed ? 'off' : ''} ${this.flipped && !dimmed ? 'flip' : ''}"
            data-testid="pill"
            data-dimmed=${String(dimmed)}
            title=${dimmed
              ? 'previews the default — an empty draft submits nothing (L10)'
              : RUNGS[primary].label}
            >${this.flipped && !dimmed ? '⇥ ' : ''}${RUNGS[primary].pill} ⏎</span
          >
          <span class="rung-pill alt ${dimmed ? 'off' : ''}" data-testid="pill-alt" title=${RUNGS[alt].label}
            >${RUNGS[alt].pill} ⇥</span
          >
          <button type="button" data-testid="commit" @click=${this.commit}>${askLabel}</button>
        </div>
        <p class="count" data-testid="result-count">${live?.matchCount ?? 0} results</p>
        <ul class="rowlist" data-testid="live-results">
          ${results.map(
            (h) => html`<li data-testid="live-row">${h.title} <span class="count">${h.path}</span></li>`,
          )}
        </ul>
      </section>
    `;
  }

  /** Slice 2+ occupants, present as labelled boxes so the window's shape is honest about them. */
  private placeholders(): TemplateResult {
    return html`
      <div class="placeholders">
        <div class="placeholder" data-testid="placeholder-agent-run">Agent run — slice 2</div>
        <div class="placeholder" data-testid="placeholder-context">Context ledger — slice 2</div>
        <div class="placeholder" data-testid="placeholder-lock">Session lock — slice 2</div>
      </div>
    `;
  }
}

if (!customElements.get('jf-search-v2')) {
  customElements.define('jf-search-v2', SearchV2View);
}
