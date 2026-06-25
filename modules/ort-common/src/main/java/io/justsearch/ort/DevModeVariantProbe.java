/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Probes the filesystem for a model variant when {@link
 * io.justsearch.configuration.model.InstallContract} is absent — the dev-mode branch that was
 * previously inlined into {@code KnowledgeServer.resolveVariant}.
 *
 * <p>Tempdoc 397 §14.26 T2-A1: centralising this logic here completes §7.6's intent
 * (<em>"dev mode does not require a second path — it requires the resolver to have a fallback"</em>).
 * After T2-A1, every {@link VariantSelection} in the JVM comes from one of two sibling
 * resolver paths — contract-driven via
 * {@link io.justsearch.configuration.model.VariantSelector#select} or filesystem-probed here.
 * The composition root / assembler never distinguishes the two.
 *
 * <p>Probe semantics (ported verbatim from {@code KnowledgeServer.resolveVariant:1071-1104}):
 *
 * <ul>
 *   <li>Returns {@code null} if {@code modelDir} does not exist or is not a directory.
 *   <li>Loads {@link ModelManifest} to resolve CPU + GPU paths (falling back to the convention
 *       {@code model.onnx} / {@code model_fp16.onnx} when no manifest present).
 *   <li>Accepts an {@code .optimized} sidecar in place of the bare file (the ORT
 *       graph-optimisation cache can exist without the original when a build was incremental).
 *   <li>Prefers the GPU file when {@code gpuEnabled} and it exists; falls back to CPU file
 *       with {@link ExecutionProvider#CUDA} when only CPU file is present and GPU is enabled
 *       (the {@link NativeSessionHandle} will attempt a GPU session from the CPU file and
 *       retry-to-CPU on failure); otherwise CPU file with {@link ExecutionProvider#CPU}.
 *   <li>Precision detection uses a substring check for {@code "fp16"} in the filename.
 * </ul>
 */
public final class DevModeVariantProbe {

  private DevModeVariantProbe() {}

  /**
   * Probes {@code modelDir} for a loadable ONNX model file; returns a {@link VariantSelection} or
   * {@code null} if nothing loadable is present.
   *
   * @param modelDir the per-encoder model directory (e.g., {@code models/onnx/gte-multilingual-base})
   * @param gpuEnabled whether the caller wants a GPU variant if one is available
   */
  public static VariantSelection probe(Path modelDir, boolean gpuEnabled) {
    if (modelDir == null || !Files.isDirectory(modelDir)) {
      return null;
    }
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
    Path cpuModelFile = manifest.resolveModelPath(modelDir, false);
    Path gpuModelFile = manifest.resolveModelPath(modelDir, true);

    boolean gpuFileExists = gpuEnabled && Files.exists(gpuModelFile);
    boolean cpuFileExists = Files.exists(cpuModelFile);

    // Also check for optimized cache (model.onnx may not exist but model.onnx.optimized does).
    if (!cpuFileExists) {
      cpuFileExists = Files.exists(Path.of(cpuModelFile + ".optimized"));
    }
    if (!gpuFileExists && gpuEnabled) {
      gpuFileExists = Files.exists(Path.of(gpuModelFile + ".optimized"));
    }

    if (gpuFileExists) {
      boolean isFp16 = gpuModelFile.getFileName().toString().contains("fp16");
      return VariantSelection.optimal(
          gpuModelFile,
          isFp16 ? ModelPrecision.FP16 : ModelPrecision.FP32,
          ExecutionProvider.CUDA);
    }
    if (cpuFileExists) {
      boolean isFp16 = cpuModelFile.getFileName().toString().contains("fp16");
      // No dedicated GPU model file — use CPU model with GPU if enabled.
      // NativeSessionHandle attempts a GPU session from the CPU model file and retries to CPU
      // on failure.
      return VariantSelection.optimal(
          cpuModelFile,
          isFp16 ? ModelPrecision.FP16 : ModelPrecision.FP32,
          gpuEnabled ? ExecutionProvider.CUDA : ExecutionProvider.CPU);
    }
    return null;
  }
}
