// SPDX-License-Identifier: Apache-2.0
/**
 * Brain install/pack live-status bridge (FE) — tempdoc 575 §17 Face C (review fix).
 *
 * The shared, subscribable source of the AI install/pack live state for AMBIENT surfaces (the System
 * Self-View), mirroring how `indexingJobsBridge` projects indexing jobs into the tasks substrate.
 * Polls `/api/ai/install/status` and projects each snapshot through the ONE liveness authority
 * {@link isAiInstallLive} — so "running vs stalled" is decided in one place, never re-derived per
 * surface (the `inflight-liveness` gate registers this file as the brain-install render-bearing site).
 *
 * `BrainSurface` keeps its own detailed poll for install CONTROL; this bridge is the glanceable feed
 * the Self-View subscribes to. The interval BOTH re-polls and re-derives `stalled`: a running install
 * that stops beating crosses the freshness window and flips to stalled even without a new payload.
 */

import { isAiInstallLive } from './aiInstallLiveness.js';
import { authorizedFetch } from '../../api/authorizedFetch.js';
import { friendlyInstallMessage } from '../../state/installComponents.js';
import type { AiInstallStatus } from '../../../api/generated/schema-types/ai-install-status.js';

export interface InstallLiveStatus {
  /** 'idle' | 'running' | 'succeeded' | 'failed' (the backend AiInstallStatus.state). */
  readonly state: string;
  readonly message: string;
  readonly updatedAtEpochMs: number;
  /** True iff the install is "running" but its backend heartbeat has gone stale (the soft warning). */
  readonly stalled: boolean;
}

const POLL_INTERVAL_MS = 2_500;

let current: InstallLiveStatus | null = null;
const listeners = new Set<() => void>();
let timer: ReturnType<typeof setInterval> | null = null;
let apiBase = '';

/** The current projected install status, or null until the first poll. */
export function getInstallLiveStatus(): InstallLiveStatus | null {
  return current;
}

/** Configure the backend base URL used by the packaged WebView poller. */
export function setInstallStatusApiBase(base: string): void {
  apiBase = base || '';
}

/** Subscribe to install/pack live-status changes; starts polling while anyone is subscribed. */
export function subscribeInstallStatus(listener: () => void, base?: string): () => void {
  if (base !== undefined) setInstallStatusApiBase(base);
  listeners.add(listener);
  ensurePolling();
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) stopPolling();
  };
}

/**
 * Project a raw install-status payload through the ONE liveness authority. Pure + clock-injectable so
 * it is unit-testable without a real poll (the FE analogue of the worker `PolledStateLiveness` law).
 */
export function projectInstallStatus(
  raw: {
    state?: string;
    message?: string;
    updatedAtEpochMs?: number;
    packages?: AiInstallStatus['packages'];
  },
  now: number = Date.now(),
): InstallLiveStatus {
  const updatedAtEpochMs = raw.updatedAtEpochMs ?? 0;
  const state = raw.state ?? 'idle';
  return {
    state,
    // Tempdoc 840 Phase 5 (U5) — the backend words this message with the download's TARGET PATH
    // ("Downloading onnx/gte-multilingual-base/model.onnx..."), because the scheduler's item id is
    // the path. This is the one place that message reaches a user-facing surface, so the friendly
    // label is applied here rather than at the render site.
    message: friendlyInstallMessage(raw.message, raw.packages),
    updatedAtEpochMs,
    stalled: state === 'running' && !isAiInstallLive(updatedAtEpochMs, now),
  };
}

export function installStatusUrl(base: string): string {
  return `${(base || '').replace(/\/$/, '')}/api/ai/install/status`;
}

async function poll(): Promise<void> {
  try {
    const r = await authorizedFetch(installStatusUrl(apiBase));
    if (!r.ok) return;
    const raw = (await r.json()) as {
      state?: string;
      message?: string;
      updatedAtEpochMs?: number;
      packages?: AiInstallStatus['packages'];
    };
    current = projectInstallStatus(raw);
    for (const l of listeners) l();
  } catch {
    // Transient (offline / proxy hiccup) — keep last-known; the next tick re-derives staleness.
  }
}

function ensurePolling(): void {
  if (timer !== null) return;
  void poll();
  timer = setInterval(() => void poll(), POLL_INTERVAL_MS);
}

function stopPolling(): void {
  if (timer !== null) {
    clearInterval(timer);
    timer = null;
  }
}
