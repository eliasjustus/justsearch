/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.util;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker-held lock for an index base path.
 *
 * <p>This prevents two different Workers (potentially from different data dirs) from operating on
 * the same {@code indexBasePath} when {@code justsearch.index.base_path} is overridden.
 *
 * <p>Includes stale-lock recovery: if the lock is held by a dead process (detectable via PID
 * metadata), the lock file is deleted and acquisition is retried once.
 */
public final class IndexRootLock implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(IndexRootLock.class);

  private final Path lockFile;
  private FileChannel channel;
  private FileLock lock;

  public IndexRootLock(Path indexBasePath) {
    if (indexBasePath == null) {
      throw new IllegalArgumentException("indexBasePath is required");
    }
    Path base = indexBasePath.toAbsolutePath().normalize();
    if (base.getParent() == null) {
      throw new IllegalArgumentException("indexBasePath must not be a filesystem root: " + base);
    }
    // Important for Windows: IndexGenerationManager may rename/move the indexBasePath directory during
    // legacy import. If we lock a file *inside* indexBasePath, the directory move can fail with
    // AccessDeniedException because Windows refuses to move a folder containing an open/locked file.
    //
    // Use a sibling lock file instead (still scoped to this specific indexBasePath path).
    this.lockFile = base.resolveSibling(base.getFileName().toString() + ".index.lock");
  }

  public void acquire() throws IOException {
    acquireInner(false);
  }

  private void acquireInner(boolean isRetry) throws IOException {
    if (lock != null) {
      return;
    }
    Files.createDirectories(lockFile.getParent());
    this.channel =
        FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    try {
      this.lock = channel.tryLock();
    } catch (Exception e) {
      close();
      throw new IOException("Failed to acquire index root lock: " + lockFile, e);
    }
    if (this.lock == null) {
      close();
      if (!isRetry && tryRecoverStaleLock()) {
        acquireInner(true);
        return;
      }
      throw new IOException("Index base path is already locked by another process: " + lockFile);
    }

    writeOwnerMetadataBestEffort();
    log.info("Acquired index root lock: {}", lockFile);
  }

  private void writeOwnerMetadataBestEffort() {
    try {
      long pid = ProcessHandle.current().pid();
      String content = "pid=" + pid + "\nstarted_at=" + Instant.now() + "\n";
      channel.truncate(0);
      channel.position(0);
      channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
      channel.force(true);
    } catch (Exception e) {
      log.debug("Failed to write index lock metadata (best-effort): {}", e.getMessage());
    }
  }

  private boolean tryRecoverStaleLock() {
    try {
      if (!Files.exists(lockFile)) {
        return false;
      }
      String content = Files.readString(lockFile);
      Long pid = parsePidFromMetadata(content);
      if (pid == null) {
        return false;
      }

      var handleOpt = ProcessHandle.of(pid);
      if (handleOpt.isEmpty()) {
        log.warn("Recovering stale index lock: PID {} is dead, deleting {}", pid, lockFile);
        Files.deleteIfExists(lockFile);
        return true;
      }

      Long metadataStartMs = parseStartedAtFromMetadata(content);
      if (metadataStartMs != null) {
        var actualStart = handleOpt.get().info().startInstant();
        if (actualStart.isPresent()) {
          long actualMs = actualStart.get().toEpochMilli();
          if (Math.abs(actualMs - metadataStartMs) > 1000) {
            log.warn(
                "Recovering stale index lock: PID {} reused (metadata start={}, actual start={}), deleting {}",
                pid, metadataStartMs, actualMs, lockFile);
            Files.deleteIfExists(lockFile);
            return true;
          }
        }
      }

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

  @Override
  public void close() {
    try {
      if (lock != null) {
        lock.release();
      }
    } catch (Exception e) {
      log.debug("Failed to release file lock on {}: {}", lockFile, e.getMessage());
    } finally {
      lock = null;
    }
    try {
      if (channel != null) {
        channel.close();
      }
    } catch (Exception e) {
      log.debug("Failed to close file channel for {}: {}", lockFile, e.getMessage());
    } finally {
      channel = null;
    }
  }
}
