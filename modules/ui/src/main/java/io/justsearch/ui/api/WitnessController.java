/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.ContributionRegistry;
import io.justsearch.agent.api.registry.PluginRef;
import io.justsearch.agent.api.registry.RegistryRef;
import io.justsearch.app.services.registry.snapshot.RegistrySnapshotExporter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tempdoc 560 §28 Phase 3 — the run-tier witness as OBSERVABILITY (NOT a CI gate; the §5 runtime-witness
 * gate stays deferred, blocked on the four §11.2 maintainer decisions).
 *
 * <p>Serves {@code GET /api/registry/witness}: what is composed into the LIVE
 * {@link ContributionRegistry}. SCOPE (not the full catalog union): the registry holds every
 * <em>operation</em> from all sources (core / agent-tools / workflows / MCP / plugins) but, for the other
 * five kinds, only the <em>plugin-contributed</em> ones — core surfaces / resources / prompts / channels
 * / shapes live in separate substrate catalogs (served at {@code /api/registry/*}) and are NOT mirrored
 * here. Unlike the build-time snapshot ({@link RegistrySnapshotExporter}, reconstructed from static
 * catalogs before any boot), this reads the running registry, so runtime-only contributions are visible.
 * Each row carries {@code buildWitnessed}: whether the build-time snapshot also covers it. A
 * runtime-composed operation (e.g. {@code core_workflow_demo_compose}) appears with
 * {@code buildWitnessed=false} — the concrete "Issue 4" blind-spot DR-D demonstrated. This is the
 * observability half of §4b; the full uniform-all-kinds witness §4b envisions would additionally union
 * the core substrate catalogs.
 *
 * <p>Additive + read-only. {@code null} live registry (the test-only fallback) yields an empty witness.
 */
public final class WitnessController {

  /** The live composed registry; {@code null} when no MCP host service ran (test-only fallback). */
  private final ContributionRegistry liveRegistry;

  public WitnessController(ContributionRegistry liveRegistry) {
    this.liveRegistry = liveRegistry;
  }

  /** GET /api/registry/witness */
  public void handle(Context ctx) {
    List<Map<String, Object>> entries = new ArrayList<>();
    if (liveRegistry != null) {
      // The build-time witness covers operations + resources + prompts (the ConsumerDeclaring kinds).
      // Surfaces / channels / shapes are not in that snapshot, so they always report buildWitnessed=false.
      Set<String> buildWitnessed = new HashSet<>();
      RegistrySnapshotExporter.buildOperationEntries().forEach(e -> buildWitnessed.add(e.id()));
      RegistrySnapshotExporter.buildResourceEntries().forEach(e -> buildWitnessed.add(e.id()));
      RegistrySnapshotExporter.buildPromptEntries().forEach(e -> buildWitnessed.add(e.id()));

      liveRegistry
          .operations()
          .forEach(o -> entries.add(row("operation", o.id().value(), o.id(), buildWitnessed)));
      liveRegistry
          .resources()
          .forEach(r -> entries.add(row("resource", r.id().value(), r.id(), buildWitnessed)));
      liveRegistry
          .prompts()
          .forEach(p -> entries.add(row("prompt", p.id().value(), p.id(), buildWitnessed)));
      liveRegistry
          .surfaces()
          .forEach(s -> entries.add(row("surface", s.id().value(), s.id(), buildWitnessed)));
      liveRegistry
          .diagnosticChannels()
          .forEach(
              c -> entries.add(row("diagnostic-channel", c.id().value(), c.id(), buildWitnessed)));
      liveRegistry
          .conversationShapes()
          .forEach(
              s -> entries.add(row("conversation-shape", s.id().value(), s.id(), buildWitnessed)));
    }
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("schemaVersion", "1.0");
    envelope.put("namespace", "registry-witness");
    envelope.put("entries", entries);
    ctx.json(envelope);
  }

  private Map<String, Object> row(
      String kind, String id, RegistryRef<?> ref, Set<String> buildWitnessed) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("kind", kind);
    m.put("id", id);
    // ownerOf returns the contributing plugin (empty for host/core-owned contributions → null owner).
    m.put("owner", liveRegistry.ownerOf(ref).map(PluginRef::value).orElse(null));
    m.put("buildWitnessed", buildWitnessed.contains(id));
    return m;
  }
}
