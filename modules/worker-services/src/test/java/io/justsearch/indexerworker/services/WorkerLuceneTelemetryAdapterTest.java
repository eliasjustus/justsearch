package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.SwapReason;
import io.justsearch.adapters.lucene.runtime.ValidationReason;
import io.justsearch.indexerworker.services.IndexRuntimeTags.CommitTags;
import io.justsearch.indexerworker.services.IndexRuntimeTags.SwapTags;
import io.justsearch.indexerworker.services.IndexRuntimeTags.ValidationTags;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 417 Phase 1 + F8 migration: validates that every {@code TelemetryEvents} method on the
 * production adapter emits to the expected {@code index.runtime.*} metric via the typed catalog.
 *
 * <p>Migrated from real {@code LocalTelemetry} + NDJSON parsing (Phase 1) to {@link
 * TestMetricRegistry} (F8): assertions read typed counter / histogram values directly without
 * hitting disk or parsing JSON. The wire-format guarantee continues to be tested by
 * {@link IndexRuntimeWireFormatRegressionTest}.
 */
final class WorkerLuceneTelemetryAdapterTest {

  private TestMetricRegistry registry;
  private IndexRuntimeMetricCatalog catalog;
  private WorkerLuceneTelemetryAdapter adapter;

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(IndexRuntimeMetricCatalog.DEFINITIONS);
    catalog = new IndexRuntimeMetricCatalog(registry);
    adapter = new WorkerLuceneTelemetryAdapter(catalog);
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  @Test
  void onHardDeleteIncrementsCounterByOne() {
    adapter.onHardDelete();
    assertEquals(
        1L, registry.counterValue(IndexRuntimeMetricCatalog.HARD_DELETE_TOTAL, EmptyTags.INSTANCE));
  }

  @Test
  void onHardDeleteWithCountAddsExactly() {
    adapter.onHardDelete(7);
    assertEquals(
        7L, registry.counterValue(IndexRuntimeMetricCatalog.HARD_DELETE_TOTAL, EmptyTags.INSTANCE));
  }

  @Test
  void onSoftDeleteAddsCount() {
    adapter.onSoftDelete(3);
    assertEquals(
        3L, registry.counterValue(IndexRuntimeMetricCatalog.SOFT_DELETE_TOTAL, EmptyTags.INSTANCE));
  }

  @Test
  void onSoftDeleteIgnoresZeroOrNegative() {
    adapter.onSoftDelete(0);
    adapter.onSoftDelete(-5);
    assertEquals(
        0L, registry.counterValue(IndexRuntimeMetricCatalog.SOFT_DELETE_TOTAL, EmptyTags.INSTANCE));
  }

  @Test
  void onBackpressureIncrementsCounter() {
    adapter.onBackpressure();
    adapter.onBackpressure();
    assertEquals(
        2L, registry.counterValue(IndexRuntimeMetricCatalog.BACKPRESSURE_TOTAL, EmptyTags.INSTANCE));
  }

  @Test
  void onCommitRecordsHistogramWithReasonTag() {
    adapter.onCommit(42L, CommitReason.DRAIN);
    assertEquals(
        1L,
        registry.histogramCount(
            IndexRuntimeMetricCatalog.COMMIT_MS, CommitTags.of(CommitReason.DRAIN)));
  }

  @Test
  void onCommitNoReasonDefaultsToUnknown() {
    adapter.onCommit(100L);
    assertEquals(
        1L,
        registry.histogramCount(
            IndexRuntimeMetricCatalog.COMMIT_MS, CommitTags.of(CommitReason.UNKNOWN)));
  }

  @Test
  void onCommitNullReasonNormalisedToUnknown() {
    adapter.onCommit(50L, (CommitReason) null);
    assertEquals(
        1L,
        registry.histogramCount(
            IndexRuntimeMetricCatalog.COMMIT_MS, CommitTags.of(CommitReason.UNKNOWN)));
  }

  @Test
  void onValidationFailureIncrementsCounterWithReason() {
    adapter.onValidationFailure(ValidationReason.MISSING_ID_FIELD);
    adapter.onValidationFailure(ValidationReason.MISSING_ID_FIELD);
    adapter.onValidationFailure(ValidationReason.VECTOR_NOT_NUMERIC);
    assertEquals(
        2L,
        registry.counterValue(
            IndexRuntimeMetricCatalog.VALIDATION_FAILURE_TOTAL,
            ValidationTags.of(ValidationReason.MISSING_ID_FIELD)));
    assertEquals(
        1L,
        registry.counterValue(
            IndexRuntimeMetricCatalog.VALIDATION_FAILURE_TOTAL,
            ValidationTags.of(ValidationReason.VECTOR_NOT_NUMERIC)));
  }

  @Test
  void onSwapStartIncrementsCounterWithReason() {
    adapter.onSwapStart(SwapReason.ADMIN_TRIGGERED);
    assertEquals(
        1L,
        registry.counterValue(
            IndexRuntimeMetricCatalog.SWAP_STARTED_TOTAL,
            SwapTags.of(SwapReason.ADMIN_TRIGGERED)));
  }

  @Test
  void onSwapCompleteRecordsHistogramWithReason() {
    adapter.onSwapComplete(91L, SwapReason.ADMIN_TRIGGERED);
    assertEquals(
        1L,
        registry.histogramCount(
            IndexRuntimeMetricCatalog.SWAP_DURATION_MS,
            SwapTags.of(SwapReason.ADMIN_TRIGGERED)));
  }

  @Test
  void onDrainTimeoutIncrementsCounter() {
    adapter.onDrainTimeout(5_000L, 12L);
    assertEquals(
        1L,
        registry.counterValue(IndexRuntimeMetricCatalog.DRAIN_TIMEOUT_TOTAL, EmptyTags.INSTANCE));
  }

  @Test
  void onWriteBarrierContentionConvertsNanosToMicros() {
    adapter.onWriteBarrierContention(2_500L); // 2.5 us
    adapter.onWriteBarrierContention(150_000L); // 150 us
    assertEquals(
        2L,
        registry.histogramCount(
            IndexRuntimeMetricCatalog.WRITE_BARRIER_WAIT_US, EmptyTags.INSTANCE));
  }

  @Test
  void allNineMetricNamesAreEmittedAtLeastOnce() {
    adapter.onHardDelete(1);
    adapter.onSoftDelete(1);
    adapter.onBackpressure();
    adapter.onCommit(1L, CommitReason.DRAIN);
    adapter.onValidationFailure(ValidationReason.MISSING_ID_FIELD);
    adapter.onSwapStart(SwapReason.ADMIN_TRIGGERED);
    adapter.onSwapComplete(1L, SwapReason.ADMIN_TRIGGERED);
    adapter.onDrainTimeout(1L, 0L);
    adapter.onWriteBarrierContention(1_000L);

    var emitted = registry.emittedNames();
    String[] names = {
      IndexRuntimeMetricCatalog.HARD_DELETE_TOTAL,
      IndexRuntimeMetricCatalog.SOFT_DELETE_TOTAL,
      IndexRuntimeMetricCatalog.BACKPRESSURE_TOTAL,
      IndexRuntimeMetricCatalog.COMMIT_MS,
      IndexRuntimeMetricCatalog.VALIDATION_FAILURE_TOTAL,
      IndexRuntimeMetricCatalog.SWAP_STARTED_TOTAL,
      IndexRuntimeMetricCatalog.SWAP_DURATION_MS,
      IndexRuntimeMetricCatalog.DRAIN_TIMEOUT_TOTAL,
      IndexRuntimeMetricCatalog.WRITE_BARRIER_WAIT_US,
    };
    for (String name : names) {
      assertTrue(emitted.contains(name), "expected emitted name " + name + "; got " + emitted);
    }
  }
}
