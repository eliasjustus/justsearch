/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import java.util.function.Consumer;

/**
 * Top-level service interface for the agent capability.
 *
 * <p>Tempdoc 584 §B.4: the READ-TIME query/projection surface (operation history, session
 * snapshots, resume, thread/lifecycle/presence projections) is segregated into the narrow
 * {@link AgentRunQueries} super-interface so read-only consumers can depend on it alone; this
 * interface adds the loop + live-run control surface on top.
 */
public interface AgentService extends AgentRunQueries {

  /**
   * Start an agent session. Events are emitted to the consumer as the agent loop progresses. This
   * method blocks until the agent loop completes or is cancelled.
   */
  void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer);

  /**
   * Tempdoc 561 P-D — start an agent session marked as a BACKGROUND (non-interactive) run when
   * {@code background} is true: the safety gate becomes safe-by-default (no watcher → write/destructive
   * tool calls are rejected immediately, never silently auto-run). The default delegates to the
   * interactive {@link #runAgent(AgentRequest, Consumer)} so implementations without background support
   * compile unchanged; {@code AgentLoopService} overrides it to thread the flag onto the session.
   */
  default void runAgent(
      AgentRequest request, Consumer<AgentEvent> eventConsumer, boolean background) {
    runAgent(request, eventConsumer);
  }

  /** Approve a pending tool call within a session. */
  void approveToolCall(String sessionId, String callId);

  /** Reject a pending tool call within a session. */
  void rejectToolCall(String sessionId, String callId, String reason);

  /**
   * Tempdoc 565 §15.C enforcement — approve a pending tool call and report whether a gate with that
   * {@code callId} actually existed in the session (and was completed). The unified approval dispatch
   * ({@code POST /api/chat/approve}) uses the boolean to decide whether to fall through to the workflow
   * gate registry. Default returns {@code false} (no gate found) so unavailable / mock services and the
   * Null Object compile unchanged; {@code AgentLoopService} overrides it with the real lookup.
   */
  default boolean tryApproveToolCall(String sessionId, String callId) {
    return false;
  }

  /** Reject sibling of {@link #tryApproveToolCall} — see its contract. */
  default boolean tryRejectToolCall(String sessionId, String callId, String reason) {
    return false;
  }

  /**
   * Tempdoc 561 P-D — update the live autonomy dial ({@code "watch"|"assist"|"auto"}) for a running
   * session, so a mid-run dial change takes effect on the next gated tool call. The default is a
   * no-op so legacy {@code AgentService} implementations compile unchanged.
   */
  default void setSessionAutonomy(String sessionId, String autonomyLevelWire) {
    // no-op default
  }

  /**
   * Tempdoc 565 §30 — the DIRECTION authority's {@code interject}: queue a free-form human steering
   * directive for a running session (POST /api/chat/agent/steer), drained at the next step boundary
   * and folded into the next LLM call. Returns true when the session was found and the directive
   * queued; false (404) for an unknown/finished session. Default no-op returns false so legacy
   * implementations compile unchanged.
   */
  default boolean injectSteeringDirective(String sessionId, String text) {
    return false;
  }

  /**
   * Tempdoc 577 Ext III — the over-budget remedy: grant a running session additional tokens (POST
   * /api/chat/agent/budget). Returns true when the session was found and the budget raised; false
   * (404) for an unknown/finished session or a non-positive grant. Default no-op returns false so
   * legacy implementations compile unchanged.
   */
  default boolean raiseSessionBudget(String sessionId, int addTokens) {
    return false;
  }

  /**
   * Tempdoc 577 §2.12 Move 2 — resolve a HELD budget gate (POST /api/chat/agent/budget-decision).
   * {@code decision} ∈ {@code "finalize"} (synthesize from what the run has) | {@code "stop"}
   * (cancel at the gate). CONTINUE is not a value here — it arrives via
   * {@link #raiseSessionBudget} (raising the budget IS the continue decision). Returns true when a
   * gate was held and resolved; false (404) when the run is not parked. Default no-op so legacy
   * implementations compile unchanged.
   */
  default boolean resolveBudgetGate(String sessionId, String decision) {
    return false;
  }

  /**
   * Tempdoc 577 §2.14 Root II (#14) — resolve a HELD context-pressure gate (POST
   * /api/chat/agent/context-decision). {@code decision} ∈ {@code "continue"} (proceed with the large
   * prompt) | {@code "summarize"} (compact older turns, then resume) | {@code "stop"} (cancel at the
   * gate). Returns true when a gate was held and resolved; false (404) when the run is not parked.
   * Default no-op so legacy implementations compile unchanged.
   */
  default boolean resolveContextGate(String sessionId, String decision) {
    return false;
  }

  /**
   * Tempdoc 577 §2.14 Root I (#13) — ATTACH a new observer to a LIVE run (GET/POST
   * /api/chat/agent/{sessionId}/attach). The observer subscribes to the run's session-local event
   * hub: it REPLAYS the buffered events (the run's recent history) then receives ongoing events,
   * and this call BLOCKS (streaming to the observer) until the run terminates — exactly like the
   * initiating run stream. N observers can attach to one run; a dropped observer never affects the
   * run or the others. Returns true when a LIVE session was found and observed; false (404) when the
   * run is not live (terminal/evicted — the caller falls back to the persisted record).
   *
   * <p>Default returns false so legacy implementations compile unchanged.
   */
  default boolean attachToRun(String sessionId, Consumer<AgentEvent> eventConsumer) {
    return attachToRun(sessionId, Long.MIN_VALUE, eventConsumer);
  }

  /**
   * Tempdoc 585 §D Phase 2 (B1) — ATTACH, replaying only events newer than {@code fromSeq} (the SSE
   * {@code Last-Event-ID} the reattaching client last saw, per {@link TraceContext#seq()}). {@code
   * Long.MIN_VALUE} replays the whole buffered window (the {@link #attachToRun(String, Consumer)}
   * default). Default returns false so legacy implementations compile unchanged.
   */
  default boolean attachToRun(String sessionId, long fromSeq, Consumer<AgentEvent> eventConsumer) {
    return false;
  }

  /**
   * Tempdoc 508 §11.5 / §13.5 Phase B — deliver the result of a
   * frontend-executed virtual tool ({@code vop_*} prefix) back to
   * the agent loop. The FE listens for {@code tool_call_virtual}
   * SSE events, invokes the matching shell/plugin command, and
   * POSTs the captured success/output here via
   * {@code POST /api/chat/agent/tool-result}. Returns true when
   * the call was found and the agent's wait future resolved;
   * false when no pending call had that callId (404).
   *
   * <p>Default implementation returns false (no-op) so old
   * AgentService implementations without virtual-tool support
   * compile unchanged.
   */
  default boolean completeVirtualToolCall(
      String sessionId, String callId, boolean success, String output, String errorDetail) {
    return false;
  }

  /** Cancel an active agent session. */
  void cancelSession(String sessionId);

  // Tempdoc 584 §B.4: the READ-TIME query/projection surface (availableOperations, undoOperation,
  // operationHistory/Detail, lastSessionSnapshot, listSessions, sessionSnapshot, resume*,
  // sessionEvents, threadEvents, lifecycles, presenceSince) moved to the AgentRunQueries
  // super-interface this extends.

  /** Whether the agent capability is available (model loaded, tools registered). */
  boolean isAvailable();

  /**
   * Tempdoc 560 WS5 (the one window) — late-bind the streaming workflow-as-tool runner. Default
   * no-op so unavailable services and test mocks need not implement it; the live {@code
   * AgentLoopService} forwards it to its step runner so projected workflow tools stream through the
   * runner instead of the synchronous executor. Set by {@code LocalApiServer} once {@code
   * WorkflowShapeRunner} is constructed (it is wired later than the agent loop).
   */
  default void setWorkflowToolRunner(
      io.justsearch.agent.api.registry.WorkflowToolRunner runner) {
    // no-op by default
  }

  /**
   * Returns the number of currently-running agent sessions (tempdoc 415). Sources the
   * {@code agent.session.active_count} gauge and the {@code agentSessions.activeCount} field
   * on {@code /api/status}. Default implementation returns 0 — appropriate for the unavailable
   * service and for test mocks that don't track sessions.
   */
  default int activeSessionCount() {
    return 0;
  }

  /**
   * Null Object for environments where the agent capability is not configured (inference
   * disabled or unconfigured). Returns {@code false} from {@link #isAvailable()} and empty /
   * default values from the other interface defaults. Tempdoc 519 F2 (refined per §22): kept
   * as the Null Object pattern.
   */
  static AgentService unavailable() {
    return UnavailableAgentService.INSTANCE;
  }
}
