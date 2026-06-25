/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;

/**
 * The shared cross-cutting <b>consumers</b> axis (tempdoc 560 §4.1), lifted to one structural
 * position so the {@code NonEmpty<ConsumerHook>} keystone (§5/§6) can police <em>every</em>
 * consumer-bearing declaration uniformly — not only the primitive axes.
 *
 * <p>Implemented by both the sealed {@link RegistryEntry} primitives that carry a consumer set
 * ({@link Operation} / {@link Resource} / {@link Prompt}) <em>and</em> the Manifest tiers that bundle
 * primitives ({@link Workflow} / {@link Plugin}). A declaration that declares <em>who consumes it</em>
 * is a {@code ConsumerDeclaring}; the {@code consumer-presence} gate enumerates {@code ConsumerDeclaring}
 * kinds and fails the build on any whose merged consumer set is empty — making "substrate without a
 * consumer" unrepresentable across the whole model, not just three axes.
 *
 * <p>{@link Surface} / {@link ConversationShape} / {@link IntentSource} deliberately do <b>not</b>
 * implement this: they declare what they <em>{@code consume}</em> (the inverse direction), not a set of
 * consumers <em>of</em> themselves.
 */
public interface ConsumerDeclaring extends Provenanced {

  /** The declared consumers of this declaration ({@code NonEmpty} in a valid build). */
  List<ConsumerHook> consumers();

  /** Shared cross-cutting axis (§4.1): the declaration's invocation/visibility audience. */
  Audience audience();
}
