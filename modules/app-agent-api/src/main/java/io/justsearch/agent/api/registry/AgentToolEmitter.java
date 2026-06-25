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
@FunctionalInterface
public interface AgentToolEmitter {

  /**
   * Project the catalog into the OpenAI function-calling tools array, optionally filtered
   * to a subset of operation ids (empty / null filter returns all AGENT-targeted entries).
   */
  List<Map<String, Object>> emit(OperationCatalog catalog, Collection<String> selectedNames);
}
