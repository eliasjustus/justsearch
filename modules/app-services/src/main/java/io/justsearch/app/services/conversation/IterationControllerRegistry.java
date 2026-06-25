/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.conversation.IterationController;
import io.justsearch.agent.api.conversation.SingleHopController;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Substrate registry for {@link IterationController} implementations.
 *
 * <p>Per tempdoc 491 §5.1: a {@link ConversationShape}'s manifest references the controller
 * by id; the engine looks it up here. The CORE registry always includes
 * {@link SingleHopController} so any shape can fall back to it without explicit registration.
 */
public final class IterationControllerRegistry {

  private final Map<String, IterationController> byId;

  private IterationControllerRegistry(Map<String, IterationController> byId) {
    this.byId = Map.copyOf(byId);
  }

  /** Resolve a controller by id, or empty if not registered. */
  public Optional<IterationController> findById(String id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(byId.get(id));
  }

  /**
   * Build a registry that always includes {@link SingleHopController#INSTANCE}, plus the
   * supplied implementations indexed by their {@link IterationController#id}.
   */
  public static IterationControllerRegistry of(Iterable<IterationController> impls) {
    Objects.requireNonNull(impls, "impls");
    Map<String, IterationController> idx = new LinkedHashMap<>();
    idx.put(SingleHopController.ID, SingleHopController.INSTANCE);
    for (IterationController impl : impls) {
      Objects.requireNonNull(impl, "IterationController");
      IterationController prior = idx.putIfAbsent(impl.id(), impl);
      if (prior != null && prior != impl) {
        throw new IllegalArgumentException(
            "Duplicate IterationController id: " + impl.id());
      }
    }
    return new IterationControllerRegistry(idx);
  }
}
