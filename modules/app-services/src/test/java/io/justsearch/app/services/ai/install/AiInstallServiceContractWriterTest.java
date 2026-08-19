package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.SkipCause;
import io.justsearch.configuration.model.SupportingFile;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 805 G.3 (derisk U3, refinement 3) — the contract writer's entry-kind decision.
 *
 * <p>{@code ModelPackage.selectVariant} returns null for a package with {@code variants: []}, which
 * made the writer record {@code cuda-runtime} as {@code skipped("No variant")} with NO installed
 * files — in both 0.1.0 and 0.2.0. The contract therefore carried no per-file authority for exactly
 * the package class whose new supporting file round 11 lost. A variantless package whose supporting
 * files the plan installed is now recorded installed-WITH-FILES.
 */
final class AiInstallServiceContractWriterTest {

  @TempDir Path tmp;

  private static final HardwareProfile HARDWARE = new HardwareProfile(true, true, 12L << 30);

  private static ModelPackage variantlessPackage(String id, String... supportingFileNames) {
    List<SupportingFile> supporting =
        java.util.Arrays.stream(supportingFileNames)
            .map(n -> new SupportingFile(n, "sha-" + n, 10L, "https://example/" + n))
            .toList();
    return new ModelPackage(
        id, id, "desc", "runtime", List.of(), supporting, 0L, null, "native-bin");
  }

  private static InstallPlan planWithNothingSkipped() {
    return new InstallPlan(DownloadProfile.values()[0], List.of(), List.of(), 0L, List.of());
  }

  @Test
  @DisplayName("a variantless package with supporting files is recorded installed-with-files")
  void variantlessPackageWithSupportingFiles_isRecordedWithFiles() {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    ModelRegistry registry =
        new ModelRegistry(
            2,
            "test",
            List.of(variantlessPackage("cuda-runtime", "cuda-runtime-12.4.zip", "ort-native-cuda12-v1.24.3.zip")));

    InstallContract contract = svc.buildContract(planWithNothingSkipped(), registry, HARDWARE);

    InstallContract.InstalledModel entry = contract.getModel("cuda-runtime");
    assertFalse(entry.skipped(), "a package whose files were installed is not a skip");
    assertEquals(
        List.of("cuda-runtime-12.4.zip", "ort-native-cuda12-v1.24.3.zip"),
        entry.installedFiles(),
        "the contract regains per-file authority for the package class that caused round 11");
    assertNull(entry.variantFilename(), "there is genuinely no model variant — that stays honest");
  }

  @Test
  @DisplayName("a package with neither a variant nor supporting files is still skipped(No variant)")
  void variantlessPackageWithNoFiles_staysSkipped() {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    ModelRegistry registry = new ModelRegistry(2, "test", List.of(variantlessPackage("empty")));

    InstallContract contract = svc.buildContract(planWithNothingSkipped(), registry, HARDWARE);

    InstallContract.InstalledModel entry = contract.getModel("empty");
    assertTrue(entry.skipped(), "nothing was installed, so nothing is claimed");
    assertEquals("No variant", entry.skipReason());
  }

  @Test
  @DisplayName("a package the PLAN skipped keeps its skipped entry and reason")
  void planSkippedPackage_keepsSkippedEntry() {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    ModelRegistry registry =
        new ModelRegistry(2, "test", List.of(variantlessPackage("cuda-runtime", "cuda-runtime-12.4.zip")));
    InstallPlan plan =
        new InstallPlan(
            DownloadProfile.values()[0],
            List.of(),
            List.of(new InstallPlan.SkippedPackage("cuda-runtime", SkipCause.HARDWARE, "no CUDA GPU")),
            0L,
            List.of());

    InstallContract contract = svc.buildContract(plan, registry, HARDWARE);

    InstallContract.InstalledModel entry = contract.getModel("cuda-runtime");
    assertTrue(entry.skipped(), "hardware/policy skips are unchanged by the entry-kind fix");
    assertEquals("no CUDA GPU", entry.skipReason());
    assertEquals(SkipCause.HARDWARE, entry.skipCause(), "the planner's typed cause is recorded");
    assertTrue(entry.installedFiles().isEmpty(), "a skipped package claims no files");
  }

  /**
   * Tempdoc 840 Phase 2 — the contract records WHY, typed, and the planner is the only authority for
   * it. The writer must carry the cause through rather than re-deriving it (from the prose, or by
   * re-evaluating hardware/intent/declined), which would drift the first time a message is reworded.
   */
  @Test
  @DisplayName("a user-declined skip is recorded with its typed cause, not just its prose")
  void userDeclinedSkip_recordsTheTypedCause() {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    ModelRegistry registry =
        new ModelRegistry(2, "test", List.of(variantlessPackage("reranker", "reranker.zip")));
    InstallPlan plan =
        new InstallPlan(
            DownloadProfile.values()[0],
            List.of(),
            List.of(
                new InstallPlan.SkippedPackage(
                    "reranker", SkipCause.USER_DECLINED, "Search reranker was declined")),
            0L,
            List.of());

    InstallContract contract = svc.buildContract(plan, registry, HARDWARE);

    InstallContract.InstalledModel entry = contract.getModel("reranker");
    assertTrue(entry.skipped());
    assertEquals(
        SkipCause.USER_DECLINED,
        entry.skipCause(),
        "a hardware skip and a user decline must be distinguishable without parsing prose");
    assertEquals("Search reranker was declined", entry.skipReason(), "the prose stays, for display");
  }
}
