/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.snapshot;

import io.justsearch.agent.api.registry.ContributionRegistry;
import io.justsearch.agent.api.registry.PluginRef;
import io.justsearch.agent.api.registry.RegistryRef;
import io.justsearch.agent.api.registry.SurfaceCatalog;
import io.justsearch.app.services.registry.SurfaceConsumerIndex;
import java.util.ArrayList;
import java.util.List;

/**
 * Tempdoc 560 §4b/§5 — the LIVE-REGISTRY witness (ADR-0042). Consumer-presence has three tiers:
 *
 * <ul>
 *   <li>the {@code consumer-presence} gate — a STATIC referencer scan over {@link
 *       RegistrySnapshotExporter}'s snapshot (a declaration NAMES a consumer);
 *   <li>the {@code runtime-witness} gate — STILL static, but bidirectional for the AGENT channel (the
 *       declared agent consumers match what {@code AgentOperationEmitter} would deliver). Since
 *       tempdoc 876 §B.6 it composes what production composes, so it now also covers the projected
 *       workflow ops ({@code core.workflow-*}) it used to miss — but only those a BUILD can derive:
 *       what a running install actually connects (MCP tools, plugin contributions) stays outside it
 *       by construction;
 *   <li>this LIVE-REGISTRY witness — the only tier that examines the running {@link
 *       ContributionRegistry}, so it covers the contributions composed at RUNTIME that no static
 *       tier can see: MCP tools and plugin contributions, and any workflow projection that depends
 *       on them. That is the empirically-demonstrated DR-D blind spot (tempdoc 560 §11.6); the
 *       build-tier witness is structurally static (§11.2/DR-A) and cannot see runtime composition.
 * </ul>
 *
 * <p>This applies the SAME consumer-presence merge over the LIVE registry — it is NOT a second
 * authority. It reuses {@link RegistrySnapshotExporter#operationConsumerIds} (inline {@code
 * ConsumerHook}s union executor-derived consumers) for operations and {@link SurfaceConsumerIndex}
 * (inline union surface-derived) for resources, exactly as the static snapshot does; prompts are
 * inline-only (and a zero-consumer prompt is unrepresentable by construction). So a delivered
 * operation with at least one executor always has a consumer — only a zero-executor, zero-consumer
 * delivered contribution is an orphan, the live analog of a {@code consumer-presence} failure.
 *
 * <p>Per ADR-0042: delivery-PRESENCE not a traffic count (decision 4); a contribution is "delivered"
 * once composed into the registry, not when invoked (decision 1); Realized hooks only — the only kind
 * that exists today (decision 3). This is a pure read over a fully-composed registry; it is exercised
 * by {@code LiveWitnessTest}, which is the sole authority for this tier — the only one that sees
 * runtime composition, and (since tempdoc 930 retired the {@code live-witness} register and its
 * offline wiring check) the only thing that holds the no-fork reuse of {@link
 * RegistrySnapshotExporter#operationConsumerIds} in place.
 */
public final class LiveWitness {

  private LiveWitness() {}

  /**
   * A delivered contribution that carries no consumer (inline or derived) — a live consumer-presence
   * violation. {@code owner} is the contributing plugin ref value, or {@code null} for a host/core-owned
   * contribution.
   */
  public record Orphan(String kind, String id, String owner) {}

  /**
   * Every delivered {@code ConsumerDeclaring} contribution in the live registry (operation / resource
   * / prompt) with zero merged consumers. Empty in a healthy build; a non-empty result is the live
   * analog of a {@code consumer-presence} gate failure, covering the runtime-composed contributions the
   * static snapshot misses. A {@code null} registry (the test-only fallback when no MCP host ran) yields
   * an empty list.
   */
  public static List<Orphan> orphanedDeliveries(ContributionRegistry live) {
    List<Orphan> out = new ArrayList<>();
    if (live == null) {
      return out;
    }
    // Surface-derived consumers for resources are read from the live surfaces, exactly as the static
    // snapshot reads them from the core surface catalog.
    SurfaceConsumerIndex surfaceIndex =
        new SurfaceConsumerIndex(List.of(SurfaceCatalog.of("live", live.surfaces())));

    live.operations()
        .forEach(
            op -> {
              if (RegistrySnapshotExporter.operationConsumerIds(op).isEmpty()) {
                add(out, "operation", op.id(), live);
              }
            });
    live.resources()
        .forEach(
            r -> {
              if (SurfaceConsumerIndex.merge(r.consumers(), surfaceIndex.consumersOf(r.id()))
                  .isEmpty()) {
                add(out, "resource", r.id(), live);
              }
            });
    live.prompts()
        .forEach(
            p -> {
              if (p.consumers().isEmpty()) {
                add(out, "prompt", p.id(), live);
              }
            });
    return out;
  }

  private static void add(
      List<Orphan> out, String kind, RegistryRef<?> ref, ContributionRegistry live) {
    out.add(new Orphan(kind, ref.value(), live.ownerOf(ref).map(PluginRef::value).orElse(null)));
  }
}
