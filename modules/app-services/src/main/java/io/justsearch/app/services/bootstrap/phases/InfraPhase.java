/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import com.sun.net.httpserver.HttpHandler;
import io.grpc.Server;
import io.justsearch.app.config.ConfigManagerBootstrap;
import io.justsearch.app.config.ConfigSnapshot;
import io.justsearch.app.observability.CapabilitiesController;
import io.justsearch.app.observability.CapabilitiesService;
import io.justsearch.app.observability.InfraDiagnosticsService;
import io.justsearch.app.observability.InfraHealthBootstrap;
import io.justsearch.app.observability.InfraHealthGrpcService;
import io.justsearch.app.services.bootstrap.BootstrapCapabilitiesFactory;
import io.justsearch.app.services.bootstrap.PhaseOutcome;
import io.justsearch.app.util.RepoPaths;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Tempdoc 519 §4 Phase 1 — infrastructure setup. Constructs the diagnostics service, infra
 * health bootstrap, gRPC health service / server, and the {@code /infra/capabilities} HTTP
 * handler. Returns the closeable + projectable handles the bootstrap needs to hold; transient
 * collaborators (diagnostics service, grpcService, healthBootstrap) are constructed for their
 * side effects only.
 *
 * <p>The capabilities handler reads {@code catalogVersionSupplier} on each request so the bootstrap
 * can wire it to the late-bound CapabilitiesChangeRegistry.currentSeq() without circular
 * construction order.
 */
public final class InfraPhase {

  private InfraPhase() {}

  /** Held output — the closeable grpcServer + the capabilities HTTP handler. */
  public record Output(Server infraHealthGrpcServer, HttpHandler capabilitiesHandler) {}

  /**
   * Tempdoc 541 §5.3 + fix-pass Tier 5 + §12.F: sealed-sum entry. Failure modes (gRPC bind
   * error, capabilities-handler factory exception) are caught and reported as
   * {@link PhaseOutcome.Failed}. No Degraded scenarios today — InfraPhase is binary
   * (gRPC + handler bind or fail). §12.F: legacy {@code run()} delegated target inlined
   * here; there are no external callers (HeadAssembly uses {@code runWithOutcome} since the
   * Tier 5 sealed-sum migration).
   */
  public static PhaseOutcome<Output> runWithOutcome(
      ResolvedConfig rc,
      ConfigSnapshot snapshot,
      ConfigManagerBootstrap configManager,
      LongSupplier catalogVersionSupplier) {
    try {
      InfraDiagnosticsService diagnostics =
          new InfraDiagnosticsService(BootstrapHelpers.toInfraHealthConfig(rc.infraHealth()));
      BootstrapHelpers.configureAutomationDiagnostics(diagnostics);
      diagnostics.setConfigValidSupplier(() -> true);
      diagnostics.setMetadataSupplier(
          () -> Map.of("config_loaded_at", snapshot.loadedAt().toString()));
      InfraHealthBootstrap infraHealthBootstrap = new InfraHealthBootstrap(diagnostics);
      infraHealthBootstrap.bindConfigManager(configManager);
      configManager.registerListener(
          snap ->
              diagnostics.setMetadataSupplier(
                  () -> Map.of("config_loaded_at", snap.loadedAt().toString())),
          false);
      InfraHealthGrpcService grpcService = new InfraHealthGrpcService(diagnostics);
      ResolvedConfig.InfraGrpc grpcCfg = rc.infraGrpc();
      Server grpcServer = BootstrapHelpers.startInfraHealthGrpcServer(grpcService, grpcCfg);
      HttpHandler capabilitiesHandler =
          BootstrapCapabilitiesFactory.createCapabilitiesHandler(
              System.getProperty("app.api.fake_capabilities"),
              System.getenv("APP_API_FAKE_CAPABILITIES"),
              FileBackedCapabilitiesHandler::new,
              () ->
                  new CapabilitiesController(
                      new CapabilitiesService(
                          RepoPaths.findRepoRoot(), catalogVersionSupplier)));
      return new PhaseOutcome.Ready<>(new Output(grpcServer, capabilitiesHandler));
    } catch (RuntimeException e) {
      return PhaseOutcome.Failed.of(e);
    }
  }
}
