/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A pause that halts an acquisition run BETWEEN items, and a resume that continues it (tempdoc 840
 * Phase 3, task 5).
 *
 * <p><b>Distinct from cancel, which is terminal.</b> Cancel abandons the set: the scheduler returns
 * a cancelled summary, the run reports {@code cancelled}, and what is on disk is whatever finished.
 * A pause abandons nothing — the run stays in flight, holding its op-lease and its place in the set,
 * and resumes at the next item. Between items rather than mid-item for the same reason no
 * configuration step is interruptible mid-write: a transfer stopped halfway is a transfer that has
 * to make a decision about its {@code .partial}, which is {@link ResumableFetch}'s business and
 * already has an answer (cancel). Pausing needs no such decision, so it does not take one.
 *
 * <p>A cancel raised while a run is paused wins: {@link #awaitRunnable()} returns false rather than
 * waiting for a resume that a cancelled run will never get. The wait is sliced at {@link
 * TransportRetryPolicy#CANCEL_POLL_SLICE_MS} for the reason that constant exists — cancel only
 * raises a flag and never interrupts the install thread — with {@link #wakeForCancellation()} as the
 * prompt path so the slice is a backstop rather than the latency.
 *
 * <p>The mechanism only; nothing exposes it over the wire yet.
 */
public final class AcquisitionPause implements AcquisitionScheduler.PauseGate {

  private static final Logger log = LoggerFactory.getLogger(AcquisitionPause.class);

  private final Object monitor = new Object();
  private final BooleanSupplier cancelRequested;
  private final long pollSliceMs;
  private volatile boolean paused;

  public AcquisitionPause(BooleanSupplier cancelRequested) {
    this(cancelRequested, TransportRetryPolicy.CANCEL_POLL_SLICE_MS);
  }

  /** Test seam: a shorter slice, so a test never spends a real quarter-second per check. */
  AcquisitionPause(BooleanSupplier cancelRequested, long pollSliceMs) {
    this.cancelRequested = cancelRequested == null ? () -> false : cancelRequested;
    this.pollSliceMs = Math.max(1L, pollSliceMs);
  }

  /** Halts the run before its next item. The item in flight finishes normally. */
  public void pause() {
    synchronized (monitor) {
      if (!paused) {
        paused = true;
        log.info("Acquisition paused — the run halts before its next item");
      }
    }
  }

  /** Continues a paused run at its next item. A no-op when the run is not paused. */
  public void resume() {
    synchronized (monitor) {
      if (paused) {
        paused = false;
        log.info("Acquisition resumed");
      }
      monitor.notifyAll();
    }
  }

  /** Whether the run is currently halted between items. */
  public boolean isPaused() {
    return paused;
  }

  /**
   * Wakes a waiting run so it re-reads the cancel flag now instead of at the end of its slice.
   * Deliberately does NOT clear {@code paused}: cancellation is not a resume, and a paused-then
   * cancelled run must not look like it was continued.
   */
  public void wakeForCancellation() {
    synchronized (monitor) {
      monitor.notifyAll();
    }
  }

  @Override
  public boolean awaitRunnable() {
    synchronized (monitor) {
      while (paused) {
        if (cancelRequested.getAsBoolean()) {
          return false;
        }
        try {
          monitor.wait(pollSliceMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    return true;
  }
}
