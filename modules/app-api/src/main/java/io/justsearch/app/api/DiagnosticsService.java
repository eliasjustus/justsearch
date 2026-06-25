/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.nio.file.Path;

/**
 * Diagnostics-pack export surface exposed to the AppFacade.
 *
 * <p>Slice 3a-1-2 closure: backs the {@code core.export-diagnostics} Operation
 * (modules/app-services {@code ExportDiagnosticsHandler}) without forcing a
 * cross-module cycle through the modules/ui-side {@code DiagnosticsController}.
 * Production wiring: {@code DiagnosticsController} implements this interface;
 * {@code LocalApiServer} late-binds it onto HeadAssembly after both
 * are constructed.
 *
 * <p>Stability: stable (API contract).
 */
public interface DiagnosticsService {

  /**
   * Generate a privacy-redacted diagnostics ZIP under AI Home and return the
   * path. Throws on filesystem / I/O failure or when the underlying state
   * sources (telemetry, debug-state, etc.) are unavailable.
   *
   * @return absolute path to the produced ZIP
   */
  Path exportDiagnostics() throws Exception;

}
