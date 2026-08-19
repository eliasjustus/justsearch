/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
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
 *
 * <p><b>Transport retry lives here, around that cycle.</b> A transport-transient failure (see {@link
 * TransportFailure}) re-enters the whole decide-transfer-verify cycle after a spaced wait, at an
 * escalated transport tier ({@link TransportRetryPolicy}). The two loops must not multiply: an
 * integrity failure is permanent, so it ends the fetch after its single restart-from-zero rather
 * than being re-attempted by the retry policy.
 */
public final class ResumableFetch {
  private static final Logger log = LoggerFactory.getLogger(ResumableFetch.class);

  private ResumableFetch() {}

  /** The transport, parameterised by the resume decision. Implemented by {@link DownloadExecutor}. */
  public interface Transfer {
    /**
     * Transfers {@code url} into {@code destPartial}, honouring {@code decision} (fresh, HTTP Range
     * resume, or BITS job resume). Returns true only when the whole file is on disk.
     *
     * @param transportTier which transport to use, see {@link TransportRetryPolicy}
     */
    boolean transfer(
        String url,
        Path destPartial,
        DownloadResume.Decision decision,
        DownloadExecutor.ProgressCallback callback,
        int transportTier);

    /**
     * Why the last {@link #transfer} returned false, or null when the transport cannot say. An
     * unclassified failure is treated as permanent — the retry policy only ever spends time on a
     * failure a transport explicitly calls transient.
     */
    default TransportFailure lastFailure() {
      return null;
    }

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
   * @param failure the typed transport classification, non-null <em>iff</em> the fetch ended at
   *     TRANSPORT; null for success, cancellation, verification failure, and bookkeeping failure.
   *     Consumers that act only on transport failures ({@link InstallAttemptMemory}'s escalation and
   *     terminal verdict) test this field rather than the wording of {@link #error()}.
   */
  public record Outcome(
      boolean ok,
      boolean cancelled,
      String error,
      DownloadResume.Action firstAction,
      int transferAttempts,
      TransportFailure failure) {}

  /**
   * Side-channel callbacks the fetch fires as it moves through its phases.
   *
   * @param onFreshStart invoked before a from-zero transfer (BITS scratch cleanup); must NOT run
   *     when resuming, because a suspended BITS job still owns its {@code BIT*.tmp} scratch
   * @param onVerifyStart invoked before each verification pass (package-state reporting)
   * @param onAttempt invoked with the 1-based transport attempt number before each attempt, so the
   *     user-visible phase message can name which of the policy's attempts is running
   */
  public record Hooks(Runnable onFreshStart, Runnable onVerifyStart, IntConsumer onAttempt) {

    /** No-op hooks, for callers that only want the bytes. */
    public static Hooks none() {
      return new Hooks(null, null, null);
    }

    void freshStart() {
      if (onFreshStart != null) onFreshStart.run();
    }

    void verifyStart() {
      if (onVerifyStart != null) onVerifyStart.run();
    }

    void attempt(int attemptNumber) {
      if (onAttempt != null) onAttempt.accept(attemptNumber);
    }
  }

  /** One attempt's result, plus whether the retry policy is allowed to spend time on another. */
  private record AttemptResult(
      Outcome outcome, int transfers, boolean retryable, TransportFailure failure) {}

  /**
   * Runs the decide → transfer → verify cycle, re-attempting a transport-transient failure up to
   * {@code policy}'s budget with spaced waits and an escalating transport tier.
   */
  public static Outcome fetch(
      Request request,
      Transfer transfer,
      DownloadExecutor.ProgressCallback progress,
      BooleanSupplier cancelRequested,
      Hooks hooks,
      TransportRetryPolicy policy) {
    Path partial = request.partialFile();
    String url = request.url();
    long expectedSize = request.expectedSize();
    String expectedSha = request.expectedSha256();
    Hooks callbacks = hooks == null ? Hooks.none() : hooks;
    TransportRetryPolicy retry = policy == null ? TransportRetryPolicy.defaultPolicy() : policy;

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

    int transfers = 0;
    TransportFailure lastFailure = null;
    for (int attempt = 0; attempt < retry.maxAttempts(); attempt++) {
      if (attempt > 0) {
        if (isCancelled(cancelRequested)) {
          return new Outcome(false, true, "Cancelled.", firstAction, transfers, null);
        }
        long waitMs = retry.delayMsBeforeAttempt(attempt);
        log.info(
            "Retrying {} in {} ms (attempt {} of {}, transport tier {})",
            partial.getFileName(),
            waitMs,
            attempt + 1,
            retry.maxAttempts(),
            retry.tierForAttempt(attempt));
        // Sliced, cancellation-polling wait: cancel() only raises a flag, so a wait that ignored it
        // would keep the user staring at a cancelled install for up to the whole 27 s backoff.
        if (!retry.sleep(waitMs, cancelRequested)) {
          return new Outcome(false, true, "Cancelled.", firstAction, transfers, null);
        }
        // Re-decide against disk: the failed attempt may have staged bytes worth resuming, and the
        // sidecar identity check is still what gates whether they are reusable.
        decision =
            DownloadResume.decide(
                DownloadResume.partialSize(partial),
                DownloadResume.read(partial),
                url,
                expectedSize,
                expectedSha);
      }
      callbacks.attempt(attempt + 1);

      AttemptResult result =
          runAttempt(
              request,
              transfer,
              decision,
              firstAction,
              retry.tierForAttempt(attempt),
              progress,
              cancelRequested,
              callbacks,
              transfers);
      transfers = result.transfers();
      lastFailure = result.failure();
      if (result.outcome().ok() || result.outcome().cancelled() || !result.retryable()) {
        return result.outcome();
      }
    }

    return new Outcome(
        false,
        false,
        "Download failed for "
            + request.label()
            + " after "
            + retry.maxAttempts()
            + " attempts"
            + (lastFailure == null ? "" : " (" + lastFailure.summary() + ")"),
        firstAction,
        transfers,
        lastFailure);
  }

  /**
   * One transport attempt: the historical two-pass loop (transfer, verify, and on an integrity
   * failure of RESUMED bytes discard and restart from zero exactly once).
   */
  private static AttemptResult runAttempt(
      Request request,
      Transfer transfer,
      DownloadResume.Decision startDecision,
      DownloadResume.Action firstAction,
      int transportTier,
      DownloadExecutor.ProgressCallback progress,
      BooleanSupplier cancelRequested,
      Hooks hooks,
      int transfersSoFar) {
    Path partial = request.partialFile();
    String url = request.url();
    long expectedSize = request.expectedSize();
    String expectedSha = request.expectedSha256();
    DownloadResume.Decision decision = startDecision;

    int transfers = transfersSoFar;
    for (int pass = 0; pass < 2; pass++) {
      if (decision.action() == DownloadResume.Action.FRESH) {
        DownloadResume.clear(partial);
        hooks.freshStart();
      }
      try {
        DownloadResume.write(partial, new DownloadResume.State(url, expectedSize, expectedSha, null));
      } catch (IOException e) {
        return new AttemptResult(
            new Outcome(
                false,
                false,
                "Failed to record resume state: " + e.getMessage(),
                firstAction,
                transfers,
                null),
            transfers,
            false,
            null);
      }

      if (decision.action() != DownloadResume.Action.VERIFY_ONLY) {
        transfers++;
        boolean transferred = transfer.transfer(url, partial, decision, progress, transportTier);
        if (!transferred) {
          // Keep the partial: the sidecar identity check gates whether it is reusable next time.
          persistSuspendedJob(transfer, partial, url, expectedSize, expectedSha);
          if (isCancelled(cancelRequested)) {
            return new AttemptResult(
                new Outcome(false, true, "Cancelled.", firstAction, transfers, null),
                transfers,
                false,
                null);
          }
          TransportFailure classified = transfer.lastFailure();
          String reason =
              classified == null
                  ? "Download failed for " + request.label()
                  : "Download failed for " + request.label() + " (" + classified.summary() + ")";
          // Ended at TRANSPORT, so the outcome carries a typed failure either way: the escalation
          // memory keys on "was this transport" and must not silently disengage because the
          // transport could not name a code (an unclassified failure is still non-retryable).
          TransportFailure failure =
              classified == null ? TransportFailure.unclassified() : classified;
          return new AttemptResult(
              new Outcome(false, false, reason, firstAction, transfers, failure),
              transfers,
              failure.retryable(),
              failure);
        }
      }

      hooks.verifyStart();
      try {
        DownloadExecutor.verify(partial, expectedSize, expectedSha);
        DownloadResume.deleteSidecar(partial);
        return new AttemptResult(
            new Outcome(true, false, null, firstAction, transfers, null), transfers, false, null);
      } catch (Exception e) {
        // The bytes are not what the manifest promises: upstream changed under the partial, the
        // partial was stale, or the transfer corrupted it. Destroy, never accept. This is NOT a
        // transport-transient failure, so the retry policy must not re-attempt it.
        DownloadResume.clear(partial);
        if (decision.action() == DownloadResume.Action.FRESH) {
          return new AttemptResult(
              new Outcome(
                  false,
                  false,
                  "Verification failed: " + e.getMessage(),
                  firstAction,
                  transfers,
                  null),
              transfers,
              false,
              null);
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
    return new AttemptResult(
        new Outcome(false, false, "Verification failed after restart", firstAction, transfers, null),
        transfers,
        false,
        null);
  }

  private static boolean isCancelled(BooleanSupplier cancelRequested) {
    return cancelRequested != null && cancelRequested.getAsBoolean();
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
