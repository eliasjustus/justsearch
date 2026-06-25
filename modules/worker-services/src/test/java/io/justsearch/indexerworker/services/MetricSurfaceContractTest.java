package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.app.api.status.AgentSessionView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.GpuStatusView;
import io.justsearch.app.api.status.TelemetryHealthView;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.StatusEndpoint;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 417 Phase 3b ArchUnit-style contract test.
 *
 * <p>For every {@link MetricDefinition} declaring
 * {@link MetricDefinition#surfacedAt(StatusEndpoint, String)} across the catalogs that this
 * test covers, asserts the declared {@code statusFieldName} matches an actual record component
 * on the corresponding API record class. Catches drift between catalog declarations and API
 * contract.
 *
 * <p>Tempdoc 419 C3 V1 (2026-04-26) extended the covered set with
 * {@link WorkerOpsMetricCatalog} (worker.job_queue.depth → CoreIndexView.recentJobQueueDepth).
 * Head-side catalogs (e.g., {@code HeadGpuMetricCatalog}) are validated by a parallel test in
 * the {@code modules/app-services} module.
 */
final class MetricSurfaceContractTest {

  @Test
  void everySurfacedFieldExistsOnApiRecord() {
    List<MetricDefinition> all = new ArrayList<>();
    all.addAll(IndexRuntimeMetricCatalog.DEFINITIONS);
    all.addAll(WorkerOpsMetricCatalog.DEFINITIONS);
    int checked = 0;
    for (MetricDefinition def : all) {
      if (def.statusEndpoint() == null) continue;
      Class<?> recordClass = recordClassFor(def.statusEndpoint());
      Set<String> components = recordComponentNames(recordClass);
      assertTrue(
          components.contains(def.statusFieldName()),
          "Metric '"
              + def.name()
              + "' declares surfacedAt("
              + def.statusEndpoint()
              + ", \""
              + def.statusFieldName()
              + "\") but "
              + recordClass.getSimpleName()
              + " has no such record component. Existing components: "
              + components);
      checked++;
    }
    assertTrue(
        checked > 0,
        "expected at least one surfacedAt declaration across catalogs covered by this test");
  }

  private static Class<?> recordClassFor(StatusEndpoint endpoint) {
    return switch (endpoint) {
      case CORE_INDEX_VIEW -> CoreIndexView.class;
      // Tempdoc 415: AgentSessionView surfaces agent.session.active_count.
      case AGENT_SESSION_VIEW -> AgentSessionView.class;
      // Tempdoc 419 C3 V1: GpuStatusView surfaces 30-min trends of gpu.utilization.percent
      // and gpu.memory.utilization.percent.
      case GPU_STATUS_VIEW -> GpuStatusView.class;
      // Tempdoc 419 C3 V1: TelemetryHealthView is reserved for future surfacedAt declarations
      // on TelemetryHealthState-derived metrics; today its fields are populated directly from
      // the in-process counters and not metric-catalog-backed.
      case TELEMETRY_HEALTH_VIEW -> TelemetryHealthView.class;
      // Tempdoc 412 Phase 4: enum value declared so that the switch is exhaustive. No catalog
      // currently declares surfacedAt(INFERENCE_VIEW, ...); when the llama-server Prometheus
      // scraper lands and queue/generation gauges get surfacedAt declarations, the validation
      // automatically applies.
      case INFERENCE_VIEW -> io.justsearch.app.api.status.InferenceRuntimeView.class;
    };
  }

  private static Set<String> recordComponentNames(Class<?> recordClass) {
    if (!recordClass.isRecord()) {
      fail("expected " + recordClass + " to be a record");
    }
    RecordComponent[] components = recordClass.getRecordComponents();
    return new HashSet<>(Arrays.stream(components).map(RecordComponent::getName).toList());
  }
}
