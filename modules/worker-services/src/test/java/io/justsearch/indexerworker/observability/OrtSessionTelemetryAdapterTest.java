package io.justsearch.indexerworker.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.observability.OrtSessionTags.AssemblerFailureTags;
import io.justsearch.indexerworker.observability.OrtSessionTags.ConsumerOutcomeTags;
import io.justsearch.indexerworker.observability.OrtSessionTags.ConsumerTags;
import io.justsearch.indexerworker.observability.OrtSessionTags.GpuInitFailureTags;
import io.justsearch.indexerworker.observability.OrtSessionTags.RecoveryTags;
import io.justsearch.ort.telemetry.AssemblerEvent;
import io.justsearch.ort.telemetry.AssemblerFailureKind;
import io.justsearch.ort.telemetry.CpuRecreateCause;
import io.justsearch.ort.telemetry.FailureCause;
import io.justsearch.ort.telemetry.Outcome;
import io.justsearch.ort.telemetry.TransitionReason;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Per-permit assertion tests for {@link OrtSessionTelemetryAdapter}. Pairs with the compile-time
 * exhaustive-switch check on the adapter to provide belt-and-suspenders drift prevention. Adding
 * a {@link TransitionReason} or {@link AssemblerEvent} permit without a test case here weakens
 * the contract — author the test alongside the adapter case in the same change.
 */
@DisplayName("OrtSessionTelemetryAdapter — per-permit dispatch")
final class OrtSessionTelemetryAdapterTest {

  private TestMetricRegistry registry;
  private OrtSessionMetricCatalog catalog;
  private OrtSessionTelemetryAdapter adapter;

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(OrtSessionMetricCatalog.DEFINITIONS);
    catalog = new OrtSessionMetricCatalog(registry);
    adapter = new OrtSessionTelemetryAdapter(catalog, List.of("embed", "splade"));
  }

  @AfterEach
  void tearDown() {
    registry.close();
  }

  @Test
  @DisplayName("GpuInitialized → gpu_init_total{outcome=success}")
  void gpuInitialized() {
    adapter.onTransition(new TransitionReason.GpuInitialized("embed"));
    assertEquals(
        1L,
        registry.counterValue(
            OrtSessionMetricCatalog.GPU_INIT_TOTAL,
            new ConsumerOutcomeTags("embed", Outcome.SUCCESS)));
  }

  @Test
  @DisplayName("GpuInitFailed → gpu_init_total{outcome=failure} + gpu_init_failure_total{cause}")
  void gpuInitFailed() {
    adapter.onTransition(new TransitionReason.GpuInitFailed("embed", FailureCause.CUDA_UNAVAILABLE));
    assertEquals(
        1L,
        registry.counterValue(
            OrtSessionMetricCatalog.GPU_INIT_TOTAL,
            new ConsumerOutcomeTags("embed", Outcome.FAILURE)));
    assertEquals(
        1L,
        registry.counterValue(
            OrtSessionMetricCatalog.GPU_INIT_FAILURE_TOTAL,
            new GpuInitFailureTags("embed", FailureCause.CUDA_UNAVAILABLE)));
  }

  @Test
  @DisplayName("GpuReleaseCompleted → release_total{outcome=success}")
  void gpuReleaseCompleted() {
    adapter.onTransition(new TransitionReason.GpuReleaseCompleted("embed"));
    assertEquals(
        1L,
        registry.counterValue(
            OrtSessionMetricCatalog.RELEASE_TOTAL,
            new ConsumerOutcomeTags("embed", Outcome.SUCCESS)));
  }

  @Test
  @DisplayName("GpuReleaseFailed → release_total{outcome=failure}")
  void gpuReleaseFailed() {
    adapter.onTransition(new TransitionReason.GpuReleaseFailed("embed"));
    assertEquals(
        1L,
        registry.counterValue(
            OrtSessionMetricCatalog.RELEASE_TOTAL,
            new ConsumerOutcomeTags("embed", Outcome.FAILURE)));
  }

  @Test
  @DisplayName("GpuFallbackTaken → fallback_total{consumer}")
  void gpuFallbackTaken() {
    adapter.onTransition(new TransitionReason.GpuFallbackTaken("embed"));
    assertEquals(
        1L,
        registry.counterValue(
            OrtSessionMetricCatalog.FALLBACK_TOTAL, new ConsumerTags("embed")));
  }

  @Test
  @DisplayName("CpuSessionRecreated → recovery_total{cause}")
  void cpuSessionRecreated() {
    adapter.onTransition(
        new TransitionReason.CpuSessionRecreated("embed", CpuRecreateCause.BFC_ARENA_FAILURE));
    assertEquals(
        1L,
        registry.counterValue(
            OrtSessionMetricCatalog.RECOVERY_TOTAL,
            new RecoveryTags("embed", CpuRecreateCause.BFC_ARENA_FAILURE)));
  }

  @Test
  @DisplayName("GpuRetryAttempted → retry_total{consumer} + retry_interval_ms{consumer}")
  void gpuRetryAttempted() {
    adapter.onTransition(new TransitionReason.GpuRetryAttempted("embed", 60_000L));
    assertEquals(
        1L,
        registry.counterValue(
            OrtSessionMetricCatalog.RETRY_TOTAL, new ConsumerTags("embed")));
    assertEquals(
        1L,
        registry.histogramCount(
            OrtSessionMetricCatalog.RETRY_INTERVAL_MS, new ConsumerTags("embed")));
  }

  @Test
  @DisplayName("AssemblerEvent.Failed → assembler_failure_total{kind}")
  void assemblerFailed() {
    adapter.onAssemblerEvent(
        new AssemblerEvent.Failed("stress", AssemblerFailureKind.NULL_VARIANT));
    assertEquals(
        1L,
        registry.counterValue(
            OrtSessionMetricCatalog.ASSEMBLER_FAILURE_TOTAL,
            new AssemblerFailureTags("stress", AssemblerFailureKind.NULL_VARIANT)));
  }

  @Test
  @DisplayName("onSemaphoreWait records into semaphore_wait_us histogram (no accelerator tag)")
  void onSemaphoreWait() {
    adapter.onSemaphoreWait("embed", 42L);
    assertEquals(
        1L,
        registry.histogramCount(
            OrtSessionMetricCatalog.SEMAPHORE_WAIT_US, new ConsumerTags("embed")));
  }

  @Test
  @DisplayName("onSemaphoreWait projects unknown consumer correctly")
  void onSemaphoreWaitUnknownConsumer() {
    adapter.onSemaphoreWait("unknown_consumer", 7L);
    assertEquals(
        1L,
        registry.histogramCount(
            OrtSessionMetricCatalog.SEMAPHORE_WAIT_US, new ConsumerTags("unknown_consumer")));
  }

  @Test
  @DisplayName("multiple consumers tracked independently")
  void multipleConsumersIndependent() {
    adapter.onTransition(new TransitionReason.GpuInitialized("embed"));
    adapter.onTransition(new TransitionReason.GpuInitialized("splade"));
    adapter.onTransition(new TransitionReason.GpuInitialized("embed"));
    assertEquals(
        2L,
        registry.counterValue(
            OrtSessionMetricCatalog.GPU_INIT_TOTAL,
            new ConsumerOutcomeTags("embed", Outcome.SUCCESS)));
    assertEquals(
        1L,
        registry.counterValue(
            OrtSessionMetricCatalog.GPU_INIT_TOTAL,
            new ConsumerOutcomeTags("splade", Outcome.SUCCESS)));
  }

  @Test
  @DisplayName("emit produces the expected metric name")
  void emittedNames() {
    adapter.onTransition(new TransitionReason.GpuInitialized("embed"));
    assertTrue(registry.emittedNames().contains(OrtSessionMetricCatalog.GPU_INIT_TOTAL));
  }

  @Test
  @DisplayName("forAllRoles factory derives consumer set from EncoderRole.values()")
  void forAllRolesFactory() {
    OrtSessionTelemetryAdapter fromFactory = OrtSessionTelemetryAdapter.forAllRoles(catalog);
    // Sanity: emitting through the factory adapter for any EncoderRole consumer name resolves
    // a cached ConsumerTags (no allocation on the hot path).
    fromFactory.onSemaphoreWait("embed", 1L);
    fromFactory.onSemaphoreWait("bgem3", 1L);
    fromFactory.onSemaphoreWait("citation", 1L);
    assertEquals(
        1L,
        registry.histogramCount(
            OrtSessionMetricCatalog.SEMAPHORE_WAIT_US, new ConsumerTags("embed")));
    assertEquals(
        1L,
        registry.histogramCount(
            OrtSessionMetricCatalog.SEMAPHORE_WAIT_US, new ConsumerTags("bgem3")));
    assertEquals(
        1L,
        registry.histogramCount(
            OrtSessionMetricCatalog.SEMAPHORE_WAIT_US, new ConsumerTags("citation")));
  }
}
