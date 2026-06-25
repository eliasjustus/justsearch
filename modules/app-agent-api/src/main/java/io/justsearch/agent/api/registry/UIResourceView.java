/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Tempdoc 560 §4c — the typed wire-view of a {@link Resource} as served at {@code
 * /api/registry/resources}. {@code UIResourceEmitter} builds this record and serializes it, so the
 * Resource wire has ONE typed authority and its FE projection (record→JSON-Schema→{TS,Zod}) is
 * faithful AND precise (the {@link PreciseWire} marker makes the generated schema's required/non-null
 * shape bite, instead of the all-optional default).
 *
 * <p>The shape mirrors the historical raw-{@link Resource} wire ({@code convertValue(resource,
 * Map.class)}) component-for-component — same field declaration order, same value types (so {@code
 * Optional<>} fields stay present-as-null, {@code Set<OperationRef>} stays an array of bare-string
 * refs, the full 4-field {@link Provenance} keeps its {@code identity} axis). The single intended
 * divergence is {@code consumers}: projected onto the discriminator-free {@link ConsumerView} (the
 * wire never carried {@link ConsumerHook}'s {@code kind} — it was erased by the controller's {@code
 * Map} round-trip and stripped from the schema post-hoc; the view makes the flat shape authoritative).
 * Byte-faithfulness vs. the prior raw-record wire is pinned by {@code UIResourceViewConformanceTest}.
 */
public record UIResourceView(
    ResourceRef id,
    String type,
    Presentation presentation,
    String schema,
    Category category,
    SubscriptionMode subscriptionMode,
    String endpoint,
    String kind,
    Optional<HistoryPolicy> history,
    Optional<OperationRef> recovery,
    Provenance provenance,
    Privacy privacy,
    Set<OperationRef> itemOperations,
    Set<OperationRef> collectionOperations,
    String primaryKey,
    Audience audience,
    List<ConsumerView> consumers,
    Optional<EmissionPolicy> emissionPolicy,
    Role role)
    implements PreciseWire {}
