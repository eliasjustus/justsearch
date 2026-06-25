/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.Map;

/**
 * AI pack preflight + import surface exposed to the AppFacade.
 *
 * <p>Slice 3a-2-c continuation (BrainPackImport cluster, preflight + import
 * subset): backs {@code core.preflight-ai-pack} (LOW; read-only check) and
 * {@code core.import-ai-pack} (MEDIUM; starts import lifecycle).
 *
 * <p>Production wiring: {@code AiPackController} implements this interface;
 * {@code LocalApiServer} late-binds it onto {@code HeadAssembly}.
 *
 * <p>Both methods return {@code Map<String, Object>} mirroring the existing
 * REST response shapes (AiPackPreflightResult, AiPackImportStatus) so the
 * FE consumer (BrainPackImportSection) can deserialize without lifting the
 * modules/ui-side types into app-api.
 *
 * <p>Stability: stable (API contract).
 */
public interface PackImportService {

  /**
   * Validate a pack path against installed runtime + manifest constraints
   * without mutating state. Returns the preflight result as a serializable
   * map (mirrors AiPackPreflightResult).
   *
   * @param path absolute or relative path to a .zip or folder
   * @return preflight result map
   * @throws IllegalArgumentException for null/blank path
   * @throws Exception on preflight failure (typed errors carry their own
   *     codes; the Operation handler surfaces messages)
   */
  Map<String, Object> preflight(String path) throws Exception;

  /**
   * Begin importing an AI pack. Returns the post-start import status
   * snapshot (mirrors AiPackImportStatus). Actual import runs
   * asynchronously; the FE polls /api/ai/packs/status for progress.
   *
   * @param path absolute or relative path to a .zip or folder
   * @param allowDowngrade pass true to allow installing a lower-version pack
   *     over a higher-version one (used in repair flows)
   * @return current import status as a serializable map
   * @throws IllegalArgumentException for null/blank path
   * @throws IllegalStateException when an import is already running
   * @throws Exception on import-start failure
   */
  Map<String, Object> startImport(String path, boolean allowDowngrade) throws Exception;

}
