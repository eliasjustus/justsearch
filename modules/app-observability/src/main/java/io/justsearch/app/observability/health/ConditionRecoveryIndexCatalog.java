/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.I18nKey;
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
 * Resource catalog for the {@code core.condition-recovery-index} derived inverse Resource.
 *
 * <p>Per slice 447 §X.3.4 + 447-impl-D + §X.11.5 follow-up Phase 4: the
 * metadata-not-edge framing's discoverability axis. AssertedCondition entries declare
 * recoveries as metadata; this Resource materializes the inverse view (Operation →
 * Conditions referencing it).
 *
 * <p>Categorized STATE × SSE_STREAM per Phase 4: the derived snapshot broadcasts on
 * each {@code ConditionStore} mutation via {@link ConditionRecoveryIndexChangeRegistry}.
 * The REST snapshot endpoint stays available as a fallback at {@link #ENDPOINT_REST};
 * SSE consumers connect to {@link #ENDPOINT}.
 */
public final class ConditionRecoveryIndexCatalog implements ResourceCatalog {

  /** Stable namespace shared with the other core catalogs. */
  public static final String NAMESPACE = "core";

  /** Stable id for the derived inverse Resource entry. */
  public static final ResourceRef CONDITION_RECOVERY_INDEX_ID =
      new ResourceRef("core.condition-recovery-index");

  /**
   * SSE stream endpoint advertised by the Resource entry per Phase 4. Universal SSE
   * envelope (slice 436); broadcasts on each ConditionStore mutation.
   */
  public static final String ENDPOINT = "/api/condition-recovery-index/stream";

  /** REST snapshot endpoint kept as a fallback (one-shot fetch, no live updates). */
  public static final String ENDPOINT_REST = "/api/condition-recovery-index";

  /** Schema URL for the wire payload (ConditionRecoveryIndex record). */
  public static final String SCHEMA_URL =
      "https://ssot.justsearch/v1/schemas/condition-recovery-index.v1.json";

  /** Discriminates the renderer in the FE generic dispatcher. */
  public static final String KIND = "condition-recovery-index";

  private static final List<Resource> DEFINITIONS =
      List.of(
          new Resource(
              CONDITION_RECOVERY_INDEX_ID,
              Presentation.of(
                  new I18nKey("registry-resource.condition-recovery-index.label"),
                  new I18nKey("registry-resource.condition-recovery-index.description")),
              SCHEMA_URL,
              Category.STATE,
              SubscriptionMode.SSE_STREAM,
              ENDPOINT,
              KIND,
              Optional.empty(),
              Optional.empty(),
              Provenance.core("1.0"),
              Privacy.noPaths(),
              Set.of(),
              Set.of(),
              "")
              // tempdoc 571 §4c: derived recovery index is an operator diagnostic view.
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
