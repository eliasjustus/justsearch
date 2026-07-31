/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetch-one-file-with-resume: decide, transfer, verify, and on an integrity failure discard and
 * restart exactly once from zero.
 *
 * <p>Split out of {@link AiInstallService}'s download loop so the resume lifecycle is exercisable
 * without a 10 GB transfer: the {@link Transfer} seam stands in for BITS/curl.
 *
 * <p><b>Integrity is not negotiable.</b> A resumed file goes through the same {@link
 * DownloadExecutor#verify} as a fresh one. When it fails, the partial is destroyed and the download
 * restarts from zero once; if the fresh attempt also fails verification the package fails. There is
 * no path on which unverified bytes are moved to the target.
 */
public final class ResumableFetch {
  private static final Logger log = LoggerFactory.getLogger(ResumableFetch.class);

  private ResumableFetch() {}

  /** The transport, parameterised by the resume decision. Implemented by {@link DownloadExecutor}. */
  public interface Transfer {
    /**
     * Transfers {@code url} into {@code destPartial}, honouring {@code decision} (fresh, HTTP Range
     * resume, or BITS job resume). Returns true only when the whole file is on disk.
     */
    boolean transfer(
        String url,
        Path destPartial,
        DownloadResume.Decision decision,
        DownloadExecutor.ProgressCallback callback);

    /**
     * Id of a BITS job this transport suspended (rather than destroyed) on the last cancellation, so
     * the next run can resume it. Null when there is nothing to resume.
     */
    default String suspendedBitsJobId() {
      return null;
    }

    /** Discards a recorded resume handle that is no longer usable, so it doesn't leak. */
    default void abandonResumeHandle(String bitsJobId) {}
  }

  /**
   * What to fetch.
   *
   * @param partialFile the {@code .partial} staging path
   * @param url source URL
   * @param expectedSize expected total size ({@code <= 0} when unknown)
   * @param expectedSha256 expected SHA-256, always enforced
   * @param label user-facing name of the file, used in failure messages
   */
  public record Request(
      Path partialFile, String url, long expectedSize, String expectedSha256, String label) {

    /** Label defaults to the URL when the caller has no friendlier name. */
    public Request(Path partialFile, String url, long expectedSize, String expectedSha256) {
      this(partialFile, url, expectedSize, expectedSha256, url);
    }
  }

  /**
   * Result of the fetch.
   *
   * @param ok true when {@code partialFile} exists and passed verification
   * @param cancelled true when the transfer stopped because cancellation was requested
   * @param error failure message, null when {@code ok}
   * @param firstAction the resume verdict this fetch started from — how progress was reused
   * @param transferAttempts how many transfers ran (2 means a resumed attempt failed integrity and
   *     was restarted from zero)
   */
  public record Outcome(
      boolean ok,
      boolean cancelled,
      String error,
      DownloadResume.Action firstAction,
      int transferAttempts) {}

  /**
   * Runs the decide → transfer → verify cycle.
   *
   * @param onFreshStart invoked before a from-zero transfer (BITS scratch cleanup); must NOT run
   *     when resuming, because a suspended BITS job still owns its {@code BIT*.tmp} scratch
   * @param onVerifyStart invoked before each verification pass (package-state reporting)
   */
  public static Outcome fetch(
      Request request,
      Transfer transfer,
      DownloadExecutor.ProgressCallback progress,
      BooleanSupplier cancelRequested,
      Runnable onFreshStart,
      Runnable onVerifyStart) {
    Path partial = request.partialFile();
    String url = request.url();
    long expectedSize = request.expectedSize();
    String expectedSha = request.expectedSha256();

    DownloadResume.State recorded = DownloadResume.read(partial);
    DownloadResume.Decision decision =
        DownloadResume.decide(
            DownloadResume.partialSize(partial), recorded, url, expectedSize, expectedSha);
    DownloadResume.Action firstAction = decision.action();
    log.info(
        "Resume decision for {}: {} ({})",
        partial.getFileName(),
        decision.action(),
        decision.reason());

    if (decision.action() == DownloadResume.Action.FRESH
        && recorded != null
        && recorded.bitsJobId() != null
        && !recorded.bitsJobId().isBlank()) {
      // The recorded job no longer matches what we want (changed url/size/sha). Left alone it would
      // sit suspended in the BITS queue for its 90-day default lifetime.
      transfer.abandonResumeHandle(recorded.bitsJobId());
    }

    int attempts = 0;
    for (int pass = 0; pass < 2; pass++) {
      if (decision.action() == DownloadResume.Action.FRESH) {
        DownloadResume.clear(partial);
        if (onFreshStart != null) onFreshStart.run();
      }
      try {
        DownloadResume.write(partial, new DownloadResume.State(url, expectedSize, expectedSha, null));
      } catch (IOException e) {
        return new Outcome(
            false, false, "Failed to record resume state: " + e.getMessage(), firstAction, attempts);
      }

      if (decision.action() != DownloadResume.Action.VERIFY_ONLY) {
        attempts++;
        boolean transferred = transfer.transfer(url, partial, decision, progress);
        if (!transferred) {
          // Keep the partial: the sidecar identity check gates whether it is reusable next time.
          persistSuspendedJob(transfer, partial, url, expectedSize, expectedSha);
          if (cancelRequested != null && cancelRequested.getAsBoolean()) {
            return new Outcome(false, true, "Cancelled.", firstAction, attempts);
          }
          return new Outcome(
              false, false, "Download failed for " + request.label(), firstAction, attempts);
        }
      }

      if (onVerifyStart != null) onVerifyStart.run();
      try {
        DownloadExecutor.verify(partial, expectedSize, expectedSha);
        DownloadResume.deleteSidecar(partial);
        return new Outcome(true, false, null, firstAction, attempts);
      } catch (Exception e) {
        // The bytes are not what the manifest promises: upstream changed under the partial, the
        // partial was stale, or the transfer corrupted it. Destroy, never accept.
        DownloadResume.clear(partial);
        if (decision.action() == DownloadResume.Action.FRESH) {
          return new Outcome(
              false, false, "Verification failed: " + e.getMessage(), firstAction, attempts);
        }
        log.warn(
            "Resumed download of {} failed verification ({}); discarding and restarting from zero",
            partial.getFileName(),
            e.getMessage());
        decision =
            new DownloadResume.Decision(
                DownloadResume.Action.FRESH,
                0L,
                null,
                "resumed bytes failed integrity verification; restarting from zero");
      }
    }
    // Unreachable: pass 0 either returns or downgrades to FRESH, and a FRESH pass always returns.
    return new Outcome(false, false, "Verification failed after restart", firstAction, attempts);
  }

  private static void persistSuspendedJob(
      Transfer transfer, Path partial, String url, long expectedSize, String expectedSha) {
    String jobId = transfer.suspendedBitsJobId();
    if (jobId == null || jobId.isBlank()) return;
    try {
      DownloadResume.write(
          partial, new DownloadResume.State(url, expectedSize, expectedSha, jobId));
    } catch (IOException e) {
      log.debug("Failed to record suspended BITS job {} (progress will be lost)", jobId, e);
    }
  }
}
