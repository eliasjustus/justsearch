/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.emitter;

import io.justsearch.agent.api.registry.ConsumerView;
import io.justsearch.agent.api.registry.DiagnosticChannel;
import io.justsearch.agent.api.registry.UIDiagnosticChannelView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Projects a {@link DiagnosticChannel} onto the typed {@link UIDiagnosticChannelView} wire record
 * served at {@code /api/registry/diagnostic-channels}.
 *
 * <p>Tempdoc 560 §4c (DiagnosticChannel slice): the DiagnosticChannel wire now has ONE typed
 * authority whose record→JSON-Schema→{TS,Zod} projection is faithful AND precise — a
 * component-for-component copy of the {@link DiagnosticChannel} record (same value types, so {@code
 * convertValue} serialization is identical by construction) plus the {@code "diagnostic-channel"}
 * type discriminator. The record has no {@code consumers} field; {@code toView} carries an empty
 * list, and {@code RegistryController} overwrites it with the Surface-merged set (flattened to {@link
 * ConsumerView} — the last {@code kind}-ful consumers wire, now consistent with Resource/Operation).
 * The declared-only (empty-consumers) projection here is what {@code
 * UIDiagnosticChannelViewConformanceTest} pins against the historical raw-record wire.
 */
public final class UIDiagnosticChannelEmitter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private UIDiagnosticChannelEmitter() {}

  /** Project a {@link DiagnosticChannel} onto its typed wire view (declared consumers are empty). */
  public static UIDiagnosticChannelView toView(DiagnosticChannel dc) {
    return new UIDiagnosticChannelView(
        dc.id(),
        "diagnostic-channel",
        dc.presentation(),
        dc.dataClasses(),
        dc.producer(),
        dc.deliveryMode(),
        dc.selector(),
        dc.endpoint(),
        dc.consumerPermission(),
        dc.provenance(),
        List.of());
  }

  /** Serialize the typed view to the wire {@code Map} (declaration-ordered; the envelope re-keys). */
  public static Map<String, Object> toEntry(DiagnosticChannel dc) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> result = MAPPER.convertValue(toView(dc), Map.class);
      return new LinkedHashMap<>(result);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to serialize DiagnosticChannel " + dc.id() + " to UI format", e);
    }
  }

  /** Project a catalog's worth of channels, declared consumers only (pre Surface-merge). */
  public static List<Map<String, Object>> emit(List<DiagnosticChannel> channels) {
    return channels.stream().map(UIDiagnosticChannelEmitter::toEntry).toList();
  }
}
