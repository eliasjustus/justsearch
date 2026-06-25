/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Set;

/**
 * Tempdoc 560 §4c (DiagnosticChannel slice) — the typed wire-view of a {@link DiagnosticChannel} as
 * served at {@code /api/registry/diagnostic-channels}. {@code UIDiagnosticChannelEmitter} builds this
 * record and serializes it, so the DiagnosticChannel wire has ONE typed authority and its FE
 * projection (record→JSON-Schema→{TS,Zod}) is faithful AND precise (the {@link PreciseWire} marker
 * makes the generated schema's required/non-null shape bite). This retires the hand-mirrored
 * {@code modules/ui-web/src/api/types/diagnostic.ts} — the last live instance of the registry
 * hand-mirror drift class the {@code AuditPolicy} bug proved.
 *
 * <p>The shape mirrors the historical raw-{@link DiagnosticChannel} wire ({@code convertValue(dc,
 * Map.class)}) component-for-component — same value types, the full 4-field {@link Provenance} with
 * its {@code identity} axis, the {@code Map<String,SubCategory>} selector. Two intended additions:
 *
 * <ul>
 *   <li>{@code type} — the {@code "diagnostic-channel"} discriminator the raw wire carried via {@link
 *       RegistryEntry}'s {@code @JsonTypeInfo}; a plain wire field here (mirroring {@link
 *       UIResourceView#type()}).
 *   <li>{@code consumers} — the {@link DiagnosticChannel} record has NO consumers field, but the wire
 *       ADDS one (the controller merges Surface-derived hooks). Projecting it onto the
 *       discriminator-free {@link ConsumerView} declares the field in the schema (fixing the FE
 *       omission) AND flattens the last {@code kind}-ful consumers wire (consistent with Resource /
 *       Operation). The declared-only projection here carries an empty list; the controller overwrites
 *       it with the Surface-merged set.
 * </ul>
 *
 * Byte-faithfulness vs. the prior raw-record wire is pinned by {@code
 * UIDiagnosticChannelViewConformanceTest}.
 */
public record UIDiagnosticChannelView(
    DiagnosticChannelRef id,
    String type,
    Presentation presentation,
    Set<DataClass> dataClasses,
    ProducerKind producer,
    DeliveryMode deliveryMode,
    LoggerNamespaceSelector selector,
    String endpoint,
    ConsumerPermission consumerPermission,
    Provenance provenance,
    List<ConsumerView> consumers)
    implements PreciseWire {}
