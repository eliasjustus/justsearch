/**
 * Tempdoc 840 Phase 5 (Task 3) — the staged install must reach the app's ONE background-progress
 * surface, not a second bespoke one. These pin the projection onto the task substrate that the
 * footer chip (`core.running-job`) and `<jf-task-list>` already render.
 */

import { afterEach, describe, expect, it } from 'vitest';
import {
  AI_INSTALL_TASK_PREFIX,
  projectInstallToTasks,
  stageTaskStatus,
} from './aiInstallTasksBridge.js';
import { __resetTasksForTest, listRunningTasks, listTasks } from './index.js';
import { AI_INSTALL_STALE_MS } from '../ai/aiInstallLiveness.js';
import type { AiInstallStatus } from '../../../api/generated/schema-types/ai-install-status.js';

const NOW = 1_800_000_000_000;

const RUNNING: AiInstallStatus = {
  state: 'running',
  updatedAtEpochMs: NOW - 1_000,
  currentStage: 'enrichment',
  stages: [
    {
      stage: 'core',
      label: 'Search core',
      state: 'completed',
      totalBytes: 1_000,
      downloadedBytes: 1_000,
    },
    {
      stage: 'enrichment',
      label: 'Retrieval enrichment',
      state: 'running',
      totalBytes: 400,
      downloadedBytes: 100,
    },
    { stage: 'chat', label: 'Chat & AI answers', state: 'pending', totalBytes: 6_000 },
  ],
};

describe('aiInstallTasksBridge — the staged install on the ONE task surface', () => {
  afterEach(() => {
    __resetTasksForTest();
  });

  it('mirrors each LIVE stage as a task, with its true lifecycle state', () => {
    projectInstallToTasks(RUNNING, NOW);
    const byId = new Map(listTasks().map((t) => [t.id, t]));
    expect(byId.get(`${AI_INSTALL_TASK_PREFIX}enrichment`)?.status).toBe('running');
    expect(byId.get(`${AI_INSTALL_TASK_PREFIX}chat`)?.status).toBe('queued');
    // A finished stage is history, not live work — it vanishes rather than lingering as 'succeeded'.
    expect(byId.has(`${AI_INSTALL_TASK_PREFIX}core`)).toBe(false);
  });

  it('carries per-stage progress and a return route to the surface that owns the install', () => {
    projectInstallToTasks(RUNNING, NOW);
    const enrichment = listTasks().find((t) => t.id.endsWith('enrichment'));
    expect(enrichment?.progress).toBeCloseTo(0.25, 5);
    expect(enrichment?.originSurfaceId).toBe('core.brain-surface');
    expect(enrichment?.label).toBe('Downloading AI · Retrieval enrichment');
    // Read-only mirror: cancel/pause stay on Brain, which owns the ceremony.
    expect(enrichment?.cancellable).toBe(false);
  });

  it('omits progress entirely for a stage with no declared byte total (no fabricated 0%)', () => {
    projectInstallToTasks(
      { state: 'running', updatedAtEpochMs: NOW, stages: [{ stage: 'chat', label: 'Chat', state: 'running' }] },
      NOW,
    );
    expect(listTasks()[0]!.progress).toBeUndefined();
  });

  it('feeds the running-job chip — a running stage IS a running task', () => {
    projectInstallToTasks(RUNNING, NOW);
    expect(listRunningTasks().map((t) => t.id)).toEqual([`${AI_INSTALL_TASK_PREFIX}enrichment`]);
  });

  it('demotes a running stage to queued when the backend heartbeat has gone stale', () => {
    projectInstallToTasks({ ...RUNNING, updatedAtEpochMs: NOW - AI_INSTALL_STALE_MS - 1 }, NOW);
    const enrichment = listTasks().find((t) => t.id.endsWith('enrichment'));
    expect(enrichment?.status).toBe('queued');
  });

  it('reads a PAUSED run as parked, not running, and says so on the row', () => {
    projectInstallToTasks({ ...RUNNING, paused: true }, NOW);
    const enrichment = listTasks().find((t) => t.id.endsWith('enrichment'));
    expect(enrichment?.status).toBe('queued');
    expect(enrichment?.label).toContain('(paused)');
  });

  it('surfaces a blocked stage as failed, carrying the reason the user must act on', () => {
    projectInstallToTasks(
      {
        ...RUNNING,
        stages: [
          {
            stage: 'chat',
            label: 'Chat & AI answers',
            state: 'blocked',
            totalBytes: 6_000,
            blockedReason: 'Not enough disk space: 6.0 GB needed, 2.1 GB free.',
          },
        ],
      },
      NOW,
    );
    const chat = listTasks()[0]!;
    expect(chat.status).toBe('failed');
    expect(chat.label).toContain('Not enough disk space');
  });

  it('clears every mirrored row once the run is no longer running', () => {
    projectInstallToTasks(RUNNING, NOW);
    expect(listTasks().length).toBeGreaterThan(0);
    projectInstallToTasks({ ...RUNNING, state: 'succeeded' }, NOW);
    expect(listTasks()).toEqual([]);
    projectInstallToTasks(RUNNING, NOW);
    projectInstallToTasks(null, NOW);
    expect(listTasks()).toEqual([]);
  });

  it('reconciles statelessly — a stage that leaves the set vanishes, and one that returns re-appears', () => {
    projectInstallToTasks(RUNNING, NOW);
    projectInstallToTasks({ ...RUNNING, stages: RUNNING.stages!.slice(0, 2) }, NOW);
    expect(listTasks().map((t) => t.id)).toEqual([`${AI_INSTALL_TASK_PREFIX}enrichment`]);
    projectInstallToTasks(RUNNING, NOW);
    expect(listTasks().map((t) => t.id).sort()).toEqual([
      `${AI_INSTALL_TASK_PREFIX}chat`,
      `${AI_INSTALL_TASK_PREFIX}enrichment`,
    ]);
  });
});

describe('stageTaskStatus — the stage→task lifecycle map', () => {
  it('never asserts running without a live owner', () => {
    expect(stageTaskStatus('running', true, false)).toBe('running');
    expect(stageTaskStatus('running', false, false)).toBe('queued');
  });

  it('drops terminal stages from a "what is running now" surface', () => {
    expect(stageTaskStatus('completed', true, false)).toBeNull();
    expect(stageTaskStatus('skipped', true, false)).toBeNull();
    expect(stageTaskStatus('cancelled', true, false)).toBeNull();
  });

  it('calls a blocked stage failed — a wait that will never resolve on its own is not queued', () => {
    expect(stageTaskStatus('blocked', true, false)).toBe('failed');
    expect(stageTaskStatus('failed', true, false)).toBe('failed');
  });
});
