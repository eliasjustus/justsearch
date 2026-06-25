package io.justsearch.indexerworker.disambiguation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("EntityClusterStore")
class EntityClusterStoreTest {

  @TempDir Path tempDir;
  private EntityClusterStore store;

  @BeforeEach
  void setUp() throws Exception {
    store = new EntityClusterStore(tempDir.resolve("entity-clusters.db"));
    store.open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (store != null) {
      store.close();
    }
  }

  @Nested
  @DisplayName("open/close lifecycle")
  class Lifecycle {

    @Test
    @DisplayName("open creates database file")
    void openCreatesFile() {
      assertTrue(tempDir.resolve("entity-clusters.db").toFile().exists());
    }

    @Test
    @DisplayName("double close is safe")
    void doubleClose() throws Exception {
      store.close();
      store.close(); // should not throw
    }
  }

  @Nested
  @DisplayName("upsert + loadAll")
  class UpsertAndLoad {

    @Test
    @DisplayName("upsert then loadAll returns entry")
    void upsertAndLoad() throws Exception {
      store.upsert("john smith", "PERSON", "c1", "john smith", 0.95);

      List<ClusterEntry> entries = store.loadAll();
      assertEquals(1, entries.size());
      assertEquals("john smith", entries.get(0).rawForm());
      assertEquals("PERSON", entries.get(0).entityType());
      assertEquals("c1", entries.get(0).clusterId());
      assertEquals("john smith", entries.get(0).canonicalForm());
      assertEquals(0.95, entries.get(0).confidence(), 0.001);
    }

    @Test
    @DisplayName("upsert replaces existing entry")
    void upsertReplaces() throws Exception {
      store.upsert("john smith", "PERSON", "c1", "john smith", 0.8);
      store.upsert("john smith", "PERSON", "c2", "j smith", 0.9);

      List<ClusterEntry> entries = store.loadAll();
      assertEquals(1, entries.size());
      assertEquals("c2", entries.get(0).clusterId());
      assertEquals("j smith", entries.get(0).canonicalForm());
    }

    @Test
    @DisplayName("upsert preserves created_at_ms on update")
    void upsertPreservesCreatedAt() throws Exception {
      store.upsert("john smith", "PERSON", "c1", "john smith", 0.8);

      List<ClusterEntry> before = store.loadAll();
      assertEquals(1, before.size());
      long originalCreatedAt = before.get(0).createdAtMs();
      long originalUpdatedAt = before.get(0).updatedAtMs();

      // Small delay to ensure timestamp difference
      Thread.sleep(50);

      // Update the same entry with a different cluster
      store.upsert("john smith", "PERSON", "c2", "j smith", 0.9);

      List<ClusterEntry> after = store.loadAll();
      assertEquals(1, after.size());
      assertEquals(
          originalCreatedAt,
          after.get(0).createdAtMs(),
          "created_at_ms should be preserved on update");
      assertTrue(
          after.get(0).updatedAtMs() > originalUpdatedAt,
          "updated_at_ms should be newer after update");
      assertEquals("c2", after.get(0).clusterId());
      assertEquals("j smith", after.get(0).canonicalForm());
    }

    @Test
    @DisplayName("multiple entries with different types")
    void multipleTypes() throws Exception {
      store.upsert("john smith", "PERSON", "c1", "john smith", 1.0);
      store.upsert("acme corp", "ORGANIZATION", "c2", "acme corporation", 0.9);
      store.upsert("new york", "LOCATION", "c3", "new york", 1.0);

      List<ClusterEntry> entries = store.loadAll();
      assertEquals(3, entries.size());
    }
  }

  @Nested
  @DisplayName("delete operations")
  class Delete {

    @Test
    @DisplayName("deleteAll removes everything")
    void deleteAll() throws Exception {
      store.upsert("a", "PERSON", "c1", "a", 1.0);
      store.upsert("b", "PERSON", "c2", "b", 1.0);
      assertEquals(2, store.loadAll().size());

      store.deleteAll();
      assertEquals(0, store.loadAll().size());
    }

    @Test
    @DisplayName("deleteByCluster removes only matching cluster")
    void deleteByCluster() throws Exception {
      store.upsert("a", "PERSON", "c1", "a", 1.0);
      store.upsert("b", "PERSON", "c1", "a", 1.0);
      store.upsert("c", "PERSON", "c2", "c", 1.0);

      store.deleteByCluster("c1");

      List<ClusterEntry> remaining = store.loadAll();
      assertEquals(1, remaining.size());
      assertEquals("c", remaining.get(0).rawForm());
    }
  }

  @Nested
  @DisplayName("loadByCluster")
  class LoadByCluster {

    @Test
    @DisplayName("returns entries for specific cluster")
    void loadsCluster() throws Exception {
      store.upsert("john smith", "PERSON", "c1", "john smith", 1.0);
      store.upsert("smith john", "PERSON", "c1", "john smith", 0.85);
      store.upsert("acme corp", "ORGANIZATION", "c2", "acme corp", 1.0);

      List<ClusterEntry> cluster = store.loadByCluster("c1");
      assertEquals(2, cluster.size());
    }

    @Test
    @DisplayName("returns empty for nonexistent cluster")
    void emptyForMissing() throws Exception {
      assertEquals(0, store.loadByCluster("nonexistent").size());
    }
  }

  @Nested
  @DisplayName("countClusters")
  class CountClusters {

    @Test
    @DisplayName("counts distinct clusters per entity type")
    void counts() throws Exception {
      store.upsert("a", "PERSON", "c1", "a", 1.0);
      store.upsert("b", "PERSON", "c1", "a", 1.0);
      store.upsert("c", "PERSON", "c2", "c", 1.0);
      store.upsert("d", "ORGANIZATION", "c3", "d", 1.0);

      Map<String, Long> counts = store.countClusters();
      assertEquals(2L, counts.get("PERSON"));
      assertEquals(1L, counts.get("ORGANIZATION"));
    }

    @Test
    @DisplayName("empty store returns empty map")
    void emptyStore() throws Exception {
      assertTrue(store.countClusters().isEmpty());
    }
  }
}
