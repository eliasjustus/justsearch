/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.app.services.conversation.AgentEventSseTranslator;
import io.justsearch.app.services.conversation.ProposedBatchProjection;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Tempdoc 585 §B.5 (Hybrid C, the keystone): the agent capability's SSE write seam for the
 * resume/attach streaming paths — it owns the hub-observer eviction contract and delegates the
 * {@code AgentEvent → SSE-wire} translation to the ONE canonical {@link AgentEventSseTranslator}.
 *
 * <p>Tempdoc 585 followup (observation #354): the event-vocabulary switch used to live here
 * (copied from {@code AgentController.writeAgentEvent}), byte-for-byte parallel to
 * {@link AgentEventSseTranslator} (which the live {@code engine.run} path uses) — a documented,
 * recurring drift surface (the dispatch path once silently lost {@code AgentProgress.severity},
 * 577 Ext II). The duplicate switch was deleted; {@link #writeAgentEvent} now delegates to the single
 * translator, so the conformance tests ({@code AgentEventPayloadConformanceTest} /
 * {@code AgentEventSchemaConformanceTest}) cover this path too, and drift is impossible by construction.
 *
 * <p>What stays here is the hub-observer eviction seam ({@link #writeOrEvict}/{@link #evictIfGone}/
 * {@link SseObserverGoneException}, tempdoc 577 §2.14 Root I) — a {@link Context}-coupled concern that
 * belongs in the ui layer, not the translator. {@link #initSseHeaders}/{@link #writeEvent} are
 * passthroughs so a streaming controller holds only this one SSE collaborator.
 */
final class AgentSseWriter {
  private final SseWriter sseWriter;
  private final Supplier<AgentService> agentServiceSupplier;
  // Tempdoc 550 thesis III: the plan-preview READS the one shared IntentGateEvaluator so the
  // proposed-batch heads-up shows the SAME gate the dispatcher would enforce. Nullable.
  private final io.justsearch.app.services.intent.IntentGateEvaluator intentGateEvaluator;

  AgentSseWriter(
      SseWriter sseWriter,
      Supplier<AgentService> agentServiceSupplier,
      io.justsearch.app.services.intent.IntentGateEvaluator intentGateEvaluator) {
    this.sseWriter = sseWriter;
    this.agentServiceSupplier = agentServiceSupplier;
    this.intentGateEvaluator = intentGateEvaluator;
  }

  private AgentService agentService() {
    return agentServiceSupplier.get();
  }

  /** Configures standard SSE response headers (passthrough to {@link SseWriter}). */
  void initSseHeaders(Context ctx, String route) {
    sseWriter.initSseHeaders(ctx, route);
  }

  /**
   * Best-effort SSE event write (passthrough to {@link SseWriter}), used by the terminal error
   * writes that are NOT hub observers (so they must not evict on disconnect).
   */
  void writeEvent(Context ctx, String event, Map<String, ?> payload) {
    sseWriter.writeEvent(ctx, event, payload);
  }

  /**
   * Translate an {@link AgentEvent} via the ONE canonical {@link AgentEventSseTranslator} and write
   * it AS A HUB OBSERVER (see {@link #writeOrEvict}). The tool index feeds the translator's
   * {@code tool_batch_proposed} gate-prediction projection; it degrades to an empty index when the
   * engine is offline (availableOperations unavailable) — the same graceful behaviour the prior
   * inline {@code projectBatchCalls} had.
   */
  void writeAgentEvent(Context ctx, AgentEvent event) {
    Map<String, Operation> opsByToolName;
    try {
      opsByToolName = ProposedBatchProjection.indexByToolName(agentService().availableOperations());
    } catch (RuntimeException ignored) {
      opsByToolName = Map.of(); // availableOperations unavailable (engine offline) — degrade.
    }
    SseEvent sse = AgentEventSseTranslator.translate(event, intentGateEvaluator, opsByToolName);
    writeOrEvict(ctx, sse.name(), sse.payload());
  }

  /**
   * Tempdoc 577 §2.14 Root I (#13) — write to the SSE socket AS A HUB OBSERVER. A disconnect
   * ({@link SseWriter#writeEvent} returns {@code false}) THROWS, so the {@link
   * io.justsearch.agent RunEventHub}'s {@code deliver} catch evicts this observer and {@code
   * observerCount()} drops. That eviction is the precondition the posture-graded zero-observer
   * park depends on: without it a dead socket lingers in the subscriber set and a Watch run
   * proceeds UNWATCHED (the safety goal unmet). Safe because every caller is a hub observer
   * (the loop publishes through the hub, whose {@code deliver} swallows the throw — the loop is
   * never aborted; the V3 root cause stays fixed). NOT used for the terminal error writes, which
   * are not hub observers.
   */
  void writeOrEvict(Context ctx, String eventType, Map<String, ?> payload) {
    // Tempdoc 585 §D Phase 2 (B1) — stamp the SSE id: from the event's monotonic trace span. Every
    // agent event's payload carries trace.spanId (AgentEventPayloads.withTrace), so BOTH the live
    // run stream and the reattach stream emit a Last-Event-ID with no signature change to either
    // caller — the reconnecting client echoes it back and the hub replays only newer events.
    evictIfGone(sseWriter.writeResult(ctx, seqOfPayload(payload), eventType, payload));
  }

  /**
   * Tempdoc 585 §D Phase 2 (B1) — extract the monotonic event sequence from a translated payload's
   * {@code trace.spanId} ({@code span-NNNNNN}), the same value {@link
   * io.justsearch.agent.api.TraceContext#seq()} parses on the backend. Returns {@code null} when the
   * payload has no parseable span (an untraced/synthetic event), so the {@code id:} line is omitted.
   */
  private static Long seqOfPayload(Map<String, ?> payload) {
    if (payload == null || !(payload.get("trace") instanceof Map<?, ?> trace)) {
      return null;
    }
    if (!(trace.get("spanId") instanceof String spanId)) {
      return null;
    }
    int dash = spanId.lastIndexOf('-');
    if (dash < 0 || dash + 1 >= spanId.length()) {
      return null;
    }
    try {
      return Long.parseLong(spanId.substring(dash + 1));
    } catch (NumberFormatException notNumeric) {
      return null;
    }
  }

  /**
   * The hub-observer eviction decision, factored out as a pure seam (testable without a Javalin
   * {@link Context}): ONLY a {@link SseWriter.SseWriteOutcome#CLIENT_GONE} (the socket closed)
   * THROWS, so {@code RunEventHub.deliver} evicts the observer. A {@link
   * SseWriter.SseWriteOutcome#SERIALIZATION_FAILED} is NOT a disconnect — the bad event is skipped
   * but the observer is KEPT, so a non-serializable payload cannot kill a live stream or re-poison
   * every reattach (it sits in the hub's replay buffer).
   */
  static void evictIfGone(SseWriter.SseWriteOutcome outcome) {
    if (outcome == SseWriter.SseWriteOutcome.CLIENT_GONE) {
      throw new SseObserverGoneException();
    }
  }

  /**
   * Thrown by a hub-observer write when the SSE client has disconnected, so {@code
   * RunEventHub.deliver} evicts the observer (tempdoc 577 §2.14 Root I). Unchecked: it must
   * propagate through the loop's {@code publish} into the hub's fan-out catch.
   */
  static final class SseObserverGoneException extends RuntimeException {
    SseObserverGoneException() {
      super("SSE observer disconnected");
    }
  }
}
