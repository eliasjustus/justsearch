// @vitest-environment happy-dom

/**
 * §32 R-E1 — <jf-task-list> render tests.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TaskList } from './TaskList.js';
import {
  startTask,
  upsertMirroredTask,
  __resetTasksForTest,
} from '../substrates/tasks/index.js';

void TaskList;

let host: HTMLElement;

beforeEach(() => {
  __resetTasksForTest();
  host = document.createElement('jf-task-list');
  document.body.appendChild(host);
});

afterEach(() => {
  host.remove();
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

describe('<jf-task-list> (§32 R-E1)', () => {
  it('collapses when there are no tasks', async () => {
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(true);
  });

  it('renders a running task with status, progress bar, and cancel', async () => {
    const id = startTask({ label: 'reindex', progress: 0.4, cancel: () => {} });
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(false);
    expect(
      host.shadowRoot
        ?.querySelector(`[data-testid="task-status-${id}"]`)
        ?.textContent?.trim(),
    ).toBe('running');
    expect(
      host.shadowRoot?.querySelector(`[data-testid="task-cancel-${id}"]`),
    ).not.toBeNull();
    expect(host.shadowRoot?.querySelector('.bar > span')).not.toBeNull();
  });

  // Tempdoc 550 Thesis III (bounded projection / F-1 cure): a backlog of queued
  // jobs must render as a single COUNT chip, never as N individual pills.
  it('collapses a large queued backlog to a count chip (no per-item flood)', async () => {
    for (let i = 0; i < 40; i++) {
      upsertMirroredTask({ id: `idxjob:q${i}`, label: `Indexing · default (q${i})`, status: 'queued' });
    }
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(false);
    // The count chip reads "40 queued"…
    expect(
      host.shadowRoot?.querySelector('[data-testid="task-count-queued"]')?.textContent?.trim(),
    ).toBe('40 queued');
    // …and NOT one row per queued job (queued is collapsed, not listed).
    expect(host.shadowRoot?.querySelectorAll('.task').length).toBe(0);
  });

  it('lists running rows individually while keeping the queued bulk a count', async () => {
    upsertMirroredTask({ id: 'idxjob:r1', label: 'Indexing · default (r1)', status: 'running' });
    for (let i = 0; i < 12; i++) {
      upsertMirroredTask({ id: `idxjob:q${i}`, label: `q${i}`, status: 'queued' });
    }
    await flush();
    expect(
      host.shadowRoot?.querySelector('[data-testid="task-count-running"]')?.textContent?.trim(),
    ).toBe('1 running');
    expect(
      host.shadowRoot?.querySelector('[data-testid="task-count-queued"]')?.textContent?.trim(),
    ).toBe('12 queued');
    // The single running job is listed as a row; the 12 queued are not.
    expect(host.shadowRoot?.querySelectorAll('.task').length).toBe(1);
  });

  it('the cancel button cancels the task (invokes the cancel fn)', async () => {
    const cancel = vi.fn();
    const id = startTask({ label: 'x', cancel });
    await flush();
    await activateJfButton(
      host.shadowRoot?.querySelector(`[data-testid="task-cancel-${id}"]`),
    );
    await flush();
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(
      host.shadowRoot
        ?.querySelector(`[data-testid="task-status-${id}"]`)
        ?.textContent?.trim(),
    ).toBe('cancelled');
  });
});
