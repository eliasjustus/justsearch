/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.InstallPlan;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 840 R4 / R5 / R7 — three things the polled install status kept saying that were not true.
 *
 * <p>All three share a shape: a value written once at some moment and never revisited, on an object
 * that outlives the moment. A rate stamped the instant its sample arrived (where no stall can have
 * elapsed) survives the transfer that stopped; an error written by {@code fail} survives into the
 * next, successful run; a package counter last written by an in-flight progress event survives its
 * own completion a fraction short of 100 %.
 */
final class AiInstallServiceRunStatusTruthTest {

  private static final long ONE_SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);

  @TempDir Path tmp;

  private static AiInstallStatus statusOf(AiInstallService svc) throws Exception {
    Field f = AiInstallService.class.getDeclaredField("status");
    f.setAccessible(true);
    return (AiInstallStatus) f.get(svc);
  }

  private static void updateState(AiInstallService svc, String state, String phase, String message)
      throws Exception {
    Method m =
        AiInstallService.class.getDeclaredMethod(
            "updateState", String.class, String.class, String.class);
    m.setAccessible(true);
    m.invoke(svc, state, phase, message);
  }

  private static void fail(AiInstallService svc, String code, String message) throws Exception {
    Method m = AiInstallService.class.getDeclaredMethod("fail", String.class, String.class);
    m.setAccessible(true);
    m.invoke(svc, code, message);
  }

  private static AcquisitionScheduler.Listener projection(
      AiInstallService svc, InstallPlan plan, LongSupplier priorStageBytes) throws Exception {
    Method m =
        AiInstallService.class.getDeclaredMethod(
            "acquisitionProjection", InstallPlan.class, LongSupplier.class);
    m.setAccessible(true);
    return (AcquisitionScheduler.Listener) m.invoke(svc, plan, priorStageBytes);
  }

  private static InstallPlan.PlannedDownload dl(String packageId, String targetPath, long size) {
    return new InstallPlan.PlannedDownload(
        packageId, "https://example.invalid/" + targetPath, targetPath, "sha", size, true);
  }

  /** Seeds the one package row the run's bookkeeping would have created for this download. */
  private static void seedPackage(AiInstallStatus status, String packageId, long total) {
    var ps = new AiInstallStatus.PackageStatus();
    ps.packageId = packageId;
    ps.state = "pending";
    ps.bytesTotal = total;
    status.packages.add(ps);
  }

  /**
   * R4. {@code AcquisitionRate.estimate} decides "this transfer has stopped reporting" from how long
   * ago its newest sample arrived, and the only caller used to ask on the line after feeding it that
   * sample — where the answer is always "0 ns ago" and the stall arm is unreachable. Nothing then
   * re-published, so a stalled transfer left its last measured rate standing for as long as it
   * stayed stalled: the most confident lie available.
   */
  @Test
  @Timeout(30)
  @DisplayName("a transfer that stops reporting drops its rate to unknown by the next status read")
  void aStalledTransferStopsPublishingItsLastRate() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AtomicLong clock = new AtomicLong(ONE_SECOND_NANOS);
    svc.setNanoClockForTest(clock::get);
    AiInstallStatus status = statusOf(svc);
    updateState(svc, "running", "download", "Downloading...");
    status.totalBytes = 10_000L;
    seedPackage(status, "embedding", 10_000L);

    InstallPlan plan =
        new InstallPlan(
            DownloadProfile.CPU, List.of(dl("embedding", "e/model.onnx", 10_000L)), List.of(), 10_000L, List.of());
    AcquisitionScheduler.Listener listener = projection(svc, plan, () -> 0L);

    double[] rateWhileMoving = new double[1];
    double[] rateAfterStall = new double[1];
    long[] remainingAfterStall = new long[1];

    AcquisitionScheduler scheduler =
        new AcquisitionScheduler(
            List.of(new AcquisitionScheduler.Item("e/model.onnx", "embedding", 10_000L)),
            (item, startTier, progress) -> {
              // Four samples a second apart: past the minimum sample count and the minimum span.
              for (int i = 1; i <= 4; i++) {
                clock.addAndGet(ONE_SECOND_NANOS);
                progress.onProgress(i * 1_000L, 10_000L);
              }
              rateWhileMoving[0] = svc.getStatus().bytesPerSecond;
              // The transport wedges: no further progress events, and time passes.
              clock.addAndGet(AcquisitionRate.DEFAULT_STALL_NANOS + ONE_SECOND_NANOS);
              AiInstallStatus stalled = svc.getStatus();
              rateAfterStall[0] = stalled.bytesPerSecond;
              remainingAfterStall[0] = stalled.remainingSeconds;
              return new ResumableFetch.Outcome(false, true, "cancelled", null, 1, null);
            },
            item -> null,
            null,
            listener,
            () -> false,
            clock::get);

    svc.acquireStage(scheduler);

    assertTrue(
        rateWhileMoving[0] > 0d,
        "a transfer that IS moving must still publish its measured rate: " + rateWhileMoving[0]);
    assertEquals(
        -1d,
        rateAfterStall[0],
        "past the stall window the last measured rate describes a transfer that is no longer running");
    assertEquals(-1L, remainingAfterStall[0], "and a horizon can never be more knowable than its rate");
  }

  /**
   * R5. Reproduced live: a run finished {@code state=completed} still carrying {@code
   * errorCode=RUNTIME_MISSING} from an earlier attempt — the ordinary "press Install, fix the
   * problem, press Install again" sequence. {@code fail} was the only writer of these two fields and
   * nothing ever cleared them.
   */
  @Test
  @DisplayName("a new run does not republish the previous run's error")
  void aNewRunDropsThePreviousRunsError() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);

    fail(svc, "RUNTIME_MISSING", "Bundled AI runtime is missing and no runtime-supplying package is planned.");
    assertEquals("RUNTIME_MISSING", status.errorCode, "the failed run reports its own failure");

    // The user fixes the problem and presses Install again.
    updateState(svc, "running", "preflight", "Starting AI install...");
    assertEquals("", status.errorCode, "a run that is starting has not failed");
    assertEquals("", status.lastError);

    updateState(svc, "running", "download", "Downloading...");
    updateState(svc, "completed", "done", "AI install complete.");
    assertEquals("", status.errorCode, "and a completed install reports no error at all");
    assertEquals("", status.lastError);
  }

  /**
   * R7. Live: {@code cuda-runtime} read 1,989,333,036 of 1,989,906,411 on an {@code installed}
   * package — 99.97 %. In-flight progress stops one credit short of the total, because an item's
   * bytes are banked at placement and no progress event follows it. The STAGE counter has been
   * settled on the exact placed figure since Phase 3; the per-package one never was.
   */
  @Test
  @Timeout(30)
  @DisplayName("an installed package's byte counter settles on its exact size")
  void anInstalledPackageSettlesOnItsFullSize() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    updateState(svc, "running", "download", "Downloading...");
    long size = 1_989_906_411L;
    status.totalBytes = size;
    seedPackage(status, "cuda-runtime", size);

    InstallPlan plan =
        new InstallPlan(
            DownloadProfile.CPU, List.of(dl("cuda-runtime", "r/runtime.zip", size)), List.of(), size, List.of());
    AcquisitionScheduler.Listener listener = projection(svc, plan, () -> 0L);

    AcquisitionScheduler scheduler =
        new AcquisitionScheduler(
            List.of(new AcquisitionScheduler.Item("r/runtime.zip", "cuda-runtime", size)),
            (item, startTier, progress) -> {
              // The last progress event a real transport fires lands just short of the total.
              progress.onProgress(1_989_333_036L, size);
              return new ResumableFetch.Outcome(true, false, null, null, 1, null);
            },
            item -> null,
            null,
            listener,
            () -> false,
            System::nanoTime);

    AcquisitionScheduler.Summary summary = svc.acquireStage(scheduler);

    assertEquals(1, summary.installed());
    var ps = status.packages.get(0);
    assertEquals("installed", ps.state);
    assertEquals(
        size,
        ps.bytesDownloaded,
        "an installed package that reads 99.97 % of itself is a progress bar that never finishes");
  }
}
