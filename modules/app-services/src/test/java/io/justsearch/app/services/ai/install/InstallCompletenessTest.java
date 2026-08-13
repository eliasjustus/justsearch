package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallPlan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 805 G.3 W-TRUTH — file-granularity install completeness.
 *
 * <p>The scenario these tests exist for (round 11, tempdoc 734 R11-F3/F4): an upgraded machine's
 * {@code cuda-runtime} package gained a NEW supporting file ({@code ort-native-cuda12-v1.24.3.zip},
 * PR #276) that the upgrade never downloaded. Every ONNX encoder silently fell back to CPU while
 * {@code /api/ai/install/status} reported {@code installedFully:false} with an EMPTY package list
 * and no pending additions — three wrong answers from one package-level check.
 */
final class InstallCompletenessTest {

  private static final DownloadProfile PROFILE = DownloadProfile.values()[0];

  private static InstallPlan plan(List<InstallPlan.PlannedDownload> downloads, String... installed) {
    long total = downloads.stream().mapToLong(InstallPlan.PlannedDownload::sizeBytes).sum();
    return new InstallPlan(PROFILE, downloads, List.of(), total, List.of(installed));
  }

  private static InstallPlan.PlannedDownload file(String packageId, String targetPath) {
    return new InstallPlan.PlannedDownload(
        packageId, "https://example/" + targetPath, targetPath, "sha", 100L, false);
  }

  private static InstallContract contract(InstallContract.InstalledModel... entries) {
    Map<String, InstallContract.InstalledModel> models = new LinkedHashMap<>();
    for (InstallContract.InstalledModel e : entries) {
      models.put(e.packageId(), e);
    }
    return new InstallContract(2, 1L, null, PROFILE, models);
  }

  private static InstallContract.InstalledModel installed(String packageId, String... files) {
    return new InstallContract.InstalledModel(
        packageId, files.length > 0 ? files[0] : null, null, null, packageId, "sha",
        List.of(files), false, null);
  }

  // ── (i) The round-11 scenario ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("round-11: a skipped-kind contract entry claims no files, so its new file is an addition")
  void roundEleven_skippedCudaRuntimeEntry_missingOrtNative() {
    // The contract both 0.1.0 and 0.2.0 actually wrote: cuda-runtime has `variants: []`, so
    // ModelPackage.selectVariant returned null and the writer recorded skipped("No variant") with
    // NO installedFiles (tempdoc 805 Part H, U3 — verified at the v0.1.0 tag).
    InstallContract contract =
        contract(
            installed("embedding", "model.onnx"),
            installed("chat", "model.gguf"),
            InstallContract.InstalledModel.skipped("cuda-runtime", "No variant"));
    InstallPlan plan =
        plan(
            List.of(
                file(
                    "cuda-runtime",
                    "C:\\Users\\u\\AppData\\Roaming\\io.justsearch.shell\\native-bin\\ort\\ort-native-cuda12-v1.24.3.zip")),
            "embedding",
            "chat");

    InstallCompleteness result = InstallCompleteness.compute(plan, contract);

    assertTrue(
        result.installedFully(),
        "a skipped-kind entry claims no files — its package's missing file is a registry addition,"
            + " not the contracted gap round 11 reported");
    assertEquals(
        List.of("cuda-runtime"),
        result.pendingRegistryAdditions(),
        "the newly-registered artifact is named so the UI can offer it");
    assertTrue(
        result.repairNeeded(),
        "a required file IS missing — repair must be offered even though the file is an addition"
            + " (this is the consequence round 11 had no signal for)");
  }

  @Test
  @DisplayName("entry-kind rule: a skipped entry claims nothing even if it carries file names")
  void skippedEntryClaimsNothing_evenWithFileNames() {
    // `InstalledModel.skipped()` writes an empty file list today, so the round-11 fixture above
    // cannot distinguish the entry-kind rule from plain per-file matching. This one can: a skipped
    // entry that DOES name files (a hand-edited or future-writer contract) still claims none —
    // a skip records that nothing was installed, so it can never be evidence that something was.
    InstallContract contract =
        contract(
            installed("embedding", "model.onnx"),
            new InstallContract.InstalledModel(
                "cuda-runtime", null, null, null, "runtime", null,
                List.of("ort-native-cuda12-v1.24.3.zip"), true, "No variant"));
    InstallPlan plan =
        plan(List.of(file("cuda-runtime", "runtime/ort-native-cuda12-v1.24.3.zip")), "embedding");

    InstallCompleteness result = InstallCompleteness.compute(plan, contract);

    assertEquals(
        List.of("cuda-runtime"),
        result.pendingRegistryAdditions(),
        "a skipped-kind entry is not contracted-for-files (tempdoc 805 Part H, U3)");
    assertTrue(result.installedFully(), "so it is not a contracted gap");
    assertTrue(result.repairNeeded());
  }

  // ── (ii) A real gap ───────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("a contracted FILE missing from disk is a real gap")
  void contractedFileMissing_isARealGap() {
    InstallContract contract = contract(installed("embedding", "model.onnx"), installed("chat", "model.gguf"));
    InstallPlan plan = plan(List.of(file("chat", "chat/model.gguf")), "embedding");

    InstallCompleteness result = InstallCompleteness.compute(plan, contract);

    assertFalse(
        result.installedFully(),
        "the contract named model.gguf as installed and disk lacks it — the honest 'Not Installed'");
    assertTrue(
        result.pendingRegistryAdditions().isEmpty(),
        "a real gap is not a registry addition");
    assertTrue(result.repairNeeded(), "a missing required file always warrants repair");
    assertTrue(
        result.files().stream()
            .anyMatch(f -> f.classification() == InstallCompleteness.Classification.MISSING_CONTRACTED),
        "classified per file, not per package");
  }

  // ── (iii) Everything satisfied ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("nothing left to download: installed, no additions, no repair")
  void allSatisfied() {
    InstallContract contract = contract(installed("embedding", "model.onnx"), installed("chat", "model.gguf"));

    InstallCompleteness result = InstallCompleteness.compute(plan(List.of(), "embedding", "chat"), contract);

    assertTrue(result.installedFully());
    assertTrue(result.pendingRegistryAdditions().isEmpty());
    assertFalse(result.repairNeeded());
    assertTrue(
        result.files().stream()
            .allMatch(f -> f.classification() == InstallCompleteness.Classification.SATISFIED),
        "every enumerated file is satisfied");
  }

  // ── (iv) The forward-fixed contract ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("forward-fixed contract: a file absent from installedFiles is uncontracted even on an installed-kind entry")
  void forwardFixedContract_newFileIsStillAnAddition() {
    // After the buildContract forward fix, cuda-runtime is recorded installed-WITH-FILES. A LATER
    // app version adds a second supporting file to the same package. The contract is a record of
    // what was installed — it cannot have claimed a file that did not exist when it was written —
    // so the new file is uncontracted even though the package is contracted. Contractedness is a
    // per-FILE property; only that keeps `installedFully` a truthful claim about install history
    // while `repairNeeded` (below) carries the consequence.
    InstallContract contract =
        contract(installed("embedding", "model.onnx"), installed("cuda-runtime", "cuda-runtime-12.4.zip"));
    InstallPlan plan = plan(List.of(file("cuda-runtime", "runtime/ort-native-cuda12-v9.9.9.zip")), "embedding");

    InstallCompleteness result = InstallCompleteness.compute(plan, contract);

    assertEquals(
        List.of(
            new InstallCompleteness.FileState(
                "cuda-runtime",
                "ort-native-cuda12-v9.9.9.zip",
                InstallCompleteness.Classification.MISSING_UNCONTRACTED)),
        result.files().stream().filter(f -> f.fileName() != null
            && f.fileName().startsWith("ort-native")).toList(),
        "the added file is uncontracted — it is not in THIS entry's installedFiles");
    assertTrue(result.installedFully(), "the recorded install is still complete on its own terms");
    assertEquals(List.of("cuda-runtime"), result.pendingRegistryAdditions());
    assertTrue(result.repairNeeded(), "and the consequence is still surfaced");
  }

  @Test
  @DisplayName("the same package can carry a contracted gap AND an addition at once")
  void mixedClassificationWithinOnePackage() {
    InstallContract contract =
        contract(installed("embedding", "model.onnx"), installed("cuda-runtime", "cuda-runtime-12.4.zip"));
    InstallPlan plan =
        plan(
            List.of(
                file("cuda-runtime", "runtime/cuda-runtime-12.4.zip"),
                file("cuda-runtime", "runtime/ort-native-cuda12-v1.24.3.zip")),
            "embedding");

    InstallCompleteness result = InstallCompleteness.compute(plan, contract);

    assertFalse(
        result.installedFully(),
        "the contracted file is gone — a package-level check would have been forced to pick one answer");
    assertEquals(List.of("cuda-runtime"), result.pendingRegistryAdditions());
    assertTrue(result.repairNeeded());
  }

  @Test
  @DisplayName("satisfied files are named from the contract; a package it does not enumerate reports one nameless row")
  void satisfiedFilesCarryContractFileNames() {
    InstallContract contract =
        contract(
            new InstallContract.InstalledModel(
                "embedding", "model.onnx", null, null, "onnx", "sha",
                List.of("model.onnx", "tokenizer.json"), false, null));

    InstallCompleteness result =
        InstallCompleteness.compute(plan(List.of(), "embedding", "uncontracted-pkg"), contract);

    assertEquals(
        List.of("model.onnx", "tokenizer.json"),
        result.files().stream()
            .filter(f -> "embedding".equals(f.packageId()))
            .map(InstallCompleteness.FileState::fileName)
            .toList(),
        "a contracted, fully-installed package is enumerated per file");
    assertEquals(
        java.util.Collections.singletonList(null),
        result.files().stream()
            .filter(f -> "uncontracted-pkg".equals(f.packageId()))
            .map(InstallCompleteness.FileState::fileName)
            .toList(),
        "a package the contract does not enumerate is present-but-unnamed, never invented");
  }

  @Test
  @DisplayName("file names come from the LAST path segment, including a rooted path")
  void fileNameIsTheLastSegment() {
    // The planner emits ABSOLUTE target paths for installRoot packages (cuda-runtime), so the
    // basename split is load-bearing: a leading separator must not survive into the name compared
    // against the contract's installedFiles.
    InstallContract contract = contract(installed("cuda-runtime", "ort-native-cuda12-v1.24.3.zip"));
    InstallPlan plan = plan(List.of(file("cuda-runtime", "/ort-native-cuda12-v1.24.3.zip")), "embedding");

    InstallCompleteness result = InstallCompleteness.compute(plan, contract);

    assertEquals(
        List.of("ort-native-cuda12-v1.24.3.zip"),
        result.files().stream()
            .filter(f -> f.classification() != InstallCompleteness.Classification.SATISFIED)
            .map(InstallCompleteness.FileState::fileName)
            .toList());
    assertFalse(result.installedFully(), "the contract DID claim this exact file — a real gap");
  }

  // ── Degenerate inputs ─────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("no contract: the plan is the only authority, so remaining downloads mean not-installed")
  void noContract_planIsTheOnlyAuthority() {
    InstallCompleteness result =
        InstallCompleteness.compute(plan(List.of(file("chat", "chat/model.gguf")), "embedding"), null);

    assertFalse(result.installedFully(), "a fresh/pre-contract machine cannot claim completeness");
    assertTrue(result.repairNeeded());
  }

  @Test
  @DisplayName("nothing installed at all is never 'fully installed'")
  void nothingInstalled() {
    InstallCompleteness result = InstallCompleteness.compute(plan(List.of()), contract(installed("chat", "m.gguf")));

    assertFalse(result.installedFully(), "an empty registry/plan must not read as a complete install");
    assertFalse(result.repairNeeded(), "…but nothing is missing either");
  }

  // ── Required/optional axis (tempdoc 824 §3.3b) ────────────────────────────────────────────────

  private static InstallPlan.PlannedDownload optionalFile(String packageId, String targetPath) {
    return new InstallPlan.PlannedDownload(
        packageId, "https://example/" + targetPath, targetPath, "sha", 100L, false, false, false);
  }

  /**
   * Round 16's wedge, at file granularity: the ONLY thing missing is {@code splade/config.json},
   * which carries {@code "required": false} because no required-file list names it and no resolver
   * call site reads it. Before the axis this produced the same verdict as a missing 500 MB model.
   */
  @Test
  @DisplayName("round-16: an optional-only gap is not a repair, and completeness survives it")
  void optionalOnlyGap_isNotAGap() {
    InstallContract contract =
        contract(installed("splade", "model_fp16.onnx", "tokenizer.json", "vocab.txt", "idf.json"));
    InstallPlan plan =
        plan(List.of(optionalFile("splade", "splade/naver-splade-v3/config.json")), "embedding");

    InstallCompleteness result = InstallCompleteness.compute(plan, contract);

    assertFalse(result.repairNeeded(), "no required file is missing — nothing to repair");
    assertTrue(result.installedFully(), "an optional metadata sidecar is not an incomplete install");
    assertEquals(
        List.of(new InstallCompleteness.OptionalGap("splade", "config.json")),
        result.optionalGaps(),
        "…and it is still REPORTED, just not as an alarm");
    assertTrue(
        result.pendingRegistryAdditions().isEmpty(),
        "an optional gap must not be re-reported as 'new AI components are available'");
  }

  /** The axis must never soften a REQUIRED gap — every pre-824 verdict is bit-for-bit unchanged. */
  @Test
  @DisplayName("a required gap beside an optional one still repairs, and still reads incomplete")
  void requiredGapBesideOptional_isStillAGap() {
    InstallContract contract = contract(installed("splade", "model_fp16.onnx", "vocab.txt"));
    InstallPlan plan =
        plan(
            List.of(
                file("splade", "splade/naver-splade-v3/vocab.txt"),
                optionalFile("splade", "splade/naver-splade-v3/config.json")),
            "embedding");

    InstallCompleteness result = InstallCompleteness.compute(plan, contract);

    assertTrue(result.repairNeeded(), "the contracted required file IS missing");
    assertFalse(result.installedFully());
    assertEquals(
        List.of(new InstallCompleteness.OptionalGap("splade", "config.json")),
        result.optionalGaps(),
        "the optional gap is reported alongside, not instead");
  }

  /**
   * Fail-closed default: a plan whose downloads never declared the axis (every pre-824 construction
   * path, and every registry entry without an explicit {@code "required"}) is REQUIRED.
   */
  @Test
  @DisplayName("an unclassified file is required — the default fails closed")
  void unclassifiedFileIsRequired() {
    InstallPlan.PlannedDownload legacy =
        new InstallPlan.PlannedDownload("ner", "https://example/c", "onnx/ner/config.json", "sha", 1L, false);

    assertTrue(legacy.required(), "the compat constructor must not silently make a file optional");
    InstallCompleteness result = InstallCompleteness.compute(plan(List.of(legacy), "embedding"), null);
    assertTrue(result.repairNeeded());
    assertTrue(result.optionalGaps().isEmpty());
  }
}
