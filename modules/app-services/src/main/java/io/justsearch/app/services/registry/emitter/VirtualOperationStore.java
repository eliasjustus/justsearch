/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.emitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tempdoc 508 §11.5 / §13.5 — sidecar store for FE-projected
 * {@code VirtualOperation} entries. The core {@link
 * io.justsearch.agent.api.registry.OperationCatalog} is immutable
 * at boot; plugin-contributed commands cannot mutate it. The FE
 * projects TRUSTED+/CORE commands into Operation-shaped tool
 * envelopes and publishes them here via the REST endpoint; {@link
 * AgentOperationEmitter} merges them into the tool list at emit
 * time so the agent sees one unified vocabulary.
 *
 * <p>This is a single global store (one FE session per backend in
 * the V1 deployment topology). Multi-tenant support is a follow-up.
 *
 * <p>Wire-shape stored is the already-serialized OpenAI tools
 * envelope: {@code [{type: "function", function: {name, description,
 * parameters}}, ...]}. The FE owns the projection logic; the
 * backend just stores and merges.
 */
public final class VirtualOperationStore {

  private final AtomicReference<List<Map<String, Object>>> tools =
      new AtomicReference<>(List.of());

  /** Replace the stored list. Called by the REST endpoint on FE publish. */
  public void publish(List<Map<String, Object>> nextTools) {
    if (nextTools == null) {
      tools.set(List.of());
      return;
    }
    // Defensive copy to insulate from caller mutation.
    List<Map<String, Object>> snapshot = nextTools.stream()
        .map(entry -> {
          if (entry == null) return null;
          Map<String, Object> copy = new LinkedHashMap<>(entry);
          Object fn = copy.get("function");
          if (fn instanceof Map<?, ?> fnMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) fnMap;
            copy.put("function", new LinkedHashMap<>(typed));
          }
          return copy;
        })
        .filter(java.util.Objects::nonNull)
        .toList();
    tools.set(snapshot);
  }

  /** Current snapshot. Returned list is immutable. */
  public List<Map<String, Object>> snapshot() {
    return tools.get();
  }

  /** Clear all virtual tools (used by tests + by FE disconnect). */
  public void clear() {
    tools.set(List.of());
  }
}
