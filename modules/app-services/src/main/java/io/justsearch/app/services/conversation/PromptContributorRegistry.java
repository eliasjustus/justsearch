/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.conversation.PromptContributor;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Substrate registry for {@link PromptContributor} implementations.
 *
 * <p>Per tempdoc 491 §5.1: a {@link ConversationShape}'s manifest references contributors by
 * id; the engine looks them up here. Phase C registers CORE implementations; Phase D extends
 * registration to {@code Provenance.tier=PLUGIN} contributors with the static-callsite +
 * wire-emitter checks.
 */
public final class PromptContributorRegistry {

  private final Map<String, PromptContributor> byId;

  private PromptContributorRegistry(Map<String, PromptContributor> byId) {
    this.byId = Map.copyOf(byId);
  }

  /** Resolve a contributor by id, or empty if not registered. */
  public Optional<PromptContributor> findById(String id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(byId.get(id));
  }

  /** Build a registry indexing the supplied implementations by their {@link PromptContributor#id}. */
  public static PromptContributorRegistry of(Iterable<PromptContributor> impls) {
    Objects.requireNonNull(impls, "impls");
    Map<String, PromptContributor> idx = new LinkedHashMap<>();
    Iterator<PromptContributor> it = impls.iterator();
    while (it.hasNext()) {
      PromptContributor impl = it.next();
      Objects.requireNonNull(impl, "PromptContributor");
      PromptContributor prior = idx.putIfAbsent(impl.id(), impl);
      if (prior != null) {
        throw new IllegalArgumentException(
            "Duplicate PromptContributor id: " + impl.id());
      }
    }
    return new PromptContributorRegistry(idx);
  }
}
