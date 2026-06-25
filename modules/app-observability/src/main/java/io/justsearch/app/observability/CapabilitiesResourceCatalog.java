/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.SubscriptionMode;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Resource entry surfacing the existing {@code /infra/capabilities/stream} handshake
 * as a discoverable {@link Category#STATE} Resource.
 *
 * <p>Per slice 443 (serverCapabilities STATE Resource): the capabilities handshake from
 * tempdoc 429 §A.4 is structurally a STATE × SSE_STREAM Resource — current value of the
 * server's capability advertisement, replace-on-change. This catalog entry makes it
 * discoverable through the framework's resource catalog, so FE consumers (and the
 * agent registry) can feature-detect via the same mechanism as health-events and runtime-
 * context Resources.
 *
 * <p>Conforms to {@code 30-agent-workflows/01a-add-state-resource.md}: STATE Category,
 * SSE_STREAM subscription mode, no HistoryPolicy (STATE has no retained past), no
 * recovery cross-link.
 *
 * <p>Schema URL points at the existing {@code capabilities-view.schema.json} envelope.
 */
public final class CapabilitiesResourceCatalog implements ResourceCatalog {

  /** Stable namespace for the server-capabilities Resource entry. */
  public static final String NAMESPACE = "core";

  /** Stable id for the Resource entry. */
  public static final ResourceRef SERVER_CAPABILITIES_ID =
      new ResourceRef("core.server-capabilities");

  /** Existing SSE endpoint advertised by the Resource entry. */
  public static final String ENDPOINT = "/infra/capabilities/stream";

  /** Schema URL for the wire payload (CapabilitiesView envelope). */
  public static final String SCHEMA_URL =
      "https://ssot.justsearch/v1/schemas/capabilities-view.schema.json";

  /** Discriminates the renderer in the FE generic dispatcher. */
  public static final String KIND = "server-capabilities";

  private static final List<Resource> DEFINITIONS =
      List.of(
          new Resource(
              SERVER_CAPABILITIES_ID,
              Presentation.of(
                  new I18nKey("registry-resource.server-capabilities.label"),
                  new I18nKey("registry-resource.server-capabilities.description")),
              SCHEMA_URL,
              Category.STATE,
              SubscriptionMode.SSE_STREAM,
              ENDPOINT,
              KIND,
              Optional.empty(),
              Optional.empty(),
              Provenance.core("1.0"),
              // Slice 445 substrate-extension: capabilities envelope has no
              // path-typed fields; NO_PATHS is the sane default.
              Privacy.noPaths(),
              Set.of(),
              Set.of(),
              "",
              Audience.USER,
              // Tempdoc 560 Fix D: the real consumer is the FE contract-events bridge
              // (bootContractEventBridge in contractEventsBridge.ts), which subscribes to
              // /infra/capabilities/stream to refresh the live capability/operation catalogs on
              // mid-session change. Declared honestly rather than inferred from the endpoint.
              List.of(new ConsumerHook.Realized("contract-events-bridge", Audience.USER))));

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Resource> definitions() {
    return DEFINITIONS;
  }
}
