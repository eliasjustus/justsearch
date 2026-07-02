/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 672 follow-up — Head-side counterpart to {@code GpuSaturationSampler}'s shape (same
 * single-thread {@link ScheduledExecutorService}, same defensive posture), periodically
 * evaluating {@link VduPacingPolicy} and auto-triggering {@code
 * OfflineCoordinator.startOfflineProcessing()} when idle/energy/exclusivity conditions allow and
 * VDU work is actually pending.
 *
 * <p>Deliberately gates on {@code coordinator.getPendingVduCount() > 0}, not the broader {@code
 * hasPendingWork()} (which also covers embeddings) — embedding backfill already has its own
 * autonomous, idle-aware trigger on the Worker side (tempdoc 630's {@code LoopPacingPolicy}); this
 * sampler exists specifically to close the gap tempdoc 672 found for VDU, not to duplicate a
 * mechanism that already works.
 */
public final class VduOfflineTriggerSampler {

  private static final Logger log = LoggerFactory.getLogger(VduOfflineTriggerSampler.class);

  /** Check cadence. Coarser than the 5-minute idle threshold it evaluates — no need to poll fast. */
  static final long CHECK_INTERVAL_SECONDS = 30;

  private final Supplier<OfflineCoordinator> coordinatorSupplier;
  private final Supplier<KnowledgeServerBootstrap> knowledgeServerSupplier;
  private final BooleanSupplier llmOnlineSupplier;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean started = new AtomicBoolean(false);

  public VduOfflineTriggerSampler(
      Supplier<OfflineCoordinator> coordinatorSupplier,
      Supplier<KnowledgeServerBootstrap> knowledgeServerSupplier,
      BooleanSupplier llmOnlineSupplier) {
    this.coordinatorSupplier = coordinatorSupplier;
    this.knowledgeServerSupplier = knowledgeServerSupplier;
    this.llmOnlineSupplier = llmOnlineSupplier;
    ThreadFactory tf =
        r -> {
          Thread t = new Thread(r, "vdu-offline-trigger-sampler");
          t.setDaemon(true);
          return t;
        };
    this.executor = Executors.newSingleThreadScheduledExecutor(tf);
  }

  /** Starts the sampler. Idempotent: subsequent calls are no-ops. */
  public void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    var unused =
        executor.scheduleAtFixedRate(
            this::checkOnce, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
    log.debug("VduOfflineTriggerSampler started ({}s cadence)", CHECK_INTERVAL_SECONDS);
  }

  /** Stops the sampler. Idempotent; safe to call without start. */
  public void stop() {
    executor.shutdownNow();
    try {
      executor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Visible for tests. Evaluates the pacing policy once and dispatches
   * {@code startOfflineProcessing()} on a new virtual thread if conditions allow — mirrors the
   * existing production trigger call sites' own dispatch pattern (never blocks the sampler's own
   * thread on a real VDU run).
   */
  void checkOnce() {
    try {
      OfflineCoordinator coordinator = coordinatorSupplier.get();
      if (coordinator == null || coordinator.isProcessing()) {
        return;
      }
      if (coordinator.getPendingVduCount() <= 0) {
        return;
      }
      KnowledgeServerBootstrap ks = knowledgeServerSupplier.get();
      long msSinceActivity =
          ks != null ? ks.msSinceLastUserActivity(System.currentTimeMillis()) : Long.MAX_VALUE;
      boolean energyReduced = ks != null && ks.energyState().reduced();
      boolean llmOnline = llmOnlineSupplier.getAsBoolean();
      if (VduPacingPolicy.shouldTrigger(msSinceActivity, energyReduced, llmOnline)) {
        log.info(
            "Idle ({}ms since activity) and energy conditions met; auto-triggering VDU offline"
                + " processing",
            msSinceActivity);
        Thread.ofVirtual().name("vdu-auto-trigger").start(coordinator::startOfflineProcessing);
      }
    } catch (RuntimeException e) {
      log.debug("VduOfflineTriggerSampler: check failed: {}", e.getMessage());
    }
  }
}
