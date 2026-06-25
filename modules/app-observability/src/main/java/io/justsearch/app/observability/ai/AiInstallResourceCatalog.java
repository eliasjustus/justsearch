/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ai;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.I18nKey;
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
 * Tempdoc 575 §17 Face C — surfaces the Brain <b>install</b> progress as an OBSERVABLE
 * {@link Category#STATE} Resource, so it is a declared in-family concept of the observed-happening
 * register (the reverse-coverage rule requires every catalog Resource to be a concept contributor).
 *
 * <p>The registration prerequisite the de-risking pass identified: install/pack were live in-flight
 * things but NOT registered concepts, so a {@code liveness.model} facet could not attach to them. With
 * this entry (+ the pack-import sibling) the register declares their POLLED-STATE liveness model — fed
 * by the existing {@code /api/ai/install/status} endpoint, the same {@code AiInstallStatus} the FE
 * polls (with its {@code updatedAtEpochMs} the backstop reaper reads).
 *
 * <p>STATE × {@link SubscriptionMode#ONE_SHOT}: the install status is a request/response REST snapshot
 * today; declaring SSE here would be a dishonest publication claim.
 */
public final class AiInstallResourceCatalog implements ResourceCatalog {

  public static final String NAMESPACE = "core";

  public static final ResourceRef AI_INSTALL_ID = new ResourceRef("core.ai-install");

  public static final String ENDPOINT = "/api/ai/install/status";

  public static final String SCHEMA_URL = "https://ssot.justsearch/v1/schemas/ai-install-status.v1.json";

  public static final String KIND = "ai-install";

  private static final List<Resource> DEFINITIONS =
      List.of(
          new Resource(
              AI_INSTALL_ID,
              Presentation.of(
                  new I18nKey("registry-resource.ai-install.label"),
                  new I18nKey("registry-resource.ai-install.description")),
              SCHEMA_URL,
              Category.STATE,
              SubscriptionMode.ONE_SHOT,
              ENDPOINT,
              KIND,
              Optional.empty(),
              Optional.empty(),
              Provenance.core("1.0"),
              Privacy.noPaths(),
              Set.of(),
              Set.of(),
              "",
              Audience.USER,
              List.of(new ConsumerHook.Realized("brain-surface", Audience.USER))));

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Resource> definitions() {
    return DEFINITIONS;
  }
}
