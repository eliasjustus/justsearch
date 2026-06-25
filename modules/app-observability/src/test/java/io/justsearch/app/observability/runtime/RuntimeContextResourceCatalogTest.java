package io.justsearch.app.observability.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.SubscriptionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RuntimeContextResourceCatalog")
final class RuntimeContextResourceCatalogTest {

  @Test
  @DisplayName("catalog ships exactly one Resource entry")
  void exactlyOneEntry() {
    ResourceCatalog catalog = new RuntimeContextResourceCatalog();
    assertEquals(1, catalog.definitions().size());
  }

  @Test
  @DisplayName("namespace is core")
  void namespaceIsCore() {
    assertEquals("core", new RuntimeContextResourceCatalog().namespace());
  }

  @Test
  @DisplayName("entry shape: STATE × SSE_STREAM + endpoint + kind + schema URL")
  void entryShape() {
    Resource entry = new RuntimeContextResourceCatalog().definitions().get(0);
    assertSame(Category.STATE, entry.category());
    assertEquals(SubscriptionMode.SSE_STREAM, entry.subscriptionMode());
    assertEquals("/api/runtime-context/stream", entry.endpoint());
    assertEquals("runtime-context", entry.kind());
    assertNotNull(entry.schema());
    assertFalse(entry.schema().isBlank());
    assertTrue(entry.schema().endsWith("runtime-context.v1.json"));
  }

  @Test
  @DisplayName("history is empty (per 01a recipe: STATE has no retained past)")
  void historyEmpty() {
    Resource entry = new RuntimeContextResourceCatalog().definitions().get(0);
    assertTrue(entry.history().isEmpty());
  }

  @Test
  @DisplayName("recovery is empty (no per-Resource recovery for runtime context)")
  void recoveryEmpty() {
    Resource entry = new RuntimeContextResourceCatalog().definitions().get(0);
    assertTrue(entry.recovery().isEmpty());
  }

  @Test
  @DisplayName("presentation i18n keys point to registry-resource.runtime-context.*")
  void presentationKeys() {
    Resource entry = new RuntimeContextResourceCatalog().definitions().get(0);
    assertEquals(
        "registry-resource.runtime-context.label",
        entry.presentation().labelKey().value());
    assertEquals(
        "registry-resource.runtime-context.description",
        entry.presentation().descriptionKey().value());
  }

  @Test
  @DisplayName("ResourceRef is core.runtime-context (regex-compliant)")
  void operationIdShape() {
    Resource entry = new RuntimeContextResourceCatalog().definitions().get(0);
    assertEquals(new ResourceRef("core.runtime-context"), entry.id());
  }

  @Test
  @DisplayName("findById resolves the entry")
  void findByIdResolves() {
    ResourceCatalog catalog = new RuntimeContextResourceCatalog();
    assertTrue(
        catalog.findById(new ResourceRef("core.runtime-context")).isPresent(),
        "Catalog must resolve its own entry by id");
  }
}
