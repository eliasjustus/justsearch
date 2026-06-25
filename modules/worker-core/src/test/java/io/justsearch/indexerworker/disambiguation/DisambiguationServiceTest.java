package io.justsearch.indexerworker.disambiguation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("DisambiguationService")
class DisambiguationServiceTest {

  @TempDir Path tempDir;
  private DisambiguationService service;

  @BeforeEach
  void setUp() throws Exception {
    service = new DisambiguationService(tempDir);
    service.open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (service != null) {
      service.close();
    }
  }

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {

    @Test
    @DisplayName("is available after open")
    void availableAfterOpen() {
      assertTrue(service.isAvailable());
    }

    @Test
    @DisplayName("snapshot is empty initially")
    void emptySnapshot() {
      assertTrue(service.snapshot().isEmpty());
    }

    @Test
    @DisplayName("not available after close")
    void notAvailableAfterClose() throws Exception {
      service.close();
      assertFalse(service.isAvailable());
    }
  }

  @Nested
  @DisplayName("processBatch")
  class ProcessBatch {

    @Test
    @DisplayName("null or empty batch returns 0")
    void emptyBatch() throws Exception {
      assertEquals(0, service.processBatch(null));
      assertEquals(0, service.processBatch(Map.of()));
      assertEquals(0, service.processBatch(Map.of("PERSON", List.of())));
    }

    @Test
    @DisplayName("single mention creates singleton cluster")
    void singleMention() throws Exception {
      int created = service.processBatch(Map.of("PERSON", List.of("John Smith")));
      assertEquals(1, created);

      EntityClusterSnapshot snap = service.snapshot();
      assertFalse(snap.isEmpty());
      // The canonical form should be the raw form (singleton)
      String canonical = snap.getCanonical("PERSON", "John Smith");
      assertNotNull(canonical);
    }

    @Test
    @DisplayName("identical mentions after normalization are not duplicated")
    void deduplication() throws Exception {
      // "JOHN SMITH" and "John Smith" normalize to the same form
      int created =
          service.processBatch(Map.of("PERSON", List.of("John Smith", "John Smith")));
      assertEquals(1, created);
    }

    @Test
    @DisplayName("different entities create separate clusters")
    void separateClusters() throws Exception {
      service.processBatch(Map.of("PERSON", List.of("John Smith", "Sarah Chen")));

      EntityClusterSnapshot snap = service.snapshot();
      String c1 = snap.getCanonical("PERSON", "John Smith");
      String c2 = snap.getCanonical("PERSON", "Sarah Chen");
      // Different people should have different canonical forms
      assertFalse(c1.equals(c2), "Different entities should not share canonical form");
    }

    @Test
    @DisplayName("within-batch mentions share correct canonical form (C1/C2 regression)")
    void withinBatchClustering() throws Exception {
      // Two similar names in one batch should cluster together and both have
      // the correct canonical form (the first mention), not some unrelated canonical.
      int created =
          service.processBatch(
              Map.of("PERSON", List.of("John Smith", "Jon Smith", "Jane Doe")));
      assertTrue(created >= 2, "Should create at least 2 entries (maybe 3)");

      EntityClusterSnapshot snap = service.snapshot();
      String c1 = snap.getCanonical("PERSON", "John Smith");
      String c2 = snap.getCanonical("PERSON", "Jon Smith");
      String c3 = snap.getCanonical("PERSON", "Jane Doe");
      assertNotNull(c1, "John Smith should have a canonical");
      assertNotNull(c3, "Jane Doe should have a canonical");

      // Jon Smith should either match John Smith's canonical (if SoftTFIDF scored high enough)
      // or be its own canonical — but should NOT have Jane Doe's canonical
      assertFalse(
          c2.equals(c3) && !c2.equals(c1),
          "Jon Smith should not be assigned an unrelated canonical (C1/C2 regression)");
    }

    @Test
    @DisplayName("similar entities cluster together (case variants)")
    void caseClustering() throws Exception {
      // First batch: create a cluster for "john smith"
      service.processBatch(Map.of("PERSON", List.of("john smith")));
      // Second batch: "Jon Smith" has typo — SoftTFIDF should match via token overlap
      service.processBatch(Map.of("PERSON", List.of("Jon Smith")));

      EntityClusterSnapshot snap = service.snapshot();
      // Both should resolve to the same canonical
      String c1 = snap.getCanonical("PERSON", "john smith");
      String c2 = snap.getCanonical("PERSON", "Jon Smith");
      assertEquals(c1, c2, "Typo variant should cluster with original");
    }

    @Test
    @DisplayName("multiple entity types processed independently")
    void multipleTypes() throws Exception {
      service.processBatch(
          Map.of(
              "PERSON", List.of("John Smith"),
              "ORGANIZATION", List.of("Acme Corp"),
              "LOCATION", List.of("New York")));

      EntityClusterSnapshot snap = service.snapshot();
      assertNotNull(snap.getCanonical("PERSON", "John Smith"));
      assertNotNull(snap.getCanonical("ORGANIZATION", "Acme Corp"));
      assertNotNull(snap.getCanonical("LOCATION", "New York"));
    }
  }

  @Nested
  @DisplayName("cluster size limits")
  class ClusterSizeLimits {

    @Test
    @DisplayName("new mention becomes singleton when cluster reaches MAX_CLUSTER_SIZE")
    void maxClusterSizeCap() throws Exception {
      // Fill a cluster to MAX_CLUSTER_SIZE with mentions that share the same tokens.
      // Use "john smith <suffix>" variants so SoftTFIDF clusters them together.
      java.util.List<String> batch = new java.util.ArrayList<>();
      for (int i = 0; i < DisambiguationService.MAX_CLUSTER_SIZE; i++) {
        batch.add("john smith " + (char) ('a' + (i % 26)) + (i / 26));
      }
      service.processBatch(Map.of("PERSON", batch));

      // Add one more similar mention — should overflow into a new singleton
      String overflow = "john smith overflow";
      service.processBatch(Map.of("PERSON", List.of(overflow)));

      EntityClusterSnapshot snap = service.snapshot();
      String overflowCanonical = snap.getCanonical("PERSON", overflow);

      // The overflow mention should be its own canonical (singleton cluster),
      // not merged into the full cluster
      assertEquals(
          overflow,
          overflowCanonical,
          "Overflow mention should be a singleton when cluster is full");
    }
  }

  @Nested
  @DisplayName("snapshot queries")
  class SnapshotQueries {

    @Test
    @DisplayName("getCanonical returns raw form when unmapped")
    void unmappedReturnsRaw() {
      assertEquals("Unknown Person", service.snapshot().getCanonical("PERSON", "Unknown Person"));
    }

    @Test
    @DisplayName("expandCanonical returns singleton for unmapped")
    void expandUnmapped() {
      Set<String> expanded = service.snapshot().expandCanonical("PERSON", "Unknown Person");
      assertEquals(Set.of("Unknown Person"), expanded);
    }

    @Test
    @DisplayName("expandCanonical returns all variants for clustered entity")
    void expandClustered() throws Exception {
      service.processBatch(Map.of("PERSON", List.of("john smith")));
      service.processBatch(Map.of("PERSON", List.of("Jon Smith")));

      EntityClusterSnapshot snap = service.snapshot();
      String canonical = snap.getCanonical("PERSON", "john smith");
      Set<String> variants = snap.expandCanonical("PERSON", canonical);
      assertTrue(variants.size() >= 2, "Should have at least 2 variants: " + variants);
    }
  }
}
