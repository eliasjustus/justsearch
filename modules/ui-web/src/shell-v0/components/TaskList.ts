// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-task-list> — Tempdoc 543 §32 R-E1.
 *
 * Floating list of long-running tasks (agent operations now; backend indexing
 * jobs as a follow-up): per task a status chip, optional progress bar, and a
 * cancel button when the task is cancellable. Collapses (data-empty) when
 * there are no tasks. Floats lower-LEFT to avoid the PendingEffect queue
 * (lower-right) and the digest (top-right).
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import {
  listTasks,
  cancelTask,
  clearFinishedTasks,
  subscribeTasks,
  isTerminalStatus,
  type Task,
  type TaskStatus,
} from '../substrates/tasks/index.js';
import { countByKey, capWithOverflow } from '../projections/boundedProjection.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';
import { subscribeFeedStalled } from '../substrates/tasks/indexingJobsBridge.js';

/**
 * Bounded projection (tempdoc 550 Thesis III). The rail always shows a per-status
 * summary, then lists individual rows for the actionable/recent tasks up to a
 * total cap — but the `queued` bulk (the F-1 flood source: a backlog of waiting
 * indexing jobs) is collapsed to a COUNT only, never N individual pills. Running
 * / failed / succeeded / cancelled are naturally few (agent ops + failures) and
 * stay individually visible for feedback, capped with a "+N more" overflow.
 */
const MAX_DETAIL_ROWS = 8;
const COUNT_ORDER: readonly TaskStatus[] = [
  'running',
  'queued',
  'failed',
  'succeeded',
  'cancelled',
];

export class TaskList extends JfElement {
  static properties = {
    tasks: { state: true },
    feedStalled: { state: true },
  };

  declare tasks: readonly Task[];
  /** 595 §4.4 — the live jobs feed (SSE) went quiet while work is in-flight. */
  declare feedStalled: boolean;

  private unsub: (() => void) | null = null;
  private feedUnsub: (() => void) | null = null;

  constructor() {
    super();
    this.tasks = listTasks();
    this.feedStalled = false;
  }

  connectedCallback(): void {
    super.connectedCallback();
    this.tasks = listTasks();
    this.unsub = subscribeTasks(() => {
      this.tasks = listTasks();
    });
    this.feedUnsub = subscribeFeedStalled((stalled) => {
      this.feedStalled = stalled;
    });
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsub?.();
    this.unsub = null;
    this.feedUnsub?.();
    this.feedUnsub = null;
  }

  static styles = css`
    :host {
      /* 559 Authority I: placement owned by the OverlayHost bottom-left slot. */
      max-width: 24rem;
      pointer-events: none;
    }
    :host([data-empty]) {
      display: none;
    }
    .panel {
      pointer-events: auto;
      background: var(--surface-1);
      border: 1px solid var(--border-default);
      border-radius: 0.5rem;
      padding: 0.5rem 0.75rem;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    .head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
    }
    .title {
      font-weight: 600;
    }
    .task {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }
    .task-row {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    .status {
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      padding: 0.1rem 0.4rem;
      border-radius: 9999px;
      background: var(--surface-2);
      color: var(--text-secondary);
    }
    .status.queued {
      background: var(--surface-2);
      color: var(--text-secondary);
    }
    .status.running {
      background: var(--accent-info);
      color: #fff;
    }
    .summary {
      display: flex;
      flex-wrap: wrap;
      gap: 0.35rem;
      align-items: center;
    }
    .count {
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      padding: 0.1rem 0.4rem;
      border-radius: 9999px;
      background: var(--surface-2);
      color: var(--text-secondary);
    }
    .count.running {
      background: var(--accent-info);
      color: var(--accent-on-chat);
    }
    .count.failed {
      background: var(--accent-danger);
      color: var(--accent-on-danger);
    }
    .more {
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
      padding: 0.1rem 0;
    }
    .feed-stalled {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      padding: 0.1rem 0;
    }
    .status.succeeded {
      background: var(--accent-success);
      color: var(--accent-on-success);
    }
    .status.failed {
      background: var(--accent-danger);
      color: var(--accent-on-danger);
    }
    .status.cancelled {
      background: var(--surface-2);
      color: var(--text-tertiary);
    }
    .label {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    /* Tempdoc 609 §R (T1.3) — navigable label (return to the job's origin surface). */
    .label-link {
      text-align: left;
      background: transparent;
      border: none;
      color: inherit;
      font: inherit;
      padding: 0;
      cursor: pointer;
      text-decoration: underline;
      text-decoration-style: dotted;
    }
    .label-link:hover,
    .label-link:focus-visible {
      text-decoration-style: solid;
      outline: none;
    }
    .bar {
      height: 4px;
      background: var(--surface-2);
      border-radius: 2px;
      overflow: hidden;
    }
    .bar > span {
      display: block;
      height: 100%;
      background: var(--accent-info);
    }
    /* 574 B (remediation) — cancel/clear are jf-button(sm) atoms now. */
  `;

  updated(): void {
    if (this.tasks.length === 0) this.setAttribute('data-empty', '');
    else this.removeAttribute('data-empty');
  }

  private renderTask(t: Task): TemplateResult {
    return html`
      <div class="task" data-testid="task-${t.id}">
        <div class="task-row">
          <span class="status ${t.status}" data-testid="task-status-${t.id}"
            >${t.status}</span
          >
          ${/* Tempdoc 609 §R (T1.3) — when the task knows its origin surface, the label is a button that
                returns there ("return to running job"); otherwise a plain span. */ ''}
          ${t.originSurfaceId
            ? html`<button
                type="button"
                class="label label-link"
                data-testid="task-return-${t.id}"
                title=${`Return to ${t.label}`}
                @click=${() => requestSurfaceNavigation(t.originSurfaceId!)}
              >
                ${t.label}
              </button>`
            : html`<span class="label" title=${t.label}>${t.label}</span>`}
          ${t.status === 'running' && t.cancellable
            ? html`<jf-button
                size="sm"
                data-testid="task-cancel-${t.id}"
                label="Cancel"
                .onActivate=${() => cancelTask(t.id)}
              >
                Cancel
              </jf-button>`
            : nothing}
        </div>
        ${t.status === 'running' && t.progress !== undefined
          ? html`<div class="bar">
              <span style=${`width:${Math.round(t.progress * 100)}%`}></span>
            </div>`
          : nothing}
      </div>
    `;
  }

  private counts(): Map<TaskStatus, number> {
    // Projection-layer primitive (tempdoc 550 thesis III(b)); shared with the Activity timeline.
    return countByKey(this.tasks, (t) => t.status) as Map<TaskStatus, number>;
  }

  render(): TemplateResult {
    const counts = this.counts();
    const hasFinished = this.tasks.some((t) => isTerminalStatus(t.status));
    // Collapse the `queued` bulk to a count only (the flood cure); list the rest, capped.
    const detail = this.tasks.filter((t) => t.status !== 'queued');
    const { shown, overflow } = capWithOverflow(detail, MAX_DETAIL_ROWS);
    return html`
      <div class="panel">
        <div class="head">
          <span class="title">Tasks</span>
          ${hasFinished
            ? html`<jf-button
                size="sm"
                data-testid="tasks-clear"
                label="Clear finished"
                .onActivate=${() => clearFinishedTasks()}
              >
                Clear finished
              </jf-button>`
            : nothing}
        </div>
        <!-- Bounded summary: one count chip per non-empty status (550 Thesis III). -->
        <div class="summary" data-testid="task-summary">
          ${COUNT_ORDER.filter((s) => (counts.get(s) ?? 0) > 0).map(
            (s) => html`<span class="count ${s}" data-testid="task-count-${s}"
              >${counts.get(s)} ${s}</span
            >`,
          )}
        </div>
        <!-- 595 §4.4: when the live feed stalls, say so — don't show stale counts
             as if they were live (the §1.3 freeze). -->
        ${this.feedStalled
          ? html`<div class="feed-stalled" data-testid="task-feed-stalled">
              ⚠ Live updates paused — reconnecting…
            </div>`
          : nothing}
        <!-- Individual rows for actionable/recent tasks; queued stays a count. -->
        ${shown.map((t) => this.renderTask(t))}
        ${overflow > 0
          ? html`<div class="more" data-testid="task-more">
              +${overflow} more
            </div>`
          : nothing}
      </div>
    `;
  }
}

if (!customElements.get('jf-task-list')) {
  customElements.define('jf-task-list', TaskList);
}
