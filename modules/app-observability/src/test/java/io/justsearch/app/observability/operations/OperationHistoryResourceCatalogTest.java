package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.HistoryPolicy;
import io.justsearch.agent.api.registry.OnOverflow;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.SubscriptionMode;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OperationHistoryResourceCatalog")
final class OperationHistoryResourceCatalogTest {

  private static Resource entry() {
    return new OperationHistoryResourceCatalog().definitions().get(0);
  }

  @Test
  @DisplayName("catalog ships exactly one Resource entry")
  void exactlyOneEntry() {
    ResourceCatalog catalog = new OperationHistoryResourceCatalog();
    assertEquals(1, catalog.definitions().size());
  }

  @Test
  @DisplayName("namespace is core")
  void namespaceIsCore() {
    assertEquals("core", new OperationHistoryResourceCatalog().namespace());
  }

  @Test
  @DisplayName("entry shape: EVENT_STREAM × SSE_STREAM + endpoint + kind + schema URL")
  void entryShape() {
    Resource e = entry();
    assertSame(Category.EVENT_STREAM, e.category());
    assertEquals(SubscriptionMode.SSE_STREAM, e.subscriptionMode());
    assertEquals("/api/operation-history/stream", e.endpoint());
    assertEquals("operation-history", e.kind());
    assertNotNull(e.schema());
    assertFalse(e.schema().isBlank());
    assertTrue(e.schema().endsWith("operation-history-entry.v1.json"));
  }

  @Test
  @DisplayName("HistoryPolicy is present (EVENT_STREAM Category requires it)")
  void historyPolicyPresent() {
    assertTrue(entry().history().isPresent(), "EVENT_STREAM Resource must declare HistoryPolicy");
  }

  @Test
  @DisplayName("HistoryPolicy mode is RING_BUFFER (matches in-memory store)")
  void historyPolicyModeIsRingBuffer() {
    HistoryPolicy p = entry().history().orElseThrow();
    assertSame(HistoryPolicy.Mode.RING_BUFFER, p.mode());
  }

  @Test
  @DisplayName("HistoryPolicy capacity matches OperationHistoryStore.DEFAULT_CAPACITY")
  void historyPolicyCapacityMatches() {
    HistoryPolicy p = entry().history().orElseThrow();
    assertEquals(
        OperationHistoryStore.DEFAULT_CAPACITY,
        p.capacity().orElseThrow(),
        "HISTORY_CAPACITY must equal OperationHistoryStore.DEFAULT_CAPACITY; if either"
            + " changes without the other, the wire-declared retention diverges from the"
            + " actual in-memory retention.");
  }

  @Test
  @DisplayName("HistoryPolicy onOverflow is EVICT_OLDEST")
  void historyPolicyEvictOldest() {
    HistoryPolicy p = entry().history().orElseThrow();
    assertSame(OnOverflow.EVICT_OLDEST, p.onOverflow());
  }

  @Test
  @DisplayName("HistoryPolicy resumeWindow is 5 minutes")
  void historyPolicyResumeWindow() {
    HistoryPolicy p = entry().history().orElseThrow();
    assertEquals(Duration.ofMinutes(5), p.resumeWindow());
  }

  @Test
  @DisplayName("recovery is empty (per slice 444b: no singular per-Resource recovery)")
  void recoveryEmpty() {
    assertTrue(entry().recovery().isEmpty());
  }

  @Test
  @DisplayName("ResourceRef is core.operation-history (regex-compliant)")
  void operationIdShape() {
    assertEquals(new ResourceRef("core.operation-history"), entry().id());
  }

  @Test
  @DisplayName("findById resolves the entry")
  void findByIdResolves() {
    ResourceCatalog catalog = new OperationHistoryResourceCatalog();
    assertTrue(
        catalog.findById(new ResourceRef("core.operation-history")).isPresent(),
        "Catalog must resolve its own entry by id");
  }

  @Test
  @DisplayName("HISTORY_CAPACITY constant is 200 (pinned)")
  void historyCapacityConstantPinned() {
    assertEquals(200, OperationHistoryResourceCatalog.HISTORY_CAPACITY);
  }
}
