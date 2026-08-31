/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SPI for projecting an {@link OperationCatalog} into the OpenAI function-calling tools
 * format consumed by the agent loop.
 *
 * <p>Per tempdoc 429 §E.8 + Phase 10: lives in {@code app-agent-api} so {@code AgentLoopService}
 * (in {@code app-agent}) can consume it without depending on {@code app-services}. The
 * concrete implementation ({@code AgentOperationEmitter}) lives in {@code app-services}
 * and is injected at boot time by {@code HeadAssembly}.
 *
 * <p>Replaces the legacy {@code ToolRegistry.toOpenAiToolsArray(...)} contract. Output
 * is byte-stable for a given catalog state per §C.G — the regression test
 * {@code AgentOperationEmitterRegressionTest} (Phase 11) asserts deep-equality against
 * the captured baseline after Jackson normalization.
 */
public interface AgentToolEmitter {

  /**
   * Tempdoc 876 §B.1 — THE authority on membership in the model's tool list. Returns the
   * {@link Operation} entries this catalog + selection actually puts in front of the model:
   * the full filter chain (target executor, audience allow-list, the caller's selection, and
   * the operation's availability expression evaluated against current state), and nothing else.
   *
   * <p>{@link #emit} is this method's WIRE PROJECTION — same membership, OpenAI-shaped, plus
   * the virtual-operation merge. Any other view of "what the model is offered" (the trust
   * panel's {@code GET /api/chat/agent/tools}, governance witnesses) must derive from THIS
   * method rather than re-deriving from raw catalog declarations; a second derivation is a
   * fork that will drift, which is precisely the defect 876 exists to close.
   *
   * @param catalog the operation catalog to filter
   * @param selectedNames wire names (or raw {@code OperationRef} values) the caller restricts the
   *     offering to; empty / null means "everything that survives the other filters"
   */
  List<Operation> offer(OperationCatalog catalog, Collection<String> selectedNames);

  /**
   * Project the catalog into the OpenAI function-calling tools array, optionally filtered
   * to a subset of operation ids (empty / null filter returns all AGENT-targeted entries).
   *
   * <p>Membership is {@link #offer}'s; this method only decides the wire shape (and merges
   * FE-published virtual tools, which have no {@link Operation} to project).
   */
  List<Map<String, Object>> emit(OperationCatalog catalog, Collection<String> selectedNames);

  /**
   * The wire names this emitter would offer for {@code (catalog, selectedNames)} — i.e. the
   * authority set the agent loop is allowed to dispatch (tempdoc 875 Move 3).
   *
   * <p><strong>Projection, not fork.</strong> The set is derived from {@link #emit} rather than
   * re-deriving the filter chain (executor tag, {@code Audience} allow-list, availability
   * expression, selection), because a second copy of that chain is precisely the drift that made
   * "offered" and "resolvable" disagree: the emitter withheld a tool and
   * {@code OperationCatalog.resolveByWireName} — which iterates the raw {@code definitions()} —
   * still handed it to the dispatcher. One authority ({@code emit}), one derived view.
   *
   * <p>Consequence for implementers: do not override this to compute the names some cheaper way.
   * If it can disagree with {@code emit}, the boundary is back to being two lists.
   *
   * <p>Malformed entries (no {@code function} object, no {@code name}) are skipped — an entry the
   * model could not name is not authority for anything.
   *
   * @param catalog the catalog to project
   * @param selectedNames the run's tool selection (empty / null = no selection restriction)
   * @return an unmodifiable set in emission order; empty when nothing is offered
   */
  default Set<String> offeredWireNames(OperationCatalog catalog, Collection<String> selectedNames) {
    List<Map<String, Object>> emitted = emit(catalog, selectedNames);
    if (emitted == null || emitted.isEmpty()) {
      return Set.of();
    }
    Set<String> names = new LinkedHashSet<>(emitted.size());
    for (Map<String, Object> tool : emitted) {
      if (tool == null) {
        continue;
      }
      if (!(tool.get("function") instanceof Map<?, ?> function)) {
        continue;
      }
      Object name = function.get("name");
      if (name == null) {
        continue;
      }
      String wire = name.toString();
      if (!wire.isEmpty()) {
        names.add(wire);
      }
    }
    return Collections.unmodifiableSet(names);
  }
}
