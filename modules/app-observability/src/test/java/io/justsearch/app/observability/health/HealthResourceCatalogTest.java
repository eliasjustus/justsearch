package io.justsearch.app.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.SubscriptionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HealthResourceCatalog")
final class HealthResourceCatalogTest {

  @Test
  @DisplayName("catalog ships exactly one Resource entry")
  void exactlyOneEntry() {
    ResourceCatalog catalog = new HealthResourceCatalog();
    assertEquals(1, catalog.definitions().size());
  }

  @Test
  @DisplayName("namespace is core")
  void namespaceIsCore() {
    assertEquals("core", new HealthResourceCatalog().namespace());
  }

  @Test
  @DisplayName("entry shape: SSE_STREAM + endpoint + kind + schema URL + presentation keys")
  void entryShape() {
    Resource entry = new HealthResourceCatalog().definitions().get(0);
    assertEquals(SubscriptionMode.SSE_STREAM, entry.subscriptionMode());
    assertEquals("/api/health/events/stream", entry.endpoint());
    assertEquals("health-event-stream", entry.kind());
    assertNotNull(entry.schema());
    assertFalse(entry.schema().isBlank());
    assertTrue(entry.schema().endsWith("health-event.v1.json"));
    assertEquals(
        "registry-resource.health-events.label",
        entry.presentation().labelKey().value());
    assertEquals(
        "registry-resource.health-events.description",
        entry.presentation().descriptionKey().value());
  }

  @Test
  @DisplayName("ResourceRef is core.health-events (regex-compliant)")
  void operationIdShape() {
    Resource entry = new HealthResourceCatalog().definitions().get(0);
    assertEquals(new ResourceRef("core.health-events"), entry.id());
  }

  @Test
  @DisplayName("findById resolves the entry")
  void findByIdResolves() {
    ResourceCatalog catalog = new HealthResourceCatalog();
    assertTrue(
        catalog.findById(new ResourceRef("core.health-events")).isPresent(),
        "Catalog must resolve its own entry by id");
  }
}
