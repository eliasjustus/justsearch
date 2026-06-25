/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized temporary file management with automatic cleanup.
 *
 * <p>Tracks all created temporary files and directories, providing cleanup on close
 * or via JVM shutdown hook. Designed for use during VDU processing where PDF pages
 * are rendered to temporary images.
 *
 * <h2>Usage</h2>
 * <pre>
 * try (TempFileManager temp = new TempFileManager(dataDir.resolve("temp"))) {
 *     temp.registerShutdownHook();
 *
 *     Path tempDir = temp.createTempDirectory("vdu_");
 *     // ... use tempDir for processing ...
 *
 *     // Cleanup happens automatically at end of try block
 * }
 * </pre>
 */
public final class TempFileManager implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(TempFileManager.class);

  private final Path tempRoot;
  private final Set<Path> trackedPaths = ConcurrentHashMap.newKeySet();
  private volatile boolean closed = false;
  private Thread shutdownHook;

  /**
   * Creates a new TempFileManager with the specified root directory.
   *
   * @param tempRoot the root directory for temporary files (will be created if needed)
   * @throws IOException if the root directory cannot be created
   */
  public TempFileManager(Path tempRoot) throws IOException {
    this.tempRoot = tempRoot.toAbsolutePath().normalize();
    Files.createDirectories(this.tempRoot);
    LOG.debug("TempFileManager initialized with root: {}", this.tempRoot);
  }

  /**
   * Creates a tracked temporary file that will be cleaned up on close.
   *
   * @param prefix filename prefix
   * @param suffix filename suffix (e.g., ".png")
   * @return path to the created file
   * @throws IOException if file creation fails
   * @throws IllegalStateException if manager is closed
   */
  public Path createTempFile(String prefix, String suffix) throws IOException {
    checkNotClosed();
    Path file = Files.createTempFile(tempRoot, prefix, suffix);
    trackedPaths.add(file);
    LOG.trace("Created temp file: {}", file);
    return file;
  }

  /**
   * Creates a tracked temporary directory that will be cleaned up on close.
   *
   * @param prefix directory name prefix
   * @return path to the created directory
   * @throws IOException if directory creation fails
   * @throws IllegalStateException if manager is closed
   */
  public Path createTempDirectory(String prefix) throws IOException {
    checkNotClosed();
    Path dir = Files.createTempDirectory(tempRoot, prefix);
    trackedPaths.add(dir);
    LOG.trace("Created temp directory: {}", dir);
    return dir;
  }

  /**
   * Manually cleans up a specific tracked file or directory.
   *
   * @param path the path to clean up
   */
  public void cleanup(Path path) {
    if (path == null) {
      return;
    }
    try {
      if (Files.isDirectory(path)) {
        deleteDirectoryRecursively(path);
      } else if (Files.exists(path)) {
        Files.deleteIfExists(path);
      }
      trackedPaths.remove(path);
      LOG.trace("Cleaned up: {}", path);
    } catch (IOException e) {
      LOG.warn("Failed to cleanup temp path: {}", path, e);
    }
  }

  /**
   * Registers a JVM shutdown hook to ensure cleanup on abnormal termination.
   *
   * <p>Only one shutdown hook is registered per instance; subsequent calls are no-ops.
   */
  public void registerShutdownHook() {
    if (shutdownHook != null) {
      return; // Already registered
    }
    shutdownHook = new Thread(this::cleanupAll, "TempFileManager-Shutdown");
    Runtime.getRuntime().addShutdownHook(shutdownHook);
    LOG.debug("Registered shutdown hook for TempFileManager");
  }

  /**
   * Cleans up all tracked files and directories.
   */
  public void cleanupAll() {
    if (trackedPaths.isEmpty()) {
      return;
    }
    LOG.debug("Cleaning up {} tracked temp paths", trackedPaths.size());

    // Clean up in reverse order (deepest paths first)
    trackedPaths.stream()
        .sorted(Comparator.comparing(Path::getNameCount).reversed())
        .forEach(this::cleanup);

    trackedPaths.clear();
  }

  /**
   * Closes this manager and cleans up all tracked files.
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;

    cleanupAll();

    // Remove shutdown hook if registered (and we're not in shutdown)
    if (shutdownHook != null) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException e) {
        // JVM is shutting down, ignore
      }
      shutdownHook = null;
    }

    LOG.debug("TempFileManager closed");
  }

  private void checkNotClosed() {
    if (closed) {
      throw new IllegalStateException("TempFileManager is closed");
    }
  }

  private void deleteDirectoryRecursively(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }

    Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.deleteIfExists(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        Files.deleteIfExists(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }
}
