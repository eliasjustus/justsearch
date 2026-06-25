/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.chaos;

import io.justsearch.systemtests.process.ManagedProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages Knowledge Server worker processes for system tests.
 *
 * <p>Extends {@link ManagedProcess} to provide worker-specific functionality:
 * <ul>
 *   <li>Spawning worker processes with configurable JVM parameters</li>
 *   <li>Support for JAR or distribution script launch modes</li>
 *   <li>Native Memory Tracking (NMT) for soak tests</li>
 *   <li>Test configuration directory setup (SSOT copying)</li>
 *   <li>Memory-mapped file (MMF) signal path management</li>
 * </ul>
 *
 * <p>The worker can be launched via:
 * <ul>
 *   <li>Distribution script (preferred): {@code installDist/bin/indexer-worker.bat}</li>
 *   <li>JAR file: {@code java -jar worker.jar}</li>
 * </ul>
 */
public final class WorkerProcessManager extends ManagedProcess {
  private static final Logger log = LoggerFactory.getLogger(WorkerProcessManager.class);

  private final Path workerPath;
  private final Path dataDir;
  private final Path signalFilePath;
  private final boolean useScript;
  private final Path externalTestConfigDir;

  private String extraJvmArgs = "";

  /**
   * Creates a new WorkerProcessManager using a JAR file.
   *
   * @param workerJarPath Path to the indexer-worker JAR file
   * @param dataDir Path to the data directory for this test
   */
  public WorkerProcessManager(Path workerJarPath, Path dataDir) {
    this(workerJarPath, dataDir, false);
  }

  /**
   * Creates a new WorkerProcessManager.
   *
   * @param workerPath Path to the worker executable (JAR or script)
   * @param dataDir Path to the data directory for this test
   * @param useScript true to use distribution script, false for JAR
   */
  public WorkerProcessManager(Path workerPath, Path dataDir, boolean useScript) {
    this(workerPath, dataDir, useScript, null);
  }

  /**
   * Creates a new WorkerProcessManager with external test config.
   *
   * @param workerPath Path to the worker executable (JAR or script)
   * @param dataDir Path to the data directory for this test
   * @param useScript true to use distribution script, false for JAR
   * @param externalTestConfigDir Pre-configured test config directory (may be null)
   */
  public WorkerProcessManager(Path workerPath, Path dataDir, boolean useScript, Path externalTestConfigDir) {
    super("WORKER", dataDir);
    this.workerPath = workerPath;
    this.dataDir = dataDir;
    this.signalFilePath = dataDir.resolve("worker_signal.lock");
    this.useScript = useScript;
    this.externalTestConfigDir = externalTestConfigDir;
  }

  /**
   * Creates a WorkerProcessManager using the distribution script.
   *
   * @param distDir Path to the distribution directory (e.g., build/install/indexer-worker)
   * @param dataDir Path to the data directory for this test
   * @return WorkerProcessManager configured to use the distribution script
   */
  public static WorkerProcessManager fromDistribution(Path distDir, Path dataDir) {
    return fromDistributionWithConfig(distDir, dataDir, null);
  }

  /**
   * Creates a WorkerProcessManager using the distribution script with pre-configured test config.
   *
   * @param distDir Path to the distribution directory (e.g., build/install/indexer-worker)
   * @param dataDir Path to the data directory for this test
   * @param testConfigDir Pre-configured test config directory with SSOT (may be null for auto-setup)
   * @return WorkerProcessManager configured to use the distribution script
   */
  public static WorkerProcessManager fromDistributionWithConfig(Path distDir, Path dataDir, Path testConfigDir) {
    boolean isWindows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows");
    String scriptName = isWindows ? "indexer-worker.bat" : "indexer-worker";
    Path scriptPath = distDir.resolve("bin").resolve(scriptName);
    return new WorkerProcessManager(scriptPath, dataDir, true, testConfigDir);
  }

  /**
   * Creates a WorkerProcessManager using the distribution with system properties pointing
   * to the real project root. NO directory copying occurs - the worker reads SSOT directly
   * from the project root.
   *
   * <p>This is the preferred method for tests that don't need isolated config directories.
   * It eliminates disk space bloat and stale config issues.
   *
   * @param distDir Path to the distribution directory (e.g., build/install/indexer-worker)
   * @param dataDir Path to the data directory for this test
   * @param projectRoot Path to the project root (must contain SSOT/ and config/)
   * @return WorkerProcessManager configured to use system properties for SSOT resolution
   */
  public static WorkerProcessManager fromDistributionNoConfig(Path distDir, Path dataDir, Path projectRoot) {
    boolean isWindows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows");
    String scriptName = isWindows ? "indexer-worker.bat" : "indexer-worker";
    Path scriptPath = distDir.resolve("bin").resolve(scriptName);

    // Pass projectRoot as externalTestConfigDir - the createJavaWithArgfileProcessBuilder()
    // method will detect it's a project root (has SSOT/ and settings.gradle.kts) and skip copying
    WorkerProcessManager manager = new WorkerProcessManager(scriptPath, dataDir, true, projectRoot);

    // Pre-configure JVM args for system property approach
    manager.withJvmArgs(
        "-Djustsearch.repo.root=" + projectRoot.toAbsolutePath(),
        "-Djustsearch.ssot.path=" + projectRoot.resolve("SSOT").toAbsolutePath(),
        "-Djustsearch.config=" + projectRoot.resolve("config/application.yaml").toAbsolutePath()
    );

    log.info("Created WorkerProcessManager with no-copy mode, project root: {}", projectRoot);
    return manager;
  }

  /**
   * Enables Native Memory Tracking (NMT) for the worker JVM.
   * Must be called before {@link #spawnWorker()}.
   *
   * @return this manager for chaining
   */
  public WorkerProcessManager enableNmt() {
    this.extraJvmArgs = "-XX:NativeMemoryTracking=summary";
    log.info("NMT enabled for worker process");
    return this;
  }

  /**
   * Sets additional JVM arguments for the worker process.
   * Must be called before {@link #spawnWorker()}.
   *
   * @param args Additional JVM arguments (space-separated)
   * @return this manager for chaining
   */
  public WorkerProcessManager withJvmArgs(String args) {
    this.extraJvmArgs = args;
    return this;
  }

  /**
   * Sets additional JVM arguments for the worker process.
   * Must be called before {@link #spawnWorker()}.
   *
   * @param args Additional JVM arguments as varargs
   * @return this manager for chaining
   */
  public WorkerProcessManager withJvmArgs(String... args) {
    this.extraJvmArgs = String.join(" ", args);
    return this;
  }

  /**
   * Spawns the worker process.
   *
   * @return The worker process PID
   * @throws IOException if spawning fails
   */
  public long spawnWorker() throws IOException {
    Files.createDirectories(dataDir);
    return start(); // Delegate to ManagedProcess.start()
  }

  @Override
  protected ProcessBuilder createProcessBuilder() throws IOException {
    ProcessBuilder pb;
    if (useScript) {
      pb = createScriptProcessBuilder();
    } else {
      pb = createJarProcessBuilder();
    }
    return pb;
  }

  private ProcessBuilder createJarProcessBuilder() {
    List<String> command = new ArrayList<>();
    command.add(getJavaExecutable());
    command.add("-Xmx256m");

    // Add extra JVM args (e.g., NMT for soak tests, or custom config)
    if (extraJvmArgs != null && !extraJvmArgs.isBlank()) {
      for (String arg : extraJvmArgs.split("\\s+")) {
        command.add(arg);
      }
    }

    command.add("-Djustsearch.worker.signal_path=" + signalFilePath.toAbsolutePath());
    command.add("-Djustsearch.data.dir=" + dataDir.toAbsolutePath());
    command.add("-Djustsearch.data_dir=" + dataDir.toAbsolutePath()); // back-compat
    command.add("-jar");
    command.add(workerPath.toAbsolutePath().toString());

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(dataDir.toFile());

    // Set environment variables to ensure worker uses correct paths
    pb.environment().put("JUSTSEARCH_DATA_DIR", dataDir.toAbsolutePath().toString());
    pb.environment().put("JUSTSEARCH_WORKER_SIGNAL_PATH", signalFilePath.toAbsolutePath().toString());

    // Set repo root for SSOT resolution (if not already set via JVM args)
    Path projectRoot = findProjectRoot(workerPath);
    if (projectRoot != null) {
      pb.environment().put("JUSTSEARCH_REPO_ROOT", projectRoot.toAbsolutePath().toString());
      pb.environment().put("JUSTSEARCH_SSOT_PATH", projectRoot.resolve("SSOT").toAbsolutePath().toString());
    }

    return pb;
  }

  private ProcessBuilder createScriptProcessBuilder() throws IOException {
    boolean isWindows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows");

    // On Windows, the batch script can hit command line length limits
    if (isWindows) {
      return createJavaWithArgfileProcessBuilder();
    }

    // On Unix, run the script directly
    ProcessBuilder pb = new ProcessBuilder(workerPath.toAbsolutePath().toString());

    StringBuilder javaOpts = new StringBuilder();
    javaOpts.append("-Xmx256m ");
    if (extraJvmArgs != null && !extraJvmArgs.isBlank()) {
      javaOpts.append(extraJvmArgs).append(" ");
    }
    javaOpts.append("-Djustsearch.worker.signal_path=").append(signalFilePath.toAbsolutePath()).append(" ");
    javaOpts.append("-Djustsearch.data.dir=").append(dataDir.toAbsolutePath()).append(" ");
    javaOpts.append("-Djustsearch.data_dir=").append(dataDir.toAbsolutePath());

    pb.environment().put("JAVA_OPTS", javaOpts.toString());

    // Set repo root for SSOT resolution
    Path projectRoot = findProjectRoot(workerPath);
    if (projectRoot != null) {
      pb.environment().put("JUSTSEARCH_REPO_ROOT", projectRoot.toAbsolutePath().toString());
      pb.environment().put("JUSTSEARCH_SSOT_PATH", projectRoot.resolve("SSOT").toAbsolutePath().toString());
    }

    return pb;
  }

  private ProcessBuilder createJavaWithArgfileProcessBuilder() throws IOException {
    Path distDir = workerPath.getParent().getParent();
    Path libDir = distDir.resolve("lib");

    // Determine the test config directory
    // If externalTestConfigDir is a project root (has SSOT/ and settings.gradle.kts),
    // use it directly without copying - this is the "no-copy" mode from fromDistributionNoConfig()
    Path testConfigDir;
    boolean isProjectRoot = externalTestConfigDir != null
        && Files.exists(externalTestConfigDir.resolve("SSOT"))
        && Files.exists(externalTestConfigDir.resolve("settings.gradle.kts"));

    if (isProjectRoot) {
      // Project root passed directly via fromDistributionNoConfig() - no copying needed
      testConfigDir = externalTestConfigDir;
      log.info("Using project root directly (no SSOT copy): {}", testConfigDir);
    } else if (externalTestConfigDir != null) {
      // Pre-configured test config dir (legacy fromDistributionWithConfig mode) - use as-is
      testConfigDir = externalTestConfigDir;
      log.info("Using pre-configured test config directory: {}", testConfigDir);
    } else {
      // No config dir provided - set up with copy/symlink (legacy fallback)
      testConfigDir = setupTestConfig();
      log.info("Set up test config directory with SSOT copy: {}", testConfigDir);
    }

    // Build classpath from all JARs in lib
    StringBuilder classpath = new StringBuilder();
    try (var files = Files.list(libDir)) {
      files.filter(p -> p.toString().endsWith(".jar"))
          .forEach(jar -> {
            if (classpath.length() > 0) {
              classpath.append(java.io.File.pathSeparator);
            }
            classpath.append(jar.toAbsolutePath());
          });
    }

    Path configFile = testConfigDir.resolve("config/application.yaml");

    // Determine repo root for SSOT resolution
    Path ssotDir = testConfigDir.resolve("SSOT");
    Path repoRootForWorker;
    if (Files.isDirectory(ssotDir)) {
      repoRootForWorker = testConfigDir;
      log.info("Using test config as repo root: {}", testConfigDir);
    } else {
      repoRootForWorker = findProjectRoot(workerPath);
      log.info("Using project root for worker: {}", repoRootForWorker);
    }

    // Write arguments to a file to avoid Windows command line length limits
    Path argFile = dataDir.resolve("worker_args.txt");
    StringBuilder args = new StringBuilder();
    args.append("-Xmx256m\n");
    if (extraJvmArgs != null && !extraJvmArgs.isBlank()) {
      for (String arg : extraJvmArgs.split("\\s+")) {
        args.append(arg).append("\n");
      }
    }
    args.append("-Djustsearch.worker.signal_path=").append(signalFilePath.toAbsolutePath()).append("\n");
    args.append("-Djustsearch.data.dir=").append(dataDir.toAbsolutePath()).append("\n");
    args.append("-Djustsearch.data_dir=").append(dataDir.toAbsolutePath()).append("\n"); // back-compat
    if (Files.exists(configFile)) {
      args.append("-Djustsearch.config=").append(configFile.toAbsolutePath()).append("\n");
    }
    // Add repo root and SSOT path as system properties (critical for SSOT resolution)
    if (repoRootForWorker != null) {
      args.append("-Djustsearch.repo.root=").append(repoRootForWorker.toAbsolutePath()).append("\n");
      args.append("-Djustsearch.ssot.path=").append(repoRootForWorker.resolve("SSOT").toAbsolutePath()).append("\n");
    }
    args.append("-cp\n");
    args.append(classpath.toString()).append("\n");
    args.append("io.justsearch.indexerworker.IndexerWorker");
    Files.writeString(argFile, args.toString());

    log.info("Created worker args file: {}", argFile);

    ProcessBuilder pb = new ProcessBuilder(
        getJavaExecutable(),
        "@" + argFile.toAbsolutePath()
    );

    // Set working directory:
    // - For no-copy mode (isProjectRoot=true), use dataDir to avoid polluting project root
    // - For legacy modes, use testConfigDir as before
    if (isProjectRoot) {
      pb.directory(dataDir.toFile());
      log.info("Set worker working directory to data dir (no-copy mode): {}", dataDir);
    } else {
      pb.directory(testConfigDir.toFile());
      log.info("Set worker working directory to test config: {}", testConfigDir);
    }

    pb.environment().put("JUSTSEARCH_DATA_DIR", dataDir.toAbsolutePath().toString());
    pb.environment().put("JUSTSEARCH_WORKER_SIGNAL_PATH", signalFilePath.toAbsolutePath().toString());

    // Also set env vars for repo root (backup to system properties in argfile)
    if (repoRootForWorker != null) {
      pb.environment().put("JUSTSEARCH_REPO_ROOT", repoRootForWorker.toAbsolutePath().toString());
      pb.environment().put("JUSTSEARCH_SSOT_PATH", repoRootForWorker.resolve("SSOT").toAbsolutePath().toString());
    }

    log.info("Set JUSTSEARCH_DATA_DIR={}", dataDir.toAbsolutePath());

    return pb;
  }

  /**
   * Sets up a test configuration directory by symlinking to the real SSOT.
   */
  private Path setupTestConfig() throws IOException {
    Path testConfigDir = dataDir.resolve("test-config");
    Files.createDirectories(testConfigDir);

    Path projectRoot = findProjectRoot(workerPath);
    if (projectRoot == null) {
      throw new IOException("Cannot find project root with SSOT directory");
    }

    Path realConfig = projectRoot.resolve("config/application.yaml");
    Path realSsot = projectRoot.resolve("SSOT");

    // Copy application.yaml
    Path targetConfig = testConfigDir.resolve("config/application.yaml");
    Files.createDirectories(targetConfig.getParent());
    if (Files.exists(realConfig)) {
      Files.copy(realConfig, targetConfig, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      log.debug("Copied config: {} -> {}", realConfig, targetConfig);
    }

    // Create symlink or copy SSOT directory
    Path targetSsot = testConfigDir.resolve("SSOT");
    if (Files.exists(realSsot)) {
      try {
        Files.createSymbolicLink(targetSsot, realSsot);
        log.info("Created SSOT symlink: {} -> {}", targetSsot, realSsot);
      } catch (UnsupportedOperationException | IOException e) {
        log.info("Symlink failed, copying SSOT directory...");
        copyDirectory(realSsot, targetSsot);
        log.info("Copied SSOT directory: {} -> {}", realSsot, targetSsot);
      }
    } else {
      log.warn("Real SSOT directory not found: {}", realSsot);
    }

    log.info("Set up test config directory: {}", testConfigDir);
    return testConfigDir;
  }

  /**
   * Recursively copies a directory.
   */
  private void copyDirectory(Path source, Path target) throws IOException {
    try (var walk = Files.walk(source)) {
      walk.forEach(sourcePath -> {
        try {
          Path targetPath = target.resolve(source.relativize(sourcePath));
          if (Files.isDirectory(sourcePath)) {
            Files.createDirectories(targetPath);
          } else {
            Files.createDirectories(targetPath.getParent());
            Files.copy(sourcePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          }
        } catch (IOException e) {
          throw new java.io.UncheckedIOException(e);
        }
      });
    }
  }

  private static Path findProjectRoot(Path start) {
    Path current = start;
    while (current != null) {
      if (Files.exists(current.resolve("SSOT")) || Files.exists(current.resolve("settings.gradle.kts"))) {
        return current;
      }
      current = current.getParent();
    }
    return null;
  }

  // =========================================================================
  // Compatibility Methods (delegate to ManagedProcess)
  // =========================================================================

  /**
   * Checks if a process with the given PID exists in the OS process list.
   * Delegates to the static method in ManagedProcess.
   *
   * @param pid The process ID to check
   * @return true if the process exists and is alive
   */
  public static boolean isProcessAlive(long pid) {
    return ManagedProcess.isProcessAlive(pid);
  }

  /**
   * Returns the ProcessHandle for the given PID.
   * Delegates to the static method in ManagedProcess.
   *
   * @param pid The process ID
   * @return Optional containing the ProcessHandle, or empty if not found
   */
  public static Optional<ProcessHandle> getProcessHandle(long pid) {
    return ManagedProcess.getProcessHandle(pid);
  }

  /**
   * Returns process info for the given PID.
   *
   * @param pid The process ID
   * @return Optional containing the process info, or empty if not found
   */
  public Optional<ProcessHandle.Info> getProcessInfo(long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::info);
  }

  /**
   * Force-kills a process by PID using OS-level signals.
   *
   * @param pid The process ID to kill
   * @return true if the kill signal was sent successfully
   */
  public boolean forceKill(long pid) {
    log.info("Force-killing process with PID: {}", pid);
    return ProcessHandle.of(pid)
        .map(ProcessHandle::destroyForcibly)
        .orElse(false);
  }

  /**
   * Waits for a process to terminate within the given timeout.
   *
   * @param pid The process ID to wait for
   * @param timeout Maximum time to wait
   * @return true if the process terminated within the timeout
   */
  public boolean waitForTermination(long pid, Duration timeout) {
    log.info("Waiting up to {}s for PID {} to terminate", timeout.toSeconds(), pid);
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    long pollInterval = 100;

    while (System.currentTimeMillis() < deadline) {
      if (!isProcessAlive(pid)) {
        log.info("PID {} terminated", pid);
        return true;
      }
      try {
        Thread.sleep(pollInterval);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    log.warn("PID {} did not terminate within {}s", pid, timeout.toSeconds());
    return false;
  }

  /**
   * Returns the signal file path used for MMF coordination.
   */
  public Path getSignalFilePath() {
    return signalFilePath;
  }

  /**
   * Returns the data directory.
   */
  public Path getDataDir() {
    return dataDir;
  }

  /**
   * Lists all child processes of the current JVM.
   *
   * @return Stream of child process handles
   */
  public static java.util.stream.Stream<ProcessHandle> listJvmChildProcesses() {
    return ProcessHandle.current().children();
  }

  /**
   * Lists all descendant processes of the current JVM.
   *
   * @return Stream of descendant process handles
   */
  public static java.util.stream.Stream<ProcessHandle> listJvmDescendantProcesses() {
    return ProcessHandle.current().descendants();
  }

  private String getJavaExecutable() {
    String javaHome = System.getProperty("java.home");
    String separator = System.getProperty("file.separator");
    String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")
        ? "java.exe" : "java";
    return javaHome + separator + "bin" + separator + executable;
  }
}
