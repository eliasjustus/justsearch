// SPDX-License-Identifier: Apache-2.0
/**
 * Live-channel runtime peak signal — tempdoc 662 Design §D2 / Reach §"declare claimants
 * statically; measure the peak at runtime".
 *
 * `governance/live-channels.v1.json` + `check-live-channels.mjs` catch DRIFT (a new opener
 * added without declaring it, or the declared always-on count exceeding the budget) — but a
 * static declaration count cannot see the runtime CONCURRENCY peak for a resource consumed
 * dynamically (lazy streams + transient fetches + an in-flight AI generation stack at runtime
 * in ways no static count models). This module is the narrow runtime half that pairs with the
 * static register: a module-global high-water-mark of currently-open long-lived origin
 * connections, bumped at the universal seams every consumer funnels through.
 *
 * Instrumented seams (deliberately narrow — see the honest-scope note below):
 *  - `streaming/EnvelopeStream.ts` — the universal SSE transport EVERY EventSource-based
 *    channel in the shell routes through (the multiplexed connection, the pool, and every
 *    direct/fallback/lazy stream) — bumped at the exact instant a physical socket opens
 *    (`this.factory(url)` in `start()`) and closes (`es.close()` in `detachSource()`), not at
 *    the logical `start()`/`stop()` call (which can be a no-op if already started/stopped, or
 *    fire once per reconnect without a true close — see EnvelopeStream.ts for the precise
 *    instrumentation points).
 *  - `api/streams.ts` `consumeShapeStream` — the one shared chat/agent/summarize generation
 *    entry point (UnifiedChatView, SummarizeView, AgentSessionController, NavigateView, and
 *    the plugin-host AI bridge all route through it).
 *
 * HONEST SCOPE: a few `governance/live-channels.v1.json`-declared fetch-stream sites that
 * read a response body directly (`HealthSurface.ts`, `InspectorPane.ts`,
 * `conversationListStore.ts`, `plugin-api/capabilities/ai.ts`) do NOT share one substrate
 * function the way `consumeShapeStream` does, so they are NOT instrumented here — matching
 * this tempdoc's own register/gate honest-limit precedent (import-visible coverage, not
 * exhaustive). The peak this module reports is therefore a LOWER BOUND on true concurrency,
 * not an exact count — sufficient to ground the budget number in measurement and catch a
 * gross regression, not to certify precision down to the last fetch.
 */

let openCount = 0;
let peakCount = 0;

/** Call at the instant a physical long-lived connection actually opens. */
export function bumpChannelOpened(): void {
  openCount += 1;
  if (openCount > peakCount) {
    peakCount = openCount;
  }
}

/** Call at the instant a physical long-lived connection actually closes. Floors at 0 so a
 * mismatched bump pair (a bug elsewhere) can't drive the count negative. */
export function bumpChannelClosed(): void {
  openCount = Math.max(0, openCount - 1);
}

/** The current number of open long-lived connections this module is aware of. */
export function getCurrentOpenChannelCount(): number {
  return openCount;
}

/** The high-water-mark since the last reset (or process start). */
export function getPeakOpenChannelCount(): number {
  return peakCount;
}

/** Test-only: reset both counters to zero. */
export function __resetLiveChannelBudgetForTest(): void {
  openCount = 0;
  peakCount = 0;
}
