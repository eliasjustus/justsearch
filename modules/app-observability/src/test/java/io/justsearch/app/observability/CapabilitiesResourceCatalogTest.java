package io.justsearch.app.observability;

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

@DisplayName("CapabilitiesResourceCatalog")
final class CapabilitiesResourceCatalogTest {

  @Test
  @DisplayName("catalog ships exactly one Resource entry")
  void exactlyOneEntry() {
    ResourceCatalog catalog = new CapabilitiesResourceCatalog();
    assertEquals(1, catalog.definitions().size());
  }

  @Test
  @DisplayName("namespace is core")
  void namespaceIsCore() {
    assertEquals("core", new CapabilitiesResourceCatalog().namespace());
  }

  @Test
  @DisplayName("entry shape: STATE × SSE_STREAM + endpoint + kind + schema URL")
  void entryShape() {
    Resource entry = new CapabilitiesResourceCatalog().definitions().get(0);
    assertSame(Category.STATE, entry.category());
    assertEquals(SubscriptionMode.SSE_STREAM, entry.subscriptionMode());
    assertEquals("/infra/capabilities/stream", entry.endpoint());
    assertEquals("server-capabilities", entry.kind());
    assertNotNull(entry.schema());
    assertFalse(entry.schema().isBlank());
    assertTrue(entry.schema().endsWith("capabilities-view.schema.json"));
  }

  @Test
  @DisplayName("history is empty (per 01a recipe: STATE has no retained past)")
  void historyEmpty() {
    Resource entry = new CapabilitiesResourceCatalog().definitions().get(0);
    assertTrue(entry.history().isEmpty());
  }

  @Test
  @DisplayName("recovery is empty (no per-Resource recovery for capability handshake)")
  void recoveryEmpty() {
    Resource entry = new CapabilitiesResourceCatalog().definitions().get(0);
    assertTrue(entry.recovery().isEmpty());
  }

  @Test
  @DisplayName("presentation i18n keys point to registry-resource.server-capabilities.*")
  void presentationKeys() {
    Resource entry = new CapabilitiesResourceCatalog().definitions().get(0);
    assertEquals(
        "registry-resource.server-capabilities.label",
        entry.presentation().labelKey().value());
    assertEquals(
        "registry-resource.server-capabilities.description",
        entry.presentation().descriptionKey().value());
  }

  @Test
  @DisplayName("ResourceRef is core.server-capabilities (regex-compliant)")
  void operationIdShape() {
    Resource entry = new CapabilitiesResourceCatalog().definitions().get(0);
    assertEquals(new ResourceRef("core.server-capabilities"), entry.id());
  }

  @Test
  @DisplayName("findById resolves the entry")
  void findByIdResolves() {
    ResourceCatalog catalog = new CapabilitiesResourceCatalog();
    assertTrue(
        catalog.findById(new ResourceRef("core.server-capabilities")).isPresent(),
        "Catalog must resolve its own entry by id");
  }
}
