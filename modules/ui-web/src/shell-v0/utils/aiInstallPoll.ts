// SPDX-License-Identifier: Apache-2.0
/**
 * aiInstallPoll — shared, always-on, self-healing poller for the AI engine's install/runtime/pack
 * status (tempdoc 663 Stage 3).
 *
 * Before this module, `BrainSurface` fetched `/api/ai/install/status`, `/api/ai/runtime/status`, and
 * `/api/ai/packs/status` exactly ONCE on mount (`refreshAll()`), with narrower interval pollers
 * (`pollInstall`/`pollPack`/`pollRuntime`) that only self-armed AFTER a prior fetch had already
 * succeeded with `state:'running'`. A failed (or merely slow) first fetch left the corresponding
 * field permanently `null`, with no retry — `deriveAiState()` then had no escape from the calm
 * "Connecting…" state, live-reproduced as an indefinite stuck panel (tempdoc 663 §O).
 *
 * This module fixes that structurally, mirroring `inferencePoll.ts`'s proven shape: ONE shared,
 * always-on poller (first fetch is eager, then every `INTERVAL_MS`), fanning out to every subscriber.
 * A failed tick retains the last-known-good value per field (never regresses a known field to
 * `null`) and simply tries again on the next tick — "stuck forever" becomes structurally impossible.
 */

export interface InstallStatus {
  state: string;
  phase: string;
  installedFully?: boolean;
  message?: string;
  errorCode?: string;
  lastError?: string;
  // `packageId`/`label`/`tier` are the real wire fields (backend `PackageStatus`); the legacy `id`
  // is kept optional because older callers referenced it (it was never populated — tempdoc 657).
  packages?: Array<{
    packageId?: string;
    label?: string;
    tier?: string;
    id?: string;
    state?: string;
    skipReason?: string;
    bytesDownloaded?: number;
    bytesTotal?: number;
  }>;
  downloadedBytes?: number;
  totalBytes?: number;
  startedAtEpochMs?: number;
  updatedAtEpochMs?: number;
  cancelRequested?: boolean;
}

/**
 * Side-effect-free per-tier weight preview (tempdoc 657), from `GET /api/ai/install/plan-preview`.
 * Drives the honest first-run download breakdown before the user commits.
 */
export interface InstallPlanPreview {
  intent?: string;
  downloadProfile?: string;
  totalDownloadBytes?: number;
  tiers?: Array<{
    tier?: string;
    label?: string;
    includedByIntent?: boolean;
    totalBytes?: number;
    downloadBytes?: number;
  }>;
}

export interface AiRuntimeStatus {
  activation?: {
    state?: string;
    phase?: string;
    message?: string;
    activeVariantId?: string | null;
  };
  variants?: Array<{
    id: string;
    label?: string;
    description?: string;
    requiredVramBytes?: number;
    available?: boolean;
    reason?: string;
  }>;
  onnxFeatures?: Array<{
    feature: string;
    modelActive?: boolean;
    modelDescription?: string;
  }>;
}

export interface PackImportStatus {
  state: string;
  phase: string;
  message?: string;
  manifestSha256?: string;
  packageId?: string;
}

export interface AiInstallSnapshot {
  install: InstallStatus | null;
  runtime: AiRuntimeStatus | null;
  packs: PackImportStatus | null;
}

type Listener = (snapshot: AiInstallSnapshot) => void;

const listeners = new Set<Listener>();
let timer: number | null = null;
let apiBase = '';
let last: AiInstallSnapshot = { install: null, runtime: null, packs: null };

// Matches the original per-operation pollers' cadence (1s) — this poller is always-on rather than
// conditionally-armed, but that should not make download/activation progress feel less responsive
// than it did before.
const INTERVAL_MS = 1000;

async function fetchJson<T>(path: string): Promise<T | null> {
  try {
    const res = await fetch((apiBase || '') + path);
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    return null;
  }
}

async function fetchOnce(): Promise<void> {
  const [install, runtime, packs] = await Promise.all([
    fetchJson<InstallStatus>('/api/ai/install/status'),
    fetchJson<AiRuntimeStatus>('/api/ai/runtime/status'),
    fetchJson<PackImportStatus>('/api/ai/packs/status'),
  ]);
  // Retain last-known-good per field on a transient failure — a single bad tick must never
  // regress an already-known field back to null (that regression is what stranded BrainSurface
  // on "Connecting…" before this module existed).
  last = {
    install: install ?? last.install,
    runtime: runtime ?? last.runtime,
    packs: packs ?? last.packs,
  };
  for (const l of listeners) l(last);
}

function ensureRunning(): void {
  if (timer !== null) return;
  void fetchOnce();
  timer = window.setInterval(() => void fetchOnce(), INTERVAL_MS);
}

function stop(): void {
  if (timer !== null) {
    window.clearInterval(timer);
    timer = null;
  }
  last = { install: null, runtime: null, packs: null };
}

/**
 * Subscribe to the install/runtime/pack snapshot. Returns an unsubscribe function. The first call
 * after `setAiInstallApiBase()` starts the poller; the last unsubscribe stops it.
 */
export function subscribeAiInstall(listener: Listener): () => void {
  listeners.add(listener);
  listener(last);
  ensureRunning();
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) stop();
  };
}

/** Set the API base for the shared poller. Idempotent. */
export function setAiInstallApiBase(base: string): void {
  if (apiBase !== base) {
    apiBase = base;
    if (timer !== null) {
      stop();
      ensureRunning();
    }
  }
}

/** Test-only reset. */
export function __resetAiInstallPollForTest(): void {
  stop();
  listeners.clear();
  apiBase = '';
}
