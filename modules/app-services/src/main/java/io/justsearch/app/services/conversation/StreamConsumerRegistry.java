/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.conversation.StreamConsumer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Substrate registry for {@link StreamConsumer} implementations.
 *
 * <p>Per tempdoc 491 §5.1: a {@link ConversationShape}'s manifest references consumers by
 * id; the engine looks them up here.
 */
public final class StreamConsumerRegistry {

  private final Map<String, StreamConsumer> byId;

  private StreamConsumerRegistry(Map<String, StreamConsumer> byId) {
    this.byId = Map.copyOf(byId);
  }

  /** Resolve a consumer by id, or empty if not registered. */
  public Optional<StreamConsumer> findById(String id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(byId.get(id));
  }

  /** Build a registry indexing the supplied implementations by their {@link StreamConsumer#id}. */
  public static StreamConsumerRegistry of(Iterable<StreamConsumer> impls) {
    Objects.requireNonNull(impls, "impls");
    Map<String, StreamConsumer> idx = new LinkedHashMap<>();
    for (StreamConsumer impl : impls) {
      Objects.requireNonNull(impl, "StreamConsumer");
      StreamConsumer prior = idx.putIfAbsent(impl.id(), impl);
      if (prior != null) {
        throw new IllegalArgumentException("Duplicate StreamConsumer id: " + impl.id());
      }
    }
    return new StreamConsumerRegistry(idx);
  }
}
