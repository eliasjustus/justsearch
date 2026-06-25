/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.process;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for managing external processes in tests.
 *
 * <p>Provides:
 * <ul>
 *   <li>Process lifecycle management (start, stop, kill)</li>
 *   <li>Log piping with configurable prefix</li>
 *   <li>JVM Shutdown Hook for orphan process cleanup</li>
 *   <li>Process state queries via {@link ProcessHandle}</li>
 * </ul>
 *
 * <p>All instances are automatically registered with a static Shutdown Hook
 * that ensures processes are killed if the JVM exits unexpectedly (e.g., Ctrl+C).
 *
 * <p>Subclasses should implement {@link #createProcessBuilder()} to define
 * the specific command to run.
 */
public abstract class ManagedProcess implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(ManagedProcess.class);

  // =========================================================================
  // Static Shutdown Hook Registry
  // =========================================================================

  private static final Set<ManagedProcess> ACTIVE_PROCESSES = ConcurrentHashMap.newKeySet();
  private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);

  static {
    registerShutdownHook();
  }

  private static void registerShutdownHook() {
    if (SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        log.info("Shutdown hook triggered - killing {} orphan process(es)", ACTIVE_PROCESSES.size());
        for (ManagedProcess mp : ACTIVE_PROCESSES) {
          try {
            if (mp.process != null && mp.process.isAlive()) {
              log.info("Killing orphan process: {} (PID {})", mp.name, mp.pid);
              mp.process.destroyForcibly();
            }
          } catch (Exception e) {
            log.warn("Failed to kill process {}: {}", mp.name, e.getMessage());
          }
        }
      }, "ManagedProcess-ShutdownHook"));
      log.debug("Registered ManagedProcess shutdown hook");
    }
  }

  // =========================================================================
  // Instance Fields
  // =========================================================================

  protected final String name;
  protected final Path workingDirectory;

  protected Process process;
  protected long pid = -1;
  private Thread stdoutThread;
  private Thread stderrThread;
  private volatile boolean logPipingEnabled = true;

  /**
   * Creates a new ManagedProcess.
   *
   * @param name Display name for logging (e.g., "WORKER", "SERVER")
   * @param workingDirectory Working directory for the process
   */
  protected ManagedProcess(String name, Path workingDirectory) {
    this.name = name;
    this.workingDirectory = workingDirectory;
  }

  // =========================================================================
  // Abstract Methods
  // =========================================================================

  /**
   * Creates the ProcessBuilder for this process.
   * Subclasses define the specific command and environment.
   *
   * @return Configured ProcessBuilder (do NOT call start())
   * @throws IOException if configuration fails
   */
  protected abstract ProcessBuilder createProcessBuilder() throws IOException;

  // =========================================================================
  // Lifecycle Methods
  // =========================================================================

  /**
   * Starts the process.
   *
   * @return The process PID
   * @throws IOException if starting fails
   * @throws IllegalStateException if already running
   */
  public long start() throws IOException {
    if (process != null && process.isAlive()) {
      throw new IllegalStateException(name + " already running with PID " + pid);
    }

    ProcessBuilder pb = createProcessBuilder();
    pb.directory(workingDirectory.toFile());

    // Redirect error stream to capture both stdout and stderr
    pb.redirectErrorStream(false);

    log.info("[{}] Starting: {}", name, String.join(" ", pb.command()));
    process = pb.start();
    pid = process.pid();

    // Register for shutdown hook cleanup
    ACTIVE_PROCESSES.add(this);

    // Start log piping threads
    if (logPipingEnabled) {
      startLogPiping();
    }

    log.info("[{}] Started with PID {}", name, pid);
    return pid;
  }

  /**
   * Enables or disables log piping. Must be called before {@link #start()}.
   *
   * @param enabled true to pipe process output to test logs
   * @return this for chaining
   */
  public ManagedProcess withLogPiping(boolean enabled) {
    this.logPipingEnabled = enabled;
    return this;
  }

  private void startLogPiping() {
    stdoutThread = new Thread(() -> pipeStream(process.getInputStream(), "OUT"), name + "-stdout");
    stderrThread = new Thread(() -> pipeStream(process.getErrorStream(), "ERR"), name + "-stderr");

    stdoutThread.setDaemon(true);
    stderrThread.setDaemon(true);

    stdoutThread.start();
    stderrThread.start();
  }

  private void pipeStream(java.io.InputStream stream, String streamType) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        // Forward the child process's output through the test logger (PMD SystemPrintln).
        log.info("[{}:{}] {}", name, streamType, line);
      }
    } catch (IOException e) {
      // Process likely terminated - this is expected
      log.trace("[{}] {} stream closed: {}", name, streamType, e.getMessage());
    }
  }

  /**
   * Requests graceful shutdown of the process.
   *
   * @param timeout Maximum time to wait for graceful shutdown
   * @return true if process stopped gracefully within timeout
   * @throws InterruptedException if waiting is interrupted
   */
  public boolean stopGracefully(Duration timeout) throws InterruptedException {
    if (process == null || !process.isAlive()) {
      return true;
    }

    log.info("[{}] Requesting graceful shutdown (PID {})", name, pid);
    process.destroy(); // Sends SIGTERM on Unix, terminates on Windows

    boolean terminated = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!terminated) {
      log.warn("[{}] Did not stop gracefully within {}s, force killing", name, timeout.toSeconds());
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }

    return terminated;
  }

  /**
   * Force-kills the process immediately.
   *
   * @return true if kill signal was sent
   */
  public boolean forceKill() {
    if (process == null) {
      return false;
    }

    log.info("[{}] Force-killing PID {}", name, pid);
    process.destroyForcibly();
    return true;
  }

  /**
   * Waits for the process to terminate.
   *
   * @param timeout Maximum time to wait
   * @return true if process terminated within timeout
   */
  public boolean waitForTermination(Duration timeout) {
    if (process == null) {
      return true;
    }

    log.debug("[{}] Waiting up to {}s for termination", name, timeout.toSeconds());
    long deadline = System.currentTimeMillis() + timeout.toMillis();

    while (System.currentTimeMillis() < deadline) {
      if (!process.isAlive()) {
        log.info("[{}] Terminated (PID {})", name, pid);
        return true;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    log.warn("[{}] Did not terminate within {}s", name, timeout.toSeconds());
    return false;
  }

  // =========================================================================
  // State Queries
  // =========================================================================

  /**
   * Returns the process PID, or -1 if not started.
   */
  public long getPid() {
    return pid;
  }

  /**
   * Returns the process name (for logging).
   */
  public String getName() {
    return name;
  }

  /**
   * Checks if the process is currently alive.
   */
  public boolean isAlive() {
    return process != null && process.isAlive();
  }

  /**
   * Checks if a process with the given PID is alive in the OS.
   *
   * @param pid Process ID to check
   * @return true if process exists and is alive
   */
  public static boolean isProcessAlive(long pid) {
    return ProcessHandle.of(pid)
        .map(ProcessHandle::isAlive)
        .orElse(false);
  }

  /**
   * Returns the ProcessHandle for the given PID.
   *
   * @param pid Process ID
   * @return Optional containing the ProcessHandle, or empty if not found
   */
  public static Optional<ProcessHandle> getProcessHandle(long pid) {
    return ProcessHandle.of(pid);
  }

  /**
   * Returns the underlying Process object for advanced operations.
   */
  public Process getProcess() {
    return process;
  }

  /**
   * Returns the working directory.
   */
  public Path getWorkingDirectory() {
    return workingDirectory;
  }

  // =========================================================================
  // Closeable
  // =========================================================================

  @Override
  public void close() {
    if (process != null && process.isAlive()) {
      log.info("[{}] Closing - killing PID {}", name, pid);
      process.destroyForcibly();
      try {
        process.waitFor(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Unregister from shutdown hook
    ACTIVE_PROCESSES.remove(this);

    // Stop and wait for log piping threads to finish
    // This ensures all file handles are released before returning
    if (stdoutThread != null) {
      stdoutThread.interrupt();
      try {
        stdoutThread.join(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (stderrThread != null) {
      stderrThread.interrupt();
      try {
        stderrThread.join(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Brief pause to ensure OS releases file handles (Windows-specific workaround)
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // =========================================================================
  // Utility: List Child Processes
  // =========================================================================

  /**
   * Lists all child processes of the current JVM.
   * Useful for detecting zombie processes in tests.
   *
   * @return List of child process handles
   */
  public static List<ProcessHandle> listChildProcesses() {
    return ProcessHandle.current().children().toList();
  }

  /**
   * Lists all descendant processes of the current JVM.
   *
   * @return List of descendant process handles
   */
  public static List<ProcessHandle> listDescendantProcesses() {
    return ProcessHandle.current().descendants().toList();
  }

  /**
   * Returns the count of currently active ManagedProcess instances.
   * Useful for debugging and leak detection.
   */
  public static int getActiveProcessCount() {
    return ACTIVE_PROCESSES.size();
  }
}
