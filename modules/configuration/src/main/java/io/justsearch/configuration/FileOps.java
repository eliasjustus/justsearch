/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.slf4j.Logger;

/**
 * File-system utilities with built-in fault handling.
 *
 * <p>These methods handle per-item failures internally (counting + summary logging) so callers
 * don't need catch blocks around file operations in loops.
 */
public final class FileOps {

  private FileOps() {} // utility class

  /** Result of a best-effort recursive delete: how many entries were deleted vs. failed. */
  public record DeleteResult(int deleted, int failed) {}

  /**
   * Recursively deletes a directory, counting per-file failures rather than throwing on them.
   *
   * <p>Per-file {@link IOException}s (e.g. Windows file locks) are counted silently. A single
   * summary line is logged at DEBUG if any deletions failed. The caller can inspect the returned
   * {@link DeleteResult} to decide whether the failures matter.
   *
   * <p>An {@link IOException} from {@link Files#walk} itself (e.g. directory unreadable) is still
   * thrown — only per-entry delete failures are absorbed.
   *
   * @param dir the directory to delete (if null or nonexistent, returns zero counts)
   * @param log the logger for the summary line
   * @return counts of deleted and failed entries
   * @throws IOException if the directory walk itself fails
   */
  public static DeleteResult deleteRecursivelyBestEffort(Path dir, Logger log) throws IOException {
    if (dir == null || !Files.exists(dir)) {
      return new DeleteResult(0, 0);
    }
    int[] counts = {0, 0}; // [deleted, failed]
    try (var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                  counts[0]++;
                } catch (IOException e) {
                  counts[1]++;
                }
              });
    }
    if (counts[1] > 0) {
      log.debug(
          "Deleted {}/{} entries in {}; {} failed (likely locked)",
          counts[0],
          counts[0] + counts[1],
          dir.getFileName(),
          counts[1]);
    }
    return new DeleteResult(counts[0], counts[1]);
  }
}
