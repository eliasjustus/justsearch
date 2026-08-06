/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Downloads files using BITS (Windows preferred) with curl.exe fallback.
 *
 * <p>Extracted from v1 {@code AiInstallService}. Handles cancellation, progress reporting, and
 * platform-specific download mechanics. The download infrastructure is correct and battle-tested —
 * this class is a direct extraction, not a rewrite.
 */
public final class DownloadExecutor implements ResumableFetch.Transfer {
  private static final Logger log = LoggerFactory.getLogger(DownloadExecutor.class);

  /** curl's CURLE_RANGE_ERROR — the server would not honour the resume Range request. */
  private static final int CURL_RANGE_ERROR = 33;

  /** Poll result sentinels for {@link #runCurl}. */
  private static final int CURL_CANCELLED = -1;

  private static final int CURL_LAUNCH_FAILED = -2;

  /** Max consecutive polls a resumed BITS job may stay {@code Suspended} before we give up. */
  private static final int MAX_SUSPENDED_POLLS = 20;

  private static final JsonMapper JSON =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /** Callback for download progress updates. */
  @FunctionalInterface
  public interface ProgressCallback {
    void onProgress(long bytesDownloaded, long bytesTotal);
  }

  private final AtomicBoolean cancelRequested;
  private volatile Process curlProcess;
  private volatile String bitsJobId;
  private volatile String suspendedBitsJobId;

  public DownloadExecutor(AtomicBoolean cancelRequested) {
    this.cancelRequested = Objects.requireNonNull(cancelRequested);
  }

  /**
   * Downloads the URL to destPartial, reporting progress via callback. Returns true on success,
   * false on failure or cancellation. Tries BITS first on Windows, falls back to curl.exe.
   */
  public boolean download(String url, Path destPartial, ProgressCallback callback) {
    if (isWindows()) {
      try {
        boolean ok = downloadWithBits(url, destPartial, callback);
        if (ok) return true;
      } catch (Exception e) {
        log.info("BITS download failed; falling back to curl.exe: {}", e.getMessage());
      }
    }
    return downloadWithCurl(url, destPartial, callback);
  }

  /**
   * Resume-aware transfer. BITS cannot adopt a {@code .partial} it did not create, so a byte-offset
   * resume goes down the curl path where {@code --continue-at -} issues a real HTTP {@code Range}
   * request; BITS is used only for a suspended job it already owns, or a from-zero start.
   */
  @Override
  public boolean transfer(
      String url, Path destPartial, DownloadResume.Decision decision, ProgressCallback callback) {
    DownloadResume.Action action =
        decision == null ? DownloadResume.Action.FRESH : decision.action();
    if (action == DownloadResume.Action.RESUME_BITS) {
      try {
        if (resumeBitsJob(decision.bitsJobId(), callback)) return true;
      } catch (Exception e) {
        log.info(
            "BITS resume of job {} failed; restarting from zero: {}",
            decision.bitsJobId(),
            e.getMessage());
      }
      if (cancelRequested.get()) return false;
      // The job held the bytes, not our .partial — a dead job means there is nothing to keep.
      removeBitsJobBestEffort(decision.bitsJobId());
      deleteBestEffort(destPartial);
      return download(url, destPartial, callback);
    }
    if (action == DownloadResume.Action.RESUME_RANGE) {
      int code = runCurl(url, destPartial, callback);
      if (code == 0) return true;
      if (code == CURL_CANCELLED || cancelRequested.get()) return false;
      // Range refused (CURLE_RANGE_ERROR) or the remaining range is otherwise unfetchable — the
      // partial is worthless, so give up the bytes rather than the download.
      log.info(
          "Resume from byte {} failed (curl exit {}); restarting {} from zero",
          decision.resumeFromBytes(),
          code,
          destPartial.getFileName());
      deleteBestEffort(destPartial);
      return download(url, destPartial, callback);
    }
    return download(url, destPartial, callback);
  }

  /**
   * Request cancellation of any active download. A BITS transfer is SUSPENDED rather than removed —
   * {@code Remove-BitsTransfer} deletes the job's bytes, which is what made cancelling a 10 GB
   * install destroy every byte of progress. The suspended job id is exposed via {@link
   * #suspendedBitsJobId()} so the caller can record it for the next run.
   */
  public void cancel() {
    suspendBitsBestEffort();
    cancelCurlBestEffort();
  }

  /** Id of the BITS job suspended by the last {@link #cancel()}, or null. */
  @Override
  public String suspendedBitsJobId() {
    return suspendedBitsJobId;
  }

  /** Removes a recorded BITS job that is no longer usable, so it doesn't linger in the queue. */
  @Override
  public void abandonResumeHandle(String jobId) {
    removeBitsJobBestEffort(jobId);
  }

  /**
   * Verifies a downloaded file against its expected size and SHA-256. Throws {@link
   * IllegalStateException} on mismatch (fail-closed). The size check is skipped when {@code
   * expectedSize <= 0} (size unknown in the registry). Shared by the fresh-download path and any
   * future re-verification of already-present files.
   */
  public static void verify(Path file, long expectedSize, String expectedSha256) throws Exception {
    if (expectedSize > 0) {
      long size = Files.size(file);
      if (size != expectedSize) {
        throw new IllegalStateException("Size mismatch: expected " + expectedSize + ", got " + size);
      }
    }
    String got = sha256(file);
    if (!got.equalsIgnoreCase(expectedSha256)) {
      throw new IllegalStateException("SHA-256 mismatch");
    }
  }

  /** SHA-256 hash of a file (lowercase hex). */
  public static String sha256(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var in = new BufferedInputStream(Files.newInputStream(file))) {
      byte[] buf = new byte[1024 * 1024];
      int r;
      while ((r = in.read(buf)) >= 0) {
        if (r > 0) digest.update(buf, 0, r);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /** Best-effort atomic move (falls back to non-atomic on Windows if needed). */
  public static void moveAtomicBestEffort(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  // -- BITS download ----------------------------------------------------------

  private boolean downloadWithBits(String url, Path destPartial, ProgressCallback callback)
      throws Exception {
    String jobId = startBitsJob(url, destPartial);
    bitsJobId = jobId;
    suspendedBitsJobId = null;
    return awaitBitsJob(jobId, callback);
  }

  /**
   * Resumes a BITS job suspended by an earlier {@link #cancel()} — including one from a previous
   * process, since BITS jobs live in the system service, not in this JVM. Throws when the job is
   * gone or unusable; the caller then restarts from zero.
   */
  private boolean resumeBitsJob(String jobId, ProgressCallback callback) throws Exception {
    if (jobId == null || jobId.isBlank()) return false;
    if (!isWindows()) return false;
    String script =
        "$ErrorActionPreference='Stop'; "
            + "Get-BitsTransfer -JobId '"
            + psEscape(jobId)
            + "' | Resume-BitsTransfer -Asynchronous | Out-Null";
    runPowerShell(script, Duration.ofSeconds(30));
    bitsJobId = jobId;
    suspendedBitsJobId = null;
    log.info("Resumed suspended BITS job {}", jobId);
    return awaitBitsJob(jobId, callback);
  }

  /** Polls a running BITS job to completion, reporting progress. */
  private boolean awaitBitsJob(String jobId, ProgressCallback callback) throws Exception {
    int suspendedPolls = 0;
    while (true) {
      if (cancelRequested.get()) {
        suspendBitsBestEffort();
        return false;
      }
      BitsSnapshot snap = getBitsSnapshot(jobId);
      if (snap != null && callback != null) {
        callback.onProgress(snap.bytesTransferred(), snap.bytesTotal());
      }
      if (snap == null) {
        throw new IllegalStateException("BITS job disappeared");
      }
      switch (snap.jobState()) {
        case "Transferred" -> {
          completeBitsJob(jobId);
          bitsJobId = null;
          return true;
        }
        case "Error", "TransientError", "Cancelled" -> {
          removeBitsJobBestEffort(jobId);
          bitsJobId = null;
          throw new IllegalStateException(
              "BITS failed (" + snap.jobState() + "): " + snap.errorDescription());
        }
        case "Suspended" -> {
          // A job we just resumed should leave Suspended promptly; if it never does, the resume
          // did not take and looping forever would hang the install.
          if (++suspendedPolls > MAX_SUSPENDED_POLLS) {
            throw new IllegalStateException("BITS job stayed suspended after resume");
          }
          Thread.sleep(750);
        }
        default -> {
          suspendedPolls = 0;
          Thread.sleep(750);
        }
      }
    }
  }

  // -- Curl download ----------------------------------------------------------

  private boolean downloadWithCurl(String url, Path destPartial, ProgressCallback callback) {
    return runCurl(url, destPartial, callback) == 0;
  }

  /**
   * Runs curl.exe, returning its exit code — 0 on success, {@link #CURL_CANCELLED} when stopped by
   * cancellation, {@link #CURL_LAUNCH_FAILED} when it could not be run, otherwise curl's own code
   * ({@link #CURL_RANGE_ERROR} means the server refused the resume Range request).
   *
   * <p>{@code --continue-at -} is what makes resume work: curl reads the existing size of
   * {@code destPartial} and asks for {@code Range: bytes=<size>-}. On a missing/empty file it starts
   * at zero, so the same invocation serves both the fresh and the resumed case.
   */
  private int runCurl(String url, Path destPartial, ProgressCallback callback) {
    try {
      List<String> cmd =
          List.of(
              "curl.exe",
              "--fail",
              "--location",
              "--retry", "3",
              "--retry-delay", "2",
              "--continue-at", "-",
              "--output", destPartial.toAbsolutePath().toString(),
              url);
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.redirectErrorStream(true);
      curlProcess = pb.start();
      try {
        InputStream stream = curlProcess.getInputStream();
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    stream.transferTo(OutputStream.nullOutputStream());
                  } catch (Exception ignored) {
                    // best-effort drain
                  }
                });
      } catch (Exception ignored) {
        // best-effort
      }

      while (curlProcess.isAlive()) {
        if (cancelRequested.get()) {
          cancelCurlBestEffort();
          return CURL_CANCELLED;
        }
        long sz = sizeBestEffort(destPartial);
        if (callback != null) {
          callback.onProgress(sz, 0);
        }
        Thread.sleep(750);
      }
      int code = curlProcess.waitFor();
      curlProcess = null;
      if (code != 0) {
        log.warn("curl.exe failed with exit code {}", code);
      }
      return code;
    } catch (Exception e) {
      log.warn("Download failed: {}", e.getMessage());
      return CURL_LAUNCH_FAILED;
    }
  }

  // -- Cancellation -----------------------------------------------------------

  /**
   * Suspends the active BITS job, keeping its transferred bytes so the next run can resume it. If
   * the suspend itself fails there is nothing resumable, so the job is removed instead of being left
   * stuck in the queue.
   */
  private void suspendBitsBestEffort() {
    String jobId = bitsJobId;
    if (jobId == null || jobId.isBlank()) return;
    try {
      String script =
          "$ErrorActionPreference='Stop'; "
              + "Get-BitsTransfer -JobId '"
              + psEscape(jobId)
              + "' | Suspend-BitsTransfer -Confirm:$false | Out-Null";
      runPowerShell(script, Duration.ofSeconds(10));
      suspendedBitsJobId = jobId;
      log.info("Suspended BITS job {} (progress preserved for resume)", jobId);
    } catch (Exception e) {
      log.debug("BITS suspend failed; removing the job instead (best-effort)", e);
      removeBitsJobBestEffort(jobId);
      suspendedBitsJobId = null;
    }
    bitsJobId = null;
  }

  /** Destroys a BITS job and its bytes. Only for jobs that are unusable or already terminal. */
  private void removeBitsJobBestEffort(String jobId) {
    if (jobId == null || jobId.isBlank()) return;
    try {
      String script =
          "$ErrorActionPreference='SilentlyContinue'; "
              + "Get-BitsTransfer -JobId '"
              + psEscape(jobId)
              + "' | Remove-BitsTransfer -Confirm:$false | Out-Null";
      runPowerShell(script, Duration.ofSeconds(10));
    } catch (Exception e) {
      log.debug("BITS remove failed (best-effort)", e);
    }
    if (jobId.equals(bitsJobId)) bitsJobId = null;
    if (jobId.equals(suspendedBitsJobId)) suspendedBitsJobId = null;
  }

  private void cancelCurlBestEffort() {
    Process p = curlProcess;
    if (p == null) return;
    try {
      p.destroyForcibly();
    } catch (Exception e) {
      log.debug("curl process destroy failed (best-effort)", e);
    }
    curlProcess = null;
  }

  // -- BITS PowerShell helpers ------------------------------------------------

  private static String startBitsJob(String url, Path dest) throws Exception {
    String u = psEscape(url);
    String d = psEscape(dest.toAbsolutePath().toString());
    String script =
        "$ErrorActionPreference='Stop'; "
            + "$job = Start-BitsTransfer -Source '"
            + u
            + "' -Destination '"
            + d
            + "' -Asynchronous "
            + "-DisplayName 'JustSearch AI' -Description 'JustSearch AI model download'; "
            + "$job.JobId.Guid";
    String out = runPowerShell(script, Duration.ofSeconds(30));
    String id = out.trim();
    if (id.isBlank()) throw new IllegalStateException("BITS did not return a JobId");
    return id;
  }

  private static void completeBitsJob(String jobId) throws Exception {
    String id = psEscape(jobId);
    String script =
        "$ErrorActionPreference='Stop'; "
            + "Get-BitsTransfer -JobId '"
            + id
            + "' | Complete-BitsTransfer -Confirm:$false | Out-Null";
    runPowerShell(script, Duration.ofSeconds(30));
  }

  private static BitsSnapshot getBitsSnapshot(String jobId) throws Exception {
    String id = psEscape(jobId);
    String script =
        "$ErrorActionPreference='Stop'; "
            + "$job = Get-BitsTransfer -JobId '"
            + id
            + "'; "
            + "$obj = [PSCustomObject]@{ "
            + "JobState = $job.JobState.ToString(); "
            + "BytesTotal = "
            + bitsCountExpression("$job.BytesTotal")
            + "; "
            + "BytesTransferred = "
            + bitsCountExpression("$job.BytesTransferred")
            + "; "
            + "ErrorCount = [Int32]$job.ErrorCount; "
            + "ErrorDescription = $job.ErrorDescription }; "
            + "$obj | ConvertTo-Json -Compress";
    String out = runPowerShell(script, Duration.ofSeconds(10)).trim();
    if (out.isBlank()) return null;
    return JSON.readValue(out, BitsSnapshot.class);
  }

  static String bitsCountExpression(String expression) {
    return "(& { $v = "
        + expression
        + "; if ($null -eq $v) { 0 } else { $u = [UInt64]$v; "
        + "if ($u -eq [UInt64]::MaxValue -or $u -gt [UInt64]([Int64]::MaxValue)) "
        + "{ 0 } else { [Int64]$u } } })";
  }

  private record BitsSnapshot(
      String JobState,
      long BytesTotal,
      long BytesTransferred,
      int ErrorCount,
      String ErrorDescription) {
    String jobState() {
      return JobState == null ? "" : JobState;
    }

    long bytesTotal() {
      return BytesTotal;
    }

    long bytesTransferred() {
      return BytesTransferred;
    }

    String errorDescription() {
      return ErrorDescription == null ? "" : ErrorDescription;
    }
  }

  private static String runPowerShell(String script, Duration timeout) throws Exception {
    Objects.requireNonNull(script, "script");
    ProcessBuilder pb =
        new ProcessBuilder(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    boolean ok = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!ok) {
      p.destroyForcibly();
      throw new IllegalStateException("PowerShell timed out");
    }
    byte[] bytes = p.getInputStream().readAllBytes();
    String out = new String(bytes, StandardCharsets.UTF_8);
    if (p.exitValue() != 0) {
      throw new IllegalStateException(
          "PowerShell failed (exit " + p.exitValue() + "): " + out.trim());
    }
    return out;
  }

  // -- Utilities --------------------------------------------------------------

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
  }

  private static String psEscape(String raw) {
    if (raw == null) return "";
    return raw.replace("'", "''");
  }

  private static void deleteBestEffort(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException e) {
      log.debug("Failed to delete {} (best-effort)", p, e);
    }
  }

  private static long sizeBestEffort(Path p) {
    try {
      return Files.exists(p) ? Files.size(p) : 0;
    } catch (Exception e) {
      return 0;
    }
  }
}
