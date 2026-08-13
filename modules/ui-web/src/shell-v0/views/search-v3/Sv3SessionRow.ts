// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-session-row — the Search v3 sidebar's session row (tempdoc 822 slice 2).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * ONE surface model for every row, per the design spec: the surface encodes INTERACTION (active,
 * selected, hover) and the content encodes STATUS. The spec tried the other way — elevated cards for live
 * threads, plain rows for settled ones — and recorded that it produced neither a hierarchy nor a
 * reliable hover cue.
 *
 * Precedence is written into the selectors, not into declaration order: the hover rule is GUARDED
 * out of the claimed states (`:host(:not([active]):not([selected]))`) so exactly one fill can ever
 * win. Declaration order alone would leave a later-added rule free to stack two fills.
 *
 * Status colour is a 3-budget: act-now, in-motion, broken. A resting row spends none
 * of it — its slot carries a muted timestamp instead, so colour keeps meaning "attend to this".
 *
 * The trailing slot SWAPS (tempdoc 822 Phase F3): status at rest, the pin action on
 * hover or keyboard focus, hidden state out of flow, one width floor so nothing jitters. With one
 * exception, which is the whole reason the rule is quotable: an act-now or broken status never
 * yields — the spec's PR badge stays visible and clickable while the row is hovered, and only the
 * time label yields. Those two statuses are this window's honesty facts, so the pin appears beside
 * them instead of on top of them.
 *
 * Side-effect registers <jf-sv3-session-row>.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { icon } from '../../components/Icon.js';
import { sv3Shared } from './sv3-shared-styles.js';
import type { Sv3RowStatus } from './fixtures.js';

/** Screen-reader text for the three states that spend a colour; a resting row announces nothing. */
const STATUS_LABEL: Record<Exclude<Sv3RowStatus, 'resting'>, string> = {
  'act-now': 'New results',
  'in-motion': 'Searching',
  broken: 'Failed',
};

/** The row asks to be pinned or unpinned; the sidebar names which row, the window owns the list. */
export const SV3_SESSION_PIN_TOGGLE = 'sv3-session-pin-toggle';

/**
 * The rename triad (tempdoc 822 Phase F5). The row raises intent and holds no title of its own: the
 * one title lives in `sv3-sessions.ts`, so an edit in flight cannot become a second copy of it.
 */
export const SV3_SESSION_RENAME_START = 'sv3-session-rename-start';
export const SV3_SESSION_RENAME_COMMIT = 'sv3-session-rename-commit';
export const SV3_SESSION_RENAME_CANCEL = 'sv3-session-rename-cancel';

export interface Sv3SessionRenameCommit {
  readonly title: string;
}

/** Lucide "bookmark" — the shared icon set's pin glyph, at the slim row's control size. */
const PIN_GLYPH_SIZE = 14;

export class Sv3SessionRow extends JfElement {
  static styles = [
    sv3Shared,
    css`
      :host {
        display: block;
        /* The containing block for the pin action, which overlays the row's trailing slot. The pin
           is a SIBLING of the row button rather than a child: a button inside a button is invalid,
           and the claim control must stay the row's one big target. */
        position: relative;
      }

      button.row {
        display: flex;
        align-items: center;
        gap: var(--sidebar-control-gap);
        width: 100%;
        /* The spec's SLIM SESSION row (h-9), not the menu-button ladder: the sidebar-comparison
           pass found the row had been built off the wrong referent, and these rows are sessions. */
        height: var(--space-9);
        padding-inline: var(--sidebar-row-content-inset);
        padding-block: 0.375rem;
        border: 0;
        border-radius: var(--control-radius);
        background: transparent;
        color: var(--sidebar-foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-sm);
        font-weight: 400;
        text-align: left;
        cursor: pointer;
        overflow: hidden;
        user-select: none;
        /* A long session list stays cheap without a virtualizer: the browser skips rendering work
           for rows outside the viewport, and the intrinsic size keeps the scrollbar honest. */
        content-visibility: auto;
        contain-intrinsic-size: auto var(--space-9);
        transition:
          background-color var(--duration-sv3-micro) var(--ease-sv3-enter),
          opacity var(--duration-sv3-micro) var(--ease-sv3-enter);
      }

      /* ── Surface: interaction only, in precedence order ──────────────────── */
      :host([active]) button.row {
        background: var(--sidebar-row-active);
        color: var(--sidebar-foreground);
      }
      :host([selected]:not([active])) button.row {
        background: var(--sidebar-row-selected);
        color: var(--sidebar-foreground);
      }
      :host(:not([active]):not([selected])) button.row:hover {
        background: var(--sidebar-row-hover);
      }
      button.row:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: -2px;
      }
      /* In-flight is ORTHOGONAL to the fill ladder: it dims the whole row and lifts on hover,
         and it yields entirely once the row is the claimed one. */
      :host([inflight]:not([active]):not([selected])) button.row {
        opacity: 0.7;
      }
      :host([inflight]:not([active]):not([selected])) button.row:hover {
        opacity: 1;
      }

      /* ── Content: the source glyph ───────────────────────────────────────── */
      .glyph {
        inline-size: var(--space-4);
        block-size: var(--space-4);
        flex-shrink: 0;
        border-radius: var(--radius-sm);
        background: var(--sidebar-control-surface);
        transition:
          opacity var(--duration-sv3-micro) var(--ease-sv3-enter),
          filter var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      /* Settled history recedes: dimmed glyph at rest, restored on hover so the tail stays
         scannable when you are hunting through it. */
      :host(:not([active])) .glyph {
        opacity: 0.4;
        filter: grayscale(1);
      }
      :host(:not([active])) button.row:hover .glyph {
        opacity: 1;
        filter: grayscale(0);
      }

      /* ── Content: the title ladder — emphasis is foreground ALPHA, never a hue ─ */
      .row-label {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        color: color-mix(in srgb, var(--foreground) 90%, transparent);
      }
      :host([unread]) .row-label {
        color: var(--foreground);
        font-weight: 500;
      }
      :host([status='broken']) .row-label {
        color: color-mix(in srgb, var(--foreground) 95%, transparent);
      }
      :host([receded]:not([active]):not([selected])) .row-label {
        color: color-mix(in srgb, var(--sidebar-muted-foreground) 75%, transparent);
        font-weight: 400;
      }
      :host([receded]:not([active]):not([selected])) button.row:hover .row-label {
        color: var(--sidebar-foreground);
      }

      /* ── Content: the status / meta slot, and the action it swaps for ────── */
      .status-slot {
        position: relative;
        margin-left: auto;
        display: flex;
        align-items: center;
        justify-content: flex-end;
        block-size: var(--space-5);
        /* A floor on the slot so rows do not jitter as their contents change width. */
        min-inline-size: var(--space-8);
        flex-shrink: 0;
        font-size: var(--font-size-sv3-xs);
      }
      .slot-content {
        display: flex;
        align-items: center;
        transition: opacity var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      /* THE SWAP: the visible state owns the slot's width — status at rest, the action
         on hover or keyboard focus. The hidden state leaves the FLOW (absolute) so the title can
         reclaim the width without the two states overlapping, and the slot's min floor is what keeps
         the row from jittering as they trade places. The status-state guards are the exception rule
         below, written into the selector so a later rule cannot quietly stack on top of them.

         Each trigger is its OWN rule, and none of them nests :has() inside :host() — Chrome rejects
         that nesting as a syntax error, and an invalid member takes its whole selector list down
         with it, which is how the first cut of this swap passed its CSS-text unit tests while doing
         nothing at all in the browser (live-measured, 822 F3). */
      :host(:hover:not([compact]):not([status='act-now']):not([status='broken'])) .slot-content {
        position: absolute;
        inset-inline-end: 0;
        opacity: 0;
      }
      /* Keyboard, in two halves: the row itself focused, and the action focused. The pin is a
         FOLLOWING SIBLING of the row button, so the second half is a :has() on a plain element. */
      :host(:not([compact]):not([status='act-now']):not([status='broken']))
        button.row:focus-visible
        .slot-content {
        position: absolute;
        inset-inline-end: 0;
        opacity: 0;
      }
      :host(:not([compact]):not([status='act-now']):not([status='broken']))
        button.row:has(~ button.pin:focus-visible)
        .slot-content {
        position: absolute;
        inset-inline-end: 0;
        opacity: 0;
      }
      /* THE NEVER-YIELDS EXCEPTION (the spec's counterpoint: the PR badge "must remain visible AND
         clickable while the row is hovered. Only the time/jump label yields"). Act-now and broken are
         this window's PR badge: one says the run is blocked on the reader, the other that it failed,
         and a fact that only shows itself when the pointer is elsewhere is not a fact the reader can
         rely on. So the status keeps its place and the pin appears BESIDE it, in a gutter reserved at
         rest — reserving it on hover instead would move the dot, which is the jitter the floor
         exists to prevent. */
      :host([status='act-now']) .status-slot,
      :host([status='broken']) .status-slot {
        padding-inline-end: var(--space-7);
      }

      button.pin {
        position: absolute;
        inset-inline-end: var(--sidebar-row-content-inset);
        top: 50%;
        transform: translateY(-50%);
        display: flex;
        align-items: center;
        justify-content: center;
        inline-size: var(--space-6);
        block-size: var(--space-6);
        padding: 0;
        border: 0;
        border-radius: var(--control-radius);
        background: transparent;
        color: var(--icon-muted);
        cursor: pointer;
        opacity: 0;
        /* At rest the action is not a pointer target: the whole row is one claim target, and an
           invisible button that swallowed the click would make the row's edge lie. Keyboard reach is
           unaffected — the button stays in the tab order and reveals itself on focus. */
        pointer-events: none;
        transition: opacity var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      :host(:hover) button.pin {
        opacity: 1;
        pointer-events: auto;
      }
      button.row:focus-visible ~ button.pin,
      button.pin:focus-visible {
        opacity: 1;
        pointer-events: auto;
      }
      button.pin:hover {
        background: var(--sidebar-row-active);
        color: var(--sidebar-foreground);
      }
      button.pin:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: -1px;
      }
      /* Pressed state as foreground weight, not a hue: the 3-colour budget is for status only. */
      :host([pinned]) button.pin {
        color: var(--sidebar-foreground);
      }
      .meta {
        color: var(--secondary-label);
        font-variant-numeric: tabular-nums;
      }
      .dot-box {
        position: relative;
        display: inline-flex;
        inline-size: var(--space-3);
        block-size: var(--space-3);
        align-items: center;
        justify-content: center;
      }
      .dot {
        inline-size: var(--space-2);
        block-size: var(--space-2);
        border-radius: 50%;
      }
      .ping {
        position: absolute;
        inset: 0;
        border-radius: 50%;
      }
      :host([status='act-now']) .dot {
        background: var(--success);
      }
      :host([status='in-motion']) .dot {
        background: var(--warning);
      }
      :host([status='broken']) .dot {
        background: var(--destructive);
      }
      :host([status='in-motion']) .ping {
        background: color-mix(in srgb, var(--warning) 60%, transparent);
      }

      /* ── COMPACT: the row on the 3rem icon rail (tempdoc 822 Phase F5) ───────────────────────
         The spec's icon mode squares the menu button
         (group-data-[collapsible=icon]:size-8 plus its content inset) and HIDES the row's action
         (group-data-[collapsible=icon]:hidden on the menu action). The label goes with them:
         48px minus the panel's two 8px insets is not a place a title can be read.

         WHAT DOES NOT GO: the status dot. Act-now means the run is blocked on the reader and broken
         means it failed — the same two facts the never-yields exception above refuses to let a hover
         take away, and collapsing the rail is no better a reason than hovering was. So the slot
         leaves the flow and becomes a corner badge on the square, and only the RESTING slot's
         timestamp is dropped (it is the one thing here that spends no colour and claims nothing). */
      :host([compact]) button.row {
        inline-size: var(--space-8);
        /* The rail's 48px is a TOTAL: its 8px insets and 1px border come out of it (border-box), so
           the content column is 31px and the spec's 32px square would overflow it by one pixel.
           The square is still the declared size; the column is what bounds it. */
        max-inline-size: 100%;
        block-size: var(--space-8);
        padding-inline: 0;
        justify-content: center;
        contain-intrinsic-size: auto var(--space-8);
      }
      :host([compact]) .row-label,
      :host([compact]) .meta,
      :host([compact]) button.pin {
        display: none;
      }
      :host([compact]) .status-slot {
        position: absolute;
        inset-block-start: 0;
        inset-inline-end: 0;
        min-inline-size: 0;
        block-size: auto;
        padding-inline-end: 0;
      }

      /* ── RENAMING: the spec's inline input, in the row's own box ─────────────────────────────
         The spec swaps its title span for an autoFocused input in place.
         Ours swaps the whole ROW ELEMENT with it, because our row is a native button and an input
         inside a button is invalid content — and because a row that is being renamed is not a
         navigation target: the reader is editing it, not going into it. */
      .row-static {
        display: flex;
        align-items: center;
        gap: var(--sidebar-control-gap);
        width: 100%;
        height: var(--space-9);
        padding-inline: var(--sidebar-row-content-inset);
        padding-block: 0.375rem;
        border-radius: var(--control-radius);
        background: var(--sidebar-row-active);
        overflow: hidden;
      }
      input.rename {
        min-width: 0;
        flex: 1 1 auto;
        border: 1px solid var(--input);
        border-radius: var(--radius-sm);
        padding-inline: var(--space-1);
        background: var(--card);
        color: var(--card-foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-sm);
        font-weight: 500;
        outline: none;
      }
      input.rename:focus {
        border-color: var(--foreground);
      }

      @media (prefers-reduced-motion: reduce) {
        button.row,
        button.pin,
        .slot-content,
        .glyph {
          transition: none;
        }
      }
    `,
  ];

  static properties = {
    label: { type: String, attribute: false },
    meta: { type: String, attribute: false },
    status: { type: String, reflect: true },
    active: { type: Boolean, reflect: true },
    selected: { type: Boolean, reflect: true },
    receded: { type: Boolean, reflect: true },
    unread: { type: Boolean, reflect: true },
    inflight: { type: Boolean, reflect: true },
    pinned: { type: Boolean, reflect: true },
    compact: { type: Boolean, reflect: true },
    renaming: { type: Boolean, reflect: true },
  };

  declare label: string;
  declare meta: string;
  declare status: Sv3RowStatus;
  declare active: boolean;
  declare selected: boolean;
  declare receded: boolean;
  declare unread: boolean;
  declare inflight: boolean;
  declare pinned: boolean;
  /** The sidebar is on its collapsed icon rail; the row is a 32px square. */
  declare compact: boolean;
  /** The reader is editing this row's title (tempdoc 822 Phase F5). */
  declare renaming: boolean;

  constructor() {
    super();
    this.label = '';
    this.meta = '';
    this.status = 'resting';
    this.active = false;
    this.selected = false;
    this.receded = false;
    this.unread = false;
    this.inflight = false;
    this.pinned = false;
    this.compact = false;
    this.renaming = false;
  }

  /**
   * The pin is a SECOND action inside one row, so the claim must not also fire: a reader pinning a
   * conversation is not asking to be taken into it.
   */
  private togglePin(event: Event): void {
    event.stopPropagation();
    this.dispatchEvent(new CustomEvent(SV3_SESSION_PIN_TOGGLE, { bubbles: true, composed: true }));
  }

  /**
   * The spec's rename trigger: a DOUBLE-CLICK on the row, with its two
   * guards — a modified double-click is a selection gesture, and a double-click that landed on a
   * control inside the row belongs to that control.
   */
  private onRowDoubleClick(event: MouseEvent): void {
    if (this.renaming || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    if ((event.target as HTMLElement | null)?.closest('button.pin') !== null) return;
    event.preventDefault();
    this.dispatchEvent(
      new CustomEvent(SV3_SESSION_RENAME_START, { bubbles: true, composed: true }),
    );
  }

  /**
   * The keyboard half of the same trigger. The spec reaches rename by a row MENU it has and this
   * window does not, so the gesture is F2 — the platform convention for
   * renaming the focused item, and the only way a pointerless reader gets to the affordance at all.
   */
  private onRowKeydown(event: KeyboardEvent): void {
    if (event.key !== 'F2' || this.renaming) return;
    event.preventDefault();
    this.dispatchEvent(
      new CustomEvent(SV3_SESSION_RENAME_START, { bubbles: true, composed: true }),
    );
  }

  /** Enter commits, Escape cancels, per the design spec. */
  private onRenameKeydown(event: KeyboardEvent): void {
    // The row list is inside the window's own key handling; an edit's keys are the edit's alone.
    event.stopPropagation();
    if (event.key === 'Enter') {
      event.preventDefault();
      this.commitRename(event.currentTarget as HTMLInputElement);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      this.renameCommitted = true;
      this.dispatchEvent(
        new CustomEvent(SV3_SESSION_RENAME_CANCEL, { bubbles: true, composed: true }),
      );
    }
  }

  /** Blur commits what is there, unless a key already settled it, per the design spec. */
  private onRenameBlur(event: FocusEvent): void {
    if (this.renameCommitted) return;
    this.commitRename(event.currentTarget as HTMLInputElement);
  }

  /** Latch so the blur that FOLLOWS an Enter or an Escape does not commit a second time. */
  private renameCommitted = false;

  private commitRename(input: HTMLInputElement): void {
    this.renameCommitted = true;
    this.dispatchEvent(
      new CustomEvent<Sv3SessionRenameCommit>(SV3_SESSION_RENAME_COMMIT, {
        detail: { title: input.value },
        bubbles: true,
        composed: true,
      }),
    );
  }

  protected override updated(changed: Map<string, unknown>): void {
    if (!changed.has('renaming')) return;
    if (!this.renaming) return;
    this.renameCommitted = false;
    // The spec's `autoFocus` + `onFocus → select()`: the edit opens with
    // the old title selected, so typing replaces it and Escape still has something to restore to.
    const input = this.shadowRoot?.querySelector<HTMLInputElement>('input.rename');
    input?.focus();
    input?.select();
  }

  /**
   * The row while it is being edited. The input's value is UNCONTROLLED (`.value` set once, on the
   * first render of the edit) — the committed title is the session's, and a keystroke round-tripping
   * through the window would put the caret at the mercy of a re-render.
   */
  private renameRow(): TemplateResult {
    return html`
      <div class="row-static" data-testid="sv3-session-row-renaming">
        <span class="glyph" aria-hidden="true"></span>
        <input
          class="rename"
          type="text"
          aria-label="Conversation title"
          .value=${this.label}
          data-testid="sv3-session-row-rename-input"
          @keydown=${this.onRenameKeydown}
          @blur=${this.onRenameBlur}
        />
      </div>
    `;
  }

  render(): TemplateResult {
    if (this.renaming) return this.renameRow();
    const colored = this.status !== 'resting';
    return html`
      <button
        type="button"
        class="row"
        aria-current=${this.active ? 'true' : nothing}
        aria-label=${this.compact && this.label !== '' ? this.label : nothing}
        title=${this.compact && this.label !== '' ? this.label : nothing}
        data-testid="sv3-session-row-button"
        @dblclick=${this.onRowDoubleClick}
        @keydown=${this.onRowKeydown}
      >
        <span class="glyph" aria-hidden="true"></span>
        <span class="row-label">${this.label}</span>
        <span class="status-slot">
          <span class="slot-content" data-testid="sv3-session-row-slot">
            ${colored
              ? html`
                  <span
                    class="dot-box"
                    role="img"
                    aria-label=${STATUS_LABEL[this.status as Exclude<Sv3RowStatus, 'resting'>]}
                    data-testid="sv3-session-row-status"
                  >
                    ${this.status === 'in-motion'
                      ? html`<span class="ping sv3-anim-status-ping" aria-hidden="true"></span>`
                      : nothing}
                    <span class="dot"></span>
                  </span>
                `
              : html`<span class="meta" data-testid="sv3-session-row-meta">${this.meta}</span>`}
          </span>
        </span>
      </button>
      <button
        type="button"
        class="pin"
        aria-pressed=${this.pinned ? 'true' : 'false'}
        aria-label=${this.label === '' ? 'Pin conversation' : `Pin ${this.label}`}
        data-testid="sv3-session-row-pin"
        @click=${this.togglePin}
      >
        ${icon({ name: 'bookmark', size: PIN_GLYPH_SIZE })}
      </button>
    `;
  }
}

customElements.define('jf-sv3-session-row', Sv3SessionRow);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-session-row': Sv3SessionRow;
  }
}
