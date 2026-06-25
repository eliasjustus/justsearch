/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Selects the best model variant for the current hardware and installed state.
 *
 * <p>Pure function: takes the install contract (what's on disk) and hardware profile (what the
 * machine can run), returns a {@link VariantSelection} for each model.
 *
 * <p>Selection logic:
 *
 * <ol>
 *   <li>If the model is skipped in the contract (e.g., GGUF on CPU), returns null.
 *   <li>If the contract's installed variant matches the hardware (FP16 on CUDA, FP32 on CPU),
 *       returns an optimal selection.
 *   <li>If the installed variant doesn't match (e.g., FP16 installed but running on CPU), returns a
 *       degraded selection with an explanation.
 *   <li>If the model file doesn't exist on disk, returns null (model not installed).
 * </ol>
 */
public final class VariantSelector {

  private VariantSelector() {}

  /**
   * Selects the variant for a model based on the install contract, hardware, and the
   * caller's GPU policy for this specific role.
   *
   * <p>Tempdoc 374 alpha.18 Bug I: {@code gpuAllowed} added so CPU-only roles
   * (citation-scorer is CPU-only by design) don't get promoted to CUDA when their
   * installed variant is CPU and the host has CUDA. Pre-alpha.18 the promotion was
   * unconditional — citation init then failed-loud at {@code assertCitationIsCpuOnly}
   * and the citation feature was dark on every GPU install. The {@code gpuAllowed}
   * parameter encodes the per-role policy: GPU-capable roles (embed/splade/ner/
   * reranker/bgeM3) pass {@code true}; CPU-only roles (citation) pass {@code false}.
   *
   * @param packageId the model package ID (e.g., "embedding")
   * @param contract the install contract (what's on disk)
   * @param hardware the current hardware profile
   * @param modelsDir root models directory
   * @param gpuAllowed whether GPU execution is permissible for this role; if
   *     {@code false}, a CPU-installed variant on a CUDA host stays on CPU instead
   *     of being promoted to CUDA EP
   * @return variant selection, or null if the model is skipped/not installed
   */
  public static VariantSelection select(
      String packageId,
      InstallContract contract,
      HardwareProfile hardware,
      Path modelsDir,
      boolean gpuAllowed) {
    if (contract == null) return null;

    InstallContract.InstalledModel model = contract.getModel(packageId);
    if (model == null || model.skipped()) return null;

    Path modelFile = contract.resolveModelPath(packageId, modelsDir);
    if (modelFile == null) return null;
    if (!Files.isRegularFile(modelFile)) {
      ExecutionProvider ep =
          hardware.cudaFunctional() && gpuAllowed
              ? ExecutionProvider.CUDA
              : ExecutionProvider.CPU;
      return VariantSelection.degraded(
          modelFile,
          model.precision(),
          ep,
          "Model file missing from disk: "
              + modelFile.getFileName()
              + " — re-run Install AI to repair");
    }

    // Determine if the installed variant matches the current hardware. When
    // gpuAllowed=false (CPU-only role), force currentEP to CPU regardless of
    // hardware so the promotion below cannot fire.
    boolean cudaAvailable = hardware.cudaFunctional() && gpuAllowed;
    ExecutionProvider currentEP = cudaAvailable ? ExecutionProvider.CUDA : ExecutionProvider.CPU;
    ExecutionProvider installedEP = model.targetEP();

    // Tempdoc 374 alpha.21 Bug P: LLAMA_SERVER variants are always optimal — chat
    // doesn't go through ORT EP selection. llama-server handles its own GPU layer
    // assignment via the -ngl flag. Pre-alpha.21 the catch-all branch reported
    // chat as `degraded("Unexpected variant/hardware combination")` because
    // LLAMA_SERVER doesn't match the ORT-EP-shaped CPU/CUDA branches below.
    // Round-11 evidence: chat actually worked at 41.73 tok/s on cuda12 with -ngl 99
    // but the status display reported it as degraded — misleading.
    if (installedEP == ExecutionProvider.LLAMA_SERVER) {
      return VariantSelection.optimal(modelFile, model.precision(), ExecutionProvider.LLAMA_SERVER);
    }

    if (installedEP == currentEP) {
      // Optimal: installed variant matches hardware
      return VariantSelection.optimal(modelFile, model.precision(), currentEP);
    }

    if (installedEP == ExecutionProvider.CPU && currentEP == ExecutionProvider.CUDA) {
      // FP32/INT8 installed but CUDA is available — suboptimal but functional.
      // GPU session manager will run FP32 on CUDA (works, just slower than FP16).
      return VariantSelection.degraded(
          modelFile,
          model.precision(),
          ExecutionProvider.CUDA,
          "FP16 variant not installed — running " + model.precision() + " on CUDA");
    }

    if (installedEP == ExecutionProvider.CUDA && currentEP == ExecutionProvider.CPU) {
      // FP16 installed but no CUDA — the pathological case (I1).
      // Model works but with severe Cast overhead.
      return VariantSelection.degraded(
          modelFile,
          model.precision(),
          ExecutionProvider.CPU,
          model.precision()
              + " model on CPU (significant performance overhead from runtime Cast).");
    }

    // Shouldn't reach here, but return degraded as fallback
    return VariantSelection.degraded(
        modelFile, model.precision(), currentEP, "Unexpected variant/hardware combination");
  }
}
