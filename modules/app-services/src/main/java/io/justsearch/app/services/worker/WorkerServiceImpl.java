/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.WorkerService;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link WorkerService} backed by {@link KnowledgeServerBootstrap}.
 *
 * <p>Resolves the bootstrap reference lazily via the supplier — the bootstrap
 * may be late-bound in {@code HeadAssembly} after async Worker startup,
 * so eager capture would freeze a null reference.
 *
 * <p>Replicates the restart logic previously inlined in
 * {@code InferenceHandlers.handleRestartWorker}: spawner.restart() →
 * client.reconnect(expectedPid) → client.resetCircuitBreaker(). Reconnect
 * failure is logged at WARN (best-effort; client retries on next call) but
 * doesn't fail the restart itself, matching the pre-existing UX.
 */
public final class WorkerServiceImpl implements WorkerService {

  private static final Logger log = LoggerFactory.getLogger(WorkerServiceImpl.class);

  private final Supplier<KnowledgeServerBootstrap> bootstrapSupplier;

  public WorkerServiceImpl(Supplier<KnowledgeServerBootstrap> bootstrapSupplier) {
    this.bootstrapSupplier = Objects.requireNonNull(bootstrapSupplier, "bootstrapSupplier");
  }

  @Override
  public boolean available() {
    KnowledgeServerBootstrap ks = safeGet();
    return ks != null && ks.spawner() != null;
  }

  @Override
  public long workerPid() {
    KnowledgeServerBootstrap ks = safeGet();
    if (ks == null || ks.spawner() == null) {
      return 0L;
    }
    return ks.spawner().getWorkerPid();
  }

  @Override
  public int restart() throws Exception {
    KnowledgeServerBootstrap ks = safeGet();
    if (ks == null) {
      throw new IllegalStateException("Knowledge Server not configured");
    }
    if (ks.spawner() == null) {
      throw new IllegalStateException("Worker spawner unavailable");
    }
    int port = ks.spawner().restart();
    long expectedPid = ks.spawner().getWorkerPid();
    try {
      ks.client().reconnect(expectedPid);
      ks.client().resetCircuitBreaker();
    } catch (Exception e) {
      // Best-effort: client has its own reconnect logic. Surface as WARN but
      // keep the restart "success" — matches the prior bespoke endpoint UX.
      log.warn(
          "Worker restarted, but client reconnect failed (will retry on next call): {}",
          e.getMessage());
    }
    return port;
  }

  private KnowledgeServerBootstrap safeGet() {
    try {
      return bootstrapSupplier.get();
    } catch (RuntimeException e) {
      log.warn("WorkerServiceImpl: bootstrap supplier threw", e);
      return null;
    }
  }
}
