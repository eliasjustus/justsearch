/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import java.nio.file.Files;
import java.nio.file.Path;
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
