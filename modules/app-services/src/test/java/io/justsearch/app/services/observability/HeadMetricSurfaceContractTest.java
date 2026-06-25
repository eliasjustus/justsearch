package io.justsearch.app.services.observability;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.app.api.status.AgentSessionView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.GpuStatusView;
import io.justsearch.app.api.status.TelemetryHealthView;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.StatusEndpoint;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 419 C3 V1 head-side counterpart to {@code MetricSurfaceContractTest} in
 * {@code modules/worker-services}.
 *
 * <p>For every {@link MetricDefinition} in {@link HeadGpuMetricCatalog#DEFINITIONS} declaring
 * {@link MetricDefinition#surfacedAt(StatusEndpoint, String)}, asserts the declared {@code
 * statusFieldName} matches an actual record component on the corresponding API record class.
 * Catches drift between catalog declarations and API contract for head-side catalogs.
 */
final class HeadMetricSurfaceContractTest {

  @Test
  void everySurfacedFieldExistsOnApiRecord() {
    int checked = 0;
    for (MetricDefinition def : HeadGpuMetricCatalog.DEFINITIONS) {
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
        "expected at least one surfacedAt declaration in HeadGpuMetricCatalog");
  }

  private static Class<?> recordClassFor(StatusEndpoint endpoint) {
    return switch (endpoint) {
      case CORE_INDEX_VIEW -> CoreIndexView.class;
      case AGENT_SESSION_VIEW -> AgentSessionView.class;
      case GPU_STATUS_VIEW -> GpuStatusView.class;
      case TELEMETRY_HEALTH_VIEW -> TelemetryHealthView.class;
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
