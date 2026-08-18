/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes a download plan from the registry, hardware profile, and current installed state.
 *
 * <p>Pure function — no side effects, no IO beyond checking file existence. The plan can be
 * inspected, tested, and shown to the user before any download starts.
 */
public final class InstallPlanner {

  /**
   * Staging suffix an in-flight download occupies until it verifies and is renamed onto its final
   * target. Declared here — the lowest module that resolves install target paths — so the download
   * loop that writes the file and the planner that must account for it name the same convention
   * once instead of both hardcoding the literal.
   */
  public static final String PARTIAL_SUFFIX = ".partial";

  private InstallPlanner() {}

  /** The staging path a partially-downloaded {@code finalTarget} occupies. */
  public static Path partialPathFor(Path finalTarget) {
    return finalTarget.resolveSibling(finalTarget.getFileName() + PARTIAL_SUFFIX);
  }

  /**
   * Bytes already on disk for {@code finalTarget} in its sibling {@code .partial} staging file — the
   * progress a cancelled install kept, which a resumed download will not re-transfer.
   *
   * <p>This is the counterpart {@link #isAlreadyInstalled} cannot answer: that test asks about the
   * FINAL path (present, and exactly the expected size). A cancelled multi-GB download leaves the
   * final path absent and its bytes in the sibling staging path, so the pure size question ("how much
   * of this file is still to fetch?") needs both probes, not one.
   *
   * <p>Returns 0 for an absent partial, an empty one, or one longer than the expected total — the
   * same impossible-state guard {@code DownloadResume.decide} applies before it will resume, so the
   * planner never promises bytes the fetch would discard. Conservative by construction: over-counting
   * would understate the download the user is about to consent to.
   */
  public static long partialBytesFor(Path finalTarget, long expectedSize) {
    Path partial = partialPathFor(finalTarget);
    if (!Files.isRegularFile(partial)) {
      return 0L;
    }
    try {
      long staged = Files.size(partial);
      if (staged <= 0 || (expectedSize > 0 && staged > expectedSize)) {
        return 0L;
      }
      return staged;
    } catch (java.io.IOException e) {
      return 0L;
    }
  }

  /**
   * Computes the install plan.
   *
   * <p>Backwards-compat overload — derives the home directory from
   * {@code modelsDir.getParent()}. Existing tests/callers that pass only
   * {@code modelsDir} continue to work.
   *
   * @param registry the v2 model registry
   * @param hardware the detected hardware profile
   * @param modelsDir root models directory (for checking already-installed files)
   * @return the install plan
   */
  public static InstallPlan plan(ModelRegistry registry, HardwareProfile hardware, Path modelsDir) {
    Path homeDir = modelsDir.getParent() != null ? modelsDir.getParent() : modelsDir;
    return plan(registry, hardware, modelsDir, homeDir);
  }

  /**
   * Computes the install plan with an explicit home directory, at the default
   * {@link InstallIntent#DEFAULT} (Full Desktop). Backwards-compat overload —
   * existing callers get the full experience unchanged.
   */
  public static InstallPlan plan(
      ModelRegistry registry, HardwareProfile hardware, Path modelsDir, Path homeDir) {
    return plan(registry, hardware, InstallIntent.DEFAULT, modelsDir, homeDir);
  }

  /**
   * Computes the install plan for an explicit {@link InstallIntent} (tempdoc 657).
   *
   * <p>Intent is the product-shape axis, orthogonal to the hardware
   * {@link DownloadProfile}: a package is included iff its {@link CapabilityTier}
   * is {@link InstallIntent#wants wanted} by the intent <em>and</em> hardware
   * permits its variant. So {@code MCP_LITE} skips the LLM + runtime tiers even on
   * a capable GPU; the hardware gate still applies within the wanted tiers.
   *
   * <p>Tempdoc 374 alpha.15 fix B: packages with non-null {@code installRoot}
   * (currently the {@code cuda-runtime} package) install relative to
   * {@code homeDir} rather than {@code modelsDir}, so their files land in
   * shared runtime locations like {@code native-bin/llama-server/variants/cuda12}
   * instead of polluting the model tree. The planner produces an absolute
   * {@code targetPath} for these packages so {@link InstallPlan.PlannedDownload}
   * carries the resolved path through to the install service unchanged.
   *
   * @param registry the v2 model registry
   * @param hardware the detected hardware profile
   * @param intent the install/runtime intent selecting which capability tiers are wanted
   * @param modelsDir root models directory (typically {@code homeDir/models})
   * @param homeDir AI home directory (typically {@code %APPDATA%/io.justsearch.shell})
   * @return the install plan
   */
  public static InstallPlan plan(
      ModelRegistry registry,
      HardwareProfile hardware,
      InstallIntent intent,
      Path modelsDir,
      Path homeDir) {
    DownloadProfile profile = hardware.downloadProfile();
    List<InstallPlan.PlannedDownload> downloads = new ArrayList<>();
    List<InstallPlan.SkippedPackage> skipped = new ArrayList<>();
    List<String> alreadyInstalled = new ArrayList<>();
    long totalBytes = 0;
    long resumableBytes = 0;

    for (ModelPackage pkg : registry.packages()) {
      // Dev-only gate (tempdoc 842): a package flagged devOnly is never part of ANY user install
      // plan — unconditional, ahead of the intent/hardware gates, because no intent and no
      // hardware can make it wanted. Dev tooling fetches these directly from the registry.
      if (pkg.devOnly()) {
        skipped.add(
            new InstallPlan.SkippedPackage(
                pkg.id(), String.format("%s is a development-only package", pkg.label())));
        continue;
      }

      // Intent gate (tempdoc 657): skip packages whose capability tier this intent
      // does not want, independent of hardware. An untagged package (tier == null) is
      // always wanted, so pre-tier registries behave exactly as before.
      if (!intent.wants(pkg.tier())) {
        skipped.add(
            new InstallPlan.SkippedPackage(
                pkg.id(),
                String.format("Not included in %s mode", intent.id())));
        continue;
      }

      // CUDA-requiring packages (e.g. the cuda-runtime CUDA DLLs) are a hardware-support payload,
      // not a capability: include them iff the profile uses CUDA — the same axis selectVariant uses
      // to pick the FP16/CUDA model variants those DLLs make runnable — independent of the GGUF VRAM
      // floor. Gating them on the chat threshold wrongly skipped them for GPU_LITE (CUDA functional,
      // VRAM < 7.5 GB), silently downgrading CUDA ONNX inference to CPU. Tempdoc 772 Q3: this now
      // gates on the package's own requiresCuda flag rather than tier identity, so a future
      // RUNTIME-tier package that sets requiresCuda=false is never skipped here regardless of
      // hardware — while cuda-runtime (requiresCuda=true) keeps the exact GPU_LITE fix above.
      if (requiresUnavailableCuda(pkg, profile)) {
        skipped.add(
            new InstallPlan.SkippedPackage(
                pkg.id(),
                String.format(
                    "%s requires a CUDA-capable GPU (none detected on this system).", pkg.label())));
        continue;
      }

      // GGUF packages (chat) require GPU on this build — tempdoc 381 §"GPU-Primary"
      // direction. The skip reason names the actual constraint instead of the
      // misleading "CUDA not available" so the UI can surface why honestly.
      if (pkg.hasVramRequirement() && !profile.includesGguf()) {
        long minMb = pkg.minVramBytes() / (1024 * 1024);
        String reason;
        if (hardware.cudaFunctional()) {
          long haveMb = hardware.vramBytes() / (1024 * 1024);
          reason =
              String.format(
                  "Insufficient VRAM for %s (%d MB available, %d MB required)",
                  pkg.label(), haveMb, minMb);
        } else if (hardware.gpuDetected()) {
          reason =
              String.format(
                  "%s requires a CUDA-capable GPU. An NVIDIA GPU was detected but the CUDA"
                      + " runtime is not available — install the CUDA toolkit, or use this app"
                      + " without chat features.",
                  pkg.label());
        } else {
          reason =
              String.format(
                  "%s requires a CUDA-capable GPU (none detected on this system). CPU chat is"
                      + " not supported in this build.",
                  pkg.label());
        }
        skipped.add(new InstallPlan.SkippedPackage(pkg.id(), reason));
        continue;
      }

      // Tempdoc 374 alpha.15 fix B: when pkg.installRoot is set, the package
      // installs under homeDir/installRoot/targetDir (and the planner emits
      // ABSOLUTE targetPath strings so AiInstallService bypasses modelsDir).
      // Otherwise existing behavior — paths relative to modelsDir.
      Path installBaseDir = pkg.installRoot() != null && !pkg.installRoot().isBlank()
          ? homeDir.resolve(pkg.installRoot()).resolve(pkg.targetDir())
          : modelsDir.resolve(pkg.targetDir());
      boolean useAbsoluteTargetPath = pkg.installRoot() != null && !pkg.installRoot().isBlank();

      // Select the variant for this profile
      ModelVariant variant = pkg.selectVariant(profile);
      boolean packageFullyInstalled = true;

      if (variant != null) {
        Path targetFile = installBaseDir.resolve(variant.filename());
        if (isAlreadyInstalled(targetFile, variant.sizeBytes())) {
          // Already installed with correct hash — skip download
        } else {
          String targetPath = useAbsoluteTargetPath
              ? targetFile.toAbsolutePath().toString()
              : joinTargetPath(pkg.targetDir(), variant.filename());
          downloads.add(
              new InstallPlan.PlannedDownload(
                  pkg.id(), variant.downloadUrl(), targetPath, variant.sha256(),
                  variant.sizeBytes(), true, false, true));
          totalBytes += variant.sizeBytes();
          resumableBytes += partialBytesFor(targetFile, variant.sizeBytes());
          packageFullyInstalled = false;
        }
      }

      // Supporting files are always downloaded (profile-independent)
      for (SupportingFile sf : pkg.supportingFiles()) {
        Path targetFile = installBaseDir.resolve(sf.filename());
        if (isAlreadyInstalled(targetFile, sf.sizeBytes())) {
          continue;
        }
        String targetPath = useAbsoluteTargetPath
            ? targetFile.toAbsolutePath().toString()
            : joinTargetPath(pkg.targetDir(), sf.filename());
        downloads.add(
            new InstallPlan.PlannedDownload(
                pkg.id(),
                sf.downloadUrl(),
                targetPath,
                sf.sha256(),
                sf.sizeBytes(),
                false,
                sf.extract(),
                sf.required()));
        totalBytes += sf.sizeBytes();
        resumableBytes += partialBytesFor(targetFile, sf.sizeBytes());
        packageFullyInstalled = false;
      }

      if (packageFullyInstalled) {
        alreadyInstalled.add(pkg.id());
      }
    }

    return new InstallPlan(
        profile, downloads, skipped, totalBytes, alreadyInstalled, resumableBytes);
  }

  /**
   * Whether {@code pkg} requires CUDA the current profile does not provide — the single source for
   * the requiresCuda hardware gate, so the planner loop and any consumer that needs the same
   * decision (e.g. the preflight severity signal) never re-implement {@code pkg.requiresCuda() &&
   * !profile.usesCuda()} verbatim (tempdoc 772 Q3).
   */
  public static boolean requiresUnavailableCuda(ModelPackage pkg, DownloadProfile profile) {
    return pkg.requiresCuda() && !profile.usesCuda();
  }

  /**
   * Whether the package would be included in the plan for the given {@code intent} and
   * {@code hardware} — i.e. it is NOT skipped by the dev-only gate, the intent gate, the
   * requiresCuda hardware gate, or the GGUF VRAM floor. Mirrors the skip conditions in
   * {@link #plan}'s loop so consumers get the include/skip verdict from one place rather than
   * re-deriving the devOnly/tier/CUDA/VRAM checks (tempdoc 772 Q3, tempdoc 842). Note: a variant
   * might still be absent, but that is a completeness concern, not an inclusion one.
   */
  public static boolean isIncludedByPlan(
      ModelPackage pkg, InstallIntent intent, HardwareProfile hardware) {
    DownloadProfile profile = hardware.downloadProfile();
    if (pkg.devOnly()) {
      return false;
    }
    if (!intent.wants(pkg.tier())) {
      return false;
    }
    if (requiresUnavailableCuda(pkg, profile)) {
      return false;
    }
    if (pkg.hasVramRequirement() && !profile.includesGguf()) {
      return false;
    }
    return true;
  }

  /**
   * Checks whether a file is already correctly installed. Planning stays cheap (O(1)): it checks
   * existence and, when the expected size is known ({@code expectedSize > 0}), that the on-disk size
   * matches — this catches a truncated or wrong file without the multi-GB hashing the pure planner
   * must avoid. Full SHA-256 verification of freshly-downloaded files still happens during the
   * install execution phase ({@code DownloadExecutor.verify}); a same-size byte-flip in an
   * already-present file is not caught here by design.
   */
  private static boolean isAlreadyInstalled(Path file, long expectedSize) {
    if (!Files.isRegularFile(file)) {
      return false;
    }
    if (expectedSize <= 0) {
      return true;
    }
    try {
      return Files.size(file) == expectedSize;
    } catch (java.io.IOException e) {
      return false;
    }
  }

  /**
   * Joins a package targetDir with a filename. Empty targetDir produces just the filename
   * (no leading slash), so {@code Path.resolve} treats it as a relative child of modelsDir
   * rather than absolute path.
   */
  private static String joinTargetPath(String targetDir, String filename) {
    return targetDir.isEmpty() ? filename : targetDir + "/" + filename;
  }
}
