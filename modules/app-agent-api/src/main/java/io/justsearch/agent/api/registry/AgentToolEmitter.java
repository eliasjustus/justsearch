/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Collection;
import java.util.List;
import java.util.Map;

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
}
