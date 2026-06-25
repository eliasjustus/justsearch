// SPDX-License-Identifier: Apache-2.0
/**
 * StatusDeck — Lit bottom-bar telemetry zone (slice 461).
 *
 * Replaces the React `<StatusDeck>` (`components/zones/StatusDeck.tsx`)
 * for the Lit chrome. Compact one-line layout:
 *  [conn-badge] [files] [size] [memory] [inference-mode] [queue]
 *
 * Subscribes to shared `statusPoll` + `inferencePoll` primitives.
 *
 * V1 deferrals (vs React parity):
 *  - "Next action" hint button (`deriveStatusDeckNextActionHint`):
 *    moves to V2 via a `nextActionHint.ts` helper. Today the React
 *    version's hint mostly says "Restart worker" or "Switch to online"
 *    which the user can do from Health / Brain surfaces directly.
 *  - Alt-key reveal of all keyboard hints: deferred. The Help surface
 *    documents them.
 *  - Last-search processing-time display: belongs to slice 463
 *    (search HUD); the bridge isn't built yet.
 */

import { html, css, type TemplateResult, nothing } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { icon } from './Icon.js';
import './StatusBadge.js';
import { type NoticeTone } from '../utils/statusTone.js';
import { LIFECYCLE } from '../../api/lifecycleState.js';
import { subscribeAiState, type AiState } from '../state/aiStateStore.js';
import { presentVerdict, type VerdictKind } from '../state/verdict.js';
import { emitEphemeralToast } from './advisory/ephemeralToast.js';
import './SystemNotice.js';
import { orElse } from '../state/known.js';
import {
  listStatusBarItems,
  onStatusBarChange,
  registerStatusBarItem,
  type StatusBarItem,
} from '../commands/StatusBarRegistry.js';
// Tempdoc 543 §3.A — kernel-rendered attribution next to plugin items.
import './ProvenanceChip.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
// Tempdoc 559 Part II Authority VI — adaptive overflow instead of clipping.
import { OverflowController } from '../primitives/adaptiveBar.js';
// Tempdoc 559 Authority V — the metric's accessible name projects from here.
import { present } from '../display/present.js';
import { projectFact } from '../display/facts.js';
import { formatBytes, formatCount } from '../display/format.js';
import './Control.js';
import { subscribeTasks, listRunningTasks, type Task } from '../substrates/tasks/index.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';

// Tempdoc 508 §4.3 — core items register via the same contribution
// mechanism plugins use. The render functions are markers (returning
// the item id) — StatusDeck's render method dispatches by id to
// produce the actual reactive template. This unifies core and plugin
// contributions in one ordered list while preserving reactive data flow.
const CORE_STATUSBAR_IDS = [
  'core.running-job',
  'core.conn',
  'core.files',
  'core.size',
  'core.memory',
  'core.inference-mode',
  'core.queue',
] as const;
const CORE_PRIORITY = { 'core.running-job': 5, 'core.conn': 10, 'core.files': 20, 'core.size': 30, 'core.memory': 40, 'core.inference-mode': 50, 'core.queue': 60 } as const;
// 559 Authority V — each core metric DECLARES its accessible name here; the
// rendered aria-label PROJECTS this through present({kind:'metric'}) instead of
// hand-stamping the English inline in renderCoreItem (the declaration-deepening).
const CORE_LABEL: Record<(typeof CORE_STATUSBAR_IDS)[number], string> = {
  'core.running-job': 'Background work',
  'core.conn': 'Connection',
  'core.files': 'Documents indexed',
  'core.size': 'Index size',
  'core.memory': 'Memory in use',
  'core.inference-mode': 'AI status',
  'core.queue': 'Indexing queue',
};
// 559 Authority VI — the always-visible health signals: connection + memory are
// `pinned`, so the adaptive bar trims only the normal tail (files/size/queue/…)
// at narrow widths and never hides these two.
const CORE_PINNED = new Set<(typeof CORE_STATUSBAR_IDS)[number]>(['core.running-job', 'core.conn', 'core.memory']);

let coreStatusItemsRegistered = false;
function ensureCoreStatusItemsRegistered(): void {
  if (coreStatusItemsRegistered) return;
  coreStatusItemsRegistered = true;
  for (const id of CORE_STATUSBAR_IDS) {
    registerStatusBarItem({
      id,
      position: 'left',
      priority: CORE_PRIORITY[id],
      source: 'core',
      provenance: CORE_PROVENANCE,
      accessibleLabel: CORE_LABEL[id],
      overflow: CORE_PINNED.has(id) ? 'pinned' : 'normal',
      // Marker render — StatusDeck dispatches by id for the actual template
      render: () => id,
    });
  }
}

const NUM = new Intl.NumberFormat();

export class StatusDeck extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
    aiState: { state: true },
    pluginItems: { state: true },
    overflowOpen: { state: true },
    runningTasks: { state: true },
  };

  declare apiBase: string;
  declare aiState: AiState | null;
  declare pluginItems: StatusBarItem[];
  declare overflowOpen: boolean;
  // Tempdoc 609 §R (T1.3) — running background jobs (from the Task substrate) for the status-bar chip.
  declare runningTasks: readonly Task[];
  private unsubTasks: (() => void) | null = null;

  // 559 Authority VI — measures the left metrics + tells render how many fit;
  // the rest go behind the "…" control. Cached widths → no oscillation.
  private readonly overflow = new OverflowController(this, {
    items: () => Array.from(this.renderRoot.querySelectorAll('.adaptive-bar .item')) as HTMLElement[],
    container: () => this.renderRoot.querySelector('.adaptive-bar'),
    signature: () => this.leftItemIds().join('|') + ':' + (this.aiState?.statusLabel ?? ''),
    reserve: 40,
    // 559 Authority VI per-item policy — same DOM query/order as items(), so the
    // pinned flags align index-for-index with the measured `.item` elements.
    pinned: () =>
      Array.from(this.renderRoot.querySelectorAll('.adaptive-bar .item')).map(
        (el) => el.getAttribute('data-pinned') === 'true',
      ),
  });

  // B2: the last-known raw status snapshot comes from the ONE observed-state
  // authority (aiStateStore), not a second statusPoll subscription.
  private get status() {
    return this.aiState?.status ?? null;
  }

  private unsubAi: (() => void) | null = null;
  private unsubStatusBar: (() => void) | null = null;
  // 595 §15.3 N1 — true once a `transitioning` verdict has been seen since the last
  // `operational`, so the one-shot completion toast fires even when recovery passes
  // through an intermediate `checking` (settled-but-readiness-unknown) before settling
  // to `operational`. An immediate `transitioning → operational` edge would miss that.
  private sawTransitioning = false;

  constructor() {
    super();
    this.apiBase = '';
    this.aiState = null;
    this.pluginItems = [];
    this.overflowOpen = false;
  }

  /** Left (core + non-core) item ids in priority order — the adaptive set. */
  private leftItemIds(): string[] {
    return listStatusBarItems('left').map((i) => i.id);
  }

  static styles = css`
    :host {
      display: flex;
      align-items: center;
      gap: 0.875rem;
      padding: 0 1rem;
      height: 100%;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      border-top: 1px solid var(--border-subtle);
      background: var(--surface-1);
      position: relative;
    }
    /* 559 Authority VI — the adaptive track: the OverflowController measures this
       and the .item widths, hiding the lowest-priority items into "…" instead of
       clipping. min-width:0 lets it shrink so the controller can react. */
    .adaptive-bar {
      display: flex;
      align-items: center;
      gap: 0.875rem;
      min-width: 0;
      flex: 0 1 auto;
    }
    .adaptive-bar .item[hidden] {
      display: none;
    }
    .overflow-trigger::part(control) {
      padding: 0 0.3rem;
      color: var(--text-secondary);
      font-weight: 700;
      letter-spacing: 0.08em;
    }
    .overflow-menu {
      position: absolute;
      bottom: 100%;
      left: 0.5rem;
      margin-bottom: 0.4rem;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      padding: 0.5rem 0.75rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      box-shadow: var(--shadow-float);
      z-index: var(--z-overlay-chrome);
    }
    .group {
      display: inline-flex;
      align-items: center;
      gap: 0.375rem;
    }
    .dot {
      width: 0.4rem;
      height: 0.4rem;
      border-radius: 50%;
      flex-shrink: 0;
    }
    .dot.healthy {
      background: var(--accent-success);
    }
    .dot.warn {
      background: var(--accent-warning);
    }
    .dot.error {
      background: var(--accent-danger);
    }
    .dot.muted {
      background: var(--text-muted);
    }
    .key {
      color: var(--text-muted);
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    .val {
      color: var(--text-primary);
      font-variant-numeric: tabular-nums;
    }
    /* 595 §15.3 (E2) — the "last known, updating" cue: dimmed so the retained value
       reads as provisional, not a fresh measurement. */
    .val.stale {
      color: var(--text-muted);
      font-style: italic;
    }
    .spacer {
      flex: 1;
    }
    .endpoint {
      color: var(--text-muted);
      font-family: monospace;
      font-size: var(--font-size-xs);
    }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    // B2: ONE observed-state subscription. The store owns the status poll +
    // apiBase; StatusDeck reads the last-known snapshot off aiState.status.
    this.unsubAi = subscribeAiState((s) => (this.aiState = s));
    // Tempdoc 508 §4.3 — register core items via contribution mechanism.
    ensureCoreStatusItemsRegistered();
    // §4 — subscribe to all status bar contributions (core + plugin).
    this.pluginItems = listStatusBarItems('right');
    this.unsubStatusBar = onStatusBarChange(() => {
      this.pluginItems = listStatusBarItems('right');
    });
    // Tempdoc 609 §R (T1.3) — track running jobs for the running-job chip (singleton substrate, so a job
    // started on any surface shows here and follows the user across navigation).
    this.runningTasks = listRunningTasks();
    this.unsubTasks = subscribeTasks(() => {
      this.runningTasks = listRunningTasks();
    });
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubAi?.();
    this.unsubStatusBar?.();
    this.unsubTasks?.();
  }

  /**
   * 595 §15.3 N1 — fire the completion toast after each render that changed the verdict
   * kind. Runs in `updated()` (not the store callback) so it fires on every aiState change,
   * however delivered.
   */
  override updated(): void {
    const kind = this.aiState?.verdict.kind ?? null;
    if (kind !== null) this.announceSettledIfNeeded(kind);
  }

  /**
   * 595 §15.3 N1 — fire the ONE completion toast once on reaching `operational` IF a
   * `transitioning` occurred since the last `operational`. Tracking the transition with a
   * flag (rather than the immediate-previous kind) means an intermediate `checking`/`degraded`
   * stop on the way to `operational` no longer drops the toast (the recovery path
   * `transitioning → checking → operational` is real — a settled poll whose readiness is
   * momentarily `unknown` resolves to `checking` first). Structurally spam-free: first-load
   * (`connecting → operational`) and reconnects (`unreachable → operational`) never set the
   * flag, and it is cleared at each `operational`, so a later `degraded → operational` with no
   * preceding transition stays silent. Routes through the one sanctioned message channel
   * (`emitEphemeralToast`, 559 Authority III) — never a second toast system.
   */
  private announceSettledIfNeeded(next: VerdictKind): void {
    if (next === 'transitioning') {
      this.sawTransitioning = true;
    } else if (next === 'operational') {
      if (this.sawTransitioning) {
        emitEphemeralToast({
          // Tempdoc 613 §5.2 — the `core.verdict.settled` class declares defaultSeverity:'success';
          // sourced from the class policy, not re-stated here.
          message: 'Index ready — all systems operational',
          classId: 'core.verdict.settled',
        });
      }
      this.sawTransitioning = false;
    }
  }

  /**
   * 595 §15.3 N3 — open the Health surface from the status pill, via the ONE
   * `navigate-with-context` seam the Shell listens on (chrome/Shell.ts) → IntentRouter.
   * `composed: true` so the event crosses this component's shadow boundary.
   */
  private openHealth(): void {
    this.dispatchEvent(
      new CustomEvent('navigate-with-context', {
        detail: { target: 'core.health-surface', state: {} },
        bubbles: true,
        composed: true,
      }),
    );
  }

  private connDotClass(): string {
    if (!this.status) return 'muted';
    const head = this.status.components?.head?.state;
    const worker = this.status.components?.worker?.state;
    if (head === LIFECYCLE.READY && worker === LIFECYCLE.READY) return 'healthy';
    if (head === LIFECYCLE.STARTING || worker === LIFECYCLE.STARTING) return 'warn';
    return 'error';
  }

  private memoryDotClass(): string {
    const used = this.status?.memoryUsedBytes ?? 0;
    const max = this.status?.memoryMaxBytes ?? 0;
    if (max <= 0) return 'muted';
    const ratio = used / max;
    if (ratio > 0.9) return 'error';
    if (ratio > 0.8) return 'warn';
    return 'healthy';
  }

  private inferencePillTone(): NoticeTone {
    switch (this.aiState?.statusTier ?? 'offline') {
      case 'online':
        return 'success';
      case 'disconnected':
        return 'error';
      case 'degraded':
        return 'warning';
      default:
        return 'neutral';
    }
  }

  /**
   * Tempdoc 508 §4.3 — dispatch core status bar items by id. Each
   * core item's render() returns its id as a marker; this method
   * produces the actual reactive template from current state.
   */
  private renderCoreItem(id: string): TemplateResult | typeof nothing {
    // Post-merge: data sources unified onto aiStateStore (parallel
    // 508 work) where main migrated them, plus statusPoll for index
    // size + memory which aiStateStore doesn't carry.
    const idx = this.aiState?.index;
    // 594 §17.2 — files/size/memory VALUES derive from the ONE value authority (projectFact), not
    // bespoke per-metric code; `?? '—'` preserves the tri-state "—". 595 §4.3 — while the backend is
    // in flux (rebuild / worker restart) render the "settling" hint '…' (the status label already says
    // "Rebuilding…"), never a settled value that reads as data loss. Queue stays shell-local below.
    const provisional = this.aiState?.stability.kind === 'provisional';
    // 595 §15.3 (E2) — while provisional, show the last SETTLED count/size (dimmed) instead of
    // collapsing to '…', so a healthy rebuild stops looking like data loss. Falls back to '…'
    // only when no settled value has been observed yet. Formatting reuses the shared helpers.
    const settled = this.aiState?.lastSettledIndex ?? null;
    const lastKnown = provisional && settled !== null;
    // Size honesty: only show a last-known size that was actually observed; otherwise '…'.
    const lastKnownSize = lastKnown && settled!.indexSizeBytes != null;
    const docsDisp = provisional
      ? settled
        ? formatCount(settled.documentCount)
        : '…'
      : projectFact('core.files', this.aiState).value ?? '—';
    const sizeDisp = provisional
      ? lastKnownSize
        ? formatBytes(settled!.indexSizeBytes!)
        : '…'
      : projectFact('core.size', this.aiState).value ?? '—';
    const memUsedDisp = provisional ? '…' : projectFact('core.memory', this.aiState).value ?? '—';
    const queue = idx ? orElse(idx.pendingJobs, 0) : 0;
    const embed =
      idx && orElse(idx.embeddingBlocked, false)
        ? 0
        : idx
          ? orElse(idx.embeddingPending, 0)
          : 0;
    const mode = this.aiState?.statusLabel ?? 'offline';
    // 559 Authority V — each status metric carries an accessible NAME (a bare
    // span's aria-label is ignored by AT, so the cluster is role="img" + label).
    // The NAME projects from the StatusBarItem registry via present({kind:'metric'})
    // — `${name}: ${liveValue}` — so the English is declared once on the registry
    // entry, not hand-stamped here (the declaration-deepening, 559 Authority V).
    const name = present({ kind: 'metric', id }).label;
    const connName: Record<string, string> = {
      healthy: 'connected',
      warn: 'starting',
      error: 'error',
      muted: 'unknown',
    };
    switch (id) {
      case 'core.running-job': {
        // Tempdoc 609 §R (T1.3) — the running-job chip: hidden when idle; when work runs it shows the
        // count and (if the job knows its origin) is an operable control returning to that surface.
        const running = this.runningTasks ?? [];
        if (running.length === 0) return nothing;
        const target = running.find((t) => t.originSurfaceId)?.originSurfaceId;
        const count = running.length;
        const text = count === 1 ? running[0]!.label : `${count} running`;
        if (!target) {
          return html`<span class="group" role="img" aria-label="${name}: ${text}">
            <span aria-hidden="true">⟳</span><span class="val">${count}</span>
          </span>`;
        }
        return html`<jf-control
          class="status-pill group"
          label="${name}: ${text}. Return to it."
          .onActivate=${() => requestSurfaceNavigation(target)}
        >
          <span aria-hidden="true">⟳</span><span class="val">${count}</span>
        </jf-control>`;
      }
      case 'core.conn':
        return html`<span
          class="group"
          role="img"
          aria-label="${name}: ${connName[this.connDotClass()] ?? 'unknown'}"
        >
          <span class="dot ${this.connDotClass()}"></span>
          <span class="key">conn</span>
        </span>`;
      case 'core.files':
        return html`<span
          class="group"
          role="img"
          aria-label="${name}: ${docsDisp}${lastKnown ? ' (last known)' : ''}"
          title=${lastKnown ? 'Last known — updating' : nothing}
        >
          ${icon({ name: 'file-text', size: 11 })}
          <span class="val ${lastKnown ? 'stale' : ''}">${docsDisp}</span>
        </span>`;
      case 'core.size':
        return html`<span
          class="group"
          role="img"
          aria-label="${name}: ${sizeDisp}${lastKnownSize ? ' (last known)' : ''}"
          title=${lastKnownSize ? 'Last known — updating' : nothing}
        >
          ${icon({ name: 'database', size: 11 })}
          <span class="val ${lastKnownSize ? 'stale' : ''}">${sizeDisp}</span>
        </span>`;
      case 'core.memory':
        return html`<span class="group" role="img" aria-label="${name}: ${memUsedDisp}">
          <span class="dot ${this.memoryDotClass()}"></span>
          ${icon({ name: 'memory-stick', size: 11 })}
          <span class="val">${memUsedDisp}</span>
        </span>`;
      case 'core.inference-mode':
        // 595 §15.3 N3 — the system pill is operable: a `jf-control` (the one operability
        // primitive — native button, keyboard-activatable, named) that opens Health via the
        // `navigate-with-context` seam. The accessible name still PROJECTS the metric name.
        return html`<jf-control
          class="status-pill group"
          label="${name}: ${mode}. Open Health."
          .onActivate=${() => this.openHealth()}
        >
          <jf-status-badge tone=${this.inferencePillTone()}>${mode}</jf-status-badge>
        </jf-control>`;
      case 'core.queue': {
        if (queue <= 0 && embed <= 0) return nothing;
        // 630: the global, main-surface home for energy-pause legibility. When the OS energy saver
        // is deferring deferrable backfill, a queue that sits still should read as "paused to save
        // energy", not stalled/broken — the calm twin of the Health card's "Paused — saving energy".
        // (Not routed through the degradation banner: that mechanism is degradation-only, and search
        // still works. "Catching up…" already shows here via the verdict pill.)
        const energyPaused = this.status?.power?.energyReduced === true && queue > 0;
        if (energyPaused) {
          return html`<span
            class="group"
            role="img"
            aria-label="${name}: ${NUM.format(queue)} job(s) queued — paused to save energy"
          >
            ${icon({ name: 'moon', size: 11 })}
            <span class="val">queue: ${NUM.format(queue)} · paused</span>
          </span>`;
        }
        return html`<span
          class="group"
          role="img"
          aria-label="${name}: ${NUM.format(queue)} job(s) queued${
            embed > 0 ? `, ${NUM.format(embed)} embedding` : ''
          }"
        >
          ${icon({ name: 'zap', size: 11 })}
          <span class="val">queue: ${NUM.format(queue)}${
            embed > 0 ? html` · embed: ${NUM.format(embed)}` : nothing
          }</span>
        </span>`;
      }
      default:
        return nothing;
    }
  }

  /** Render one left item's content (core → reactive template; plugin → its own). */
  private renderLeftContent(item: StatusBarItem): TemplateResult | typeof nothing {
    return item.source === 'core'
      ? this.renderCoreItem(item.id)
      : html`<span class="group plugin-item" data-plugin-item=${item.id}>${
          item.render()
        }<jf-provenance-chip .provenance=${item.provenance}></jf-provenance-chip></span>`;
  }

  override render(): TemplateResult {
    // 508 §4.3 — items (core + plugin) from the registry, ordered by priority.
    // 559 Authority VI — the LEFT items go into the adaptive bar: the controller
    // hides the lowest-priority ones into "…" rather than clipping.
    const left = listStatusBarItems('left')
      .map((item) => ({
        id: item.id,
        pinned: item.overflow === 'pinned',
        content: this.renderLeftContent(item),
      }))
      .filter((e) => e.content !== nothing);
    const allRight = listStatusBarItems('right').filter((i) => i.source !== 'core');
    const vis = this.overflow.visibleCount;
    const hiddenCount = Number.isFinite(vis) ? Math.max(0, left.length - vis) : 0;
    // 595 §15.1 (E1) — the ONE a11y announcer for system-health verdict changes.
    // A visually-hidden notice mirrors the verdict headline; aria-live announces it
    // only when the text changes (a real verdict change / escalation), silent across
    // identical polls. Reuses 559's `<jf-system-notice live>` role/aria-live authority.
    const announce = this.aiState ? presentVerdict(this.aiState.verdict).announce : null;
    return html`
      ${announce
        ? html`<jf-system-notice
            class="visually-hidden"
            live=${announce.politeness}
            data-testid="verdict-announcer"
            >${announce.text}</jf-system-notice
          >`
        : nothing}
      <div class="adaptive-bar">
        ${left.map(
          (e, i) =>
            html`<span
              class="item"
              ?hidden=${i >= vis}
              data-item=${e.id}
              data-pinned=${e.pinned ? 'true' : 'false'}
              >${e.content}</span
            >`,
        )}
        <jf-control
          class="overflow-trigger"
          ?hidden=${hiddenCount === 0}
          label="${hiddenCount} more status item(s)"
          aria-expanded=${this.overflowOpen ? 'true' : 'false'}
          .onActivate=${() => (this.overflowOpen = !this.overflowOpen)}
          >…</jf-control
        >
      </div>
      ${this.overflowOpen && hiddenCount > 0
        ? html`<div
            class="overflow-menu"
            role="group"
            aria-label="More status"
            @keydown=${(e: KeyboardEvent) =>
              e.key === 'Escape' && (this.overflowOpen = false)}
          >
            ${left.slice(vis).map((e) => html`<span data-item=${e.id}>${e.content}</span>`)}
          </div>`
        : nothing}
      ${allRight.map((item) => html`<span class="group plugin-item" data-plugin-item=${item.id}>${
        item.render()
      }<jf-provenance-chip .provenance=${item.provenance}></jf-provenance-chip></span>`)}
      <span class="spacer"></span>
      <span class="endpoint">${this.apiBase || 'no api'}</span>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-status-deck')) {
  customElements.define('jf-status-deck', StatusDeck);
}
