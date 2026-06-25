// SPDX-License-Identifier: Apache-2.0
/**
 * LibrarySurface — slice 449 phase 7b calibration target, slice 450 polish.
 *
 * Bespoke specialty Lit element (Option-Y per slice 449 §7 D3): consumes
 * the {@code core.indexed-roots} TABULAR × ONE_SHOT Resource directly via
 * REST + the OperationClient for the 5 wired Operations.
 *
 * <p>Slice 450 §1 polish: Tauri runtime detection + browser-mode warning,
 * native folder picker (Tauri only), file-count fetch, relative-time
 * formatting, icon primitives, CTA color tokens, persisted-excludes
 * hydration via /api/settings/v2.
 *
 * <p>Side-effect registers {@code <jf-library-surface>} for the chrome
 * dispatcher.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import '../components/OpButton.js';
import '../components/Button.js';
// Tempdoc 571 §11 / 578 — Library ⊇ Browse: Library hosts the Browse file-tree as a tab.
import '../components/SurfaceTabs.js';
import type { SurfaceTabItem } from '../components/SurfaceTabs.js';
import { getSurface } from '../../api/registry/SurfaceCatalogClient.js';
import { present } from '../display/present.js';
import { takeMemberTabIntent, subscribeMemberTab } from '../router/memberTabIntent.js';
import { resolvePathLazy } from '../hooks/resolvePathLazy.js';
import { icon } from '../components/Icon.js';
// 569 §14 — render the indexed-folder cards through the projection engine (the 2nd real surface).
import { activeBodyFor, subscribePresentation } from '../state/presentationRuntime.js';
import { subscribeAiState } from '../state/aiStateStore.js';
import { LIBRARY_CARDS_REGION } from '../themes/builtinPresentations.js';
import '../components/DeclaredSurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
// Tempdoc 564 Phase 2: the indexed-roots wire shape is the generated IndexedRootView projection
// (record → JSON Schema → {TS, Zod}); validated at the fetch boundary, no hand interface.
import { z } from 'zod';
import { parseWireContract } from '../../api/schemas.js';
import {
  indexedRootViewSchema,
  type IndexedRootView,
} from '../../api/generated/schema-types/indexed-root-view.js';
// Tempdoc 599 §9.1 — the ONE per-folder status derivation; the row glyph + meta line project from it.
import { folderStatus } from '../state/folderStatus.js';
// Tempdoc 599 §16/B1 — the clickable "N failed" chip opens the per-folder failed-files drawer.
import { openFailedJobs } from '../state/failedJobsDrawer.js';
// Tempdoc 599 §9.4 — gate the Add button with a reachable reason (596 operability authority).
import { unavailableBecause, AVAILABLE } from '../state/availability.js';

/** Tempdoc 599 §9.4 — add-time path validation result from /api/indexing-roots/preview. */
interface FolderPreview {
  readonly path: string;
  readonly exists: boolean;
  readonly isDir: boolean;
  readonly fileCount: number;
  /** Tempdoc 599 Fix 3 — the count walk hit its cap; fileCount is a lower bound ("N+"). */
  readonly capped: boolean;
  readonly alreadyWatched: boolean;
}

// The substrate list response: a thin {items, count} envelope around the generated IndexedRootView.
const listResponseSchema = z
  .object({
    items: z.array(indexedRootViewSchema),
    count: z.number().optional(),
  })
  .loose();

const RESOURCE_ID = 'core.indexed-roots';
const ENDPOINT = '/api/indexing-roots/substrate';
const OP_ADD = 'core.add-watched-root';
const OP_REMOVE = 'core.remove-watched-root';
const OP_PREVIEW = 'core.preview-excludes';
const OP_APPLY = 'core.apply-excludes';

// Tempdoc 599 §9.3 — minimum spacing between aiState-tick-driven (counts-free) live refreshes, so
// the row updates while indexing without re-fetching faster than the underlying status cadence.
const LIVE_REFRESH_MIN_MS = 4000;

export class LibrarySurface extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    host_: { attribute: false },
    roots: { state: true },
    resolvedPaths: { state: true },
    loading: { state: true },
    error: { state: true },
    excludesText: { state: true },
    excludesLoaded: { state: true },
    pendingPath: { state: true },
    pendingPreview: { state: true },
    showManualInput: { state: true },
    isTauri: { state: true },
    activeTab: { state: true },
    provisional: { state: true },
  };

  declare apiBase: string;
  declare host_: PluginHostApi;
  declare roots: IndexedRootView[];
  declare resolvedPaths: Record<string, string>;
  declare loading: boolean;
  declare error: string | null;
  declare excludesText: string;
  declare excludesLoaded: boolean;
  declare pendingPath: string;
  /** Tempdoc 599 §9.4 — add-time validation result for the typed path (null = not yet checked). */
  declare pendingPreview: FolderPreview | null;
  declare showManualInput: boolean;
  declare isTauri: boolean;
  /** Active composition tab id: 'folders' (own body) or a member surface id. */
  declare activeTab: string;
  /**
   * 595 §4.3 — the backend is mid-transition (rebuild / worker restart / initial
   * load). While provisional, an empty `roots` is "unknown right now", NOT a
   * settled "no folders configured" — so we render the transition, not the
   * catastrophe-reading empty state. Projected from the one `Stability` axis.
   */
  declare provisional: boolean;

  private aiUnsub: (() => void) | null = null;
  // Tempdoc 599 §9.4 — debounce the add-time preview while typing.
  private previewTimer: number | null = null;
  private previewSeq = 0;

  constructor() {
    super();
    this.apiBase = '';
    this.host_ = undefined as unknown as PluginHostApi;
    this.roots = [];
    this.resolvedPaths = {};
    this.loading = false;
    this.error = null;
    this.excludesText = '';
    this.excludesLoaded = false;
    this.pendingPath = '';
    this.pendingPreview = null;
    this.showManualInput = false;
    this.isTauri = false;
    this.activeTab = 'folders';
    this.provisional = false;
  }

  // Tempdoc 571 §11 / 578: Library is a host surface — it delegates layout to <jf-surface-tabs>
  // (a display:contents pass-through, the layout-purity-approved host pattern, cf. AgentSurface→
  // AgentView). The Folders tab's own body scrolls inside `.folders-scroll`; the Browse member
  // surface carries its own SurfaceLayout.
  static styles = [
    css`
    :host {
      display: contents;
    }
    .folders-scroll {
      height: 100%;
      overflow-y: auto;
      padding: 1rem 1.5rem;
      box-sizing: border-box;
    }
    .header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;
      margin-bottom: 1rem;
    }
    .header-left h2 {
      margin: 0;
      font-size: var(--font-size-lg);
      font-weight: 600;
    }
    .header-left .subtitle {
      margin: 0.125rem 0 0 0;
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
    }
    .actions {
      display: flex;
      gap: 0.5rem;
      flex-shrink: 0;
    }
    /* 574 B1 — the button look is the jf-button atom's single authority; the per-surface
       base + primary/danger variant fork (§14 V2) is deleted. */
    .browser-banner {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.625rem 0.75rem;
      margin-bottom: 1rem;
      background: var(--accent-warning-08);
      border: 1px solid var(--accent-warning-30);
      border-radius: 0.375rem;
      color: var(--text-warning);
      font-size: var(--font-size-sm);
    }
    .cards {
      display: grid;
      gap: 0.5rem;
    }
    .card {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 0.75rem 1rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
    }
    .card-icon {
      flex-shrink: 0;
      color: var(--text-tint);
    }
    .card-info {
      flex: 1;
      min-width: 0;
    }
    .card-path {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-family: monospace;
      font-size: var(--font-size-sm);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .card-meta {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.25rem;
    }
    /* Tempdoc 599 §16/B1 — the clickable "N failed" chip (danger tone) opens the failed-files drawer. */
    .failed-chip {
      margin-left: 0.4rem;
      --jf-button-color: var(--text-danger);
      color: var(--text-danger);
    }
    .status-icon {
      flex-shrink: 0;
    }
    .status-icon.indexed {
      color: var(--text-success);
    }
    .status-icon.error {
      color: var(--text-danger);
    }
    .status-icon.pending {
      color: var(--text-warning);
    }
    .status-icon.unavailable {
      color: var(--text-secondary);
    }
    .status-icon.unverified {
      color: var(--text-warning);
    }
    .reindex-chip {
      margin-left: 0.4rem;
      --jf-button-color: var(--text-secondary);
      color: var(--text-secondary);
    }
    .empty {
      padding: 2rem;
      text-align: center;
      color: var(--text-secondary);
    }
    .error-banner {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.75rem;
      margin-bottom: 1rem;
      background: var(--accent-danger-08);
      border: 1px solid var(--accent-danger);
      border-radius: 0.375rem;
      color: var(--text-danger);
    }
    .error-banner button {
      margin-left: auto;
      background: transparent;
      border: none;
      color: inherit;
      padding: 0.25rem;
    }
    .add-row {
      display: flex;
      gap: 0.5rem;
      margin-bottom: 0.5rem;
    }
    .add-preview {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      margin-bottom: 1rem;
      font-size: var(--font-size-sm);
    }
    .add-preview.ok {
      color: var(--text-success);
    }
    .add-preview.err {
      color: var(--text-danger);
    }
    .add-preview.warn {
      color: var(--text-warning);
    }
    .add-row input {
      flex: 1;
      padding: 0.4rem 0.5rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      color: var(--text-primary);
      font-family: monospace;
      font-size: var(--font-size-sm);
    }
    .excludes-section {
      margin-top: 1.5rem;
      padding: 1rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
    }
    .excludes-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;
      margin-bottom: 0.5rem;
    }
    .excludes-header h3 {
      margin: 0;
      font-size: var(--font-size-md);
      font-weight: 600;
    }
    .excludes-header p {
      margin: 0.125rem 0 0 0;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    textarea {
      width: 100%;
      min-height: 6rem;
      padding: 0.5rem;
      background: var(--surface-tertiary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      color: var(--text-primary);
      font-family: monospace;
      font-size: var(--font-size-sm);
      box-sizing: border-box;
      resize: vertical;
    }
    .jf-icon-spin {
      animation: jf-spin 1s linear infinite;
    }
  `,
  ];

  // 569 §14 — re-render when the active presentation changes (the declared cards region appears
  // when CORE_DECLARED is applied, reverts when cleared/quarantined — degrade-never-fail).
  private presentationUnsub: (() => void) | null = null;
  private memberTabUnsub: (() => void) | null = null;
  // Tempdoc 599 §9.3 — live-refresh guards (overlap + throttle); not reactive state.
  private refreshing = false;
  private lastLiveRefreshAtMs = 0;

  override connectedCallback(): void {
    super.connectedCallback();
    // Tempdoc 571 §11 / 578 — if we were reached via a member deep-link (core.browse-surface →
    // redirected here), open that member's tab. Drain a pending intent (host mounting now) AND
    // subscribe (so a member deep-link while THIS host is already active still switches the tab).
    const requested = takeMemberTabIntent('core.library-surface');
    if (requested) this.activeTab = requested;
    this.memberTabUnsub = subscribeMemberTab((hostId, memberId) => {
      if (hostId !== 'core.library-surface') return false;
      this.activeTab = memberId;
      return true;
    });
    if (this.host_) {
      this.isTauri = this.host_.platform.capabilities.has('folder-picker');
    }
    void this.refresh();
    void this.loadExcludes();
    this.presentationUnsub = subscribePresentation(() => this.requestUpdate());
    // 595 §4.3 — observe the one Stability axis so an empty roots list during a
    // transition renders as "Rebuilding…", not "No watched folders".
    this.aiUnsub = subscribeAiState((s) => {
      this.provisional = s.stability.kind === 'provisional';
      // Tempdoc 599 §9.3 — ride the existing status tick to live-refresh the rows (counts-free, no
      // new poller), so a folder's "Indexing · N remaining → ✓ indexed" updates without re-nav.
      void this.refresh({ live: true });
    });
  }

  /** Tempdoc 609 — settle transient state on hide: the in-flight `loading` flag + last `error` (a stale
   *  spinner / error would otherwise survive navigation). Folder/excludes drafts, the active tab, and the
   *  fetched roots are recoverable and KEPT. */
  protected override settleTransients(): void {
    this.loading = false;
    this.error = null;
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.presentationUnsub?.();
    this.presentationUnsub = null;
    this.memberTabUnsub?.();
    this.memberTabUnsub = null;
    this.aiUnsub?.();
    this.aiUnsub = null;
    if (this.previewTimer !== null) {
      window.clearTimeout(this.previewTimer);
      this.previewTimer = null;
    }
  }

  /**
   * 569 §14 — the indexed-folder cards. When the active presentation declares the cards region,
   * project them through the engine (<jf-declared-surface> + the folder-card renderer) at bespoke
   * parity, pre-resolving each card's display fields; otherwise the built-in Lit render (the
   * quarantine fallback, degrade-never-fail). The Remove INTENT the renderer emits is handled here
   * (the gated operation + confirm stay surface-owned).
   */
  private renderCardsRegion(): TemplateResult {
    const body = activeBodyFor(LIBRARY_CARDS_REGION);
    if (!body) {
      return html`<div class="cards">${this.roots.map((r) => this.renderCard(r))}</div>`;
    }
    const folders = this.roots.map((r) => {
      const pathHash = r.pathHash ?? '';
      // Tempdoc 599 §9.1 — project the card's glyph (`status`) + `metaText` from the one seam, so the
      // declared FolderCardRenderer and the hand renderCard cannot diverge. `status` carries the
      // glyph token (indexed/error/pending); `metaText` carries the truthful state line. walkError is
      // already folded into metaText by the seam, so it is not passed again (no duplicate display).
      const fs = folderStatus(r, {
        relativeTime: this.host_.utilities.formatRelativeTime(r.lastIndexedIsoTime ?? ''),
        // Tempdoc 626 §Recency — the freshness heartbeat: when the index↔disk correspondence was last
        // confirmed (distinct from lastIndexed, the last write). Same host time-ago util, no new formatter.
        verifiedRelativeTime: this.host_.utilities.formatRelativeTime(r.lastVerifiedIsoTime ?? ''),
        provisional: this.provisional,
      });
      return {
        pathHash,
        displayPath: this.resolvedPaths[pathHash] ?? `[${pathHash.slice(0, 12)}…]`,
        status: fs.glyph,
        metaText: fs.metaText,
        walkError: undefined,
        // Tempdoc 599 §16/B1 — the clickable "N failed" chip's count (the declared renderer emits an
        // intent the surface handles by opening the failed-files drawer). Named `failed` (the seam's
        // output field), not `failedCount`, to stay clear of the folder-status-derivation gate's
        // raw-wire-field scan (only the seam reads `row.failedCount`).
        failed: fs.failed,
      };
    });
    return html`<jf-declared-surface
      .declaration=${body}
      .data=${{ folders }}
      .enabled=${true}
      @jf-folder-card-remove=${(e: Event) =>
        void this.handleRemoveRoot((e as CustomEvent<{ pathHash: string }>).detail.pathHash)}
      @jf-folder-card-show-failed=${(e: Event) =>
        openFailedJobs((e as CustomEvent<{ pathHash: string }>).detail.pathHash)}
    ></jf-declared-surface>`;
  }

  /**
   * Fetch the watched-root rows.
   *
   * <p>Tempdoc 599 §9.3: in `live` mode (the aiState-tick refresh) the request is COUNTS-FREE —
   * it skips the expensive per-root `Files.walk` (the §11/U6 cost) and relies on the cheap, always-
   * returned per-folder job counts (`inFlightCount`/`failedCount`) to drive the live status. The
   * filesystem `fileCount` (which a counts-free response reports as -1) is preserved from the last
   * full refresh by pathHash, so it does not flicker to "count pending". Live refreshes are
   * throttled and never toggle the loading spinner or clobber the error banner.
   */
  private async refresh(opts: { live?: boolean } = {}): Promise<void> {
    const live = opts.live === true;
    if (live) {
      if (this.refreshing) return;
      const sinceLast = Date.now() - this.lastLiveRefreshAtMs;
      if (sinceLast < LIVE_REFRESH_MIN_MS) return;
      this.lastLiveRefreshAtMs = Date.now();
    }
    this.refreshing = true;
    if (!live) {
      this.loading = true;
      this.error = null;
    }
    try {
      const res = await this.doFetch(live ? ENDPOINT : ENDPOINT + '?counts=true');
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      const body = parseWireContract(listResponseSchema, await res.json(), ENDPOINT);
      let items = body.items ?? [];
      if (live) {
        // Counts-free rows carry fileCount=-1; keep the last-known filesystem count per folder.
        const prev = new Map(this.roots.map((r) => [r.pathHash, r.fileCount]));
        items = items.map((r) => {
          if (r.fileCount != null && r.fileCount >= 0) return r;
          const known = r.pathHash ? prev.get(r.pathHash) : undefined;
          return known != null && known >= 0 ? { ...r, fileCount: known } : r;
        });
      }
      this.roots = items;
      // Lazy-resolve hashes via core.resolve-path-hash (slice 450 §1.1).
      for (const r of this.roots) {
        const hash = r.pathHash;
        if (hash && !this.resolvedPaths[hash]) {
          void resolvePathLazy(RESOURCE_ID, hash, { apiBase: this.apiBase }).then((path) => {
            if (path) {
              this.resolvedPaths = { ...this.resolvedPaths, [hash]: path };
            }
          });
        }
      }
    } catch (err) {
      // Live (background) failures stay silent — don't clobber the surface with a tick error.
      if (!live) {
        this.error = err instanceof Error ? err.message : String(err);
      }
    } finally {
      if (!live) {
        this.loading = false;
      }
      this.refreshing = false;
    }
  }

  private doFetch(path: string, init?: RequestInit): Promise<Response> {
    return this.host_.data.fetch(path, {
      method: init?.method,
      headers: init?.headers as Record<string, string> | undefined,
      body: init?.body as string | undefined,
    });
  }

  private async loadExcludes(): Promise<void> {
    try {
      const res = await this.doFetch('/api/settings/v2');
      if (!res.ok) return;
      const body = await res.json();
      const patterns: string[] = body?.ui?.excludePatterns ?? [];
      this.excludesText = patterns.join('\n');
      this.excludesLoaded = true;
    } catch {
      // Silent: excludes section still works, just starts empty.
      this.excludesLoaded = true;
    }
  }

  private async persistExcludes(): Promise<void> {
    const patterns = this.excludesText
      .split('\n')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    try {
      await this.doFetch('/api/settings/v2', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ui: { excludePatterns: patterns } }),
      });
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  private async invoke(
    operationId: string,
    args: Record<string, unknown> = {},
  ): Promise<void> {
    this.error = null;
    try {
      await this.host_.data.invokeOperation(operationId, args);
      await this.refresh();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  private async handleAddRoot(): Promise<void> {
    if (this.host_.platform.capabilities.has('folder-picker')) {
      const picked = await this.host_.platform.pickFolder();
      if (picked) {
        await this.invoke(OP_ADD, { path: picked });
        return;
      }
      this.showManualInput = true;
      return;
    }
    // Browser mode: use the inline manual-input flow.
    if (!this.showManualInput) {
      this.showManualInput = true;
      return;
    }
    const path = this.pendingPath.trim();
    if (!path) return;
    // Tempdoc 599 §9.4 — don't create the job if the preview already says it can't be added.
    if (this.addAvailability().kind !== 'available') return;
    await this.invoke(OP_ADD, { path });
    this.pendingPath = '';
    this.pendingPreview = null;
    this.showManualInput = false;
  }

  /** Tempdoc 599 §9.4 — typed-path input: update the path + (debounced) validate it. */
  private onPendingPathInput(value: string): void {
    this.pendingPath = value;
    this.pendingPreview = null;
    if (this.previewTimer !== null) {
      window.clearTimeout(this.previewTimer);
      this.previewTimer = null;
    }
    const path = value.trim();
    if (!path) return;
    this.previewTimer = window.setTimeout(() => void this.fetchPreview(path), 350);
  }

  /** Tempdoc 599 §9.4 — fetch add-time validation for a typed path; last-write-wins via previewSeq. */
  private async fetchPreview(path: string): Promise<void> {
    const seq = ++this.previewSeq;
    try {
      const res = await this.doFetch('/api/indexing-roots/preview', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ path }),
      });
      if (!res.ok) return;
      const body = await res.json();
      if (seq !== this.previewSeq) return; // a newer keystroke superseded this result
      this.pendingPreview = {
        path,
        exists: Boolean(body?.exists),
        isDir: Boolean(body?.isDir),
        fileCount: typeof body?.fileCount === 'number' ? body.fileCount : -1,
        capped: Boolean(body?.capped),
        alreadyWatched: Boolean(body?.alreadyWatched),
      };
    } catch {
      // Silent: preview is advisory; the Add path still validates server-side.
    }
  }

  /**
   * Tempdoc 599 §9.4 — the Add button's availability, gated on the preview (596 reachable reason).
   * Available while still typing/checking (so the button isn't dead before the preview lands); a
   * settled preview that can't be added yields a soft `unavailable` with a reachable reason.
   */
  private addAvailability() {
    const path = this.pendingPath.trim();
    if (!path) return unavailableBecause('Enter a folder path', true);
    const p = this.pendingPreview;
    if (!p || p.path !== path) return AVAILABLE; // checking — don't block prematurely
    if (!p.exists) return unavailableBecause('Path not found');
    if (!p.isDir) return unavailableBecause('Not a folder');
    if (p.alreadyWatched) return unavailableBecause('Already watched');
    return AVAILABLE;
  }

  private async handleRemoveRoot(pathHash: string): Promise<void> {
    const resolved = this.resolvedPaths[pathHash];
    if (!resolved) {
      this.error = 'Path resolution pending; retry in a moment.';
      return;
    }
    const ok = await this.host_.ui.showConfirmDialog(
      `Remove ${resolved}? Files indexed from this folder will be removed from search results.`,
      { confirmLabel: 'Remove', destructive: true },
    );
    if (!ok) return;
    await this.invoke(OP_REMOVE, { path: resolved });
  }

  /**
   * Tempdoc 626 §Axis-C — the one-click recovery for an "unverified" folder (its reconcile couldn't
   * verify deletions). {@code core.reindex} is incremental-all-roots; {@code force:true} bypasses the
   * unchanged fast-path so deletions are re-checked and the per-root unverified state clears once the
   * reconcile verifies it. {@link #invoke} surfaces errors and refreshes the rows on completion.
   */
  private async handleReindexToVerify(): Promise<void> {
    await this.invoke('core.reindex', { force: true });
  }

  private async handlePreviewExcludes(): Promise<void> {
    await this.persistExcludes();
    await this.invoke(OP_PREVIEW, {});
  }

  private async handleApplyExcludes(): Promise<void> {
    await this.persistExcludes();
    await this.invoke(OP_APPLY, {});
  }

  private renderStatusIcon(status: string): TemplateResult {
    if (status === 'indexed') {
      return html`<span class="status-icon indexed"
        >${icon({ name: 'check-circle-2', size: 16 })}</span
      >`;
    }
    if (status === 'error') {
      return html`<span class="status-icon error"
        >${icon({ name: 'alert-circle', size: 16 })}</span
      >`;
    }
    if (status === 'unavailable') {
      // Tempdoc 599 §16/A1 — folder path gone: a muted x-circle (not the red error glyph).
      return html`<span class="status-icon unavailable"
        >${icon({ name: 'x-circle', size: 16 })}</span
      >`;
    }
    if (status === 'unverified') {
      // Tempdoc 626 §Axis-C — indexed but deletions couldn't be verified: a muted caution glyph,
      // never the green ✓ (the folder is searchable but its delete correspondence is unknown).
      return html`<span class="status-icon unverified"
        >${icon({ name: 'alert-triangle', size: 16 })}</span
      >`;
    }
    return html`<span class="status-icon pending"
      >${icon({ name: 'clock', size: 16 })}</span
    >`;
  }

  private renderCard(root: IndexedRootView): TemplateResult {
    const pathHash = root.pathHash ?? '';
    const displayPath = this.resolvedPaths[pathHash] ?? `[${pathHash.slice(0, 12)}…]`;
    // Tempdoc 599 §9.1 — glyph + meta line project from the single folderStatus seam, so the row
    // reports a truthful state (✓ only on job drain) and a live "Indexing · N remaining" count-down.
    const status = folderStatus(root, {
      relativeTime: this.host_.utilities.formatRelativeTime(root.lastIndexedIsoTime ?? ''),
      // Tempdoc 626 §Recency — the freshness heartbeat (last index↔disk confirmation), distinct from
      // lastIndexed (last write). Same host time-ago util.
      verifiedRelativeTime: this.host_.utilities.formatRelativeTime(root.lastVerifiedIsoTime ?? ''),
      provisional: this.provisional,
    });
    return html`
      <div class="card">
        <span class="card-icon">${icon({ name: 'folder', size: 24 })}</span>
        <div class="card-info">
          <div class="card-path">
            ${this.renderStatusIcon(status.glyph)}<span>${displayPath}</span>
          </div>
          <div class="card-meta">
            ${status.metaText}${status.failed > 0
              ? html`<jf-button
                  class="failed-chip"
                  variant="ghost"
                  size="sm"
                  label=${`Show ${status.failed} failed file${status.failed === 1 ? '' : 's'}`}
                  .onActivate=${() => openFailedJobs(pathHash)}
                  >${icon({ name: 'alert-circle', size: 12 })} ${status.failed} failed</jf-button
                >`
              : nothing}${status.state === 'unverified'
              ? html`<jf-button
                  class="reindex-chip"
                  variant="ghost"
                  size="sm"
                  label="Reindex to verify"
                  .onActivate=${() => this.handleReindexToVerify()}
                  >${icon({ name: 'refresh-cw', size: 12 })} Reindex</jf-button
                >`
              : nothing}
          </div>
        </div>
        <jf-button
          variant="danger"
          label="Remove"
          .onActivate=${() => this.handleRemoveRoot(pathHash)}
        >
          ${icon({ name: 'trash-2', size: 14 })} Remove
        </jf-button>
      </div>
    `;
  }

  private renderHeader(): TemplateResult {
    return html`
      <div class="header">
        <div class="header-left">
          <h2>Library</h2>
          <p class="subtitle">Manage your indexed folders</p>
        </div>
        <div class="actions">
          <!-- Tempdoc 511 Phase 9: <jf-operation> aggregate component
               supersedes the slice 509 <jf-op-button>. LibrarySurface
               is user-tier; core.reindex's wire audience=USER passes
               the gate. -->
          <jf-operation context="button" operation-id="core.reindex" api-base=${this.apiBase} @op-success=${() => this.refresh()}></jf-operation>
          <jf-button
            variant="primary"
            label="Add Folder"
            .onActivate=${() => this.handleAddRoot()}
          >
            ${icon({ name: 'folder-plus', size: 14 })} Add Folder
          </jf-button>
        </div>
      </div>
    `;
  }

  override render(): TemplateResult {
    // Tempdoc 571 §11 / 578 — host/member composition: tab 0 is Library's own "Folders" body
    // (projected via a slot so this surface's shadow CSS styles it); the remaining tabs are the
    // declared member surfaces (Browse), mounted by <jf-surface-tabs>. Members are read from the
    // live catalog (the wire's `members`), so a new member is purely a catalog declaration.
    const members = getSurface('core.library-surface')?.members ?? [];
    const items: SurfaceTabItem[] = [
      { id: 'folders', label: 'Folders', altitude: 'PRODUCT', slot: 'tab-folders' },
      ...members.map((mid) => ({
        id: mid,
        label: present({ kind: 'surface', id: mid }).label,
        altitude: getSurface(mid)?.altitude,
        surfaceId: mid,
      })),
    ];
    // A single declared member with no host-body of interest could render bare; but Library always
    // has its Folders body, so the tab strip is always meaningful.
    return html`
      <jf-surface-tabs
        tablist-label="Library views"
        api-base=${this.apiBase}
        .host_=${this.host_}
        active-id=${this.activeTab}
        .items=${items}
        @tab-change=${(e: CustomEvent<{ id: string }>) => (this.activeTab = e.detail.id)}
      >
        <div slot="tab-folders" class="folders-scroll">${this.renderFolders()}</div>
      </jf-surface-tabs>
    `;
  }

  /** Tempdoc 599 §9.4 — the add-time preview status line below the path input. */
  private renderPreviewLine(): TemplateResult {
    const path = this.pendingPath.trim();
    const p = this.pendingPreview;
    if (!path || !p || p.path !== path) return html``;
    if (!p.exists) {
      return html`<div class="add-preview err">${icon({ name: 'alert-circle', size: 14 })} Path not found</div>`;
    }
    if (!p.isDir) {
      return html`<div class="add-preview err">${icon({ name: 'alert-circle', size: 14 })} Not a folder</div>`;
    }
    if (p.alreadyWatched) {
      return html`<div class="add-preview warn">${icon({ name: 'alert-circle', size: 14 })} Already watched</div>`;
    }
    const files =
      p.fileCount >= 0
        ? ` · ${p.fileCount.toLocaleString()}${p.capped ? '+' : ''} ${p.fileCount === 1 && !p.capped ? 'file' : 'files'}`
        : '';
    return html`<div class="add-preview ok">${icon({ name: 'check-circle-2', size: 14 })} Folder found${files}</div>`;
  }

  private renderFolders(): TemplateResult {
    return html`
      ${this.renderHeader()}
      ${!this.isTauri
        ? html`<div class="browser-banner">
            ${icon({ name: 'alert-circle', size: 14 })}
            <span
              >Running in browser mode. Native folder picker not available — enter
              paths manually.</span
            >
          </div>`
        : nothing}

      ${this.error
        ? html`<div class="error-banner">
            ${icon({ name: 'alert-circle', size: 14 })}
            <span>${this.error}</span>
            <jf-button
              variant="ghost"
              size="icon"
              label="Dismiss error"
              .onActivate=${() => (this.error = null)}
              >${icon({ name: 'x', size: 12 })}</jf-button
            >
          </div>`
        : nothing}

      ${this.showManualInput
        ? html`
            <div class="add-row">
              <input
                type="text"
                placeholder="C:\\path\\to\\folder"
                .value=${this.pendingPath}
                @keydown=${(e: KeyboardEvent) => {
                  if (e.key === 'Enter') void this.handleAddRoot();
                }}
                @input=${(e: Event) =>
                  this.onPendingPathInput((e.target as HTMLInputElement).value)}
              />
              <jf-button
                variant="primary"
                label="Add"
                .availability=${this.addAvailability()}
                .onActivate=${() => this.handleAddRoot()}
                >Add</jf-button
              >
              <jf-button label="Cancel" .onActivate=${() => (this.showManualInput = false)}
                >Cancel</jf-button
              >
            </div>
            ${this.renderPreviewLine()}
          `
        : nothing}

      ${this.loading
        ? html`<div class="empty">Loading…</div>`
        : this.roots.length === 0
          ? this.provisional
            ? html`<div class="empty">
                Rebuilding index… your watched folders will reappear when it finishes.
              </div>`
            : html`<div class="empty">
              No watched folders. Click "Add Folder" to add one.
            </div>`
          : this.renderCardsRegion()}

      <div class="excludes-section">
        <div class="excludes-header">
          <div>
            <h3>Exclude patterns</h3>
            <p>
              One glob per line. Bare names like <code>node_modules</code> or
              <code>dist/</code> match at any depth.
            </p>
          </div>
          <div class="actions">
            <jf-button label="Preview" .onActivate=${() => void this.handlePreviewExcludes()}
              >Preview</jf-button
            >
            <jf-button label="Apply" .onActivate=${() => void this.handleApplyExcludes()}
              >Apply</jf-button
            >
          </div>
        </div>
        <textarea
          .value=${this.excludesText}
          @input=${(e: Event) =>
            (this.excludesText = (e.target as HTMLTextAreaElement).value)}
          placeholder="**/node_modules/**"
        ></textarea>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-library-surface')) {
  customElements.define('jf-library-surface', LibrarySurface);
}
