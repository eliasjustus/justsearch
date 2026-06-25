/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reaps stale ONNX Runtime native-library extraction directories from the JVM temp directory.
 *
 * <p>The ORT Java bindings extract their bundled native libraries — for the GPU package, the full
 * CUDA dependency set (cuBLAS / cuDNN / cuFFT / {@code onnxruntime_providers_cuda}), ~2&nbsp;GB — into
 * a fresh {@code $TMPDIR/onnxruntime-java<random>/} directory per JVM, and register cleanup only as a
 * JVM <em>shutdown hook</em>. That hook does not run when the process is force-killed (SIGKILL) — and
 * this codebase force-kills ORT-loading JVMs routinely (dev-stack stop, the eval harness, test
 * teardown). So every force-killed worker/eval/test orphans a ~2&nbsp;GB extraction, and they
 * accumulate without bound (observed: 977 dirs / ~682&nbsp;GB).
 *
 * <p>The reliable fix is to reap at <em>startup</em> rather than shutdown: the next ORT-using JVM
 * cleans the previous runs' leftovers. {@link #reapStaleOnce()} runs once per JVM, just before the
 * first {@link ai.onnxruntime.OrtEnvironment} is obtained.
 *
 * <p><b>Concurrency safety.</b> Multiple ORT JVMs may run at once. Two guards keep this from touching
 * a live sibling's directory: (1) directories modified within {@link #MIN_AGE_MS} are skipped (a sibling
 * mid-extraction); (2) a directory whose native libraries are still <em>loaded</em> is skipped — a
 * loaded DLL cannot be opened for write on Windows (sharing violation), which {@link #isInUse}
 * detects. A live directory is therefore left entirely untouched (never partially deleted). Fully
 * best-effort: any error is swallowed so ORT initialization is never affected.
 */
final class OrtNativeTempReaper {
  private static final Logger log = LoggerFactory.getLogger(OrtNativeTempReaper.class);

  /** Directories younger than this are assumed possibly-live (a sibling mid-extraction) and skipped. */
  private static final long MIN_AGE_MS = Duration.ofMinutes(5).toMillis();

  private static final String GLOB = "onnxruntime-java*";

  private static final AtomicBoolean DONE = new AtomicBoolean(false);

  private OrtNativeTempReaper() {}

  /** Reap stale ORT temp dirs at most once per JVM. Never throws. */
  static void reapStaleOnce() {
    if (!DONE.compareAndSet(false, true)) {
      return;
    }
    try {
      reapStale();
    } catch (Throwable t) {
      // Best-effort housekeeping must never break ORT init.
      log.debug("ORT temp reaper skipped: {}", t.toString());
    }
  }

  private static void reapStale() throws IOException {
    String tmp = System.getProperty("java.io.tmpdir");
    if (tmp == null || tmp.isBlank()) {
      return;
    }
    Path tmpDir = Path.of(tmp);
    if (!Files.isDirectory(tmpDir)) {
      return;
    }
    reap(tmpDir);
  }

  /** Sweep {@code tmpDir} for stale {@value #GLOB} dirs; returns the count removed. Package-private for tests. */
  static int reap(Path tmpDir) throws IOException {
    long now = System.currentTimeMillis();
    int reaped = 0;
    long bytesFreed = 0L;
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(tmpDir, GLOB)) {
      for (Path dir : dirs) {
        try {
          if (!Files.isDirectory(dir)) {
            continue;
          }
          if (now - Files.getLastModifiedTime(dir).toMillis() < MIN_AGE_MS) {
            continue; // fresh — possibly a sibling JVM still extracting
          }
          if (isInUse(dir)) {
            continue; // a live JVM still holds this dir's native libraries
          }
          long size = sizeOf(dir);
          deleteRecursive(dir);
          reaped++;
          bytesFreed += size;
        } catch (Exception perDir) {
          // Skip this directory; keep going.
          log.debug("ORT temp reaper could not remove {}: {}", dir, perDir.toString());
        }
      }
    }
    if (reaped > 0) {
      log.info(
          "ORT temp reaper removed {} stale onnxruntime-java dir(s), freed ~{} MB",
          reaped,
          bytesFreed / (1024 * 1024));
    }
    return reaped;
  }

  /**
   * True if any native library in {@code dir} is still loaded by a live process. A loaded DLL on
   * Windows is opened with deny-write sharing, so opening it for {@link StandardOpenOption#WRITE}
   * fails — that is the liveness signal. Opening for write performs no modification (no truncation).
   */
  private static boolean isInUse(Path dir) {
    try (DirectoryStream<Path> libs = Files.newDirectoryStream(dir, "*.dll")) {
      for (Path lib : libs) {
        try (FileChannel ch = FileChannel.open(lib, StandardOpenOption.WRITE)) {
          // Opened successfully → not locked. Keep probing the rest.
          ch.size();
        } catch (IOException locked) {
          return true; // at least one lib is held by a live process
        }
      }
    } catch (IOException cannotInspect) {
      return true; // be conservative if the dir cannot be read
    }
    return false;
  }

  private static long sizeOf(Path dir) {
    try (var walk = Files.walk(dir)) {
      return walk.filter(Files::isRegularFile)
          .mapToLong(
              p -> {
                try {
                  return Files.size(p);
                } catch (IOException e) {
                  return 0L;
                }
              })
          .sum();
    } catch (IOException e) {
      return 0L;
    }
  }

  private static void deleteRecursive(Path dir) throws IOException {
    try (var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignore) {
                  // A lock that appeared after isInUse() — leave it; next run retries.
                }
              });
    }
  }
}
