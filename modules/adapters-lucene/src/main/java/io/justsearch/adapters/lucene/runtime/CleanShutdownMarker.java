/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Durable "the index was closed cleanly" marker (tempdoc 628 Gap 1).
 *
 * <p>The bounded {@code STRUCTURAL} integrity check (commit + segment-info checksums) is cheap enough to
 * run on every open and catches the corruption a crash mid-commit usually produces. But silent
 * <em>body</em> bit-rot in a committed data file is only caught by the {@code FULL} check, which is
 * O(index size) and therefore too slow to run on every clean restart. This marker lets the worker run
 * the heavy {@code FULL} scan <em>only after a crash</em>: the writable runtime drops the marker on a
 * graceful close (after the final commit), and the next open consumes it — an <em>absent</em> marker
 * means the previous shutdown was unclean (a crash) or this is a first run, so a thorough scan is
 * warranted.
 *
 * <p>{@code write.lock} cannot serve this role: Lucene's {@code NativeFSLockFactory} keeps the lock
 * <em>file</em> after a clean close too, so its presence does not distinguish clean from crash. Like
 * {@link IndexRecoveryMarker} this is a <em>sibling</em> file of the index directory, so it never
 * disturbs Lucene's own file set.
 */
public final class CleanShutdownMarker {

  private static final String SUFFIX = ".clean-shutdown";

  private CleanShutdownMarker() {}

  /** Sibling marker path for the given index directory. */
  public static Path pathFor(Path indexDir) {
    Path fileName = indexDir.getFileName();
    String base = fileName != null ? fileName.toString() : "index";
    return indexDir.resolveSibling(base + SUFFIX);
  }

  /** Records a clean shutdown (best-effort; a failure here just means the next open scans FULL). */
  public static void write(Path indexDir) {
    try {
      Files.writeString(pathFor(indexDir), "clean\n", StandardCharsets.UTF_8);
    } catch (IOException e) {
      // best-effort: a missing marker only costs a FULL scan on the next open, never correctness.
    }
  }

  /**
   * Returns whether the previous shutdown was clean, and clears the marker so a crash in <em>this</em>
   * session is detectable on the next open. Absent marker (crash or first run) → {@code false}.
   */
  public static boolean consumeWasClean(Path indexDir) {
    Path p = pathFor(indexDir);
    boolean existed = Files.exists(p);
    try {
      Files.deleteIfExists(p);
    } catch (IOException e) {
      // best-effort
    }
    return existed;
  }
}
