/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.chaos;

import io.justsearch.ipc.mmf.MmfWorkerSignalLayoutV1;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test harness for manipulating the Memory-Mapped File (MMF) signal bus.
 *
 * <p>This harness acts as the "Time Lord" for deterministic testing of
 * the Worker's throttling and suicide pact logic by directly manipulating
 * timestamps and signals in the MMF.
 *
 * <p>MMF Layout (64 bytes):
 * <ul>
 *   <li>[0-7]: Last User Activity (Epoch Millis, long) - Main writes; Worker reads</li>
 *   <li>[8-15]: Main Heartbeat (Epoch Millis, long) - Main writes; Worker reads</li>
 *   <li>[16]: Shutdown Signal (1=Stop) - Main writes; Worker reads</li>
 *   <li>[17-19]: Reserved</li>
 *   <li>[20-23]: Worker gRPC Port (int) - Worker writes; Main reads</li>
 * </ul>
 */
public final class MmfTestHarness implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(MmfTestHarness.class);

  private static final ValueLayout.OfLong LE_LONG =
      ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
  private static final ValueLayout.OfInt LE_INT =
      ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

  private static final int OFFSET_RESERVED_START = MmfWorkerSignalLayoutV1.OFFSET_RESERVED0_START;

  private final Path signalFilePath;
  private RandomAccessFile raf;
  private FileChannel channel;
  private Arena arena;
  private MemorySegment segment;

  // For MMF fuzzing tests
  private final AtomicBoolean fuzzerRunning = new AtomicBoolean(false);
  private Thread fuzzerThread;

  public MmfTestHarness(Path signalFilePath) {
    this.signalFilePath = signalFilePath;
  }

  /**
   * Opens the MMF for manipulation.
   * Creates the file if it doesn't exist.
   *
   * @throws IOException if the file cannot be opened
   */
  public void open() throws IOException {
    Files.createDirectories(signalFilePath.getParent());

    this.raf = new RandomAccessFile(signalFilePath.toFile(), "rw");
    this.channel = raf.getChannel();

    if (raf.length() < MmfWorkerSignalLayoutV1.MMF_SIZE_BYTES) {
      raf.setLength(MmfWorkerSignalLayoutV1.MMF_SIZE_BYTES);
    }

    this.arena = Arena.ofShared();
    this.segment =
        channel.map(
            FileChannel.MapMode.READ_WRITE, 0, MmfWorkerSignalLayoutV1.MMF_SIZE_BYTES, arena);

    log.info("MmfTestHarness opened: {}", signalFilePath);
  }

  // ============== Activity Manipulation (Time Lord) ==============

  /**
   * Writes a custom activity timestamp to simulate user activity.
   *
   * @param epochMillis The timestamp to write
   */
  public void writeActivity(long epochMillis) {
    ensureOpen();
    segment.set(LE_LONG, MmfWorkerSignalLayoutV1.OFFSET_ACTIVITY_EPOCH_MS, epochMillis);
    segment.force();
    log.debug("Wrote activity timestamp: {}", epochMillis);
  }

  /**
   * Simulates recent user activity (now - offsetMs).
   *
   * @param offsetMs Milliseconds before now
   */
  public void simulateRecentActivity(long offsetMs) {
    writeActivity(System.currentTimeMillis() - offsetMs);
  }

  /**
   * Simulates stale user activity (triggers worker to resume indexing).
   *
   * @param staleMs How stale the activity should be
   */
  public void simulateStaleActivity(long staleMs) {
    writeActivity(System.currentTimeMillis() - staleMs);
  }

  /**
   * Reads the current activity timestamp.
   */
  public long readActivity() {
    ensureOpen();
    return segment.get(LE_LONG, MmfWorkerSignalLayoutV1.OFFSET_ACTIVITY_EPOCH_MS);
  }

  // ============== Heartbeat Manipulation (Suicide Pact) ==============

  /**
   * Writes a custom heartbeat timestamp.
   *
   * @param epochMillis The timestamp to write
   */
  public void writeHeartbeat(long epochMillis) {
    ensureOpen();
    segment.set(LE_LONG, MmfWorkerSignalLayoutV1.OFFSET_HEARTBEAT_EPOCH_MS, epochMillis);
    segment.force();
    log.debug("Wrote heartbeat timestamp: {}", epochMillis);
  }

  /**
   * Writes current time as heartbeat (keeps worker alive).
   */
  public void keepAlive() {
    writeHeartbeat(System.currentTimeMillis());
  }

  /**
   * Simulates a stale heartbeat (should trigger worker suicide pact).
   *
   * @param staleMs How stale the heartbeat should be (default threshold is 5000ms)
   */
  public void simulateDeadMain(long staleMs) {
    writeHeartbeat(System.currentTimeMillis() - staleMs);
  }

  /**
   * Reads the current heartbeat timestamp.
   */
  public long readHeartbeat() {
    ensureOpen();
    return segment.get(LE_LONG, MmfWorkerSignalLayoutV1.OFFSET_HEARTBEAT_EPOCH_MS);
  }

  // ============== Shutdown Signal ==============

  /**
   * Sets the shutdown signal.
   */
  public void setShutdown() {
    ensureOpen();
    segment.set(
        ValueLayout.JAVA_BYTE, MmfWorkerSignalLayoutV1.OFFSET_SHUTDOWN_SIGNAL, (byte) 1);
    segment.force();
    log.debug("Set shutdown signal");
  }

  /**
   * Clears the shutdown signal.
   */
  public void clearShutdown() {
    ensureOpen();
    segment.set(
        ValueLayout.JAVA_BYTE, MmfWorkerSignalLayoutV1.OFFSET_SHUTDOWN_SIGNAL, (byte) 0);
    segment.force();
    log.debug("Cleared shutdown signal");
  }

  /**
   * Reads the shutdown signal.
   */
  public boolean isShutdownSet() {
    ensureOpen();
    return segment.get(ValueLayout.JAVA_BYTE, MmfWorkerSignalLayoutV1.OFFSET_SHUTDOWN_SIGNAL)
        == 1;
  }

  // ============== Port Discovery ==============

  /**
   * Writes a port value (for testing stale port discovery).
   */
  public void writePort(int port) {
    ensureOpen();
    segment.set(LE_INT, MmfWorkerSignalLayoutV1.OFFSET_WORKER_GRPC_PORT, port);
    segment.force();
    log.debug("Wrote port: {}", port);
  }

  /**
   * Zeros the port (standard pre-spawn reset).
   */
  public void zeroPort() {
    writePort(0);
  }

  /**
   * Reads the current port value.
   */
  public int readPort() {
    ensureOpen();
    return segment.get(LE_INT, MmfWorkerSignalLayoutV1.OFFSET_WORKER_GRPC_PORT);
  }

  /**
   * Waits for a non-zero port with timeout.
   *
   * @param timeoutMs Maximum time to wait
   * @param pollIntervalMs Polling interval
   * @return The discovered port
   * @throws IllegalStateException if timeout exceeded
   */
  public int awaitPort(long timeoutMs, long pollIntervalMs) throws InterruptedException {
    log.debug("Awaiting port from signal file: {} (timeout={}ms)", signalFilePath, timeoutMs);
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      // Force buffer refresh to see external changes
      segment.load();
      int port = readPort();
      if (port > 0) {
        log.info("Discovered port: {} in file {}", port, signalFilePath);
        return port;
      }
      Thread.sleep(pollIntervalMs);
    }
    throw new IllegalStateException(
        "Timeout waiting for port after " + timeoutMs + "ms in file " + signalFilePath);
  }

  // ============== MMF Fuzzing ==============

  /**
   * Starts the MMF fuzzer thread that writes random bytes to the reserved area.
   * Used for testing IPC robustness.
   *
   * @param frequencyHz Writes per second
   */
  public void startFuzzer(int frequencyHz) {
    if (fuzzerRunning.compareAndSet(false, true)) {
      long intervalMs = 1000 / frequencyHz;
      Random random = new Random();

      fuzzerThread =
          new Thread(
              () -> {
                log.info("MMF Fuzzer started at {}Hz", frequencyHz);
                while (fuzzerRunning.get() && !Thread.currentThread().isInterrupted()) {
                  try {
                    // Write random bytes to reserved area [17-19]
                    byte[] randomBytes = new byte[3];
                    random.nextBytes(randomBytes);
                    MemorySegment.copy(
                        randomBytes, 0, segment, ValueLayout.JAVA_BYTE, OFFSET_RESERVED_START, 3);
                    segment.force();

                    Thread.sleep(intervalMs);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                  }
                }
                log.info("MMF Fuzzer stopped");
              },
              "mmf-fuzzer");
      fuzzerThread.setDaemon(true);
      fuzzerThread.start();
    }
  }

  /**
   * Stops the MMF fuzzer thread.
   */
  public void stopFuzzer() {
    if (fuzzerRunning.compareAndSet(true, false)) {
      if (fuzzerThread != null) {
        fuzzerThread.interrupt();
        try {
          fuzzerThread.join(1000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  // ============== Utilities ==============

  /**
   * Resets the entire MMF to zeros.
   */
  public void resetAll() {
    ensureOpen();
    for (int i = 0; i < MmfWorkerSignalLayoutV1.MMF_SIZE_BYTES; i++) {
      segment.set(ValueLayout.JAVA_BYTE, i, (byte) 0);
    }
    segment.force();
    log.debug("Reset all MMF bytes to zero");
  }

  /**
   * Returns the signal file path.
   */
  public Path getSignalFilePath() {
    return signalFilePath;
  }

  private void ensureOpen() {
    if (segment == null) {
      throw new IllegalStateException("MmfTestHarness is not open");
    }
  }

  @Override
  public void close() throws IOException {
    stopFuzzer();

    if (arena != null) {
      try {
        segment.force();
      } catch (Exception e) {
        log.warn("Failed to force segment on close", e);
      }
      try {
        arena.close();
      } catch (Exception e) {
        log.debug("Could not close arena (best-effort).", e);
      }
      arena = null;
      segment = null;
    }
    if (channel != null) {
      channel.close();
      channel = null;
    }
    if (raf != null) {
      raf.close();
      raf = null;
    }

    log.info("MmfTestHarness closed");
  }
}
