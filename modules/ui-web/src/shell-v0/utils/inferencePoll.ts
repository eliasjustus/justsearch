// SPDX-License-Identifier: Apache-2.0
/**
 * inferencePoll — shared `/api/inference/status` polling primitive
 * (slice 460). Multiple Lit consumers (Health surface, IndexingOverlay
 * host, StatusDeck) subscribe to a single shared poller; the poller
 * makes one fetch per interval and fans out to all subscribers.
 *
 * Lifecycle: first subscriber starts the poller; last unsubscriber
 * stops it. Initial fetch is eager (no wait for first interval tick).
 */

export interface InferenceSnapshot {
  mode?: string;
  available?: boolean;
  starting?: boolean;
  embeddingQueueSize?: number;
  vduQueueSize?: number;
  llmContextTokens?: number | null;
  configuredContextTokens?: number | null;
  tier?: string | null;
  activeModelId?: string | null;
  // Tempdoc 586 §3 dedup — these are already on the /api/inference/status wire
  // (Tempdoc 518 Appendix F W3.3 / W3.1); `fetchOnce` casts the raw JSON, so
  // declaring them here just exposes already-present values to subscribers
  // (e.g. BrainSurface, which previously ran its own second poll for them).
  /** Monotonic generation counter, increments per transition. */
  generation?: number;
  /** Ms duration of the most recent successful startup; -1 if none. */
  lastStartupDurationMs?: number;
  gpu?: {
    cudaAvailable?: boolean;
    totalVramBytes?: number | null;
    vramDescription?: string;
  } | null;
}

type Listener = (snapshot: InferenceSnapshot | null) => void;

const listeners = new Set<Listener>();
let timer: number | null = null;
let lastSnapshot: InferenceSnapshot | null = null;
let apiBase = '';

const INTERVAL_MS = 5000;

async function fetchOnce(): Promise<void> {
  try {
    const res = await fetch((apiBase || '') + '/api/inference/status');
    if (!res.ok) {
      for (const l of listeners) l(null);
      return;
    }
    const data = (await res.json()) as InferenceSnapshot;
    lastSnapshot = data;
    for (const l of listeners) l(data);
  } catch {
    for (const l of listeners) l(null);
  }
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
  lastSnapshot = null;
}

/**
 * Subscribe to inference status. Returns an unsubscribe function.
 * The first call after `setApiBase()` starts the poller; the last
 * unsubscribe stops it.
 */
export function subscribeInference(listener: Listener): () => void {
  listeners.add(listener);
  if (lastSnapshot !== null) listener(lastSnapshot);
  ensureRunning();
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) stop();
  };
}

/** Set the API base for the shared poller. Idempotent. */
export function setInferenceApiBase(base: string): void {
  if (apiBase !== base) {
    apiBase = base;
    // If the poller is running, restart so it picks up the new base
    // (rare; mostly a one-time startup call from chrome boot).
    if (timer !== null) {
      stop();
      ensureRunning();
    }
  }
}

/** Test-only reset. */
export function __resetInferencePollForTest(): void {
  stop();
  listeners.clear();
  apiBase = '';
}
