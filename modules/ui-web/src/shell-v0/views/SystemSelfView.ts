// SPDX-License-Identifier: Apache-2.0
/**
 * SystemSelfView — tempdoc 575 §17 Face B (the human projection of the observed-happening register).
 *
 * One glanceable "what is the system doing right now?" RAIL surface. It is the ONLY surface that can
 * answer that, because the register enumerates the system's live concepts. It COMPOSES the existing
 * live authorities — it does NOT fuse them (571 §9.D): the task substrate (indexing jobs, a PRODUCT
 * concept) and the unified AI state (install/runtime, a DIAGNOSTIC concept) — ordered by altitude
 * (DIAGNOSTIC before PRODUCT), with a CONFIDENT idle state (a positive "system idle" signal, not a
 * blank panel — the gap VS Code / JetBrains status bars leave). It declares EMPTY consumes in the
 * catalog ⟹ derives PRODUCT altitude, so it never trips the altitude-conflict merge-foreclosure.
 *
 * Side-effect registers `<jf-system-self-view>` for the chrome dispatcher.
 *
 * Tempdoc 578 Workstream A — the standalone `core.system-self-view` RAIL surface is RETIRED ("Now is
 * too empty to stand alone", §5.5). This element is now embedded as Health's top live-strip via
 * `variant="strip"` (HealthSurface imports it directly); a deep-link to `core.system-self-view`
 * redirects to the System hub → Health (RETIRED_SURFACE_ALIASES). The 575 §17 capability — compose
 * the live concepts by altitude — is preserved as that strip.
 */

import { html, css, nothing, type PropertyValues, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { surfaceLayoutStyles } from '../primitives/surfaceLayout.js';
import { subscribeTasks, listTasks, type Task } from '../substrates/tasks/index.js';
import { subscribeAiState, type AiState } from '../state/aiStateStore.js';
import { isKnown, type Maybe } from '../state/known.js';
import {
  subscribeInstallStatus,
  getInstallLiveStatus,
  setInstallStatusApiBase,
} from '../substrates/ai/aiInstallBridge.js';

function knownPositive(value: Maybe<number>): number {
  return isKnown(value) && value.value > 0 ? value.value : 0;
}

export function visibleIndexQueueCount(aiState: Pick<AiState, 'index'> | null): number | null {
  if (aiState === null) return null;
  const index = aiState.index;
  const pendingJobs = knownPositive(index.pendingJobs);
  if (pendingJobs > 0) return pendingJobs;
  const queued =
    knownPositive(index.embeddingPending) +
    knownPositive(index.embeddingQueueSize) +
    knownPositive(index.vduQueueSize);
  return queued > 0 ? queued : null;
}

export class SystemSelfView extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
    // Tempdoc 578 Workstream A — 'full' is the historical standalone view; 'strip' is the compact
    // live-strip embedded at the top of Health's body (no own heading, condensed idle). The standalone
    // RAIL surface is retired; the element is now reused by HealthSurface in 'strip' mode.
    variant: { type: String, attribute: 'variant' },
  } as const;

  declare apiBase: string;
  declare variant: 'full' | 'strip';
  private aiState: AiState | null = null;
  private unsubs: Array<() => void> = [];

  constructor() {
    super();
    this.apiBase = '';
    this.variant = 'full';
  }

  override connectedCallback(): void {
    super.connectedCallback();
    setInstallStatusApiBase(this.apiBase);
    this.unsubs.push(subscribeTasks(() => this.requestUpdate()));
    this.unsubs.push(
      subscribeAiState((s) => {
        this.aiState = s;
        this.requestUpdate();
      }),
    );
    // Tempdoc 575 §17 Face C: the install/pack live feed (DIAGNOSTIC tier) — derived from the ONE
    // liveness authority via aiInstallBridge, so a running/stalled install shows in "what's happening now".
    this.unsubs.push(subscribeInstallStatus(() => this.requestUpdate(), this.apiBase));
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    for (const u of this.unsubs) u();
    this.unsubs = [];
  }

  override updated(changed: PropertyValues<this>): void {
    if (changed.has('apiBase')) {
      setInstallStatusApiBase(this.apiBase);
    }
  }

  // Tempdoc 559 Authority I: the :host / .header / .body region contract is owned by
  // surfaceLayoutStyles (the one layout authority); only bespoke rules live here.
  static styles = [
    surfaceLayoutStyles,
    css`
      .section-label {
        margin: 0.75rem 0 0.25rem 0;
        font-size: var(--font-size-xs);
        font-weight: 600;
        letter-spacing: 0.03em;
        text-transform: uppercase;
        color: var(--text-secondary);
      }
      .row {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0.35rem 0;
      }
      .label {
        flex: 1;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        font-size: var(--font-size-sm);
      }
      .badge {
        font-size: var(--font-size-xs);
        padding: 0.05rem 0.4rem;
        border-radius: 0.25rem;
        border: 1px solid currentColor;
      }
      .badge-running {
        color: var(--text-info);
      }
      .badge-stalled {
        color: var(--text-warning);
      }
      .badge-queued {
        color: var(--text-secondary);
      }
      .idle {
        margin-top: 1rem;
        padding: 1rem;
        border: 1px solid var(--border-subtle);
        border-radius: 0.5rem;
        color: var(--text-secondary);
        font-size: var(--font-size-sm);
      }
      .idle strong {
        color: var(--text-success);
      }
      /* 578 Workstream A — the compact strip embedded at the top of Health: tighter, no big margin. */
      .idle-strip {
        margin-top: 0;
        padding: 0.5rem 0.75rem;
      }
    `,
  ];

  /** Live tasks (indexing jobs project here), running first, then queued — the PRODUCT live set. */
  private liveTasks(): Task[] {
    const rank: Record<string, number> = { running: 0, queued: 1 };
    return listTasks()
      .filter((t) => t.status === 'running' || t.status === 'queued')
      .slice()
      .sort((a, b) => (rank[a.status] ?? 9) - (rank[b.status] ?? 9));
  }

  /** Is the AI runtime doing live work (a non-idle agent activity)? The higher-altitude live concept. */
  private aiActivityLabel(): string | null {
    const s = this.aiState?.activity?.state;
    if (!s || s === 'idle') return null;
    return s;
  }

  override render(): TemplateResult {
    const tasks = this.liveTasks();
    const running = tasks.filter((t) => t.status === 'running').length;
    const queued = tasks.length - running;
    const aiLabel = this.aiActivityLabel();
    const install = getInstallLiveStatus();
    const installRunning = install?.state === 'running';
    const indexQueueCount = visibleIndexQueueCount(this.aiState);
    const indexQueueBusy = indexQueueCount !== null && tasks.length === 0;
    const idle = tasks.length === 0 && aiLabel === null && !installRunning && !indexQueueBusy;
    const strip = this.variant === 'strip';

    return html`
      ${strip
        ? nothing
        : html`
            <div class="header">
              <!-- Tempdoc 571 §11 / 578: shell topbar owns the page <h1>; <h2> here so this view is
                   embeddable as a host member without emitting a second <h1>. -->
              <h2>Now</h2>
              <div class="sub">What the system is doing right now — most important first.</div>
            </div>
          `}
      <div class="body">
        ${aiLabel !== null
          ? html`
              <div class="section-label">AI runtime</div>
              <div class="row">
                <span class="label">${aiLabel}</span>
                <span class="badge badge-running">working</span>
              </div>
            `
          : nothing}
        ${installRunning
          ? html`
              <div class="section-label">AI install</div>
              <div class="row" data-testid="self-view-install">
                <span class="label">${install?.message || 'Installing…'}</span>
                <span class="badge ${install?.stalled ? 'badge-stalled' : 'badge-running'}"
                  >${install?.stalled ? 'stalled' : 'running'}</span
                >
              </div>
            `
          : nothing}
        ${tasks.length > 0
          ? html`
              <div class="section-label">
                Indexing — ${running} running · ${queued} queued
              </div>
              ${tasks.slice(0, 8).map(
                (t) => html`
                  <div class="row" data-testid="self-view-task">
                    <span class="label">${t.label}</span>
                    <span class="badge badge-${t.status}">${t.status}</span>
                  </div>
                `,
              )}
              ${tasks.length > 8
                ? html`<div class="section-label">+${tasks.length - 8} more</div>`
                : nothing}
            `
          : nothing}
        ${indexQueueBusy
          ? html`
              <div class="section-label">Indexing</div>
              <div class="row" data-testid="self-view-index-queue">
                <span class="label">Processing ${indexQueueCount.toLocaleString()} items</span>
                <span class="badge badge-running">running</span>
              </div>
            `
          : nothing}
        ${idle
          ? strip
            ? html`
                <div class="idle idle-strip" data-testid="self-view-idle">
                  <strong>System idle</strong> — nothing running right now.
                </div>
              `
            : html`
                <div class="idle" data-testid="self-view-idle">
                  <strong>System idle</strong> — nothing is running right now.
                  ${this.aiState
                    ? html`<div style="margin-top: 0.25rem">Connection: ${this.aiState.phase}.</div>`
                    : nothing}
                </div>
              `
          : nothing}
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-system-self-view')) {
  customElements.define('jf-system-self-view', SystemSelfView);
}
