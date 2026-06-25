package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.TelemetryEvents;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IndexingCoordinatorTest {

  private RuntimeSession session;
  private List<String> telemetryReasons;
  private int backpressureCount;

  private TelemetryEvents telemetrySpy;

  @BeforeEach
  void setUp() {
    telemetryReasons = new ArrayList<>();
    backpressureCount = 0;
    telemetrySpy =
        new TelemetryEvents() {
          @Override
          public void onHardDelete() {}

          @Override
          public void onHardDelete(int count) {}

          @Override
          public void onSoftDelete(int count) {}

          @Override
          public void onBackpressure() {
            backpressureCount++;
          }

          @Override
          public void onCommit(long latencyMs) {}

          @Override
          public void onValidationFailure(ValidationReason reason) {
            telemetryReasons.add(reason.wireValue());
          }
        };
    session = new RuntimeSession(IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)));
    session.telemetryEvents = telemetrySpy;
  }

  private IndexingCoordinator coordinator(ValidationMode mode, long maxDepth) {
    session.validationMode = mode;
    session.maxQueueDepth = maxDepth;
    return new IndexingCoordinator(session, () -> null);
  }

  // ---- validate() tests ----

  @Test
  void validateAcceptsValidDocument() {
    IndexingCoordinator coord = coordinator(ValidationMode.FAIL, 100);
    IndexDocument doc =
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "doc-1", SchemaFields.DOC_UID, "doc-1#0"));
    assertDoesNotThrow(() -> coord.validate(doc));
    assertTrue(telemetryReasons.isEmpty());
  }

  @Test
  void validateThrowsOnMissingIdInFailMode() {
    IndexingCoordinator coord = coordinator(ValidationMode.FAIL, 100);
    IndexDocument doc = new IndexDocument(Map.of(SchemaFields.DOC_UID, "uid-only"));
    IndexRuntimeIOException ex =
        assertThrows(IndexRuntimeIOException.class, () -> coord.validate(doc));
    assertTrue(ex.getMessage().contains("missing_id_field"));
    assertEquals(List.of("missing_id_field"), telemetryReasons);
  }

  @Test
  void validateWarnsOnMissingIdInWarnMode() {
    IndexingCoordinator coord = coordinator(ValidationMode.WARN, 100);
    IndexDocument doc = new IndexDocument(Map.of(SchemaFields.DOC_UID, "uid-only"));
    assertDoesNotThrow(() -> coord.validate(doc));
    assertEquals(List.of("missing_id_field"), telemetryReasons);
  }

  @Test
  void validateThrowsOnMissingUidInFailMode() {
    IndexingCoordinator coord = coordinator(ValidationMode.FAIL, 100);
    IndexDocument doc = new IndexDocument(Map.of(SchemaFields.DOC_ID, "id-only"));
    IndexRuntimeIOException ex =
        assertThrows(IndexRuntimeIOException.class, () -> coord.validate(doc));
    assertTrue(ex.getMessage().contains("missing_uid_field"));
    assertTrue(telemetryReasons.contains("missing_uid_field"));
  }

  @Test
  void validateWarnsOnMissingUidInWarnMode() {
    IndexingCoordinator coord = coordinator(ValidationMode.WARN, 100);
    IndexDocument doc = new IndexDocument(Map.of(SchemaFields.DOC_ID, "id-only"));
    assertDoesNotThrow(() -> coord.validate(doc));
    assertTrue(telemetryReasons.contains("missing_uid_field"));
  }

  @Test
  void validateThrowsOnBlankIdInFailMode() {
    IndexingCoordinator coord = coordinator(ValidationMode.FAIL, 100);
    IndexDocument doc =
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "  ", SchemaFields.DOC_UID, "uid-1"));
    IndexRuntimeIOException ex =
        assertThrows(IndexRuntimeIOException.class, () -> coord.validate(doc));
    assertTrue(ex.getMessage().contains("missing_id_field"));
  }

  @Test
  void validateThrowsOnBlankUidInFailMode() {
    IndexingCoordinator coord = coordinator(ValidationMode.FAIL, 100);
    IndexDocument doc =
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "doc-1", SchemaFields.DOC_UID, ""));
    IndexRuntimeIOException ex =
        assertThrows(IndexRuntimeIOException.class, () -> coord.validate(doc));
    assertTrue(ex.getMessage().contains("missing_uid_field"));
  }

  @Test
  void validateThrowsOnVectorDimensionMismatch() {
    IndexingCoordinator coord = coordinator(ValidationMode.FAIL, 100);
    float[] wrongDim = new float[] {1.0f, 2.0f}; // 2 != catalog dimension 4
    IndexDocument doc =
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                "vector", wrongDim));
    IndexRuntimeIOException ex =
        assertThrows(IndexRuntimeIOException.class, () -> coord.validate(doc));
    assertTrue(ex.getMessage().contains("vector_dimension_mismatch"));
    assertTrue(telemetryReasons.contains("vector_dimension_mismatch"));
  }

  @Test
  void validateAcceptsCorrectVectorDimension() {
    IndexingCoordinator coord = coordinator(ValidationMode.FAIL, 100);
    float[] correctDim = new float[] {1.0f, 2.0f, 3.0f, 4.0f}; // matches catalog dimension 4
    IndexDocument doc =
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                "vector", correctDim));
    assertDoesNotThrow(() -> coord.validate(doc));
    assertTrue(telemetryReasons.isEmpty());
  }

  @Test
  void validateThrowsOnNonNumericVector() {
    IndexingCoordinator coord = coordinator(ValidationMode.FAIL, 100);
    IndexDocument doc =
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                "vector", "not-a-vector"));
    IndexRuntimeIOException ex =
        assertThrows(IndexRuntimeIOException.class, () -> coord.validate(doc));
    assertTrue(ex.getMessage().contains("vector_not_numeric"));
    assertTrue(telemetryReasons.contains("vector_not_numeric"));
  }

  // ---- guardBackpressure() tests ----

  @Test
  void guardBackpressureAcceptsWithinLimit() {
    IndexingCoordinator coord = coordinator(ValidationMode.FAIL, 10);
    session.queueDepth.set(9);
    assertDoesNotThrow(coord::guardBackpressure);
    assertEquals(10, session.queueDepth.get());
    assertEquals(0, backpressureCount);
  }

  @Test
  void guardBackpressureRejectsOverLimit() {
    IndexingCoordinator coord = coordinator(ValidationMode.FAIL, 10);
    session.queueDepth.set(10);
    IndexRuntimeIOException ex =
        assertThrows(IndexRuntimeIOException.class, coord::guardBackpressure);
    assertTrue(ex.getMessage().contains("queue_depth_exceeded"));
    assertEquals(10, session.queueDepth.get(), "queue depth should be rolled back");
    assertEquals(1, backpressureCount);
  }

  @Test
  void guardBackpressureRollsBackOnReject() {
    IndexingCoordinator coord = coordinator(ValidationMode.FAIL, 5);
    session.queueDepth.set(5);
    assertThrows(IndexRuntimeIOException.class, coord::guardBackpressure);
    assertEquals(5, session.queueDepth.get(), "queue depth should be restored to original value");
  }
}
