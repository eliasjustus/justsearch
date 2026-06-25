/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

/**
 * Coarse-grained reason a session run ended. Orthogonal to {@link
 * io.justsearch.agent.api.AgentErrorCode}: when {@link #ERRORED}, the error code carries the
 * specific failure mode; otherwise the disposition alone describes the outcome.
 *
 * <p>Tempdoc 415 — observed in {@code agent.session.terminate_total} as the {@code disposition}
 * tag value, and persisted in {@code AgentRunStore} {@code meta.json} as part of {@code
 * terminationReason}.
 */
enum TerminalDisposition {
  /** Session produced a final text response. */
  COMPLETED,
  /** Session ran to {@code request.maxIterations()} without producing a final response. */
  MAX_ITERATIONS,
  /** Session reached the budget edge and the graceful finalize attempt produced text. */
  BUDGET_EDGE_FINALIZE,
  /** Session ended due to an error; the paired {@code AgentErrorCode} carries the cause. */
  ERRORED,
  /** Session ended via cancellation; the paired {@code CancelTrigger} carries the cause. */
  CANCELLED
}
