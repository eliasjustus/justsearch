// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 491 §9.D Phase E (C3) — typed view consuming `core.summarize`.
 *
 * Composes `<jf-streaming-text-block>` + a small doc-id input panel. POSTs to
 * `/api/chat/summarize` with `{docId, content?}`; routes SSE events through
 * the generated `CoreSummarizeHandlers` interface.
 *
 * Mount paths:
 * - **Programmatic / standalone**: registered factory for `core.summarize`;
 *   `<jf-chat-shape-mount shape-id="core.summarize">` mounts a fresh
 *   `<jf-summarize-view>` with an empty doc-id input.
 * - **Context-menu (future C3 polish)**: a "Summarize" action on
 *   BrowseSurface documents dispatches `core.summarize-document` Operation
 *   with the selected `documentId`; the Operation's handler invokes
 *   SummarizeShape with `docId` pre-filled. The context-menu wiring is
 *   tracked separately — BrowseSurface today lacks a structured context-menu
 *   that would dispatch through the intent router; closing that gap is
 *   slice-level work outside Phase E scope (the §9.E A8 finding showed no
 *   existing chat-block precedents, and the same applies to the
 *   right-click-on-doc pattern).
 *
 * Pre-Phase-E: `streams.ts:summarizeDocumentStream` exported a typed wrapper
 * with zero in-repo callers (§9.E A5). C3 deletes that orphan and routes
 * SummarizeView through `consumeShapeStream` directly.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { friendlyStreamError } from '../utils/streamError.js';
import { ReasoningController } from '../controllers/ReasoningController.js';
import '../components/chat/ReasoningBlock.js';
import { composerStyles } from '../components/Composer.js';
import '../components/Composer.js';

import { dispatchShapeEventToHandlers, type RagMetaPayload } from '../../api/streams.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { streamViaHost } from '../plugin-api/pumpHostAiStream.js';
import type { CoreSummarizeHandlers } from '../../api/generated/shape-handlers/core-summarize.js';
// Tempdoc 565 §15.B — `Claim` relocated to the leaf `citationTypes` when StreamingTextBlock retired.
import type { Claim } from '../components/chat/citationTypes.js';
import type {
  CitationMatch,
  RetrievalCitation,
} from '../components/chat/CitationsPanel.js';
import { claimsToCitations } from '../components/chat/citationResolve.js';
import { registerViewFactory } from '../router/viewFactoryRegistry.js';
import {
  getSelection as getCurrentSelection,
  subscribeSelection,
} from '../state/selectionState.js';

import '../components/chat/MarkdownBlock.js';
import '../components/chat/CitationsPanel.js';

/**
 * Slice 491 F5 — endpoint + body-shape config per shape id. SummarizeView is a
 * single view that handles all three Summarize shapes (single-doc, batch,
 * hierarchical) by branching on its `shape-id` attribute. The body-shape
 * mismatch (docId: string vs docIds: string[]) is handled at submit time:
 * `core.summarize` extracts the first id from the multi-doc input and sends
 * `{docId}`; batch + hierarchical send `{docIds}`.
 */
const SHAPE_CONFIG: Record<
  string,
  { endpoint: string; title: string; description: string }
> = {
  'core.summarize': {
    endpoint: '/api/chat/summarize',
    title: 'Summarize',
    description: 'enter a document id to stream a local-LLM summary.',
  },
  'core.batch-summarize': {
    endpoint: '/api/chat/batch-summarize',
    title: 'Batch Summarize',
    description:
      'enter one or more document ids (comma- or newline-separated) to stream a batched local-LLM summary.',
  },
  'core.hierarchical-summarize': {
    endpoint: '/api/chat/hierarchical-summarize',
    title: 'Hierarchical Summarize',
    description:
      'enter one or more document ids (comma- or newline-separated) to stream a multi-pass synthesized summary.',
  },
};

export class SummarizeView extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    shapeId: { attribute: 'shape-id', type: String },
    host_: { attribute: false },
    docIdsDraft: { state: true },
    isStreaming: { state: true },
    streamingText: { state: true },
    claims: { state: true },
    sources: { state: true },
    citations: { state: true },
    ragMeta: { state: true },
    errorMessage: { state: true },
  };

  declare apiBase: string;
  declare shapeId: string;
  declare host_: PluginHostApi | undefined;
  declare docIdsDraft: string;
  declare isStreaming: boolean;
  declare streamingText: string;
  declare claims: Claim[];
  declare sources: RetrievalCitation[];
  declare citations: CitationMatch[];
  declare ragMeta: RagMetaPayload | null;
  declare errorMessage: string;

  readonly reasoning = new ReasoningController(() => this.requestUpdate());

  private abortController: AbortController | null = null;
  private selectionUnsubscribe: (() => void) | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.shapeId = 'core.summarize';
    this.docIdsDraft = '';
    this.isStreaming = false;
    this.streamingText = '';
    this.claims = [];
    this.sources = [];
    this.citations = [];
    this.ragMeta = null;
    this.errorMessage = '';
  }

  override connectedCallback(): void {
    super.connectedCallback();
    // Tempdoc 526 §14.5 T4 + §16 F10 — docs sourced from selectionState's
    // result-set kind. summarizeChatState was deleted in §16 F10 (no live
    // shapeId writer remained after askAi was removed); the shape comes
    // from compose()'s force-shape register on dispatch-routed flows and
    // from the URL-restored unified-chat adapter otherwise.
    const refreshFromSelection = (): void => {
      const cur = getCurrentSelection().items[0];
      if (cur && cur.kind === 'result-set') {
        this.docIdsDraft = cur.items.map((r) => r.id).join('\n');
      }
    };
    refreshFromSelection();
    this.selectionUnsubscribe = subscribeSelection(() => {
      if (!this.isStreaming) refreshFromSelection();
    });
  }

  /**
   * F5 — parse the multi-doc input into a clean string[] (trim each entry,
   * drop empties, split on comma or newline). Single-doc mode extracts the
   * first element.
   */
  private parseDocIds(): string[] {
    return this.docIdsDraft
      .split(/[,\n]/)
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
  }

  static styles = [composerStyles, css`
    :host {
      display: flex;
      flex-direction: column;
      width: 100%;
      height: 100%;
      padding: 1rem;
      gap: 0.75rem;
      box-sizing: border-box;
      color: var(--text-primary);
      font-family: system-ui, -apple-system, sans-serif;
    }
    .header {
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
      padding-bottom: 0.5rem;
      border-bottom: 1px solid var(--border-subtle);
    }
    .conversation {
      flex: 1;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }
    .error {
      padding: 0.5rem 0.75rem;
      background: var(--accent-danger-08);
      border: 1px solid var(--accent-danger-30);
      border-radius: 0.375rem;
      color: var(--text-danger);
      font-size: var(--font-size-sm);
    }
    .preamble {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      padding: 0.35rem 0.5rem;
      background: var(--surface-2);
      border-radius: 0.25rem;
      font-style: italic;
    }
  `];

  override render(): TemplateResult {
    const cfg = SHAPE_CONFIG[this.shapeId] ?? SHAPE_CONFIG['core.summarize']!;
    const placeholder =
      this.shapeId === 'core.summarize'
        ? 'docId'
        : 'docId, docId, docId  (or one per line)';
    const docIds = this.parseDocIds();
    return html`
      <div class="header">
        <strong>${cfg.title}</strong> — ${cfg.description}
      </div>
      <div class="conversation">
        ${this.ragMeta
          ? html`<div class="preamble">${this.ragMeta.retrieval_mode === 'FULLTEXT_FALLBACK'
              ? 'Full-document retrieval'
              : `${this.ragMeta.chunks_used ?? 0} passages · ${(this.ragMeta.retrieval_mode ?? '').toLowerCase().replace(/_/g, ' ')}`}</div>`
          : nothing}
        ${this.reasoning.isThinking
          ? html`<jf-reasoning-block .controller=${this.reasoning}></jf-reasoning-block>`
          : nothing}
        ${this.reasoning.reasoningBlocks.length > 0 && !this.reasoning.isThinking
          ? this.reasoning.reasoningBlocks.map(
              (block) => html`<jf-reasoning-block
                .text=${block.text} .durationMs=${block.durationMs}
              ></jf-reasoning-block>`,
            )
          : nothing}
        ${this.streamingText || this.isStreaming
          ? html`<jf-markdown-block
              format="plain"
              .text=${this.streamingText}
              .citations=${claimsToCitations(this.claims, this.sources)}
              ?is-streaming=${this.isStreaming}
            ></jf-markdown-block>`
          : nothing}
        ${this.sources.length > 0 || this.citations.length > 0
          ? html`<jf-citations-panel
              .sources=${this.sources}
              .citations=${this.citations}
              .retrievalMode=${this.ragMeta?.retrieval_mode ?? ''}
            ></jf-citations-panel>`
          : nothing}
        ${this.errorMessage
          ? html`<div class="error">${this.errorMessage}</div>`
          : nothing}
      </div>
      <jf-composer
        mono
        cancellable
        .value=${this.docIdsDraft}
        placeholder=${placeholder}
        rows=${this.shapeId === 'core.summarize' ? 1 : 3}
        submit-mode=${this.shapeId === 'core.summarize' ? 'enter' : 'ctrl-enter'}
        ?streaming=${this.isStreaming}
        ?submit-disabled=${docIds.length === 0}
        submit-label=${cfg.title}
        streaming-label=${`${cfg.title}…`}
        cancel-label=${this.streamingText ? 'Stop' : 'Cancel'}
        @composer-input=${(e: CustomEvent<{ value: string }>) =>
          (this.docIdsDraft = e.detail.value)}
        @composer-submit=${() => void this.send()}
        @composer-cancel=${() => this.abortController?.abort()}
      ></jf-composer>
    `;
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.reasoning.destroy();
    this.abortController?.abort();
    this.abortController = null;
    this.selectionUnsubscribe?.();
    this.selectionUnsubscribe = null;
  }

  /**
   * Public API: pre-fill the doc-id field. Used by future context-menu
   * integrations that mount this view with a pre-selected document.
   * Single-doc shape stores the id directly; batch + hierarchical store it
   * as a single-id list (the user can append more ids before submitting).
   */
  setDocId(docId: string): void {
    this.docIdsDraft = docId;
  }

  private async send(): Promise<void> {
    const docIds = this.parseDocIds();
    if (docIds.length === 0 || this.isStreaming) return;

    const cfg = SHAPE_CONFIG[this.shapeId] ?? SHAPE_CONFIG['core.summarize']!;

    this.abortController = new AbortController();
    this.isStreaming = true;
    this.streamingText = '';
    this.reasoning.reset();
    this.claims = [];
    this.sources = [];
    this.citations = [];
    this.ragMeta = null;
    this.errorMessage = '';

    // Tempdoc 521 §16.1 phase A.3 — host.ai consumer when wired; fallback URL otherwise.
    const fallbackUrl = (this.apiBase || '') + cfg.endpoint;
    // Body shape differs by shape id: single-doc takes {docId}; batch +
    // hierarchical take {docIds: string[]}. Single-doc takes the first
    // entry from the parsed list (graceful when the user types one id).
    const body: Record<string, unknown> =
      this.shapeId === 'core.summarize'
        ? { docId: docIds[0] }
        : { docIds };

    const handlers: CoreSummarizeHandlers = {
      onReasoningChunk: (payload: unknown) => { this.reasoning.handleReasoningChunk(payload); },
      onChunk: (payload: unknown) => {
        this.reasoning.endThinking();
        const p = payload as Record<string, unknown> | string | null;
        const text =
          typeof p === 'string'
            ? p
            : typeof p?.text === 'string'
              ? (p.text as string)
              : '';
        if (text) this.streamingText = this.streamingText + text;
      },
      onDone: () => {
        this.reasoning.finalize();
        this.isStreaming = false;
      },
      onError: (payload: unknown) => {
        const p = payload as Record<string, unknown> | null;
        this.errorMessage =
          (p?.error as string) ??
          (p?.message as string) ??
          'shape stream error';
        this.isStreaming = false;
      },
      onRagCitations: (payload: unknown) => {
        const p = payload as { citations?: RetrievalCitation[] } | null;
        if (p && Array.isArray(p.citations)) this.sources = p.citations;
      },
      onRagCitationDelta: (payload: unknown) => {
        const p = payload as {
          sentenceIndex?: number;
          sentenceText?: string;
          citations?: Array<{ parentDocId: string; chunkIndex: number; score: number }>;
        } | null;
        if (p && Array.isArray(p.citations) && typeof p.sentenceText === 'string') {
          const bestScore = Math.max(...p.citations.map((c) => c.score), 0);
          if (!this.claims.some((cl) => cl.sentenceIndex === (p.sentenceIndex ?? 0))) {
            this.claims = [
              ...this.claims,
              {
                sentenceIndex: p.sentenceIndex ?? 0,
                sentenceText: p.sentenceText,
                score: bestScore,
                sourceRefs: p.citations.map((c) => c.chunkIndex),
              },
            ];
          }
        }
      },
    };

    try {
      await streamViaHost({
        host_: this.host_,
        shapeId: this.shapeId,
        fallbackUrl,
        body,
        onEvent: (event, payload) => {
          dispatchShapeEventToHandlers(
            handlers as unknown as Record<string, unknown>,
            event,
            payload,
          );
        },
        signal: this.abortController.signal,
      });
    } catch (err) {
      if (!(err instanceof Error && err.name === 'AbortError')) {
        this.errorMessage = friendlyStreamError(err);
      }
    } finally {
      this.isStreaming = false;
      this.abortController = null;
    }
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-summarize-view')
) {
  customElements.define('jf-summarize-view', SummarizeView);
}

// Slice 491 §9.D Phase E C3 — register the view factory at module-load time
// so <jf-chat-shape-mount shape-id="core.summarize"> resolves correctly.
registerViewFactory('core.summarize', 'jf-summarize-view');
// F5 — same view handles batch + hierarchical via the shape-id branching in
// send(); the SHAPE_CONFIG record + parseDocIds() handle the body-shape
// mismatch (single-doc vs docIds[]). Lifts the C5 EXEMPT entries for these
// two shapes.
registerViewFactory('core.batch-summarize', 'jf-summarize-view');
registerViewFactory('core.hierarchical-summarize', 'jf-summarize-view');
