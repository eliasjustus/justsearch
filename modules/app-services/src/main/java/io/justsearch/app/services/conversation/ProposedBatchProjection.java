/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.services.intent.IntentGateEvaluator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tempdoc 550 thesis III / F6 — the ONE projection of an agent's proposed tool batch.
 *
 * <p>Both the {@code /api/agent} SSE path ({@code AgentController}) and the AgentRun conversation
 * shape ({@code ToolIteratingShapeRunner}) emit {@code tool_batch_proposed}; this is the single
 * shared projector they both call, so the two surfaces cannot drift. Each call is annotated with
 * its risk and the gate the dispatcher WOULD apply — READ from the one shared
 * {@link IntentGateEvaluator} for transport {@code AGENT_LOOP}, never recomputed. Degrades to
 * id+toolName when the operation or the evaluator is unavailable.
 */
public final class ProposedBatchProjection {

  private ProposedBatchProjection() {}

  /** Index the agent's available operations by their model-visible wire (tool) name. */
  public static Map<String, Operation> indexByToolName(Iterable<Operation> ops) {
    Map<String, Operation> byName = new HashMap<>();
    if (ops != null) {
      for (Operation op : ops) {
        byName.put(OperationCatalog.toWireName(op.id()), op);
      }
    }
    return byName;
  }

  /** Project each proposed call to its wire row, annotated with risk + predicted gate. */
  public static List<Map<String, Object>> project(
      List<ToolCallRequest> calls,
      Map<String, Operation> opsByToolName,
      IntentGateEvaluator evaluator) {
    List<Map<String, Object>> out = new ArrayList<>(calls.size());
    for (ToolCallRequest c : calls) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("callId", c.id());
      m.put("toolName", c.toolName());
      Operation op = opsByToolName.get(c.toolName());
      if (op != null) {
        m.put("risk", op.policy().risk().name().toLowerCase(Locale.ROOT));
        if (evaluator != null) {
          m.put(
              "gateBehavior",
              evaluator.evaluate(op.policy().risk(), TransportTag.AGENT_LOOP).gateBehavior().name());
        }
      }
      out.add(m);
    }
    return out;
  }
}
