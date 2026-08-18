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
 * resume/attach streaming paths — it owns the run-observer eviction contract and delegates the
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
 * <p>What stays here is the run-observer eviction seam ({@link #writeOrEvict}/{@link #evictIfGone}/
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
   * writes that are NOT run observers (so they must not evict on disconnect).
   */
  void writeEvent(Context ctx, String event, Map<String, ?> payload) {
    sseWriter.writeEvent(ctx, event, payload);
  }

  /**
   * Translate an {@link AgentEvent} via the ONE canonical {@link AgentEventSseTranslator} and write
   * it AS A RUN OBSERVER (see {@link #writeOrEvict}). The tool index feeds the translator's
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
   * Tempdoc 577 §2.14 Root I (#13) — write an ALREADY-PROJECTED run frame to the socket. The
   * journal carries the wire {@code (name, payload)} pair (tempdoc 834 §1.3.2), so an attaching
   * observer needs no translation at all: the projection happened once, at publish time, through
   * the one payload authority.
   */
  void writeWireFrame(Context ctx, io.justsearch.agent.api.RunObservation.WireFrame frame) {
    writeOrEvict(ctx, frame.name(), frame.payload());
  }

  /**
   * Tempdoc 577 §2.14 Root I (#13) — write to the SSE socket AS A RUN OBSERVER. A disconnect
   * ({@link SseWriter#writeEvent} returns {@code false}) THROWS, so the observer is evicted and
   * {@code observerCount()} drops. That eviction is the precondition the posture-graded
   * zero-observer park depends on: without it a dead socket lingers in the observer set and a Watch
   * run proceeds UNWATCHED (the safety goal unmet).
   *
   * <p><strong>Still load-bearing after tempdoc 834's hub deletion, and deliberately kept.</strong>
   * §7-S3b's sweep table calls this seam "obsolete once {@code onClose} owns disconnect" — true for
   * the run-stream family, which runs on a MANAGED {@code SseClient}. It is NOT true for the raw
   * {@code Context}-based attach routes below, which have no {@code onClose}: for those, a failed
   * write is the ONLY disconnect signal, and deleting the throw would silently make their
   * observerCount permanently non-zero. Retiring the seam belongs with retiring the raw routes.
   *
   * <p>Safe because every caller is a run observer, and the substrate's fan-out evicts on the
   * throw rather than propagating it — the loop is never aborted; the V3 root cause stays fixed.
   * NOT used for the terminal error writes, which are not observers.
   */
  void writeOrEvict(Context ctx, String eventType, Map<String, ?> payload) {
    // Tempdoc 585 §D Phase 2 (B1) stamped the SSE id: from the event's monotonic trace span so a
    // reconnecting client could echo it back as Last-Event-ID.
    //
    // Tempdoc 834 §1.6 RETIRED that resume story: probe P7 found no producer of the header (the FE
    // sends only a content type and the session token), the attach handler no longer parses it, and
    // run streams do not emit id: at all — one resume input (?sinceSeq=), one grammar. What remains
    // here is an id: line on the LEGACY raw-Context routes with nothing reading it. Left in place
    // rather than changed, because it is part of those routes' existing wire and they are retired
    // as a unit in S5; labelled so it is not mistaken for a live resume channel.
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
   * The run-observer eviction decision, factored out as a pure seam (testable without a Javalin
   * {@link Context}): ONLY a {@link SseWriter.SseWriteOutcome#CLIENT_GONE} (the socket closed)
   * THROWS, so the run channel's evict-on-throw fan-out drops the observer. A {@link
   * SseWriter.SseWriteOutcome#SERIALIZATION_FAILED} is NOT a disconnect — the bad event is skipped
   * but the observer is KEPT, so a non-serializable payload cannot kill a live stream or re-poison
   * every reattach (it sits in the replay ring).
   */
  static void evictIfGone(SseWriter.SseWriteOutcome outcome) {
    if (outcome == SseWriter.SseWriteOutcome.CLIENT_GONE) {
      throw new SseObserverGoneException();
    }
  }

  /**
   * Thrown by a run-observer write when the SSE client has disconnected, so the run channel's
   * fan-out evicts the observer (tempdoc 577 §2.14 Root I). Unchecked: it must propagate through
   * the loop's publish into the substrate's evict-on-throw catch.
   */
  static final class SseObserverGoneException extends RuntimeException {
    SseObserverGoneException() {
      super("SSE observer disconnected");
    }
  }
}
