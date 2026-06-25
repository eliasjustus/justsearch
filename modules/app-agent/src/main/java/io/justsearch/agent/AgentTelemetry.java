/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.AgentTags.AgentBudgetEdgeTags;
import io.justsearch.agent.AgentTags.AgentErrorTags;
import io.justsearch.agent.AgentTags.AgentRetryExhaustedTags;
import io.justsearch.agent.AgentTags.AgentRetryTags;
import io.justsearch.agent.AgentTags.SessionEndedTags;
import io.justsearch.agent.AgentTags.ToolCallTags;
import io.justsearch.agent.AgentTags.ToolFailureTags;
import io.justsearch.agent.GenAiTags.GenAiOperationTags;
import io.justsearch.agent.GenAiTags.GenAiTokenUsageTags;
import io.justsearch.agent.api.AgentErrorClass;
import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.telemetry.catalog.EmptyTags;

/**
 * Low-cardinality agent telemetry counters for errors and retry behavior. Tempdoc 417 Phase 2d:
 * thin façade over typed {@link AgentMetricCatalog} and {@link GenAiMetricCatalog}. Bridge
 * holders use {@link #noop()} when no {@code LocalTelemetry} is available; the catalogs
 * internally use {@link io.justsearch.telemetry.catalog.NoopMetricRegistry} so emit calls are
 * silent no-ops without any null-check ceremony.
 */
final class AgentTelemetry {
  private final AgentMetricCatalog agentCatalog;
  private final GenAiMetricCatalog genAiCatalog;

  AgentTelemetry(AgentMetricCatalog agentCatalog, GenAiMetricCatalog genAiCatalog) {
    this.agentCatalog = agentCatalog;
    this.genAiCatalog = genAiCatalog;
  }

  /** Cached no-op singleton. F6: avoid constructing fresh catalogs per call. */
  private static final AgentTelemetry NOOP =
      new AgentTelemetry(AgentMetricCatalog.noop(), GenAiMetricCatalog.noop());

  static AgentTelemetry noop() {
    return NOOP;
  }

  void recordError(AgentErrorCode code, AgentErrorClass errorClass) {
    if (code == null || errorClass == null) {
      return;
    }
    agentCatalog.errorTotal.increment(new AgentErrorTags(code, errorClass));
  }

  void recordRetry(AgentErrorCode code, int retryAttempt) {
    if (code == null) {
      return;
    }
    agentCatalog.retryTotal.increment(new AgentRetryTags(code, String.valueOf(retryAttempt)));
  }

  void recordLoopBlocked() {
    agentCatalog.loopBlockedTotal.increment(EmptyTags.INSTANCE);
  }

  /**
   * Tempdoc 585 §D Phase 1 (A1): count one emission of {@code eventType} (the wire event name from
   * {@code AgentEventPayloads.name}). Called once per event at the run's single publish chokepoint.
   */
  void recordEventEmitted(String eventType) {
    if (eventType == null) {
      return;
    }
    agentCatalog.eventEmitTotal.increment(new AgentTags.AgentEventTypeTags(eventType));
  }

  void recordBudgetEdgeFinalize(boolean success) {
    agentCatalog.budgetEdgeFinalizeTotal.increment(new AgentBudgetEdgeTags(success));
  }

  void recordRetryExhausted(AgentErrorCode code) {
    if (code == null) {
      return;
    }
    agentCatalog.retryExhaustedTotal.increment(new AgentRetryExhaustedTags(code));
  }

  /** Records LLM call duration following gen_ai.client.operation.duration convention. */
  void recordLlmDuration(long durationMs, String operationName) {
    if (operationName == null) {
      return;
    }
    genAiCatalog.operationDuration.record(durationMs, new GenAiOperationTags(operationName));
  }

  /** Records token usage following gen_ai.client.token.usage convention. */
  void recordTokenUsage(int tokens, String tokenType) {
    if (tokenType == null || tokens <= 0) {
      return;
    }
    genAiCatalog.tokenUsage.record(tokens, new GenAiTokenUsageTags(tokenType));
  }

  // ---------------------------------------------------------------------------
  // Tempdoc 415: agent.session.* lifecycle emissions
  // ---------------------------------------------------------------------------

  /** Increments {@code agent.session.start_total} once per session creation. */
  void recordSessionStart() {
    agentCatalog.sessionStartTotal.increment(EmptyTags.INSTANCE);
  }

  /**
   * Emits the session-end family in one call. Centralized in {@code AgentLoopService}'s
   * {@code finally{}} block — fires once per session regardless of which return path was taken.
   * No-op if {@code disposition} is null (defensive — the loop always sets it via
   * {@code AgentSession.markTerminated}).
   */
  void recordSessionEnd(
      TerminalDisposition disposition,
      AgentErrorCode errorCode,
      CancelTrigger cancelTrigger,
      long durationMs,
      int contextSizeBytes,
      int iterations,
      int toolCalls) {
    if (disposition == null) {
      return;
    }
    agentCatalog.sessionTerminateTotal.increment(
        new SessionEndedTags(disposition, errorCode, cancelTrigger));
    agentCatalog.sessionDurationMs.record(durationMs, EmptyTags.INSTANCE);
    agentCatalog.sessionContextSizeBytesAtEnd.record(contextSizeBytes, EmptyTags.INSTANCE);
    agentCatalog.sessionIterationsAtEnd.record(iterations, EmptyTags.INSTANCE);
    agentCatalog.sessionToolCallsAtEnd.record(toolCalls, EmptyTags.INSTANCE);
  }

  /**
   * Increments {@code agent.session.tool_call_total} once per logical tool call. Emitted at the
   * post-resolve point in {@code AgentLoopService}; handoff calls do not enter this surface.
   */
  void recordToolCall(String toolName) {
    if (toolName == null || toolName.isBlank()) {
      return;
    }
    agentCatalog.sessionToolCallTotal.increment(new ToolCallTags(toolName));
  }

  /**
   * Increments {@code agent.session.tool_failure_total} when a tool execution returns
   * {@code !success()} after policy retries. {@code tool_name} only — per-call retry-class
   * signal is already covered by {@code agent.retry.total{error_code=TOOL_TRANSIENT_READ_ONLY}}.
   */
  void recordToolFailure(String toolName) {
    if (toolName == null || toolName.isBlank()) {
      return;
    }
    agentCatalog.sessionToolFailureTotal.increment(new ToolFailureTags(toolName));
  }
}
