/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.conversation.ContextInjector;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Substrate registry for {@link ContextInjector} implementations.
 *
 * <p>Per tempdoc 491 §5.1: a {@link ConversationShape}'s manifest references injectors by
 * id; the engine looks them up here.
 */
public final class ContextInjectorRegistry {

  private final Map<String, ContextInjector> byId;

  private ContextInjectorRegistry(Map<String, ContextInjector> byId) {
    this.byId = Map.copyOf(byId);
  }

  /** Resolve an injector by id, or empty if not registered. */
  public Optional<ContextInjector> findById(String id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(byId.get(id));
  }

  /** Build a registry indexing the supplied implementations by their {@link ContextInjector#id}. */
  public static ContextInjectorRegistry of(Iterable<ContextInjector> impls) {
    Objects.requireNonNull(impls, "impls");
    Map<String, ContextInjector> idx = new LinkedHashMap<>();
    for (ContextInjector impl : impls) {
      Objects.requireNonNull(impl, "ContextInjector");
      ContextInjector prior = idx.putIfAbsent(impl.id(), impl);
      if (prior != null) {
        throw new IllegalArgumentException(
            "Duplicate ContextInjector id: " + impl.id());
      }
    }
    return new ContextInjectorRegistry(idx);
  }
}
