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
 * Resource catalog entry surfacing the {@code metric:worker.job_queue.depth} TIMESERIES
 * Resource — slice 3a.1.4's canonical first instance.
 *
 * <p>Conforms to {@code 30-agent-workflows/01f-add-timeseries-resource.md}:
 * {@link Category#TIMESERIES} category, {@link SubscriptionMode#SSE_STREAM} (live samples
 * via the universal envelope), no {@link io.justsearch.agent.api.registry.HistoryPolicy}
 * (the {@code values[]} array on the {@link TimeseriesSnapshot} wire payload carries the
 * window implicitly).
 *
 * <p>Producer mechanics: {@code JobQueueDepthMetricProducer} (in {@code app-services})
 * polls the head's existing {@code RrdMetricStore} on a fixed cadence and broadcasts
 * fresh {@link TimeseriesSnapshot} payloads via the
 * {@link JobQueueDepthMetricChangeRegistry}. Mirrors the slice 440 {@code RuntimeContext}
 * registration pattern: catalog → registry → controller, with the bootstrap wiring
 * everything together.
 *
 * <p>Schema URL points to {@code timeseries-snapshot.v1.json}. JSON Schema baseline is not
 * shipped in this slice (per slice 3a.1.4 §"Schema regeneration"); the wire-types.ts
 * generated file is the FE-side authority.
 */
public final class JobQueueDepthMetricResourceCatalog implements ResourceCatalog {

  /** Stable namespace for the metric Resource entry — `core` per ResourceRef pattern.
   *
   * <p>Note: the slice 3a.1.4 tempdoc §B.10 wrote {@code "metric:worker.job_queue.depth"}
   * for the Resource id, but {@link ResourceRef}'s regex enforces a closed namespace
   * vocabulary: {@code core} or {@code vendor.<id>}. Trailing segments use
   * dashed-lowercase, no dots or underscores. Using {@code core.metric-worker-job-queue-depth}
   * for V1; revisit if a metric-specific namespace is justified by future shape governance.
   */
  public static final String NAMESPACE = "core";

  /** Stable id for the job-queue-depth Resource entry. */
  public static final ResourceRef JOB_QUEUE_DEPTH_ID =
      new ResourceRef("core.metric-worker-job-queue-depth");

  /** SSE endpoint advertised by the Resource entry. */
  public static final String ENDPOINT = "/api/metrics/worker.job_queue.depth/stream";

  /** Schema URL for the wire payload (TimeseriesSnapshot). */
  public static final String SCHEMA_URL =
      "https://ssot.justsearch/v1/schemas/timeseries-snapshot.v1.json";

  /** Discriminates the renderer in the FE Resource-view dispatcher. */
  public static final String KIND = "timeseries-snapshot";

  private static final List<Resource> DEFINITIONS =
      List.of(
          new Resource(
              JOB_QUEUE_DEPTH_ID,
              Presentation.of(
                  new I18nKey("registry-resource.metric.job-queue-depth.label"),
                  new I18nKey("registry-resource.metric.job-queue-depth.description")),
              SCHEMA_URL,
              Category.TIMESERIES,
              SubscriptionMode.SSE_STREAM,
              ENDPOINT,
              KIND,
              Optional.empty(), // history not declared at Resource level for TIMESERIES (window-implicit)
              Optional.empty(), // recovery not applicable at metric-level
              Provenance.core("1.0"),
              // Slice 445 substrate-extension. TIMESERIES samples are numeric;
              // no path-typed fields.
              Privacy.noPaths(),
              Set.of(),
              Set.of(),
              "",
              // Slice 481 §7 step 2: metric series are observability/profiling primitives
              // — operator-facing, not user-facing.
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
