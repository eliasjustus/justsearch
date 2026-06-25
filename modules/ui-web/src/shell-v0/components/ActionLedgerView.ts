// SPDX-License-Identifier: Apache-2.0
/**
 * `<jf-action-ledger>` — the Outcome face's unified activity read-view (tempdoc 550 C1, FE).
 *
 * Renders one chronological, originator-attributed stream over BOTH the backend action
 * ledger (`GET /api/action-ledger` — authoritative Operation + Navigation records) and the
 * FE-local Effect Journal, via {@link ActionLedgerClient.unifiedActivity}. This is the
 * "show me everything that happened this session (and who did it)" view — the receipt /
 * timeline / trust-audit read 550's Outcome face describes, as read-views over one ledger
 * rather than separate stores.
 *
 * Refreshes on connect and whenever a new FE Effect is journaled (so freshly-applied
 * local effects appear immediately without re-fetching the backend on every keystroke).
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { openActionLedgerStream, type UnifiedActionEntry } from '../operations/ActionLedgerClient.js';
import { collapseBursts } from '../projections/boundedProjection.js';
import { newestFirst } from '../primitives/eventStreamProjection.js';
// tempdoc 558 §S1 — the ONE shared ledger-row projection (also used by the retrospective History tab).
import { renderEventRow, renderEventWhen, eventRowStyles } from './eventRow.js';
// tempdoc 558 §E1 — reuse the FilterChip atom (the same facet primitive the advisory drawer composes).
import './FilterChip.js';
// tempdoc 612 §UX/§CI R1 — the ONE shared "seen" authority (577 §2.14). Activity is "any 'new since you
// looked' marker" the cursor was built to serve; it READS the one cursor (a 2nd cursor would re-create the
// two-authorities bug 577 removed) and offers a "mark all read" affordance that advances that one boundary.
import {
  getSeenCursor,
  markSeen,
  subscribeSeenCursor,
} from '../substrates/recall/recallCursor.js';

/** A collapsed indexing burst — N adjacent same-collection index rows summarized (tempdoc 550 III(b)). */
interface IndexBurstSummary {
  readonly summary: true;
  readonly groupKey: string;
  readonly count: number;
  // tempdoc 558 Deepening 3 — the burst is still a projection of records that happened at a time; carry
  // the most-recent occurredAt of the collapsed group so the summary row is not the one row with no time.
  readonly occurredAt: string;
}

function isBurst(row: UnifiedActionEntry | IndexBurstSummary): row is IndexBurstSummary {
  return (row as IndexBurstSummary).summary === true;
}

export class ActionLedgerView extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
    entries: { attribute: false },
    // tempdoc 558 §E1 — client-side facets over the streamed entries (empty = all).
    filterOriginators: { state: true },
    filterOutcomes: { state: true },
    // tempdoc 613 §6/§10 — the default Activity feed is a CURATED projection: routine direct-user
    // navigation (the user already witnessed it) is excluded unless this toggle reveals it.
    showRoutine: { state: true },
    // tempdoc 612 §UX — the seen-cursor snapshot, mirrored into reactive state so the feed re-renders its
    // "new since you looked" marks when any recall surface (incl. the AI digest) advances the one cursor.
    seenCursor: { state: true },
  } as const;

  declare apiBase: string;
  declare entries: UnifiedActionEntry[];
  declare filterOriginators: string[];
  declare filterOutcomes: string[];
  declare showRoutine: boolean;
  declare seenCursor: string;

  /** Injected only by tests; production uses the real EventSource via openActionLedgerStream. */
  eventSourceFactory?: (url: string) => EventSource;

  private stopStream: (() => void) | null = null;
  private stopSeenSub: (() => void) | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.entries = [];
    this.filterOriginators = [];
    this.filterOutcomes = [];
    this.showRoutine = false;
    this.seenCursor = getSeenCursor();
  }

  /** A foreground row the user has not yet seen (newer than the one shared seen-cursor). Routine rows
   *  are never marked — they are hidden noise, not "what happened that you should know about". */
  private isNewRow(e: UnifiedActionEntry): boolean {
    // Reads the reactive `seenCursor` state (kept in sync with the one shared cursor) so render is a pure
    // function of state. ISO-8601 `Z` timestamps compare lexically === chronologically (per recallCursor).
    return !e.isRoutine && e.occurredAt > this.seenCursor;
  }

  // tempdoc 558 §S1 — the row styles are single-sourced in eventRow.ts (shared with the History tab);
  // this surface adds only its host + empty-state styles + the burst-summary chrome.
  static styles = [
    eventRowStyles,
    css`
      :host {
        display: block;
        font-size: var(--font-size-sm);
      }
      .empty {
        opacity: 0.6;
        padding: 0.5rem 0;
      }
      /* tempdoc 612 §L.2 #4 — inline reveal affordance on the all-routine curated-empty state. */
      .reveal-routine {
        margin-left: 0.4rem;
        padding: 0;
        border: 0;
        background: none;
        color: var(--accent, currentColor);
        font: inherit;
        text-decoration: underline;
        cursor: pointer;
      }
      /* tempdoc 612 §UX — the "N new since you looked" affordance + its "mark all read" link. */
      .new-count {
        font-size: var(--font-size-xs);
        color: var(--accent-tint, var(--text-primary));
        font-weight: 600;
      }
      .mark-read {
        padding: 0;
        border: 0;
        background: none;
        color: var(--accent, currentColor);
        font: inherit;
        font-size: var(--font-size-xs);
        text-decoration: underline;
        cursor: pointer;
      }
      /* tempdoc 558 §E1 — the facet bar (who / outcome), composed from the FilterChip atom. */
      .filters {
        display: flex;
        flex-wrap: wrap;
        gap: 0.75rem;
        padding: 0 0 0.5rem 0;
      }
      .filters .group {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.35rem;
      }
      .filters .group-label {
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
      }
      .chip-count {
        opacity: 0.6;
      }
    `,
  ];

  override connectedCallback(): void {
    super.connectedCallback();
    // One live read path: openActionLedgerStream delivers the initial snapshot frame AND every
    // subsequent backend row, and re-folds the FE Effect Journal on each change — so there is no
    // separate snapshot fetch to drift from the live stream. (tempdoc 550 thesis I: snapshot and
    // live are two reads of one projection, not two code paths.)
    this.stopStream = openActionLedgerStream({
      apiBase: this.apiBase,
      ...(this.eventSourceFactory ? { eventSourceFactory: this.eventSourceFactory } : {}),
      onActivity: (rows) => {
        this.entries = rows;
        this.requestUpdate();
      },
    });
    // tempdoc 612 §UX — mirror the one shared seen-cursor into reactive state, so the "new" marks
    // re-fold whenever the cursor advances (here, or from the AI digest's "mark as seen" — one boundary).
    this.seenCursor = getSeenCursor();
    this.stopSeenSub = subscribeSeenCursor(() => {
      this.seenCursor = getSeenCursor();
    });
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.stopStream?.();
    this.stopStream = null;
    this.stopSeenSub?.();
    this.stopSeenSub = null;
  }

  /** tempdoc 612 §UX — advance the one shared seen-cursor to the newest foreground row (the standard
   *  "mark all read"). Monotonic in {@link markSeen}; clears the marks here AND any other recall surface. */
  private markAllRead(): void {
    let newest = '';
    for (const e of this.entries) {
      if (!e.isRoutine && e.occurredAt > newest) newest = e.occurredAt;
    }
    if (newest) markSeen(newest);
  }

  /** tempdoc 558 §E1 — toggle a facet value in/out of the active filter for its dimension. */
  private toggleFilter(dim: 'originator' | 'outcome', value: string): void {
    const cur = dim === 'originator' ? this.filterOriginators : this.filterOutcomes;
    const next = cur.includes(value) ? cur.filter((v) => v !== value) : [...cur, value];
    if (dim === 'originator') this.filterOriginators = next;
    else this.filterOutcomes = next;
  }

  /** An entry passes when it matches every ACTIVE facet (empty facet = no constraint). */
  private matchesFilter(e: UnifiedActionEntry): boolean {
    if (this.filterOriginators.length > 0 && !this.filterOriginators.includes(e.originator)) return false;
    if (this.filterOutcomes.length > 0 && !(e.outcome && this.filterOutcomes.includes(e.outcome)))
      return false;
    return true;
  }

  /** Distinct facet values + counts over the UNFILTERED entries (stable chip set showing totals). */
  private facet(
    pick: (e: UnifiedActionEntry) => string | undefined,
  ): Array<{ value: string; count: number }> {
    const counts = new Map<string, number>();
    for (const e of this.entries) {
      const v = pick(e);
      if (v) counts.set(v, (counts.get(v) ?? 0) + 1);
    }
    return [...counts.entries()].map(([value, count]) => ({ value, count }));
  }

  private renderFilters(): TemplateResult {
    const originators = this.facet((e) => e.originator);
    const outcomes = this.facet((e) => e.outcome);
    // Only show a facet that actually discriminates (>1 value) — no chrome for a trivial single value.
    const showWho = originators.length > 1;
    const showOutcome = outcomes.length > 1;
    // tempdoc 613 §6/§10 + 612 §3/§L — how many routine direct-user rows (navigation + witnessed
    // local-ack/preference effects + insignificant operations) are excluded from the curated default.
    const routineCount = this.entries.filter((e) => e.isRoutine).length;
    // tempdoc 612 §UX — foreground rows newer than the one shared seen-cursor: "what happened that you
    // should know about, since you last looked". A "mark all read" advances the shared boundary.
    const newCount = this.entries.filter((e) => this.isNewRow(e)).length;
    if (!showWho && !showOutcome && routineCount === 0 && newCount === 0) return html`${''}`;
    const chip = (
      dim: 'originator' | 'outcome',
      selected: string[],
      f: { value: string; count: number },
    ): TemplateResult =>
      html`<jf-filter-chip
        ?active=${selected.includes(f.value)}
        @click=${() => this.toggleFilter(dim, f.value)}
      >
        ${f.value}
        <span class="chip-count">(${f.count})</span>
      </jf-filter-chip>`;
    return html`<div class="filters" data-testid="ledger-filters">
      ${newCount > 0
        ? html`<div class="group">
            <span class="group-label">new</span>
            <span class="new-count" data-testid="ledger-new-count"
              >${newCount} since you looked</span
            >
            <button
              type="button"
              class="mark-read"
              data-testid="ledger-mark-read"
              @click=${() => this.markAllRead()}
            >
              mark all read
            </button>
          </div>`
        : ''}
      ${showWho
        ? html`<div class="group">
            <span class="group-label">who</span>
            ${originators.map((f) => chip('originator', this.filterOriginators, f))}
          </div>`
        : ''}
      ${showOutcome
        ? html`<div class="group">
            <span class="group-label">outcome</span>
            ${outcomes.map((f) => chip('outcome', this.filterOutcomes, f))}
          </div>`
        : ''}
      ${routineCount > 0
        ? html`<div class="group">
            <span class="group-label">hidden</span>
            <jf-filter-chip
              ?active=${this.showRoutine}
              data-testid="ledger-routine-toggle"
              @click=${() => {
                this.showRoutine = !this.showRoutine;
              }}
            >
              routine
              <span class="chip-count">(${routineCount})</span>
            </jf-filter-chip>
          </div>`
        : ''}
    </div>`;
  }

  override render(): TemplateResult {
    if (this.entries.length === 0) {
      return html`<div class="empty" data-testid="ledger-empty">No activity yet.</div>`;
    }
    const filters = this.renderFilters();
    // tempdoc 558 §E1 — facet the stream client-side before the existing ordering + burst projection.
    // tempdoc 613 §6/§10 — and exclude routine direct-user navigation by default (the de-flood): the
    // curated feed foregrounds what the user did NOT already witness; the toggle reveals the rest.
    const filtered = this.entries.filter(
      (e) => this.matchesFilter(e) && (this.showRoutine || !e.isRoutine),
    );
    if (filtered.length === 0) {
      // tempdoc 612 §L.2 #4 — distinguish a genuinely-empty curated feed (the session was ALL routine,
      // nothing hidden by who/outcome facets) from a real filter mismatch, so de-flooding never reads as
      // breakage. The reveal toggle is already rendered in `filters` above (routineCount > 0).
      const onlyRoutineHidden =
        !this.showRoutine &&
        this.filterOriginators.length === 0 &&
        this.filterOutcomes.length === 0 &&
        this.entries.some((e) => e.isRoutine);
      return html`${filters}<div class="empty" data-testid="ledger-empty">
          ${onlyRoutineHidden
            ? html`Only routine activity this session.
                <button
                  type="button"
                  class="reveal-routine"
                  data-testid="ledger-reveal-routine"
                  @click=${() => {
                    this.showRoutine = true;
                  }}
                >
                  Show routine
                </button>`
            : html`No activity matches the filter.`}
        </div>`;
    }
    // Most-recent first for display (the projection is oldest-first). Tempdoc 571 — the shared
    // newest-first ordering primitive (the one bit genuinely duplicated with the Logs surface; the
    // SSE substrate EnvelopeStream + the bounded collapseBursts are already shared).
    const rows = newestFirst(filtered);
    // Bounded projection (tempdoc 550 thesis III(b)): collapse a burst of adjacent same-collection
    // indexing terminal outcomes into one summary row, so a large indexing run shows
    // "Indexed N · <collection>" rather than N individual rows flooding the timeline.
    const projected = collapseBursts<UnifiedActionEntry, IndexBurstSummary>(rows, {
      keyOf: (e) => (e.kind === 'index' ? (e.groupKey ?? 'default') : null),
      // rows are newest-first, so items[0] is the most recent terminal outcome in the burst.
      summarize: (groupKey, items) => ({
        summary: true,
        groupKey,
        count: items.length,
        occurredAt: items[0]?.occurredAt ?? '',
      }),
    });
    return html`${filters}${projected.map((row) =>
      isBurst(row)
        ? html`<div
            class="row"
            data-testid="ledger-burst"
            data-kind="index"
            ?data-new=${row.occurredAt > this.seenCursor}
          >
            ${row.occurredAt > this.seenCursor
              ? html`<span class="new-dot" data-testid="ledger-new-dot" aria-label="new since you looked"></span>`
              : ''}
            <span class="who" data-originator="system">system</span>
            <span class="label">Indexed ${row.count} · ${row.groupKey}</span>
            <span class="src">backend</span>
            ${renderEventWhen(row.occurredAt)}
          </div>`
        : // tempdoc 612 §UX — pass the "new since you looked" mark as an ADDITIVE opt-in to the shared row;
          // History/Timeline render the same row WITHOUT it (they pass no opts), so the marker never leaks.
          renderEventRow(row, { isNew: this.isNewRow(row) }),
    )}`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-action-ledger')) {
  customElements.define('jf-action-ledger', ActionLedgerView);
}
