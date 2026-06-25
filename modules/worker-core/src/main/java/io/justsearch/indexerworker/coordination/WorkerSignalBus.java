/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.coordination;

import java.io.Closeable;
import java.io.IOException;

/**
 * Interface for worker-main process coordination signals.
 *
 * <p>The signal bus enables inter-process communication for:
 * <ul>
 *   <li>Heartbeat monitoring (suicide pact liveness check)</li>
 *   <li>User activity tracking (breath holding/throttling)</li>
 *   <li>Shutdown signaling</li>
 *   <li>Port discovery</li>
 * </ul>
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link MmfWorkerSignalBus} - Memory-mapped file implementation for production</li>
 *   <li>MockWorkerSignalBus - In-memory mock for hermetic testing (in testFixtures)</li>
 * </ul>
 */
public interface WorkerSignalBus extends Closeable {

  /**
   * Opens the signal bus for read/write access.
   *
   * @throws IOException if the signal bus cannot be opened
   */
  void open() throws IOException;

  /**
   * Writes the gRPC port to the signal bus.
   * Called by the worker after binding to an ephemeral port.
   *
   * @param port The actual bound port number
   */
  void writePort(int port);

  /**
   * Reads the last activity timestamp.
   * Used by the indexing loop for "breath holding" throttling.
   *
   * @return Last user activity timestamp (epoch millis)
   */
  long readActivity();

  /**
   * Reads the main process heartbeat.
   * Used for the "suicide pact" liveness check.
   *
   * @return Main process heartbeat timestamp (epoch millis)
   */
  long readHeartbeat();

  /**
   * Checks if a shutdown signal has been set.
   *
   * @return true if shutdown is requested
   */
  boolean isShutdownRequested();

  /**
   * Determines if the worker should terminate based on the "suicide pact" logic.
   *
   * <p>Rules:
   * <ul>
   *   <li>Ignores heartbeat during the startup grace period</li>
   *   <li>After grace period, terminates if heartbeat is stale</li>
   *   <li>Always terminates if shutdown signal is set</li>
   * </ul>
   *
   * @return true if the worker should terminate
   */
  boolean shouldDie();

  /**
   * Checks if user activity is recent.
   * Used for "breath holding" to pause indexing during active user interaction.
   *
   * @return true if user was active recently
   */
  boolean isUserActive();

  /**
   * Checks if the Main process is actively using the GPU (Online Mode).
   * Used to pause GPU-accelerated embeddings when Main has the GPU.
   *
   * <p>When this returns true, the Worker should skip GPU operations
   * to avoid VRAM conflicts on systems with limited GPU memory.
   *
   * @return true if Main process is using GPU (Online Mode active)
   */
  boolean isMainGpuActive();

  /**
   * Whether the OS is requesting reduced background work (energy saver engaged; tempdoc 630). Main
   * writes this from a polled {@code GetSystemPowerStatus}. Default {@code false} (conservative):
   * impls without an energy signal — and a host where the probe is unavailable — never throttle.
   *
   * @return true if the OS wants background work reduced to save power
   */
  default boolean isEnergyReduced() {
    return false;
  }

  /**
   * Whether the Worker should yield the GPU-heavy bulk backfill right now — either because Main has
   * claimed the GPU (Online Mode) OR because the OS wants reduced background work (tempdoc 630).
   * One concept, two reasons; the GPU-heavy backfill sites read this instead of {@link
   * #isMainGpuActive()} alone.
   *
   * @return true if GPU-heavy bulk backfill should be deferred
   */
  default boolean shouldYieldGpuBackfill() {
    return isMainGpuActive() || isEnergyReduced();
  }

  /**
   * Returns the startup time of this signal bus instance.
   *
   * @return Startup timestamp in epoch millis
   */
  long startupTime();

  /**
   * Checks if a dev hot-reload signal has been set.
   * Used by the sentinel thread to trigger classloader restart (tempdoc 305 Phase 2).
   *
   * @return true if reload is requested
   */
  default boolean isReloadRequested() {
    return false;
  }

  /**
   * Clears the reload signal after the worker has acknowledged it.
   */
  default void clearReloadSignal() {
    // no-op by default
  }
}
