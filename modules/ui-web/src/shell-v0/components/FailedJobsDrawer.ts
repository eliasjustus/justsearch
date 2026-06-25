// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-failed-jobs-drawer> — Tempdoc 599 §16/B1.
 *
 * The per-folder "failed files" drill-down: a right-drawer (mirroring RetrospectivePanel /
 * AdvisoryInboxDrawer) opened from a folder row's clickable "N failed" badge. Lists the FAILED
 * indexing jobs under that watched root (the folder-scoped `/api/indexing-jobs/failed/by-prefix`
 * endpoint), each with its error and a Retry. Retry dispatches the existing user-audience Operation
 * `core.retry-indexing-job` DIRECTLY via the OperationClient (the failed-jobs Resource itself is
 * operator-only, so we do not surface it as a Resource view here — we reuse the Operation, not the
 * Resource). After a retry the list refetches; the folder row's count then drops on its own live tick.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import './RowActions.js';
import { icon } from './Icon.js';
import { TransientController } from '../primitives/transientController.js';
import {
  isFailedJobsOpen,
  failedJobsFolderPathHash,
  closeFailedJobs,
  subscribeFailedJobs,
} from '../state/failedJobsDrawer.js';
import { resolvePathLazy } from '../hooks/resolvePathLazy.js';
import { getOperationClient } from '../operations/OperationClient.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

const RESOURCE_ID = 'core.failed-indexing-jobs';
const RETRY_OP = 'core.retry-indexing-job';

interface FailedRow {
  readonly pathHash: string;
  readonly errorMessage: string;
}

export class FailedJobsDrawer extends JfElement {
  static properties = {
    open: { type: Boolean, reflect: true },
    apiBase: { attribute: 'api-base', type: String },
    host_: { attribute: false },
    rows: { state: true },
    loading: { state: true },
    error: { state: true },
    resolved: { state: true },
  };

  declare open: boolean;
  declare apiBase: string;
  declare host_: PluginHostApi | undefined;
  declare rows: FailedRow[];
  declare loading: boolean;
  declare error: string | null;
  /** pathHash → resolved display path (lazy). */
  declare resolved: Record<string, string>;

  private unsub: (() => void) | null = null;

  // 574 §23.B — single-open arbitration by construction; the panel reflects the failedJobsDrawer store.
  private readonly transient = new TransientController(this, {
    layer: 'right-drawer',
    id: 'failed-jobs',
    close: () => closeFailedJobs(),
  });

  constructor() {
    super();
    this.open = isFailedJobsOpen();
    this.apiBase = '';
    this.rows = [];
    this.loading = false;
    this.error = null;
    this.resolved = {};
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.unsub = subscribeFailedJobs(() => {
      const next = isFailedJobsOpen();
      const opening = next && !this.open;
      this.open = next;
      if (opening) void this.refresh();
      this.requestUpdate();
    });
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsub?.();
    this.unsub = null;
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('open')) {
      if (this.open) this.transient.open();
      else this.transient.close();
    }
  }

  private async refresh(): Promise<void> {
    const folder = failedJobsFolderPathHash();
    if (!folder) return;
    this.loading = true;
    this.error = null;
    try {
      const res = await this.doFetch(
        `/api/indexing-jobs/failed/by-prefix?pathHash=${encodeURIComponent(folder)}`,
      );
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const body = await res.json();
      const items: Array<Record<string, unknown>> = Array.isArray(body?.jobs) ? body.jobs : [];
      this.rows = items.map((j) => ({
        pathHash: String(j['pathHash'] ?? ''),
        errorMessage: String(j['errorMessage'] ?? ''),
      }));
      // Lazy-resolve hashes → display paths (best-effort; deleted files resolve to null → show hash).
      for (const r of this.rows) {
        if (r.pathHash && !this.resolved[r.pathHash]) {
          void resolvePathLazy(RESOURCE_ID, r.pathHash, { apiBase: this.apiBase }).then((path) => {
            if (path) this.resolved = { ...this.resolved, [r.pathHash]: path };
          });
        }
      }
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    } finally {
      this.loading = false;
    }
  }

  private doFetch(path: string): Promise<Response> {
    if (this.host_?.data?.fetch) return this.host_.data.fetch(path);
    return fetch((this.apiBase || '') + path);
  }

  /**
   * Per-row retry/cancel are rendered by {@code <jf-row-actions>} (tempdoc 599 §16.1 Move 1 — the
   * failed-jobs Resource now declares the retry/cancel item-operations); when one succeeds, drop that
   * row. The folder row's "N failed" count then updates on its own live tick.
   */
  private onRowActionSuccess(e: Event): void {
    const key = (e as CustomEvent<{ rowKey?: string }>).detail?.rowKey;
    if (key) this.rows = this.rows.filter((r) => r.pathHash !== key);
  }

  /** Header bulk action: invoke the existing retry Operation for each listed file, dropping each on
   * success (the "Retry all" analogue of the Advisories drawer's "Mark all read"). */
  private async retryAll(): Promise<void> {
    const client = getOperationClient(this.apiBase || '');
    for (const r of [...this.rows]) {
      try {
        await client.invoke(RETRY_OP, { args: { pathHash: r.pathHash } });
        this.rows = this.rows.filter((x) => x.pathHash !== r.pathHash);
      } catch {
        // Leave the failed row; its error stays visible. (Surfacing a toast is the message channel's job.)
      }
    }
  }

  static styles = css`
    :host(:not([open])) {
      display: none;
    }
    .panel {
      position: relative;
      height: 100%;
      width: 26rem;
      max-width: 90vw;
      background: var(--surface-1);
      border-left: 1px solid var(--border-default);
      box-shadow: -4px 0 16px rgba(0, 0, 0, 0.35);
      color: var(--text-primary);
      display: flex;
      flex-direction: column;
    }
    .head {
      flex-shrink: 0;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--border-default);
    }
    .title {
      font-weight: 600;
      font-size: var(--font-size-sm);
    }
    .head-actions {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    .body {
      flex: 1;
      overflow-y: auto;
      padding: 0.5rem 1rem;
    }
    .row {
      display: flex;
      align-items: flex-start;
      gap: 0.75rem;
      padding: 0.6rem 0;
      border-bottom: 1px solid var(--border-subtle);
    }
    .row-info {
      flex: 1;
      min-inline-size: 0;
    }
    .row-path {
      font-size: var(--font-size-sm);
      color: var(--text-primary);
      overflow-wrap: anywhere;
    }
    .row-error {
      margin-top: 0.15rem;
      font-size: var(--font-size-xs);
      color: var(--text-danger);
      overflow-wrap: anywhere;
    }
    .empty,
    .loading {
      padding: 1.5rem 0;
      text-align: center;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
  `;

  override render(): TemplateResult {
    if (!this.open) return html``;
    return html`
      <div class="panel" role="dialog" aria-label="Failed files">
        <div class="head">
          <span class="title">Failed files</span>
          <div class="head-actions">
            ${this.rows.length > 0
              ? html`<jf-button
                  size="sm"
                  variant="ghost"
                  label="Retry all failed files"
                  .onActivate=${() => void this.retryAll()}
                  >Retry all</jf-button
                >`
              : nothing}
            <jf-button
              size="sm"
              variant="ghost"
              label="Close"
              .onActivate=${() => closeFailedJobs()}
              >${icon({ name: 'x', size: 14 })}</jf-button
            >
          </div>
        </div>
        <div class="body" @row-action-success=${(e: Event) => this.onRowActionSuccess(e)}>
          ${this.renderBody()}
        </div>
      </div>
    `;
  }

  private renderBody(): TemplateResult {
    if (this.loading) return html`<div class="loading">Loading…</div>`;
    if (this.error) return html`<div class="empty">Couldn't load failed files: ${this.error}</div>`;
    if (this.rows.length === 0)
      return html`<div class="empty">No failed files in this folder.</div>`;
    return html`${this.rows.map((r) => this.renderRow(r))}`;
  }

  private renderRow(r: FailedRow): TemplateResult {
    const display = this.resolved[r.pathHash] ?? `[${r.pathHash.slice(0, 12)}…]`;
    // Per-row retry/cancel reuse the shared <jf-row-actions> (tempdoc 599 §16.1 Move 1 + §17.2): it
    // reads the failed-jobs Resource's itemOperations and dispatches the user-invocable Operation with
    // {pathHash: rowKey} — no hand-rolled button or direct OperationClient call.
    return html`
      <div class="row">
        <div class="row-info">
          <div class="row-path" title=${display}>${display}</div>
          ${r.errorMessage ? html`<div class="row-error">${r.errorMessage}</div>` : nothing}
        </div>
        <jf-row-actions
          resource-id=${RESOURCE_ID}
          row-key=${r.pathHash}
          api-base=${this.apiBase}
        ></jf-row-actions>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-failed-jobs-drawer')) {
  customElements.define('jf-failed-jobs-drawer', FailedJobsDrawer);
}
