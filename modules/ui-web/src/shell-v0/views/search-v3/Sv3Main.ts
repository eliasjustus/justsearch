// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-main — the Search v3 window's content surface (tempdoc 822 slice 1; wired in Phase A1).
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * The ONE scroller in the window. The host itself is clipped; only `.scroller` inside it scrolls,
 * so the window's frame (topbar, sidebar, composer) can never be scrolled out of reach.
 *
 * The region is EMPTY in the composer's hero state (slice 3): nothing has been asked yet, so the
 * hero composer is the region's only subject. Once docked it holds the active session's TRANSCRIPT
 * (Phase F1) — and, when that session has no turns, the window's read of the shared search store
 * (`sv3-results.ts`, now the secondary axis). The region owns no store subscription and no client of
 * its own, so it cannot render anything the window did not hand it.
 *
 * The count line is computed HERE, off the same array the rows are mapped from, because that is the
 * only construction in which the number cannot come to describe a different set than the one on
 * screen. It is the shipped `matchCountLabel`, not a second count authority.
 *
 * Side-effect registers <jf-sv3-main>.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { matchCountLabel } from '../../components/searchResults/matchCountLabel.js';
import { sv3Shared } from './sv3-shared-styles.js';
import './Sv3Empty.js';
// The product's ONE tool-call primitive (`governance/run-renderers.v1.json` — this file is a
// registered mount site). A window-local tool card would be the second render path that register
// exists to forbid, so the donor's own tool row is deliberately NOT ported.
import '../../components/chat/ToolCallCard.js';
// The product's ONE markdown renderer and ONE citations panel (tempdoc 822 Phase F4). A window-local
// markdown pass would be a second parse of the same text with a second sanitiser behind it, and a
// window-local source list a second evidence presentation — both are what these authorities exist to
// prevent. The window supplies only the CLOTHES: the donor's `.chat-markdown` values, re-expressed on
// sv3 tokens through the custom properties the two components read.
import '../../components/chat/MarkdownBlock.js';
import '../../components/chat/CitationsPanel.js';
import {
  COMPOSER_STATE_DEFAULT,
  MAIN_EMPTY,
  MAIN_UNREACHABLE,
  RUN_DISPATCHING,
  TURN_EMPTY_ANSWER,
  TURN_FAILED,
  TURN_HALTED,
} from './fixtures.js';
import type { Sv3ComposerState } from './fixtures.js';
import { SV3_RESULTS_IDLE, type Sv3ResultsView } from './sv3-results.js';
import { sv3TurnSourceCount, type Sv3Turn } from './sv3-sessions.js';
import { sv3RunReceiptLabel } from './sv3-run.js';
import type { Sv3RunFeedItem, Sv3RunPrompt, Sv3RunView } from './sv3-run.js';

/**
 * Raised when the reader resolves a typed prompt with its OWN dedicated control (tempdoc 822 Phase
 * F2, donor pattern (f)). The surface announces the decision; the window dispatches it through the
 * ONE `dispatchRunControl` seam, because only the window may reach the run.
 */
export const SV3_RUN_DECISION = 'sv3-run-decision';

export type Sv3RunDecision =
  | { readonly kind: 'budget'; readonly decision: 'finalize' | 'stop' }
  | { readonly kind: 'context'; readonly decision: 'continue' | 'summarize' | 'stop' };

/** Enough bars to fill the region's first screen without claiming a result count it cannot know. */
const SKELETON_ROWS = 6;

/**
 * How close to the end counts as "at the end" for the follow re-arm below. The donor's own re-arm is
 * a boolean `isAtEnd` its virtual list reports (`apps/web/src/components/ChatView.tsx:3904-3925`:
 * at-end → `following-end`, otherwise → `free-scrolling`); with a plain scroller the equivalent test
 * is a threshold, kept small so only a reader who is genuinely at the bottom stays armed, and
 * non-zero so sub-pixel scroll heights cannot disarm the follow on their own.
 */
const FOLLOW_END_SLACK_PX = 24;

export class Sv3Main extends JfElement {
  static styles = [
    sv3Shared,
    css`
      :host {
        display: flex;
        flex-direction: column;
        flex: 1 1 auto;
        min-height: 0;
        overflow: hidden;
        background: var(--background);
        color: var(--foreground);
        font-family: var(--font-sans);
      }
      .scroller {
        flex: 1 1 auto;
        min-height: 0;
        padding: var(--floating-content-inset);
        display: flex;
        flex-direction: column;
        gap: var(--space-1);
      }
      h2 {
        margin: 0 0 var(--space-2);
        font-size: var(--font-size-sv3-sm);
        font-weight: 600;
      }
      .row {
        display: flex;
        align-items: baseline;
        gap: var(--space-2);
        padding: var(--space-2) var(--space-3);
        border-radius: var(--radius-md);
        font-size: var(--font-size-sv3-sm);
        /* A long list stays cheap: the browser skips rendering work for rows outside the
           viewport, and the intrinsic size keeps the scrollbar honest while they are skipped. */
        content-visibility: auto;
        contain-intrinsic-size: auto 36px;
      }
      .row-title {
        font-weight: 500;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .row-path {
        margin-left: auto;
        flex-shrink: 0;
        font-size: var(--font-size-sv3-xs);
        color: var(--secondary-label);
        font-family: var(--font-mono);
      }

      /* The pending state is the row rhythm with the content withheld — same height, same radius,
         same gap — so the list does not jump when the answer replaces it. The sweep is the shared
         sheet's duty-cycled keyframe (transform-only, long hold), not a continuous shimmer. */
      .skeleton-row {
        position: relative;
        overflow: hidden;
        height: var(--space-9);
        border-radius: var(--radius-md);
        background: var(--muted);
      }
      .skeleton-sheen {
        position: absolute;
        inset: 0;
        background: linear-gradient(
          90deg,
          transparent,
          color-mix(in srgb, var(--foreground) 8%, transparent),
          transparent
        );
      }

      /* ── The transcript (donor 'MessagesTimeline') ─────────────────────────
         One measure for the whole conversation, centred, matching the composer's own box: the donor
         gives its timeline root the same 'max-w-3xl' its composer uses
         ('chat/MessagesTimeline.tsx:553'), so a turn and the field that produced it share an edge. */
      .transcript {
        width: 100%;
        max-inline-size: 48rem;
        min-width: 0;
        margin-inline: auto;
      }
      /* The donor's turn rhythm: 16px under a message row ('chat/MessagesTimeline.tsx:936-939' —
         'pb-4', with 'pb-2' reserved for the commentary rows this window has none of). Bottom
         padding rather than a gap, so the LAST turn keeps its breathing room above the composer. */
      .turn {
        padding-bottom: var(--space-4);
      }
      /* Donor 'flex flex-col items-end gap-1' ('chat/MessagesTimeline.tsx:984'). */
      .ask {
        display: flex;
        flex-direction: column;
        align-items: flex-end;
        gap: var(--space-1);
      }
      /* Donor 'max-w-[80%] rounded-2xl bg-message p-3 text-message-foreground'
         ('chat/MessagesTimeline.tsx:985'). The fill is the ONE surface in the transcript. */
      .ask-bubble {
        max-inline-size: 80%;
        padding: var(--space-3);
        border-radius: var(--radius-2xl);
        background: var(--message-surface);
        color: var(--message-foreground);
        font-size: var(--font-size-sv3-sm);
        line-height: 1.625;
        white-space: pre-wrap;
        overflow-wrap: anywhere;
      }
      /* The response has NO bubble and NO alignment — plain content on the panel, inset by the
         donor's 'px-1 py-0.5' ('chat/MessagesTimeline.tsx:1117'). Phase F4 fills it with the shared
         markdown renderer, so the block-level rhythm is the renderer's and this box no longer
         preserves source whitespace (a 'pre-wrap' around block children would re-introduce the
         template's own newlines as vertical space). */
      .answer {
        position: relative;
        min-width: 0;
        padding: var(--space-0-5) var(--space-1);
        font-size: var(--font-size-sv3-sm);
        line-height: 1.625;
        overflow-wrap: anywhere;
      }

      /* ── The donor's '.chat-markdown' clothes on the shared renderer ────────
         The renderer is the product's (components/chat/MarkdownBlock.ts) and is NOT forked; what
         it exposes is the set of custom properties it reads, so the donor's values arrive as a
         re-mapping of those names onto sv3 tokens. Every mapping below cites the donor rule it
         carries (t3code@b73232b apps/web/src/index.css).

         GAP, recorded rather than worked around: the renderer hard-codes its block GEOMETRY (list
         indent, paragraph/pre margins, pre radius + padding, blockquote rule), its mono face
         ('monospace', not our --font-mono), and it declares nothing at all for headings or tables —
         so donor :1824 (0.65rem block rhythm), :1839-1855 (heading scale), :1995-1997 (pre chrome)
         and :2101-2140 (the table separators + truncate/expand rule) cannot be applied from here.
         Closing it needs the shared component to expose a part or geometry tokens, which is a
         change to an authority three surfaces render through — its own slice, not this one's. */
      .sv3-markdown,
      .sv3-citations {
        /* Donor :1836 / :1972 — headings and code sit at full foreground. */
        --text-primary: var(--foreground);
        /* Donor :1858 / :1935 — the h6 + blockquote recession. */
        --text-secondary: var(--muted-foreground);
        /* Donor :1908 — a link is the info hue, not the brand accent. */
        --text-tint: var(--info-foreground);
        /* Donor :1970 / :1996 — inline code and code blocks share the muted fill. */
        --surface-tertiary: var(--muted);
        --surface-2: var(--muted);
        --surface-3: var(--secondary);
        /* Donor :1933 / :1994 — the blockquote rule and the code block's edge. */
        --border-subtle: var(--border);
        --accent-tint: var(--primary);
        --accent-on-tint: var(--primary-foreground);
        --accent-warning: var(--warning-foreground);
        --text-warning: var(--warning-foreground);
        --text-command: var(--foreground);
        --font-size-sm: var(--font-size-sv3-sm);
        /* Donor :1973 / :2004 / :2107 — inline code, block code and table text all step down. */
        --font-size-xs: var(--font-size-sv3-xs);
        /* The window's motion budget reaches the shared components too, so a transition inside
           them cannot outlast one authored here. */
        --duration-fast: var(--duration-sv3-micro);
        --duration-normal: var(--duration-sv3-layout);
        --ease-standard: var(--ease-sv3-enter);
      }
      .sv3-markdown {
        display: block;
        min-width: 0;
        /* Donor :1806-1807 — an unbroken token in chat prose must not widen the measure. */
        overflow-wrap: anywhere;
        word-break: break-word;
      }
      /* Donor :1824 — the block rhythm the renderer's own margins cannot express, applied where it
         still can be: between the answer and the evidence beneath it. */
      .sv3-citations {
        display: block;
        margin-top: var(--space-2-5);
      }
      .answer-empty {
        color: var(--secondary-label);
      }
      /* The turn's terminal, said in words. Halting is the reader's own act and gets no colour — the
         3-colour budget is for act-now / in-motion / broken, and a stop is none of those. */
      .turn-note {
        margin-top: var(--space-1);
        padding-inline: var(--space-1);
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
      }
      .turn-note[data-broken='true'] {
        color: var(--error-foreground);
      }

      /* ── The delegated run (Phase F2) ──────────────────────────────────────
         The live feed sits where the answer would be, at the same measure and the same rhythm: a run
         and an answer are two ways the same turn can be answered, so they must not read as two
         different regions of the window. */
      .run {
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
        min-width: 0;
      }
      .run-feed {
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
        min-width: 0;
      }
      .run-echo {
        margin: 0;
        padding-inline: var(--space-1);
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-sm);
      }
      .run-note {
        margin: 0;
        padding-inline: var(--space-1);
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
        line-height: 1.5;
      }
      .run-note-label {
        color: var(--foreground);
        font-weight: 500;
      }
      .run-note[data-label='Error'] .run-note-label {
        color: var(--error-foreground);
      }
      /* A held decision is act-now, which is the one place this window spends --success on a surface.
         It is a SIBLING of the feed, never inside it, so no amount of feed content can bury it. */
      .run-prompt {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: var(--space-2);
        padding: var(--space-3);
        border: 1px solid color-mix(in srgb, var(--success) 40%, transparent);
        border-radius: var(--radius-lg);
        background: color-mix(in srgb, var(--success) 8%, transparent);
      }
      .run-prompt-text {
        flex: 1 1 100%;
        margin: 0;
        font-size: var(--font-size-sv3-sm);
      }
      .run-prompt button {
        padding: var(--space-1) var(--space-3);
        border: 1px solid var(--border);
        border-radius: var(--control-radius);
        background: var(--background);
        color: var(--foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-xs);
        cursor: pointer;
      }
      .run-prompt button:hover {
        background: var(--muted);
      }
      .run-prompt button:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }

      /* The store's own failure text, kept at diagnostic altitude: the state is said in words above
         it, and this is the detail that makes the words checkable. */
      .failure-detail {
        color: var(--secondary-label);
        font-family: var(--font-mono);
        font-size: var(--font-size-sv3-xs);
      }
    `,
  ];

  static properties = {
    state: { type: String, reflect: true },
    view: { attribute: false },
    turns: { attribute: false },
    run: { attribute: false },
  };

  declare state: Sv3ComposerState;
  declare view: Sv3ResultsView;
  /** The ACTIVE session's turns, oldest first. Handed down; the region holds no session list. */
  declare turns: readonly Sv3Turn[];
  /**
   * The ONE live agent run, or null. Rendered against `run.turnId` and no other turn, so the feed
   * cannot appear under a turn that did not open it (tempdoc 822 Phase F2).
   */
  declare run: Sv3RunView | null;

  /**
   * The donor's two scroll modes as one flag: armed = `following-end` (the reader is at the end, so
   * new text keeps the end in view), disarmed = `free-scrolling` (the reader scrolled up and owns
   * the viewport until they return to the end, which RE-ARMS it).
   */
  private followEnd = true;

  constructor() {
    super();
    this.state = COMPOSER_STATE_DEFAULT;
    this.view = SV3_RESULTS_IDLE;
    this.turns = [];
    this.run = null;
  }

  private get scroller(): HTMLElement | null {
    return this.shadowRoot?.querySelector('.scroller') ?? null;
  }

  /** Re-arm/disarm on the reader's own scrolling — never on a scroll this element caused itself. */
  private readonly onScroll = (): void => {
    const el = this.scroller;
    if (el === null) return;
    this.followEnd = el.scrollHeight - el.scrollTop - el.clientHeight <= FOLLOW_END_SLACK_PX;
  };

  protected override updated(): void {
    const el = this.scroller;
    if (el === null || !this.followEnd) return;
    // Assigned unconditionally while armed, which is what makes a streaming answer stay in view:
    // each delta grows the content and the end is followed in the same frame it grew.
    el.scrollTop = el.scrollHeight;
  }

  render(): TemplateResult {
    const view = this.view ?? SV3_RESULTS_IDLE;
    const turns = this.turns ?? [];
    // The conversation owns the region whenever the claimed session has one. The search projection
    // below is the SECONDARY axis now (822 §4b course correction) and speaks only for a session that
    // has asked nothing — it is reached from the palette, never from a plain submit.
    if (turns.length > 0) return this.transcript(turns);
    // Nothing but the hero composer belongs in the region until the window has docked: an untouched
    // window's emptiness is the composer's to speak for, not a state to announce.
    if (this.state !== 'docked' || view.status === 'idle') {
      return html`<div class="scroller sv3-scroller" data-testid="sv3-main-scroller"></div>`;
    }
    if (view.status === 'loading') return this.pending();
    if (view.status === 'unreachable') return this.unreachable(view.failure);
    if (view.status === 'empty') {
      return html`
        <jf-sv3-empty
          roomy
          data-testid="sv3-main-empty"
          glyph="&#9634;"
          heading=${MAIN_EMPTY.title}
          description=${MAIN_EMPTY.description}
        ></jf-sv3-empty>
      `;
    }
    const rows = view.rows;
    return html`
      <div class="scroller sv3-scroller" data-testid="sv3-main-scroller">
        <h2 data-testid="sv3-main-count">
          ${matchCountLabel(view.matched, rows.length, false, view.ranked, view.truncated)}
        </h2>
        ${rows.map(
          (row) => html`
            <div class="row" data-testid="sv3-main-row">
              <span class="row-title">${row.title}</span>
              <span class="row-path">${row.path}</span>
            </div>
          `,
        )}
      </div>
    `;
  }

  /**
   * One turn = the question as a right-aligned bubble, the response as plain content beneath it.
   * The asymmetry is the donor's and it is load-bearing: only the user's turn carries a fill, so the
   * transcript reads as answers punctuated by asks rather than as two columns of chat.
   */
  private transcript(turns: readonly Sv3Turn[]): TemplateResult {
    return html`
      <div
        class="scroller sv3-scroller"
        data-testid="sv3-main-scroller"
        @scroll=${this.onScroll}
        aria-busy=${turns.at(-1)?.status === 'streaming' ? 'true' : 'false'}
      >
        <div class="transcript" data-testid="sv3-transcript">
          ${turns.map((turn) => this.turn(turn))}
        </div>
      </div>
    `;
  }

  private turn(turn: Sv3Turn): TemplateResult {
    const streaming = turn.status === 'streaming';
    const empty = turn.answer === '';
    // The run this turn OPENED, if it is the one live — matched by id, never by "the last turn". An
    // ENDED run renders nothing here: its live feed was attention, and the receipt below is what
    // survives it (the same record/attention split search-v2's L8 makes).
    const live = this.run;
    const run =
      turn.kind === 'agent' && live?.turnId === turn.id && live.phase !== 'ended' ? live : null;
    return html`
      <div class="turn" data-testid="sv3-turn" data-kind=${turn.kind} data-status=${turn.status}>
        <div class="ask">
          <div class="ask-bubble" data-testid="sv3-turn-question">${turn.question}</div>
        </div>
        ${turn.kind === 'agent'
          ? run === null
            ? nothing
            : this.runBody(run)
          : html`
              <div class="answer" data-testid="sv3-turn-answer">
                ${empty && !streaming
                  ? html`<span class="answer-empty" data-testid="sv3-turn-answer-empty"
                      >${TURN_EMPTY_ANSWER}</span
                    >`
                  : html`<jf-markdown-block
                      class="sv3-markdown"
                      data-testid="sv3-turn-markdown"
                      .text=${turn.answer}
                      ?is-streaming=${streaming}
                      .citations=${[...(turn.evidence?.marks ?? [])]}
                    ></jf-markdown-block>`}
              </div>
              ${this.citations(turn)}
            `}
        ${this.turnNote(turn)}
      </div>
    `;
  }

  /**
   * Whether the shared panel has anything to render for this turn — the ONE test, asked by the
   * renderer below AND by {@link turnNote}, so the note can never repeat a count the panel is
   * already showing (`CitationsPanel.render` returns nothing when both sets are empty).
   */
  private panelSpeaks(turn: Sv3Turn): boolean {
    if (turn.kind !== 'ask' || turn.status === 'streaming' || turn.evidence === null) return false;
    return turn.evidence.sources.length > 0 || turn.evidence.matches.length > 0;
  }

  /**
   * The answer's evidence, in the product's ONE citations panel — mounted per turn and collapsed by
   * its own disclosure, exactly as search-v2 mounts it on a landed answer
   * (`views/search-v2/SearchV2View.ts:2220-2229`). Its `citation-select` is deliberately NOT handled
   * here: the Shell is the one listener for it (`chrome/Shell.ts:543-554`) and already pushes the
   * cited line range onto the shared inspector selection, so a handler of this window's own would be
   * a second answer to a question that has one.
   */
  private citations(turn: Sv3Turn): TemplateResult | typeof nothing {
    if (!this.panelSpeaks(turn) || turn.evidence === null) return nothing;
    return html`<jf-citations-panel
      class="sv3-citations"
      data-testid="sv3-turn-citations"
      .citations=${[...turn.evidence.matches]}
      .sources=${[...turn.evidence.sources]}
      .retrievalMode=${turn.evidence.retrievalMode}
    ></jf-citations-panel>`;
  }

  /**
   * The live run: its feed, then the decisions it is parked on. Prompts come LAST and outside the
   * feed's own flow, because a held decision must not be something the reader can scroll past — the
   * same "incompressible occupant" rule search-v2 gives its run controls
   * (`views/search-v2/SearchV2View.ts:2550-2554`).
   *
   * `dispatching` is the optimistic window: the reader's task left and the server has not answered.
   * It is a distinct STATE, not an empty feed, so the window never has to imply progress it cannot
   * see (the handoff predicate in `sv3-run.ts` is what leaves it).
   */
  private runBody(run: Sv3RunView): TemplateResult {
    return html`
      <div class="run" data-testid="sv3-run" data-phase=${run.phase}>
        ${run.phase === 'dispatching'
          ? html`<p class="run-echo" data-testid="sv3-run-echo" role="status">${RUN_DISPATCHING}</p>`
          : html`
              <div class="run-feed" data-testid="sv3-run-feed">
                ${run.feed.items.map((item) => this.runItem(item))}
              </div>
            `}
        ${run.prompts.map((prompt) => this.runPrompt(prompt))}
      </div>
    `;
  }

  private runItem(item: Sv3RunFeedItem): TemplateResult {
    if (item.kind === 'text') {
      // The agent's prose is the same kind of text as an answer and gets the same renderer: a feed
      // that showed raw asterisks beside a settled turn that did not would be two markdown policies
      // in one transcript. Never streaming — a feed entry arrives whole.
      return html`<div class="answer" data-testid="sv3-run-text">
        <jf-markdown-block class="sv3-markdown" .text=${item.text}></jf-markdown-block>
      </div>`;
    }
    if (item.kind === 'tool') {
      return html`<jf-tool-call-card
        data-testid="sv3-run-tool"
        .toolCall=${item.call}
        .stepPresentation=${null}
      ></jf-tool-call-card>`;
    }
    return html`<p class="run-note" data-testid="sv3-run-note" data-label=${item.label}>
      <span class="run-note-label">${item.label}</span> ${item.text}
    </p>`;
  }

  /**
   * A typed prompt with its OWN controls (donor pattern (f)). The APPROVAL arm deliberately carries
   * no Approve/Deny of its own: the product has exactly one approve/deny ceremony
   * (`operations/authorizationBroker.ts:14-21`, which those inline per-card buttons were retired
   * INTO), so this block SAYS what is held and lets the one ceremony ask. The two gates are the
   * window's to resolve, and each button is a dedicated typed command — never a sentence typed into
   * the composer, which refuses to send while any prompt is pending.
   */
  private runPrompt(prompt: Sv3RunPrompt): TemplateResult {
    if (prompt.kind === 'budget') {
      return html`
        <div class="run-prompt" role="group" aria-label="Budget decision" data-testid="sv3-run-prompt" data-kind="budget">
          <p class="run-prompt-text">
            The run needs ${prompt.tokensNeeded.toLocaleString()} more tokens;
            ${prompt.tokensRemaining.toLocaleString()} remain.
          </p>
          <button type="button" data-testid="sv3-run-budget-finalize" @click=${() =>
            this.decide({ kind: 'budget', decision: 'finalize' })}>
            Finish with what it has
          </button>
          <button type="button" data-testid="sv3-run-budget-stop" @click=${() =>
            this.decide({ kind: 'budget', decision: 'stop' })}>
            Stop the run
          </button>
        </div>
      `;
    }
    if (prompt.kind === 'context') {
      return html`
        <div class="run-prompt" role="group" aria-label="Context decision" data-testid="sv3-run-prompt" data-kind="context">
          <p class="run-prompt-text">
            The prompt is ${prompt.promptTokens.toLocaleString()} of
            ${prompt.contextWindow.toLocaleString()} tokens.
          </p>
          <button type="button" data-testid="sv3-run-context-continue" @click=${() =>
            this.decide({ kind: 'context', decision: 'continue' })}>
            Continue anyway
          </button>
          <button type="button" data-testid="sv3-run-context-summarize" @click=${() =>
            this.decide({ kind: 'context', decision: 'summarize' })}>
            Compact older turns
          </button>
          <button type="button" data-testid="sv3-run-context-stop" @click=${() =>
            this.decide({ kind: 'context', decision: 'stop' })}>
            Stop the run
          </button>
        </div>
      `;
    }
    return html`
      <div
        class="run-prompt"
        role="group"
        aria-label="Tool approval"
        data-testid="sv3-run-prompt"
        data-kind="approval"
      >
        <p class="run-prompt-text">
          ${prompt.toolName} is waiting for your approval (${prompt.risk.toLowerCase()} risk).
        </p>
      </div>
    `;
  }

  private decide(decision: Sv3RunDecision): void {
    this.dispatchEvent(
      new CustomEvent<Sv3RunDecision>(SV3_RUN_DECISION, {
        detail: decision,
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * What became of the turn, in words. A streaming turn says nothing — the text arriving IS the
   * state, and a "generating…" label beside moving text would be a second, redundant claim.
   */
  private turnNote(turn: Sv3Turn): TemplateResult | typeof nothing {
    if (turn.status === 'streaming') return nothing;
    const broken = turn.status === 'failed' || turn.status === 'refused';
    if (turn.kind === 'agent') {
      return html`<p
        class="turn-note"
        data-testid="sv3-run-receipt"
        data-outcome=${turn.status}
        data-broken=${String(broken)}
      >
        ${sv3RunReceiptLabel(turn.toolCalls, turn.status)}
      </p>`;
    }
    const sources = sv3TurnSourceCount(turn);
    const note =
      turn.status === 'halted'
        ? TURN_HALTED
        : turn.status === 'refused'
          ? turn.detail
          : turn.status === 'failed'
            ? `${TURN_FAILED} ${turn.detail}`.trim()
            : // The completed turn's evidence line, and only when the panel is not already showing
              // it: `null` means the backend never said, which is not "0 sources", and a panel with
              // cards in it heads its own count — two of them would be one claim too many.
              sources === null || this.panelSpeaks(turn)
              ? ''
              : `${sources} ${sources === 1 ? 'source' : 'sources'}`;
    if (note === '') return nothing;
    return html`<p class="turn-note" data-testid="sv3-turn-note" data-broken=${String(broken)}>
      ${note}
    </p>`;
  }

  private pending(): TemplateResult {
    return html`
      <div
        class="scroller sv3-scroller"
        data-testid="sv3-main-scroller"
        aria-busy="true"
        aria-label="Searching"
      >
        ${Array.from(
          { length: SKELETON_ROWS },
          () => html`
            <div class="skeleton-row" data-testid="sv3-main-skeleton" aria-hidden="true">
              <span class="skeleton-sheen sv3-anim-skeleton"></span>
            </div>
          `,
        )}
      </div>
    `;
  }

  /**
   * The request never reached the backend, so NOTHING is known about the corpus — which is why this
   * is its own state rather than the zero-results one wearing different words.
   */
  private unreachable(failure: string): TemplateResult {
    return html`
      <jf-sv3-empty
        roomy
        data-testid="sv3-main-unreachable"
        glyph="&#9634;"
        heading=${MAIN_UNREACHABLE.title}
        description=${MAIN_UNREACHABLE.description}
      >
        ${failure === ''
          ? nothing
          : html`<span class="failure-detail" data-testid="sv3-main-failure-detail"
              >${failure}</span
            >`}
      </jf-sv3-empty>
    `;
  }
}

customElements.define('jf-sv3-main', Sv3Main);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-main': Sv3Main;
  }
}
