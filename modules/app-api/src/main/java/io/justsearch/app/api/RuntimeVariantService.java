/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.Map;

/**
 * GPU runtime-variant activation surface exposed to the AppFacade.
 *
 * <p>Slice 3a-2-c continuation (BrainRuntime variant cluster): backs
 * {@code core.activate-runtime-variant} (MEDIUM, requires variantId) and
 * {@code core.deactivate-runtime-variant} (MEDIUM). Production wiring:
 * {@code AiRuntimeController} (modules/ui) implements this interface;
 * {@code LocalApiServer} late-binds it onto {@code HeadAssembly} after
 * both are constructed.
 *
 * <p>Both methods return a {@code Map<String, Object>} mirroring the
 * pre-existing AiRuntimeStatusResponse shape so the FE consumer
 * (BrainRuntimeSection / useBrainRuntimeVariants) can deserialize without
 * lifting the modules/ui-side type into app-api.
 *
 * <p>Stability: stable (API contract).
 */
public interface RuntimeVariantService {

  /**
   * Begin activation of a runtime variant (e.g., a CUDA pack). Validates
   * that activation is allowed under enterprise policy (Online AI enabled,
   * GPU acceleration enabled), starts the activation lifecycle in the
   * background, and returns the post-start status snapshot.
   *
   * @param variantId the variant id; must be non-null and non-blank
   * @return current activation status as a serializable map
   * @throws IllegalArgumentException for null/blank variantId
   * @throws IllegalStateException when an activation is already running
   * @throws Exception on policy denial or activation start failure
   */
  Map<String, Object> activate(String variantId) throws Exception;

  /**
   * Begin deactivation of the currently-active runtime variant and return
   * the post-start status snapshot.
   *
   * @return current activation status as a serializable map
   * @throws IllegalStateException when an activation/deactivation is
   *     already running
   * @throws Exception on deactivation start failure
   */
  Map<String, Object> deactivate() throws Exception;

}
