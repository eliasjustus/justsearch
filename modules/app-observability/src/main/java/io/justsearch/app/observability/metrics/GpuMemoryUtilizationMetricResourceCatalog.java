/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.metrics;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.SubscriptionMode;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resource catalog entry surfacing the {@code gpu.memory.utilization.percent} TIMESERIES
 * Resource — slice 3a.1.4b cohort follow-up.
 */
public final class GpuMemoryUtilizationMetricResourceCatalog implements ResourceCatalog {

  public static final String NAMESPACE = "core";

  public static final ResourceRef GPU_MEMORY_UTILIZATION_ID =
      new ResourceRef("core.metric-gpu-memory-utilization-percent");

  public static final String ENDPOINT = "/api/metrics/gpu.memory.utilization.percent/stream";

  public static final String SCHEMA_URL =
      "https://ssot.justsearch/v1/schemas/timeseries-snapshot.v1.json";

  public static final String KIND = "timeseries-snapshot";

  private static final List<Resource> DEFINITIONS =
      List.of(
          new Resource(
              GPU_MEMORY_UTILIZATION_ID,
              Presentation.of(
                  new I18nKey("registry-resource.metric.gpu-memory-utilization.label"),
                  new I18nKey("registry-resource.metric.gpu-memory-utilization.description")),
              SCHEMA_URL,
              Category.TIMESERIES,
              SubscriptionMode.SSE_STREAM,
              ENDPOINT,
              KIND,
              Optional.empty(),
              Optional.empty(),
              Provenance.core("1.0"),
              // Slice 445 substrate-extension.
              Privacy.noPaths(),
              Set.of(),
              Set.of(),
              "",
              // Slice 481 §7 step 2: metric series are operator-facing observability.
              Audience.OPERATOR));

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Resource> definitions() {
    return DEFINITIONS;
  }
}
