/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.ContributionRegistry;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Plugin;
import io.justsearch.agent.api.registry.PluginContributions;
import io.justsearch.agent.api.registry.PluginRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.SubscriptionMode;
import io.justsearch.agent.api.registry.TrustTier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tempdoc 560 §28 Phase 3 — the run-tier witness observability endpoint. */
class WitnessControllerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("a null live registry yields an empty witness (test-only fallback path)")
  void nullRegistryYieldsEmptyWitness() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

    new WitnessController(null).handle(ctx);

    verify(ctx).json(captor.capture());
    JsonNode envelope = MAPPER.valueToTree(captor.getValue());
    assertEquals("registry-witness", envelope.get("namespace").asText());
    assertTrue(envelope.get("entries").isArray());
    assertEquals(0, envelope.get("entries").size());
  }

  @Test
  @DisplayName("a plugin-installed resource surfaces with its owner and buildWitnessed=false")
  void pluginResourceSurfacesAsRuntimeOnly() throws Exception {
    ContributionRegistry registry = new ContributionRegistry();
    PluginRef pluginId = new PluginRef("vendor.example.demo");
    ResourceRef resourceId = new ResourceRef("vendor.example.demo-resource");
    Provenance prov = new Provenance(TrustTier.TRUSTED_PLUGIN, "vendor.example", "1.0");
    Resource resource =
        new Resource(
            resourceId,
            Presentation.of(
                new I18nKey("registry-resource.vendor-example-demo-resource.label"),
                new I18nKey("registry-resource.vendor-example-demo-resource.description")),
            "vendor.example.demo-resource.schema.json",
            Category.STATE,
            SubscriptionMode.ONE_SHOT,
            "/api/registry/resources",
            "document",
            Optional.empty(),
            Optional.empty(),
            prov,
            Privacy.noPaths(),
            Set.of(),
            Set.of(),
            "id");
    Plugin plugin =
        new Plugin(
            pluginId,
            Presentation.of(
                new I18nKey("plugin.vendor-example-demo.label"),
                new I18nKey("plugin.vendor-example-demo.description")),
            prov,
            Audience.USER,
            new PluginContributions(
                Set.of(), Set.of(resourceId), Set.of(), Set.of(), Set.of(), Set.of()),
            List.of(new ConsumerHook.Realized("registry", Audience.OPERATOR)));
    registry.install(
        new ContributionRegistry.Installation(
            plugin,
            List.of(),
            List.of(resource),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.of()));

    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    new WitnessController(registry).handle(ctx);

    verify(ctx).json(captor.capture());
    JsonNode envelope = MAPPER.valueToTree(captor.getValue());
    JsonNode entries = envelope.get("entries");
    JsonNode row = null;
    for (JsonNode e : entries) {
      if ("vendor.example.demo-resource".equals(e.get("id").asText())) {
        row = e;
        break;
      }
    }
    assertTrue(row != null, "the plugin-installed resource must surface in the live witness");
    assertEquals("resource", row.get("kind").asText());
    assertEquals("vendor.example.demo", row.get("owner").asText());
    // The build-time snapshot is reconstructed from static catalogs, so a runtime-installed plugin
    // resource is NOT build-witnessed — the DR-D blind-spot, now observable.
    assertFalse(row.get("buildWitnessed").asBoolean());
  }
}
