/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import io.justsearch.configuration.model.ModelPrecision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves a {@link ModelCapabilities} value for a model directory. Tempdoc 710 Wave 2 Move 1:
 * the single production entry point for model-intrinsic facts — no encoder parses sidecar JSON
 * itself anymore.
 *
 * <p>Per-fact source priority (S-C.R): manifest {@code capabilities} field → ecosystem files where
 * authoritative → boot-time graph probe (dimension only; precision is sanity-checked, never
 * sourced, against I/O dtype — S-C.R: "no ecosystem field is authoritative for an exported ONNX
 * file's precision") → legacy sidecar (deprecated fallback generation) → no default. Every gap
 * that falls through to a fallback (or bottoms out with nothing) is recorded in {@link
 * ModelCapabilities#warnings()} and logged at WARN — mirroring {@code
 * InferenceCompositionRoot.resolveVariant}'s degraded-selection WARN (tempdoc 691 B-5). Under
 * {@code justsearch.models.capability_contract_strict} (default {@code false} until 657 ships
 * manifests in packs), a non-empty warning list is a hard failure for that encoder lane instead —
 * the caller's existing {@code Optional.empty()} composition pattern absorbs it per-lane.
 *
 * <p>Ecosystem readers (S-C.R R-1, verified against the real Alibaba-NLP/gte-multilingual-base HF
 * repo): sentence-transformers {@code 1_Pooling/config.json} — supports BOTH schema generations
 * (older boolean flags {@code pooling_mode_cls_token}/{@code pooling_mode_mean_tokens}, newer
 * string {@code pooling_mode}); {@code sentence_bert_config.json} {@code max_seq_length}
 * cross-checked against {@code config.json} {@code max_position_embeddings} (disagreement → WARN,
 * prefer the smaller value — safest); {@code config.json} {@code hidden_size} for dimension;
 * {@code config_sentence_transformers.json} {@code prompts} for prefixes, IF present (absence is
 * never treated as "no prefix" — verified 404 in practice for every model checked so far).
 * {@code tokenizer_config.json model_max_length} is NEVER read for context length — verified wrong
 * for gte-multilingual-base (32768 vs the real trained 8192).
 */
public final class ModelCapabilityResolver {

  private static final Logger log = LoggerFactory.getLogger(ModelCapabilityResolver.class);

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private ModelCapabilityResolver() {}

  /**
   * Resolves capabilities for {@code modelDir}. Logs every degraded/undeclared fact at WARN.
   *
   * @param packageId short encoder identifier for log lines (e.g. {@code "embedding"}, {@code
   *     "ner"}) — mirrors {@code InferenceCompositionRoot.resolveVariant}'s {@code packageId}
   * @param modelDir directory containing the manifest, model files, and any ecosystem/legacy
   *     sidecar files
   * @param manifest the already-loaded manifest for {@code modelDir}
   * @param strict when {@code true}, a non-empty warning list throws {@link IllegalStateException}
   *     instead of returning a degraded {@link ModelCapabilities} — {@code
   *     justsearch.models.capability_contract_strict}
   * @throws IllegalStateException if {@code strict} and any fact was undeclared/ambiguous
   */
  public static ModelCapabilities resolve(
      String packageId, Path modelDir, ModelManifest manifest, boolean strict) {
    List<String> warnings = new ArrayList<>();

    ModelCapabilities.PoolingMode poolingMode = resolvePoolingMode(modelDir, manifest, warnings);
    int contextLength = resolveContextLength(modelDir, manifest, warnings);
    int dimension = resolveDimension(modelDir, manifest, warnings);
    if (dimension <= 0) {
      dimension = probeStaticEmbeddingDimension(modelDir, manifest, warnings);
    }
    ModelPrecision cpuPrecision =
        resolvePrecision(manifest.capabilities().cpuPrecision(), manifest.cpu(), "cpu", warnings);
    ModelPrecision gpuPrecision =
        resolvePrecision(manifest.capabilities().gpuPrecision(), manifest.gpu(), "gpu", warnings);
    String[] prefixes = resolvePrefixes(modelDir, manifest, warnings);
    Map<String, String> labelMapping = resolveLabelMapping(modelDir, manifest, warnings);

    for (String warning : warnings) {
      log.warn("{}: model capability degraded — {}", packageId, warning);
    }
    if (strict && !warnings.isEmpty()) {
      throw new IllegalStateException(
          packageId
              + ": capability_contract_strict mode — "
              + warnings.size()
              + " undeclared/ambiguous capability fact(s) for "
              + modelDir
              + ": "
              + warnings);
    }

    return new ModelCapabilities(
        poolingMode,
        contextLength,
        dimension,
        cpuPrecision,
        gpuPrecision,
        prefixes[0],
        prefixes[1],
        labelMapping,
        List.copyOf(warnings));
  }

  // ---------------------------------------------------------------------------
  // Pooling mode
  // ---------------------------------------------------------------------------

  private static ModelCapabilities.PoolingMode resolvePoolingMode(
      Path modelDir, ModelManifest manifest, List<String> warnings) {
    String declared = manifest.capabilities().poolingMode();
    if (declared != null && !declared.isBlank()) {
      ModelCapabilities.PoolingMode parsed = parsePoolingMode(declared);
      if (parsed != ModelCapabilities.PoolingMode.UNKNOWN) {
        return parsed;
      }
      warnings.add("manifest capabilities.pooling_mode='" + declared + "' unrecognized (expected cls|mean)");
    }

    // Sentence-transformers ecosystem: 1_Pooling/config.json, both schema generations.
    Path stPooling = modelDir.resolve("1_Pooling").resolve("config.json");
    if (Files.exists(stPooling)) {
      try {
        JsonNode root = readJson(stPooling);
        JsonNode modeNode = root.get("pooling_mode");
        if (modeNode != null && modeNode.isTextual()) {
          ModelCapabilities.PoolingMode parsed = parsePoolingMode(modeNode.asText());
          if (parsed != ModelCapabilities.PoolingMode.UNKNOWN) {
            return parsed;
          }
        }
        if (asBoolean(root.get("pooling_mode_cls_token"))) {
          return ModelCapabilities.PoolingMode.CLS;
        }
        if (asBoolean(root.get("pooling_mode_mean_tokens"))) {
          return ModelCapabilities.PoolingMode.MEAN;
        }
      } catch (Exception e) {
        warnings.add("failed to read 1_Pooling/config.json: " + e.getMessage());
      }
    }

    // Legacy sidecar — deprecated generation, kept as a fallback during the migration window
    // (tombstone: removed once packs ship manifests, tempdoc 657).
    Path legacy = modelDir.resolve(manifest.poolingConfig());
    if (Files.exists(legacy)) {
      try {
        String content = Files.readString(legacy);
        if (content.contains("\"cls\"")) {
          return ModelCapabilities.PoolingMode.CLS;
        }
        if (content.contains("\"mean\"")) {
          return ModelCapabilities.PoolingMode.MEAN;
        }
      } catch (Exception e) {
        warnings.add("failed to read legacy " + manifest.poolingConfig() + ": " + e.getMessage());
      }
    }

    warnings.add(
        "pooling mode undeclared (no manifest field, 1_Pooling/config.json, or legacy "
            + manifest.poolingConfig()
            + ")");
    return ModelCapabilities.PoolingMode.UNKNOWN;
  }

  private static ModelCapabilities.PoolingMode parsePoolingMode(String value) {
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "cls" -> ModelCapabilities.PoolingMode.CLS;
      case "mean" -> ModelCapabilities.PoolingMode.MEAN;
      default -> ModelCapabilities.PoolingMode.UNKNOWN;
    };
  }

  // ---------------------------------------------------------------------------
  // Trained context length
  // ---------------------------------------------------------------------------

  private static int resolveContextLength(Path modelDir, ModelManifest manifest, List<String> warnings) {
    Integer declared = manifest.capabilities().contextLength();
    if (declared != null && declared > 0) {
      return declared;
    }

    Integer stSeqLen = readIntField(modelDir.resolve("sentence_bert_config.json"), "max_seq_length", warnings);
    Integer configMaxPos = readIntField(modelDir.resolve("config.json"), "max_position_embeddings", warnings);

    if (stSeqLen != null && configMaxPos != null) {
      if (!stSeqLen.equals(configMaxPos)) {
        int chosen = Math.min(stSeqLen, configMaxPos);
        warnings.add(
            "context length disagreement: sentence_bert_config.json max_seq_length="
                + stSeqLen
                + " vs config.json max_position_embeddings="
                + configMaxPos
                + " — using the smaller value ("
                + chosen
                + ")");
        return chosen;
      }
      return stSeqLen;
    }
    if (stSeqLen != null) {
      return stSeqLen;
    }
    if (configMaxPos != null) {
      return configMaxPos;
    }

    warnings.add(
        "trained context length undeclared (no manifest field, sentence_bert_config.json"
            + " max_seq_length, or config.json max_position_embeddings — tokenizer_config.json"
            + " model_max_length is deliberately never trusted, S-C.R)");
    return 0;
  }

  // ---------------------------------------------------------------------------
  // Embedding dimension
  // ---------------------------------------------------------------------------

  private static int resolveDimension(Path modelDir, ModelManifest manifest, List<String> warnings) {
    Integer declared = manifest.capabilities().embeddingDimension();
    if (declared != null && declared > 0) {
      return declared;
    }
    Integer hiddenSize = readIntField(modelDir.resolve("config.json"), "hidden_size", warnings);
    if (hiddenSize != null && hiddenSize > 0) {
      return hiddenSize;
    }
    return 0;
  }

  /**
   * Boot-time graph probe (no inference run) for the trailing output-tensor axis, used only when
   * neither the manifest nor {@code config.json} declares the dimension. Dynamic shapes (batch and
   * sequence axes are typically {@code -1}) yield no signal on most outputs; the trailing axis is
   * typically the one statically-sized dimension for an embedding output. Best-effort — any probe
   * failure degrades to the reactive first-inference detection already in {@code
   * OnnxEmbeddingEncoder}, not a hard error.
   */
  private static int probeStaticEmbeddingDimension(
      Path modelDir, ModelManifest manifest, List<String> warnings) {
    try {
      Path modelFile = manifest.resolveExistingModelFile(modelDir);
      if (!Files.isRegularFile(modelFile)) {
        return 0;
      }
      OrtEnvironment env = OrtEnvironment.getEnvironment();
      OrtSessionAssembler.ProbedNames names = OrtSessionAssembler.probeModelNames(env, modelFile);
      for (String outputName : names.outputs()) {
        Optional<OrtSessionAssembler.ProbedTensorInfo> info =
            OrtSessionAssembler.probeOutputTensorInfo(env, modelFile, outputName);
        if (info.isEmpty()) {
          continue;
        }
        long[] shape = info.get().shape();
        if (shape.length > 0 && shape[shape.length - 1] > 0) {
          return (int) shape[shape.length - 1];
        }
      }
    } catch (OrtException e) {
      warnings.add("boot-probe for embedding dimension failed: " + e.getMessage());
      return 0;
    }
    warnings.add(
        "embedding dimension undeclared (no manifest field, config.json hidden_size, or"
            + " statically-shaped graph output) — falling back to reactive first-inference"
            + " detection");
    return 0;
  }

  // ---------------------------------------------------------------------------
  // Precision (per-variant; sanity-checked, never sourced, from I/O dtype)
  // ---------------------------------------------------------------------------

  private static ModelPrecision resolvePrecision(
      String declared, String variantFile, String label, List<String> warnings) {
    if (declared != null && !declared.isBlank()) {
      ModelPrecision parsed = parsePrecision(declared);
      if (parsed != null) {
        return parsed;
      }
      warnings.add("manifest capabilities." + label + "_precision='" + declared + "' unrecognized");
    }
    if (variantFile == null || variantFile.isBlank()) {
      return null;
    }
    // Legacy fallback: filename-substring heuristic (tempdoc 710 orphan #4 — narrowed to a
    // fallback with a WARN, not the sole mechanism it was pre-Wave-2).
    String lower = variantFile.toLowerCase(Locale.ROOT);
    ModelPrecision guessed;
    if (lower.contains("int8")) {
      guessed = ModelPrecision.INT8;
    } else if (lower.contains("fp16")) {
      guessed = ModelPrecision.FP16;
    } else {
      guessed = ModelPrecision.FP32;
    }
    warnings.add(
        label
            + " precision undeclared for '"
            + variantFile
            + "' — using legacy filename-substring heuristic: "
            + guessed);
    return guessed;
  }

  private static ModelPrecision parsePrecision(String value) {
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "fp32" -> ModelPrecision.FP32;
      case "fp16" -> ModelPrecision.FP16;
      case "int8" -> ModelPrecision.INT8;
      case "gguf" -> ModelPrecision.GGUF;
      default -> null;
    };
  }

  // ---------------------------------------------------------------------------
  // Task-instruction prefixes
  // ---------------------------------------------------------------------------

  private static String[] resolvePrefixes(Path modelDir, ModelManifest manifest, List<String> warnings) {
    ModelManifest.Capabilities caps = manifest.capabilities();
    String doc = caps.documentPrefix();
    String query = caps.queryPrefix();

    // Sentence-transformers ecosystem: config_sentence_transformers.json `prompts`, IF present
    // (S-C.R: verified unpopulated in practice for gte-multilingual-base AND
    // multilingual-e5-large — absence is never "no prefix").
    if (doc == null || query == null) {
      Path stPrompts = modelDir.resolve("config_sentence_transformers.json");
      if (Files.exists(stPrompts)) {
        try {
          JsonNode root = readJson(stPrompts);
          JsonNode prompts = root.get("prompts");
          if (prompts != null && prompts.isObject()) {
            if (doc == null) {
              JsonNode docNode = prompts.get("document");
              if (docNode != null && docNode.isTextual()) {
                doc = docNode.asText();
              }
            }
            if (query == null) {
              JsonNode queryNode = prompts.get("query");
              if (queryNode != null && queryNode.isTextual()) {
                query = queryNode.asText();
              }
            }
          }
        } catch (Exception e) {
          warnings.add("failed to read config_sentence_transformers.json: " + e.getMessage());
        }
      }
    }

    // Legacy sidecar — deprecated generation.
    if (doc == null || query == null) {
      Path legacy = modelDir.resolve("prefix_config.json");
      if (Files.exists(legacy)) {
        try {
          JsonNode root = readJson(legacy);
          if (doc == null) {
            JsonNode d = root.get("document_prefix");
            if (d != null && d.isTextual()) {
              doc = d.asText();
            }
          }
          if (query == null) {
            JsonNode q = root.get("query_prefix");
            if (q != null && q.isTextual()) {
              query = q.asText();
            }
          }
        } catch (Exception e) {
          warnings.add("failed to read legacy prefix_config.json: " + e.getMessage());
        }
      }
    }

    if (doc == null || query == null) {
      warnings.add(
          "prefix(es) undeclared (no manifest field, config_sentence_transformers.json prompts,"
              + " or legacy prefix_config.json) — caller applies its own documented fallback");
    }
    return new String[] {doc, query};
  }

  // ---------------------------------------------------------------------------
  // Label mapping (NER)
  // ---------------------------------------------------------------------------

  private static Map<String, String> resolveLabelMapping(
      Path modelDir, ModelManifest manifest, List<String> warnings) {
    Path configFile = modelDir.resolve(manifest.labelConfig());
    if (!Files.exists(configFile)) {
      warnings.add(
          "label config '" + manifest.labelConfig() + "' not found in " + modelDir);
      return Map.of();
    }
    try {
      JsonNode root = readJson(configFile);
      JsonNode id2label = root.get("id2label");
      if (id2label == null || !id2label.isObject()) {
        warnings.add("no id2label object in " + manifest.labelConfig());
        return Map.of();
      }
      Map<String, String> mapping = new LinkedHashMap<>();
      for (var entry : id2label.properties()) {
        mapping.put(entry.getKey(), entry.getValue().asText());
      }
      return mapping;
    } catch (Exception e) {
      warnings.add("failed to read " + manifest.labelConfig() + ": " + e.getMessage());
      return Map.of();
    }
  }

  // ---------------------------------------------------------------------------
  // JSON helpers
  // ---------------------------------------------------------------------------

  private static JsonNode readJson(Path file) {
    return JSON.readTree(file.toFile());
  }

  private static boolean asBoolean(JsonNode node) {
    return node != null && node.isBoolean() && node.asBoolean();
  }

  private static Integer readIntField(Path file, String field, List<String> warnings) {
    if (!Files.exists(file)) {
      return null;
    }
    try {
      JsonNode root = readJson(file);
      JsonNode node = root.get(field);
      if (node != null && node.isNumber()) {
        return node.asInt();
      }
      return null;
    } catch (Exception e) {
      warnings.add("failed to read " + file.getFileName() + ": " + e.getMessage());
      return null;
    }
  }
}
