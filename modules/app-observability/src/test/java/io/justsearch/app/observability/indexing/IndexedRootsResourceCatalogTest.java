package io.justsearch.app.observability.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.PathPolicy;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.SubscriptionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IndexedRootsResourceCatalog")
final class IndexedRootsResourceCatalogTest {

  @Test
  @DisplayName("catalog ships exactly one Resource entry")
  void exactlyOneEntry() {
    ResourceCatalog catalog = new IndexedRootsResourceCatalog();
    assertEquals(1, catalog.definitions().size());
  }

  @Test
  @DisplayName("namespace is core")
  void namespaceIsCore() {
    assertEquals("core", new IndexedRootsResourceCatalog().namespace());
  }

  @Test
  @DisplayName("entry shape: TABULAR × ONE_SHOT × endpoint × kind × schema URL")
  void entryShape() {
    Resource entry = new IndexedRootsResourceCatalog().definitions().get(0);
    assertEquals(Category.TABULAR, entry.category());
    assertEquals(SubscriptionMode.ONE_SHOT, entry.subscriptionMode());
    assertEquals("/api/indexing-roots/substrate", entry.endpoint());
    assertEquals("indexed-roots-cards", entry.kind());
    assertNotNull(entry.schema());
    assertFalse(entry.schema().isBlank());
    assertTrue(entry.schema().endsWith("indexed-root.v1.json"));
    assertEquals(
        "registry-resource.indexed-roots.label",
        entry.presentation().labelKey().value());
    assertEquals(
        "registry-resource.indexed-roots.description",
        entry.presentation().descriptionKey().value());
  }

  @Test
  @DisplayName("OperationRef is core.indexed-roots (regex-compliant)")
  void operationIdShape() {
    Resource entry = new IndexedRootsResourceCatalog().definitions().get(0);
    assertEquals(new ResourceRef("core.indexed-roots"), entry.id());
  }

  @Test
  @DisplayName("findById resolves the entry")
  void findByIdResolves() {
    ResourceCatalog catalog = new IndexedRootsResourceCatalog();
    assertTrue(
        catalog.findById(new ResourceRef("core.indexed-roots")).isPresent(),
        "Catalog must resolve its own entry by id");
  }

  @Test
  @DisplayName("Privacy axis: HASHED_REQUIRES_RESOLVER + core.resolve-path-hash resolver")
  void privacyAxisDeclared() {
    Resource entry = new IndexedRootsResourceCatalog().definitions().get(0);
    assertEquals(PathPolicy.HASHED_REQUIRES_RESOLVER, entry.privacy().pathPolicy());
    assertEquals(
        new OperationRef("core.resolve-path-hash"),
        entry.privacy().resolver().orElseThrow());
    assertTrue(entry.privacy().loopbackOnly(), "loopback-only true matches the hard invariant");
  }

  @Test
  @DisplayName("V1 ships no item or collection Operations on the Resource")
  void noOperationsInV1() {
    Resource entry = new IndexedRootsResourceCatalog().definitions().get(0);
    assertTrue(
        entry.itemOperations().isEmpty(),
        "V1 keeps Operations on the Surface manifest, not the Resource");
    assertTrue(entry.collectionOperations().isEmpty());
  }

  @Test
  @DisplayName("primaryKey is pathHash")
  void primaryKeyIsPathHash() {
    Resource entry = new IndexedRootsResourceCatalog().definitions().get(0);
    assertEquals("pathHash", entry.primaryKey());
  }
}
