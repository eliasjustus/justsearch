/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.emitter;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  /**
   * Tempdoc 876 §B.1 — the ONE "a virtual tool never shadows a core one" rule. Both the emit path
   * ({@link AgentOperationEmitter#emit}) and the trust-panel projection
   * ({@code AgentToolsController.handleListTools}) merge this store's entries after the core tools;
   * before 876 each carried its own copy of the drop rule, and they had drifted — the controller's
   * copy read core names out of a {@code function} envelope its own flat projection never emits, so
   * its collision set was always empty and it dropped nothing; it also kept a malformed entry the
   * emit path drops. One rule, called from both, is the point.
   *
   * <p>An entry is kept only when it carries a {@code function} map with a non-null {@code name}
   * that is absent from {@code coreWireNames}. A malformed entry (no {@code function} map, or a
   * null name) is DROPPED: it cannot be shown to be collision-free, and an unnamed tool is not
   * callable anyway.
   *
   * @param virtual candidate virtual entries, in publish order
   * @param coreWireNames the OpenAI function names the core tools already occupy
   * @return the subset of {@code virtual} safe to append, order preserved
   */
  public static List<Map<String, Object>> withoutCollisions(
      List<Map<String, Object>> virtual, Set<String> coreWireNames) {
    if (virtual == null || virtual.isEmpty()) {
      return List.of();
    }
    Set<String> taken = coreWireNames == null ? Set.of() : coreWireNames;
    return virtual.stream()
        .filter(entry -> {
          String name = wireName(entry);
          return name != null && !taken.contains(name);
        })
        .toList();
  }

  /**
   * The OpenAI function names a list of tool envelopes occupies — the {@code coreWireNames} input
   * to {@link #withoutCollisions}. Entries without a {@code function} map or a name contribute
   * nothing (they cannot be collided with).
   */
  public static Set<String> wireNames(List<Map<String, Object>> toolEnvelopes) {
    if (toolEnvelopes == null || toolEnvelopes.isEmpty()) {
      return Set.of();
    }
    Set<String> names = new LinkedHashSet<>();
    for (Map<String, Object> tool : toolEnvelopes) {
      String name = wireName(tool);
      if (name != null) {
        names.add(name);
      }
    }
    return names;
  }

  /** {@code entry.function.name} as a String, or null when the envelope does not carry one. */
  private static String wireName(Map<String, Object> entry) {
    if (entry == null) {
      return null;
    }
    Object fn = entry.get("function");
    if (!(fn instanceof Map<?, ?> fnMap)) {
      return null;
    }
    Object name = fnMap.get("name");
    return name == null ? null : name.toString();
  }
}
