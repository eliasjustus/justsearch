/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.advisory;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.EmissionPolicy;
import io.justsearch.agent.api.registry.HistoryPolicy;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.OnOverflow;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.SubscriptionMode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resource catalog for all advisory-class Resources. Per slice 494 Q1=(b): per-class
 * Resources preserving Resource-layer policy controls. FE discovers these via
 * {@code listResources(kind=KIND_ADVISORY)} and subscribes to each class's SSE stream.
 *
 * <p>Replaces the old per-class {@code OperationCompletedAdvisoryResourceCatalog}.
 * Each advisory class gets its own Resource entry with its own endpoint, emission
 * policy, and schema URL. The unified stream is a transport detail inside
 * {@link AdvisoryChangeRegistry}; the Resource layer is the discovery abstraction.
 */
public final class AdvisoryResourceCatalog implements ResourceCatalog {

  public static final String NAMESPACE = "core";

  public static final ResourceRef OPERATION_COMPLETED_ID =
      new ResourceRef("core.advisory-operation-completed");
  public static final String OPERATION_COMPLETED_ENDPOINT =
      "/api/advisory/operation-completed/stream";
  public static final String OPERATION_COMPLETED_SCHEMA_URL =
      "https://ssot.justsearch/v1/schemas/advisory-record.v1.json";

  public static final ResourceRef HEALTH_RECOVERABLE_ID =
      new ResourceRef("core.advisory-health-recoverable");
  public static final String HEALTH_RECOVERABLE_ENDPOINT =
      "/api/advisory/health-recoverable/stream";
  public static final String HEALTH_RECOVERABLE_SCHEMA_URL =
      "https://ssot.justsearch/v1/schemas/advisory-record.v1.json";

  public static final Duration RESUME_WINDOW = Duration.ofMinutes(5);
  public static final int BUFFER_CAPACITY = 200;

  private static final List<Resource> DEFINITIONS =
      List.of(
          advisoryResource(
              OPERATION_COMPLETED_ID,
              OPERATION_COMPLETED_ENDPOINT,
              OPERATION_COMPLETED_SCHEMA_URL,
              "advisory-operation-completed",
              EmissionPolicy.persisted().withDedupeWindow(Duration.ofMinutes(1))),
          advisoryResource(
              HEALTH_RECOVERABLE_ID,
              HEALTH_RECOVERABLE_ENDPOINT,
              HEALTH_RECOVERABLE_SCHEMA_URL,
              "advisory-health-recoverable",
              EmissionPolicy.persisted().withDedupeWindow(Duration.ofMinutes(5))));

  private static Resource advisoryResource(
      ResourceRef id,
      String endpoint,
      String schemaUrl,
      String i18nSuffix,
      EmissionPolicy emissionPolicy) {
    return new Resource(
        id,
        Presentation.of(
            new I18nKey("registry-resource." + i18nSuffix + ".label"),
            new I18nKey("registry-resource." + i18nSuffix + ".description")),
        schemaUrl,
        Category.EVENT_STREAM,
        SubscriptionMode.SSE_STREAM,
        endpoint,
        Resource.KIND_ADVISORY,
        Optional.of(
            new HistoryPolicy(
                HistoryPolicy.Mode.RING_BUFFER,
                Optional.of(BUFFER_CAPACITY),
                Optional.empty(),
                OnOverflow.EVICT_OLDEST,
                RESUME_WINDOW)),
        Optional.empty(),
        Provenance.core("1.0"),
        Privacy.noPaths(),
        Set.of(),
        Set.of(),
        "",
        Audience.USER,
        List.of(),
        Optional.of(emissionPolicy));
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Resource> definitions() {
    return DEFINITIONS;
  }
}
