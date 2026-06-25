package io.justsearch.app.observability.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FailedIndexingJobsResourceCatalog")
final class FailedIndexingJobsResourceCatalogTest {

  @Test
  @DisplayName("catalog ships exactly one Resource entry in the core namespace")
  void exactlyOneEntry() {
    FailedIndexingJobsResourceCatalog catalog = new FailedIndexingJobsResourceCatalog();
    assertEquals(1, catalog.definitions().size());
    assertEquals("core", catalog.namespace());
  }

  /**
   * Tempdoc 599 §16.1 Move 1 — per-row retry/cancel are bound as item-operations (the V1 scaffolding
   * left them empty). This makes failed-jobs rows actionable wherever they render (the operator global
   * view + the per-folder user drill-down's {@code <jf-row-actions>}), and clear-failed stays the
   * collection operation.
   */
  @Test
  @DisplayName("itemOperations declare cancel + retry; clear-failed stays the collection operation")
  void itemOperationsDeclareRetryAndCancel() {
    Resource entry = new FailedIndexingJobsResourceCatalog().definitions().get(0);
    assertEquals(2, entry.itemOperations().size());
    assertTrue(entry.itemOperations().contains(new OperationRef("core.retry-indexing-job")));
    assertTrue(entry.itemOperations().contains(new OperationRef("core.cancel-indexing-job")));
    assertTrue(entry.collectionOperations().contains(new OperationRef("core.clear-failed-jobs")));
  }

  /**
   * Tempdoc 599 §16.1 Move 1 — binding user-invocable item-ops is orthogonal to the Resource's own
   * audience: the GLOBAL failed-jobs view stays operator-grade triage (slice 481 §7); per-op audience
   * governs invocability.
   */
  @Test
  @DisplayName("the Resource keeps OPERATOR audience for its global view")
  void resourceStaysOperatorAudience() {
    Resource entry = new FailedIndexingJobsResourceCatalog().definitions().get(0);
    assertEquals(Audience.OPERATOR, entry.audience());
  }
}
