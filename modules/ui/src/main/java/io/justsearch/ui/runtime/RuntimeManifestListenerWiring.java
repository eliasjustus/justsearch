/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.lifecycle.LifecycleProjection;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 501 Phase 29 / §13.1 polish item: extract the manifest-listener
 * wiring out of {@code HeadlessApp}.
 *
 * <p>Three pieces of wiring previously sat side-by-side in {@code HeadlessApp.main}:
 *
 * <ol>
 *   <li>The initial worker-state publish — one shot after {@code connectWorker}
 *       resolves, projecting either {@code worker.state="ready"} or
 *       {@code worker.state="failed"} into the manifest.
 *   <li>The {@link InferenceCapability} listener (Phase 13) — runs on every
 *       inference transition, projects the AI sub-record.
 *   <li>The {@link WorkerCapability} listener (Phase 22) — runs on every
 *       worker transition, projects the Worker sub-record + recomputed
 *       lifecycle.
 * </ol>
 *
 * <p>All three share the same lifecycle-derivation step
 * ({@code LifecycleProjection.derive(workerCap, inferenceCap)}) and the
 * same defensive try/catch wrapping ("publish failures must not break
 * boot"). Keeping them as inline blocks in {@code HeadlessApp} duplicated
 * structure and made the wiring re-projection-drift surface unnecessarily
 * large. This helper owns the wiring; {@code HeadlessApp} now calls
 * {@link #wire} once.
 */
public final class RuntimeManifestListenerWiring {

  private static final Logger log = LoggerFactory.getLogger(RuntimeManifestListenerWiring.class);

  private RuntimeManifestListenerWiring() {}

  /**
   * Wire all manifest listeners + perform the initial worker-state and AI
   * publishes.
   *
   * @param publisher the runtime-manifest publisher
   * @param bootstrap HeadAssembly (carries WorkerCapability +
   *     InferenceCapability via {@code bootstrap.capabilities().worker() / .inference()}).
   *     Tempdoc 519 §31 Phase 5 renamed the prior {@code AppFacadeBootstrap}
   *     to {@code HeadAssembly}; capability accessors moved from direct
   *     methods to the typed {@code CapabilityGraph} accessor.
   * @param knowledgeServer connected Worker bootstrap or {@code null} on
   *     spawn failure (drives the initial worker-state branch)
   * @param knowledgeServerStartError reason string when
   *     {@code knowledgeServer == null}, else {@code null}
   * @param latestKnowledgeServer supplier that re-reads the *current*
   *     knowledge-server reference at re-attainment time — when the
   *     worker is restarted by the health monitor, the gRPC port may
   *     change; the listener must read it fresh, not from a captured
   *     snapshot
   * @param indexBasePathSupplier supplier returning the current index
   *     base path (read each time a publish call is composed so config
   *     changes propagate)
   */
  public static void wire(
      RuntimeManifestPublisher publisher,
      HeadAssembly bootstrap,
      KnowledgeServerBootstrap knowledgeServer,
      String knowledgeServerStartError,
      Supplier<KnowledgeServerBootstrap> latestKnowledgeServer,
      Supplier<Path> indexBasePathSupplier) {
    final InferenceCapability infCap = bootstrap.capabilities().inference();
    final WorkerCapability workCap = bootstrap.capabilities().worker();

    // Inference listener (Phase 13).
    infCap.addListener(
        (prev, curr) -> {
          try {
            LifecycleState ls = LifecycleProjection.derive(workCap, infCap);
            publisher.publishAi(
                curr.name(),
                infCap.required(),
                infCap.pendingReason(),
                curr == CapabilityHealth.READY,
                ls.name());
          } catch (Exception e) {
            log.warn("Runtime manifest publishAi (listener) failed (non-fatal)", e);
          }
        });

    // Initial worker-state publish (Phase 12).
    try {
      LifecycleState ls = LifecycleProjection.derive(workCap, infCap);
      String lifecycleStr = ls.name();
      if (knowledgeServer != null) {
        Integer grpcPort = readGrpcPort(knowledgeServer);
        Path idx = indexBasePathSupplier.get();
        publisher.publishWorkerReady(
            grpcPort, idx != null ? idx.toString() : null, lifecycleStr);
      } else {
        String reason =
            knowledgeServerStartError != null && !knowledgeServerStartError.isBlank()
                ? knowledgeServerStartError
                : "Worker bootstrap did not produce a connected handle";
        publisher.publishWorkerFailed(reason, lifecycleStr);
      }
    } catch (Exception e) {
      log.warn("Runtime manifest worker-state publish failed (non-fatal)", e);
    }

    // Worker listener (Phase 22).
    workCap.addListener(
        (prev, curr) -> {
          try {
            LifecycleState ls = LifecycleProjection.derive(workCap, infCap);
            if (curr == CapabilityHealth.READY) {
              Integer grpcPort = readGrpcPort(latestKnowledgeServer.get());
              Path idx = indexBasePathSupplier.get();
              publisher.publishWorkerReady(
                  grpcPort, idx != null ? idx.toString() : null, ls.name());
            } else if (curr == CapabilityHealth.OFFLINE
                || curr == CapabilityHealth.DEGRADED
                || curr == CapabilityHealth.RECOVERING) {
              String reason = workCap.pendingReason();
              publisher.publishWorkerFailed(
                  reason != null && !reason.isBlank()
                      ? reason
                      : "Worker capability degraded",
                  ls.name());
            } else {
              publisher.publishLifecycle(ls.name());
            }
          } catch (Exception e) {
            log.warn("Runtime manifest publishWorker (listener) failed (non-fatal)", e);
          }
        });

    // Initial AI publish (Phase 13).
    try {
      LifecycleState ls = LifecycleProjection.derive(workCap, infCap);
      publisher.publishAi(
          infCap.health().name(),
          infCap.required(),
          infCap.pendingReason(),
          infCap.health() == CapabilityHealth.READY,
          ls.name());
    } catch (Exception e) {
      log.warn("Runtime manifest initial-AI publish failed (non-fatal)", e);
    }
  }

  private static Integer readGrpcPort(KnowledgeServerBootstrap ks) {
    if (ks == null) {
      return null;
    }
    try {
      int p = ks.signalBus().readPort();
      return p > 0 ? p : null;
    } catch (Exception e) {
      return null;
    }
  }
}
