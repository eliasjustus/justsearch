/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.indexing;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.PathPolicy;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.SubscriptionMode;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resource catalog entry for the {@code core.indexing-jobs} TABULAR Resource
 * (slice 445). The first {@link Category#TABULAR} instance — establishes the
 * substrate pattern for collection-with-deltas resources.
 *
 * <p>Wire shape:
 *
 * <ul>
 *   <li><b>Subscription</b>: {@link SubscriptionMode#SSE_STREAM} on
 *       {@link #ENDPOINT}. Initial frame is the snapshot; subsequent frames
 *       are typed deltas (Insert / Update / Delete) discriminated within the
 *       universal SSE envelope.
 *   <li><b>Privacy</b>: {@link PathPolicy#HASHED_REQUIRES_RESOLVER}. Every
 *       row carries SHA-256 hex {@code pathHash}, never raw paths. UI consumers
 *       resolve a hash to a path via {@link #PATH_HASH_RESOLVER} when a user
 *       gesture demands it (ADR-0028 / LibraryResolveHashOnlyCallerPin).
 *   <li><b>Item Operations</b>: row-level actions invoked with a row's
 *       {@code pathHash} as input. {@code core.cancel-indexing-job} +
 *       {@code core.retry-indexing-job}.
 *   <li><b>Collection Operations</b>: aggregate actions. The existing
 *       {@code core.clear-failed-jobs} is lifted to TABULAR composition; no
 *       behavior change, only declarative linkage.
 * </ul>
 */
public final class IndexingJobsResourceCatalog implements ResourceCatalog {

  /** Shared namespace with other core catalogs. */
  public static final String NAMESPACE = "core";

  /** Stable id for the indexing-jobs Resource. */
  public static final ResourceRef INDEXING_JOBS_ID = new ResourceRef("core.indexing-jobs");

  /** Composed Operations referenced by item / collection / privacy.resolver axes. */
  public static final OperationRef CANCEL_OP = new OperationRef("core.cancel-indexing-job");

  public static final OperationRef RETRY_OP = new OperationRef("core.retry-indexing-job");

  public static final OperationRef CLEAR_FAILED_OP = new OperationRef("core.clear-failed-jobs");

  public static final OperationRef PATH_HASH_RESOLVER = new OperationRef("core.resolve-path-hash");

  /** SSE endpoint advertised by the Resource entry. */
  public static final String ENDPOINT = "/api/indexing-jobs/stream";

  /** Schema URL for the per-row wire payload ({@code IndexingJobView}). */
  public static final String SCHEMA_URL =
      "https://ssot.justsearch/v1/schemas/indexing-job-view.v1.json";

  /** Discriminator used by the FE renderer dispatcher to wire the table UI. */
  public static final String KIND = "indexing-jobs-table";

  private static final List<Resource> DEFINITIONS =
      List.of(
          new Resource(
              INDEXING_JOBS_ID,
              Presentation.of(
                  new I18nKey("registry-resource.indexing-jobs.label"),
                  new I18nKey("registry-resource.indexing-jobs.description")),
              SCHEMA_URL,
              Category.TABULAR,
              SubscriptionMode.SSE_STREAM,
              ENDPOINT,
              KIND,
              Optional.empty(), // No history retention policy today; deltas are ephemeral
              Optional.empty(), // No per-Resource recovery Operation
              Provenance.core("1.0"),
              Privacy.hashedWithResolver(PATH_HASH_RESOLVER),
              Set.of(CANCEL_OP, RETRY_OP),
              Set.of(CLEAR_FAILED_OP),
              "pathHash",
              Audience.USER,
              // Tempdoc 560 Fix D: real consumers are the FE task-tray bridge (startIndexingJobsBridge
              // in indexingJobsBridge.ts) + the jf-resource-view table, both subscribing to
              // /api/indexing-jobs/stream. Declared honestly rather than inferred from the endpoint.
              List.of(new ConsumerHook.Realized("indexing-jobs-task-bridge", Audience.USER))));

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Resource> definitions() {
    return DEFINITIONS;
  }
}
