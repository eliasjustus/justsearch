/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Durable "this index was recovered to empty — rebuild it from source" marker (tempdoc 628 Stage B,
 * the G3 join).
 *
 * <p>When the adapter recovers a corrupt index by backing it up and opening a fresh empty index, the
 * empty index is structurally fine but has lost all content. Re-population must not be left to the
 * passive file-watcher (a source file that never changes again would never be re-indexed — the
 * silent-partial-index gap). So the adapter drops this marker as a sibling of the index directory; the
 * orchestration layer (Worker) reads it after open and deterministically kicks a full rebuild from the
 * source files still on disk, then clears it.
 *
 * <p>It is a sibling file (not inside the Lucene index dir) so it never interferes with Lucene's own
 * file set, and it is durable so a crash between recovery and the rebuild-kick simply re-triggers the
 * rebuild on the next start (idempotent). The adapter writes it but cannot itself trigger a rebuild —
 * {@code adapters-lucene} has no dependency on the blue/green migration machinery (worker-core), which
 * is exactly why the decision is relocated up to the orchestration layer.
 */
public final class IndexRecoveryMarker {

  private static final String SUFFIX = ".rebuild-pending";

  private IndexRecoveryMarker() {}

  /** Sibling marker path for the given index directory. */
  public static Path pathFor(Path indexDir) {
    Path fileName = indexDir.getFileName();
    String base = fileName != null ? fileName.toString() : "index";
    return indexDir.resolveSibling(base + SUFFIX);
  }

  /** Writes the marker recording why a rebuild is pending (best-effort, never throws checked). */
  public static void write(Path indexDir, String reason) {
    try {
      Files.writeString(pathFor(indexDir), (reason == null ? "unknown" : reason) + "\n",
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write index rebuild-pending marker", e);
    }
  }

  /** True if a rebuild-pending marker exists for the given index directory. */
  public static boolean exists(Path indexDir) {
    return Files.exists(pathFor(indexDir));
  }

  /** Reads the recorded reason, or {@code null} if no marker / unreadable. */
  public static String readReason(Path indexDir) {
    try {
      Path p = pathFor(indexDir);
      return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8).trim() : null;
    } catch (IOException e) {
      return null;
    }
  }

  /** Removes the marker once the rebuild has been kicked off (idempotent). */
  public static void clear(Path indexDir) {
    try {
      Files.deleteIfExists(pathFor(indexDir));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to clear index rebuild-pending marker", e);
    }
  }
}
