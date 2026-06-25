/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;

/**
 * Flat wire projection of a {@link ConsumerHook} as served on a registry entry's {@code consumers}
 * array at {@code /api/registry/{operations,resources}}.
 *
 * <p>Tempdoc 560 §4c: the domain {@link ConsumerHook} is a sealed, {@code @JsonTypeInfo}-discriminated
 * union (its {@code kind} discriminator is preserved for slice-485's {@code Promised} variant). But
 * the wire never carries {@code kind}: {@code RegistryController} merges the derived hooks into a
 * {@code Map<String,Object>}, which erases the element type and drops the discriminator. The
 * generated schema must match that flat wire — so the wire views ({@link UIResourceView}, {@link
 * UIOperationView}) project consumers onto this discriminator-free DTO. The schema is then flat by
 * construction, retiring the brittle post-hoc {@code kind}-strip the substrate schema test used to
 * carry.
 */
public record ConsumerView(String consumerId, Audience audience) implements PreciseWire {

  public ConsumerView {
    Objects.requireNonNull(consumerId, "consumerId");
    Objects.requireNonNull(audience, "audience");
  }

  /** Project a domain {@link ConsumerHook} (any variant) onto its flat wire shape. */
  public static ConsumerView from(ConsumerHook hook) {
    return new ConsumerView(hook.consumerId(), hook.audience());
  }
}
