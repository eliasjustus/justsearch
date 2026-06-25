/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentEventPayloads;
import io.justsearch.agent.api.conversation.SseEvent;
import java.util.Map;

/**
 * The wire {@link AgentEvent} → {@link SseEvent} translator (tempdoc 585 followup / §D Phase 0).
 *
 * <p>The base name+payload mapping lives in {@link AgentEventPayloads} (app-agent-api), the ONE
 * authority shared with the persistence path ({@code AgentRunStore}) so wire, persistence, and the
 * generated FE handler types cannot drift. This translator is the thin wire adapter: it delegates the
 * payload to {@link AgentEventPayloads#base} and adds the single wire-only overlay — the
 * {@code tool_batch_proposed} gate-prediction projection, which needs {@link
 * io.justsearch.app.services.intent.IntentGateEvaluator} / {@link ProposedBatchProjection}
 * (app-services-only). Both the live run path ({@code ToolIteratingShapeRunner}) and the resume/attach
 * path ({@code AgentSseWriter}) call this, so the wire vocabulary stays single-authority; the
 * conformance tests ({@code AgentEventPayloadConformanceTest}, {@code AgentEventSchemaConformanceTest})
 * pin it against the declared {@code AgentRunShape.EVENT_SCHEMA}.
 */
public final class AgentEventSseTranslator {
  private AgentEventSseTranslator() {}

  /**
   * Translate an {@link AgentEvent} to an {@link SseEvent} (name + payload, trace-decorated).
   * {@code evaluator} (nullable) and {@code opsByToolName} feed the {@code tool_batch_proposed}
   * gate-prediction projection; everything else ignores them.
   */
  public static SseEvent translate(
      AgentEvent event,
      io.justsearch.app.services.intent.IntentGateEvaluator evaluator,
      Map<String, io.justsearch.agent.api.registry.Operation> opsByToolName) {
    Map<String, Object> payload = buildPayload(event, evaluator, opsByToolName);
    payload = AgentEventPayloads.withTrace(payload, event.trace());
    return new SseEvent(AgentEventPayloads.name(event), payload);
  }

  /**
   * The wire payload = the shared {@link AgentEventPayloads#base} with the ONE wire-only overlay: the
   * {@code tool_batch_proposed} gate-prediction (the persistence-grade base carries only id+toolName;
   * the wire annotates each call with risk + the predicted gate via the shared
   * {@link ProposedBatchProjection}).
   */
  private static Map<String, Object> buildPayload(
      AgentEvent event,
      io.justsearch.app.services.intent.IntentGateEvaluator evaluator,
      Map<String, io.justsearch.agent.api.registry.Operation> opsByToolName) {
    if (event instanceof AgentEvent.ToolBatchProposed e) {
      return Map.of("calls", ProposedBatchProjection.project(e.calls(), opsByToolName, evaluator));
    }
    return AgentEventPayloads.base(event);
  }
}
