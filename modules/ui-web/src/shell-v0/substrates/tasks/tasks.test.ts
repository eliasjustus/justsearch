// @vitest-environment happy-dom

/**
 * §32 R-E1 — Task substrate tests.
 */

import { beforeEach, describe, it, expect, vi } from 'vitest';
import {
  startTask,
  updateTaskProgress,
  completeTask,
  cancelTask,
  listTasks,
  listRunningTasks,
  clearFinishedTasks,
  upsertMirroredTask,
  isTerminalStatus,
  getTask,
  __resetTasksForTest,
} from './index.js';

beforeEach(() => {
  __resetTasksForTest();
});

describe('§32 R-E1 — Task substrate', () => {
  it('start → running; complete → succeeded (progress 1)', () => {
    const id = startTask({ label: 'reindex' });
    expect(getTask(id)?.status).toBe('running');
    expect(getTask(id)?.cancellable).toBe(false);
    completeTask(id, 'succeeded');
    expect(getTask(id)?.status).toBe('succeeded');
    expect(getTask(id)?.progress).toBe(1);
  });

  it('updateTaskProgress applies only while running', () => {
    const id = startTask({ label: 'x', progress: 0 });
    updateTaskProgress(id, 0.5);
    expect(getTask(id)?.progress).toBe(0.5);
    completeTask(id, 'succeeded');
    updateTaskProgress(id, 0.2); // ignored — not running
    expect(getTask(id)?.progress).toBe(1);
  });

  it('cancelTask invokes the cancel fn and marks the task cancelled', () => {
    const cancel = vi.fn();
    const id = startTask({ label: 'cancellable', cancel });
    expect(getTask(id)?.cancellable).toBe(true);
    cancelTask(id);
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(getTask(id)?.status).toBe('cancelled');
  });

  it('completeTask is a no-op on an already-terminal task (terminal guard)', () => {
    const cancel = vi.fn();
    const id = startTask({ label: 'x', cancel });
    cancelTask(id);
    expect(getTask(id)?.status).toBe('cancelled');
    completeTask(id, 'succeeded'); // ignored — already terminal
    expect(getTask(id)?.status).toBe('cancelled');
  });

  it('listRunningTasks excludes finished; clearFinishedTasks prunes them', () => {
    const a = startTask({ label: 'a' });
    const b = startTask({ label: 'b' });
    completeTask(a, 'failed');
    expect(listRunningTasks().map((t) => t.id)).toEqual([b]);
    clearFinishedTasks();
    expect(listTasks().map((t) => t.id)).toEqual([b]);
  });
});

// Tempdoc 550 Thesis II: the explicit `queued` lifecycle state + upsertMirroredTask.
describe('§550 Thesis II — queued state + upsertMirroredTask', () => {
  it('queued is non-terminal and NOT running (a backlog is not "running")', () => {
    expect(isTerminalStatus('queued')).toBe(false);
    upsertMirroredTask({ id: 'idxjob:q1', label: 'Indexing · default (q1)', status: 'queued' });
    expect(getTask('idxjob:q1')?.status).toBe('queued');
    expect(getTask('idxjob:q1')?.cancellable).toBe(false); // mirrored = read-only
    expect(listRunningTasks().map((t) => t.id)).toEqual([]); // queued ≠ running
  });

  it('clearFinishedTasks does NOT prune queued (it is live, not finished)', () => {
    upsertMirroredTask({ id: 'idxjob:q2', label: 'q2', status: 'queued' });
    clearFinishedTasks();
    expect(getTask('idxjob:q2')?.status).toBe('queued'); // retained
  });

  it('transitions queued → running → preserves id + startedAt across the flip', () => {
    upsertMirroredTask({ id: 'idxjob:q3', label: 'q3', status: 'queued' });
    const startedAt = getTask('idxjob:q3')?.startedAt;
    upsertMirroredTask({ id: 'idxjob:q3', label: 'q3', status: 'running' });
    expect(getTask('idxjob:q3')?.status).toBe('running');
    expect(getTask('idxjob:q3')?.startedAt).toBe(startedAt); // stable across transition
    expect(listTasks().filter((t) => t.id === 'idxjob:q3')).toHaveLength(1); // no dup
  });
});
