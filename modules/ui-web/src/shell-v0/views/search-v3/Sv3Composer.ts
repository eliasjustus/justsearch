// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-composer — the Search v3 window's composer (tempdoc 822 slice 3).
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * The donor's composer anatomy (§6.7), minus the welded-tray `clip-path` the charter excludes — and
 * therefore minus the layer split that tray exists for: radius, glass fill, blur and elevation all
 * sit on ONE node, with only the 1px outline ring on a `::after` above the content.
 *
 * The material is entirely token-fed (`--composer-*`), which is what makes the dark ELEVATION
 * INVERSION expressible at all: the donor writes it as `.dark` rules, and a selector inside a shadow
 * root cannot see a class on `<html>` (§8.3). Light casts a shadow down; dark removes the shadow and
 * catches light on its top edge instead.
 *
 * The composer OWNS the draft and nothing else: sending announces the draft (Phase A1's
 * `sv3-composer-submit`) and the window decides what that means — which is now ASKING the local
 * model (Phase F1). It does not dock itself and it knows neither the search store nor the ask client
 * — the alternative would put a second issuance site here.
 *
 * Its primary-action slot holds exactly ONE control (donor `chat/ComposerPrimaryActions.tsx:156-157`):
 * Send, or Stop while a response streams. Never both, and never one disabled behind the other.
 *
 * ONE component in TWO states. HERO centres it in the main region under a headline (the empty
 * window); DOCKED returns it to the bottom band (the working window). Docking evaporates the scope-control
 * LABELS leftward into their glyphs (§5.9's signature compaction) and the window morphs the moving
 * box with the view transition in `sv3-composer-morph.ts` (§5.5).
 *
 * Side-effect registers <jf-sv3-composer>.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { icon } from '../../components/Icon.js';
import { sv3Shared } from './sv3-shared-styles.js';
import {
  COMPOSER_SCOPES,
  COMPOSER_PLACEHOLDER,
  COMPOSER_STATE_DEFAULT,
  CORPUS_ADD_FOLDERS,
  CORPUS_REMEDY_TARGET,
  HERO_HEADLINE,
  type Sv3ComposerState,
} from './fixtures.js';
import { sv3PrimaryAction, type Sv3SlotKind } from './sv3-run.js';
import {
  SV3_CORPUS_UNKNOWN,
  SV3_REMEDY,
  type Sv3Corpus,
  type Sv3RemedyDetail,
} from './sv3-honesty.js';

/** Raised when the composer asks the window for the other state; the window owns the morph. */
export const SV3_COMPOSER_STATE_REQUEST = 'sv3-composer-state-request';

export interface Sv3ComposerStateRequest {
  readonly state: Sv3ComposerState;
}

/**
 * Raised when the draft is SENT (tempdoc 822 Phase A1). The composer holds the draft and therefore
 * announces it; the window decides what a send means — issuing the search and docking are both its
 * calls, made once, in one handler. Every affordance that sends (the control, Enter) goes through
 * {@link Sv3Composer.submit}, so there is exactly one place a send can originate.
 */
export const SV3_COMPOSER_SUBMIT = 'sv3-composer-submit';

/**
 * Which tier the reader routed the draft to (tempdoc 822 Phase F2). Enter asks the local model;
 * Ctrl+Enter DELEGATES the same draft as an agent task. The keys are the whole difference — no mode
 * switch, no second field, and no new chrome: the routing is announced in the send slot's aria-label
 * and title, which is the one place a control may explain itself without spending screen.
 */
export type Sv3ComposerTier = 'ask' | 'delegate';

export interface Sv3ComposerSubmit {
  readonly query: string;
  readonly tier: Sv3ComposerTier;
}

/**
 * Raised when the reader halts a streaming response (tempdoc 822 Phase F1). The window holds the
 * AbortController, so the composer announces the intent and nothing else.
 */
export const SV3_COMPOSER_STOP = 'sv3-composer-stop';

/**
 * Raised by the `answer` rung of the primary slot (tempdoc 822 Phase F2). The composer cannot resolve
 * a typed prompt — that is the point of pattern (f) — so the control does the one thing it honestly
 * can: it asks the window to take the reader to the decision that is holding the run.
 */
export const SV3_COMPOSER_ANSWER = 'sv3-composer-answer';

/** Donor `ComposerControlIcon` at its default optical size (`size-4`). */
const SCOPE_GLYPH_SIZE = 16;

export class Sv3Composer extends JfElement {
  static styles = [
    sv3Shared,
    css`
      :host {
        display: block;
        flex-shrink: 0;
        padding: var(--floating-content-inset);
        font-family: var(--font-sans);
      }

      /* HERO lifts the composer out of the band and centres it over the content region. Its
         containing block is the window's column, so the top inset is the topbar's own token rather
         than a repeated number, and the overlay stays click-through except on the composer itself. */
      :host([state='hero']) {
        position: absolute;
        inset: var(--workspace-topbar-height) 0 0 0;
        z-index: var(--z-overlay);
        display: flex;
        align-items: center;
        pointer-events: none;
      }
      :host([state='hero']) .band {
        pointer-events: auto;
      }

      /* The moving box of the morph is the composer, not the overlay around it. The name is set
         ONLY while the window is morphing: a view-transition-name must be unique in the document,
         and a permanently-named element would join any other transition the app runs. */
      :host([morphing]) .band {
        view-transition-name: sv3-composer;
      }
      :host([morphing]) .headline {
        view-transition-name: sv3-hero-headline;
      }

      .band {
        position: relative;
        width: 100%;
        max-inline-size: 48rem;
        margin-inline: auto;
      }

      /* The hero INTRO — headline plus the corpus line under it (tempdoc 822 Phase F7, inventory
         E10). It sits directly above the composer box, which is the shipped landing's own placement
         ("this block renders at the bottom of the conversation column so the intro sits directly
         above the CSS-centered bar", views/UnifiedChatView.ts:3016-3018). The absolute positioning
         moved here off .headline so the two lines are ONE block above the band; .headline keeps its
         type and its view-transition name, so the morph is untouched. */
      .landing {
        position: absolute;
        inset-inline: 0;
        bottom: 100%;
        padding-bottom: var(--space-8);
      }

      .headline {
        margin: 0;
        color: var(--foreground);
        font-size: var(--font-size-sv3-display);
        font-weight: 400;
        letter-spacing: -0.025em;
        text-align: center;
        text-wrap: balance;
      }

      /* The corpus fact under the headline. Recedes to the secondary label because it is context for
         the question, not the question — and the REMEDY inside it is a real control, so it takes the
         foreground and an underline rather than becoming a coloured word that only looks clickable. */
      .corpus {
        margin: var(--space-2) 0 0;
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-sm);
        text-align: center;
        text-wrap: balance;
      }
      .corpus-remedy {
        padding: 0;
        border: 0;
        background: none;
        color: var(--foreground);
        font-family: inherit;
        font-size: inherit;
        text-decoration: underline;
        text-underline-offset: 2px;
        cursor: pointer;
      }
      .corpus-remedy:hover {
        color: var(--primary);
      }
      .corpus-remedy:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 2px;
        border-radius: var(--control-radius);
      }

      /* The donor stacks composer banners ABOVE the box, 8px clear of it, at the composer's own
         radius ('chat/ComposerBannerStack.tsx:101,193' — 'mx-auto mb-2 max-w-3xl', and the same
         rounded value the composer box carries, which is --radius-3xl here).
         This window's one banner is the availability reason: the local model cannot answer, said
         where the send would have happened. It is TEXT, not a disabled control's tooltip — the
         availability authority's whole point is that the reason stays reachable ('state/availability.ts:18-20'). */
      .notice {
        margin-bottom: var(--space-2);
        padding: var(--space-2) var(--space-4);
        border: 1px solid var(--border);
        border-radius: var(--radius-3xl);
        background: var(--muted);
        color: var(--foreground);
        font-size: var(--font-size-sv3-xs);
        line-height: 1.5;
      }

      /* ── The glass: ONE node carrying the whole recipe ────────────────────
         The donor splits the fill onto a pseudo-element under a separate host, because its welded
         attachment tray needs the material on its own clip-pathed layer. This port excludes that
         tray, so the split has no remaining purpose — and a split silhouette is a trap: the radius
         lands on the element while the blur and fill land on a sibling layer, so the surface anyone
         inspects reports no glass at all. Radius, fill, blur and elevation stay together here; only
         the 1px ring is a pseudo-element, because a border would eat into the padding. */
      .glass {
        position: relative;
        /* Holds the ring's stacking context even in the no-blur fallback below, where the
           backdrop-filter that would otherwise establish one is gone. */
        isolation: isolate;
        border-radius: var(--radius-3xl);
        background: color-mix(
          in srgb,
          var(--composer-glass-surface) var(--glass-opacity),
          transparent
        );
        -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(var(--glass-saturation));
        backdrop-filter: blur(var(--glass-blur)) saturate(var(--glass-saturation));
        box-shadow: var(--composer-shadow);
      }
      /* Mandatory companion to any glass surface: where blur is unsupported the fill goes opaque,
         because a translucent surface with nothing blurred behind it is unreadable, not subtle. */
      @supports not ((-webkit-backdrop-filter: blur(1px)) or (backdrop-filter: blur(1px))) {
        .glass {
          background: var(--composer-glass-surface);
        }
      }

      .glass::after {
        content: '';
        pointer-events: none;
        position: absolute;
        z-index: 1;
        inset: 0;
        border: 1px solid var(--composer-outline);
        border-radius: inherit;
        box-shadow: var(--composer-highlight);
      }

      /* Donor §6.4: the field itself stays unstyled and every state is read off the wrapper, so
         focus and validity are one ring rather than two competing outlines. */
      :host(:has(textarea:focus-visible)) .glass::after {
        border-color: var(--ring);
        outline: 3px solid color-mix(in srgb, var(--ring) 24%, transparent);
      }
      :host(:has(textarea[aria-invalid='true'])) .glass::after {
        border-color: color-mix(in srgb, var(--destructive) 36%, transparent);
      }
      :host(:has(textarea[aria-invalid='true']:focus-visible)) .glass::after {
        outline-color: color-mix(in srgb, var(--destructive) 16%, transparent);
      }

      /* ── The field ───────────────────────────────────────────────────────── */
      .field {
        padding: var(--space-4) var(--space-4) var(--space-2);
      }
      /* Donor compact row inset (px-3 py-2), split across our two rows: 8 above the field, 8 below
         the controls, with a 4px seam where the donor has none because it has only one row. */
      :host([state='docked']) .field {
        padding: var(--space-2) var(--space-3) var(--space-1);
      }
      .editor {
        position: relative;
      }
      textarea {
        display: block;
        width: 100%;
        min-width: 0;
        margin: 0;
        padding: 0;
        border: 0;
        outline: none;
        resize: none;
        background: transparent;
        color: var(--foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-sm);
        line-height: 1.625;
        /* Grows with its content between the donor's floor and ceiling; past the ceiling the UA
           scrolls the field itself, which is the field's own overflow and not a window scroller. */
        field-sizing: content;
        min-block-size: var(--composer-field-min-hero);
        max-block-size: var(--composer-field-max);
      }
      /* The donor's compact composer is a SINGLE truncating line beside the send control, and its
         expanded form is the 70px editor — the two forms differ in INTERNAL layout, not just in
         position, which is the whole reason the morph crossfades rather than cutting. Only the FLOOR
         moves: field-sizing and the ceiling stay on the base rule, so a docked draft still grows. */
      :host([state='docked']) textarea {
        min-block-size: var(--composer-field-min-docked);
      }
      /* The placeholder is a real overlaid element rather than the input pseudo-element: that pseudo
         is an ambient facet this window may not re-author, and the donor overlays an element too. */
      .placeholder {
        pointer-events: none;
        position: absolute;
        inset: 0;
        color: var(--placeholder);
        font-size: var(--font-size-sv3-sm);
        line-height: 1.625;
      }

      /* ── The footer: scope controls left, primary action right ──────────────── */
      .footer {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--space-2);
        min-width: 0;
        padding: 0 var(--space-4) var(--space-4);
      }
      :host([state='docked']) .footer {
        padding: 0 var(--space-3) var(--space-2);
      }
      .scopes {
        display: flex;
        align-items: center;
        gap: var(--space-1);
        min-width: 0;
      }

      button.scope-control {
        display: inline-flex;
        align-items: center;
        gap: var(--space-1);
        height: var(--space-6);
        min-width: 0;
        padding-inline: var(--control-pad-3);
        border: 1px solid transparent;
        border-radius: var(--control-radius);
        background: transparent;
        color: color-mix(in srgb, var(--muted-foreground) 70%, transparent);
        font-family: inherit;
        font-size: var(--font-size-sv3-xs);
        font-weight: 500;
        cursor: pointer;
        --control-icon-color: var(--icon-muted);
        /* Donor §6.3: a button transitions its ELEVATION only, so a hover fill lands instantly
           while the depth change eases. */
        transition: box-shadow var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      button.scope-control:hover {
        background: var(--accent-surface);
      }
      button.scope-control:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }
      /* A real glyph, not a placeholder swatch: the label evaporates on docking, so whatever is left
         has to carry the control's meaning on its own. Lucide strokes read currentColor. */
      .scope-glyph {
        flex-shrink: 0;
        color: var(--control-icon-color);
      }

      /* §5.9's compaction, in two elements: the outer carries the WIDTH (which collapses in one
         frame, so the footer reflows immediately) and the inner carries the MOTION. Docking is
         therefore an instant layout change that the morph's mid-transition crossfade covers — which
         is what that crossfade is for (§5.5) — while the reverse, and any state change made without
         a view transition, animates the label back in over the 180ms. */
      .scope-label {
        display: block;
        min-inline-size: 0;
        max-inline-size: 240px;
      }
      :host([state='docked']) .scope-label {
        max-inline-size: 0;
      }
      .scope-label-motion {
        display: block;
        width: 100%;
        min-width: 0;
        max-width: 240px;
        overflow: hidden;
        white-space: nowrap;
        text-overflow: ellipsis;
        text-align: left;
        transform-origin: left;
        transition:
          opacity var(--duration-sv3-morph) var(--ease-sv3-morph),
          transform var(--duration-sv3-morph) var(--ease-sv3-morph);
      }
      :host([state='docked']) .scope-label-motion {
        transform: translateX(-0.25rem) scaleX(0.95);
        opacity: 0;
      }

      /* ── The primary-action SLOT ──────────────────────────────────────────
         The slot holds exactly ONE control, chosen by a strict-priority state machine (Phase F2):
         Answer ▸ Stop ▸ Follow-up ▸ Send. That is the donor's own construction — an early 'return'
         renders the stop button INSTEAD of the send control ('chat/ComposerPrimaryActions.tsx:156-157')
         — and it is what makes double-firing structurally impossible rather than merely guarded: an
         unrendered Send cannot be clicked. Every occupant is the same box with the same physics, so
         the geometry is declared once for all of them; only the material differs below. */
      button.stop,
      button.send {
        position: relative;
        isolation: isolate;
        flex-shrink: 0;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        inline-size: var(--space-8);
        block-size: var(--space-8);
        padding: 0;
        overflow: hidden;
        border: 0;
        border-radius: 9999px;
        background: var(--message-action);
        color: var(--message-action-foreground);
        font-size: var(--font-size-sv3-sm);
        box-shadow:
          var(--control-inset-highlight),
          0 1px 2px 0 color-mix(in srgb, var(--message-action) 24%, transparent);
        cursor: pointer;
        transition: all var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      /* Donor 'bg-destructive/90' at rest, full strength on hover ('chat/ComposerPrimaryActions.tsx:88').
         Halting a response is destructive-tier by the donor's own colour budget: it is act-now. */
      button.stop {
        background: color-mix(in srgb, var(--destructive) 90%, transparent);
        color: var(--color-white);
        box-shadow:
          var(--control-inset-highlight),
          0 1px 2px 0 color-mix(in srgb, var(--destructive) 24%, transparent);
      }
      /* The ACT-NOW rung. The 3-colour budget spends --success on "you are the blocker", which is
         exactly what a held decision is; the control is a jump to the decision, never the decision. */
      button.send.answer {
        background: var(--success);
        color: var(--color-white);
        box-shadow:
          var(--control-inset-highlight),
          0 1px 2px 0 color-mix(in srgb, var(--success) 24%, transparent);
      }
      button.stop:hover:not(:disabled),
      button.send:hover:not(:disabled) {
        background: var(--message-action-hover);
        transform: scale(1.05);
      }
      /* Declared after the shared hover rule, or the slot's send material would win on a Stop. */
      button.stop:hover:not(:disabled) {
        background: var(--destructive);
      }
      button.send.answer:hover:not(:disabled) {
        background: var(--success);
      }
      /* Pressing does three things at once: the highlight flips from top-light to top-dark and the
         drop shadow goes, so the control reads as pressed INTO the surface rather than merely dimmed. */
      button.stop:active:not(:disabled),
      button.send:active:not(:disabled) {
        box-shadow: var(--control-inset-pressed);
      }
      button.stop:focus-visible,
      button.send:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }
      button.send:disabled {
        pointer-events: none;
        opacity: 0.3;
        box-shadow: none;
        transform: none;
      }

      @media (prefers-reduced-motion: reduce) {
        /* The fade survives, the transform does not — the donor keeps whichever half still carries
           the meaning. */
        .scope-label-motion {
          transition: opacity var(--duration-sv3-morph) var(--ease-sv3-morph);
        }
        :host([state='docked']) .scope-label-motion {
          transform: none;
        }
        button.scope-control,
        button.stop,
        button.send {
          transition: none;
        }
        button.stop:hover:not(:disabled),
        button.send:hover:not(:disabled) {
          transform: none;
        }
      }
    `,
  ];

  static properties = {
    state: { type: String, reflect: true },
    slotKind: { type: String, reflect: true, attribute: 'slot-kind' },
    slotReason: { type: String, attribute: 'slot-reason' },
    steerable: { type: Boolean, reflect: true },
    unavailableReason: { type: String, attribute: 'unavailable-reason' },
    delegateUnavailableReason: { type: String, attribute: 'delegate-unavailable-reason' },
    corpus: { attribute: false },
    draft: { state: true },
  };

  declare state: Sv3ComposerState;
  /**
   * Which control occupies the primary slot, decided by the WINDOW's `sv3PrimaryAction` state machine
   * (`sv3-run.ts`) — the composer renders the verdict and never re-derives it, so the slot's priority
   * order lives in exactly one place. The attribute is `slot-kind`, not `slot`: `slot` is a reserved
   * global attribute and would try to assign this element to a light-DOM slot.
   */
  declare slotKind: Sv3SlotKind;
  /** The reason from the same derivation, carried into the control's aria-label and title. */
  declare slotReason: string;
  /**
   * The live run accepts a mid-run submit as a STEER that joins it (an agent run does; an ask stream
   * has no such channel). It is what decides whether a submit while the slot is Stop is a refusal or
   * a directive — the window then routes it, because only the window may reach the run.
   */
  declare steerable: boolean;
  /**
   * Why the ask tier cannot be used right now, from the app's availability authority — empty means
   * available. The composer refuses its OWN send on it rather than dispatching a send the window
   * would have to un-do, which is what keeps the draft safe: a refused send never leaves here.
   */
  declare unavailableReason: string;
  /**
   * The same authority's answer for the DELEGATE tier, which is gated separately because it is gated
   * differently: an agent task needs a live model, but not an indexed document to ground an answer
   * in. The visible notice stays the ASK tier's — Enter is the composer's default and its refusal is
   * the one that must never be silent — so a window that can delegate but cannot ask says exactly
   * that, and Ctrl+Enter still works while the notice explains why Enter does not.
   */
  declare delegateUnavailableReason: string;
  /**
   * What the next question can be answered from (tempdoc 822 Phase F7; inventory E10). Handed down
   * already DERIVED by the window's one projection (`sv3-honesty.ts`), so the composer decides
   * nothing about the corpus — including whether "not reported" counts as zero, which is exactly the
   * decision that must not be made twice.
   */
  declare corpus: Sv3Corpus;
  declare draft: string;

  constructor() {
    super();
    this.state = COMPOSER_STATE_DEFAULT;
    this.slotKind = 'send';
    this.slotReason = sv3PrimaryAction({
      pendingPrompt: false,
      running: false,
      followUp: false,
    }).reason;
    this.steerable = false;
    this.unavailableReason = '';
    this.delegateUnavailableReason = '';
    this.corpus = SV3_CORPUS_UNKNOWN;
    this.draft = '';
  }

  private request(next: Sv3ComposerState): void {
    this.dispatchEvent(
      new CustomEvent<Sv3ComposerStateRequest>(SV3_COMPOSER_STATE_REQUEST, {
        detail: { state: next },
        bubbles: true,
        composed: true,
      }),
    );
  }

  private onInput(event: Event): void {
    this.draft = (event.target as HTMLTextAreaElement).value;
  }

  private onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.request('hero');
      return;
    }
    if (event.key !== 'Enter' || event.isComposing) return;
    // Shift+Enter is the newline the multi-line field would otherwise have no way to take, and it
    // wins over the modifier tiers: a reader adding a line never means to dispatch anything. An IME
    // composing a character owns the key first (`isComposing`), or a Japanese or Chinese draft is
    // sent halfway through being typed.
    if (event.shiftKey) return;
    event.preventDefault();
    // Ctrl+Enter (⌘↩ on macOS) DELEGATES; plain Enter asks. Alt is left alone — an Alt+Enter this
    // window claimed would swallow a chord the platform or the shell may already own.
    this.submit(event.ctrlKey || event.metaKey ? 'delegate' : 'ask');
  }

  /**
   * Empties the draft. The composer still OWNS it — this is the window asking for a documented state
   * change (starting a new session returns the window to its empty form, and a leftover draft would
   * be the previous session's text sitting in a fresh one), not the window reaching in to write it.
   */
  clearDraft(): void {
    this.draft = '';
  }

  /**
   * The ONE origin of a send, whichever affordance asked. An empty draft is not a send.
   *
   * Three refusals, each returning WITHOUT TOUCHING THE DRAFT. The reason is already on screen (the
   * banner, or the slot's own control), so a refusal is legible rather than silent, and the text the
   * reader typed survives. Clearing a draft the window never accepted would be destroying work.
   *
   *  - the model is unreachable;
   *  - a typed prompt is holding the run (`answer` rung) — THE structural half of donor pattern (f):
   *    a held approval or question is resolved by its own dedicated command, so there must be no path
   *    by which typing a sentence into the composer could resolve it. This is a refusal and not a
   *    disabled field, because the reader may legitimately be drafting the message they will send
   *    once the decision is made;
   *  - a response is streaming and the run takes no steer (the ask tier has no interject channel).
   *    A STEERABLE run does not refuse: the submit leaves as a directive that joins the live turn,
   *    which is the window's call to make, not this element's.
   */
  private submit(tier: Sv3ComposerTier): void {
    const query = this.draft.trim();
    if (query.length === 0) return;
    if (this.slotKind === 'answer') return;
    if (this.slotKind === 'stop' && !this.steerable) return;
    // A STEER is not a new commitment against the tier's gate — it joins a run that is already
    // running, so it is refused only by the slot rule above.
    if (this.slotKind !== 'stop') {
      const reason = tier === 'delegate' ? this.delegateUnavailableReason : this.unavailableReason;
      if (reason !== '') return;
    }
    this.dispatchEvent(
      new CustomEvent<Sv3ComposerSubmit>(SV3_COMPOSER_SUBMIT, {
        detail: { query, tier },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /** Halt the streaming response. The window owns the stream; this only says the reader asked. */
  private stop(): void {
    this.dispatchEvent(new CustomEvent(SV3_COMPOSER_STOP, { bubbles: true, composed: true }));
  }

  /** Ask the window to take the reader to the decision holding the run. Resolves nothing itself. */
  private answer(): void {
    this.dispatchEvent(new CustomEvent(SV3_COMPOSER_ANSWER, { bubbles: true, composed: true }));
  }

  render(): TemplateResult {
    const empty = this.draft.trim().length === 0;
    const docked = this.state === 'docked';
    // Soft, never `disabled`: the availability authority's contract is that the reason stays
    // reachable, and a natively-disabled control is not even focusable (`state/availability.ts:6-20`).
    const unavailable = this.unavailableReason !== '';
    return html`
      <div class="band" data-testid="sv3-composer-band">
        ${this.state === 'hero' ? this.landing() : nothing}
        ${this.unavailableReason === ''
          ? nothing
          : html`<p class="notice" id="sv3-composer-notice" role="status" data-testid="sv3-composer-notice">
              ${this.unavailableReason}
            </p>`}
        <div class="glass" data-testid="sv3-composer-shell">
          <div class="field">
            <div class="editor">
              <textarea
                rows="1"
                .value=${this.draft}
                aria-label=${COMPOSER_PLACEHOLDER}
                data-testid="sv3-composer-input"
                @input=${this.onInput}
                @keydown=${this.onKeydown}
              ></textarea>
              ${empty
                ? html`<span
                    class="placeholder"
                    aria-hidden="true"
                    data-testid="sv3-composer-placeholder"
                    >${COMPOSER_PLACEHOLDER}</span
                  >`
                : nothing}
            </div>
          </div>
          <div class="footer">
            <div class="scopes">
              ${COMPOSER_SCOPES.map(
                (scope) => html`
                  <button
                    type="button"
                    class="scope-control"
                    data-testid="sv3-composer-scope"
                    aria-label=${docked ? scope.label : nothing}
                  >
                    ${icon({ name: scope.glyph, size: SCOPE_GLYPH_SIZE, className: 'scope-glyph' })}
                    <span class="scope-label"
                      ><span class="scope-label-motion">${scope.label}</span></span
                    >
                  </button>
                `,
              )}
            </div>
            ${this.primaryAction(empty, unavailable)}
          </div>
        </div>
      </div>
    `;
  }

  /**
   * The hero intro: the headline, and what the next question can actually be answered from
   * (tempdoc 822 Phase F7; inventory E10 / tempdoc 811 C-4).
   *
   * THREE outcomes, and the third is silence. A corpus of zero offers the REMEDY instead of letting
   * the window imply it will search something; a known corpus states its size; and an `unknown` one
   * says nothing at all, because a window that has not been told the count must not fill the gap with
   * either claim. The remedy is a real navigation the window performs — the composer announces it,
   * exactly as it announces a send.
   */
  private landing(): TemplateResult {
    return html`
      <div class="landing">
        <h1 class="headline" data-testid="sv3-composer-headline">${HERO_HEADLINE}</h1>
        ${this.corpusLine()}
      </div>
    `;
  }

  private corpusLine(): TemplateResult | typeof nothing {
    if (this.corpus.kind === 'unknown') return nothing;
    if (this.corpus.kind === 'documents') {
      return html`<p class="corpus" data-testid="sv3-composer-corpus" data-kind="documents">
        Searching ${this.corpus.count.toLocaleString()}
        ${this.corpus.count === 1 ? 'file' : 'files'}
      </p>`;
    }
    return html`<p class="corpus" data-testid="sv3-composer-corpus" data-kind="empty">
      <button
        type="button"
        class="corpus-remedy"
        data-testid="sv3-composer-corpus-remedy"
        @click=${this.remedy}
      >
        ${CORPUS_ADD_FOLDERS}
      </button>
    </p>`;
  }

  private remedy(): void {
    this.dispatchEvent(
      new CustomEvent<Sv3RemedyDetail>(SV3_REMEDY, {
        detail: { target: CORPUS_REMEDY_TARGET },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * The primary slot holds EXACTLY ONE control — a switch with four arms, not four conditionals that
   * could each independently decide to render. The donor early-returns its one action
   * (`chat/ComposerPrimaryActions.tsx:156-157`) and never disables the loser behind the winner; F1
   * made that structural for Stop-vs-Send and F2 keeps the same construction across all four rungs.
   *
   * The reason string is the window's derivation, and it lands in BOTH `aria-label` and `title`: the
   * routing (Enter vs Ctrl+Enter, or that Enter now steers) is explained here and nowhere else, which
   * is what "no new chrome" means in practice — the composer gained a tier without gaining a band.
   */
  private primaryAction(empty: boolean, unavailable: boolean): TemplateResult {
    switch (this.slotKind) {
      case 'answer':
        return html`
          <button
            type="button"
            class="send answer"
            aria-label=${this.slotReason}
            title=${this.slotReason}
            data-testid="sv3-composer-answer"
            @click=${this.answer}
          >
            &#8226;
          </button>
        `;
      case 'stop':
        return html`
          <button
            type="button"
            class="stop"
            aria-label=${this.slotReason}
            title=${this.slotReason}
            data-testid="sv3-composer-stop"
            @click=${this.stop}
          >
            <!-- Donor's 12px square with a 1.5 radius (chat/ComposerPrimaryActions.tsx:96). -->
            <svg width="12" height="12" viewBox="0 0 12 12" fill="currentColor" aria-hidden="true">
              <rect x="2" y="2" width="8" height="8" rx="1.5"></rect>
            </svg>
          </button>
        `;
      case 'follow-up':
      case 'send':
        // TWO forms of the one control, and the split is not cosmetic. An empty draft keeps slice-3's
        // native `disabled` — there is nothing to route, so nothing to explain — and carries NO
        // `title`, because a browser suppresses a tooltip on a disabled element and an unreachable
        // reason is worse than none (596 face 1.1, enforced by `check-controls-a11y`). The moment
        // there IS a draft the control is live and the routing hint becomes both true and reachable.
        return empty
          ? html`
              <button
                type="button"
                class="send"
                aria-label="Send"
                disabled
                data-unavailable=${String(unavailable)}
                data-testid="sv3-composer-send"
              >
                &#8593;
              </button>
            `
          : html`
              <button
                type="button"
                class="send"
                aria-label=${this.slotReason}
                title=${this.slotReason}
                data-unavailable=${String(unavailable)}
                aria-describedby=${unavailable ? 'sv3-composer-notice' : nothing}
                data-testid="sv3-composer-send"
                @click=${() => this.submit('ask')}
              >
                &#8593;
              </button>
            `;
    }
  }
}

customElements.define('jf-sv3-composer', Sv3Composer);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-composer': Sv3Composer;
  }
}
