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
 * `sv3-composer-submit`) and the window decides what that means. It does not dock itself and it does
 * not know a search store exists — the alternative would put a second issuance site here.
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
  HERO_HEADLINE,
  type Sv3ComposerState,
} from './fixtures.js';

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

export interface Sv3ComposerSubmit {
  readonly query: string;
}

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

      .headline {
        position: absolute;
        inset-inline: 0;
        bottom: 100%;
        margin: 0;
        padding-bottom: var(--space-8);
        color: var(--foreground);
        font-size: var(--font-size-sv3-display);
        font-weight: 400;
        letter-spacing: -0.025em;
        text-align: center;
        text-wrap: balance;
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
        background: var(--accent);
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
      button.send:hover:not(:disabled) {
        background: var(--message-action-hover);
        transform: scale(1.05);
      }
      /* Pressing does three things at once: the highlight flips from top-light to top-dark and the
         drop shadow goes, so the control reads as pressed INTO the surface rather than merely dimmed. */
      button.send:active:not(:disabled) {
        box-shadow: var(--control-inset-pressed);
      }
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
        button.send {
          transition: none;
        }
        button.send:hover:not(:disabled) {
          transform: none;
        }
      }
    `,
  ];

  static properties = {
    state: { type: String, reflect: true },
    draft: { state: true },
  };

  declare state: Sv3ComposerState;
  declare draft: string;

  constructor() {
    super();
    this.state = COMPOSER_STATE_DEFAULT;
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
    // Enter sends; Shift+Enter is the newline the multi-line field would otherwise have no way to
    // take. An IME composing a character owns the key first (`isComposing`), or a Japanese or
    // Chinese draft is sent halfway through being typed.
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      this.submit();
    }
  }

  /**
   * Empties the draft. The composer still OWNS it — this is the window asking for a documented state
   * change (starting a new session returns the window to its empty form, and a leftover draft would
   * be the previous session's text sitting in a fresh one), not the window reaching in to write it.
   */
  clearDraft(): void {
    this.draft = '';
  }

  /** The ONE origin of a send, whichever affordance asked. An empty draft is not a send. */
  private submit(): void {
    const query = this.draft.trim();
    if (query.length === 0) return;
    this.dispatchEvent(
      new CustomEvent<Sv3ComposerSubmit>(SV3_COMPOSER_SUBMIT, {
        detail: { query },
        bubbles: true,
        composed: true,
      }),
    );
  }

  render(): TemplateResult {
    const empty = this.draft.trim().length === 0;
    const docked = this.state === 'docked';
    return html`
      <div class="band" data-testid="sv3-composer-band">
        ${this.state === 'hero'
          ? html`<h1 class="headline" data-testid="sv3-composer-headline">${HERO_HEADLINE}</h1>`
          : nothing}
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
            <button
              type="button"
              class="send"
              aria-label="Search"
              ?disabled=${empty}
              data-testid="sv3-composer-send"
              @click=${this.submit}
            >
              &#8593;
            </button>
          </div>
        </div>
      </div>
    `;
  }
}

customElements.define('jf-sv3-composer', Sv3Composer);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-composer': Sv3Composer;
  }
}
