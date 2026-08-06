// @vitest-environment happy-dom

/**
 * §32 R-E1 — <jf-task-list> render tests, re-expressed for the tempdoc 813 §5 redesign.
 *
 * The panel's default view is now ONE aggregate card driven by the indexing-progress projection;
 * the per-file rows moved behind an opt-in "Show files" disclosure. Every pre-813 case below is
 * preserved in INTENT and re-pointed at where its subject now lives (the row cases open the
 * disclosure first), plus the new cases the redesign's honesty rules require.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TaskList, READY_DISMISS_MS } from './TaskList.js';
import {
  startTask,
  upsertMirroredTask,
  __resetTasksForTest,
} from '../substrates/tasks/index.js';
import {
  __feedForTest,
  __resetAiStateForTest,
  type StatusSnapshot,
} from '../state/aiStateStore.js';
import type { IndexingProgress } from '../state/indexingProgress.js';

void TaskList;

let host: HTMLElement;

beforeEach(() => {
  __resetTasksForTest();
  __resetAiStateForTest();
  host = document.createElement('jf-task-list');
  document.body.appendChild(host);
});

afterEach(() => {
  host.remove();
  vi.useRealTimers();
});

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

/** 574 B — cancel is a <jf-button>; activate it from the inner <jf-control> button. */
async function activateJfButton(el: Element | null | undefined): Promise<void> {
  if (!el) throw new Error('activateJfButton: element not found');
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = el.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}

const q = (sel: string): Element | null | undefined => host.shadowRoot?.querySelector(sel);
const text = (sel: string): string | undefined => q(sel)?.textContent?.trim();

/** Open the opt-in per-file disclosure (813 §5) — where the row list now lives. */
async function showFiles(): Promise<void> {
  (q('[data-testid="tasks-disclosure"]') as HTMLButtonElement | null)?.click();
  await flush();
}

/** Set the projection directly — the render-level equivalent of a poll landing. */
async function setProgress(p: Partial<IndexingProgress>): Promise<void> {
  (host as TaskList).progress = {
    phase: 'unknown',
    jobsPending: 0,
    jobsRunning: 0,
    jobsQueued: 0,
    enrichingPercent: null,
    enrichingPending: 0,
    embeddingPending: 0,
    vduPending: 0,
    etaSeconds: null,
    live: true,
    stages: { embedding: true, splade: true, ner: true },
    ...p,
  };
  await flush();
}

const snapshot = (worker: unknown): StatusSnapshot => ({ worker }) as unknown as StatusSnapshot;

const INDEXING_SNAPSHOT = snapshot({
  core: { indexState: 'INDEXING', pendingJobs: 412 },
  enrichment: { backfillMode: 'idle' },
});
const READY_SNAPSHOT = snapshot({
  core: { indexState: 'IDLE', pendingJobs: 0 },
  enrichment: { backfillMode: 'idle' },
});

describe('<jf-task-list> (§32 R-E1)', () => {
  it('collapses when there are no tasks and nothing is indexing or enriching', async () => {
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(true);
  });

  // Pre-813 intent preserved: a running task still renders its status chip, its progress bar and a
  // cancel affordance — they now live inside the opt-in disclosure rather than the default view.
  it('renders a running task with status, progress bar, and cancel (inside the disclosure)', async () => {
    const id = startTask({ label: 'reindex', progress: 0.4, cancel: () => {} });
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(false);
    expect(q(`[data-testid="task-status-${id}"]`)).toBeNull(); // collapsed by default
    await showFiles();
    expect(text(`[data-testid="task-status-${id}"]`)).toBe('running');
    expect(q(`[data-testid="task-cancel-${id}"]`)).not.toBeNull();
    expect(q('.bar > span')).not.toBeNull();
  });

  // Tempdoc 550 Thesis III (bounded projection / F-1 cure), re-expressed at 813 §5's default view:
  // a backlog of queued jobs must render as BOUNDED content — a single COUNT chip, never N pills —
  // and 813 adds that the default view renders no rows at all until asked.
  it('collapses a large queued backlog to a count chip (no per-item flood)', async () => {
    for (let i = 0; i < 550; i++) {
      upsertMirroredTask({ id: `idxjob:q${i}`, label: `Indexing · default (q${i})`, status: 'queued' });
    }
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(false);
    // Default view: 550 queued jobs produce ZERO rows and no chip strip.
    expect(host.shadowRoot?.querySelectorAll('.task').length).toBe(0);
    expect(q('[data-testid="task-summary"]')).toBeNull();
    await showFiles();
    // Disclosed: the count chip reads "550 queued"…
    expect(text('[data-testid="task-count-queued"]')).toBe('550 queued');
    // …and STILL not one row per queued job (queued is collapsed, not listed).
    expect(host.shadowRoot?.querySelectorAll('.task').length).toBe(0);
  });

  it('lists running rows individually while keeping the queued bulk a count', async () => {
    upsertMirroredTask({ id: 'idxjob:r1', label: 'Indexing · default (r1)', status: 'running' });
    for (let i = 0; i < 12; i++) {
      upsertMirroredTask({ id: `idxjob:q${i}`, label: `q${i}`, status: 'queued' });
    }
    await flush();
    await showFiles();
    expect(text('[data-testid="task-count-running"]')).toBe('1 running');
    expect(text('[data-testid="task-count-queued"]')).toBe('12 queued');
    // The single running job is listed as a row; the 12 queued are not.
    expect(host.shadowRoot?.querySelectorAll('.task').length).toBe(1);
  });

  it('the cancel button cancels the task (invokes the cancel fn)', async () => {
    const cancel = vi.fn();
    const id = startTask({ label: 'x', cancel });
    await flush();
    await showFiles();
    await activateJfButton(q(`[data-testid="task-cancel-${id}"]`));
    await flush();
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(text(`[data-testid="task-status-${id}"]`)).toBe('cancelled');
  });
});

describe('<jf-task-list> — aggregate card (813 §5)', () => {
  it('the Indexing phase leads with the phase noun, an INDETERMINATE bar and a remaining count', async () => {
    await setProgress({ phase: 'indexing', jobsPending: 412, jobsQueued: 412 });
    expect(host.hasAttribute('data-empty')).toBe(false);
    expect(text('[data-testid="task-title"]')).toBe('Indexing');
    const bar = q('[data-testid="task-aggregate-bar"]')!;
    // `pendingJobs` is a backlog, not a fraction — the bar must claim activity, never a position.
    expect(bar.classList.contains('indeterminate')).toBe(true);
    expect(bar.getAttribute('aria-valuenow')).toBeNull();
    expect(text('[data-testid="task-aggregate-counts"]')).toBe('412 files remaining');
  });

  it('the Enriching phase renders the coverage percent as a real fraction', async () => {
    await setProgress({ phase: 'enriching', enrichingPercent: 64, enrichingPending: 900 });
    expect(text('[data-testid="task-title"]')).toBe('Enriching');
    const bar = q('[data-testid="task-aggregate-bar"]')!;
    expect(bar.classList.contains('indeterminate')).toBe(false);
    expect(bar.getAttribute('aria-valuenow')).toBe('64');
    expect(text('[data-testid="task-aggregate-counts"]')).toBe('64% · semantic search catching up');
  });

  // Denominator honesty (§1f / availability.ts:193-194): no faithful denominator ⇒ NO number and NO
  // bar — the panel still says what is happening, it just does not invent a fraction.
  it('enriching with a null percent suppresses the number AND the bar, not the card', async () => {
    await setProgress({ phase: 'enriching', enrichingPercent: null, enrichingPending: 0 });
    expect(host.hasAttribute('data-empty')).toBe(false);
    expect(q('[data-testid="task-aggregate-bar"]')).toBeNull();
    expect(text('[data-testid="task-aggregate-counts"]')).toBe('semantic search catching up');
    expect(text('[data-testid="task-aggregate-counts"]')).not.toContain('%');
  });

  // §1d's absence-as-claim defect: the panel used to hide at zero TASKS, so "it disappeared" read as
  // "done" while the semantic layers were still catching up. The aggregate reads the poll, not the
  // SSE row feed, so an empty task list can no longer withdraw the claim.
  it('stays visible during enriching even when the SSE task list is empty', async () => {
    await setProgress({ phase: 'enriching', enrichingPercent: 20, enrichingPending: 500 });
    expect((host as TaskList).tasks.length).toBe(0);
    expect(host.hasAttribute('data-empty')).toBe(false);
    expect(text('[data-testid="task-title"]')).toBe('Enriching');
    // Nothing to disclose — the disclosure is a row-list affordance, not decoration.
    expect(q('[data-testid="tasks-disclosure"]')).toBeNull();
  });

  it('says nothing at all on the `unknown` arm — no phase, no bar, no counts', async () => {
    upsertMirroredTask({ id: 'idxjob:r1', label: 'agent op', status: 'running' });
    await setProgress({ phase: 'unknown' });
    expect(text('[data-testid="task-title"]')).toBe('Tasks');
    expect(q('[data-testid="task-aggregate"]')).toBeNull();
  });

  it('a non-live snapshot renders the stall line rather than passing counts off as live', async () => {
    await setProgress({ phase: 'indexing', jobsPending: 412, live: false });
    expect(q('[data-testid="task-feed-stalled"]')).not.toBeNull();
  });
});

describe('<jf-task-list> — indicative estimate (813 §5b)', () => {
  it('renders a coarse "~" estimate in the Indexing phase when the projection supplies one', async () => {
    await setProgress({ phase: 'indexing', jobsPending: 412, etaSeconds: 130 });
    expect(text('[data-testid="task-aggregate-eta"]')).toBe('~2m 10s at the current rate');
  });

  it('renders NO estimate line when the projection has no honest basis (no placeholder)', async () => {
    await setProgress({ phase: 'indexing', jobsPending: 412, etaSeconds: null });
    expect(q('[data-testid="task-aggregate-eta"]')).toBeNull();
  });

  // §1e — enrichment throughput is legitimately unstable (ingest preempts the backfill at batch
  // boundaries), so the Enriching phase never carries an estimate even if one were supplied.
  it('never renders an estimate during Enriching', async () => {
    await setProgress({ phase: 'enriching', enrichingPercent: 40, etaSeconds: 130 });
    expect(q('[data-testid="task-aggregate-eta"]')).toBeNull();
  });
});

describe('<jf-task-list> — disclosure + terminal state (813 §5)', () => {
  it('the disclosure toggles the row list and reports its state to assistive tech', async () => {
    upsertMirroredTask({ id: 'idxjob:r1', label: 'Indexing · default (r1)', status: 'running' });
    await flush();
    const btn = () => q('[data-testid="tasks-disclosure"]') as HTMLButtonElement;
    expect(btn().getAttribute('aria-expanded')).toBe('false');
    expect(btn().textContent?.trim()).toBe('Show files');
    expect(q('[data-testid="tasks-files"]')).toBeNull();

    btn().click();
    await flush();
    expect(btn().getAttribute('aria-expanded')).toBe('true');
    expect(btn().textContent?.trim()).toBe('Hide files');
    expect(q('[data-testid="tasks-files"]')).not.toBeNull();

    btn().click();
    await flush();
    expect(btn().getAttribute('aria-expanded')).toBe('false');
    expect(q('[data-testid="tasks-files"]')).toBeNull();
  });

  // The completion signal 813 §14.3 chose: a brief STATE, then dismissal — so "the panel vanished"
  // is no longer the only thing the product ever said about being done.
  it('shows a brief terminal state on the active→ready edge, then dismisses itself', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    __feedForTest({ status: INDEXING_SNAPSHOT });
    const el = document.createElement('jf-task-list') as TaskList;
    document.body.appendChild(el);
    await flush();
    expect(el.progress.phase).toBe('indexing');

    __feedForTest({ status: READY_SNAPSHOT });
    await flush();
    await flush();
    expect(el.showingReady).toBe(true);
    expect(el.shadowRoot?.querySelector('[data-testid="task-title"]')?.textContent?.trim()).toBe(
      'Ready — fully searchable',
    );
    expect(el.hasAttribute('data-empty')).toBe(false);

    vi.advanceTimersByTime(READY_DISMISS_MS);
    await flush();
    expect(el.showingReady).toBe(false);
    expect(el.hasAttribute('data-empty')).toBe(true);
    el.remove();
  });

  // `ready` is also the steady state of an idle index — a panel that announced completion on every
  // poll would be claiming an event it never witnessed.
  it('does not flash the terminal state when it never saw an active phase', async () => {
    __feedForTest({ status: READY_SNAPSHOT });
    const el = document.createElement('jf-task-list') as TaskList;
    document.body.appendChild(el);
    await flush();
    expect(el.showingReady).toBe(false);
    expect(el.hasAttribute('data-empty')).toBe(true);
    el.remove();
  });
});
