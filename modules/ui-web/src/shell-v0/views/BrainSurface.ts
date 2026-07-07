// SPDX-License-Identifier: Apache-2.0
/**
 * BrainSurface — Lit-side Brain rail surface (slice 452 phase 9).
 *
 * Self-mounting Surface with full functional parity to React BrainView:
 * install/cancel/repair AI, simple/advanced mode toggle, GPU runtime
 * variant activation, inference mode switching, pack import (Tauri),
 * LLM settings, policy banners, runtime status display.
 *
 * Visual presentation differs from React (no Tailwind / Framer Motion;
 * uses Lit + framework-agnostic CSS) — operationally equivalent.
 *
 * Side-effect registers `<jf-brain-surface>` for the chrome dispatcher.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
// Tempdoc 571 §11 / 578 — surfaceScrollLayoutStyles dropped: Brain is now a display:contents tab host.
import { activateOnKey } from '../utils/keyboardHandler.js';
// Tempdoc 571 §11 / 578 — AI Brain ⊇ Memory: Brain hosts the AI's learned-memory as a tab.
import '../components/SurfaceTabs.js';
import type { SurfaceTabItem } from '../components/SurfaceTabs.js';
import { getSurface } from '../../api/registry/SurfaceCatalogClient.js';
import { present } from '../display/present.js';
import { formatBytes } from '../display/format.js';
import { projectFact } from '../display/facts.js';
import { takeMemberTabIntent, subscribeMemberTab } from '../router/memberTabIntent.js';
import '../components/OpButton.js';
import '../components/Button.js';
import '../components/ErrorAlert.js';
import { OperationClient, OperationError, getOperationClient } from '../operations/OperationClient.js';
import { getOperation } from '../../api/registry/OperationCatalogClient.js';
import { isTauriRuntime } from '../../utils/tauriRuntime.js';
import { pickFolder } from '../../utils/folderPicker.js';
import {
  subscribeAiState,
  type AiState as UnifiedAiState,
  type InstallStatus,
  type AiRuntimeStatus,
  type PackImportStatus,
} from '../state/aiStateStore.js';
// Tempdoc 657 — pre-install per-tier weight breakdown (GET /api/ai/install/plan-preview).
import type { InstallPlanPreview } from '../utils/aiInstallPoll.js';
import { applyLocalIntent, type AiEngineVerdict, type AiStability } from '../state/aiVerdict.js';
import { unavailableBecause, AVAILABLE } from '../state/availability.js';
// Tempdoc 613 — coherence: the compat callout words its cause from the ONE canonical reindex
// vocabulary (the same `reasonFor`/CAUSE_ROWS the Chat degradation banner + 595 verdict use),
// so the same condition cannot be worded differently across surfaces.
import { isReindexCause, reasonFor } from '../state/readinessNotice.js';
import { formatStartupEstimate, humanizeSeconds, elapsedSecondsSince } from '../state/startupEstimate.js';
import { isAiInstallLive } from '../substrates/ai/aiInstallLiveness.js';
import { icon } from '../components/Icon.js';
// Tempdoc 586 §F-1a — reuse the existing pulse-dots primitive for the first-paint skeleton.
import '../components/chat/PulseDots.js';
import { confirmAsync } from '../components/ConfirmDialog.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
// Tempdoc 564 Phase B (4b): EffectivePolicy is the single generated wire-contract projection.
import type { EffectivePolicy } from '../../api/generated/schema-types/effective-policy.js';

// Tempdoc 663 Stage 3/5 — InstallStatus/AiRuntimeStatus/PackImportStatus moved to
// `utils/aiInstallPoll.ts` (re-exported by `state/aiStateStore.ts`), the shared, always-on poller
// that replaced this surface's one-shot `refreshAll()` fetches. Imported below, not redeclared.

/**
 * Tempdoc 518 Appendix F W3.2 — one row of /api/inference/transitions response.
 */
interface TransitionRecord {
  timestampMs: number;
  fromMode: string;
  toMode: string;
  reason: string;
  success: boolean;
  durationMs: number;
  wireCode?: string;
}

/**
 * Tempdoc 518 Appendix G Wave D.1 — one span row from /api/diagnostics/traces.
 * Mirrors the NdjsonSpanExporter line format.
 */
interface TraceSpan {
  trace_id?: string;
  span_id?: string;
  parent_span_id?: string;
  name?: string;
  start?: string;
  end?: string;
  duration_ms?: number;
  status?: string;
  attrs?: Record<string, unknown>;
}

interface TraceExplorerResponse {
  spans?: TraceSpan[];
  tracesAvailable?: boolean;
}

// Tempdoc 586 §3 — local InferenceStatus interface removed; `inference` is now
// typed as the shared store's snapshot (`UnifiedAiState['inference']`), which was
// extended with `generation` + `lastStartupDurationMs` (already on the wire).

// Tempdoc 586 §3 — local SystemStatus interface removed; `systemStatus` is now the
// shared store's status snapshot (`UnifiedAiState['status']` = generated StatusResponse,
// which carries the embedding/schema fields this surface reads).

interface UiSettings {
  mode?: 'simple' | 'advanced';
}

interface LlmSettings {
  serverExecutable?: string | null;
  contextWindow?: number;
  maxTokens?: number;
  gpuLayers?: number;
  modelPath?: string | null;
  llamaLibPath?: string | null;
}

const NUM = new Intl.NumberFormat();

// Tempdoc 663 — the local `friendlyModel()` formatter (model-label cleanup) was removed; its one call
// site now projects `core.ai.model` via `projectFact`, which reads `aiState.runtime.modelLabel` — the
// SAME friendly-formatting `aiStateStore.ts`'s own `friendlyModel()` already applies at the source, so
// this view no longer needs a second copy of that logic.

/**
 * Tempdoc 518 Appendix F W3.1 — restart-ETA badge.
 *
 * Returns the "Starting…" subtitle copy. When the runtime has reported a prior
 * successful-startup duration (lastStartupDurationMs >= 0 from /api/inference/status),
 * surface it as an ETA hint: "Usually takes ~Ns." Otherwise return the generic fallback.
 *
 * Tempdoc 601 — the number now comes from the ONE shared `formatStartupEstimate` helper (the
 * sole `<0 → unknown` + seconds-format decision), shared with the affordance-bar reason projector
 * (`projectAvailability`) so the estimate is not forked across surfaces. This keeps BrainSurface's
 * own "AI is initializing." sentence (label+sub structure) and shares only the NUMBER.
 *
 * Tempdoc 601 §20 — when a live load-start stamp is present, show the MEASURED elapsed too (a count-up,
 * never a countdown), mirroring the status pill's "Starting… Ns" so this deep-dive screen also reflects
 * the §18 "show both" mapping: "AI is initializing — 12s (usually ~6s)". Below the `>2s` gate (or with no
 * stamp) it falls back to the prior static copy, so existing call sites are unchanged.
 */
export function formatRestartEtaSub(
  lastStartupDurationMs: number | undefined | null,
  loadStartedAtMs?: number | null,
): string {
  const typical = formatStartupEstimate(lastStartupDurationMs); // "~6s" | null
  const elapsed = elapsedSecondsSince(loadStartedAtMs ?? null);
  if (elapsed > 2) {
    return typical
      ? `AI is initializing — ${humanizeSeconds(elapsed)} (usually ${typical})`
      : `AI is initializing — ${humanizeSeconds(elapsed)}`;
  }
  return typical === null ? 'AI is initializing.' : `AI is initializing. Usually takes ${typical}.`;
}

/**
 * Tempdoc 663 Design pass 2 (critical-review fix, 2026-07-01) — should the Runtime section's
 * GPU/VRAM/Tier grid dim (a previous reading, genuinely about to change)? Mirrors the ONE
 * `provisional` convention used identically in StatusDeck/HealthSurface/BrowseSurface/LibrarySurface
 * (`stability.kind === 'provisional'`) — but narrowed to only the causes that actually mean "this GPU
 * reading may be superseded shortly": `installing`/`starting`/`switching-variant`. Deliberately
 * EXCLUDES `checking`/`stale-poll` — those are about DATA FRESHNESS of a DIFFERENT fact (the
 * install/runtime status hasn't arrived or confirmed yet), not about these specific GPU values being
 * about to change. A retained `this.inference.gpu` snapshot from an earlier successful poll
 * (inferencePoll retains last-known-good) can coexist with `aiEngine.kind === 'connecting'` right
 * after a fresh mount — without this narrowing, the grid would dim for a reason unrelated to its own
 * purpose.
 */
export function isGpuReadingProvisional(stability: AiStability | undefined): boolean {
  return (
    stability?.kind === 'provisional' &&
    (stability.cause === 'installing' ||
      stability.cause === 'starting' ||
      stability.cause === 'switching-variant')
  );
}

/**
 * 574 A3 — map the legacy `.status-dot.<state>` class word onto the `jf-status-dot`
 * atom's (tone, live) projection. The bespoke per-state dot CSS (online glow / starting
 * + installing pulse) is replaced by the one status-dot atom; the in-progress states
 * (`starting`/`installing`) drive its `live` pulse.
 */
function brainDotTone(dot: string): {
  tone: 'success' | 'warning' | 'error' | 'info' | 'neutral';
  live: boolean;
} {
  switch (dot) {
    case 'online':
      return { tone: 'success', live: false };
    case 'starting':
      return { tone: 'warning', live: true };
    case 'installing':
      return { tone: 'info', live: true };
    case 'offline':
    case 'notinstalled':
    default:
      return { tone: 'neutral', live: false };
  }
}

export class BrainSurface extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    host_: { attribute: false },
    settings: { state: true },
    llm: { state: true },
    inference: { state: true },
    installStatus: { state: true },
    planPreview: { state: true },
    runtimeStatus: { state: true },
    policy: { state: true },
    packStatus: { state: true },
    systemStatus: { state: true },
    expanded: { state: true },
    runtimeError: { state: true },
    busy: { state: true },
    refreshing: { state: true },
    // Tempdoc 518 Appendix F W3.2 — mode-transition timeline.
    transitions: { state: true },
    // Tempdoc 518 Appendix G Wave D.1 — trace explorer state.
    recentSpans: { state: true },
    tracesAvailable: { state: true },
    activeTab: { state: true },
  };

  declare apiBase: string;
  /**
   * Tempdoc 508-followup §ε2 — host API for migrating off direct
   * platform / dialog / operation-client imports. Optional for
   * back-compat with mount paths that don't inject it yet; the
   * private helpers below transparently fall back to the direct
   * imports when {@code host_} is undefined.
   */
  declare host_: PluginHostApi | undefined;
  declare settings: UiSettings;
  declare llm: LlmSettings;
  // Tempdoc 586 §3 — `inference` + `systemStatus` are sourced from the shared
  // aiStateStore (the single observed-state authority, aiStateStore.ts §B7),
  // not a second poll BrainSurface runs itself. Typed as the store's snapshots.
  declare inference: UnifiedAiState['inference'];
  declare installStatus: InstallStatus | null;
  declare planPreview: InstallPlanPreview | null;
  declare runtimeStatus: AiRuntimeStatus | null;
  declare policy: EffectivePolicy | null;
  declare packStatus: PackImportStatus | null;
  declare systemStatus: UnifiedAiState['status'];
  declare expanded: Record<string, boolean>;
  declare runtimeError: string | null;
  declare busy: Record<string, boolean>;
  declare refreshing: boolean;
  /** Active composition tab id: 'runtime' (own body) or a member surface id. */
  declare activeTab: string;
  declare transitions: TransitionRecord[];
  /** Tempdoc 518 Appendix G Wave D.1 — recent spans for the in-product trace explorer. */
  declare recentSpans: TraceSpan[];
  declare tracesAvailable: boolean;

  private clientRef: OperationClient | null = null;
  private _unifiedAiState: UnifiedAiState | null = null;
  private unsubAi: (() => void) | null = null;
  private memberTabUnsub: (() => void) | null = null;
  private pollDiagnostics: number | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.settings = {};
    this.llm = {};
    this.inference = null;
    this.installStatus = null;
    this.planPreview = null;
    this.runtimeStatus = null;
    this.policy = null;
    this.packStatus = null;
    this.systemStatus = null;
    // Advanced sections start with Runtime open (matching React) + Models
    // collapsed by default. Install AI / Search Quality Features collapsed
    // unless install is in flight.
    this.expanded = { runtime: true };
    this.runtimeError = null;
    this.busy = {};
    this.refreshing = false;
    this.transitions = [];
    this.recentSpans = [];
    this.tracesAvailable = false;
    this.activeTab = 'runtime';
  }

  static styles = [
    css`
    /* Tempdoc 571 §11 / 578 — Brain is a host surface: display:contents pass-through (layout-purity)
       delegating layout to <jf-surface-tabs>. Its own "AI Brain" body scrolls inside .brain-scroll;
       the Memory member carries its own SurfaceLayout. */
    :host {
      display: contents;
    }
    .brain-scroll {
      height: 100%;
      overflow-y: auto;
      color: var(--text-primary);
      font-family: system-ui, -apple-system, sans-serif;
    }
    .header {
      position: sticky;
      top: 0;
      z-index: 1;
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;
      padding: 1rem 1.5rem;
      background: var(--surface-1);
      border-bottom: 1px solid var(--border-subtle);
    }
    .header h2 {
      margin: 0;
      font-size: var(--font-size-lg);
      font-weight: 600;
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    .header .subtitle {
      margin: 0.125rem 0 0 0;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .body {
      padding: 1rem 1.5rem;
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }
    /* 574 B1 — generic action buttons are the jf-button atom now; the .icon-btn /
       .primary / .danger fork is deleted. The base button{} + .mode-toggle rules below
       stay for the bespoke segmented mode-toggle + inline-styled disclosure affordances. */
    button {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      background: var(--surface-primary);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      padding: 0.4rem 0.75rem;
      cursor: pointer;
      font-size: var(--font-size-sm);
      transition: background var(--duration-fast) var(--ease-standard);
    }
    button:hover:not(:disabled) {
      background: var(--surface-hover);
    }
    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .section {
      padding: 1rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
    }
    .section h3 {
      margin: 0 0 0.5rem 0;
      font-size: var(--font-size-xs);
      font-weight: 600;
      letter-spacing: 0.05em;
      text-transform: uppercase;
      color: var(--text-secondary);
    }
    .status-row {
      display: flex;
      align-items: center;
      gap: 0.75rem;
    }
    .progress {
      height: 0.5rem;
      background: var(--surface-tertiary);
      border-radius: 9999px;
      overflow: hidden;
    }
    .progress-bar {
      height: 100%;
      background: linear-gradient(90deg, var(--accent-tint), var(--accent-tint));
      transition: width var(--duration-normal) var(--ease-standard);
    }
    .grid {
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: 0.375rem 1rem;
      font-size: var(--font-size-sm);
    }
    .grid .key {
      color: var(--text-secondary);
    }
    .grid .val {
      color: var(--text-primary);
      text-align: right;
      font-family: monospace;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .row {
      display: flex;
      gap: 0.5rem;
      flex-wrap: wrap;
    }
    .actions-right {
      display: flex;
      gap: 0.5rem;
      justify-content: flex-end;
    }
    label.field {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    input[type='text'],
    input[type='number'] {
      padding: 0.375rem 0.5rem;
      background: var(--surface-tertiary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      color: var(--text-primary);
      font-size: var(--font-size-sm);
      font-family: monospace;
    }
    .empty-state {
      padding: 2rem;
      text-align: center;
      color: var(--text-secondary);
    }
    .mode-toggle {
      display: inline-flex;
      gap: 0.25rem;
      padding: 0.25rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
    }
    .mode-toggle button {
      border: none;
      background: transparent;
      padding: 0.25rem 0.625rem;
      font-size: var(--font-size-xs);
    }
    .mode-toggle button.active {
      background: var(--accent-tint);
      color: var(--accent-on-tint);
    }
    .variant {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 0.625rem 0.75rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      margin-bottom: 0.5rem;
    }
    .variant.active {
      border-color: var(--accent-success);
      background: var(--accent-success-08);
    }
    .variant-info {
      flex: 1;
      min-width: 0;
    }
    .variant-label {
      font-size: var(--font-size-sm);
      font-weight: 500;
    }
    .variant-meta {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.125rem;
    }
    .jf-icon-spin {
      animation: jf-spin 1s linear infinite;
    }
    code {
      font-family: monospace;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
      background: var(--surface-tertiary);
      padding: 0.125rem 0.25rem;
      border-radius: 0.25rem;
    }
  `,
  ];

  override connectedCallback(): void {
    super.connectedCallback();
    // Tempdoc 571 §11 / 578 — if reached via a member deep-link (core.memory-surface → redirected
    // here), open that member's tab. Drain a pending intent (mounting now) AND subscribe (member
    // deep-link while THIS host is already active still switches the tab).
    const requested = takeMemberTabIntent('core.brain-surface');
    if (requested) this.activeTab = requested;
    this.memberTabUnsub = subscribeMemberTab((hostId, memberId) => {
      if (hostId !== 'core.brain-surface') return false;
      this.activeTab = memberId;
      return true;
    });
    void this.refreshAll();
    this.startDiagnosticsPolling();
    // Tempdoc 586 §3 — the shared aiStateStore is the single observed-state authority
    // for inference + system status (aiStateStore.ts §B7); mirror its snapshots here
    // instead of running a second poll for them.
    this.unsubAi = subscribeAiState((s) => {
      this._unifiedAiState = s;
      // Adopt only non-null snapshots: the store retains its last-known snapshot when
      // the connection goes stale (aiStateStore §B7), so a null here means "no data
      // yet" (pre-first-poll) and must not clobber an already-known value.
      if (s.inference) this.inference = s.inference;
      if (s.status) this.systemStatus = s.status;
      // Tempdoc 663 Stage 3/5 — install/runtime/pack status now come from the shared, always-on
      // `aiInstallPoll` (via aiStateStore), replacing this surface's own one-shot fetch + the
      // conditionally-armed pollInstall/pollPack/pollRuntime timers (which only ever self-armed
      // AFTER a prior fetch had already succeeded — the structural cause of the "stuck on
      // Connecting… forever" bug, tempdoc 663 §O). Same non-null-adopt rule as inference/status.
      if (s.installStatus) this.installStatus = s.installStatus;
      if (s.runtimeStatus) this.runtimeStatus = s.runtimeStatus;
      if (s.packStatus) this.packStatus = s.packStatus;
      this.requestUpdate();
    });
  }

  /** Tempdoc 609 — settle transient state on hide (in-flight refresh / op errors / per-op busy locks)
   *  so a return doesn't show a stale spinner or a locked control. Statuses, expanded sections, and the
   *  active tab are recoverable and untouched. */
  protected override settleTransients(): void {
    this.refreshing = false;
    this.runtimeError = null;
    this.busy = {};
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.stopAllPolling();
    this.unsubAi?.();
    this.memberTabUnsub?.();
    this.memberTabUnsub = null;
  }

  private client(): OperationClient {
    if (!this.clientRef) {
      const apiBase =
        this.apiBase ||
        (typeof globalThis !== 'undefined' &&
        (globalThis as { location?: { origin?: string } }).location?.origin
          ? (globalThis as { location: { origin: string } }).location.origin
          : '');
      this.clientRef = getOperationClient(apiBase);
    }
    return this.clientRef;
  }

  private base(): string {
    return this.apiBase || '';
  }

  private async fetchJson<T>(path: string, init?: RequestInit): Promise<T | null> {
    try {
      const res = await fetch(this.base() + path, init);
      if (!res.ok) return null;
      return (await res.json()) as T;
    } catch {
      return null;
    }
  }

  private async refreshAll(): Promise<void> {
    if (!this.apiBase && this.apiBase !== '') return;
    this.refreshing = true;
    try {
      // Tempdoc 586 §3 / 663 Stage 3 — inference + system status AND install/runtime/pack status
      // are NOT fetched here; all five come from the shared aiStateStore subscription
      // (connectedCallback), which is always-on and self-healing. Only settings/policy — which have
      // no shared poller and are genuinely one-shot facts — are fetched on mount.
      const [settings, policy, preview] = await Promise.all([
        this.fetchJson<{ ui?: UiSettings; llm?: LlmSettings }>('/api/settings/v2'),
        this.fetchJson<EffectivePolicy>('/api/policy/effective'),
        // Tempdoc 657 — honest per-tier download weight, computed side-effect-free by the planner.
        this.fetchJson<InstallPlanPreview>('/api/ai/install/plan-preview'),
      ]);
      if (settings) {
        this.settings = settings.ui ?? {};
        this.llm = settings.llm ?? {};
      }
      if (policy) this.policy = policy;
      if (preview) this.planPreview = preview;
    } finally {
      this.refreshing = false;
    }
  }

  private toggleSection(key: string): void {
    this.expanded = { ...this.expanded, [key]: !this.expanded[key] };
  }

  /**
   * Tempdoc 575 §17 Face C: is the install "running" but its backend owner gone quiet? Derived from
   * the ONE polled-state liveness authority ({@link isAiInstallLive}) — never inline — so the badge
   * cannot be re-implemented per site (the inflight-liveness gate registers this render site).
   */
  private installStalled(): boolean {
    return (
      this.installStatus?.state === 'running' &&
      !isAiInstallLive(this.installStatus.updatedAtEpochMs ?? 0)
    );
  }

  // Tempdoc 663 Stage 3/5 — maybeStartInstallPolling/maybeStartPackPolling/maybeStartRuntimePolling
  // removed. They only self-armed AFTER a prior fetch had already succeeded with `state:'running'`,
  // so a failed/slow FIRST fetch never retried — the structural cause of the live-reproduced
  // "stuck on Connecting… forever" bug (tempdoc 663 §O). Install/runtime/pack status now come from
  // the shared, always-on `aiInstallPoll` (via aiStateStore's subscription above), which retries
  // unconditionally on a fixed 3s cadence regardless of prior success/failure.

  // Tempdoc 586 §3 — inference status now flows from the shared aiStateStore; this
  // loop polls only the brain-specific transition timeline + trace explorer, which
  // have no shared poller. (Renamed from startInferencePolling.)
  private startDiagnosticsPolling(): void {
    if (this.pollDiagnostics !== null) return;
    this.pollDiagnostics = window.setInterval(async () => {
      // Tempdoc 518 Appendix F W3.2 — poll the transition timeline.
      // Cheap read (ring buffer snapshot); refreshes whenever a transition fires.
      const t = await this.fetchJson<{ transitions?: TransitionRecord[] }>(
        '/api/inference/transitions?limit=8',
      );
      if (t && Array.isArray(t.transitions)) {
        this.transitions = t.transitions;
      }
      // Tempdoc 518 Appendix G Wave D.1 — recent spans for the trace explorer panel.
      // Best-effort: the endpoint returns tracesAvailable=false when HEAD_TRACING_LEVEL=none
      // (the default), in which case we suppress the panel below.
      const traces = await this.fetchJson<TraceExplorerResponse>(
        '/api/diagnostics/traces?limit=10',
      );
      if (traces && Array.isArray(traces.spans)) {
        this.recentSpans = traces.spans;
        this.tracesAvailable = !!traces.tracesAvailable;
      }
    }, 5000);
  }

  private stopAllPolling(): void {
    if (this.pollDiagnostics !== null) window.clearInterval(this.pollDiagnostics);
    this.pollDiagnostics = null;
  }

  private async withBusy<T>(key: string, fn: () => Promise<T>): Promise<T | null> {
    if (this.busy[key]) return null;
    this.busy = { ...this.busy, [key]: true };
    try {
      return await fn();
    } catch (err) {
      const msg = err instanceof OperationError
        ? err.message
        : err instanceof Error
          ? err.message
          : String(err);
      this.runtimeError = msg;
      return null;
    } finally {
      this.busy = { ...this.busy, [key]: false };
    }
  }

  private async invokeOp(
    operationId: string,
    args: Record<string, unknown> = {},
  ): Promise<unknown> {
    // Tempdoc 550 C3: HIGH-risk ops hit a TYPED_CONFIRM gate (BUTTON → TRUSTED tier;
    // TRUSTED × HIGH). The button gesture is the consent; invokeWithConsent recovers the
    // backend's 428 by approving the backend-issued pending by id and re-invoking with the
    // minted capsule — no client-side mint for an arbitrary op. LOW/MEDIUM are AUTO at
    // TRUSTED tier (CoreTrustEvaluator) → consented:false, a single plain invoke.
    const consented = getOperation(operationId)?.policy?.risk === 'HIGH';
    // Tempdoc 508-followup §ε2 — prefer host.data.invokeOperation
    // when the surface was mounted with a host_. The legacy
    // OperationClient fallback supports test harnesses and any
    // mount paths that pre-date host injection.
    if (this.host_) {
      const result = await this.host_.data.invokeOperation(operationId, args, { consented });
      return result.structuredData;
    }
    const result = await this.client().invokeWithConsent(operationId, { args }, { consented });
    return result.structuredData;
  }

  /**
   * Tempdoc 508-followup §ε2 — host-aware confirm dialog. Falls back
   * to the direct confirmAsync when host_ is absent.
   */
  private async hostConfirm(opts: {
    title: string;
    message: string;
    variant?: 'info' | 'warning' | 'danger';
    confirmLabel?: string;
    cancelLabel?: string;
    typedConfirmWord?: string;
  }): Promise<boolean> {
    if (this.host_) {
      return this.host_.ui.showConfirmDialog(opts.message, {
        ...(opts.confirmLabel !== undefined ? { confirmLabel: opts.confirmLabel } : {}),
        ...(opts.cancelLabel !== undefined ? { cancelLabel: opts.cancelLabel } : {}),
        destructive: opts.variant === 'danger',
        ...(opts.typedConfirmWord !== undefined
          ? { typedConfirmWord: opts.typedConfirmWord }
          : {}),
      });
    }
    return confirmAsync(opts);
  }

  /**
   * Tempdoc 508-followup §ε2 — host-aware folder picker. Falls back
   * to the direct picker if host_ is absent.
   */
  private async hostPickFolder(title?: string): Promise<string | null> {
    if (this.host_) {
      return this.host_.platform.pickFolder();
    }
    return pickFolder(title !== undefined ? { title } : undefined);
  }

  /**
   * Tempdoc 508-followup §ε2 — host-aware Tauri-runtime check.
   * `host.platform.capabilities` advertises the same capabilities
   * detected by isTauriRuntime; `file-picker` is the closest 1:1
   * substitute for "are we in a Tauri shell?".
   */
  private hostHasFilePicker(): boolean {
    if (this.host_) {
      return this.host_.platform.capabilities.has('file-picker');
    }
    return isTauriRuntime();
  }

  // ---------- Mode toggle ----------

  private async setMode(mode: 'simple' | 'advanced'): Promise<void> {
    await this.withBusy('mode', async () => {
      this.settings = { ...this.settings, mode };
      await fetch(this.base() + '/api/settings/v2', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ui: { mode } }),
      });
    });
  }

  // ---------- Install actions ----------

  private async startInstall(): Promise<void> {
    const ok = await this.hostConfirm({
      title: 'Download AI models?',
      message:
        'This will download the recommended model files into AI Home. These files can be several GB. You must accept the upstream model terms before downloading.',
      variant: 'info',
      confirmLabel: 'I Accept & Download',
    });
    if (!ok) return;
    await this.withBusy('install-start', async () => {
      this.runtimeError = null;
      const data = (await this.invokeOp('core.start-ai-install', { acceptTerms: true })) as InstallStatus;
      if (data) this.installStatus = data;
    });
  }

  private async cancelInstall(): Promise<void> {
    await this.withBusy('install-cancel', async () => {
      const data = (await this.invokeOp('core.cancel-ai-install', {})) as InstallStatus;
      if (data) this.installStatus = data;
    });
  }

  private async repairInstall(): Promise<void> {
    const ok = await this.hostConfirm({
      title: 'Repair AI installation?',
      message:
        'This re-runs the install pipeline, re-downloading any missing or corrupt model files. Existing valid files are preserved.',
      variant: 'warning',
      confirmLabel: 'Repair',
    });
    if (!ok) return;
    await this.withBusy('install-repair', async () => {
      this.runtimeError = null;
      const data = (await this.invokeOp('core.repair-ai-install', { acceptTerms: true })) as InstallStatus;
      if (data) this.installStatus = data;
    });
  }

  // Tempdoc 508-followup §ε2 + parallel 508 reconcile: `forceRebuildIndex`
  // method removed during merge. The "Force rebuild" button now mounts
  // via `<jf-operation operation-id="core.bulk-reindex">` (parallel 508
  // migration), and the typed-REBUILD confirm ceremony lives on the
  // wire's `ConfirmStrategy.Inline` rather than in a Lit handler.
  // typedConfirmWord on `ConfirmDialogOptions` (added in followup-ε2)
  // remains useful for surfaces that haven't migrated to `<jf-operation>`.

  // ---------- Inference mode ----------

  private async switchInference(mode: 'online' | 'indexing'): Promise<void> {
    await this.withBusy('inference-switch', async () => {
      this.runtimeError = null;
      await this.invokeOp('core.switch-inference-mode', { mode });
      // Tempdoc 586 §3 — one-shot post-action refresh (not a poll) for immediate
      // feedback; the shared store's 5s poll reconciles too. Typed as the store snapshot.
      const fresh = await this.fetchJson<NonNullable<UnifiedAiState['inference']>>(
        '/api/inference/status',
      );
      if (fresh) this.inference = fresh;
    });
  }

  // ---------- Runtime variants ----------

  private async activateVariant(variantId: string): Promise<void> {
    await this.withBusy('variant', async () => {
      this.runtimeError = null;
      const data = (await this.invokeOp('core.activate-runtime-variant', { variantId })) as AiRuntimeStatus;
      if (data) {
        this.runtimeStatus = data;
      }
    });
  }

  private async deactivateVariant(): Promise<void> {
    await this.withBusy('variant', async () => {
      const data = (await this.invokeOp('core.deactivate-runtime-variant', {})) as AiRuntimeStatus;
      if (data) {
        this.runtimeStatus = data;
      }
    });
  }

  // ---------- Pack import (Tauri-only) ----------

  private async preflightPack(): Promise<void> {
    const path = await this.hostPickFolder('Select AI Pack folder or .json manifest');
    if (!path) return;
    await this.withBusy('pack-preflight', async () => {
      this.runtimeError = null;
      const data = (await this.invokeOp('core.preflight-ai-pack', { path })) as Record<string, unknown>;
      if (data) {
        this.runtimeError =
          (data['ok'] as boolean) === true
            ? null
            : `Pack preflight: ${data['message'] ?? 'failed'}`;
      }
    });
  }

  private async importPack(): Promise<void> {
    const path = await this.hostPickFolder('Select AI Pack folder');
    if (!path) return;
    await this.withBusy('pack-import', async () => {
      this.runtimeError = null;
      const data = (await this.invokeOp('core.import-ai-pack', { path })) as PackImportStatus;
      if (data) {
        this.packStatus = data;
      }
    });
  }

  // ---------- LLM settings persist ----------

  private async patchLlm(updates: Partial<LlmSettings>): Promise<void> {
    this.llm = { ...this.llm, ...updates };
    await fetch(this.base() + '/api/settings/v2', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ llm: updates }),
    });
  }

  // ---------- Render: alerts + header ----------

  private renderAlerts(): TemplateResult | typeof nothing {
    const downloadsDisabled = this.policy?.downloadsEnabled === false;
    const onlineDisabled = this.policy?.onlineAiEnabled === false;
    if (!this.runtimeError && !downloadsDisabled && !onlineDisabled) return nothing;
    return html`
      ${this.runtimeError
        ? html`<jf-error-alert
            tone="error"
            .onDismiss=${() => (this.runtimeError = null)}
          >
            <span slot="icon">${icon({ name: 'alert-circle', size: 14 })}</span>
            ${this.runtimeError}
          </jf-error-alert>`
        : nothing}
      ${downloadsDisabled
        ? html`<jf-error-alert tone="warning">
            <span slot="icon">${icon({ name: 'alert-circle', size: 14 })}</span>
            Downloads are disabled by administrator policy. Use AI Pack import instead of "Install
            AI".
          </jf-error-alert>`
        : nothing}
      ${onlineDisabled
        ? html`<jf-error-alert tone="warning">
            <span slot="icon">${icon({ name: 'alert-circle', size: 14 })}</span>
            Online AI is disabled by administrator policy. Assets can still be installed for
            staging.
          </jf-error-alert>`
        : nothing}
    `;
  }

  private renderHeader(): TemplateResult {
    const mode: 'simple' | 'advanced' = this.settings.mode ?? 'simple';
    return html`
      <div class="header">
        <div>
          <h2>AI Brain</h2>
          <p class="subtitle">Configure local language models</p>
        </div>
        <div class="row">
          <div class="mode-toggle">
            <button class=${mode === 'simple' ? 'active' : ''} @click=${() => this.setMode('simple')}>
              Simple
            </button>
            <button
              class=${mode === 'advanced' ? 'active' : ''}
              @click=${() => this.setMode('advanced')}
            >
              Advanced
            </button>
          </div>
          <jf-button
            variant="ghost"
            size="icon"
            label="Refresh status"
            .availability=${this.refreshing ? unavailableBecause('Refreshing…', true) : undefined}
            .onActivate=${() => void this.refreshAll()}
          >
            ${icon({ name: 'refresh-cw', size: 14, spin: this.refreshing })}
          </jf-button>
        </div>
      </div>
    `;
  }

  // ---------- Render: simple panel ----------

  /**
   * Tempdoc 663 Design pass 2 — the OBSERVED half (install/runtime/reachable) is now computed ONCE in
   * `aiStateStore.ts`, exposed as `AiState.aiEngine` (the fourth 594/595/596 sibling, store-level like
   * its siblings, not private to this component). This method is now a thin LOCAL overlay: it applies
   * only this surface's own optimistic click-intent (`busy['install-start']`/`busy['inference-switch']`)
   * on top of that shared observed result — `applyLocalIntent` (state/aiVerdict.ts). The fallback (before
   * the store has ever emitted) mirrors what `computeAiEngineVerdict` itself would compute for "no data,
   * not reachable yet".
   */
  private deriveAiEngineVerdict(): AiEngineVerdict {
    const observed: AiEngineVerdict = this._unifiedAiState?.aiEngine ?? {
      kind: 'connecting',
      stability: { kind: 'provisional', cause: 'stale-poll' },
      installFailure: null,
    };
    return applyLocalIntent(observed, {
      switching: !!this.busy['inference-switch'],
      installStarting: !!this.busy['install-start'],
    });
  }

  private renderSimplePanel(): TemplateResult {
    // Tempdoc 663 — the string `kind` drives this panel's dot/label/sub lookup below, unchanged
    // from before; only its SOURCE changed (the one `computeAiEngineVerdict` derivation, not the
    // old 5-source ladder).
    const aiVerdict = this.deriveAiEngineVerdict();
    const aiState = aiVerdict.kind;
    const downloadsDisabled = this.policy?.downloadsEnabled === false;
    const onlineDisabled = this.policy?.onlineAiEnabled === false;

    const statusConfig: Record<string, { dot: string; label: string; sub: string }> = {
      not_installed: {
        dot: 'notinstalled',
        label: 'Not Installed',
        sub: 'Install AI models to get started.',
      },
      installing: {
        dot: 'installing',
        label: 'Installing…',
        sub: this.installStatus?.phase
          ? `Phase: ${this.installStatus.phase.replace(/_/g, ' ')}`
          : 'Downloading models',
      },
      // Tempdoc 663 §E — install failure was previously unrepresented as a lifecycle state (folded
      // silently into a generic 'offline'/'not_installed' render, with only a separate dismissable
      // `runtimeError` banner hinting at it). Now a real, named state.
      install_failed: {
        dot: 'offline',
        label: 'Install Failed',
        sub: this.installStatus?.lastError || this.installStatus?.message || 'Installation failed — try again.',
      },
      offline: {
        dot: 'offline',
        label: this.installStatus?.installedFully === false ? 'Installed with limitations' : 'AI Offline',
        sub: 'Start AI to enable chat and summaries.',
      },
      starting: {
        dot: 'starting',
        label: 'Starting…',
        // Tempdoc 518 Appendix F W3.1 — restart-ETA badge. When the runtime has reported a
        // prior successful-startup duration, show it as an ETA hint; otherwise fall back to
        // the generic "AI is initializing." copy. Tempdoc 601 §20 — also pass the live load-start
        // stamp so this surface shows the measured elapsed too (the §18 "show both" mapping).
        sub: formatRestartEtaSub(
          this.inference?.lastStartupDurationMs,
          this._unifiedAiState?.runtime?.loadStartedAtMs,
        ),
      },
      online: { dot: 'online', label: 'AI Online', sub: 'Chat and summaries ready.' },
      // Tempdoc 663 — indexing is now a distinct, named state (the original ladder had no explicit
      // branch for `runtime.mode === 'indexing'` and fell through to 'offline').
      indexing: { dot: 'online', label: 'AI Online', sub: 'Indexing embeddings…' },
      connecting: { dot: 'starting', label: 'Connecting…', sub: 'Checking AI status…' },
    };
    const sc = statusConfig[aiState] ?? statusConfig.offline!;

    let bytesDone = 0,
      bytesTotal = 0;
    for (const a of this.installStatus?.packages ?? []) {
      bytesDone += a?.bytesDownloaded ?? 0;
      bytesTotal += a?.bytesTotal ?? 0;
    }
    const pct = bytesTotal > 0 ? Math.min(100, Math.floor((bytesDone / bytesTotal) * 100)) : null;

    // Tempdoc 663 Stage 3 — the primary action's availability is now typed (596), not a bare boolean.
    // Only the two states with a genuine, showable reason (a policy gate) use `unavailableBecause`; the
    // pure busy-only/unconditional cases use `blocked` (596 §10/C2 — a hard intent gate stays the
    // native-disabled-equivalent tier, not a soft "unavailable{reason}", since there is no reason beyond
    // "wait" to show).
    const primaryAction = (() => {
      switch (aiState) {
        case 'not_installed':
        case 'install_failed':
          return {
            label: 'Install AI',
            iconName: 'hard-drive' as const,
            onClick: () => void this.startInstall(),
            availability: downloadsDisabled
              ? unavailableBecause('Downloads are disabled by administrator policy.')
              : this.busy['install-start']
                ? ({ kind: 'blocked' } as const)
                : AVAILABLE,
            primary: true,
          };
        case 'installing':
          return {
            label: 'Cancel',
            iconName: 'x' as const,
            onClick: () => void this.cancelInstall(),
            availability: this.busy['install-cancel'] ? ({ kind: 'blocked' } as const) : AVAILABLE,
            primary: false,
          };
        case 'offline':
          return {
            label: 'Start AI',
            iconName: 'check-circle-2' as const,
            onClick: () => void this.switchInference('online'),
            availability: onlineDisabled
              ? unavailableBecause('Online AI is disabled by administrator policy.')
              : this.busy['inference-switch']
                ? ({ kind: 'blocked' } as const)
                : AVAILABLE,
            primary: true,
          };
        case 'starting':
          return {
            label: 'Cancel',
            iconName: 'x' as const,
            onClick: () => void this.switchInference('indexing'),
            availability: AVAILABLE,
            primary: false,
          };
        case 'connecting':
          return {
            label: 'Checking…',
            iconName: 'x' as const,
            onClick: () => {},
            availability: { kind: 'blocked' } as const,
            primary: false,
          };
        case 'online':
        case 'indexing':
        default:
          return {
            label: 'Shut Down AI',
            iconName: 'x' as const,
            onClick: () => void this.switchInference('indexing'),
            availability: this.busy['inference-switch'] ? ({ kind: 'blocked' } as const) : AVAILABLE,
            primary: false,
          };
      }
    })();

    return html`
      <div class="section" data-testid="brain-simple-panel">
        <div class="status-row">
          ${(() => {
            const d = brainDotTone(sc.dot);
            return html`<jf-status-dot
              size="lg"
              tone=${d.tone}
              ?live=${d.live}
            ></jf-status-dot>`;
          })()}
          <div>
            <div style="font-size: var(--font-size-md); font-weight: 500">
              ${sc.label}${this.installStalled()
                ? html`<span
                    title="No progress recently — the owner may be stuck. The backstop will reclaim it to a terminal state."
                    style="margin-left: 0.5rem; padding: 0.05rem 0.4rem; border-radius: 0.25rem; font-size: var(--font-size-xs); color: var(--text-warning); border: 1px solid var(--accent-warning)"
                    >stalled</span
                  >`
                : nothing}
            </div>
            <div style="font-size: var(--font-size-xs); color: var(--text-secondary)">${sc.sub}</div>
          </div>
        </div>

        ${aiState === 'installing' && pct !== null
          ? html`
              <div style="margin-top: 1rem">
                <div class="progress">
                  <div class="progress-bar" style="width: ${pct}%"></div>
                </div>
                <div
                  style="margin-top: 0.5rem; display:flex; justify-content:space-between; font-size: var(--font-size-xs); color: var(--text-secondary)"
                >
                  <span>${this.installStatus?.phase?.replace(/_/g, ' ') ?? 'preparing'}</span>
                  <span>${formatBytes(bytesDone)} / ${formatBytes(bytesTotal)}</span>
                </div>
              </div>
            `
          : nothing}
        ${aiState === 'online'
          ? html`
              <div style="margin-top: 1rem; padding: 0.75rem; background: var(--surface-tertiary); border-radius: 0.375rem">
                <div class="grid">
                  ${(() => {
                    // Tempdoc 663 — Model/Context/GPU are now projected via the shared Fact
                    // authority (594) instead of formatted inline; Tier stays inline (out of this
                    // stage's scope — no catalog row exists for it, and it's a single, single-use
                    // read with no second consumer, so AHA says leave it).
                    const model = projectFact('core.ai.model', this._unifiedAiState);
                    const ctx = projectFact('core.ai.contextWindow', this._unifiedAiState);
                    const gpu = projectFact('core.ai.gpu', this._unifiedAiState);
                    return html`
                      ${model.presence === 'present'
                        ? html`<span class="key">${model.name}</span
                            ><span class="val" title=${model.provenance ?? ''}>${model.value}</span
                            >`
                        : nothing}
                      ${this.inference?.tier
                        ? html`<span class="key">Tier</span
                            ><span class="val">${this.inference.tier.replace(/_/g, ' ')}</span>`
                        : nothing}
                      ${ctx.presence === 'present'
                        ? html`<span class="key">${ctx.name}</span><span class="val">${ctx.value}</span>`
                        : nothing}
                      ${gpu.presence === 'present'
                        ? html`<span class="key">${gpu.name}</span><span class="val">${gpu.value}</span>`
                        : nothing}
                    `;
                  })()}
                </div>
              </div>
            `
          : nothing}

        <div style="margin-top: 1rem">
          <jf-button
            variant=${primaryAction.primary ? 'primary' : 'secondary'}
            .availability=${primaryAction.availability}
            .onActivate=${primaryAction.onClick}
            label=${primaryAction.label}
            data-testid="brain-simple-action"
            style="min-width: 11rem"
          >
            ${icon({ name: primaryAction.iconName, size: 14 })} ${primaryAction.label}
          </jf-button>
        </div>

        ${aiVerdict.kind === 'install_failed' && aiVerdict.installFailure
          ? html`<jf-error-alert tone="error" style="margin-top: 0.75rem"
              >Install failed: ${aiVerdict.installFailure}</jf-error-alert
            >`
          : nothing}

        ${this.renderTransitionTimeline()}
        ${this.renderTraceExplorer()}
      </div>
    `;
  }

  // ---------- Render: mode-transition timeline (W3.2) ----------

  /**
   * Tempdoc 518 Appendix F W3.2 — Brain-panel timeline of recent mode transitions.
   * Renders the 8 newest from /api/inference/transitions. Hidden when the list is empty
   * so freshly-booted runtimes (no transitions yet) don't show an empty section.
   */
  /**
   * Tempdoc 518 Appendix G Wave D.2 — sparkline of inference generation over the recent
   * transition window. SVG-based, no external charting library. Each transition is a tick
   * mark on the timeline (failures red, successes teal); the current generation value is
   * the right-edge label. When generation is 0 or there are no transitions yet, returns
   * nothing — there's nothing useful to chart.
   */
  private renderGenerationSparkline(): TemplateResult | typeof nothing {
    const rows = this.transitions;
    const currentGen = typeof this.inference?.generation === 'number' ? this.inference.generation : 0;
    if (!rows || rows.length === 0 || currentGen === 0) return nothing;
    // Tempdoc 518 Wave A-E defect Fix-4: render the actual step-function y-line, not just
    // dots. Each successful transition bumps generation by 1; the line shows the count's
    // evolution. Failure dots stay on the line (failure does NOT bump generation, so the
    // step stays flat at that x — visible as a red dot on a horizontal segment).
    const ascending = [...rows].sort((a, b) => a.timestampMs - b.timestampMs);
    const first = ascending[0]!.timestampMs;
    const last = ascending[ascending.length - 1]!.timestampMs;
    const tsSpan = Math.max(1, last - first);
    const W = 200;
    const H = 32;
    const padX = 4;
    const padY = 4;
    const yMax = Math.max(1, currentGen);
    const xOf = (ts: number) => padX + ((ts - first) / tsSpan) * (W - padX * 2);
    const yOf = (gen: number) => H - padY - (gen / yMax) * (H - padY * 2);

    // Build the step function. Walk transitions in chronological order; each success bumps
    // the running gen by 1 (matches the runner's generation counter contract). Each row
    // contributes two points (horizontal then vertical jump on success) and stamps the
    // gen-at-that-row for dot positioning.
    const dotData: Array<{ ts: number; gen: number; row: TransitionRecord }> = [];
    const points: Array<{ x: number; y: number }> = [];
    let gen = 0;
    points.push({ x: padX, y: yOf(gen) });
    for (const row of ascending) {
      const px = xOf(row.timestampMs);
      points.push({ x: px, y: yOf(gen) }); // horizontal segment up to this transition
      if (row.success) {
        gen += 1;
        points.push({ x: px, y: yOf(gen) }); // vertical jump
      }
      dotData.push({ ts: row.timestampMs, gen, row });
    }
    // Final horizontal segment extending to the right edge (current generation plateau).
    points.push({ x: W - padX, y: yOf(gen) });
    const pointsStr = points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');

    return html`
      <div
        data-testid="brain-generation-sparkline"
        style="margin-top: 0.75rem; display: flex; align-items: center; gap: 0.5rem; font-size: var(--font-size-xs)"
      >
        <span style="color: var(--text-secondary)">gen:</span>
        <span style="font-weight: 600; font-variant-numeric: tabular-nums">${currentGen}</span>
        <svg width="${W}" height="${H}" style="display: block">
          <polyline
            data-testid="brain-generation-sparkline-line"
            points="${pointsStr}"
            fill="none"
            stroke="var(--accent-tint)"
            stroke-width="1.5"
            stroke-linejoin="round"
          />
          ${dotData.map(
            (d) => html`<circle
              cx="${xOf(d.ts)}"
              cy="${yOf(d.gen)}"
              r="3"
              fill="${d.row.success ? 'var(--accent-tint)' : 'var(--accent-danger)'}"
            >
              <title>${new Date(d.row.timestampMs).toLocaleTimeString()} · gen=${d.gen} · ${d.row.fromMode.toLowerCase()} → ${d.row.toMode.toLowerCase()} · ${d.row.reason.toLowerCase().replace(/_/g, ' ')} · ${d.row.durationMs}ms${d.row.wireCode ? ` · ${d.row.wireCode}` : ''}</title>
            </circle>`,
          )}
        </svg>
      </div>
    `;
  }

  private renderTransitionTimeline(): TemplateResult | typeof nothing {
    const rows = this.transitions;
    if (!rows || rows.length === 0) return nothing;
    return html`
      ${this.renderGenerationSparkline()}
      <details
        class="transitions"
        data-testid="brain-transitions-timeline"
        style="margin-top: 0.875rem; font-size: var(--font-size-xs)"
      >
        <summary style="cursor: pointer; color: var(--text-secondary)">
          Recent mode transitions (${rows.length})
        </summary>
        <ul
          style="margin: 0.5rem 0 0 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 0.25rem"
        >
          ${rows.map(
            (r) => html`
              <li
                data-testid="brain-transition-row"
                data-success=${String(r.success)}
                style="display:flex; gap:0.5rem; align-items: baseline; font-variant-numeric: tabular-nums"
              >
                <span
                  style="display:inline-block; width:0.5rem; height:0.5rem; border-radius:50%; background:${r.success
                    ? 'var(--accent-tint)'
                    : 'var(--accent-danger)'}; flex-shrink:0; align-self: center"
                ></span>
                <span style="color: var(--text-secondary)"
                  >${new Date(r.timestampMs).toLocaleTimeString()}</span
                >
                <span>${r.fromMode.toLowerCase()} → ${r.toMode.toLowerCase()}</span>
                <span style="color: var(--text-secondary)"
                  >· ${r.reason.toLowerCase().replace(/_/g, ' ')}</span
                >
                <span style="color: var(--text-secondary)">· ${r.durationMs}ms</span>
                ${r.wireCode
                  ? html`<span style="color: var(--text-danger)">· ${r.wireCode}</span>`
                  : nothing}
              </li>
            `,
          )}
        </ul>
      </details>
    `;
  }

  /**
   * Tempdoc 518 Appendix G Wave D.1 — in-product trace explorer panel.
   * Lists the 10 most recent spans from /api/diagnostics/traces. Hidden when tracing is off
   * (HEAD_TRACING_LEVEL=none, the default — endpoint reports tracesAvailable=false).
   * Clicking a row copies its trace_id to the clipboard so it can be looked up in
   * otel-desktop-viewer or grep'd against traces.ndjson.
   */
  private renderTraceExplorer(): TemplateResult | typeof nothing {
    if (!this.tracesAvailable || !this.recentSpans || this.recentSpans.length === 0) {
      return nothing;
    }
    return html`
      <details
        class="traces"
        data-testid="brain-trace-explorer"
        style="margin-top: 0.875rem; font-size: var(--font-size-xs)"
      >
        <summary style="cursor: pointer; color: var(--text-secondary)">
          Recent spans (${this.recentSpans.length})
          <span style="color: var(--text-secondary); font-size: var(--font-size-xs)">
            · click a row to copy trace ID
          </span>
        </summary>
        <ul
          style="margin: 0.5rem 0 0 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 0.25rem"
        >
          ${this.recentSpans.map(
            (s) => html`
              <li
                data-testid="brain-trace-row"
                role="button"
                tabindex="0"
                style="display:flex; gap:0.5rem; align-items: baseline; font-variant-numeric: tabular-nums; cursor: pointer"
                @click=${() => {
                  if (s.trace_id) {
                    void navigator.clipboard?.writeText(s.trace_id);
                  }
                }}
                @keydown=${(e: KeyboardEvent) =>
                  activateOnKey(e, () => {
                    if (s.trace_id) void navigator.clipboard?.writeText(s.trace_id);
                  })}
                title=${s.trace_id ? `trace_id=${s.trace_id} (click to copy)` : ''}
              >
                <span
                  style="display:inline-block; width:0.5rem; height:0.5rem; border-radius:50%; background:${s.status === 'ERROR'
                    ? 'var(--accent-danger)'
                    : 'var(--accent-tint)'}; flex-shrink:0; align-self: center"
                ></span>
                <span style="color: var(--text-secondary)">
                  ${s.start ? new Date(s.start).toLocaleTimeString() : '—'}
                </span>
                <span style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 18rem">
                  ${s.name ?? '(unnamed)'}
                </span>
                <span style="color: var(--text-secondary)">
                  · ${typeof s.duration_ms === 'number' ? `${Math.round(s.duration_ms)}ms` : '—'}
                </span>
              </li>
            `,
          )}
        </ul>
      </details>
    `;
  }

  // ---------- Render: compatibility callouts + embedding progress ----------

  private renderCompatibilityCallouts(): TemplateResult | typeof nothing {
    const emb = this.systemStatus?.embedding;
    const schema = this.systemStatus?.schema;
    const embeddingBlocked =
      emb?.compatState === 'BLOCKED_LEGACY' || emb?.compatState === 'BLOCKED_MISMATCH';
    const isLegacy = emb?.compatState === 'BLOCKED_LEGACY';
    const schemaIncompat = schema?.compatState === 'INCOMPATIBLE';
    if (!embeddingBlocked && !schemaIncompat) return nothing;
    // Tempdoc 613 — coherence. The user-facing CAUSE wording projects the ONE canonical reindex
    // vocabulary (`reasonFor`/CAUSE_ROWS — identical to the Chat degradation banner and the 595
    // verdict), so the same condition can no longer read three different ways across surfaces. The
    // reindex code(s) are READ from the shared verdict already on the aiStateStore snapshot (the
    // backend derived them onto `retrieval.reasonCodes`), so there is NO FE compatState→code remap to
    // drift. The legacy/mismatch distinction, fingerprint hashes, and schema reason stay as
    // config-altitude technical DETAIL beneath the canonical lead.
    const reindexCauses = [
      ...new Set((this._unifiedAiState?.verdict?.reasons ?? []).filter(isReindexCause)),
    ];
    const canonicalWordings = reindexCauses.map((code) => reasonFor(code).wording);
    return html`
      <div class="section" style="border-color: var(--accent-warning-45); background: var(--accent-warning-08)">
        <div style="display: flex; gap: 0.625rem; align-items: flex-start">
          ${icon({ name: 'x-circle', size: 18 })}
          <div style="flex: 1">
            ${canonicalWordings.length > 0
              ? canonicalWordings.map(
                  (wording) =>
                    html`<div style="font-weight: 600; color: var(--text-warning)">
                      ${wording}
                    </div>`,
                )
              : html`<div style="font-weight: 600; color: var(--text-warning)">
                  Rebuild the index to restore full search.
                </div>`}
            ${embeddingBlocked
              ? html`
                  <div style="font-size: var(--font-size-sm); color: var(--text-secondary); margin-top: 0.375rem">
                    ${isLegacy
                      ? 'Embedding model fingerprint missing.'
                      : 'Embedding model mismatch detected.'}
                  </div>
                  ${emb?.fingerprintStored && emb?.fingerprintCurrent
                    ? html`<div
                        style="font-size: var(--font-size-xs); color: var(--text-secondary); margin-top: 0.25rem; font-family: monospace"
                      >
                        Stored: ${emb.fingerprintStored.substring(0, 12)}… → Current:
                        ${emb.fingerprintCurrent.substring(0, 12)}…
                      </div>`
                    : nothing}
                `
              : nothing}
            ${schemaIncompat
              ? html`
                  <div style="font-size: var(--font-size-sm); color: var(--text-secondary); margin-top: 0.375rem">
                    Schema incompatible${schema?.reindexRequiredReason
                      ? html` — ${schema.reindexRequiredReason}`
                      : nothing}.
                  </div>
                `
              : nothing}
          </div>
        </div>
        <!-- Tempdoc 511 Phase 9: <jf-operation> aggregate component dispatches via the
             (Operation, button) strategy; the rebuild op is the canonical reindex remedy
             (matching reasonFor's remedy). BrainSurface is operator-tier. -->
        <jf-operation
          context="button"
          operation-id="core.rebuild-index"
          api-base=${this.apiBase}
          @op-success=${() => void this.refreshAll()}
          style="width: 100%; margin-top: 0.75rem"
        ></jf-operation>
      </div>
    `;
  }

  private renderEmbeddingProgress(): TemplateResult | typeof nothing {
    const emb = this.systemStatus?.embedding;
    const pending = emb?.pendingCount ?? 0;
    const completed = emb?.completedCount ?? 0;
    const total = emb?.docCount ?? completed + pending;
    if (pending === 0 || total === 0) return nothing;
    if (emb?.compatState?.startsWith('BLOCKED')) return nothing;
    // Use the canonical coveragePercent from the wire when present;
    // fall back to completed/total when absent.
    const pct =
      emb?.coveragePercent != null
        ? emb.coveragePercent
        : total > 0
          ? (completed / total) * 100
          : 0;
    return html`
      <div class="section" style="border-color: var(--accent-warning-30); background: var(--accent-warning-08)">
        <div style="display: flex; gap: 0.625rem; align-items: flex-start">
          ${icon({ name: 'zap', size: 18 })}
          <div style="flex: 1">
            <div style="font-weight: 600; color: var(--text-warning)">
              Building semantic search
            </div>
            <div style="font-size: var(--font-size-sm); color: var(--text-secondary); margin-top: 0.25rem">
              Generating chunk embeddings for improved Q&amp;A retrieval.
            </div>
          </div>
          <div style="font-weight: 600; font-variant-numeric: tabular-nums">${pct.toFixed(1)}%</div>
        </div>
        <div class="progress" style="margin-top: 0.625rem">
          <div class="progress-bar" style="width: ${pct}%; background: var(--accent-warning)"></div>
        </div>
        <div style="font-size: var(--font-size-xs); color: var(--text-secondary); margin-top: 0.5rem; display: flex; align-items: center; gap: 0.375rem">
          ${icon({ name: 'loader-2', size: 12, spin: true })}
          ${NUM.format(pending)} pending
        </div>
      </div>
    `;
  }

  // ---------- Render: collapsible accordion section helper ----------

  private renderAccordion(
    key: string,
    title: string,
    badgeText: string | null,
    body: () => TemplateResult,
  ): TemplateResult {
    const open = this.expanded[key] === true;
    return html`
      <div class="section" style="padding: 0">
        <button
          style="width: 100%; padding: 0.875rem 1rem; background: transparent; border: none; color: inherit; cursor: pointer; display: flex; align-items: center; gap: 0.5rem; font-size: var(--font-size-sm); font-weight: 500"
          @click=${() => this.toggleSection(key)}
        >
          <span style="display: inline-flex; transition: transform var(--duration-fast) var(--ease-standard); ${open ? 'transform: rotate(0deg)' : 'transform: rotate(-90deg)'}"
            >${icon({ name: 'chevron-down', size: 14 })}</span
          >
          <span>${title}</span>
          ${badgeText
            ? html`<span
                style="font-size: var(--font-size-xs); color: var(--text-secondary); margin-left: 0.5rem"
                >${badgeText}</span
              >`
            : nothing}
        </button>
        ${open
          ? html`<div style="padding: 0 1rem 1rem 1rem; border-top: 1px solid var(--border-subtle)">
              ${body()}
            </div>`
          : nothing}
      </div>
    `;
  }

  private renderSearchQualityFeatures(): TemplateResult {
    const features = this.runtimeStatus?.onnxFeatures ?? [];
    const activeCount = features.filter((f) => f.modelActive).length;
    return this.renderAccordion(
      'search-quality',
      'Search Quality Features',
      features.length > 0
        ? activeCount > 0
          ? `${activeCount}/${features.length} active`
          : 'optional'
        : null,
      () => html`
        <div style="margin-top: 0.625rem; font-size: var(--font-size-sm)">
          ${features.length === 0
            ? html`<div style="color: var(--text-secondary); padding: 0.5rem 0">
                No ONNX feature data yet — runtime status pending.
              </div>`
            : features.map(
                (f) => html`
                  <div class="data-row">
                    <span>
                      <jf-status-dot
                        tone=${f.modelActive ? 'success' : 'neutral'}
                        style="margin-right: 0.5rem; vertical-align: middle"
                      ></jf-status-dot>
                      ${f.feature}
                    </span>
                    <span style="color: var(--text-secondary); font-family: monospace; font-size: var(--font-size-xs)"
                      >${f.modelDescription ?? (f.modelActive ? 'active' : 'inactive')}</span
                    >
                  </div>
                `,
              )}
        </div>
      `,
    );
  }

  /**
   * Tempdoc 657 — honest first-run weight, grouped by capability tier so the optional heavy LLM pack
   * is visibly separate from the (already-bundled) retrieval stack. Sourced from the side-effect-free
   * plan-preview; renders nothing until it loads. Tiers the active mode excludes are shown dimmed so
   * the reason for their absence is legible rather than a silent gap.
   */
  private renderTierBreakdown(): TemplateResult | typeof nothing {
    const tiers = this.planPreview?.tiers;
    if (!tiers?.length) return nothing;
    const intent = this.planPreview?.intent ?? 'this';
    return html`
      <div style="margin-bottom: 0.5rem">
        ${tiers.map((t) => {
          const total = t.totalBytes ?? 0;
          const download = t.downloadBytes ?? 0;
          const statusText = !t.includedByIntent
            ? `not in ${intent} mode`
            : download > 0
              ? 'to download'
              : 'installed';
          return html`
            <div class="data-row" style=${t.includedByIntent ? '' : 'opacity: 0.55'}>
              <span>${t.label || t.tier || 'tier'}</span>
              <span style="color: var(--text-secondary); font-family: monospace">
                ${total ? formatBytes(total) : '—'} · ${statusText}
              </span>
            </div>
          `;
        })}
      </div>
    `;
  }

  private renderModels(): TemplateResult {
    const features = this.runtimeStatus?.onnxFeatures ?? [];
    const active = features.filter((f) => f.modelActive).length;
    return this.renderAccordion(
      'models',
      'Models',
      active > 0 ? `${active} loaded` : null,
      () => html`
        <div style="margin-top: 0.625rem; font-size: var(--font-size-sm)">
          ${this.renderTierBreakdown()}
          ${this.installStatus?.packages?.length
            ? this.installStatus.packages.map(
                (p) => html`
                  <div class="data-row">
                    <span>${p.label || p.packageId || 'package'}</span>
                    <span style="color: var(--text-secondary); font-family: monospace"
                      >${p.bytesTotal ? formatBytes(p.bytesTotal) : '—'}</span
                    >
                  </div>
                `,
              )
            : this.planPreview
              ? nothing
              : html`<div style="color: var(--text-secondary); padding: 0.5rem 0">
                  No model packages yet — install AI to populate this list.
                </div>`}
          ${this.llm.modelPath
            ? html`
                <div class="data-row">
                  <span>LLM model</span>
                  <span style="color: var(--text-secondary); font-family: monospace; font-size: var(--font-size-xs)"
                    >${this.llm.modelPath}</span
                  >
                </div>
              `
            : nothing}
        </div>
      `,
    );
  }

  // ---------- Render: pack import (Tauri-only advanced) ----------

  private renderPackImport(): TemplateResult | typeof nothing {
    const tauri = this.hostHasFilePicker();
    const allowlist = this.policy?.packAllowlistConfigured ?? false;
    return html`
      <div class="section">
        <h3>${icon({ name: 'folder', size: 12 })} Offline pack import</h3>
        ${!tauri
          ? html`<jf-error-alert tone="warning">
              <span slot="icon">${icon({ name: 'alert-circle', size: 14 })}</span>
              Pack import requires the desktop app (Tauri). Browser mode unavailable.
            </jf-error-alert>`
          : !allowlist
            ? html`<jf-error-alert tone="warning">
                <span slot="icon">${icon({ name: 'alert-circle', size: 14 })}</span>
                No pack allowlist is configured. Set
                <code>allowlistedPackManifestSha256</code> in policy to enable imports.
              </jf-error-alert>`
            : html`
                <div class="row">
                  <jf-button
                    label="Preflight"
                    ?disabled=${!!this.busy['pack-preflight']}
                    .onActivate=${() => void this.preflightPack()}
                  >
                    ${icon({ name: 'check-circle-2', size: 14 })} Preflight
                  </jf-button>
                  <jf-button
                    variant="primary"
                    label="Import pack"
                    .availability=${this.busy['pack-import']
                      ? { kind: 'blocked' }
                      : this.packStatus?.state === 'running'
                        ? unavailableBecause('A pack import is already in progress.')
                        : AVAILABLE}
                    .onActivate=${() => void this.importPack()}
                  >
                    ${icon({ name: 'hard-drive', size: 14 })} Import pack
                  </jf-button>
                </div>
                ${this.packStatus
                  ? html`<div style="margin-top: 0.5rem; font-size: var(--font-size-xs); color: var(--text-secondary)">
                      Pack status: <code>${this.packStatus.state}</code>
                      ${this.packStatus.phase ? html` · ${this.packStatus.phase}` : nothing}
                      ${this.packStatus.message ? html` — ${this.packStatus.message}` : nothing}
                    </div>`
                  : nothing}
              `}
      </div>
    `;
  }

  // ---------- Render: install section advanced ----------

  private renderInstallSection(): TemplateResult {
    const downloadsDisabled = this.policy?.downloadsEnabled === false;
    // Tempdoc 663 — consume the one AI-engine verdict instead of re-reading `installStatus.state`
    // directly (the ai-verdict-derivation gate). Also picks up the instant `busy['install-start']`
    // feedback the Simple panel already gets, closing the same gap here.
    const installing = this.deriveAiEngineVerdict().kind === 'installing';
    return html`
      <div class="section">
        <h3>${icon({ name: 'hard-drive', size: 12 })} AI install</h3>
        <div style="font-size: var(--font-size-xs); color: var(--text-secondary); margin-bottom: 0.5rem">
          State: <code>${this.installStatus?.state ?? 'idle'}</code>${this.installStatus?.phase
            ? html` · phase: <code>${this.installStatus.phase}</code>`
            : nothing}
          ${this.installStatus?.installedFully !== undefined
            ? html` · installedFully: <code>${String(this.installStatus.installedFully)}</code>`
            : nothing}
        </div>
        <div class="row">
          <jf-button
            variant="primary"
            label="Install"
            .availability=${installing
              ? unavailableBecause('Already installing.')
              : downloadsDisabled
                ? unavailableBecause('Downloads are disabled by administrator policy.')
                : this.busy['install-start']
                  ? { kind: 'blocked' }
                  : AVAILABLE}
            .onActivate=${() => void this.startInstall()}
          >
            Install
          </jf-button>
          <jf-button
            label="Cancel"
            ?disabled=${!installing || !!this.busy['install-cancel']}
            .onActivate=${() => void this.cancelInstall()}
          >
            Cancel
          </jf-button>
          <jf-button
            label="Repair"
            .availability=${installing
              ? unavailableBecause('Already installing.')
              : downloadsDisabled
                ? unavailableBecause('Downloads are disabled by administrator policy.')
                : this.busy['install-repair']
                  ? { kind: 'blocked' }
                  : AVAILABLE}
            .onActivate=${() => void this.repairInstall()}
          >
            Repair
          </jf-button>
        </div>
      </div>
    `;
  }

  // ---------- Render: runtime / variants / inference / LLM settings ----------

  private renderRuntimeSection(): TemplateResult {
    const variants = this.runtimeStatus?.variants ?? [];
    const activeId = this.runtimeStatus?.activation?.activeVariantId ?? null;
    const actState = this.runtimeStatus?.activation?.state ?? 'idle';
    const activating = actState === 'running' || !!this.busy['variant'];
    const provisional = isGpuReadingProvisional(this._unifiedAiState?.aiEngine.stability);

    return html`
      <div class="section">
        <h3>${icon({ name: 'hard-drive', size: 12 })} Runtime</h3>

        ${this.inference?.gpu
          ? html`
              <div class="grid" style="margin-bottom: 0.75rem; ${provisional ? 'opacity: 0.6' : ''}">
                <span class="key">CUDA</span
                ><span class="val">${this.inference.gpu.cudaAvailable ? 'available' : 'no'}</span>
                ${(() => {
                  // Tempdoc 663 — same `core.ai.gpu` fact as renderSimplePanel's grid; the VRAM
                  // description is one authority, projected wherever it renders.
                  const gpu = projectFact('core.ai.gpu', this._unifiedAiState);
                  return gpu.presence === 'present'
                    ? html`<span class="key">VRAM</span><span class="val">${gpu.value}</span>`
                    : nothing;
                })()}
                ${this.inference.tier
                  ? html`<span class="key">Tier</span
                      ><span class="val">${this.inference.tier.replace(/_/g, ' ')}</span>`
                  : nothing}
              </div>
            `
          : nothing}

        ${variants.length > 0
          ? html`
              <div style="font-size: var(--font-size-xs); text-transform: uppercase; color: var(--text-secondary); margin-bottom: 0.375rem">
                GPU runtime variants
              </div>
              ${variants.map(
                (v) => html`
                  <div class="variant ${v.id === activeId ? 'active' : ''}">
                    <div class="variant-info">
                      <div class="variant-label">${v.label ?? v.id}</div>
                      <div class="variant-meta">
                        ${v.description ?? ''}
                        ${v.requiredVramBytes
                          ? html` · requires ${formatBytes(v.requiredVramBytes)} VRAM`
                          : nothing}
                        ${v.available === false && v.reason
                          ? html` · <span style="color: var(--text-warning)">${v.reason}</span>`
                          : nothing}
                      </div>
                    </div>
                    ${v.id === activeId
                      ? html`<jf-button
                          label="Deactivate"
                          ?disabled=${activating}
                          .onActivate=${() => void this.deactivateVariant()}
                        >
                          Deactivate
                        </jf-button>`
                      : html`<jf-button
                          variant="primary"
                          label="Activate"
                          ?disabled=${activating || v.available === false}
                          .onActivate=${() => void this.activateVariant(v.id)}
                        >
                          Activate
                        </jf-button>`}
                  </div>
                `,
              )}
              ${actState === 'running'
                ? html`<div style="font-size: var(--font-size-xs); color: var(--text-secondary)">
                    Activating: ${this.runtimeStatus?.activation?.phase ?? '…'}
                  </div>`
                : nothing}
            `
          : nothing}

        <!-- Inference mode -->
        <div style="margin-top: 1rem">
          <div
            style="font-size: var(--font-size-xs); text-transform: uppercase; color: var(--text-secondary); margin-bottom: 0.375rem"
          >
            Inference mode
          </div>
          <div class="row">
            <jf-button
              variant=${this.inference?.mode === 'online' ? 'primary' : 'secondary'}
              label="Online"
              .availability=${this.policy?.onlineAiEnabled === false
                ? unavailableBecause('Online AI is disabled by administrator policy.')
                : this.busy['inference-switch']
                  ? { kind: 'blocked' }
                  : AVAILABLE}
              .onActivate=${() => void this.switchInference('online')}
            >
              Online
            </jf-button>
            <jf-button
              variant=${this.inference?.mode === 'indexing' ? 'primary' : 'secondary'}
              label="Indexing"
              ?disabled=${!!this.busy['inference-switch']}
              .onActivate=${() => void this.switchInference('indexing')}
            >
              Indexing
            </jf-button>
            <jf-button
              label="Reload"
              ?disabled=${!!this.busy['inference-switch']}
              .onActivate=${() =>
                this.withBusy('inference-switch', () => this.invokeOp('core.reload-inference'))}
            >
              Reload
            </jf-button>
            ${this.inference?.embeddingQueueSize !== undefined
              ? html`<span style="font-size: var(--font-size-xs); color: var(--text-secondary); align-self: center">
                  embed queue: ${NUM.format(this.inference.embeddingQueueSize)}
                  ${this.inference.vduQueueSize !== undefined
                    ? html` · VDU queue: ${NUM.format(this.inference.vduQueueSize)}`
                    : nothing}
                </span>`
              : nothing}
          </div>
        </div>

        <!-- LLM settings -->
        <div style="margin-top: 1rem">
          <div
            style="font-size: var(--font-size-xs); text-transform: uppercase; color: var(--text-secondary); margin-bottom: 0.375rem"
          >
            LLM settings
          </div>
          <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem">
            <label class="field">
              Model path
              <input
                type="text"
                .value=${this.llm.modelPath ?? ''}
                @change=${(e: Event) =>
                  void this.patchLlm({ modelPath: (e.target as HTMLInputElement).value || null })}
              />
            </label>
            <label class="field">
              Server executable
              <input
                type="text"
                .value=${this.llm.serverExecutable ?? ''}
                @change=${(e: Event) =>
                  void this.patchLlm({
                    serverExecutable: (e.target as HTMLInputElement).value || null,
                  })}
              />
            </label>
            <label class="field">
              Context window
              <input
                type="number"
                min="0"
                .value=${String(this.llm.contextWindow ?? 0)}
                @change=${(e: Event) =>
                  void this.patchLlm({
                    contextWindow: Number((e.target as HTMLInputElement).value) || 0,
                  })}
              />
            </label>
            <label class="field">
              Max tokens
              <input
                type="number"
                min="0"
                .value=${String(this.llm.maxTokens ?? 0)}
                @change=${(e: Event) =>
                  void this.patchLlm({ maxTokens: Number((e.target as HTMLInputElement).value) || 0 })}
              />
            </label>
            <label class="field">
              GPU layers
              <input
                type="number"
                min="0"
                .value=${String(this.llm.gpuLayers ?? 0)}
                @change=${(e: Event) =>
                  void this.patchLlm({ gpuLayers: Number((e.target as HTMLInputElement).value) || 0 })}
              />
            </label>
            <label class="field">
              Llama lib path
              <input
                type="text"
                .value=${this.llm.llamaLibPath ?? ''}
                @change=${(e: Event) =>
                  void this.patchLlm({ llamaLibPath: (e.target as HTMLInputElement).value || null })}
              />
            </label>
          </div>
        </div>
      </div>
    `;
  }

  // ---------- Top render ----------

  override render(): TemplateResult {
    // Tempdoc 571 §11 / 578 — AI Brain ⊇ Memory: tab 0 is Brain's own runtime-config body (slotted so
    // this surface's shadow CSS styles it); the remaining tabs are the declared members (Memory).
    const members = getSurface('core.brain-surface')?.members ?? [];
    const items: SurfaceTabItem[] = [
      { id: 'runtime', label: 'AI Brain', altitude: 'PRODUCT', slot: 'tab-runtime' },
      ...members.map((mid) => ({
        id: mid,
        label: present({ kind: 'surface', id: mid }).label,
        altitude: getSurface(mid)?.altitude,
        surfaceId: mid,
      })),
    ];
    return html`
      <jf-surface-tabs
        tablist-label="AI Brain views"
        api-base=${this.apiBase}
        .host_=${this.host_}
        active-id=${this.activeTab}
        .items=${items}
        @tab-change=${(e: CustomEvent<{ id: string }>) => (this.activeTab = e.detail.id)}
      >
        <div slot="tab-runtime" class="brain-scroll">${this.renderBrainBody()}</div>
      </jf-surface-tabs>
    `;
  }

  // Tempdoc 586 §F-1a — first-paint skeleton shown until the initial status lands
  // (inference from the shared store, the rest from refreshAll), instead of a bare panel.
  private renderLoadingSkeleton(): TemplateResult {
    return html`
      <div
        style="display: flex; align-items: center; gap: 0.625rem; padding: 1rem; color: var(--text-secondary); font-size: var(--font-size-sm)"
      >
        <jf-pulse-dots></jf-pulse-dots>
        <span>Checking AI status…</span>
      </div>
    `;
  }

  private renderBrainBody(): TemplateResult {
    if (!this.apiBase && this.apiBase !== '') {
      return html`<div class="empty-state">No API connection. Start the JustSearch backend to configure AI.</div>`;
    }
    const mode = this.settings.mode ?? 'simple';
    // Tempdoc 586 §F-1a — true cold start (no snapshot yet from store or refreshAll).
    const loading =
      this.inference == null && this.installStatus == null && this.runtimeStatus == null;
    return html`
      ${this.renderHeader()}
      <div class="body">
        ${this.renderAlerts()}
        ${loading ? this.renderLoadingSkeleton() : nothing}
        ${!loading && mode === 'simple' ? this.renderSimplePanel() : nothing}
        ${!loading && mode === 'advanced'
          ? html`
              <button
                style="display: inline-flex; align-items: center; gap: 0.4rem; align-self: flex-start; background: transparent; border: none; color: var(--text-secondary); cursor: pointer; font-size: var(--font-size-sm); padding: 0"
                @click=${() => this.setMode('simple')}
              >
                ${icon({ name: 'chevron-down', size: 14 })}
                <span style="display: inline-flex; transform: rotate(90deg)"></span>
                Simple view
              </button>
              ${this.renderCompatibilityCallouts()}
              ${this.renderEmbeddingProgress()}
              ${this.renderAccordion(
                'install',
                'Install AI',
                this.installStatus?.state ?? 'idle',
                () => html`<div style="padding-top: 0.625rem">${this.renderInstallSection()}</div>`,
              )}
              ${this.renderSearchQualityFeatures()}
              ${this.renderAccordion(
                'runtime',
                'Runtime',
                this.inference?.mode ? `Mode: ${this.inference.mode}` : null,
                () =>
                  html`<div style="padding-top: 0.625rem">${this.renderRuntimeSection()}</div>`,
              )}
              ${this.renderModels()}
              ${this.renderPackImport()}
            `
          : nothing}
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-brain-surface')) {
  customElements.define('jf-brain-surface', BrainSurface);
}
