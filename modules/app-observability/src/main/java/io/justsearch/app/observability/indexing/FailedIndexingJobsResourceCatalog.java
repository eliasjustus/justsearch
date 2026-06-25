/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.indexing;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.PathPolicy;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Role;
import io.justsearch.agent.api.registry.SubscriptionMode;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Slice 3a.1.9 §B.B.D Stream A — Crash list as a substrate-managed Resource.
 *
 * <p>Companion to {@link IndexingJobsResourceCatalog}. The same underlying
 * worker job queue is filtered to FAILED-state rows. The wire shape is
 * {@code IndexingJobView[]} per slice 445; SHA-256 hashed paths per
 * ADR-0028.
 *
 * <p>Wire shape:
 *
 * <ul>
 *   <li><b>Subscription</b>: {@link SubscriptionMode#ONE_SHOT}. Failed
 *       jobs change rarely (operator gestures or batch-failure events);
 *       a polling/REST shape is sufficient. The substrate's TABULAR ×
 *       ONE_SHOT strategy hydrates state once at subscribe, surfacing
 *       refresh through user gesture (re-mount or explicit refresh).
 *   <li><b>Endpoint</b>: {@code GET /api/indexing-jobs/failed} returns
 *       {@code {jobs: IndexingJobView[], count: number}}.
 *   <li><b>Privacy</b>: {@link PathPolicy#HASHED_REQUIRES_RESOLVER} same
 *       as core.indexing-jobs — same data, same privacy stance.
 *   <li><b>Item Operations</b>: empty for V1. Per-row retry could be
 *       added in a follow-up (the slice-445
 *       {@code core.retry-indexing-job} Operation already supports it
 *       by pathHash).
 *   <li><b>Collection Operations</b>: {@code core.clear-failed-jobs}
 *       (lifted from the existing legacy endpoint).
 * </ul>
 *
 * <p>Why a separate Resource and not a filter on core.indexing-jobs:
 * the current core.indexing-jobs Resource emits ALL job state
 * transitions, which for an operator-focused "failed jobs" view is
 * noisy. A dedicated Resource keeps the surface focused on the
 * failure case + delivers a second TABULAR instance (proving the
 * substrate's catalog-driven path with a non-SSE Category × Mode).
 *
 * <p>The substrate's TABULAR × ONE_SHOT strategy is registered in
 * {@code modules/ui-web/src/shell-v0/strategies/subscriptionStrategy.ts}.
 */
public final class FailedIndexingJobsResourceCatalog implements ResourceCatalog {

  /** Shared namespace with the other core catalogs. */
  public static final String NAMESPACE = "core";

  public static final ResourceRef FAILED_INDEXING_JOBS_ID =
      new ResourceRef("core.failed-indexing-jobs");

  /** Reuses the operation lifted to a collection operation (slice 445 §A.7). */
  public static final OperationRef CLEAR_FAILED_OP = new OperationRef("core.clear-failed-jobs");

  /**
   * Tempdoc 599 §16.1 Move 1 — per-row triage Operations. Binding the already-built retry/cancel
   * Operations as item-operations makes the failed-jobs rows actionable wherever they render: the
   * operator's global failed-jobs view AND the per-folder user drill-down (the §16/B1 drawer, which
   * reuses {@code <jf-row-actions>} over a folder-scoped instance). The Operations are themselves
   * user-invocable (UI executor, LOW risk); this Resource keeps {@link Audience#OPERATOR} for its
   * GLOBAL view (slice 481 §7 — global triage is admin work), which is orthogonal to per-op audience.
   */
  public static final OperationRef CANCEL_OP = new OperationRef("core.cancel-indexing-job");

  public static final OperationRef RETRY_OP = new OperationRef("core.retry-indexing-job");

  public static final OperationRef PATH_HASH_RESOLVER = new OperationRef("core.resolve-path-hash");

  /** REST endpoint for ONE_SHOT consumption. */
  public static final String ENDPOINT = "/api/indexing-jobs/failed";

  /** Same wire record as core.indexing-jobs — both serve {@code IndexingJobView}. */
  public static final String SCHEMA_URL =
      "https://ssot.justsearch/v1/schemas/indexing-job-view.v1.json";

  /**
   * Renderer dispatch hint: shares the {@code indexing-jobs-table} kind so
   * the same {@code <jf-table>} default renderer applies. The substrate
   * doesn't need a distinct kind for FAILED-only filtering — that's a
   * presentation concern handled by row content.
   */
  public static final String KIND = "indexing-jobs-table";

  private static final List<Resource> DEFINITIONS =
      List.of(
          new Resource(
              FAILED_INDEXING_JOBS_ID,
              Presentation.of(
                  new I18nKey("registry-resource.failed-indexing-jobs.label"),
                  new I18nKey("registry-resource.failed-indexing-jobs.description")),
              SCHEMA_URL,
              Category.TABULAR,
              SubscriptionMode.ONE_SHOT,
              ENDPOINT,
              KIND,
              Optional.empty(),
              Optional.empty(),
              Provenance.core("1.0"),
              Privacy.hashedWithResolver(PATH_HASH_RESOLVER),
              // Tempdoc 599 §16.1 Move 1 — per-row retry/cancel (was empty V1 scaffolding).
              Set.of(CANCEL_OP, RETRY_OP),
              Set.of(CLEAR_FAILED_OP),
              "pathHash",
              // Slice 481 §7 step 2: failed-jobs triage is operator-grade — clearing or
              // retrying failed jobs is admin work, not user self-service.
              Audience.OPERATOR)
              // tempdoc 571 §4c: a failed-jobs triage stream is operator-facing diagnostics.
              .withRole(Role.DIAGNOSTIC));

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Resource> definitions() {
    return DEFINITIONS;
  }
}
