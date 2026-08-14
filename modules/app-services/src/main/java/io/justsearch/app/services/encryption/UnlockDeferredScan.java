/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.encryption;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 834 §5.2 — runs a store scan when the data key unlocks, OFF the key monitor.
 *
 * <p>Two constraints from {@link DataKeyManager}, both load-bearing and both easy to violate with a
 * one-line lambda:
 *
 * <ul>
 *   <li>{@code fire(before, state())} runs inside the {@code synchronized unlock()} (and
 *       {@code setup} / {@code recover}), so listeners execute UNDER the key monitor. A directory
 *       scan there blocks the whole key lifecycle for its duration.
 *   <li>{@code fire} SWALLOWS listener throws, so a fault on this path is not loud — it is gone.
 * </ul>
 *
 * <p>So the listener does exactly one thing: hand the scan to a single daemon thread and return.
 * The scan itself must be idempotent, because boot, unlock and a later re-unlock all trigger it.
 *
 * <p>This exists as a named class rather than an inline lambda because those two properties are
 * testable only if there is something to hold — {@code UnlockDeferredScanTest} asserts that
 * {@code unlock()} returns while the scan is still running, and that a throwing scan neither breaks
 * unlock nor kills subsequent scans.
 */
public final class UnlockDeferredScan implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(UnlockDeferredScan.class);

  private final Runnable scan;
  private final ExecutorService executor;

  public UnlockDeferredScan(String threadName, Runnable scan) {
    this.scan = Objects.requireNonNull(scan, "scan");
    this.executor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, threadName);
              t.setDaemon(true);
              return t;
            });
  }

  /** Subscribe to {@code keys}; every transition INTO {@code UNLOCKED} schedules one scan. */
  public UnlockDeferredScan attachTo(DataKeyManager keys) {
    Objects.requireNonNull(keys, "keys").addListener(
        (from, to) -> {
          if (to == DataKeyManager.State.UNLOCKED) {
            schedule();
          }
        });
    return this;
  }

  /** Hand one scan to the worker thread. Never throws — see the class javadoc's second constraint. */
  public void schedule() {
    try {
      executor.execute(this::runGuarded);
    } catch (RejectedExecutionException shuttingDown) {
      LOG.warn("Deferred unlock scan not scheduled (executor is shutting down)", shuttingDown);
    }
  }

  private void runGuarded() {
    try {
      scan.run();
    } catch (RuntimeException | Error e) {
      // The scan runs detached, so nothing else can observe its failure. Log it here or lose it.
      LOG.warn("Deferred unlock scan failed", e);
    }
  }

  /**
   * Block until every scheduled scan has finished, or {@code timeout} elapses. Test seam — a
   * detached scan is otherwise unobservable, which is exactly how the encrypted-install bug this
   * class prevents would hide.
   */
  boolean awaitQuiescence(Duration timeout) {
    try {
      return executor.submit(() -> null).get(timeout.toMillis(), TimeUnit.MILLISECONDS) == null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
