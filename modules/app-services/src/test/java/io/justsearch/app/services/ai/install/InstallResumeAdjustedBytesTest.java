/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.InstallPlanPreview;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallIntent;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.InstallPlanner;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 840 R3 / R6 — bytes a resumed install actually still owes, and the filesystem it owes
 * them to.
 *
 * <p>One root cause with two faces. {@code InstallPlan.PlannedDownload} used to carry only the
 * file's full size, so every consumer that asked "what does this still cost?" got the wrong answer:
 * the per-stage disk precondition demanded room for a whole 6.34 GB file when 5.5 GB of it was
 * already in its {@code .partial} (refusing exactly the resume the design exists to preserve), and
 * the preview's per-component and per-tier rows charged for bytes already on the user's disk while
 * the headline {@code totalDownloadBytes} beside them did not — re-introducing at component
 * granularity the defect the headline was fixed for.
 *
 * <p>And the precondition measured {@code modelsDir} whatever the plan targeted, while a package
 * declaring {@code installRoot} ({@code cuda-runtime}, essentially the whole CORE payload) is
 * planned with an absolute path under the AI home. With {@code JUSTSEARCH_MODELS_DIR} on another
 * volume that measured a filesystem receiving almost none of the bytes, and the quiet direction is
 * the dangerous one.
 */
final class InstallResumeAdjustedBytesTest {

  private static final long GB = 1024L * 1024L * 1024L;

  @TempDir Path tmp;

  private static InstallPlan planOf(AiInstallService svc) throws Exception {
    Method hardware = AiInstallService.class.getDeclaredMethod("buildHardwareProfile");
    hardware.setAccessible(true);
    Method intent = AiInstallService.class.getDeclaredMethod("installIntent");
    intent.setAccessible(true);
    return InstallPlanner.plan(
        svc.getManifest(),
        (HardwareProfile) hardware.invoke(svc),
        (InstallIntent) intent.invoke(svc),
        svc.declinedPackages(),
        svc.modelsDir(),
        svc.aiHome());
  }

  private static InstallPlan.PlannedDownload absolute(Path target, long size, long staged) {
    return new InstallPlan.PlannedDownload(
        "cuda-runtime",
        "https://example.invalid/runtime.zip",
        target.toAbsolutePath().toString(),
        "sha",
        size,
        false,
        false,
        true,
        staged);
  }

  private static InstallStage.Slice sliceOf(InstallPlan.PlannedDownload... downloads) {
    long bytes = 0L;
    for (InstallPlan.PlannedDownload dl : downloads) {
      bytes += dl.sizeBytes();
    }
    return new InstallStage.Slice(
        InstallStage.CORE, List.of(downloads), bytes, Set.of("cuda-runtime"));
  }

  /**
   * The structural invariant: the rows have to sum to the total they sit under. With a real {@code
   * .partial} on disk the two were computed from different units — {@code remainingBytes()} for the
   * headline, raw file sizes for every row.
   */
  @Test
  @DisplayName("with bytes staged on disk, the per-component rows still sum to totalDownloadBytes")
  void componentRowsSumToTheHeadlineTotal() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    InstallPlan before = planOf(svc);
    assertTrue(before.downloads().size() > 1, "the fixture needs a real multi-file plan");
    assertEquals(0L, before.resumableBytes(), "nothing is staged yet on a fresh temp home");

    // Stage a real interrupted download: the biggest planned file, half-fetched.
    InstallPlan.PlannedDownload biggest =
        before.downloads().stream()
            .max(Comparator.comparingLong(InstallPlan.PlannedDownload::sizeBytes))
            .orElseThrow();
    long staged = Math.min(4096L, Math.max(1L, biggest.sizeBytes() - 1L));
    Path target = svc.modelsDir().resolve(biggest.targetPath());
    Files.createDirectories(target.getParent());
    Files.write(InstallPlanner.partialPathFor(target), new byte[(int) staged]);

    InstallPlanPreview preview = svc.previewInstallPlan();

    assertEquals(staged, preview.resumableBytes, "the staged bytes are seen");
    long componentSum = preview.components.stream().mapToLong(c -> c.downloadBytes).sum();
    long tierSum = preview.tiers.stream().mapToLong(t -> t.downloadBytes).sum();
    assertEquals(
        preview.totalDownloadBytes,
        componentSum,
        "a component row that charges for bytes already on disk contradicts the total above it");
    assertEquals(preview.totalDownloadBytes, tierSum, "and so does a tier row");
    assertEquals(
        before.totalBytes() - staged,
        preview.totalDownloadBytes,
        "and the total itself is the plan minus what is already staged");
  }

  /** The planner is where the per-file number is decided, once, for every consumer of it. */
  @Test
  @DisplayName("the planner carries staged bytes per file, not only as a plan-wide total")
  void plannerCarriesStagedBytesPerFile() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    InstallPlan.PlannedDownload biggest =
        planOf(svc).downloads().stream()
            .max(Comparator.comparingLong(InstallPlan.PlannedDownload::sizeBytes))
            .orElseThrow();
    long staged = Math.min(4096L, Math.max(1L, biggest.sizeBytes() - 1L));
    Path target = svc.modelsDir().resolve(biggest.targetPath());
    Files.createDirectories(target.getParent());
    Files.write(InstallPlanner.partialPathFor(target), new byte[(int) staged]);

    InstallPlan.PlannedDownload replanned =
        planOf(svc).downloads().stream()
            .filter(d -> d.targetPath().equals(biggest.targetPath()))
            .findFirst()
            .orElseThrow();

    assertEquals(staged, replanned.stagedBytes());
    assertEquals(biggest.sizeBytes() - staged, replanned.remainingBytes());
    assertEquals(
        biggest.sizeBytes(),
        replanned.sizeBytes(),
        "the file is still that big — only what it COSTS changed");
  }

  /** A stage's own cost question gets the resume-adjusted answer; its progress denominator does not. */
  @Test
  @DisplayName("a slice reports remaining bytes for cost and full sizes for progress")
  void sliceSeparatesCostFromProgress() {
    InstallStage.Slice slice =
        sliceOf(absolute(tmp.resolve("a.zip"), 6 * GB, 5 * GB), absolute(tmp.resolve("b.zip"), GB, 0L));

    assertEquals(7 * GB, slice.bytes(), "the denominator an item's full-size credit counts toward");
    assertEquals(2 * GB, slice.remainingBytes(), "what the network and the disk are still owed");
  }

  /**
   * R3's first face. Before, a stage refused itself over bytes that were already on the very disk
   * being measured.
   */
  @Test
  @DisplayName("a mostly-staged stage is not refused for space it does not need")
  void resumedStageIsNotRefusedForBytesAlreadyStaged() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    Files.createDirectories(svc.modelsDir());
    svc.setFreeSpaceProbeForTest(dir -> 2 * GB);

    InstallStage.Slice slice =
        sliceOf(absolute(svc.modelsDir().resolve("cuda/runtime.zip"), 6 * GB, 5 * GB + 512L * 1024L * 1024L));

    assertNull(
        svc.diskBlockedReason(slice),
        "only ~0.5 GB is still owed, which 2 GB free covers with room to spare");
  }

  /**
   * R6. The bytes land under the AI home, and that is the filesystem that has to have room for
   * them — measuring the models root instead PASSES a machine that is about to run out of space.
   */
  @Test
  @DisplayName("the precondition measures the filesystem the bytes land on, not modelsDir")
  void preconditionMeasuresTheDestinationFilesystem() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    Path modelsDir = svc.modelsDir();
    Files.createDirectories(modelsDir);
    // A roomy models root and a nearly-full AI home: the combination whose failure is silent.
    svc.setFreeSpaceProbeForTest(
        dir -> dir.toAbsolutePath().startsWith(modelsDir.toAbsolutePath()) ? 500 * GB : GB);

    // What the planner emits for an installRoot package: an ABSOLUTE path under the AI home.
    InstallStage.Slice slice =
        sliceOf(
            absolute(
                svc.aiHome().resolve("native-bin/llama-server/variants/cuda12/cudart64_12.dll"),
                6 * GB,
                0L));

    String reason = svc.diskBlockedReason(slice);

    assertNotNull(
        reason,
        "6 GB is landing on a filesystem with 1 GB free; the roomy models root receives none of it");
    assertTrue(reason.contains(InstallStage.CORE.label()), "names the stage that did not fit: " + reason);
  }

  /** Fail-open survives the regrouping: an unmeasurable destination filesystem still blocks nothing. */
  @Test
  @DisplayName("an unmeasurable destination filesystem still never blocks")
  void unmeasurableDestinationNeverBlocks() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    Files.createDirectories(svc.modelsDir());
    svc.setFreeSpaceProbeForTest(dir -> 0L);

    assertNull(
        svc.diskBlockedReason(sliceOf(absolute(svc.aiHome().resolve("runtime.zip"), 6 * GB, 0L))));
  }
}
