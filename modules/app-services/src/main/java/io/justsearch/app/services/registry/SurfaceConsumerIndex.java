/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.DiagnosticChannelRef;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Surface;
import io.justsearch.agent.api.registry.SurfaceCatalog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Indexes Surface catalogs by their {@code consumes} cross-references so the registry
 * controller can derive {@link ConsumerHook} entries per Operation / Resource /
 * DiagnosticChannel automatically.
 *
 * <p>Per slice 481 §7 step 3 follow-up (autonomous session, 2026-05-08): the substrate
 * shipped {@code consumers: List<ConsumerHook>} as an empty default. Auto-derivation
 * from Surface.consumes turns the field into useful information without per-entry
 * manual backfill — the Surface ID *is* the named consumer for the cross-reference
 * relationship slice 449 already typed.
 *
 * <p>The derivation is principled: the consumer is concretely identified by
 * {@link Surface#id()}, not inferred from indirect signals. Each consumed
 * Operation / Resource / DiagnosticChannel gets a {@link ConsumerHook.Realized} hook
 * with consumerId = the consuming Surface's id and audience = the Surface's audience.
 *
 * <p>Auto-derived hooks are merged with declared hooks at emit time:
 * {@code wire.consumers = entry.consumers() ∪ index.consumersOf(entry.id())}. Entries
 * may continue to declare additional hooks (e.g., agent-loop tool bindings,
 * test-only consumers, plugin-contributed consumers) explicitly; the auto-derivation
 * adds the Surface-level coverage.
 *
 * <p>Why this is not auto-derivation from {@link io.justsearch.agent.api.registry.ExecutorTag}:
 * ExecutorTag declares which executor *can* invoke an Operation; it does not name a
 * specific consumer. The Surface catalog's {@code consumes} field is a typed cross-
 * reference to a specific named entry. The substrate-without-consumer prevention
 * the unified theory aims at requires *named* consumers, not inferred-by-presence ones.
 */
public final class SurfaceConsumerIndex {

  private final Map<OperationRef, List<ConsumerHook>> byOperation;
  private final Map<ResourceRef, List<ConsumerHook>> byResource;
  private final Map<DiagnosticChannelRef, List<ConsumerHook>> byDiagnosticChannel;

  public SurfaceConsumerIndex(List<SurfaceCatalog> surfaceCatalogs) {
    Objects.requireNonNull(surfaceCatalogs, "surfaceCatalogs");
    Map<OperationRef, List<ConsumerHook>> ops = new HashMap<>();
    Map<ResourceRef, List<ConsumerHook>> res = new HashMap<>();
    Map<DiagnosticChannelRef, List<ConsumerHook>> dcs = new HashMap<>();
    for (SurfaceCatalog catalog : surfaceCatalogs) {
      for (Surface surface : catalog.definitions()) {
        ConsumerHook hook = new ConsumerHook.Realized(surface.id().value(), surface.audience());
        for (OperationRef opRef : surface.consumes().operations()) {
          ops.computeIfAbsent(opRef, k -> new ArrayList<>()).add(hook);
        }
        for (ResourceRef resRef : surface.consumes().resources()) {
          res.computeIfAbsent(resRef, k -> new ArrayList<>()).add(hook);
        }
        for (DiagnosticChannelRef dcRef : surface.consumes().diagnosticChannels()) {
          dcs.computeIfAbsent(dcRef, k -> new ArrayList<>()).add(hook);
        }
      }
    }
    this.byOperation = freeze(ops);
    this.byResource = freeze(res);
    this.byDiagnosticChannel = freeze(dcs);
  }

  public List<ConsumerHook> consumersOf(OperationRef ref) {
    return byOperation.getOrDefault(Objects.requireNonNull(ref, "ref"), List.of());
  }

  public List<ConsumerHook> consumersOf(ResourceRef ref) {
    return byResource.getOrDefault(Objects.requireNonNull(ref, "ref"), List.of());
  }

  public List<ConsumerHook> consumersOf(DiagnosticChannelRef ref) {
    return byDiagnosticChannel.getOrDefault(Objects.requireNonNull(ref, "ref"), List.of());
  }

  /**
   * Merge a declared consumers list with auto-derived consumers (declared first,
   * derived appended; duplicates by consumerId removed).
   */
  public static List<ConsumerHook> merge(List<ConsumerHook> declared, List<ConsumerHook> derived) {
    List<ConsumerHook> merged = new ArrayList<>(declared);
    for (ConsumerHook d : derived) {
      boolean alreadyPresent =
          merged.stream().anyMatch(existing -> existing.consumerId().equals(d.consumerId()));
      if (!alreadyPresent) {
        merged.add(d);
      }
    }
    return List.copyOf(merged);
  }

  private static <K, V> Map<K, List<V>> freeze(Map<K, List<V>> mutable) {
    Map<K, List<V>> frozen = new HashMap<>(mutable.size());
    for (Map.Entry<K, List<V>> entry : mutable.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Map.copyOf(frozen);
  }
}
