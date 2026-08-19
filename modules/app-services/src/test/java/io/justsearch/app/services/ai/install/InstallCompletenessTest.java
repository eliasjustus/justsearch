package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.SkipCause;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  /**
   * Tempdoc 840: {@code packagesWithMissingRequiredFiles()} had NO coverage in this seam's guard test
   * — it arrived with tempdoc 824 (commit e9d68989) and the strength baseline predates it, so the
   * ratchet never noticed. Surfaced by a PIT run while registering the sibling {@code
   * acquisition-rate} seam.
   *
   * <p>The property that makes this method distinct from its two neighbours, and the reason 824
   * introduced it: it is the per-package form of {@link InstallCompleteness#repairNeeded()}, so the
   * completion MESSAGE is derived from the same authority as {@code installedFully} rather than from
   * the run's own bookkeeping. Two things must hold — it counts a package once no matter how many of
   * its required files are gone, and a package whose ONLY casualty is optional must be absent, or the
   * message would name a package as failed while sitting next to {@code installedFully: true} (the
   * round-16 defect: an 872-byte metadata sidecar reported as a broken component).
   */
  @Test
  @DisplayName("per-package required gaps: deduped, and an optional-only casualty is not a gap")
  void packagesWithMissingRequiredFiles_dedupesAndIgnoresOptionalOnly() {
    InstallPlan.PlannedDownload optionalSidecar =
        new InstallPlan.PlannedDownload(
            "splade", "https://example/splade/config.json", "splade/config.json", "sha", 872L,
            false, false, false);
    InstallPlan plan =
        plan(
            List.of(
                file("cuda-runtime", "runtime/cuda-runtime-12.4.zip"),
                file("cuda-runtime", "runtime/ort-native-cuda12-v1.24.3.zip"),
                optionalSidecar));

    InstallCompleteness result = InstallCompleteness.compute(plan, contract());

    assertEquals(
        List.of("cuda-runtime"),
        result.packagesWithMissingRequiredFiles(),
        "two required files gone from ONE package is one entry, and splade's optional-only gap is none");
    assertEquals(
        List.of(new InstallCompleteness.OptionalGap("splade", "config.json")),
        result.optionalGaps(),
        "the optional casualty is still reported — shown, never alarming");
    assertTrue(result.repairNeeded(), "a required file IS missing, so repair is still warranted");
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

  // ── Declined components (tempdoc 840 Phase 2) ─────────────────────────────────────────────────

  private static InstallPlan planWithSkips(
      List<InstallPlan.PlannedDownload> downloads,
      List<InstallPlan.SkippedPackage> skipped,
      String... installed) {
    long total = downloads.stream().mapToLong(InstallPlan.PlannedDownload::sizeBytes).sum();
    return new InstallPlan(PROFILE, downloads, skipped, total, List.of(installed));
  }

  private static InstallPlan.SkippedPackage declinedSkip(String packageId) {
    return new InstallPlan.SkippedPackage(packageId, SkipCause.USER_DECLINED, "you declined it");
  }

  /**
   * Package ids this computation classified {@code DECLINED}, deduped in plan order.
   *
   * <p>Read off {@code files()} rather than a dedicated accessor: {@code declinedPackages()} was
   * deleted (tempdoc 840 R8) because nothing consumed it — it is absent from {@code
   * ai-install-status.v1.json} and from the generated TS, and the per-component {@code declined}
   * flag on the plan preview is the chosen authority for what the user turned off. The
   * CLASSIFICATION is still load-bearing (it is what keeps a decline out of every gap and truth
   * claim), so these tests keep asserting it at its remaining observation point.
   */
  private static List<String> declinedPackagesIn(InstallCompleteness result) {
    return result.files().stream()
        .filter(f -> f.classification() == InstallCompleteness.Classification.DECLINED)
        .map(InstallCompleteness.FileState::packageId)
        .distinct()
        .toList();
  }

  /**
   * The normal production shape: the plan was computed WITH the declined set, so the declined
   * package never reaches {@code downloads()} at all — it is in {@code skipped()}. Completeness must
   * still name it, and must not call the machine incomplete for it.
   */
  @Test
  @DisplayName("a declined package the plan skipped is named, and is not a gap")
  void declinedSkippedPackage_isNamedAndIsNotAGap() {
    InstallContract contract = contract(installed("embedding", "model.onnx"));
    InstallPlan plan =
        planWithSkips(List.of(), List.of(declinedSkip("reranker")), "embedding");

    InstallCompleteness result = InstallCompleteness.compute(plan, contract, Set.of("reranker"));

    assertEquals(List.of("reranker"), declinedPackagesIn(result));
    assertTrue(result.installedFully(), "an install the user shaped by declining a part is complete");
    assertFalse(result.repairNeeded(), "a deliberate decline must never raise a Repair prompt");
    assertTrue(result.pendingRegistryAdditions().isEmpty());
  }

  /**
   * A package skipped for a reason that was NOT the user's choice (hardware, intent) must not be
   * reported as declined — that list is about the user's decisions, and mislabeling a hardware skip
   * as one would offer a re-enable switch the machine cannot honor.
   */
  @Test
  @DisplayName("a hardware-skipped package is not reported as declined")
  void hardwareSkippedPackage_isNotDeclined() {
    InstallPlan plan =
        planWithSkips(
            List.of(),
            List.of(
                new InstallPlan.SkippedPackage("chat", SkipCause.HARDWARE, "no CUDA GPU"),
                declinedSkip("reranker")),
            "embedding");

    InstallCompleteness result = InstallCompleteness.compute(plan, contract(), Set.of("reranker"));

    assertEquals(
        List.of("reranker"),
        declinedPackagesIn(result),
        "only the package the user actually declined belongs on this list");
  }

  /**
   * The defensive half: a plan computed WITHOUT the declined set still lists the declined package's
   * files under {@code downloads()}. The user's current intent wins there too — otherwise a caller
   * that forgot to thread the set into the planner would produce a permanent "Not Installed".
   */
  @Test
  @DisplayName("declined files still in a plan's downloads are DECLINED, not a missing gap")
  void declinedFilesInDownloads_areNotGaps() {
    InstallContract contract =
        contract(installed("embedding", "model.onnx"), installed("reranker", "model.onnx"));
    InstallPlan plan =
        plan(
            List.of(
                file("reranker", "onnx/reranker/model.onnx"),
                file("reranker", "onnx/reranker/tokenizer.json")),
            "embedding");

    InstallCompleteness result = InstallCompleteness.compute(plan, contract, Set.of("reranker"));

    assertEquals(
        List.of("reranker"),
        declinedPackagesIn(result),
        "two declined files collapse to one package id (dedup, plan order)");
    assertTrue(
        result.installedFully(),
        "these files are contracted AND missing — only the user's current intent stops them being"
            + " the round-11 'real gap'");
    assertFalse(result.repairNeeded());
    assertTrue(result.packagesWithMissingRequiredFiles().isEmpty());
    assertTrue(result.pendingRegistryAdditions().isEmpty());
    assertEquals(
        InstallCompleteness.Classification.DECLINED,
        result.files().get(1).classification());
  }

  /** A decline must not launder some OTHER package's genuine gap. */
  @Test
  @DisplayName("declining one package leaves another package's gap untouched")
  void decliningOnePackage_doesNotHideAnothersGap() {
    InstallContract contract =
        contract(installed("embedding", "model.onnx"), installed("chat", "model.gguf"));
    InstallPlan plan =
        plan(
            List.of(file("reranker", "onnx/reranker/model.onnx"), file("chat", "gguf/model.gguf")),
            "embedding");

    InstallCompleteness result = InstallCompleteness.compute(plan, contract, Set.of("reranker"));

    assertEquals(List.of("reranker"), declinedPackagesIn(result));
    assertFalse(result.installedFully(), "chat's contracted model.gguf is genuinely gone");
    assertTrue(result.repairNeeded());
    assertEquals(List.of("chat"), result.packagesWithMissingRequiredFiles());
  }

  /** An OPTIONAL file of a declined package is not an optional gap either — it is simply declined. */
  @Test
  @DisplayName("a declined package's optional file is not reported as an optional gap")
  void declinedOptionalFile_isNotAnOptionalGap() {
    InstallPlan plan =
        plan(
            List.of(optionalFile("splade", "splade/naver-splade-v3/config.json")),
            "embedding");

    InstallCompleteness result = InstallCompleteness.compute(plan, contract(), Set.of("splade"));

    assertTrue(
        result.optionalGaps().isEmpty(),
        "reporting it would say 'splade is missing a file' about a package the user turned off");
    assertEquals(List.of("splade"), declinedPackagesIn(result));
  }

  /**
   * Completeness reads the user's CURRENT preference, never the contract's historical record. A
   * component declined last install and re-enabled since must become an offerable gap again —
   * reading {@code SkipCause.USER_DECLINED} from the contract would freeze the old decision.
   */
  @Test
  @DisplayName("re-enabling a previously declined component turns its files back into a real gap")
  void reEnablingAPreviouslyDeclinedComponent_reopensTheGap() {
    // The contract records the historical fact: last run, the user declined the reranker.
    InstallContract contract =
        contract(
            installed("embedding", "model.onnx"),
            InstallContract.InstalledModel.skipped(
                "reranker", SkipCause.USER_DECLINED, "you declined it"));
    InstallPlan plan = plan(List.of(file("reranker", "onnx/reranker/model.onnx")), "embedding");

    // Intent has since flipped back: the declined set no longer names it.
    InstallCompleteness result = InstallCompleteness.compute(plan, contract, Set.of());

    assertTrue(declinedPackagesIn(result).isEmpty(), "intent, not history, decides");
    assertTrue(result.repairNeeded(), "the component the user now wants is genuinely absent");
    assertEquals(List.of("reranker"), result.pendingRegistryAdditions());
  }

  /** Absent intent behaves exactly like empty intent — for both the 2-arg overload and an explicit null. */
  @Test
  @DisplayName("no declined set (omitted or null) declines nothing")
  void absentDeclinedSet_declinesNothing() {
    InstallContract contract = contract(installed("embedding", "model.onnx"));
    InstallPlan plan =
        planWithSkips(
            List.of(file("reranker", "onnx/reranker/model.onnx")),
            List.of(declinedSkip("chat")),
            "embedding");

    InstallCompleteness viaOverload = InstallCompleteness.compute(plan, contract);
    InstallCompleteness viaNull = InstallCompleteness.compute(plan, contract, null);

    for (InstallCompleteness result : List.of(viaOverload, viaNull)) {
      assertTrue(declinedPackagesIn(result).isEmpty(), "nothing is declined without intent saying so");
      assertTrue(result.repairNeeded(), "and the missing reranker file is an ordinary gap");
      assertEquals(List.of("reranker"), result.pendingRegistryAdditions());
    }
    assertEquals(viaOverload.files(), viaNull.files());
  }
}
