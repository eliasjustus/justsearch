/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Declares which files serve which role in a model directory.
 *
 * <p>Each model directory ships a {@code model_manifest.json} that maps execution providers to
 * concrete file names. Encoders read this manifest instead of hardcoding file name conventions,
 * making model directories self-describing and preventing variant-selection regressions (e.g., the
 * Q4 CPU regression where {@code model.onnx} was silently replaced with a GPU-only quantization).
 *
 * <p>Optional fields are normalized to defaults in the compact constructor — callers never see null
 * for {@code tokenizer}, {@code poolingConfig}, or {@code labelConfig}.
 *
 * <p>Tempdoc 710 Wave 2 Move 1: {@code capabilities} joins the file-routing fields as a second
 * section of the same per-model declaration (one manifest, not two) — {@link
 * ModelCapabilityResolver} reads it as the highest-priority source for model-intrinsic facts
 * (pooling mode, trained context length, embedding dimension, per-variant precision, prefixes)
 * before falling back to sentence-transformers ecosystem files, then legacy sidecars.
 *
 * @param cpu ONNX model file for CPU execution provider (required)
 * @param gpu ONNX model file for GPU/CUDA execution provider (null = fall back to cpu)
 * @param tokenizer tokenizer file name (default: {@code tokenizer.json})
 * @param poolingConfig pooling config file for embedding (default: {@code pooling_config.json})
 * @param labelConfig label mapping config file for NER (default: {@code config.json})
 * @param capabilities declared model-intrinsic facts (default: {@link Capabilities#EMPTY} — every
 *     fact falls through to the ecosystem-file / legacy-sidecar / WARN chain)
 */
public record ModelManifest(
    String cpu,
    String gpu,
    String tokenizer,
    String poolingConfig,
    String labelConfig,
    Capabilities capabilities) {

  static final String MANIFEST_FILE = "model_manifest.json";

  private static final Logger log = LoggerFactory.getLogger(ModelManifest.class);

  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .build();

  /**
   * Declared model-intrinsic facts (tempdoc 710 Wave 2 Move 1). Every field is optional — {@code
   * null} means "not declared here", not "declared as absent" (distinguishing null-vs-empty
   * matters for {@code documentPrefix}/{@code queryPrefix}: an unpopulated ST {@code prompts}
   * field is never proof of "no prefix", S-C.R). {@link ModelCapabilityResolver} treats a manifest
   * declaration as the highest-priority source, ahead of ecosystem files and legacy sidecars.
   *
   * @param poolingMode {@code "cls"} or {@code "mean"} (case-insensitive); null = undeclared
   * @param contextLength trained context length in tokens; null = undeclared
   * @param embeddingDimension declared output vector width; null = undeclared
   * @param cpuPrecision precision of the {@code cpu} file variant ({@code "fp32"|"fp16"|"int8"});
   *     null = undeclared (falls back to the legacy filename-substring heuristic, WARN)
   * @param gpuPrecision precision of the {@code gpu} file variant; same fallback as {@code
   *     cpuPrecision}
   * @param documentPrefix task-instruction prefix for document-side embedding; null = undeclared,
   *     {@code ""} = declared empty
   * @param queryPrefix task-instruction prefix for query-side embedding; same null-vs-empty rule
   * @param _comment free-text provenance note (e.g. "verified against HF Alibaba-NLP/
   *     gte-multilingual-base config.json, tempdoc 710 S-C.R") — not consumed programmatically
   */
  public record Capabilities(
      String poolingMode,
      Integer contextLength,
      Integer embeddingDimension,
      String cpuPrecision,
      String gpuPrecision,
      String documentPrefix,
      String queryPrefix,
      String _comment) {

    /** No facts declared — every field falls through to ecosystem-file / legacy-sidecar / WARN. */
    public static final Capabilities EMPTY =
        new Capabilities(null, null, null, null, null, null, null, null);
  }

  /** 5-arg convenience constructor for callers that don't declare capabilities (pre-Wave-2 call sites). */
  public ModelManifest(String cpu, String gpu, String tokenizer, String poolingConfig, String labelConfig) {
    this(cpu, gpu, tokenizer, poolingConfig, labelConfig, null);
  }

  /** Compact constructor — normalizes null optional fields to defaults. */
  public ModelManifest {
    // gpu is intentionally nullable (resolveModelPath falls back to cpu)
    if (tokenizer == null || tokenizer.isBlank()) tokenizer = "tokenizer.json";
    if (poolingConfig == null || poolingConfig.isBlank()) poolingConfig = "pooling_config.json";
    if (labelConfig == null || labelConfig.isBlank()) labelConfig = "config.json";
    if (capabilities == null) capabilities = Capabilities.EMPTY;
  }

  /**
   * Loads the manifest from {@code model_manifest.json} in the given directory.
   *
   * @param modelDir directory containing the manifest and model files
   * @return parsed manifest
   * @throws UncheckedIOException if the manifest is missing or unreadable
   * @throws IllegalStateException if the manifest is missing the required {@code cpu} field
   */
  public static ModelManifest load(Path modelDir) {
    Path manifestPath = modelDir.resolve(MANIFEST_FILE);
    if (!Files.exists(manifestPath)) {
      throw new UncheckedIOException(
          new IOException("Model manifest not found: " + manifestPath));
    }
    // Jackson 3.x readValue throws unchecked JacksonException (extends RuntimeException),
    // not checked IOException — no try/catch needed for parsing errors.
    ModelManifest manifest = JSON.readValue(manifestPath.toFile(), ModelManifest.class);
    if (manifest.cpu() == null || manifest.cpu().isBlank()) {
      throw new IllegalStateException(
          "Model manifest missing required 'cpu' field: " + manifestPath);
    }
    log.debug(
        "Loaded model manifest from {}: cpu={}, gpu={}", modelDir, manifest.cpu(), manifest.gpu());
    return manifest;
  }

  /**
   * Loads the manifest if present, otherwise returns a default using the pre-manifest naming
   * convention ({@code model.onnx} for CPU, {@code model_fp16.onnx} for GPU).
   *
   * <p>Use this for backward compatibility with external model directories that don't ship a
   * manifest. Falls back to the pre-manifest naming convention.
   *
   * @param modelDir directory containing model files
   * @return parsed manifest or conventional default
   */
  public static ModelManifest loadOrDefault(Path modelDir) {
    Path manifestPath = modelDir.resolve(MANIFEST_FILE);
    if (Files.exists(manifestPath)) {
      return load(modelDir);
    }
    log.debug("No model manifest in {} — using default convention", modelDir);
    return new ModelManifest("model.onnx", "model_fp16.onnx", null, null, null);
  }

  /**
   * Resolves the absolute model file path for the given execution provider.
   *
   * @param modelDir directory containing model files
   * @param gpuEnabled true to prefer the GPU model, false for CPU
   * @return absolute path to the model file
   */
  public Path resolveModelPath(Path modelDir, boolean gpuEnabled) {
    if (gpuEnabled && gpu != null && !gpu.isBlank()) {
      return modelDir.resolve(gpu);
    }
    return modelDir.resolve(cpu);
  }

  /**
   * Resolves to whichever declared variant actually exists on disk in
   * {@code modelDir}, GPU file preferred when both exist. Falls back to the
   * legacy {@code model.onnx} when neither declared variant is present so
   * callers without a manifest still hit the conventional name.
   *
   * <p>Decouples encoder/fingerprint code from "which variant did Install AI
   * download?" — historically each encoder hardcoded {@code model.onnx} and
   * silently broke when only the FP16 GPU variant landed on disk
   * (tempdoc 374 sandbox round 4 issue H).
   *
   * @param modelDir directory containing model files
   * @return absolute path to whichever declared variant exists, with legacy
   *     {@code model.onnx} as the final fallback
   */
  public Path resolveExistingModelFile(Path modelDir) {
    if (gpu != null && !gpu.isBlank()) {
      Path gpuPath = modelDir.resolve(gpu);
      if (Files.isRegularFile(gpuPath)) {
        return gpuPath;
      }
    }
    if (cpu != null && !cpu.isBlank()) {
      Path cpuPath = modelDir.resolve(cpu);
      if (Files.isRegularFile(cpuPath)) {
        return cpuPath;
      }
    }
    return modelDir.resolve("model.onnx");
  }
}
