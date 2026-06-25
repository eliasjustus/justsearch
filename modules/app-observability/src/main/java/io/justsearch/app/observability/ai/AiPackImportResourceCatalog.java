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
 * Tempdoc 575 §17 Face C — surfaces the Brain <b>pack import</b> progress as an OBSERVABLE
 * {@link Category#STATE} Resource (the sibling of {@link AiInstallResourceCatalog}). Registers
 * {@code core.pack-import} so it is a declared in-family concept of the observed-happening register,
 * letting the register declare its POLLED-STATE liveness model — fed by the existing pack-status
 * endpoint, the same {@code AiPackImportStatus} the FE polls (with the {@code updatedAtEpochMs} the
 * backstop reaper reads).
 */
public final class AiPackImportResourceCatalog implements ResourceCatalog {

  public static final String NAMESPACE = "core";

  public static final ResourceRef PACK_IMPORT_ID = new ResourceRef("core.pack-import");

  public static final String ENDPOINT = "/api/ai/packs/status";

  public static final String SCHEMA_URL = "https://ssot.justsearch/v1/schemas/ai-pack-import-status.v1.json";

  public static final String KIND = "pack-import";

  private static final List<Resource> DEFINITIONS =
      List.of(
          new Resource(
              PACK_IMPORT_ID,
              Presentation.of(
                  new I18nKey("registry-resource.pack-import.label"),
                  new I18nKey("registry-resource.pack-import.description")),
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
