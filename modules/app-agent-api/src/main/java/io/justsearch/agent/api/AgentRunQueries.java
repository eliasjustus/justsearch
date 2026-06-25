/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import io.justsearch.agent.api.registry.OperationResult;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The agent capability's READ-TIME query/projection surface (tempdoc 584 §B.4): operation history,
 * persisted-session snapshots/lists, the resume bridge, and the unified-thread / lifecycle / presence
 * projections over the durable agent-run record.
 *
 * <p>Segregated out of {@link AgentService} (which now {@code extends} it) so a read-only consumer —
 * e.g. the interaction-thread controller — can depend on this narrow surface rather than the full
 * loop+control interface. Every method is a pure read; none mutate live agent-loop state (the resume
 * methods re-enter the loop through the implementation's own runner, not through this contract). This
 * is the §B.2 *breadth-axis* boundary: new read-projection features attach here, not on the
 * orchestrator.
 */
public interface AgentRunQueries {

  /**
   * List all available Operation entries (tempdoc 429 §E.4 substrate).
   *
   * <p>Per Phase 10 of tempdoc 429: replaces the legacy {@code availableTools()} method
   * which returned {@code List<ToolDefinition>}. The substrate's Operation type carries
   * the same data (id, presentation, parameter schema, risk policy, undo support) plus
   * provenance/executors metadata.
   */
  List<io.justsearch.agent.api.registry.Operation> availableOperations();

  /** Undo a previous tool execution by its execution ID. */
  default OperationResult undoOperation(String toolName, String executionId) {
    throw new UnsupportedOperationException("Undo not supported");
  }

  /** List recent file operation batches (newest first). */
  default List<Map<String, Object>> operationHistory(int limit) {
    return List.of();
  }

  /** Get full detail of a specific operation batch. Returns null if not found. */
  default Map<String, Object> operationDetail(String batchId) {
    return null;
  }

  /** Returns the most recent persisted agent session snapshot for resume/debug flows. */
  default Map<String, Object> lastSessionSnapshot() {
    return null;
  }

  /** Resume the most recent persisted session. */
  default void resumeLastSession(Consumer<AgentEvent> eventConsumer) {
    throw new UnsupportedOperationException("Resume not supported");
  }

  /**
   * List recent persisted session summaries (newest first). Tempdoc 415 follow-up (C20). Default
   * returns empty so the unavailable service and lightweight test mocks compile unchanged.
   */
  default List<Map<String, Object>> listSessions(int limit) {
    return List.of();
  }

  /**
   * Returns the full persisted snapshot for a specific session, or {@code null} if none exists.
   * Tempdoc 415 follow-up (C20). Mirrors {@link #lastSessionSnapshot()} but addressed by id.
   */
  default Map<String, Object> sessionSnapshot(String sessionId) {
    return null;
  }

  /**
   * Resume a specific persisted session by id. Tempdoc 415 follow-up (C20). Sibling of
   * {@link #resumeLastSession(Consumer)} — emits the same events and inherits the same
   * resume-state safety gate.
   */
  default void resumeSession(String sessionId, Consumer<AgentEvent> eventConsumer) {
    throw new UnsupportedOperationException("Resume not supported");
  }

  /**
   * Tempdoc 585 §D Phase 3 (C2) — time-travel FORK: branch a NEW run from a finished one, rewinding
   * to its last user turn and optionally editing that question ({@code editedMessage}; blank re-runs
   * the original). No resume-state gate — a fork is meaningful for a DONE/ERRORED run.
   */
  default void forkSession(
      String sessionId, String editedMessage, Consumer<AgentEvent> eventConsumer) {
    throw new UnsupportedOperationException("Fork not supported");
  }

  /** Returns the persisted event stream for a given session ID. */
  default List<Map<String, Object>> sessionEvents(String sessionId) {
    return List.of();
  }

  /**
   * Tempdoc 561 P-A/P-B (correction): the agent plane's contribution to the unified thread — the
   * thread events (user prompt, final responses, tool activity, errors, handoffs) of every agent run
   * belonging to {@code conversationId}, projected READ-TIME from {@code AgentRunStore} (no second
   * store). Default empty for the unavailable service + test mocks.
   */
  default List<io.justsearch.agent.api.interaction.InteractionEvent> threadEvents(
      String conversationId) {
    return List.of();
  }

  /**
   * Tempdoc 561 P-A / P-A2: the typed {@link io.justsearch.agent.api.lifecycle.AgentLifecycle} loop
   * objects (Session ⊃ Turn ⊃ Iteration + state + budget) for every agent run of {@code
   * conversationId}, projected from the durable {@code AgentRunStore} record. Default empty for the
   * unavailable service + test mocks.
   */
  default List<io.justsearch.agent.api.lifecycle.AgentLifecycle> lifecycles(String conversationId) {
    return List.of();
  }

  /**
   * Tempdoc 561 P-D2 — the presence axis / render-on-return inbox source: the typed lifecycles of
   * BACKGROUND (non-interactive) agent runs that completed strictly after {@code since}, i.e. "what
   * the agent did while you were away". Projected from the durable {@code AgentRunStore} record (the
   * runs the {@code BackgroundRunService} produced + stamped {@code background=true}); never a second
   * store. {@code since == null} returns all background runs. Default empty for the unavailable
   * service + test mocks.
   */
  default List<io.justsearch.agent.api.lifecycle.AgentLifecycle> presenceSince(
      java.time.Instant since) {
    return List.of();
  }
}
