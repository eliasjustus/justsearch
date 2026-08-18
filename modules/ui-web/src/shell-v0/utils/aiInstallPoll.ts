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

import { authorizedFetch } from '../api/authorizedFetch.js';
import type { AiInstallStatus } from '../../api/generated/schema-types/ai-install-status.js';

/**
 * The `GET /api/ai/install/status` wire shape.
 *
 * Tempdoc 840 Phase 4: this used to be a hand-written mirror of
 * `modules/app-api/src/main/java/io/justsearch/app/api/AiInstallStatus.java`, and
 * `api/domains/packs.ts` held a SECOND, older mirror that still modelled the retired v1 `assets[]`.
 * Three hand-maintained copies of one shape, with nothing keeping them in sync — so the staged
 * install's `stages`/`readyCapabilities` reached neither of them. The Java DTO is now projected to
 * `SSOT/schemas/ai-install-status.v1.json` and generated into `schema-types/ai-install-status.ts`
 * (type + Zod), which `check-wire-schema-types-regen` fails the build on drifting from.
 *
 * `InstallStatus` stays as the name this module's subscribers already import; it is an alias, not a
 * fork — do not add fields here.
 */
export type InstallStatus = AiInstallStatus;

/**
 * Side-effect-free per-tier weight preview (tempdoc 657), from `GET /api/ai/install/plan-preview`.
 * Drives the honest first-run download breakdown before the user commits.
 */
export interface InstallPlanPreview {
  intent?: string;
  downloadProfile?: string;
  /** Bytes the download will actually transfer — complete files AND staged `.partial` bytes excluded. */
  totalDownloadBytes?: number;
  /** Of the planned downloads, bytes already staged on disk that a resume keeps. */
  resumableBytes?: number;
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
  /**
   * Per-ONNX-feature status from `AiRuntimeStatusResponse.OnnxFeatureStatus`.
   *
   * The first fields are the INTENT axis (what the Head configured, what the Worker discovered);
   * `executionProvider`/`gpuFallback`/`fallbackReason` are the OBSERVED axis added by tempdoc 805
   * G.3 — round 11 shipped `status:'active'`, `modelActive:true` for sessions that had silently
   * fallen back from CUDA to CPU, and no field could say so.
   *
   * Field names were `feature`/`modelDescription` here before 805; neither has ever existed on the
   * wire (the record emits `id`/`label`), so the Advanced feature rows rendered a blank name.
   */
  onnxFeatures?: Array<{
    id?: string;
    label?: string;
    status?: string;
    reason?: string;
    modelPath?: string | null;
    modelActive?: boolean;
    /** Observed: `'cuda' | 'cpu' | 'none' | 'unknown'` — what the ORT session actually runs on. */
    executionProvider?: string;
    /** Observed: GPU was configured but the session runs on CPU. */
    gpuFallback?: boolean;
    /** Observed: concrete reason for the fallback (probe failure, missing CUDA natives). */
    fallbackReason?: string | null;
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
    const res = await authorizedFetch((apiBase || '') + path);
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
