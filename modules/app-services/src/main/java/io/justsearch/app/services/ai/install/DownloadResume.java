/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resume bookkeeping for a single {@code .partial} download.
 *
 * <p>A {@code .partial} file on disk carries no identity of its own — the same path is reused for
 * whatever the manifest currently says belongs there. Resuming into it blindly is how a stale or
 * upstream-changed byte range becomes a corrupt model. So every partial is anchored by a sidecar
 * ({@code <target>.partial.resume}) recording the exact download it belongs to: source URL, expected
 * size, expected SHA-256, and (Windows) the id of a suspended BITS job holding the bytes.
 *
 * <p>{@link #decide} is a pure function over that record plus the partial's size on disk. Without a
 * matching sidecar a partial is untrusted and discarded — a missing record always degrades to a
 * fresh download, never to an optimistic resume.
 */
public final class DownloadResume {
  private static final JsonMapper JSON =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private DownloadResume() {}

  /** What to do with the bytes currently on disk (or held by a suspended BITS job). */
  public enum Action {
    /** Discard everything and download from byte zero. */
    FRESH,
    /** Ask the server for the remaining byte range (HTTP {@code Range}). */
    RESUME_RANGE,
    /** Resume the suspended BITS job that owns the bytes. */
    RESUME_BITS,
    /** The partial is already the expected size — skip the transfer, go straight to verification. */
    VERIFY_ONLY
  }

  /**
   * The sidecar contents — the identity of the download a {@code .partial} belongs to.
   *
   * @param url source URL the partial bytes came from
   * @param sizeBytes expected total size at the time the partial was written
   * @param sha256 expected SHA-256 at the time the partial was written
   * @param bitsJobId id of a suspended BITS job holding the bytes, or null
   */
  public record State(String url, long sizeBytes, String sha256, String bitsJobId) {}

  /**
   * The resume verdict.
   *
   * @param action what to do
   * @param resumeFromBytes byte offset the transfer continues at (0 unless {@link
   *     Action#RESUME_RANGE} / {@link Action#VERIFY_ONLY})
   * @param bitsJobId the BITS job to resume, or null
   * @param reason human-readable justification, logged and asserted on in tests
   */
  public record Decision(Action action, long resumeFromBytes, String bitsJobId, String reason) {}

  /** The sidecar path for a given {@code .partial} file. */
  public static Path sidecarFor(Path partial) {
    return partial.resolveSibling(partial.getFileName() + ".resume");
  }

  /** Reads the sidecar, or null when absent/unreadable/malformed (all treated as "no record"). */
  public static State read(Path partial) {
    Path sidecar = sidecarFor(partial);
    if (!Files.isRegularFile(sidecar)) return null;
    try {
      State state = JSON.readValue(Files.readString(sidecar), State.class);
      if (state == null || state.url() == null || state.url().isBlank()) return null;
      return state;
    } catch (Exception e) {
      return null;
    }
  }

  /** Writes the sidecar next to the partial. */
  public static void write(Path partial, State state) throws IOException {
    Files.writeString(sidecarFor(partial), JSON.writeValueAsString(state));
  }

  /** Deletes the sidecar only, leaving the partial in place. */
  public static void deleteSidecar(Path partial) {
    try {
      Files.deleteIfExists(sidecarFor(partial));
    } catch (IOException e) {
      // best-effort; a leftover sidecar without a partial decides FRESH anyway
    }
  }

  /** Deletes both the partial and its sidecar — the clean-slate reset. */
  public static void clear(Path partial) {
    try {
      Files.deleteIfExists(partial);
    } catch (IOException e) {
      // best-effort
    }
    deleteSidecar(partial);
  }

  /** Size of the partial on disk, 0 when absent or unreadable. */
  public static long partialSize(Path partial) {
    try {
      return Files.isRegularFile(partial) ? Files.size(partial) : 0L;
    } catch (IOException e) {
      return 0L;
    }
  }

  /**
   * Decides whether the bytes already on disk may be resumed into.
   *
   * <p>Every branch that cannot prove the partial belongs to exactly this download returns {@link
   * Action#FRESH}. The final SHA-256 check in {@link DownloadExecutor#verify} still gates acceptance
   * regardless of what this returns — this function only decides how many bytes are worth keeping,
   * never whether the result is trustworthy.
   */
  public static Decision decide(
      long partialSize, State recorded, String url, long expectedSize, String expectedSha256) {
    if (recorded == null) {
      return fresh("no resume record for this partial");
    }
    if (url == null || !url.equals(recorded.url())) {
      return fresh("source url changed since the partial was written");
    }
    if (recorded.sizeBytes() != expectedSize) {
      return fresh(
          "expected size changed since the partial was written ("
              + recorded.sizeBytes()
              + " -> "
              + expectedSize
              + ")");
    }
    if (expectedSha256 == null
        || recorded.sha256() == null
        || !expectedSha256.equalsIgnoreCase(recorded.sha256())) {
      return fresh("expected sha256 changed since the partial was written");
    }
    String jobId = recorded.bitsJobId();
    if (jobId != null && !jobId.isBlank()) {
      // BITS owns its own scratch bytes; the .partial on disk says nothing about the job's progress.
      return new Decision(Action.RESUME_BITS, 0L, jobId, "suspended BITS job recorded");
    }
    if (expectedSize <= 0) {
      // Without a total we can distinguish neither "complete" from "partial" nor an over-long
      // partial, and a Range request past the end answers 416. Not worth the risk for the bytes.
      return fresh("expected total size unknown; cannot reason about partial completeness");
    }
    if (partialSize <= 0) {
      return fresh("no partial bytes on disk");
    }
    if (partialSize > expectedSize) {
      return fresh(
          "partial larger than expected total ("
              + partialSize
              + " > "
              + expectedSize
              + "); impossible state");
    }
    if (partialSize == expectedSize) {
      return new Decision(
          Action.VERIFY_ONLY, partialSize, null, "partial is already the expected size");
    }
    return new Decision(
        Action.RESUME_RANGE, partialSize, null, "resuming from byte " + partialSize);
  }

  private static Decision fresh(String reason) {
    return new Decision(Action.FRESH, 0L, null, reason);
  }
}
