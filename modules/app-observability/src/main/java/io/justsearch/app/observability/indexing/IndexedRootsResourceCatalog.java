/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.indexing;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.OperationRef;
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
 * Slice 449 phase 7a — TABULAR Resource for watched-root rows backing the
 * {@code core.library-surface} Surface manifest.
 *
 * <p>Per slice 449 §B.A.4: Library's React view uses ad-hoc REST against
 * {@code /api/indexing/roots}. Phase 7a models that data as a Resource so the
 * calibration exercises the Surface substrate's primitive-composition axis
 * cleanly.
 *
 * <p>Wire shape (mirrors {@link FailedIndexingJobsResourceCatalog} per slice
 * 3a.1.9 §B.B.D — TABULAR × ONE_SHOT precedent):
 *
 * <ul>
 *   <li><b>Subscription</b>: {@link SubscriptionMode#ONE_SHOT}. Watched roots
 *       change rarely (operator gestures: add / remove). Polling/REST shape
 *       fits; SSE delta machinery is overkill.
 *   <li><b>Endpoint</b>: {@code GET /api/indexing-roots/substrate} returns
 *       {@code {items: IndexedRootView[], count: number}}.
 *   <li><b>Privacy</b>: {@link Privacy#hashedWithResolver(OperationRef)} with
 *       {@code core.resolve-path-hash} as the resolver — same pin as
 *       {@code IndexingJobsResourceCatalog} per ADR-0028 +
 *       {@code LibraryResolveHashOnlyCallerPin}.
 *   <li><b>Item Operations</b>: empty for V1. The 5 Library Operations
 *       (reindex / add-watched-root / remove-watched-root / preview-excludes
 *       / apply-excludes) live on the Surface manifest's
 *       {@code consumes.operations}, not on the Resource itself. Per-row
 *       remove-from-index could become an item Operation in a future
 *       iteration.
 *   <li><b>Collection Operations</b>: empty for V1. Reindex-all could be
 *       lifted here in a future iteration; currently it's a Surface-level
 *       Operation.
 * </ul>
 */
public final class IndexedRootsResourceCatalog implements ResourceCatalog {

  /** Shared namespace with the other core catalogs. */
  public static final String NAMESPACE = "core";

  public static final ResourceRef INDEXED_ROOTS_ID = new ResourceRef("core.indexed-roots");

  /** Shared resolver Operation — same pin as core.indexing-jobs. */
  public static final OperationRef PATH_HASH_RESOLVER = new OperationRef("core.resolve-path-hash");

  /** REST endpoint for ONE_SHOT consumption. */
  public static final String ENDPOINT = "/api/indexing-roots/substrate";

  /** Schema URL — points at the SSOT schema descriptor. */
  public static final String SCHEMA_URL =
      "https://ssot.justsearch/v1/schemas/indexed-root.v1.json";

  /**
   * Renderer dispatch hint for the Surface manifest layer. Library is
   * card-shaped, not table-shaped (per slice 449 §B.A finding); the Surface
   * dispatches to the bespoke {@code <jf-library-surface>} via specialty-
   * renderer registration. The {@code kind} string remains for future
   * generic-renderer cases.
   */
  public static final String KIND = "indexed-roots-cards";

  private static final List<Resource> DEFINITIONS =
      List.of(
          new Resource(
              INDEXED_ROOTS_ID,
              Presentation.of(
                  new I18nKey("registry-resource.indexed-roots.label"),
                  new I18nKey("registry-resource.indexed-roots.description")),
              SCHEMA_URL,
              Category.TABULAR,
              SubscriptionMode.ONE_SHOT,
              ENDPOINT,
              KIND,
              Optional.empty(),
              Optional.empty(),
              Provenance.core("1.0"),
              Privacy.hashedWithResolver(PATH_HASH_RESOLVER),
              Set.of(),
              Set.of(),
              "pathHash"));

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Resource> definitions() {
    return DEFINITIONS;
  }
}
