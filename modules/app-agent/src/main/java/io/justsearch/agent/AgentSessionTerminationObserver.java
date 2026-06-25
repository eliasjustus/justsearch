/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

/**
 * Observer of agent-session terminal-disposition events. Implemented by health-event emitters in
 * downstream modules (e.g., {@code HeadHealthEventsEmitter} in {@code app-services}); called once
 * per session from {@link AgentLoopService}'s {@code finally{}} block, alongside the existing
 * {@code AgentTelemetry.recordSessionEnd} and {@code AgentRunStore.setTerminationReason} calls.
 *
 * <p>Per tempdoc 430 §A.10 Phase 7 (rev 3.9 §B.V.1): the contract uses {@code String} parameters
 * to avoid promoting {@link TerminalDisposition} / {@link CancelTrigger} (package-private) to the
 * public API surface. The String values match the existing wire-format enum-name serialization
 * exposed by {@code /api/agent/sessions} (e.g., {@code "COMPLETED"}, {@code "BUDGET_EDGE_FINALIZE"}).
 *
 * <p>{@code errorCode} and {@code cancelTrigger} are nullable: {@code errorCode} is non-null only
 * when {@code disposition.equals("ERRORED")}; {@code cancelTrigger} is non-null only when
 * {@code disposition.equals("CANCELLED")}. Implementers must omit these from any body payload when
 * they're null (per §B.V.5: attribute absent, not literal {@code "null"}).
 */
public interface AgentSessionTerminationObserver {

  /**
   * Called once per session at terminal disposition. Implementations should be fast and
   * non-blocking — the call sits in the loop's {@code finally{}} block.
   *
   * @param sessionId stable session identifier
   * @param disposition {@link TerminalDisposition#name()} (e.g., {@code "COMPLETED"})
   * @param errorCode {@code AgentErrorCode.name()} when {@code disposition.equals("ERRORED")};
   *     null otherwise
   * @param cancelTrigger {@link CancelTrigger#name()} when {@code disposition.equals("CANCELLED")};
   *     null otherwise
   * @param durationMs wall-clock duration since session creation
   * @param iterationsUsed number of agent-loop iterations executed
   * @param toolCallsExecuted number of tool calls executed during the session
   * @param contextSizeBytes UTF-8 byte size of the persisted message conversation
   */
  void onSessionTerminated(
      String sessionId,
      String disposition,
      String errorCode,
      String cancelTrigger,
      long durationMs,
      int iterationsUsed,
      int toolCallsExecuted,
      int contextSizeBytes);

  /** Cached no-op singleton. Used by {@code AgentLoopService}'s back-compat constructors. */
  AgentSessionTerminationObserver NOOP =
      (sid, d, ec, ct, dur, it, tc, cs) -> {
        /* no-op */
      };
}
