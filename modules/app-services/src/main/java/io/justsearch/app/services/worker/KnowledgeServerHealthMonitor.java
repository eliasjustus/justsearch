/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import java.io.Closeable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background health monitor for the Worker process. Polls {@link
 * KnowledgeServerBootstrap#checkHealth()} and triggers deferred auxiliary initialization
 * on ERROR→READY recovery transitions.
 *
 * <p>Tempdoc 630 (latency-hardening): this periodic loop doubles as the Head-side <b>resume
 * detector</b>. Because it wakes every {@code pollIntervalMs}, an inter-tick wall-clock gap far
 * larger than that interval means the process was frozen — the machine suspended and resumed (see
 * {@link ResumeDetector}). On a detected resume the monitor <b>eagerly</b> reconnects the gRPC
 * channel and re-registers watchers + reconciles, instead of waiting for the reactive recovery
 * (first post-wake RPC reconnects; periodic sync eventually re-walks). Done before {@link
 * KnowledgeServerBootstrap#checkHealth()} so the first post-wake tick checks a fresh channel rather
 * than flipping the capability to DEGRADED on a stale one.
 */
public final class KnowledgeServerHealthMonitor implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(KnowledgeServerHealthMonitor.class);

  static final long DEFAULT_POLL_INTERVAL_MS = 10_000;

  /**
   * Resume threshold factor (tempdoc 630): a tick is treated as post-resume only if the observed
   * inter-tick gap exceeds {@code pollIntervalMs * RESUME_TOLERANCE_FACTOR}. Generous so ordinary
   * GC / scheduler jitter never false-triggers an eager reconnect+reconcile.
   */
  static final long RESUME_TOLERANCE_FACTOR = 3;

  private final KnowledgeServerBootstrap bootstrap;
  private final long pollIntervalMs;
  private final ScheduledExecutorService executor;
  private final LongSupplier nowMs;
  /** Wall-clock (epoch ms) of the previous tick; {@code -1} until the first tick. */
  private volatile long lastTickWallMs = -1;

  public KnowledgeServerHealthMonitor(KnowledgeServerBootstrap bootstrap) {
    this(bootstrap, DEFAULT_POLL_INTERVAL_MS);
  }

  public KnowledgeServerHealthMonitor(KnowledgeServerBootstrap bootstrap, long pollIntervalMs) {
    this(bootstrap, pollIntervalMs, System::currentTimeMillis);
  }

  /**
   * @param nowMs wall-clock source (epoch ms) for resume detection — injectable so the gap logic is
   *     unit-testable without a real clock or a real OS suspend (tempdoc 630)
   */
  public KnowledgeServerHealthMonitor(
      KnowledgeServerBootstrap bootstrap, long pollIntervalMs, LongSupplier nowMs) {
    if (bootstrap == null) {
      throw new IllegalArgumentException("bootstrap must not be null");
    }
    this.bootstrap = bootstrap;
    this.pollIntervalMs = pollIntervalMs > 0 ? pollIntervalMs : DEFAULT_POLL_INTERVAL_MS;
    this.nowMs = nowMs;
    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "knowledge-server-health-monitor");
              t.setDaemon(true);
              return t;
            });
  }

  public void start() {
    log.info("Knowledge Server health monitor started (poll interval: {}ms)", pollIntervalMs);
    @SuppressWarnings("unused")
    var ignored =
        executor.scheduleWithFixedDelay(
            this::tick, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
  }

  void tick() {
    try {
      // Tempdoc 630: detect an OS suspend/resume by the inter-tick wall-clock gap, and eagerly
      // re-validate the Worker surface BEFORE the health check (so a stale post-wake channel does
      // not flip the capability to DEGRADED).
      long now = nowMs.getAsLong();
      long gap = ResumeDetector.resumeGapMs(lastTickWallMs, now, pollIntervalMs, RESUME_TOLERANCE_FACTOR);
      lastTickWallMs = now;
      if (gap > 0) {
        eagerlyRevalidateAfterResume(gap);
      }

      CapabilityHealth before = bootstrap.workerCapability().health();
      bootstrap.checkHealth();
      CapabilityHealth after = bootstrap.workerCapability().health();
      if (before != CapabilityHealth.READY && after == CapabilityHealth.READY) {
        log.info(
            "Knowledge Server recovered to READY ({}→{}); running deferred auxiliary"
                + " initialization",
            before,
            after);
        bootstrap.completeReadyInitializationFromMonitor();
      }
    } catch (Exception e) {
      log.warn("Knowledge Server health monitor tick failed: {}", e.getMessage(), e);
      WorkerCapability cap = bootstrap.workerCapability();
      if (cap.health() == CapabilityHealth.READY) {
        cap.transition(CapabilityHealth.DEGRADED, "Health monitor tick exception");
      }
    }
  }

  /**
   * Tempdoc 630: on a detected resume, eagerly close the two stale-after-suspend windows using the
   * existing actuators — reconnect the gRPC channel ({@link RemoteKnowledgeClient#reconnect()}) and
   * re-register watchers + kick a (freshness-skipping) reconcile walk ({@link
   * RemoteKnowledgeClient#reindexPersistedRoots()}, which catches filesystem events missed while the
   * watcher was frozen). Each step is best-effort and independently guarded so a transient failure
   * never aborts the tick or the other step; the reactive paths (first-RPC reconnect, periodic sync)
   * remain the backstop.
   */
  private void eagerlyRevalidateAfterResume(long gapMs) {
    log.info(
        "Resume detected (process frozen ~{}ms); eagerly reconnecting gRPC + re-registering"
            + " watchers and reconciling",
        gapMs);
    // Tempdoc 630: stamp the resume so /api/status can surface a brief "Catching up after sleep"
    // transient while the reconcile below runs (auto-clears after the notice window).
    bootstrap.markResumed(nowMs.getAsLong());
    RemoteKnowledgeClient client;
    try {
      client = bootstrap.client();
    } catch (RuntimeException e) {
      // Worker not started/ready (client() throws IllegalStateException) — nothing to re-validate;
      // the normal start/spawn path owns bringing it up. Benign on resume.
      log.debug("Post-resume re-validation skipped — worker client not available: {}", e.getMessage());
      return;
    }
    try {
      client.reconnect();
    } catch (RuntimeException e) {
      log.warn("Post-resume gRPC reconnect failed (will retry on next call): {}", e.getMessage());
    }
    try {
      client.reindexPersistedRoots();
    } catch (RuntimeException e) {
      log.warn("Post-resume watcher re-register + reconcile failed: {}", e.getMessage());
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
