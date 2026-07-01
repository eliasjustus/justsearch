/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.preflight;

import io.justsearch.app.api.AiRuntimeStatusResponse;
import io.justsearch.app.services.ai.install.AiInstallService;
import io.justsearch.app.services.ai.runtime.RuntimeActivationService;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelVariant;
import io.justsearch.configuration.model.SupportingFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tempdoc 656 Task 4: registry-driven preflight — answers "given the model registry (the
 * declared source of truth for what should exist, and where), what's actually present on disk
 * right now" without requiring a running/activating inference process.
 *
 * <p>This is deliberately a thin reconciliation over two already-existing, already-correct
 * primitives — {@link AiInstallService#getManifest()} + {@link AiInstallService#modelsDir()} for
 * model-file identity/location, and {@link RuntimeActivationService#getStatus()} for runtime-exe
 * presence — not a new independent prober. It does not download, install, or mutate anything.
 */
public final class AiPreflightService {

  private final AiInstallService installService;
  private final RuntimeActivationService runtimeService;

  public AiPreflightService(AiInstallService installService, RuntimeActivationService runtimeService) {
    this.installService = installService;
    this.runtimeService = runtimeService;
  }

  public AiPreflightResult getPreflight() {
    ModelRegistry registry = installService.getManifest();
    Path modelsDir = installService.modelsDir();
    Path aiHome = installService.aiHome();

    List<PackageStatus> packages = new ArrayList<>();
    for (ModelPackage pkg : registry.packages()) {
      Path baseDir = pkg.installRoot() != null ? aiHome.resolve(pkg.installRoot()) : modelsDir;
      Path pkgDir = baseDir.resolve(pkg.targetDir());

      List<String> presentVariantFiles = new ArrayList<>();
      for (ModelVariant v : pkg.variants()) {
        if (Files.isRegularFile(pkgDir.resolve(v.filename()))) {
          presentVariantFiles.add(v.filename());
        }
      }
      List<String> missingSupportingFiles = new ArrayList<>();
      for (SupportingFile sf : pkg.supportingFiles()) {
        if (!Files.isRegularFile(pkgDir.resolve(sf.filename()))) {
          missingSupportingFiles.add(sf.filename());
        }
      }

      // A package with no variants at all (e.g. cuda-runtime, which ships only supportingFiles)
      // is "present" purely on its supporting files being complete; otherwise at least one
      // variant (any precision/EP) must be present, mirroring how CPU vs. GPU variants are
      // alternatives, not all-required.
      boolean variantsSatisfied = pkg.variants().isEmpty() || !presentVariantFiles.isEmpty();
      boolean complete = variantsSatisfied && missingSupportingFiles.isEmpty();

      packages.add(
          new PackageStatus(
              pkg.id(), pkg.label(), complete, presentVariantFiles, missingSupportingFiles));
    }

    AiRuntimeStatusResponse runtimeStatus = runtimeService.getStatus();
    boolean runtimeInstalled =
        !runtimeStatus.installedVariants().isEmpty()
            || (runtimeStatus.active() != null
                && runtimeStatus.active().serverExecutablePath() != null
                && !runtimeStatus.active().serverExecutablePath().isBlank());

    // Tempdoc 656 (post-implementation review fix): canActivateDefault must mirror what
    // RuntimeActivationService.runActivate() actually checks — the chat GGUF variant file plus the
    // runtime executable — NOT full package completeness. The "chat" package's supportingFiles
    // include mmproj-F16.gguf, which is a separate, VDU-only concern (LifecycleReasonCode
    // .VDU_MISSING_MMPROJ / vdu.missing_mmproj already model it as its own capability); runActivate
    // never references it. Gating on PackageStatus.complete() here would report "cannot activate"
    // in a case where activation would actually succeed. presentVariantFiles() alone matches
    // runActivate's real precondition; complete() remains available on PackageStatus for other
    // consumers that legitimately want full-download-completeness, not activation-readiness.
    boolean chatVariantPresent =
        packages.stream()
            .filter(p -> "chat".equals(p.id()))
            .findFirst()
            .map(p -> !p.presentVariantFiles().isEmpty())
            .orElse(false);

    boolean canActivateDefault = runtimeInstalled && chatVariantPresent;

    return new AiPreflightResult(packages, runtimeInstalled, canActivateDefault);
  }

  /** Per-package presence, projected from the registry's own identity fields (id/label). */
  public record PackageStatus(
      String id,
      String label,
      boolean complete,
      List<String> presentVariantFiles,
      List<String> missingFiles) {}

  /**
   * Overall preflight result.
   *
   * @param canActivateDefault mirrors {@code RuntimeActivationService.runActivate()}'s actual
   *     precondition (runtime executable + chat GGUF variant present) — deliberately not the same
   *     as every package's {@code complete()}, since some supportingFiles (e.g. the chat package's
   *     mmproj) are required for other capabilities (VDU) but not for base activation.
   */
  public record AiPreflightResult(
      List<PackageStatus> packages, boolean runtimeInstalled, boolean canActivateDefault) {}
}
