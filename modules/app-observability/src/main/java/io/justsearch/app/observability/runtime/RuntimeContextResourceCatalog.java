/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.runtime;

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
 * The Resource entry surfacing the head's current {@link RuntimeContext}.
 *
 * <p>Per slice 440 (Runtime mode STATE Resource): {@link Category#STATE} replace-only
 * stream. The single Resource entry advertises the snapshot endpoint + the SSE stream;
 * consumers receive a snapshot on connect plus replace broadcasts on subsequent change.
 *
 * <p>Conforms to {@code 30-agent-workflows/01a-add-state-resource.md}: STATE Category,
 * SSE_STREAM subscription mode, no HistoryPolicy (STATE has no retained past). Recovery
 * cross-link is empty — runtime mode changes don't have a singular per-Resource recovery
 * Operation.
 *
 * <p>Schema URL points to {@code runtime-context.v1.json}. Future schema versions
 * (additional dimensions like aiMode / installMode) bump the {@code v1} suffix.
 */
public final class RuntimeContextResourceCatalog implements ResourceCatalog {

  /** Stable namespace for the runtime-context Resource entry. */
  public static final String NAMESPACE = "core";

  /** Stable id for the single Resource entry. */
  public static final ResourceRef RUNTIME_CONTEXT_ID = new ResourceRef("core.runtime-context");

  /** SSE endpoint advertised by the Resource entry. */
  public static final String ENDPOINT = "/api/runtime-context/stream";

  /** Schema URL for the wire payload. */
  public static final String SCHEMA_URL =
      "https://ssot.justsearch/v1/schemas/runtime-context.v1.json";

  /** Discriminates the renderer in the FE generic dispatcher. */
  public static final String KIND = "runtime-context";

  private static final List<Resource> DEFINITIONS =
      List.of(
          new Resource(
              RUNTIME_CONTEXT_ID,
              Presentation.of(
                  new I18nKey("registry-resource.runtime-context.label"),
                  new I18nKey("registry-resource.runtime-context.description")),
              SCHEMA_URL,
              Category.STATE,
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
              ""));

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Resource> definitions() {
    return DEFINITIONS;
  }
}
