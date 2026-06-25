// SPDX-License-Identifier: Apache-2.0
/**
 * In-flight liveness authority — tempdoc 575 §15 (Pillar 3b, the single FE derivation).
 *
 * The ONE place that decides whether an in-flight record's owner is still live, from its
 * heartbeat freshness (`lastUpdatedMs`, refreshed by `JobQueue.heartbeatProcessing`). Every
 * projection that renders an in-flight record's RUNNING state must derive from this — so a
 * phantom "running without a live owner" cannot be silently re-implemented per-surface. The
 * `governance/inflight-liveness-projections.v1.json` register + `check-inflight-liveness` gate
 * enforce that (early-warning, the FE import-register ceiling per 565 §12.10).
 *
 * {@link IN_FLIGHT_STALE_MS} is the display freshness window; it is GENERATED from the register
 * (`observed-happening.v1.json` → `liveness-constants.ts`, tempdoc 575 §17 Face A) — the same source
 * the worker heartbeat + reaper constants derive from, so the two processes cannot drift apart.
 */

import { DISPLAY_STALE_MS } from '../../../api/generated/liveness-constants.js';

/**
 * Display freshness window (ms). An in-flight record is "live" only if its last heartbeat is
 * within this window — 3× the worker's 30s heartbeat with margin, so a healthy record (beaten at
 * batch phase boundaries + every 30s) never false-flaps to stale. Clock-skew is a non-issue: worker
 * + UI share one machine (the loopback desktop app), so `Date.now()` and the worker epoch agree.
 * Re-exported under its display name from the generated single source.
 */
export const IN_FLIGHT_STALE_MS = DISPLAY_STALE_MS;

/**
 * Resolve the freshness window. A dev-only `globalThis.__JF_STALE_MS__` override exists SOLELY to
 * exercise the stale path during live verification (set it tiny to watch a healthy record demote);
 * production always uses {@link IN_FLIGHT_STALE_MS}. The override read is gated on
 * `import.meta.env.DEV`, which Vite statically replaces (`true` in dev, `false` in prod) — so in a
 * production build the entire override branch is tree-shaken away and the stray global cannot leak
 * into prod behavior. The cast keeps tsc happy without pulling vite/client types into the shell-v0
 * tsconfig; direct property access (no intermediate variable) is required for the static replacement
 * to fire. Mirrors the dev-fixtures.ts gating pattern.
 */
export function staleWindowMs(): number {
  if ((import.meta as ImportMeta & { env?: { DEV?: boolean } }).env?.DEV) {
    const o = (globalThis as { __JF_STALE_MS__?: unknown }).__JF_STALE_MS__;
    if (typeof o === 'number' && o > 0) {
      return o;
    }
  }
  return IN_FLIGHT_STALE_MS;
}

/**
 * THE liveness derivation: is an in-flight record's owner still live? True iff its last heartbeat
 * ({@code lastUpdatedMs}, epoch ms) is within the freshness window — "in-flight derives from a
 * live owner, not stream membership" (tempdoc 575 §4.3b / §15). A projection renders RUNNING only
 * when this is true; otherwise the record is stale (the owner stopped beating) and must not assert
 * a phantom running badge.
 */
export function isInFlightLive(lastUpdatedMs: number, now: number = Date.now()): boolean {
  return now - lastUpdatedMs < staleWindowMs();
}
