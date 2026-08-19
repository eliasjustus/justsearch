/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether there is room on disk for what a stage is about to fetch (tempdoc 840 U2).
 *
 * <p>Nothing checked this before. An install committed to multiple GB and discovered the truth as a
 * late {@code INSTALL_IO_ERROR} after the user had already waited — the worst possible moment to
 * learn it, and a failure whose message named an IO error rather than the actual problem.
 *
 * <p>Checked PER STAGE rather than once for the whole plan, which is what staged acquisition makes
 * possible and is strictly kinder: a disk that fits the ~1.3 GB retrieval core but not the ~6 GB chat
 * model still gets working search, and is told precisely which stage did not fit instead of being
 * refused outright.
 *
 * <p><b>Fail-open by construction.</b> Every arm that cannot MEASURE returns "not blocked". A
 * filesystem that does not report usable space (some network and virtual mounts return 0) must not be
 * able to refuse an install that would have succeeded — an unmeasurable disk is not a full one. Only a
 * measurement that positively shows too little space blocks anything.
 */
final class FreeSpaceCheck {

  private static final Logger log = LoggerFactory.getLogger(FreeSpaceCheck.class);

  /**
   * Slack required beyond the bytes themselves. The transfer writes a {@code .partial} and renames it
   * onto the target — a rename within one filesystem needs no second copy — but the cuda-runtime
   * package is a zip that is EXTRACTED in place and deliberately kept afterwards, so its stage
   * genuinely needs room for the archive plus its contents. This is an allowance, not a computation:
   * the registry carries no uncompressed sizes, so an exact figure is not available and a
   * confidently precise one would be invented.
   */
  static final long MARGIN_BYTES = 1024L * 1024L * 1024L;

  /** Reads usable bytes for a directory. Injected so the decision is testable without a disk. */
  @FunctionalInterface
  interface Probe {
    /** Usable bytes, or a non-positive value when the filesystem will not say. */
    long usableBytes(Path dir);
  }

  private FreeSpaceCheck() {}

  /** The real probe: the filesystem's own answer, or 0 when it refuses to give one. */
  static Probe filesystemProbe() {
    return dir -> {
      try {
        return Files.getFileStore(dir).getUsableSpace();
      } catch (Exception e) {
        log.debug(
            "Usable-space probe failed for {} (treated as unmeasurable): {}", dir, e.toString());
        return 0L;
      }
    };
  }

  /**
   * Why this stage cannot be started, or {@code null} when it can.
   *
   * <p>Pure. {@code requiredBytes <= 0} (nothing to fetch) and {@code usableBytes <= 0}
   * (unmeasurable) both mean "not blocked" — see the fail-open note on the class.
   */
  static String blockedReason(String stageLabel, long requiredBytes, long usableBytes) {
    if (requiredBytes <= 0L || usableBytes <= 0L) {
      return null;
    }
    long needed = requiredBytes + MARGIN_BYTES;
    if (usableBytes >= needed) {
      return null;
    }
    return String.format(
        "Not enough disk space for %s: %s free, about %s needed (%s to download, plus %s working"
            + " room). Free some space and run the install again.",
        stageLabel,
        humanBytes(usableBytes),
        humanBytes(needed),
        humanBytes(requiredBytes),
        humanBytes(MARGIN_BYTES));
  }

  /**
   * Why this stage cannot be started, measured against the filesystems its files will actually land
   * on, or {@code null} when every one of them has room.
   *
   * <p>Probing one directory was wrong for a plan that writes to more than one volume, and this
   * install always can: a package declaring {@code installRoot} (today {@code cuda-runtime}, which
   * is nearly the whole CORE payload) gets an ABSOLUTE target under the AI home, while everything
   * else lands under the models root — and {@code JUSTSEARCH_MODELS_DIR} can put those two on
   * different drives. Measuring only the models root then answers about a filesystem receiving
   * almost none of the bytes, and the dangerous direction is the quiet one: a roomy models drive
   * with a nearly-full home drive PASSES, and the install hits exactly the late IO error this check
   * exists to pre-empt.
   *
   * <p>Each root is judged by {@link #blockedReason(String, long, long)}, so the fail-open rule
   * holds PER root: an unmeasurable filesystem still blocks nothing, even when a measurable sibling
   * in the same stage is fine.
   *
   * @param bytesByFilesystem bytes landing on each filesystem, keyed by a directory ON that
   *     filesystem which the probe can measure — see {@link #groupByFilesystem}
   */
  static String blockedReason(String stageLabel, Map<Path, Long> bytesByFilesystem, Probe probe) {
    if (bytesByFilesystem == null || probe == null) {
      return null;
    }
    for (Map.Entry<Path, Long> entry : bytesByFilesystem.entrySet()) {
      String reason =
          blockedReason(stageLabel, entry.getValue(), probe.usableBytes(entry.getKey()));
      if (reason != null) {
        return reason;
      }
    }
    return null;
  }

  /**
   * Folds planned destinations into one entry per FILESYSTEM, summing the bytes landing on each.
   *
   * <p>The grouping key is the {@link java.nio.file.FileStore} of the nearest EXISTING ancestor of
   * each destination — existing, because the install creates its own directories and the leaf
   * almost never exists yet; the file store rather than the path, because two destinations under
   * different directories of the SAME volume must share one budget. Checking them separately would
   * let each pass against free space the other is also about to consume, which is the same
   * false-pass this check exists to close.
   *
   * <p>The returned key is a representative directory the {@link Probe} can measure, so the probe
   * seam stays a plain path-to-bytes function and remains substitutable in tests.
   *
   * @param bytesByDestination the final path each file will occupy, and its byte cost
   */
  static Map<Path, Long> groupByFilesystem(Map<Path, Long> bytesByDestination) {
    Map<Path, Long> byFilesystem = new LinkedHashMap<>();
    if (bytesByDestination == null) {
      return byFilesystem;
    }
    Map<Object, Path> representatives = new LinkedHashMap<>();
    for (Map.Entry<Path, Long> entry : bytesByDestination.entrySet()) {
      Path measurable = nearestExistingAncestor(entry.getKey());
      Path representative =
          representatives.computeIfAbsent(filesystemKey(measurable), key -> measurable);
      byFilesystem.merge(representative, Math.max(0L, entry.getValue()), Long::sum);
    }
    return byFilesystem;
  }

  /** The closest ancestor directory of {@code destination} that exists, or its root as a fallback. */
  private static Path nearestExistingAncestor(Path destination) {
    Path absolute = destination.toAbsolutePath().normalize();
    for (Path candidate = absolute.getParent(); candidate != null; candidate = candidate.getParent()) {
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
    }
    Path root = absolute.getRoot();
    return root != null ? root : absolute;
  }

  /**
   * Identity of the filesystem holding {@code dir} — two directories sharing it share free space.
   * Falls back to the path root when the store cannot be resolved, which over-splits rather than
   * over-merges: an extra group is checked fail-open, a wrongly merged one would not be.
   */
  private static Object filesystemKey(Path dir) {
    try {
      return Files.getFileStore(dir);
    } catch (Exception e) {
      log.debug("File-store probe failed for {} (grouping by path root instead): {}", dir, e.toString());
      Path root = dir.toAbsolutePath().getRoot();
      return root != null ? root : dir;
    }
  }

  /** Sizes as the user reads them elsewhere in the product — GB/MB, one decimal. */
  static String humanBytes(long bytes) {
    if (bytes >= 1024L * 1024L * 1024L) {
      return String.format("%.1f GB", bytes / (1024d * 1024d * 1024d));
    }
    if (bytes >= 1024L * 1024L) {
      return String.format("%.0f MB", bytes / (1024d * 1024d));
    }
    return bytes + " B";
  }
}
