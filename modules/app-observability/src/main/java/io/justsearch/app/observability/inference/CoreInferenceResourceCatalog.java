/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.inference;

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
 * Tempdoc 560 WS7b (§4.1, first increment) — makes the <b>Brain</b> (inference runtime) a participant
 * in the one registry by surfacing its runtime capability as an OBSERVABLE {@link Category#STATE}
 * Resource declaration.
 *
 * <p>The §4.1 gap the WS7a spike measured: Brain was <em>observational-only</em> — its state
 * ({@code AiRuntimeStatusResponse} / {@code InferenceRuntimeView}) was served at
 * {@code /api/ai/runtime/status} but was <strong>not a declared contribution</strong> in
 * {@code io.justsearch.agent.api.registry}; Brain declared no {@code Operation}/{@code Resource}/
 * capability, so a consumer could not discover "what can the inference runtime do" through the same
 * mechanism it discovers everything else. This entry closes that for the OBSERVABLE axis: the
 * inference runtime's capability snapshot (active model/variant, GPU/VRAM tier, per-ONNX feature
 * status) is now a Resource the registry surfaces at {@code /api/registry/resources}, fed by the
 * existing {@code /api/ai/runtime/status} endpoint the Head already serves.
 *
 * <p>It is a STATE × {@link SubscriptionMode#ONE_SHOT} Resource (the current capability value, fetched
 * on demand) rather than SSE — the runtime-status endpoint is a request/response REST surface today, so
 * declaring SSE here would be a dishonest publication claim. A future increment can promote it to
 * SSE_STREAM if/when the runtime publishes a change stream.
 */
public final class CoreInferenceResourceCatalog implements ResourceCatalog {

  /** Stable namespace for the inference-runtime Resource entry. */
  public static final String NAMESPACE = "core";

  /** Stable id — the Brain's runtime-capability OBSERVABLE declaration. */
  public static final ResourceRef INFERENCE_RUNTIME_ID =
      new ResourceRef("core.inference-runtime");

  /** The existing REST endpoint that serves the current {@code AiRuntimeStatusResponse}. */
  public static final String ENDPOINT = "/api/ai/runtime/status";

  /** Schema URL for the wire payload (AiRuntimeStatusResponse envelope). */
  public static final String SCHEMA_URL =
      "https://ssot.justsearch/v1/schemas/ai-runtime-status.v1.json";

  /** Discriminates the renderer in the FE generic dispatcher. */
  public static final String KIND = "inference-runtime";

  private static final List<Resource> DEFINITIONS =
      List.of(
          new Resource(
              INFERENCE_RUNTIME_ID,
              Presentation.of(
                  new I18nKey("registry-resource.inference-runtime.label"),
                  new I18nKey("registry-resource.inference-runtime.description")),
              SCHEMA_URL,
              Category.STATE,
              SubscriptionMode.ONE_SHOT,
              ENDPOINT,
              KIND,
              Optional.empty(),
              Optional.empty(),
              Provenance.core("1.0"),
              // The runtime-status envelope (variant ids, model paths, VRAM tiers) carries no
              // user-filesystem path; NO_PATHS is the honest privacy classification.
              Privacy.noPaths(),
              Set.of(),
              Set.of(),
              "",
              Audience.USER,
              // The real consumer is the FE Brain surface (BrainSurface.ts), which renders the
              // inference runtime's status. Declared honestly rather than inferred from the endpoint.
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
