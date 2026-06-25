/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global process lock for a JustSearch data directory.
 *
 * <p>Why: JustSearch uses a multi-process architecture (Head + Worker) and stores mutable state under
 * a shared {@code dataDir} (Lucene index, jobs.db, logs, etc.). Running two app instances against the
 * same {@code dataDir} is unsafe, especially on Windows where file locking is strict.
 *
 * <p>This lock is intended to be acquired by the Main process early (before spawning the Worker) and
 * held for the lifetime of the app instance.
 */
public final class AppInstanceLock implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(AppInstanceLock.class);

  /**
   * Tempdoc 501 §3.7: tracks dataDirs for which this JVM already holds an exclusive lock.
   * Required because {@code FileChannel.tryLock()} throws {@link OverlappingFileLockException}
   * on a second acquisition in the same JVM, even from a different {@link FileChannel}.
   *
   * <p>HeadlessApp acquires the lock early in boot; KnowledgeServerBootstrap (which also
   * calls {@code .acquire()} for the standalone-test launch path) consults this set to skip
   * its own acquisition when the Head already holds the lock for the same dataDir.
   */
  private static final java.util.Set<Path> HELD_IN_JVM =
      java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

  /**
   * Returns {@code true} if this JVM already holds an exclusive lock for {@code dataDir}.
   * Callers in the same JVM should use this to skip a redundant acquire that would throw
   * {@link OverlappingFileLockException}.
   */
  public static boolean isHeldByThisJvm(Path dataDir) {
    return HELD_IN_JVM.contains(dataDir.toAbsolutePath().normalize());
  }

  private final Path lockPath;
  private final Path canonicalDataDir;
  private FileChannel channel;
  private FileLock lock;

  public AppInstanceLock(Path dataDir) {
    if (dataDir == null) {
      throw new IllegalArgumentException("dataDir is required");
    }
    this.canonicalDataDir = dataDir.toAbsolutePath().normalize();
    this.lockPath = canonicalDataDir.resolve("app.lock");
  }

  public boolean isHeld() {
    return lock != null && lock.isValid();
  }

  /**
   * Acquire an exclusive lock for this dataDir.
   *
   * <p>If the lock is held by a dead process (detectable via PID metadata), the stale lock file is
   * deleted and acquisition is retried once. This handles the case where a previous JVM crashed
   * without releasing its OS file lock (possible on some platforms or after forced process kill).
   *
   * @throws AppInstanceLockException if another process already holds the lock
   */
  public void acquire() throws IOException {
    acquireInner(false);
  }

  private void acquireInner(boolean isRetry) throws IOException {
    if (isHeld()) {
      return;
    }

    Files.createDirectories(lockPath.getParent());

    // Open/create lock file. Keep channel open for lifetime of the lock.
    channel =
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);

    try {
      lock = channel.tryLock(); // Exclusive lock
      if (lock == null) {
        closeChannelQuietly();
        if (!isRetry && tryRecoverStaleLock()) {
          acquireInner(true);
          return;
        }
        throw new AppInstanceLockException(
            "Another JustSearch instance is already running for dataDir=" + lockPath.getParent());
      }
    } catch (OverlappingFileLockException e) {
      closeChannelQuietly();
      if (!isRetry && tryRecoverStaleLock()) {
        acquireInner(true);
        return;
      }
      throw new AppInstanceLockException(
          "Another JustSearch instance is already running for dataDir=" + lockPath.getParent(), e);
    } catch (IOException e) {
      closeChannelQuietly();
      // Normalize into a clearer message for common Windows cases (AccessDenied, etc.).
      throw new AppInstanceLockException(
          "Failed to acquire app lock at " + lockPath + " (dataDir=" + lockPath.getParent() + ")", e);
    }

    // Best-effort: write metadata so humans can see who holds the lock.
    writeOwnerMetadataBestEffort();

    // Tempdoc 501 §3.7: mark in-JVM holders so re-entrant acquires from other components
    // (e.g., KnowledgeServerBootstrap inside the same Head JVM) skip cleanly.
    HELD_IN_JVM.add(canonicalDataDir);

    log.info("Acquired app lock: {}", lockPath);
  }

  private void closeChannelQuietly() {
    try {
      if (channel != null) {
        channel.close();
      }
    } catch (Exception ignored) {
      // best effort
    } finally {
      channel = null;
    }
  }

  /**
   * Attempt to recover from a stale lock by checking whether the holding process is dead.
   *
   * <p>Reads PID and start timestamp from the lock file metadata. If the PID no longer exists, or
   * has been reused by a different process (start time mismatch), deletes the lock file so the
   * caller can retry acquisition.
   *
   * @return true if the lock file was deleted (caller should retry), false otherwise
   */
  private boolean tryRecoverStaleLock() {
    try {
      if (!Files.exists(lockPath)) {
        return false;
      }
      String content = Files.readString(lockPath);
      Long pid = parsePidFromMetadata(content);
      if (pid == null) {
        return false;
      }

      var handleOpt = ProcessHandle.of(pid);
      if (handleOpt.isEmpty()) {
        // PID is dead — safe to recover.
        log.warn("Recovering stale app lock: PID {} is dead, deleting {}", pid, lockPath);
        Files.deleteIfExists(lockPath);
        return true;
      }

      // PID exists — check if it's the same process that wrote the lock.
      Long metadataStartMs = parseStartedAtFromMetadata(content);
      if (metadataStartMs != null) {
        var actualStart = handleOpt.get().info().startInstant();
        if (actualStart.isPresent()) {
          long actualMs = actualStart.get().toEpochMilli();
          if (Math.abs(actualMs - metadataStartMs) > 1000) {
            // PID was reused by a different process — safe to recover.
            log.warn(
                "Recovering stale app lock: PID {} reused (metadata start={}, actual start={}), deleting {}",
                pid, metadataStartMs, actualMs, lockPath);
            Files.deleteIfExists(lockPath);
            return true;
          }
        }
      }

      // Holder is genuinely alive — cannot recover.
      return false;
    } catch (Exception e) {
      log.debug("Stale lock recovery failed (will throw original error): {}", e.getMessage());
      return false;
    }
  }

  static Long parsePidFromMetadata(String content) {
    if (content == null) {
      return null;
    }
    for (String line : content.split("\\R")) {
      if (line.startsWith("pid=")) {
        try {
          return Long.parseLong(line.substring(4).trim());
        } catch (NumberFormatException ignored) {
          // malformed
        }
      }
    }
    return null;
  }

  static Long parseStartedAtFromMetadata(String content) {
    if (content == null) {
      return null;
    }
    for (String line : content.split("\\R")) {
      if (line.startsWith("started_at=")) {
        String value = line.substring(11).trim();
        try {
          return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
          // Try as raw epoch millis
          try {
            return Long.parseLong(value);
          } catch (NumberFormatException ignored2) {
            // malformed
          }
        }
      }
    }
    return null;
  }

  private void writeOwnerMetadataBestEffort() {
    try {
      long pid = ProcessHandle.current().pid();
      String content =
          "pid=" + pid + "\nstarted_at=" + Instant.now() + "\n";
      channel.truncate(0);
      channel.position(0);
      channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
      channel.force(true);
    } catch (Exception e) {
      // Best-effort only; do not fail startup.
      log.debug("Failed to write app.lock metadata (best-effort): {}", e.getMessage());
    }
  }

  @Override
  public void close() {
    boolean wasHeld = (lock != null);
    // Release lock first, then close channel.
    if (lock != null) {
      try {
        lock.release();
      } catch (Exception ignored) {
        // best effort
      } finally {
        lock = null;
      }
    }
    if (channel != null) {
      try {
        channel.close();
      } catch (Exception ignored) {
        // best effort
      } finally {
        channel = null;
      }
    }
    // Tempdoc 501 §3.7: clear in-JVM marker so a clean restart in the same JVM can
    // re-acquire (relevant in tests that start/stop the Head in-process).
    if (wasHeld) {
      HELD_IN_JVM.remove(canonicalDataDir);
    }
  }

  /** Thrown when the dataDir is already in use by another JustSearch instance. */
  public static final class AppInstanceLockException extends IOException {
    public AppInstanceLockException(String message) {
      super(message);
    }

    public AppInstanceLockException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
