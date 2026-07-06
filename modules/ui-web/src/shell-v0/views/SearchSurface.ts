// SPDX-License-Identifier: Apache-2.0
/**
 * SearchSurface — Lit-side Search HUD rail surface (slice 463 V1).
 *
 * Self-mounting Surface. Renders a search input + results list.
 * Click a result → setSelected() pushes it into inspectorState
 * (slice 462) which opens the inspector pane in the chrome.
 *
 * Side-effect registers `<jf-search-surface>`.
 *
 * V1 deferrals (per slice 463 plan):
 *  - No autocomplete suggestions (Phase 5).
 *  - No filter pills (Phase 3).
 *  - No launchpad / zero-results CTAs (Phase 4).
 *  - No virtualization (plain list; 50-result page is fine).
 *  - No multi-select / Ctrl+A.
 *  - Mode prefixes (`/`, `??`) treated as plain queries; chat mode
 *    deferred (Agent surface owns chat).
 *  - Keyboard nav arrow-keys deferred.
 *  - Density toggle deferred.
 */

import { html, css, type TemplateResult, nothing } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { surfaceLayoutStyles } from '../primitives/surfaceLayout.js';
import { compose } from '../utils/compose.js';
import { repeat } from 'lit/directives/repeat.js';

import { icon } from '../components/Icon.js';
import '../components/ErrorAlert.js';

// B3 — surface the backend's retrieval-degraded / reindex-required signal in the
// Search UI, projected from the ONE observed-state authority (aiStateStore).
import { subscribeAiState, type AiState } from '../state/aiStateStore.js';
import { verdictTone, type SystemHealthVerdict } from '../state/verdict.js';
// Tempdoc 596 — the "Ask AI" affordance routes through typed availability.
import { projectAvailability, type Availability } from '../state/availability.js';
import { emitEphemeralToast } from '../components/advisory/ephemeralToast.js';
import '../components/Control.js';
// Q8: gate the raw retrieval-trace diagnostics behind Advanced mode.
import { subscribeUiMode, type UiMode } from '../state/uiModeState.js';
import {
  getSearchScope,
  setSearchScope,
  type SearchScope,
} from '../state/searchState.js';
// Tempdoc 549 (Phase D1) — mount the query-level explain panel (G33) from the
// unified trace. Side-effect import registers the `<jf-search-trace>` element even
// in isolated (test) mounts; bootstrapAggregateSubstrate() registers its strategy
// at app boot.
import '../aggregate-substrate/components/JfSearchTrace.js';
import { matchCountLabel } from '../components/searchResults/matchCountLabel.js';
import '../components/SystemNotice.js';
// Tempdoc 577 Phase 2 (Ext III) — the cause+remedy projection behind the degradation
// banner, and the catalog-driven operation button that dispatches its remedy.
import { readinessNotice } from '../state/readinessNotice.js';
// Tempdoc 613 §6 R-3 — "don't push what's already pulled": suppress a cause-toast the banner shows.
import { causePushSuppressedByBanner } from '../state/messageRouting.js';
import { RetainedScroll } from '../controllers/retainedScroll.js';
// Tempdoc 577 Phase 6 (Move E) — keyword facet selections (the clickable half of dual
// filtering). Direct state import: this is a core surface; the plugin host API does not
// expose facet control yet (no consumer — add to PluginSearchState when one appears).
import {
  toggleFacetValue,
  subscribeFacetSelections,
} from '../state/searchFiltersState.js';
import '../components/OpButton.js';
import '../components/Button.js';
import type { SearchTrace } from '../../api/generated/index.js';
import type {
  PluginHostApi,
  SearchSnapshot,
  SearchHitSnapshot,
  SearchPinSnapshot,
  SearchFilterSnapshot,
} from '../plugin-api/plugin-types.js';
// Tempdoc 508-followup §γ4 — multi-select. SearchSurface is core
// chrome, so it publishes through the internal selectionState
// (typed SelectionItem union); the host.selection sub-interface
// is the plugin-facing boundary view.
import {
  setSelection as setInternalSelection,
  DEFAULT_CAPABILITIES_BY_KIND,
  type SelectionItem,
} from '../state/selectionState.js';
// Search Thread S1 — the ONE results card; this surface mounts it and wires its
// intent events (open/selection/facet/ask) through the existing host seams.
import '../components/searchResults/ResultsCard.js';
import type { CardSelectionDetail } from '../components/searchResults/ResultsCard.js';

/**
 * Slice 486 G36-widening (filter-snapshot) — short human-readable description of a filter
 * snapshot for the chip subscript. Returns null when the spec
 * is undefined or has no usable bound.
 *
 * Examples:
 *   - both bounds: "between 2024-01-01 and 2025-12-31"
 *   - only from: "since 2024-01-01"
 *   - only to:   "until 2025-12-31"
 */
function formatFilterBlurb(
  spec: SearchFilterSnapshot | undefined,
): string | null {
  if (spec === undefined) return null;
  const fromOk =
    typeof spec.modifiedFromMs === 'number' &&
    Number.isFinite(spec.modifiedFromMs);
  const toOk =
    typeof spec.modifiedToMs === 'number' &&
    Number.isFinite(spec.modifiedToMs);
  if (!fromOk && !toOk) return null;
  const fromDate = fromOk ? toIsoDate(spec.modifiedFromMs!) : null;
  const toDate = toOk ? toIsoDate(spec.modifiedToMs!) : null;
  if (fromDate !== null && toDate !== null) {
    return `between ${fromDate} and ${toDate}`;
  }
  if (fromDate !== null) return `since ${fromDate}`;
  return `until ${toDate}`;
}

/**
 * Convert a ms-epoch number to YYYY-MM-DD using local time.
 * Local — not UTC — so the displayed date matches the date
 * the user selected in `<input type="date">`.
 */
function toIsoDate(ms: number): string {
  const d = new Date(ms);
  const yyyy = d.getFullYear().toString().padStart(4, '0');
  const mm = (d.getMonth() + 1).toString().padStart(2, '0');
  const dd = d.getDate().toString().padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

/**
 * Parse a `<input type="date">` value (`YYYY-MM-DD`) into ms
 * epoch (local-time midnight). Returns undefined for empty /
 * malformed input.
 */
function fromIsoDate(s: string): number | undefined {
  if (typeof s !== 'string' || s.length === 0) return undefined;
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s);
  if (m === null) return undefined;
  const [, y, mo, d] = m;
  const dt = new Date(Number(y), Number(mo) - 1, Number(d));
  if (Number.isNaN(dt.getTime())) return undefined;
  return dt.getTime();
}

export class SearchSurface extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
    host_: { attribute: false },
    s: { state: true },
    pins: { state: true },
    filters: { state: true },
    selectedHitIds: { state: true },
    verdict: { state: true },
    aiState: { state: true },
    advanced: { state: true },
    facetSelections: { state: true },
  };

  declare apiBase: string;
  declare host_: PluginHostApi;
  declare s: SearchSnapshot;
  /** Slice 486 G36 — current pinned-search list (subscription state). */
  declare pins: readonly SearchPinSnapshot[];
  /**
   * Slice 486 G36-widening (filter-snapshot) — current filter spec (modifiedAt range).
   * Mirrors searchFiltersState; ephemeral, not persisted.
   */
  declare filters: SearchFilterSnapshot;
  /**
   * Tempdoc 508-followup §γ4 — ids of currently-selected search hits.
   * Drives the multi-select row style + selectionState publish.
   */
  declare selectedHitIds: ReadonlySet<string>;
  /** The ONE system-health verdict (595 §4.2); the degradation banner consumes it. */
  declare verdict: SystemHealthVerdict;
  /** Tempdoc 596 — the full observed-state, for the "Ask AI" availability projection. */
  declare aiState: AiState | null;
  /** Q8 — Advanced UI mode (gates the raw retrieval-trace diagnostics). */
  declare advanced: boolean;
  /** Tempdoc 577 Phase 6 — active facet selections (mirrors searchFiltersState). */
  declare facetSelections: Record<string, string[]>;

  private unsub: (() => void) | null = null;
  private unsubFacets: (() => void) | null = null;
  private unsubAi: (() => void) | null = null;
  private unsubMode: (() => void) | null = null;
  private unsubPins: (() => void) | null = null;
  private unsubFilters: (() => void) | null = null;
  // 569 §14 — set-search-* Effect listeners (the 570 seam).
  private setSearchQueryListener: ((e: Event) => void) | null = null;
  private setSearchFilterListener: ((e: Event) => void) | null = null;
  private inputRef: HTMLInputElement | null = null;
  /**
   * Tempdoc 609 §R (P4) — result-list scroll save/restore, generalized into the shared RetainedScroll
   * controller (captures `.body` scrollTop on disconnect, restores after the reconnected render paints).
   * DOM `scrollTop` resets when a node is detached/re-attached, so scroll is the one piece of view state
   * retention does NOT preserve for free; the controller is the one home for that pattern.
   */
  private readonly retainedScroll = new RetainedScroll(
    this,
    () => this.shadowRoot?.querySelector<HTMLElement>('.body') ?? null,
  );

  constructor() {
    super();
    this.apiBase = '';
    this.host_ = undefined as unknown as PluginHostApi;
    this.s = { query: '', results: [], totalHits: 0, matchCount: 0, facetsTruncated: false, isSearching: false, processingTimeMs: null, error: null };
    this.pins = [];
    this.filters = {} as SearchFilterSnapshot;
    this.selectedHitIds = new Set<string>();
    this.verdict = { kind: 'connecting', severity: 'info', reasons: [] };
    this.aiState = null;
    this.advanced = false;
    this.facetSelections = {};
  }

  // Tempdoc 559 Authority I: :host / .header region contract owned by
  // surfaceLayoutStyles (the one layout authority); only bespoke rules here.
  // Search Thread S1 — row/meta/copy/facet/why/highlight styles moved into the one
  // `jf-results-card` (its own shadow root); this surface keeps only header/pins/
  // filter/scope/banner/explain chrome.
  static styles = [
    surfaceLayoutStyles,
    css`
    /* B3 — retrieval-degraded / reindex-required banner. 559: the notice shell
       (tone/bg/border/a11y) is owned by <jf-system-notice>; this owns only the
       inline placement layout. */
    .degradation-banner {
      flex-shrink: 0;
      display: block;
      margin: 0.5rem 1.25rem 0 1.25rem;
    }
    .degradation-banner .notice-row {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-size: var(--font-size-sm);
    }
    .degradation-banner strong {
      font-weight: 600;
    }
    /* Tempdoc 577 Phase 2 — worded causes + the remedy affordance inside the notice. */
    .degradation-banner .notice-causes {
      margin: 0.35rem 0 0 1.6rem;
      padding: 0;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .degradation-banner .notice-causes li {
      margin: 0.1rem 0;
    }
    .degradation-banner .notice-remedy {
      display: inline-flex;
      margin: 0.45rem 0 0.1rem 1.6rem;
    }
    /* Tempdoc 577 Phase 3 (Ext I) — the explain panel's two altitudes: a small user-tier
       summary line in the flow; diagnostics collapsed, subordinate type scale. Results own
       the visual hierarchy — nothing here exceeds the result-title scale. */
    jf-search-trace {
      display: block;
      flex-shrink: 0;
      margin: 0.4rem 1.25rem 0 1.25rem;
    }
    .search-explain-user {
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
    }
    .search-explain-diagnostics {
      margin-top: 0.15rem;
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
    }
    .search-explain-diagnostics-summary {
      cursor: pointer;
      color: var(--text-tertiary);
    }
    .search-explain-diagnostics-summary:hover,
    .search-explain-diagnostics-summary:focus-visible {
      color: var(--text-secondary);
    }
    .search-explain-diagnostics-body {
      margin: 0.25rem 0 0.25rem 1rem;
      font-family: ui-monospace, 'SF Mono', monospace;
    }
    .search-explain-stage {
      display: flex;
      gap: 0.5rem;
      line-height: 1.6;
    }
    .search-explain-stage-label {
      min-width: 12ch;
      color: var(--text-secondary);
    }
    /* Per-hit "Why this result?" chips now live in whyThisResultStyles (shared). */
    .input-wrap {
      position: relative;
      display: flex;
      align-items: center;
    }
    .input-wrap .search-icon {
      position: absolute;
      left: 0.625rem;
      color: var(--text-secondary);
    }
    input.q {
      flex: 1;
      width: 100%;
      /* Right padding leaves room for the slice 486 G36 pin button. */
      padding: 0.5rem 2.5rem 0.5rem 2.25rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      color: var(--text-primary);
      font-size: var(--font-size-sm);
      box-sizing: border-box;
    }
    input.q:focus {
      outline: none;
      border-color: var(--accent-tint);
      box-shadow: 0 0 0 2px var(--accent-tint-16);
    }
    .body {
      /* 559 Authority I: flex/overflow from surfaceLayoutStyles; zero-horizontal
         padding is surface-specific (rows carry their own 1.25rem). */
      padding: 0.5rem 0;
    }
    .empty {
      padding: 3rem 1rem;
      text-align: center;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    /* Slice 486 G36 — pinned-search button + chip strip. */
    .input-wrap .pin-btn {
      position: absolute;
      right: 0.5rem;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 1.75rem;
      height: 1.75rem;
      padding: 0;
      background: transparent;
      border: 1px solid transparent;
      border-radius: 0.375rem;
      color: var(--text-secondary);
      cursor: pointer;
    }
    .input-wrap .pin-btn:hover {
      background: var(--surface-secondary);
      color: var(--text-primary);
      border-color: var(--border-subtle);
    }
    .input-wrap .pin-btn:focus {
      outline: none;
      border-color: var(--accent-tint);
    }
    .meta {
      margin-top: 0.4rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .meta-empty {
      opacity: 0.6;
    }
    /* Tempdoc 585 §D Phase 4 (D4b) — the search-scope segmented control. */
    .scope-selector {
      display: inline-flex;
      gap: 0.25rem;
      margin-top: 0.5rem;
    }
    .scope-opt {
      padding: 0.2rem 0.6rem;
      font: inherit;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      background: transparent;
      border: 1px solid var(--border-subtle);
      border-radius: 9999px;
      cursor: pointer;
    }
    .scope-opt:hover {
      color: var(--text-primary);
      border-color: var(--border-default);
    }
    .scope-opt.active {
      color: var(--text-primary);
      border-color: var(--accent);
      background: var(--accent-primary-weak);
    }
    .pinned-strip {
      flex-shrink: 0;
      padding: 0.5rem 1.25rem;
      display: flex;
      flex-wrap: wrap;
      gap: 0.375rem;
      border-bottom: 1px solid var(--border-subtle);
      background: var(--surface-tertiary);
    }
    .pinned-strip-label {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      margin-right: 0.25rem;
      align-self: center;
    }
    .pinned-chip {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0.25rem 0.25rem 0.25rem 0.625rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 999px;
      font-size: var(--font-size-xs);
      color: var(--text-primary);
      cursor: pointer;
      max-width: 22rem;
    }
    .pinned-chip:hover {
      border-color: var(--accent-tint);
    }
    .pinned-chip .chip-label {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      max-width: 18rem;
    }
    /* Slice 486 G36-widening (filter-snapshot) — date-range filter row. */
    .filter-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
      margin-top: 0.25rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .filter-row .filter-label {
      font-weight: 500;
    }
    .filter-row .filter-field {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
    }
    .filter-row .filter-field-label {
      opacity: 0.7;
    }
    .filter-row .filter-input {
      font-size: var(--font-size-xs);
      padding: 0.15rem 0.35rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      color: var(--text-primary);
      color-scheme: dark light;
    }
    .filter-row .filter-input:focus {
      outline: none;
      border-color: var(--accent-tint);
    }
    .filter-row .filter-clear {
      font-size: var(--font-size-xs);
      padding: 0.1rem 0.45rem;
      background: transparent;
      border: 1px solid var(--border-subtle);
      border-radius: 999px;
      color: var(--text-secondary);
      cursor: pointer;
    }
    .filter-row .filter-clear:hover {
      border-color: var(--accent-tint);
      color: var(--text-primary);
    }
    /* Slice 486 G36-widening (run-history) — run-history subscript on pinned chips. */
    .pinned-chip .chip-content {
      display: inline-flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 0.05rem;
      min-width: 0;
    }
    .pinned-chip .chip-runs {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      max-width: 18rem;
    }
    .pinned-chip .chip-remove {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 1.125rem;
      height: 1.125rem;
      padding: 0;
      margin-left: 0.125rem;
      background: transparent;
      border: none;
      border-radius: 999px;
      color: var(--text-secondary);
      cursor: pointer;
      line-height: 1;
    }
    .pinned-chip .chip-remove:hover {
      background: var(--surface-tertiary);
      color: var(--text-primary);
    }
    /* Slice 486 G35 — copy buttons in meta row. */
    .meta {
      justify-content: space-between;
    }
    .meta-info {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      flex: 1;
      min-width: 0;
    }
    /* Tempdoc 577 Phase 5 — provisional/refining markers on the meta line. */
    .meta-refining {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      color: var(--text-tertiary);
    }
    /* Tempdoc 598 R1 (§34.1) — the retrieval-mode indicator: a muted inline hint of which
       retrieval actually ran (semantic vs keyword), reusing the meta-hint token styling. */
    .retrieval-mode {
      color: var(--text-tertiary);
    }
    .retrieval-mode[data-mode='HYBRID'],
    .retrieval-mode[data-mode='VECTOR'] {
      color: var(--text-secondary);
    }
    /* Tempdoc 577 Phase 6 (Move E) — facet chips now live in facetChipStyles (shared). */
    .copy-actions {
      display: flex;
      align-items: center;
      gap: 0.25rem;
      flex-shrink: 0;
    }
    .copy-actions .copy-btn {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0.125rem 0.5rem;
      background: transparent;
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      cursor: pointer;
      font-family: inherit;
    }
    .copy-actions .copy-btn:hover {
      color: var(--text-primary);
      border-color: var(--accent-tint);
    }
    /* Tempdoc 602 R7 — the copy confirmation is in-button (no toast, 559 III). The
       593 walkthrough missed it because the tint-only flash was too subtle, so the
       confirmed state reads as a prominent success: success-family colour + a faint
       success fill + bold weight (token-only). */
    .copy-actions .copy-btn.flashing {
      color: var(--text-success);
      border-color: var(--accent-success);
      background: var(--accent-success-16);
      font-weight: 600;
    }
    /* Tempdoc 596 — "Ask AI" is a jf-control; mirror the copy-btn look onto its inner button. */
    .copy-actions jf-control.copy-btn {
      border: none;
      background: transparent;
      padding: 0;
    }
    .copy-actions jf-control.copy-btn::part(control) {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0.125rem 0.5rem;
      background: transparent;
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      cursor: pointer;
    }
    .copy-actions jf-control.copy-btn:hover::part(control) {
      color: var(--text-primary);
      border-color: var(--accent-tint);
    }
  `,
  ];

  override connectedCallback(): void {
    super.connectedCallback();
    this.host_.search.setSearchApiBase(this.apiBase || '');
    this.s = this.host_.search.getSearchState();
    this.pins = this.host_.search.getPinnedSearches();
    this.filters = this.host_.search.getFilters();
    this.unsub = this.host_.search.subscribeSearch((s) => {
      const prev = this.s;
      this.s = s;
      const settled = (prev).isSearching && !(s).isSearching && !(s).error;
      if (settled && (s).query.trim().length > 0) {
        this.host_.search.recordSearchRun((s).query, (s).totalHits);
      }
      // Tempdoc 609 — an explicit clear (query emptied) is intent-driven reset: drop the retained
      // selection + scroll so they don't resurrect against a different/empty result set.
      if (!(s).query.trim()) {
        this.selectedHitIds = new Set();
        this.retainedScroll.reset();
      }
    });
    this.unsubPins = this.host_.search.subscribePinnedSearches((pins) => (this.pins = pins));
    this.unsubFilters = this.host_.search.subscribeFilters((f) => {
      const prev = this.filters;
      this.filters = f;
      const cur = f;
      const filterChanged =
        prev.modifiedFromMs !== cur.modifiedFromMs ||
        prev.modifiedToMs !== cur.modifiedToMs;
      if (filterChanged && this.s.query.trim().length > 0) {
        this.host_.search.setQuery(this.s.query);
      }
    });
    // Tempdoc 577 Phase 6 — mirror the facet selections (chips reflect + stay dismissable).
    this.unsubFacets = subscribeFacetSelections((sel) => (this.facetSelections = sel));
    // 595 §4.2 — the degradation banner consumes the ONE verdict; 596 — the full
    // observed-state feeds the "Ask AI" availability projection.
    this.unsubAi = subscribeAiState((st: AiState) => {
      this.verdict = st.verdict;
      this.aiState = st;
    });
    // Q8 — Advanced mode gates the raw retrieval-trace diagnostics.
    this.unsubMode = subscribeUiMode((m: UiMode) => (this.advanced = m === 'advanced'));
    // 569 §14 — set-search-* Effect listeners: a Move-8 statechart can drive the
    // query/filter through the gated dispatcher (the search-as-a-mode seam 570
    // consumes). Scoped to this surface, which owns host_.search.
    this.setSearchQueryListener = (e: Event) => {
      const q = (e as CustomEvent<{ query?: string }>).detail?.query;
      if (typeof q === 'string') this.host_.search.setQuery(q);
    };
    this.setSearchFilterListener = (e: Event) => {
      const d = (e as CustomEvent<{ fromMs?: number; toMs?: number }>).detail ?? {};
      this.host_.search.setFilterRange(d.fromMs, d.toMs);
    };
    document.addEventListener('jf-set-search-query', this.setSearchQueryListener);
    document.addEventListener('jf-set-search-filter', this.setSearchFilterListener);

    // Tempdoc 609 (instance-retention) — the selection survives navigation as `this.selectedHitIds`
    // (instance @state on the retained element). On reconnect re-publish it to the GLOBAL selectionState
    // (the Shell clears the global selection on surface change — 508's anticipated "re-publish on
    // activation") and reopen the inspector for the primary hit, so the selection's command-context +
    // preview pane return with the surface. Stale ids (results changed) are dropped by applySelection.
    if (this.selectedHitIds.size > 0 && this.s.results.length > 0) {
      const primary = this.s.results.findIndex((h) => this.selectedHitIds.has(h.id));
      this.applySelection(this.selectedHitIds, primary >= 0 ? primary : 0);
      const primaryHit = primary >= 0 ? this.s.results[primary] : undefined;
      if (primaryHit) this.host_.ui.showInspector(this.host_.search.hitToSelectedItem(primaryHit));
    }
    // Scroll restore is handled by the RetainedScroll controller (hostConnected → after updateComplete).
  }

  // Tempdoc 609 — SearchSurface needs no settleTransients override: in-flight/error live in the singleton
  // searchState store (self-settling), the "Copied!" receipt is owned by ReceiptController (cleared on
  // hostDisconnected, tempdoc 613), and query/results/selection/scroll are recoverable. Scroll is
  // captured/restored by the RetainedScroll controller (§R P4) on host disconnect/connect.
  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsub?.();
    this.unsubFacets?.();
    this.unsubPins?.();
    this.unsubFilters?.();
    this.unsubAi?.();
    this.unsubMode?.();
    if (this.setSearchQueryListener) {
      document.removeEventListener('jf-set-search-query', this.setSearchQueryListener);
      this.setSearchQueryListener = null;
    }
    if (this.setSearchFilterListener) {
      document.removeEventListener('jf-set-search-filter', this.setSearchFilterListener);
      this.setSearchFilterListener = null;
    }
    // Tempdoc 613 §6 — the copy-receipt timer is owned by ReceiptController (hostDisconnected clears it).
    // Tempdoc 609 (M1) — do NOT clear the search store on unmount. The query + result snapshot are
    // recoverable task state held in the singleton `searchState` store; wiping them here is what made a
    // search vanish on a brief tab switch. Clearing now happens only through the explicit user controls
    // (the in-box clear / Esc at the search input), never as a navigation side effect.
  }

  /**
   * Slice 486 G36-widening (filter-snapshot) — date-input change handler. Reads both inputs
   * (from refs) and forwards the parsed ms-epoch values to the
   * filter projection. Empty inputs become `undefined`.
   */
  private handleFilterChange(): void {
    const root = this.shadowRoot;
    if (root === null) return;
    const fromEl = root.querySelector<HTMLInputElement>(
      '[data-testid=filter-from]',
    );
    const toEl = root.querySelector<HTMLInputElement>(
      '[data-testid=filter-to]',
    );
    this.host_.search.setFilterRange(
      fromEl !== null ? fromIsoDate(fromEl.value) : undefined,
      toEl !== null ? fromIsoDate(toEl.value) : undefined,
    );
  }

  /**
   * Slice 486 G36 — pin the current query. No-op if empty /
   * already pinned. Slice 486 G36-widening (filter-snapshot) extends: capture the active
   * filter snapshot at click time so chip restore re-applies it.
   */
  private handlePinClick(): void {
    const filters = this.host_.search.getFilters();
    this.host_.search.pinSearch(this.s.query, this.host_.search.hasActiveFilter() ? filters : undefined);
  }

  /**
   * Tempdoc 596 §1.2 — "Ask AI". `compose()` returns `false` when AI is offline; the old handler
   * DISCARDED it, so offline the button looked live and silently no-op'd. Now the button is a
   * jf-control declared `unavailable` when AI is offline (so a click surfaces the reason, not silence),
   * and as a belt-and-suspenders the handler ALSO honors the boolean if availability and compose disagree.
   */
  private handleAskAi(): void {
    const ok = compose({
      operation: 'core.ask',
      source: 'BUTTON',
      userPrompt: this.s.query,
      affordance: 'documents',
    });
    if (!ok) {
      // Tempdoc 613 §6 R-3 — "don't push what's already pulled". The AI-offline condition is a PULL
      // state: when the verdict is degraded, the degradation banner already states it. Pushing an
      // "AI is offline" toast on top would be a redundant double-surface, so suppress it whenever the
      // banner is already up (the shared `causePushSuppressedByBanner` signal); only push the
      // transient when nothing pulled the cause.
      if (!causePushSuppressedByBanner(this.verdict)) {
        emitEphemeralToast({ message: 'AI is offline', severity: 'warning' });
      }
    }
  }

  /** Tempdoc 596 — availability of the "Ask AI" affordance (documents/RAG path). */
  private askAiAvailability(): Availability {
    return projectAvailability('documents', this.aiState);
  }

  /**
   * Slice 486 G36 — clicking a chip re-runs the pinned query.
   * Slice 486 G36-widening (filter-snapshot) extends: also restores the chip's filter
   * snapshot. setFilterRange triggers a filter-change emit;
   * the subscribeFilters handler then calls setQuery, which
   * is idempotent if we already set the query here. So we
   * deliberately set filters FIRST (so the post-setQuery
   * search uses the new filter) — but setQuery short-circuits
   * the no-query case, so we still call it unconditionally.
   */
  private handleChipClick(pin: SearchPinSnapshot): void {
    // Filter range is per-surface state (not the search-initiation authority) — set it directly.
    this.host_.search.setFilterRange(pin.filterSpec?.modifiedFromMs, pin.filterSpec?.modifiedToMs);
    // 548 §4.5: route the pinned QUERY through the intent pipeline (the single authority for
    // "the user asked to search") instead of a direct setQuery bypass — the same collapse the
    // palette got in S4-B. The Shell's navigate-with-context listener → IntentRouter →
    // navigationHandler restores the query via restoreSearch (which runs the fetch).
    this.dispatchEvent(
      new CustomEvent('navigate-with-context', {
        detail: { target: 'core.search-surface', state: { query: pin.query } },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /** Slice 486 G36 — clicking the chip's × removes the pin. */
  private handleChipRemove(pin: SearchPinSnapshot, e: Event): void {
    // Prevent the chip-click handler from firing (which would set the query).
    e.stopPropagation();
    this.host_.search.unpinSearch(pin.id);
  }


  override updated(): void {
    if (!this.inputRef) {
      const el = this.shadowRoot?.querySelector<HTMLInputElement>('input.q');
      if (el) {
        this.inputRef = el;
        // Auto-focus on first paint.
        el.focus();
      }
    }
  }


  /** Search Thread S1 — card open → inspector via the host seam (selection arrives separately). */
  private handleCardOpen(hitId: string): void {
    const hit = (this.s.results as readonly SearchHitSnapshot[]).find((h) => h.id === hitId);
    if (hit) this.host_.ui.showInspector(this.host_.search.hitToSelectedItem(hit));
  }

  private applySelection(nextIds: ReadonlySet<string>, primaryIndex: number): void {
    // Tempdoc 609 (instance-retention) — `selectedHitIds` is instance @state; the Stage retains this
    // element across navigation, so the multi-select set survives a tab switch with no external store.
    this.selectedHitIds = nextIds;
    const hits = this.s.results as readonly SearchHitSnapshot[];
    // Tempdoc 526 §17 T1B — multi-select (size > 1) publishes a single
    // `result-set` SelectionItem covering all selected docs. The F9 menu's
    // result-set actions ("Summarize all", "Compare" when that shape ships)
    // then route to the registry. Single-select keeps the per-hit publish
    // so the existing "click one row → inspect" behavior is unchanged.
    if (nextIds.size > 1) {
      const refs: Array<{ id: string; kind: 'doc' }> = [];
      for (const h of hits) {
        if (nextIds.has(h.id)) refs.push({ id: h.path, kind: 'doc' });
      }
      setInternalSelection({
        items: [
          {
            kind: 'result-set',
            items: refs,
            query: this.s.query || undefined,
            capabilities: DEFAULT_CAPABILITIES_BY_KIND['result-set'],
          },
        ],
        primaryIndex: 0,
        surfaceId: 'core.search-surface',
      });
      return;
    }
    const items: SelectionItem[] = [];
    let normalizedPrimary = 0;
    for (let i = 0; i < hits.length; i++) {
      const h = hits[i]!;
      if (!nextIds.has(h.id)) continue;
      if (i === primaryIndex) normalizedPrimary = items.length;
      items.push({
        kind: 'search-hit',
        hitId: h.id,
        title: h.title,
        path: h.path,
        capabilities: DEFAULT_CAPABILITIES_BY_KIND['search-hit'],
      });
    }
    setInternalSelection({
      items,
      primaryIndex: normalizedPrimary,
      surfaceId: 'core.search-surface',
    });
  }




  /**
   * Tempdoc 597 — the truthful result-count label as a FUNNEL (see {@link matchCountLabel}).
   *
   * Tempdoc 597 R-1: the logic now lives in the shared `components/searchResults/matchCountLabel`
   * helper so the Chat-surface retrieve tier projects the IDENTICAL label from the same state. This
   * static is retained as a thin delegate to preserve existing call sites and tests.
   */
  static matchCountLabel(
    matched: number,
    shown: number,
    rankedOnly = false,
    ranked = 0,
    truncated = false,
  ): string {
    return matchCountLabel(matched, shown, rankedOnly, ranked, truncated);
  }


  /**
   * Tempdoc 577 Phase 6 (Move E) / Goal 3 §3.9a — the clickable half of dual
   * filtering. Delegates to the ONE shared `renderFacetChips` (chips projected
   * from the response's emitted counts + the current selections), shared with the
   * unified window's retrieve tier. The re-run seam stays surface-local: a toggle
   * mutates `searchFiltersState` then re-runs the full pass through the host.
   */
  /**
   * Tempdoc 585 §D Phase 4 (D4b) — the search-scope selector: "Documents" (default; the reserved
   * agent-history collection is excluded by the backend) vs "Agent history" (the user searching their
   * own run transcripts). Switching re-issues the active query through the ONE buildSearchIntent seam.
   */
  private renderScopeSelector(): TemplateResult {
    const scope = getSearchScope();
    const opt = (value: SearchScope, label: string): TemplateResult => html`<button
      class="scope-opt ${scope === value ? 'active' : ''}"
      role="tab"
      aria-selected=${scope === value ? 'true' : 'false'}
      @click=${() => {
        setSearchScope(value);
        this.requestUpdate();
      }}
    >
      ${label}
    </button>`;
    return html`<div class="scope-selector" role="tablist" aria-label="Search scope">
      ${opt('documents', 'Documents')}${opt('agent-history', 'Agent history')}
    </div>`;
  }

  private handleFacetToggle(field: string, value: string): void {
    toggleFacetValue(field, value);
    // Selections change the result set — re-run the full pass through the one seam.
    if (this.s.query.trim().length > 0) this.host_.search.submitQuery();
  }




  /**
   * Slice 486 G36-widening (run-history) — render the run-history subscript line for a
   * pinned chip. Hidden when `runs` is empty AND no filterSpec.
   * Slice 486 G36-widening (filter-snapshot) — also surfaces the captured filter snapshot
   * so the chip's saved-filter is visible at-a-glance.
   *
   * Format:
   *   "N runs · last K hits · {relativeTime} · {filter-blurb}"
   * (each segment optional; segments joined by " · " only when
   * adjacent segments are present.)
   */
  private renderChipRuns(p: SearchPinSnapshot): TemplateResult | typeof nothing {
    const segments: string[] = [];
    if (p.runs.length > 0) {
      const last = p.runs[p.runs.length - 1]!;
      const runWord = p.runs.length === 1 ? 'run' : 'runs';
      const hitWord = last.totalHits === 1 ? 'hit' : 'hits';
      segments.push(
        `${p.runs.length} ${runWord} · last ${last.totalHits} ${hitWord} · ${this.host_.utilities.formatRelativeTime(new Date(last.ranAt).toISOString())}`,
      );
    }
    const filterBlurb = formatFilterBlurb(p.filterSpec);
    if (filterBlurb !== null) {
      segments.push(filterBlurb);
    }
    if (segments.length === 0) return nothing;
    return html`<span class="chip-runs" data-testid="pinned-chip-runs"
      >${segments.join(' · ')}</span
    >`;
  }

  /**
   * B3 — surface the backend's retrieval-degraded / reindex-required state
   * (Q5: semantic search silently falling back to keyword-only). Projected from
   * the one observed-state authority's `readiness.composites`, so the signal is
   * honest by construction — the surface never has to remember to check. Hidden
   * when readiness is unknown (no data yet) or retrieval is ready.
   */
  private renderDegradationBanner(): TemplateResult | typeof nothing {
    // Tempdoc 595 §4.2 — minted from the ONE verdict (not a second readiness read),
    // so it carries its CAUSE + REMEDY AND cannot contradict the Health header/footer.
    // 577 Ext III: the cause is worded from the backend reason codes; the remedy is a
    // catalog operation via <jf-op-button>, or Open Health.
    const notice = readinessNotice(this.verdict);
    if (!notice) return nothing;
    // 559 notice-presentation — render through the shared notice primitive; the tone is
    // the verdict's severity projection (595 §10.5), so a cosmetic degradation is calm
    // (info), an impairing one alarms (warning) — matching the Health badge.
    return html`<jf-system-notice
      tone=${verdictTone(this.verdict.severity)}
      live="status"
      class="degradation-banner"
      data-testid="search-degradation"
    >
      <span class="notice-row"
        >${icon({ name: 'alert-triangle', size: 13 })}
        <span><strong>${notice.headline}</strong> ${notice.body}</span></span
      >
      ${notice.causes.length > 0
        ? html`<ul class="notice-causes" data-testid="degradation-causes">
            ${notice.causes.map((c) => html`<li>${c}</li>`)}
          </ul>`
        : nothing}
      <span class="notice-remedy">
        ${notice.remedy.kind === 'operation'
          ? html`<jf-op-button
              operation-id=${notice.remedy.operationId}
              api-base=${this.apiBase}
              data-testid="degradation-remedy-op"
            ></jf-op-button>`
          : html`<jf-button
              variant="secondary"
              data-testid="degradation-remedy-nav"
              .onActivate=${() => this.openRemedyTarget((notice.remedy as { target: string }).target)}
              >${notice.remedy.label}</jf-button
            >`}
      </span>
    </jf-system-notice>`;
  }

  /** Tempdoc 577 Phase 2 — route the navigate-remedy through the existing intent pipeline
   *  (the same `navigate-with-context` seam the pinned chips use). */
  private openRemedyTarget(target: string): void {
    this.dispatchEvent(
      new CustomEvent('navigate-with-context', {
        detail: { target, state: {} },
        bubbles: true,
        composed: true,
      }),
    );
  }

  override render(): TemplateResult {
    // Slice 486 G36 — pin button visible when query is non-empty AND
    // not already pinned. Hidden otherwise (no clutter for empty
    // state or duplicate-pin attempts).
    const showPinBtn =
      this.s.query.trim().length > 0 && !this.host_.search.isPinned(this.s.query);
    return html`
      <div class="header">
        <div class="input-wrap">
          <span class="search-icon">${icon({ name: 'search', size: 14 })}</span>
          <input
            class="q"
            type="text"
            role="searchbox"
            aria-label="Search files"
            aria-controls="search-results-list"
            data-testid="search-input"
            placeholder="Search files… (Esc to clear)"
            ?disabled=${this.verdict?.kind === 'unreachable'}
            .value=${this.s.query}
            @input=${(e: Event) => this.host_.search.setQuery((e.target as HTMLInputElement).value)}
            @keydown=${(e: KeyboardEvent) => {
              if (e.key === 'Escape') {
                e.preventDefault();
                this.host_.search.setQuery('');
              }
              // Tempdoc 577 Phase 5 — Enter runs the full refined pass immediately
              // (skips the staged quick pass; same one-intent seam).
              if (e.key === 'Enter') {
                e.preventDefault();
                this.host_.search.submitQuery();
              }
            }}
          />
          ${showPinBtn
            ? html`<button
                class="pin-btn"
                title="Pin this search"
                aria-label="Pin this search"
                data-testid="pin-search-btn"
                @click=${() => this.handlePinClick()}
              >
                ${icon({ name: 'bookmark', size: 14 })}
              </button>`
            : nothing}
        </div>
        ${this.renderScopeSelector()}
        ${this.s.query.trim().length === 0 && !this.s.isSearching && this.s.results.length === 0
          ? html`<div class="meta"><span class="meta-empty">Type to search</span></div>`
          : nothing}
        <!-- Slice 486 G36-widening (filter-snapshot) — modified-at date range filter. -->
        <div
          class="filter-row"
          data-testid="filter-row"
          aria-label="Filter results by modified date"
        >
          <span class="filter-label">Modified:</span>
          <label class="filter-field">
            <span class="filter-field-label">After</span>
            <input
              type="date"
              class="filter-input"
              data-testid="filter-from"
              .value=${this.filters.modifiedFromMs !== undefined
                ? toIsoDate(this.filters.modifiedFromMs)
                : ''}
              @change=${() => this.handleFilterChange()}
            />
          </label>
          <label class="filter-field">
            <span class="filter-field-label">Before</span>
            <input
              type="date"
              class="filter-input"
              data-testid="filter-to"
              .value=${this.filters.modifiedToMs !== undefined
                ? toIsoDate(this.filters.modifiedToMs)
                : ''}
              @change=${() => this.handleFilterChange()}
            />
          </label>
          ${this.host_.search.hasActiveFilter()
            ? html`<button
                class="filter-clear"
                title="Clear date filter"
                data-testid="filter-clear"
                @click=${() => this.host_.search.setFilterRange(undefined, undefined)}
              >
                Clear
              </button>`
            : nothing}
        </div>
      </div>
      ${this.renderDegradationBanner()}
      ${this.pins.length > 0
        ? html`<div class="pinned-strip" data-testid="pinned-strip">
            <span class="pinned-strip-label">Pinned</span>
            ${repeat(
              this.pins,
              (p) => p.id,
              (p) => html`<button
                class="pinned-chip"
                data-testid="pinned-chip"
                title=${p.query}
                @click=${() => this.handleChipClick(p)}
              >
                <span class="chip-content">
                  <span class="chip-label">${p.query}</span>
                  ${this.renderChipRuns(p)}
                </span>
                <span
                  class="chip-remove"
                  role="button"
                  tabindex="0"
                  aria-label="Remove pin"
                  data-testid="pinned-chip-remove"
                  @click=${(e: Event) => this.handleChipRemove(p, e)}
                  @keydown=${(e: KeyboardEvent) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      this.handleChipRemove(p, e);
                    }
                  }}
                  >×</span
                >
              </button>`,
            )}
          </div>`
        : nothing}
      ${this.s.error
        ? html`<jf-error-alert tone="error" style="margin: 0.5rem 1.25rem"
            >${this.s.error}</jf-error-alert
          >`
        : nothing}
      ${this.advanced ? this.renderExplainPanel() : nothing}
      <div class="body">
        ${this.renderBodyEmptyOrResults()}
      </div>
    `;
  }

  /**
   * Tempdoc 549 (Phase D1) — G33 "why was this slow / what happened" panel.
   * Mounts the registered `<jf-search-trace>` aggregate renderer with the unified
   * stage-keyed trace — the single canonical artifact (worker + head stages, query
   * scalars). Supersedes the Slice-1 `<jf-search-introspection>` mount; the legacy
   * component + strategy + the `introspection`/`pipelineExecution` state reads retire
   * in Phase E4. Shown only when a completed search has a trace; the component renders
   * nothing if no strategy is registered, so this is safe even pre-bootstrap.
   */
  private renderExplainPanel(): unknown {
    const trace = this.s.searchTrace as SearchTrace | null | undefined;
    if (!trace || this.s.isSearching || this.s.results.length === 0) {
      return nothing;
    }
    return html`<jf-search-trace
      data-testid="search-explain-panel"
      context="search-explain"
      .trace=${trace}
    ></jf-search-trace>`;
  }

  /**
   * observations.md inbox item #6 (2026-05-09): the "Type to search" empty
   * state was misleading when a filter was active and produced 0 hits. The
   * four-case grid distinguishes:
   *
   *   - has query + has filter + zero results → "No matches in filter range"
   *   - has query + zero results               → "No results for "<query>""
   *   - no query  + has filter                 → "Filter set; type a query
   *                                              to search within range"
   *   - no query  + no filter                  → "Type to search…"
   */
  private renderBodyEmptyOrResults(): unknown {
    if (this.s.isSearching || this.s.results.length > 0) {
      // Search Thread S1 — the results presentation (meta line, facet chips, copy
      // actions, Ask AI, multi-select rows) is the ONE shared `jf-results-card`;
      // this surface supplies its snapshot/selection and handles the card's
      // intent events through its existing host seams. The 559 Authority II
      // list/listitem/aria-current semantics live inside the card now (the
      // a11y-closure check's SEARCH constant points there).
      return html`<jf-results-card
        .snapshot=${this.s}
        .facetSelections=${this.facetSelections}
        .selectedIds=${this.selectedHitIds}
        .askAvailability=${this.askAiAvailability()}
        .copyText=${(text: string) => this.host_.ui.copyToClipboard(text)}
        @card-open=${(e: CustomEvent<{ id: string }>) => this.handleCardOpen(e.detail.id)}
        @card-selection=${(e: CustomEvent<CardSelectionDetail>) =>
          this.applySelection(new Set(e.detail.ids), e.detail.primaryIndex)}
        @card-facet-toggle=${(e: CustomEvent<{ field: string; value: string }>) =>
          this.handleFacetToggle(e.detail.field, e.detail.value)}
        @card-ask-ai=${() => this.handleAskAi()}
      ></jf-results-card>`;
    }
    const hasQuery = this.s.query.trim().length > 0;
    const filterActive = this.host_.search.hasActiveFilter();
    if (hasQuery && filterActive) {
      const blurb = formatFilterBlurb(this.filters);
      return html`<div class="empty">
        No matches for "${this.s.query}" within the active filter${blurb
          ? html` (${blurb})`
          : nothing}.
      </div>`;
    }
    if (hasQuery) {
      return html`<div class="empty">No results for "${this.s.query}"</div>`;
    }
    if (filterActive) {
      const blurb = formatFilterBlurb(this.filters);
      return html`<div class="empty">
        Filter set${blurb ? html` (${blurb})` : nothing}; type a query to
        search within the range.
      </div>`;
    }
    return html`<div class="empty">Type to search across all indexed files.</div>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-search-surface')) {
  customElements.define('jf-search-surface', SearchSurface);
}
