package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.app.services.worker.IpcTags.CircuitBreakerStateChangeTags;
import io.justsearch.app.services.worker.IpcTags.WorkerRestartTags;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.RrdArchive;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tempdoc 417 F4: smoke test for {@link IpcMetricCatalog}. */
final class IpcMetricCatalogSmokeTest {

  private TestMetricRegistry registry;
  private IpcMetricCatalog catalog;

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(IpcMetricCatalog.DEFINITIONS);
    catalog = new IpcMetricCatalog(registry);
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  @Test
  void constructsAndEmits() {
    assertNotNull(catalog.portDiscoveryMs);
    assertNotNull(catalog.workerRestart);
    assertNotNull(catalog.circuitBreakerStateChange);

    catalog.portDiscoveryMs.record(123L, EmptyTags.INSTANCE);
    catalog.workerRestart.increment(new WorkerRestartTags(WorkerRestartOutcome.SUCCESS));
    catalog.circuitBreakerStateChange.increment(
        new CircuitBreakerStateChangeTags(CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN));

    assertEquals(1L, registry.histogramCount(IpcMetricCatalog.PORT_DISCOVERY_MS, EmptyTags.INSTANCE));
    assertEquals(
        1L,
        registry.counterValue(
            IpcMetricCatalog.WORKER_RESTART, new WorkerRestartTags(WorkerRestartOutcome.SUCCESS)));
  }

  @Test
  void noopCatalogDoesNotThrow() {
    var noop = IpcMetricCatalog.noop();
    assertDoesNotThrow(() -> noop.portDiscoveryTimeout.increment(EmptyTags.INSTANCE));
  }

  @Test
  void grpcReconnectDeclaresStandardArchive() {
    // Tempdoc 417 critical-analysis A1: ipc.grpc.reconnect must be archived to RRD;
    // it was in the original CURATED_METRICS list pre-Phase 3b.
    MetricDefinition def =
        IpcMetricCatalog.DEFINITIONS.stream()
            .filter(d -> d.name().equals(IpcMetricCatalog.GRPC_RECONNECT))
            .findFirst()
            .orElseThrow();
    assertEquals(RrdArchive.STANDARD, def.rrdArchive());
  }
}
