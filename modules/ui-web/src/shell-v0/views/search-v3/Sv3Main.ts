// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-main — the Search v3 window's content surface (tempdoc 822 slice 1; wired in Phase A1).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
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
// exists to forbid, so the reference implementation's own tool row is deliberately NOT ported.
import '../../components/chat/ToolCallCard.js';
// The product's ONE markdown renderer and ONE citations panel (tempdoc 822 Phase F4). A window-local
// markdown pass would be a second parse of the same text with a second sanitiser behind it, and a
// window-local source list a second evidence presentation — both are what these authorities exist to
// prevent. The window supplies only the CLOTHES: the design spec's chat-markdown values, re-expressed
// on sv3 tokens through the custom properties the two components read.
import '../../components/chat/MarkdownBlock.js';
import '../../components/chat/CitationsPanel.js';
// The product's ONE citation hover preview and ONE reasoning block (tempdoc 822 Phase F7; inventory
// C3 and C9). Both are shared components the shipped window mounts for the same events this surface
// now receives; a window-local preview card or thinking disclosure would be a second presentation of
// evidence and of the model's own output.
import '../../components/chat/CitationHoverCard.js';
import '../../components/chat/ReasoningBlock.js';
import type { CitationHoverCard, CitationHoverData } from '../../components/chat/CitationHoverCard.js';
import type { CitationSelectDetail } from '../../components/chat/citationTypes.js';
import type { ReasoningController } from '../../controllers/ReasoningController.js';
// The shared clipboard util (slice 486 G35) — permission-denied and API-absent already handled, so a
// per-turn copy needs no error path of its own.
import { copyToClipboard } from '../../utils/clipboardCopy.js';
import { icon } from '../../components/Icon.js';
// The ONE readiness vocabulary. The locked transcript's heading and its remedy are read from it, not
// worded here (tempdoc 629 #3 — the locked-chat gate speaks the same words as every other cause).
import { reasonFor } from '../../state/readinessNotice.js';
import { RAISE_BUDGET_STEP_TOKENS } from '../unifiedChatRequest.js';
import {
  COMPOSER_STATE_DEFAULT,
  HISTORY_LOCKED_HELP,
  HISTORY_LOCKED_REFUSED,
  MAIN_EMPTY,
  MAIN_UNREACHABLE,
  RECORD_UNREACHABLE,
  REWRITE_NOTE_LABEL,
  RUN_DISPATCHING,
  TURN_COPY_DONE,
  TURN_COPY_FEEDBACK_MS,
  TURN_COPY_LABEL,
  TURN_EMPTY_ANSWER,
  TURN_FAILED,
  TURN_HALTED,
} from './fixtures.js';
import type { Sv3ComposerState } from './fixtures.js';
import { SV3_RESULTS_IDLE, type Sv3ResultsView } from './sv3-results.js';
import { sv3TurnSourceCount, type Sv3Turn } from './sv3-sessions.js';
import {
  projectSv3AnswerFrame,
  sv3SourcesTrigger,
  sv3SourcesTriggerCount,
  sv3SourcesTriggerLabel,
  SV3_REMEDY,
  type Sv3RemedyDetail,
} from './sv3-honesty.js';
import { sv3RunReceiptLabel } from './sv3-run.js';
import type { Sv3RunFeedItem, Sv3RunPrompt, Sv3RunView } from './sv3-run.js';

/**
 * Raised when the reader resolves a typed prompt with its OWN dedicated control (tempdoc 822 Phase
 * F2). The surface announces the decision; the window dispatches it through the
 * ONE `dispatchRunControl` seam, because only the window may reach the run.
 */
export const SV3_RUN_DECISION = 'sv3-run-decision';

/**
 * Raised when a citation is followed (tempdoc 822 Phase F8) — the window's own event, carrying only
 * what the shared `citation-select` already knew. See {@link Sv3Main.onCitationSelect} for why the
 * shared event does not leave this surface.
 */
export const SV3_CITATION_OPEN = 'sv3-citation-open';

export interface Sv3CitationOpen {
  readonly docPath: string;
  /** The cited passage's 0-based inclusive line span, or null when the citation carried none. */
  readonly range: { readonly startLine: number; readonly endLine: number } | null;
}

export type Sv3RunDecision =
  | { readonly kind: 'budget'; readonly decision: 'raise' | 'finalize' | 'stop' }
  | { readonly kind: 'context'; readonly decision: 'continue' | 'summarize' | 'stop' };

/** Enough bars to fill the region's first screen without claiming a result count it cannot know. */
const SKELETON_ROWS = 6;

/**
 * How close to the end counts as "at the end" for the follow re-arm below. The design spec's own
 * re-arm is a boolean `isAtEnd` reported by a virtual list (at-end → `following-end`, otherwise →
 * `free-scrolling`); with a plain scroller the equivalent test
 * is a threshold, kept small so only a reader who is genuinely at the bottom stays armed, and
 * non-zero so sub-pixel scroll heights cannot disarm the follow on their own.
 */
const FOLLOW_END_SLACK_PX = 24;

/** The reference `size-3.5` on the inline disclosure's chevron. */
const TAIL_CHEVRON_SIZE = 14;

/** The reference `size-3` on the copy glyph — the tail's own 12px. */
const TAIL_GLYPH_SIZE = 12;

/** The disclosure's target, per turn — the ids live in this element's shadow root and nowhere else. */
const sourcesBodyId = (turnId: string): string => `sv3-sources-${turnId}`;

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

      /* ── The transcript ────────────────────────────────────────────────────
         One measure for the whole conversation, centred, matching the composer's own box: the
         design spec gives the timeline root the same 'max-w-3xl' its composer uses, so a turn and
         the field that produced it share an edge. */
      .transcript {
        width: 100%;
        max-inline-size: 48rem;
        min-width: 0;
        margin-inline: auto;
      }
      /* The design spec's turn rhythm: 16px under a message row ('pb-4', with 'pb-2' reserved for
         the commentary rows this window has none of). Bottom
         padding rather than a gap, so the LAST turn keeps its breathing room above the composer. */
      .turn {
        padding-bottom: var(--space-4);
      }
      /* Per the design spec: 'flex flex-col items-end gap-1'. */
      .ask {
        display: flex;
        flex-direction: column;
        align-items: flex-end;
        gap: var(--space-1);
      }
      /* Per the design spec: 'max-w-[80%] rounded-2xl bg-message p-3 text-message-foreground'.
         The fill is the ONE surface in the transcript. */
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
         design spec's 'px-1 py-0.5'. Phase F4 fills it with the shared
         markdown renderer, so the block-level rhythm is the renderer's and this box no longer
         preserves source whitespace (a 'pre-wrap' around block children would re-introduce the
         template's own newlines as vertical space). */
      .answer {
        position: relative;
        min-width: 0;
        /* The spec's 'max-w-3xl' — the reading measure is the COLUMN's
           property, not the renderer's, which is why it sits here (tempdoc 822 §2.5). */
        max-inline-size: var(--measure-prose);
        padding: var(--space-0-5) var(--space-1);
        font-size: var(--font-size-sv3-sm);
        line-height: 1.625;
        overflow-wrap: anywhere;
      }

      /* ── The chat-markdown clothes on the shared renderer ───────────────────
         The renderer is the product's (components/chat/MarkdownBlock.ts) and is NOT forked; what
         it exposes is the set of custom properties it reads, so the design spec's values arrive as
         a re-mapping of those names onto sv3 tokens.

         The recorded GAP is CLOSED (tempdoc 822 §C2, slices S4 + S5). Its first half: the renderer
         names its block geometry as '--md-*' on its own ':host' with byte-identical defaults, so
         the block rhythm, the inline chip's edge + step-down, the pre chrome and the quote rule
         arrive from here as a re-mapping like every colour above. Its
         second half was the markup with NO rule at all — headings, tables, hr,
         img, task lists — which no token can express, so it lives behind the renderer's
         ':host([prose])' variant and this window OPTS IN at both transcript call sites (the settled
         answer and the agent-run text item). The variant's own defaults are the shipped type ramp;
         the heading re-points below are what align them with the spec. The reasoning trace is
         deliberately NOT opted in (§2.1): a compact trace should not adopt prose rhythm. */
      .sv3-markdown,
      .sv3-citations {
        /* Headings and code sit at full foreground. */
        --text-primary: var(--foreground);
        /* The h6 + blockquote recession. */
        --text-secondary: var(--muted-foreground);
        /* A link is the info hue, not the brand accent. */
        --text-tint: var(--info-foreground);
        /* Inline code and code blocks share the muted fill. */
        --surface-tertiary: var(--muted);
        --surface-2: var(--muted);
        --surface-3: var(--secondary);
        /* The blockquote rule and the code block's edge. */
        --border-subtle: var(--border);
        --accent-tint: var(--primary);
        --accent-on-tint: var(--primary-foreground);
        --accent-warning: var(--warning-foreground);
        --text-warning: var(--warning-foreground);
        --text-command: var(--foreground);
        --font-size-sm: var(--font-size-sv3-sm);
        /* Inline code, block code and table text all step down. */
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
        /* An unbroken token in chat prose must not widen the measure. */
        overflow-wrap: anywhere;
        word-break: break-word;

        /* The geometry half of the bridge (tempdoc 822 §2.2). These names are the renderer's own,
           declared on its ':host' with the SHIPPED literals; the values here are the spec's, and
           they reach only the elements carrying this class — the citations list and the reasoning
           trace keep the shipped rhythm on purpose. Two of the fifteen are absent because sv3 keeps
           the shipped value: '--md-list-indent' (1.25rem) and '--md-pre-padding'. */
        /* The transcript's prose leading. */
        --md-line-height: 1.625;
        /* One 0.65rem-class rhythm for every block, wide or not (10px on the ladder). */
        --md-block-gap: var(--space-2-5);
        --md-block-gap-wide: var(--space-2-5);
        /* List items sit tight; the variant's 'li + li' carries the gap (S5). */
        --md-item-gap: 0;
        /* The inline chip: an edge, the small radius, a tighter inset and the
           12px step-down in the window's mono face. */
        --md-code-border: 1px solid var(--border);
        --md-code-radius: var(--radius-sm);
        --md-code-padding: 0.1rem 0.35rem;
        --md-code-size: var(--font-size-sv3-xs);
        --md-code-font: var(--font-mono);
        /* The code block takes the window's full radius knob. */
        --md-pre-radius: var(--radius);
        /* A thinner quote rule, a slightly wider inset. */
        --md-quote-border: 2px solid var(--border);
        --md-quote-padding: 0.8rem;
        /* A link is coloured, not underlined; the renderer's unconditional ':hover'
           rule restores the affordance under the pointer. */
        --md-link-decoration: none;

        /* The prose variant's heading ramp (tempdoc 822 §2.3, slice S5). The variant reads the
           SHIPPED type scale for h1/h2/h3 (and the already-re-pointed '--font-size-sm' for h4-h6),
           so the spec's heading scale — 1.25 / 1.125 / 1 / 0.875rem — arrives the same
           way the two steps above do: as a re-point onto this window's own ramp, which already IS
           that scale. Not one rem literal is copied (§2.1). Inside the renderer nothing else reads
           these three steps, so re-pointing the ramp here retunes exactly the headings.
           The variant's remaining defaults (weight 600, line-height 1.3, the asymmetric margin, the
           table padding and rules, the 24rem truncation cap, the between-items gap) already match
           the spec's numbers, or they read a colour/size token re-pointed above — so they are
           deliberately NOT re-pointed; 'Sv3Main.imports.test.ts' carries that decision in writing,
           name by name. */
        --font-size-xl: var(--font-size-sv3-xl);
        --font-size-lg: var(--font-size-sv3-lg);
        --font-size-md: var(--font-size-sv3-base);
      }
      /* The expanded evidence sits under the TAIL ROW, not under the answer (Phase F11), so its
         rhythm is the row's own 8px. An outer-tree rule on the host beats the component's own :host
         margin (the F8 pane lesson), which is why the shared component's 0.5rem is neutralised from
         here rather than edited inside an authority three windows render through. */
      .sv3-citations {
        display: block;
        margin: var(--space-2) 0 0;
      }
      .answer-empty {
        color: var(--secondary-label);
      }

      /* ── The same clothes on the three remaining imports (Phase F9) ────────
         Identical pattern to '.sv3-markdown' above and 'Sv3Pane.ts:78' — the shared components are
         NOT forked; what they expose is the set of custom properties they read, and a property the
         window does not re-point falls through to the shipped app's ':root', which is the LIGHT
         palette (the window carries no theme attribute; the app does). Unbridged, that is not a
         taste difference but a polarity inversion: the F-series fit audit measured this card
         painting a near-white light-theme fill under a near-white tool name — white on white —
         and the reasoning block painting light-theme slate text on the window's near-black.

         Every pair below is computed against the window's own tokens in 'Sv3Main.imports.test.ts'
         (>= 4.5:1), which is why the surface choices are not always the nearest name: a 4 %-white
         wash NESTED inside another one leaves subdued text short of the floor, so a well inside a
         raised card goes DOWN the ladder (to --background) rather than up. */

      /* The agent run's tool call (components/chat/ToolCallCard.ts). */
      jf-tool-call-card {
        /* The card is the ONE raised surface here; its wells sit on it, and the quoted-output frame
           (which the component draws with a 3px --border-strong rule) goes deeper instead. */
        --surface-secondary: var(--card);
        --surface-tertiary: var(--muted);
        --surface-2: var(--background);
        --text-primary: var(--foreground);
        --text-secondary: var(--secondary-label);
        --text-warning: var(--warning-foreground);
        --border-subtle: var(--border);
        --border-strong: var(--input);
        /* As in the '.sv3-markdown' rule above, same reason — a link is the info hue. This
           is also the token whose NAME collided: sv3's hover material used to be called --accent,
           so this link painted at 4 % opacity (audit DEFECT-6, fixed in sv3-tokens.css.ts). */
        --accent: var(--info-foreground);
        --accent-tint: var(--primary);
        --accent-on-tint: var(--primary-foreground);
        /* The risk tiers keep the spec's 45 % edge grade, spent on the window's three-colour
           budget (818 law 5: act-now / in-motion / broken, no fourth role). */
        --accent-danger-45: color-mix(in srgb, var(--destructive) 45%, transparent);
        --accent-warning-45: color-mix(in srgb, var(--warning) 45%, transparent);
        /* The status word is written by an INLINE style (ToolCallCard.ts:354) off
           utils/statusTone.ts:88-104, but what that authority returns is 'var(--accent-<tone>)' —
           a custom property, so the inline colour resolves against these declarations like any
           other. (The audit recorded it as unreachable from a host token; it is not.) */
        --accent-success: var(--success-foreground);
        --accent-warning: var(--warning-foreground);
        --accent-danger: var(--error-foreground);
        /* Law 10: one family. The component's 'display' face is the window's only sans. */
        --font-display: var(--font-sans);
        --font-size-sm: var(--font-size-sv3-sm);
        --font-size-xs: var(--font-size-sv3-xs);
        --duration-normal: var(--duration-sv3-layout);
        --ease-standard: var(--ease-sv3-enter);
      }

      /* The model's thinking (components/chat/ReasoningBlock.ts). Its --text-muted is the resting
         body and --text-secondary the emphasis it brightens to on hover, so the two map to
         DIFFERENT rungs than the tool card's (which uses --text-secondary as its subdued rung) —
         the shipped hierarchy is per-component, and a bridge carries meaning, not names. */
      jf-reasoning-block {
        --surface-subtle: var(--muted);
        --text-muted: var(--secondary-label);
        --text-secondary: var(--foreground);
        --border-muted: var(--border);
        --accent-primary: var(--ring);
        /* Passed down to the nested <jf-markdown-block> the block renders its content into: it is
           outside the '.sv3-markdown' class bridge, so its tokens arrive here or not at all. The
           component re-points --text-primary to --text-muted itself (ReasoningBlock.ts:120-123),
           which is why a code well on the already-washed container goes to --background. */
        --surface-tertiary: var(--background);
        --text-primary: var(--foreground);
        --text-tint: var(--info-foreground);
        --border-subtle: var(--border);
        --accent-tint: var(--primary);
        --accent-on-tint: var(--primary-foreground);
        --accent-warning: var(--warning-foreground);
        /* Tempdoc 822 §3c — the ungrounded citation MARK's color (the warning role's text member;
           the body underline beside it uses the fill member above). Bridged in the same slice that
           introduced the read, per the charter's import-bridge clause. */
        --text-warning: var(--warning-foreground);
        --font-size-sm: var(--font-size-sv3-sm);
        --font-size-xs: var(--font-size-sv3-xs);
        --duration-fast: var(--duration-sv3-micro);
        --ease-standard: var(--ease-sv3-enter);
      }

      /* The citation preview (components/chat/CitationHoverCard.ts). It floats over the transcript,
         so it takes the window's opaque raised surface: --popover would leave its dimmest line
         (the match score) at exactly the 4.5:1 floor with no margin. Its own drop shadow is a
         hard-coded literal the bridge cannot reach (recorded, not worked around: the design spec
         wants no drop shadow in dark, and closing it is an edit inside the shared component). */
      jf-citation-hover-card {
        --surface-2: var(--card);
        --text-primary: var(--foreground);
        --text-secondary: var(--foreground);
        --text-muted: var(--secondary-label);
        --text-tint: var(--info-foreground);
        --border-subtle: var(--border);
        --font-size-sm: var(--font-size-sv3-sm);
        --font-size-xs: var(--font-size-sv3-xs);
        --z-modal: var(--z-tooltip);
        --duration-fast: var(--duration-sv3-micro);
        --ease-standard: var(--ease-sv3-enter);
      }

      /* ── The honesty pack (tempdoc 822 Phase F7) ───────────────────────────
         Every line below is a FACT about the answer, so none of them is allowed to hide behind hover
         (818 §6b L14): only the action bar at the bottom of the turn yields, and it yields on its
         own. They share the answer's own inset so a fact and the text it qualifies line up. */

      /* C8 — what retrieval actually searched for, above the answer it produced (the shipped
         window's own placement, views/UnifiedChatView.ts:5550-5553). */
      .rewrite-note {
        margin: 0 0 var(--space-1);
        padding-inline: var(--space-1);
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
      }
      .rewrite-note em {
        color: var(--foreground);
        font-style: italic;
      }

      /* ── THE ANSWER TAIL: C1 + the evidence disclosure + A9, in ONE row (Phase F11) ────────
         The design spec's assistant footer is exactly this: mt-1.5,
         gap-2, text-xs, tabular-nums, items-center, one line under the message. Everything that used
         to stack below an answer — the frame line, the imported panel's own uppercase disclosure and
         the action bar — is this row now, at 30px instead of ~103px.

         The honesty facts hold at opacity 1 (818 §6b L14); the ONE thing that yields is the copy
         button, on its own, exactly as the F7 action bar did. */
      .tail {
        display: flex;
        /* The honest overflow: the tail WRAPS. It never truncates and never hides a fact. */
        flex-wrap: wrap;
        align-items: center;
        gap: var(--space-2);
        /* The spec's xs control height — reserved whether or not the copy button is revealed, so nothing
           in the turn resizes under the pointer. */
        min-height: var(--space-6);
        margin-top: var(--space-1-5);
        /* Matches the .answer inset above, so the tail and the text it belongs to share an edge. */
        padding-inline: var(--space-1);
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
        line-height: 1.5;
        font-variant-numeric: tabular-nums;
      }
      .tail-note[data-broken='true'] {
        color: var(--error-foreground);
      }

      /* The window's ONE disclosure affordance (the fit audit's axis 3, answered for this region):
         the spec's own inline turn-fold row — sentence case,
         gap-1, rounded, px-1, muted → foreground on hover. No uppercase, no letter-spacing, and the
         same 12px as the facts beside it. */
      .tail-sources {
        display: inline-flex;
        align-items: center;
        gap: var(--space-1);
        height: var(--space-6);
        padding-inline: var(--space-1);
        border: 0;
        border-radius: var(--control-radius);
        background: none;
        color: inherit;
        font: inherit;
        cursor: pointer;
      }
      .tail-sources:hover {
        color: var(--foreground);
      }
      .tail-sources:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }
      .tail-chevron {
        flex-shrink: 0;
        color: var(--icon-muted);
      }

      /* A9 — the one thing in a turn that hides until the reader reaches for it. The spec's
         xs button (24x24) carrying the copy-button glyph pair. */
      .tail-copy {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        inline-size: var(--space-6);
        block-size: var(--space-6);
        border: 0;
        border-radius: var(--control-radius);
        background: none;
        color: inherit;
        cursor: pointer;
        opacity: 0;
        transition: opacity var(--duration-sv3-layout) var(--ease-sv3-enter);
      }
      /* THREE SEPARATE RULES, never one rule nesting a focus test inside :has() — that selector is
         a Chrome syntax error and it killed the whole rule list in Phase F3 (static-green is not
         live-working).
         Focus must not depend on the reveal having finished, and a keyboard reader gets no hover. */
      .turn:hover .tail-copy {
        opacity: 1;
      }
      .turn:focus-within .tail-copy {
        opacity: 1;
      }
      .tail-copy:focus-visible {
        opacity: 1;
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }
      .tail-copy:hover {
        background: var(--accent-surface);
        color: var(--foreground);
      }
      @media (prefers-reduced-motion: reduce) {
        .tail-copy {
          transition: none;
        }
      }

      /* ── E4/E5: the store is locked, so the transcript is NOT READABLE ─────
         Tempdoc 629 §L4 — locked must never look deleted, and it must never look readable either.
         The region renders this INSTEAD of the transcript; nothing of the conversation is drawn
         behind it, which is the whole difference from the stale-readable state tempdoc 734 fixed. */
      .locked-detail {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--space-3);
      }
      .locked-refusal {
        margin: 0;
        color: var(--foreground);
      }
      .locked-remedy {
        display: inline-flex;
        align-items: center;
        gap: var(--space-1-5);
        padding: var(--space-1-5) var(--space-3);
        border: 1px solid var(--border);
        border-radius: var(--control-radius);
        background: var(--background);
        color: var(--foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-sm);
        cursor: pointer;
      }
      .locked-remedy:hover {
        background: var(--muted);
      }
      .locked-remedy:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }
      /* The turn's terminal, said in words. Halting is the reader's own act and gets no colour — the
         3-colour budget is for act-now / in-motion / broken, and a stop is none of those. */

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

      /* ── The record could not be read (Phase F6 / inventory D2) ────────────
         In FLOW at the top of the transcript, not floating over it: it qualifies everything below,
         so it has to be the first thing read and it has to scroll away with the content it qualifies.
         It carries no colour from the 3-colour budget — a refresh that failed is neither act-now nor
         broken, it is a shortfall in what the window can show. */
      .record-notice {
        margin-block-end: var(--space-4);
        padding: var(--space-2) var(--space-3);
        border: 1px solid var(--border);
        border-radius: var(--radius-lg);
        background: var(--muted);
      }
      .record-notice-title {
        margin: 0;
        font-size: var(--font-size-sv3-sm);
      }
      .record-notice-detail {
        margin: var(--space-1) 0 0;
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
      }
    `,
  ];

  static properties = {
    state: { type: String, reflect: true },
    view: { attribute: false },
    turns: { attribute: false },
    run: { attribute: false },
    recordNotice: { type: Boolean, attribute: 'record-notice' },
    historyLocked: { type: Boolean, attribute: 'history-locked' },
    lockedRefusal: { type: Boolean, attribute: 'locked-refusal' },
    // A MUTABLE handle, not a value: the controller accumulates thinking in place, so its identity
    // never changes and Lit's default equality would hold this region back from re-rendering while
    // the model is thinking. Declared changed whenever one is present, unchanged when it is absent.
    reasoning: {
      attribute: false,
      hasChanged: (value: unknown, old: unknown) => value !== old || value !== null,
    },
    currentModelLabel: { attribute: false },
    detailed: { type: Boolean, reflect: true },
    copiedTurnId: { state: true },
    expandedSources: { state: true },
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
   * The claimed conversation's canonical record could not be read, so what is on screen may be
   * incomplete (tempdoc 822 Phase F6 / inventory D2). A BOOLEAN, not a message: the copy is fixed
   * ({@link RECORD_UNREACHABLE}) and belongs with the window's other fixed copy, so a caller cannot
   * word this state a second way.
   */
  declare recordNotice: boolean;
  /**
   * The conversation store is encrypted and locked, so this region MUST NOT render the transcript
   * (tempdoc 629 §L4; inventory E4/E5). Handed down already derived by the window's one tri-state
   * reading of the polled protection state (`sv3-honesty.ts`), because a lock taken elsewhere reaches
   * every surface the same way and no region may decide it locally.
   */
  declare historyLocked: boolean;
  /** A send this window made was REFUSED by that lock, so the locked view says what became of it. */
  declare lockedRefusal: boolean;
  /**
   * The SHARED reasoning controller driving the turn that is streaming right now, or null (inventory
   * C9). Live only: a settled turn renders the blocks stored on it, so a finished conversation does
   * not depend on a controller that has since been reset.
   */
  declare reasoning: ReasoningController | null;
  /**
   * The model the COMPOSER is currently naming (tempdoc 822 Phase F11). Handed down so a turn's own
   * stamped model can be suppressed when the two agree and re-stated when they do not — the region
   * decides neither, it is given both and asks the one derivation (`sv3TailModelLabel`).
   */
  declare currentModelLabel: string | null;
  /**
   * The app-wide Simple/Detailed authority's answer (inventory E3), handed down by the window.
   *
   * It gates ONE thing here: whether the frame line names the MODEL. The model is a technical
   * identifier — the shipped window reached the same conclusion and gates the same fact the same way
   * (`views/UnifiedChatView.ts:5040`). What it never gates is an honesty fact: the grounding verdict
   * and the duration are in both modes, because "how much of this is backed by your documents" is
   * the thing a Simple reader most needs and least knows to ask for.
   */
  declare detailed: boolean;
  /** The turn whose answer was just copied — the confirmation, and the only state this region owns. */
  declare copiedTurnId: string | null;
  /**
   * Which turns have their evidence open, BY TURN ID. Per turn and never global, so opening turn 3's
   * sources leaves turn 7 collapsed; replaced rather than mutated, because a Set mutated in place is
   * the same Set and Lit would not re-render.
   */
  declare expandedSources: ReadonlySet<string>;

  /**
   * The design spec's two scroll modes as one flag: armed = `following-end` (the reader is at the end, so
   * new text keeps the end in view), disarmed = `free-scrolling` (the reader scrolled up and owns
   * the viewport until they return to the end, which RE-ARMS it).
   */
  private followEnd = true;
  private copiedTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    super();
    this.state = COMPOSER_STATE_DEFAULT;
    this.view = SV3_RESULTS_IDLE;
    this.turns = [];
    this.run = null;
    this.recordNotice = false;
    this.historyLocked = false;
    this.lockedRefusal = false;
    this.reasoning = null;
    this.currentModelLabel = null;
    this.detailed = false;
    this.copiedTurnId = null;
    this.expandedSources = new Set();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    // The confirmation's timer would otherwise outlive the region and set state on a detached element.
    if (this.copiedTimer !== null) clearTimeout(this.copiedTimer);
    this.copiedTimer = null;
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
    // THE LOCK COMES FIRST, and it replaces the transcript rather than covering it (inventory E4):
    // a store that refuses to be read must not leave a readable copy of what it holds on screen. Only
    // the CONVERSATION is gated — the search projection below is a different, unencrypted store, and
    // gating it too would be a true statement about the wrong data (tempdoc 629's own scope rule).
    if (this.historyLocked && (turns.length > 0 || this.recordNotice || this.lockedRefusal)) {
      return this.locked();
    }
    // The conversation owns the region whenever the claimed session has one. The search projection
    // below is the SECONDARY axis now (822 §4b course correction) and speaks only for a session that
    // has asked nothing — it is reached from the palette, never from a plain submit.
    if (turns.length > 0 || this.recordNotice) return this.transcript(turns);
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
   * The asymmetry is the design spec's and it is load-bearing: only the user's turn carries a fill, so the
   * transcript reads as answers punctuated by asks rather than as two columns of chat.
   */
  private transcript(turns: readonly Sv3Turn[]): TemplateResult {
    return html`
      <div
        class="scroller sv3-scroller"
        data-testid="sv3-main-scroller"
        @scroll=${this.onScroll}
        aria-busy=${turns.at(-1)?.status === 'streaming' ? 'true' : 'false'}
        @cite-ref-hover=${this.onCiteRefHover}
        @cite-ref-leave=${this.onCiteRefLeave}
      >
        <div class="transcript" data-testid="sv3-transcript">
          ${this.recordNotice
            ? html`<div class="record-notice" role="status" data-testid="sv3-record-notice">
                <p class="record-notice-title">${RECORD_UNREACHABLE.title}</p>
                <p class="record-notice-detail">${RECORD_UNREACHABLE.description}</p>
              </div>`
            : nothing}
          ${turns.map((turn) => this.turn(turn))}
        </div>
      </div>
      <!-- OUTSIDE the scroller: the card is viewport-positioned from the mark's own rect, so a
           scroller that clipped it would hide the preview at the region's edges. -->
      <jf-citation-hover-card data-testid="sv3-citation-hover"></jf-citation-hover-card>
    `;
  }

  /**
   * C3 — the mark's preview, in the product's ONE hover card. The event is the shared markdown
   * block's own (`components/chat/MarkdownBlock.ts:591-607`), carrying both the trigger's rect and
   * the resolved source, so this surface looks nothing up: it forwards what the mark already knows.
   * Delegated at the scroller rather than bound per mark, because the marks are woven into the shared
   * block's shadow DOM and this window never touches them.
   */
  private readonly onCiteRefHover = (event: Event): void => {
    const detail = (event as CustomEvent).detail as
      | { rect?: DOMRect; source?: CitationHoverData }
      | undefined;
    const source = detail?.source;
    const rect = detail?.rect;
    if (source === undefined || rect === undefined) return;
    this.hoverCard?.show(source, rect);
  };

  private readonly onCiteRefLeave = (): void => {
    this.hoverCard?.hide();
  };

  private get hoverCard(): CitationHoverCard | null {
    return this.shadowRoot?.querySelector('jf-citation-hover-card') ?? null;
  }

  /**
   * E4/E5 — the locked store's own view, in the window's ONE empty-state component. The
   * heading is `reasonFor('conversations.locked')`'s wording and the remedy is that cause's own
   * declared navigation, so the locked transcript speaks the vocabulary every other readiness cause
   * in the product speaks and points at the surface that actually owns the unlock.
   */
  private locked(): TemplateResult {
    const reason = reasonFor('conversations.locked');
    const nav = reason.remedy?.kind === 'navigate' ? reason.remedy : null;
    return html`
      <jf-sv3-empty
        roomy
        data-testid="sv3-history-locked"
        glyph="&#9634;"
        heading=${reason.wording}
        description=${HISTORY_LOCKED_HELP}
      >
        <div class="locked-detail">
          ${this.lockedRefusal
            ? html`<p class="locked-refusal" role="alert" data-testid="sv3-history-locked-refusal">
                ${HISTORY_LOCKED_REFUSED}
              </p>`
            : nothing}
          ${nav === null
            ? nothing
            : html`<button
                type="button"
                class="locked-remedy"
                data-testid="sv3-history-locked-remedy"
                @click=${() => this.remedy(nav.target)}
              >
                ${icon({ name: 'shield', size: 14 })} ${nav.label}
              </button>`}
        </div>
      </jf-sv3-empty>
    `;
  }

  private remedy(target: string): void {
    this.dispatchEvent(
      new CustomEvent<Sv3RemedyDetail>(SV3_REMEDY, {
        detail: { target },
        bubbles: true,
        composed: true,
      }),
    );
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
        ${turn.question === ''
          ? nothing
          : html`<div class="ask">
              <div class="ask-bubble" data-testid="sv3-turn-question">${turn.question}</div>
            </div>`}
        ${turn.kind === 'agent'
          ? run === null
            ? this.recordedActivity(turn)
            : this.runBody(run)
          : html`
              ${this.rewriteNote(turn)}${this.reasoningBlocks(turn, streaming)}
              <div class="answer" data-testid="sv3-turn-answer">
                ${empty && !streaming
                  ? html`<span class="answer-empty" data-testid="sv3-turn-answer-empty"
                      >${TURN_EMPTY_ANSWER}</span
                    >`
                  : html`<jf-markdown-block
                      class="sv3-markdown"
                      prose
                      data-testid="sv3-turn-markdown"
                      .text=${turn.answer}
                      ?is-streaming=${streaming}
                      .citations=${[...(turn.evidence?.marks ?? [])]}
                      @citation-select=${this.onCitationSelect}
                    ></jf-markdown-block>`}
              </div>
            `}
        ${this.tail(turn)}${this.citations(turn)}
      </div>
    `;
  }

  /**
   * C8 (tempdoc 603 C2) — the standalone question retrieval actually ran on, shown back. A follow-up
   * like "and the second one?" is searched as something else entirely, and a reader who cannot see
   * what that was cannot tell a bad answer from a bad rewrite.
   */
  private rewriteNote(turn: Sv3Turn): TemplateResult | typeof nothing {
    if (turn.standaloneQuestion === '') return nothing;
    return html`<p class="rewrite-note" data-testid="sv3-turn-rewrite">
      ${REWRITE_NOTE_LABEL} <em>${turn.standaloneQuestion}</em>
    </p>`;
  }

  /**
   * C9 — the model's thinking, in the product's ONE controlled block: collapsed by its own
   * disclosure, never mixed into the answer text. Two sources, and only ever one of them: the LIVE
   * controller while this turn streams, the blocks recorded on the turn once it has settled.
   */
  private reasoningBlocks(turn: Sv3Turn, streaming: boolean): TemplateResult | typeof nothing {
    const live = this.reasoning;
    if (streaming) {
      if (live === null || (!live.isThinking && live.reasoningBlocks.length === 0)) return nothing;
      return html`<jf-reasoning-block
        data-testid="sv3-turn-reasoning"
        .controller=${live}
      ></jf-reasoning-block>`;
    }
    if (turn.reasoning.length === 0) return nothing;
    return html`${turn.reasoning.map(
      (block) => html`<jf-reasoning-block
        data-testid="sv3-turn-reasoning"
        .text=${block.text}
        .durationMs=${block.durationMs}
      ></jf-reasoning-block>`,
    )}`;
  }

  /**
   * THE ANSWER TAIL (tempdoc 822 Phase F11) — everything below a settled answer, in ONE row.
   *
   * Three stacked rows became one: the frame line's facts, the evidence disclosure (previously the
   * imported panel's own `▸ N SOURCES` header, on its own line, in a dialect the window speaks
   * nowhere else) and the copy action. The row is the design spec's assistant-message footer,
   * which is exactly this composition.
   *
   * It renders only when it has something in it, so a STREAMING turn has no row at all — the spec's
   * own rule (no footer while streaming) and today's behaviour unchanged.
   */
  private tail(turn: Sv3Turn): TemplateResult | typeof nothing {
    const facts = this.tailFacts(turn);
    const note = this.turnNote(turn);
    const sources = this.tailSources(turn);
    const copy = this.tailCopy(turn);
    if (facts === nothing && note === nothing && sources === nothing && copy === nothing) {
      return nothing;
    }
    return html`<div class="tail" data-testid="sv3-turn-tail">
      ${facts}${note}${sources}${copy}
    </div>`;
  }

  /**
   * C1 — the honest answer frame: what it is based on, how long it took, and which model wrote it in
   * the one case the composer would otherwise mislabel. The whole line is `sv3-honesty.ts`'s
   * derivation over the SHARED frame authority; this renders it and decides nothing, which is what
   * keeps the wording identical to the shipped window's.
   *
   * TWO NODES, ONE FACT. The authority's label is `"<verdict> — <elaboration>"`; the verdict is an
   * honesty fact and RESTS, the elaboration is elaboration by L14's own words and extends on pointer
   * (`title`). What is NOT done is hiding it from assistive tech: the `.visually-hidden` half carries
   * the authority's WHOLE string permanently, so nothing was hidden from AT and nothing has to be
   * revealed to it. The residual — a sighted keyboard-only reader sees the verdict, not the
   * elaboration — is accepted rather than paid for with a turn that resizes under the pointer or a
   * second tab stop per turn (tempdoc 822 F11 §2.6).
   */
  private tailFacts(turn: Sv3Turn): TemplateResult | typeof nothing {
    const frame = projectSv3AnswerFrame(turn, this.currentModelLabel, this.detailed);
    if (frame === null) return nothing;
    const verdict = frame.verdict ?? '';
    const label =
      verdict === '' || frame.elaboration === ''
        ? verdict
        : `${verdict} — ${frame.elaboration}`;
    // The separator lives ONLY inside this text node. Between the facts and the controls beside them
    // there is an 8px gap and no dot: a middle dot between a sentence and a button is nobody's idiom.
    const join = (head: string): string =>
      head === '' ? frame.tail : frame.tail === '' ? head : `${head} · ${frame.tail}`;
    const full = join(label);
    return html`<span
      class="tail-facts"
      role="note"
      data-testid="sv3-answer-frame"
      title=${full}
      ><span class="visually-hidden">${full}</span
      ><span aria-hidden="true">${join(verdict)}</span
    ></span>`;
  }

  /**
   * The window's ONE disclosure affordance for a turn's evidence (the fit audit's axis 3, answered
   * for this region): the design spec's own inline trigger, sentence case at the tail's 12px, opening the
   * SHARED panel beneath the row rather than a window-local source list.
   *
   * The count is not on the resting surface (the owner's direction) but is never lost: the
   * accessible name carries it always, so the calibration fact reaches AT unconditionally and the
   * sighted reader is one click from it. `SV3_SOURCES_COUNT_IN_TRIGGER` is the one-line flip.
   */
  private tailSources(turn: Sv3Turn): TemplateResult | typeof nothing {
    if (!this.panelSpeaks(turn)) return nothing;
    const trigger = sv3SourcesTrigger(turn.evidence);
    if (trigger === null) return nothing;
    const count = sv3SourcesTriggerCount(turn.evidence);
    const expanded = this.expandedSources.has(turn.id);
    return html`<button
      type="button"
      class="tail-sources"
      data-testid="sv3-turn-sources"
      aria-expanded=${expanded ? 'true' : 'false'}
      aria-controls=${expanded ? sourcesBodyId(turn.id) : nothing}
      aria-label=${`${trigger}: ${count}`}
      @click=${() => this.toggleSources(turn.id)}
    >
      <span>${sv3SourcesTriggerLabel(trigger, count)}</span>
      ${icon({
        name: expanded ? 'chevron-down' : 'chevron-right',
        size: TAIL_CHEVRON_SIZE,
        className: 'tail-chevron',
      })}
    </button>`;
  }

  /** Per TURN, never global: opening turn 3's sources must not open turn 7's. */
  private toggleSources(id: string): void {
    const next = new Set(this.expandedSources);
    if (!next.delete(id)) next.add(id);
    this.expandedSources = next;
  }

  /**
   * A9 — copy this answer. The ONE affordance in a turn that hides until the reader reaches for it;
   * every honesty fact beside it stays visible, which is L14's boundary drawn exactly where the
   * design spec draws its own. Icon-only since Phase F11, so the label is the
   * ACCESSIBLE name and the confirmation moved to the row's own live region — a name that changed to
   * "Copied" would rename the control instead of reporting the act.
   *
   * Offered only for an answer there IS: a streaming turn's text is still arriving, and a halted or
   * failed one is a fragment the reader did not ask to keep.
   */
  private tailCopy(turn: Sv3Turn): TemplateResult | typeof nothing {
    if (turn.kind !== 'ask' || turn.status !== 'complete' || turn.answer === '') return nothing;
    const copied = this.copiedTurnId === turn.id;
    return html`<button
        type="button"
        class="tail-copy"
        data-testid="sv3-turn-copy"
        aria-label=${TURN_COPY_LABEL}
        title=${TURN_COPY_LABEL}
        @click=${() => void this.copyAnswer(turn)}
      >
        ${icon({
          name: copied ? 'check-circle-2' : 'clipboard-copy',
          size: TAIL_GLYPH_SIZE,
        })}</button
      ><span
        class="visually-hidden"
        role="status"
        aria-live="polite"
        data-testid="sv3-turn-copy-status"
        >${copied ? TURN_COPY_DONE : ''}</span
      >`;
  }

  private async copyAnswer(turn: Sv3Turn): Promise<void> {
    // The util never throws and reports whether the write landed; a confirmation is shown only when
    // it did, so "Copied" is never said over an empty clipboard.
    if (!(await copyToClipboard(turn.answer))) return;
    if (this.copiedTimer !== null) clearTimeout(this.copiedTimer);
    this.copiedTurnId = turn.id;
    this.copiedTimer = setTimeout(() => {
      this.copiedTurnId = null;
      this.copiedTimer = null;
    }, TURN_COPY_FEEDBACK_MS);
  }

  /**
   * What the RECORD says happened in an agent turn that is not the live one (tempdoc 822 Phase F6;
   * inventory D1). Two sources, ONE renderer: `runItem` below draws both the live controller feed and
   * this, because they are the same three item shapes — so a run the reader watched and a run they
   * came back to cannot be drawn differently. The record's order is the record's, interleaved
   * (561 P-A), never re-sorted here.
   *
   * Empty until the record has spoken for the turn, which is also what a run that ended before this
   * window could refresh looks like — the receipt line below still says what it was.
   */
  private recordedActivity(turn: Sv3Turn): TemplateResult | typeof nothing {
    if (turn.activity.length === 0) return nothing;
    return html`<div class="run-feed" data-testid="sv3-record-activity">
      ${turn.activity.map((item) => this.runItem(item))}
    </div>`;
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
   * The answer's evidence, in the product's ONE citations panel — the same component search-v2
   * mounts on a landed answer (`views/search-v2/SearchV2View.ts:2220-2229`), with the DISCLOSURE
   * moved out to the tail row above (Phase F11).
   *
   * `externalDisclosure` is what makes the tail one line: without it the panel heads itself, on its
   * own row, in an uppercase dialect the window speaks nowhere else. The panel is mounted only while
   * open, so a collapsed turn contributes no box and no margin to the 30px tail — a permanently
   * mounted empty panel would push the next turn 8px down for a body nobody asked to see.
   */
  private citations(turn: Sv3Turn): TemplateResult | typeof nothing {
    if (!this.panelSpeaks(turn) || turn.evidence === null) return nothing;
    if (!this.expandedSources.has(turn.id)) return nothing;
    return html`<jf-citations-panel
      class="sv3-citations"
      id=${sourcesBodyId(turn.id)}
      data-testid="sv3-turn-citations"
      .externalDisclosure=${true}
      .sourcesExpanded=${true}
      .citations=${[...turn.evidence.matches]}
      .sources=${[...turn.evidence.sources]}
      .retrievalMode=${turn.evidence.retrievalMode}
      @citation-select=${this.onCitationSelect}
    ></jf-citations-panel>`;
  }

  /**
   * THE IN-WINDOW CITATION LANDING (tempdoc 822 Phase F8), and the one line in it that matters is
   * `stopPropagation`.
   *
   * `citation-select` is `bubbles: true, composed: true` from every producer (the panel above at
   * `components/chat/CitationsPanel.ts:291-296`, the inline `[n]` mark at
   * `components/chat/MarkdownBlock.ts:571-576`), and the Shell listens for it at the HOST with no
   * guard at all — "the ONE listener" (`chrome/Shell.ts:533-554`), which writes the cited document
   * onto the shared `state/inspectorState.ts` and thereby opens the SHIPPED window's reading pane.
   * Until F8 nothing collided only because the stage mounts one surface at a time; that is an
   * accident of the mount, not a guard. Stopping the event AT THE PRODUCING ELEMENT is the guard: an
   * in-window citation click is answered in-window, and the shared selection is not touched.
   *
   * What leaves this surface instead is the window's own `sv3-citation-open` — the window owns the
   * pane, because the pane is a region of the window grid whose width is clamped against the
   * sidebar's.
   */
  private readonly onCitationSelect = (event: Event): void => {
    event.stopPropagation();
    const detail = (event as CustomEvent<CitationSelectDetail>).detail;
    if (!detail?.parentDocId) return;
    const range =
      Number.isFinite(detail.startLine) && Number.isFinite(detail.endLine)
        ? { startLine: detail.startLine, endLine: detail.endLine }
        : null;
    this.dispatchEvent(
      new CustomEvent<Sv3CitationOpen>(SV3_CITATION_OPEN, {
        detail: { docPath: detail.parentDocId, range },
        bubbles: true,
        composed: true,
      }),
    );
  };

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
        <jf-markdown-block class="sv3-markdown" prose .text=${item.text}></jf-markdown-block>
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
   * A typed prompt with its OWN controls. The APPROVAL arm deliberately carries
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
          <!-- B8 — the REMEDY comes first (tempdoc 577 Ext III, views/UnifiedChatView.ts:3648): the
               other two arms both give something up, and offering them before the one that does not
               would put the concession where the reader looks first. The step is the shared
               RAISE_BUDGET_STEP_TOKENS, so the label cannot promise a different number than the
               directive spends. -->
          <button type="button" data-testid="sv3-run-budget-raise" @click=${() =>
            this.decide({ kind: 'budget', decision: 'raise' })}>
            Add ${RAISE_BUDGET_STEP_TOKENS.toLocaleString()} tokens
          </button>
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
   * What became of the turn, in words — the tail row's facts slot when the turn has no answer frame
   * of its own. A streaming turn says nothing: the text arriving IS the state, and a "generating…"
   * label beside moving text would be a second, redundant claim.
   */
  private turnNote(turn: Sv3Turn): TemplateResult | typeof nothing {
    if (turn.status === 'streaming') return nothing;
    const broken = turn.status === 'failed' || turn.status === 'refused';
    if (turn.kind === 'agent') {
      return html`<span
        class="tail-note"
        data-testid="sv3-run-receipt"
        data-outcome=${turn.status}
        data-broken=${String(broken)}
        >${sv3RunReceiptLabel(turn.toolCalls, turn.status)}</span
      >`;
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
    return html`<span
      class="tail-note"
      data-testid="sv3-turn-note"
      data-broken=${String(broken)}
      >${note}</span
    >`;
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
