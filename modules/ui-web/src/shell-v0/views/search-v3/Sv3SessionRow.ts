// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-session-row — the Search v3 sidebar's session row (tempdoc 822 slice 2).
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * ONE surface model for every row (donor §6.1): the surface encodes INTERACTION (active, selected,
 * hover) and the content encodes STATUS. The donor tried the other way — elevated cards for live
 * threads, plain rows for settled ones — and recorded that it produced neither a hierarchy nor a
 * reliable hover cue.
 *
 * Precedence is written into the selectors, not into declaration order: the hover rule is GUARDED
 * out of the claimed states (`:host(:not([active]):not([selected]))`) so exactly one fill can ever
 * win. Declaration order alone would leave a later-added rule free to stack two fills.
 *
 * Status colour is a 3-budget (donor §6.2): act-now, in-motion, broken. A resting row spends none
 * of it — its slot carries a muted timestamp instead, so colour keeps meaning "attend to this".
 *
 * Side-effect registers <jf-sv3-session-row>.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { sv3Shared } from './sv3-shared-styles.js';
import type { Sv3RowStatus } from './fixtures.js';

/** Screen-reader text for the three states that spend a colour; a resting row announces nothing. */
const STATUS_LABEL: Record<Exclude<Sv3RowStatus, 'resting'>, string> = {
  'act-now': 'New results',
  'in-motion': 'Searching',
  broken: 'Failed',
};

export class Sv3SessionRow extends JfElement {
  static styles = [
    sv3Shared,
    css`
      :host {
        display: block;
      }

      button.row {
        display: flex;
        align-items: center;
        gap: var(--sidebar-control-gap);
        width: 100%;
        /* Donor §6.1's SLIM SESSION row (h-9), not §3.2's menu-button ladder: the sidebar-comparison
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

      /* ── Content: the status / meta slot ─────────────────────────────────── */
      .status-slot {
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

      @media (prefers-reduced-motion: reduce) {
        button.row,
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
  };

  declare label: string;
  declare meta: string;
  declare status: Sv3RowStatus;
  declare active: boolean;
  declare selected: boolean;
  declare receded: boolean;
  declare unread: boolean;
  declare inflight: boolean;

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
  }

  render(): TemplateResult {
    const colored = this.status !== 'resting';
    return html`
      <button
        type="button"
        class="row"
        aria-current=${this.active ? 'true' : nothing}
        data-testid="sv3-session-row-button"
      >
        <span class="glyph" aria-hidden="true"></span>
        <span class="row-label">${this.label}</span>
        <span class="status-slot">
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
