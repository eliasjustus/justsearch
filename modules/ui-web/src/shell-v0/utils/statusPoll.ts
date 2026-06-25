// SPDX-License-Identifier: Apache-2.0
/**
 * statusPoll — shared /api/status pub-sub poller (slice 461).
 * Mirrors the inferencePoll pattern. Single-fetch fan-out.
 *
 * Tempdoc 557 §2.B (tier-1 collapse): the snapshot type IS the generated wire
 * authority `StatusResponse` — which carries the full tri-state `readiness`
 * (per-component `state` + `stale`) and `composites`, plus every leaf the
 * surfaces read. The former hand-written lossy interface (which silently
 * stripped readiness/composites/stale) is deleted; `StatusSnapshot` is kept
 * only as a name-alias so the poller's consumers stay stable while the single
 * authority is the generated type.
 */
import type { StatusResponse } from '../../api/generated/index.js';
import { parseWireContract } from '../../api/schemas.js';
import { statusResponseSchema } from '../../api/generated/schema-types/status-response.js';

export type StatusSnapshot = StatusResponse;

type Listener = (snapshot: StatusSnapshot | null) => void;

const listeners = new Set<Listener>();
let timer: number | null = null;
let lastSnapshot: StatusSnapshot | null = null;
let apiBase = '';

const INTERVAL_MS = 10000;

async function fetchOnce(): Promise<void> {
  try {
    const res = await fetch((apiBase || '') + '/api/status');
    if (!res.ok) {
      for (const l of listeners) l(null);
      return;
    }
    // Tempdoc 564 Phase A (status collapse): validate the raw /api/status response against the
    // single generated wire contract (`statusResponseSchema`, record → JSON Schema → Zod) at the
    // parse boundary — the faithful, non-fail-open gate (`[WireContract]` on drift). `StatusSnapshot`
    // is now that same generated `StatusResponse` (the barrel re-exports schema-types), so the
    // validated value IS the consumer type — no cross-projection cast.
    const raw = await res.json();
    const data: StatusSnapshot = parseWireContract(statusResponseSchema, raw, 'GET /api/status');
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

export function subscribeStatus(listener: Listener): () => void {
  listeners.add(listener);
  if (lastSnapshot !== null) listener(lastSnapshot);
  ensureRunning();
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) stop();
  };
}

export function setStatusApiBase(base: string): void {
  if (apiBase !== base) {
    apiBase = base;
    if (timer !== null) {
      stop();
      ensureRunning();
    }
  }
}

export function __resetStatusPollForTest(): void {
  stop();
  listeners.clear();
  apiBase = '';
}
