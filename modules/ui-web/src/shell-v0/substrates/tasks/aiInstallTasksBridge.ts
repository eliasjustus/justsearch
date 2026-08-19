// SPDX-License-Identifier: Apache-2.0
/**
 * AI-install → Task bridge — tempdoc 840 Phase 5 (Task 3).
 *
 * The staged model acquisition is long-running backend work whose lifecycle the FE only mirrors —
 * exactly what `upsertMirroredTask` exists for — so it belongs on the ONE background-progress
 * surface this app has (the task substrate behind `<jf-task-list>` and `StatusDeck`'s
 * `core.running-job` chip), not on a second bespoke progress widget. `indexingJobsBridge` is the
 * precedent this copies: a stateless reconcile of an external live set onto mirrored tasks, read-only
 * (no cancel affordance — cancel/pause stay on the Brain surface, which owns the ceremony), with a
 * departed item VANISHING rather than being marked with a guessed terminal status.
 *
 * The projected unit is the STAGE, not the file: the stage boundary is where a capability actually
 * becomes usable ("search works now, chat is still downloading"), which is the only progress fact a
 * glanceable tray can carry that a user can act on.
 */

import { isAiInstallLive } from '../ai/aiInstallLiveness.js';
import { subscribeAiInstall, type AiInstallSnapshot } from '../../utils/aiInstallPoll.js';
import { composeStageRows } from '../../state/installComponents.js';
import type { AiInstallStatus } from '../../../api/generated/schema-types/ai-install-status.js';
import { listTasks, removeTask, upsertMirroredTask, type TaskStatus } from './index.js';

/** Every task this bridge owns carries this id prefix, so the reconcile is stateless. */
export const AI_INSTALL_TASK_PREFIX = 'aiinstall:';

/** The surface the tray row returns to — install is managed from Brain. */
const ORIGIN_SURFACE_ID = 'core.brain-surface';

/**
 * Map a stage's state onto its TRUE task lifecycle state, or `null` when the stage should not appear
 * in a "what is running now" tray at all.
 *
 * `completed` / `skipped` VANISH: a finished stage is history, and the Brain surface owns the record.
 * `blocked` is `failed` on purpose — nothing was attempted and nothing will be until the user acts on
 * the reason, so calling it `queued` would promise a wait that never resolves.
 *
 * A `running` stage is asserted RUNNING only while the backend's polled status is still live
 * (`isAiInstallLive`, the brain-install liveness authority) — a wedged owner demotes to `queued`
 * rather than showing a phantom running pill until the backstop reclaims it.
 */
export function stageTaskStatus(
  stageState: string,
  live: boolean,
  paused: boolean,
): TaskStatus | null {
  switch (stageState) {
    case 'running':
      // A paused run is deliberately not "running": the user stopped it, and the bytes are parked.
      return paused || !live ? 'queued' : 'running';
    case 'pending':
      return 'queued';
    case 'failed':
    case 'blocked':
      return 'failed';
    case 'completed':
    case 'skipped':
    case 'cancelled':
    default:
      return null;
  }
}

/**
 * Reconcile the task substrate to the install's current stage set. STATELESS: the set of tasks this
 * bridge owns is derived from the substrate (`aiinstall:` prefix) on each call, so a remount
 * reconciles against whatever already exists instead of orphaning rows.
 *
 * An install that is not running owns no live stages at all — every row is removed, so a completed or
 * cancelled run leaves nothing behind in a tray whose subject is "right now".
 */
export function projectInstallToTasks(
  status: AiInstallStatus | null | undefined,
  now: number = Date.now(),
): void {
  const present = new Set<string>();
  const running = status?.state === 'running';
  if (running) {
    const paused = status?.paused === true;
    const live = isAiInstallLive(status?.updatedAtEpochMs ?? 0, now);
    for (const stage of composeStageRows(status)) {
      if (!stage.id) continue;
      const taskStatus = stageTaskStatus(stage.state, live, paused);
      if (taskStatus === null) continue;
      const id = `${AI_INSTALL_TASK_PREFIX}${stage.id}`;
      present.add(id);
      upsertMirroredTask({
        id,
        label: stageLabel(stage.label, stage.blockedReason, paused),
        status: taskStatus,
        originSurfaceId: ORIGIN_SURFACE_ID,
        // Progress is omitted entirely when the stage declares no byte total — an indeterminate task
        // is a supported state (`Task.progress` absent), a fabricated 0% is not.
        ...(stage.percent === null ? {} : { progress: stage.percent / 100 }),
      });
    }
  }
  for (const t of listTasks()) {
    if (t.id.startsWith(AI_INSTALL_TASK_PREFIX) && !present.has(t.id)) {
      removeTask(t.id);
    }
  }
}

/** The row's words: what is being fetched, and — when it cannot proceed — why. */
function stageLabel(label: string, blockedReason: string | null, paused: boolean): string {
  const base = `Downloading AI · ${label}`;
  if (blockedReason) return `${base} — ${blockedReason}`;
  return paused ? `${base} (paused)` : base;
}

/**
 * Start the bridge: mirror the shared install poller's snapshots into the task substrate. Returns the
 * unsubscribe. Reuses `subscribeAiInstall` rather than opening a poll of its own — the cadence
 * question (fast while installing, slow when idle) is that module's, and a second poller would have
 * to answer it a second time.
 */
export function startAiInstallTasksBridge(): () => void {
  const unsub = subscribeAiInstall((snap: AiInstallSnapshot) => {
    projectInstallToTasks(snap.install);
  });
  return () => {
    unsub();
    projectInstallToTasks(null);
  };
}
