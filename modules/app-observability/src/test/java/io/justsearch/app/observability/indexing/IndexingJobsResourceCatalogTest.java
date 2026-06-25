package io.justsearch.app.observability.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.PathPolicy;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.SubscriptionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IndexingJobsResourceCatalog")
final class IndexingJobsResourceCatalogTest {

  @Test
  @DisplayName("catalog ships exactly one Resource entry")
  void exactlyOneEntry() {
    ResourceCatalog catalog = new IndexingJobsResourceCatalog();
    assertEquals(1, catalog.definitions().size());
  }

  @Test
  @DisplayName("namespace is core")
  void namespaceIsCore() {
    assertEquals("core", new IndexingJobsResourceCatalog().namespace());
  }

  @Test
  @DisplayName("first TABULAR entry: Category × SSE_STREAM × endpoint × kind × schema URL")
  void entryShape() {
    Resource entry = new IndexingJobsResourceCatalog().definitions().get(0);
    assertEquals(Category.TABULAR, entry.category());
    assertEquals(SubscriptionMode.SSE_STREAM, entry.subscriptionMode());
    assertEquals("/api/indexing-jobs/stream", entry.endpoint());
    assertEquals("indexing-jobs-table", entry.kind());
    assertNotNull(entry.schema());
    assertFalse(entry.schema().isBlank());
    assertTrue(entry.schema().endsWith("indexing-job-view.v1.json"));
    assertEquals(
        "registry-resource.indexing-jobs.label",
        entry.presentation().labelKey().value());
    assertEquals(
        "registry-resource.indexing-jobs.description",
        entry.presentation().descriptionKey().value());
  }

  @Test
  @DisplayName("OperationRef is core.indexing-jobs (regex-compliant)")
  void operationIdShape() {
    Resource entry = new IndexingJobsResourceCatalog().definitions().get(0);
    assertEquals(new ResourceRef("core.indexing-jobs"), entry.id());
  }

  @Test
  @DisplayName("findById resolves the entry")
  void findByIdResolves() {
    ResourceCatalog catalog = new IndexingJobsResourceCatalog();
    assertTrue(
        catalog.findById(new ResourceRef("core.indexing-jobs")).isPresent(),
        "Catalog must resolve its own entry by id");
  }

  @Test
  @DisplayName("Privacy axis: HASHED_REQUIRES_RESOLVER + core.resolve-path-hash resolver")
  void privacyAxisDeclared() {
    Resource entry = new IndexingJobsResourceCatalog().definitions().get(0);
    assertEquals(PathPolicy.HASHED_REQUIRES_RESOLVER, entry.privacy().pathPolicy());
    assertEquals(
        new OperationRef("core.resolve-path-hash"),
        entry.privacy().resolver().orElseThrow());
    assertTrue(entry.privacy().loopbackOnly(), "loopback-only true matches the hard invariant");
  }

  @Test
  @DisplayName("itemOperations declare cancel + retry; collectionOperations declare clear-failed")
  void operationCompositionDeclared() {
    Resource entry = new IndexingJobsResourceCatalog().definitions().get(0);
    assertEquals(2, entry.itemOperations().size());
    assertTrue(entry.itemOperations().contains(new OperationRef("core.cancel-indexing-job")));
    assertTrue(entry.itemOperations().contains(new OperationRef("core.retry-indexing-job")));
    assertEquals(1, entry.collectionOperations().size());
    assertTrue(
        entry.collectionOperations().contains(new OperationRef("core.clear-failed-jobs")),
        "clear-failed-jobs is the lifted aggregate Operation");
  }
}
