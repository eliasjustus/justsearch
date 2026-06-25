// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 571 §4c (as-built per the §8 CI-3 finding) — the shared presentation-ORDERING primitive
 * for the two live event-stream surfaces: Logs (the 448 DiagnosticChannel tail) and the Activity
 * action-ledger (the 550 Outcome face).
 *
 * 571 §4c argued "unify the ENGINE, not the surface" for the two surfaces that merely share a render
 * genre (a live, newest-first chronological stream). The §8 de-risk (CI-3) found the engine is
 * ALREADY shared and the duplication is thin:
 *
 *   - the SSE stream-subscription substrate is the one `EnvelopeStream` primitive — BOTH surfaces
 *     compose it (`openActionLedgerStream` composes an `EnvelopeStream` of ledger-entry arrays; `LogSurface`
 *     uses `EnvelopeStream<DiagnosticChannelStrategyState>`). Nothing to extract there.
 *   - the bounded-projection concern is the shared pure `collapseBursts` (boundedProjection.ts).
 *   - the ONLY remaining duplicated concern is the newest-first ordering — extracted here.
 *
 * The surfaces stay correctly DISTINCT: Logs' filter chips / pause / virtualization and the
 * action-ledger's burst-collapse are surface-specific, and the two project DIFFERENT authorities, so
 * they must not be coupled beyond this ordering helper (the 571 single-authority thesis + AHA: only
 * unify what shares a reason to change — do not over-DRY two distinct authorities into one substrate).
 */

/**
 * Return a newest-first copy of an oldest-first (arrival-order) event list. Non-mutating — the input
 * is treated as readonly and a fresh array is returned, so callers can render newest-first without
 * disturbing the underlying arrival-order buffer.
 */
export function newestFirst<T>(rows: readonly T[]): T[] {
  return [...rows].reverse();
}
