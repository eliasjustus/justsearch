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

  private InstallPlanner() {}

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

    for (ModelPackage pkg : registry.packages()) {
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
        if (isAlreadyInstalled(targetFile)) {
          // Already installed with correct hash — skip download
        } else {
          String targetPath = useAbsoluteTargetPath
              ? targetFile.toAbsolutePath().toString()
              : joinTargetPath(pkg.targetDir(), variant.filename());
          downloads.add(
              new InstallPlan.PlannedDownload(
                  pkg.id(), variant.downloadUrl(), targetPath, variant.sha256(),
                  variant.sizeBytes(), true));
          totalBytes += variant.sizeBytes();
          packageFullyInstalled = false;
        }
      }

      // Supporting files are always downloaded (profile-independent)
      for (SupportingFile sf : pkg.supportingFiles()) {
        Path targetFile = installBaseDir.resolve(sf.filename());
        if (isAlreadyInstalled(targetFile)) {
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
                sf.extract()));
        totalBytes += sf.sizeBytes();
        packageFullyInstalled = false;
      }

      if (packageFullyInstalled) {
        alreadyInstalled.add(pkg.id());
      }
    }

    return new InstallPlan(profile, downloads, skipped, totalBytes, alreadyInstalled);
  }

  /**
   * Checks whether a file is already correctly installed. Planning phase only checks existence
   * (multi-GB hashing would be wasteful here); full SHA-256 verification happens during the
   * install execution phase. Callers therefore do not pass the expected hash — they pass it later
   * to the execution step.
   */
  private static boolean isAlreadyInstalled(Path file) {
    return Files.isRegularFile(file);
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
