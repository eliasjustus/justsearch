/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import java.util.List;

/**
 * A complete model package in the registry — one logical model with all its variants and supporting
 * files.
 *
 * <p>Example: the "embedding" package contains an FP32 variant (CPU), an FP16 variant (CUDA), plus
 * a tokenizer and pooling config as supporting files.
 *
 * @param id unique model identifier (e.g., "embedding", "splade", "reranker", "ner",
 *     "citation-scorer", "chat")
 * @param label human-readable label for UI (e.g., "Embedding model")
 * @param description short description for UI (e.g., "Vector embeddings for semantic search")
 * @param targetDir directory under {@code modelsDir} (or under {@code homeDir/installRoot} when
 *     {@code installRoot} is set) where files are placed (e.g., "onnx/gte-multilingual-base")
 * @param variants model file variants (one per precision/EP combination)
 * @param supportingFiles non-model files (tokenizer, config, etc.) — always downloaded
 * @param minVramBytes minimum VRAM to include this package (0 = always include). Used for GGUF
 *     models that require GPU VRAM to be usable.
 * @param termsUrl URL to model license/terms page (nullable)
 * @param installRoot when non-null, files install relative to {@code homeDir.resolve(installRoot)}
 *     instead of {@code modelsDir}. Tempdoc 374 alpha.15 fix B — used by the {@code cuda-runtime}
 *     package which lands DLLs at {@code native-bin/llama-server/variants/cuda12} alongside the
 *     bundled cuda12 llama-server variant, NOT under {@code models/}.
 * @param license SPDX license identifier for this package's artifacts (e.g. {@code "Apache-2.0"},
 *     {@code "AFL-3.0"}, {@code "LicenseRef-NVIDIA-CUDA-EULA"}). Single-sources model attribution so
 *     the generated NOTICE projects from the registry rather than a hand-maintained fork (tempdoc 632).
 *     Nullable for backward-compatibility (registries predating the field).
 */
public record ModelPackage(
    String id,
    String label,
    String description,
    String targetDir,
    List<ModelVariant> variants,
    List<SupportingFile> supportingFiles,
    long minVramBytes,
    String termsUrl,
    String installRoot,
    String license) {

  /** Compact constructor — normalize nulls to empty lists. */
  public ModelPackage {
    if (variants == null) variants = List.of();
    if (supportingFiles == null) supportingFiles = List.of();
  }

  /** Backwards-compat constructor — no installRoot (existing default behavior under modelsDir). */
  public ModelPackage(
      String id,
      String label,
      String description,
      String targetDir,
      List<ModelVariant> variants,
      List<SupportingFile> supportingFiles,
      long minVramBytes,
      String termsUrl) {
    this(id, label, description, targetDir, variants, supportingFiles, minVramBytes, termsUrl, null, null);
  }

  /** Backwards-compat constructor — installRoot but no license (predates tempdoc 632's license field). */
  public ModelPackage(
      String id,
      String label,
      String description,
      String targetDir,
      List<ModelVariant> variants,
      List<SupportingFile> supportingFiles,
      long minVramBytes,
      String termsUrl,
      String installRoot) {
    this(
        id, label, description, targetDir, variants, supportingFiles, minVramBytes, termsUrl,
        installRoot, null);
  }

  /** Returns true if this package requires a minimum VRAM threshold to be useful. */
  public boolean hasVramRequirement() {
    return minVramBytes > 0;
  }

  /**
   * Selects the best variant for the given download profile.
   *
   * <p>If the profile uses CUDA, prefers a CUDA-targeted variant (FP16). Otherwise, prefers a
   * CPU-targeted variant (FP32 or INT8). If no variant matches the preferred EP, falls back to the
   * first available variant. This ensures CPU-only models (e.g., citation-scorer with only an INT8
   * CPU variant) are included in GPU download profiles.
   *
   * @return the selected variant, or null only if the package has no variants at all
   */
  public ModelVariant selectVariant(DownloadProfile profile) {
    ExecutionProvider targetEP =
        profile.usesCuda() ? ExecutionProvider.CUDA : ExecutionProvider.CPU;
    return variants.stream()
        .filter(v -> v.targetEP() == targetEP)
        .findFirst()
        .orElse(variants.isEmpty() ? null : variants.get(0));
  }
}
