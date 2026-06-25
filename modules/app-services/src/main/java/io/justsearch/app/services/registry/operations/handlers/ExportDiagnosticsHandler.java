/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.DiagnosticsService;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@code core.export-diagnostics}.
 *
 * <p>Slice 3a-1-2 closure: real handler for HealthView's "Export Diagnostics"
 * button. Delegates to {@link DiagnosticsService#exportDiagnostics()} via a
 * lazy supplier (the service is late-bound by LocalApiServer after
 * DiagnosticsController is constructed).
 *
 * <p>Returns the produced ZIP path in {@code structuredData.path} so callers
 * (FE, agent loops) can surface it without a separate filesystem probe.
 */
public final class ExportDiagnosticsHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(ExportDiagnosticsHandler.class);

  private final Supplier<DiagnosticsService> diagnosticsSupplier;

  public ExportDiagnosticsHandler(Supplier<DiagnosticsService> diagnosticsSupplier) {
    this.diagnosticsSupplier = Objects.requireNonNull(diagnosticsSupplier, "diagnosticsSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson) {
    DiagnosticsService diagnostics;
    try {
      diagnostics = diagnosticsSupplier.get();
    } catch (RuntimeException e) {
      log.warn("ExportDiagnosticsHandler: diagnostics supplier threw", e);
      return OperationResult.failure("Diagnostics service unavailable: " + e.getMessage());
    }
    if (diagnostics == null) {
      return OperationResult.failure("Diagnostics service unavailable");
    }
    try {
      Path outZip = diagnostics.exportDiagnostics();
      return OperationResult.success(
          "Diagnostics exported to " + outZip.toAbsolutePath(),
          Map.of("path", outZip.toAbsolutePath().toString()));
    } catch (Exception e) {
      log.error("ExportDiagnosticsHandler: export threw", e);
      return OperationResult.failure(
          "Diagnostics export failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }
  }
}
