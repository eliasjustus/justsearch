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
public final class DownloadExecutor {
  private static final Logger log = LoggerFactory.getLogger(DownloadExecutor.class);

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

  /** Request cancellation of any active download. */
  public void cancel() {
    cancelBitsBestEffort();
    cancelCurlBestEffort();
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

    while (true) {
      if (cancelRequested.get()) {
        cancelBitsBestEffort();
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
          cancelBitsBestEffort();
          throw new IllegalStateException(
              "BITS failed (" + snap.jobState() + "): " + snap.errorDescription());
        }
        default -> Thread.sleep(750);
      }
    }
  }

  // -- Curl download ----------------------------------------------------------

  private boolean downloadWithCurl(String url, Path destPartial, ProgressCallback callback) {
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
          return false;
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
        return false;
      }
      return true;
    } catch (Exception e) {
      log.warn("Download failed: {}", e.getMessage());
      return false;
    }
  }

  // -- Cancellation -----------------------------------------------------------

  private void cancelBitsBestEffort() {
    String jobId = bitsJobId;
    if (jobId == null || jobId.isBlank()) return;
    try {
      String script =
          "$ErrorActionPreference='SilentlyContinue'; "
              + "Get-BitsTransfer -JobId '"
              + psEscape(jobId)
              + "' | Remove-BitsTransfer -Confirm:$false | Out-Null";
      runPowerShell(script, Duration.ofSeconds(10));
    } catch (Exception e) {
      log.debug("BITS cancel failed (best-effort)", e);
    }
    bitsJobId = null;
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

  private static long sizeBestEffort(Path p) {
    try {
      return Files.exists(p) ? Files.size(p) : 0;
    } catch (Exception e) {
      return 0;
    }
  }
}
