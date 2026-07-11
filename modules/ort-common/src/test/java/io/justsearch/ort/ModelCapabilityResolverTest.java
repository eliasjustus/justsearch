/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.model.ModelPrecision;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ModelCapabilityResolver}. Tempdoc 710 Wave 2 Move 1.
 *
 * <p>Covers the source-priority chain per fact (manifest → ecosystem file → legacy sidecar → no
 * default) named in the tempdoc's S-C.D/S-C.R design, both sentence-transformers {@code
 * 1_Pooling/config.json} schema generations, the context-length cross-check's smaller-wins rule,
 * the null-vs-empty-string prefix distinction, and strict-mode fail-fast.
 *
 * <p>None of these tests touch a real ONNX file — the boot-time graph probes ({@link
 * ModelCapabilityResolver}'s dimension probe and precision sanity check) guard on {@code
 * Files.isRegularFile} before opening an ORT session, so a manifest-declared {@code cpu}/{@code
 * gpu} file name that doesn't exist on disk degrades to "no signal" rather than throwing.
 */
@DisplayName("ModelCapabilityResolver")
class ModelCapabilityResolverTest {

  @Test
  @DisplayName("manifest-declared capabilities take priority over every other source")
  void manifestDeclaredCapabilitiesTakePriority(@TempDir Path modelDir) throws IOException {
    Files.writeString(
        modelDir.resolve("config.json"),
        """
        { "id2label": { "0": "O", "1": "B-PER" } }
        """);
    ModelManifest manifest =
        new ModelManifest(
            "model.onnx",
            "model_fp16.onnx",
            "tokenizer.json",
            "pooling_config.json",
            "config.json",
            new ModelManifest.Capabilities(
                "cls", 8192, 768, "fp32", "fp16", "", "", "test-declared"));

    ModelCapabilities caps = ModelCapabilityResolver.resolve("embedding", modelDir, manifest, false);

    assertEquals(ModelCapabilities.PoolingMode.CLS, caps.poolingMode());
    assertEquals(8192, caps.trainedContextLength());
    assertEquals(768, caps.embeddingDimension());
    assertEquals(ModelPrecision.FP32, caps.cpuPrecision());
    assertEquals(ModelPrecision.FP16, caps.gpuPrecision());
    assertEquals("", caps.documentPrefix());
    assertEquals("", caps.queryPrefix());
    assertEquals(2, caps.labelMapping().size());
    assertEquals("B-PER", caps.labelMapping().get("1"));
    assertTrue(caps.warnings().isEmpty(), "fully declared manifest should produce zero warnings: " + caps.warnings());
  }

  @Test
  @DisplayName("sentence-transformers string-schema 1_Pooling/config.json used when manifest silent")
  void stEcosystemStringSchemaPoolingMode(@TempDir Path modelDir) throws IOException {
    Files.createDirectories(modelDir.resolve("1_Pooling"));
    Files.writeString(modelDir.resolve("1_Pooling").resolve("config.json"), """
        { "pooling_mode": "cls" }
        """);
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);

    ModelCapabilities caps = ModelCapabilityResolver.resolve("embedding", modelDir, manifest, false);

    assertEquals(ModelCapabilities.PoolingMode.CLS, caps.poolingMode());
  }

  @Test
  @DisplayName("sentence-transformers older boolean-flag 1_Pooling/config.json schema also supported")
  void stEcosystemBooleanSchemaPoolingMode(@TempDir Path modelDir) throws IOException {
    Files.createDirectories(modelDir.resolve("1_Pooling"));
    Files.writeString(
        modelDir.resolve("1_Pooling").resolve("config.json"),
        """
        { "pooling_mode_cls_token": true, "pooling_mode_mean_tokens": false }
        """);
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);

    ModelCapabilities caps = ModelCapabilityResolver.resolve("embedding", modelDir, manifest, false);

    assertEquals(ModelCapabilities.PoolingMode.CLS, caps.poolingMode());
  }

  @Test
  @DisplayName("config.json hidden_size used for dimension when manifest/ST files silent")
  void configJsonHiddenSizeUsedForDimension(@TempDir Path modelDir) throws IOException {
    Files.writeString(modelDir.resolve("config.json"), """
        { "hidden_size": 1024 }
        """);
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);

    ModelCapabilities caps = ModelCapabilityResolver.resolve("embedding", modelDir, manifest, false);

    assertEquals(1024, caps.embeddingDimension());
  }

  @Test
  @DisplayName("context length: sentence_bert_config.json and config.json agreeing needs no warning")
  void contextLengthAgreementNoWarning(@TempDir Path modelDir) throws IOException {
    Files.writeString(modelDir.resolve("sentence_bert_config.json"), """
        { "max_seq_length": 8192 }
        """);
    Files.writeString(modelDir.resolve("config.json"), """
        { "max_position_embeddings": 8192 }
        """);
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);

    ModelCapabilities caps = ModelCapabilityResolver.resolve("embedding", modelDir, manifest, false);

    assertEquals(8192, caps.trainedContextLength());
    assertTrue(
        caps.warnings().stream().noneMatch(w -> w.contains("disagreement")),
        "agreeing sources should not warn: " + caps.warnings());
  }

  @Test
  @DisplayName("context length disagreement prefers the smaller value and warns")
  void contextLengthDisagreementPrefersSmaller(@TempDir Path modelDir) throws IOException {
    Files.writeString(modelDir.resolve("sentence_bert_config.json"), """
        { "max_seq_length": 8192 }
        """);
    Files.writeString(modelDir.resolve("config.json"), """
        { "max_position_embeddings": 4096 }
        """);
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);

    ModelCapabilities caps = ModelCapabilityResolver.resolve("embedding", modelDir, manifest, false);

    assertEquals(4096, caps.trainedContextLength(), "should prefer the smaller (safer) value");
    assertTrue(
        caps.warnings().stream().anyMatch(w -> w.contains("disagreement")),
        "disagreement should be recorded: " + caps.warnings());
  }

  @Test
  @DisplayName("tokenizer_config.json model_max_length is never trusted for context length")
  void tokenizerConfigModelMaxLengthNeverTrusted(@TempDir Path modelDir) throws IOException {
    // Verified-wrong-in-practice case from S-C.R: gte-multilingual-base's tokenizer_config.json
    // says 32768 vs the real trained 8192. Absent any authoritative source, context length must
    // stay unknown (0) rather than silently picking up this figure.
    Files.writeString(modelDir.resolve("tokenizer_config.json"), """
        { "model_max_length": 32768 }
        """);
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);

    ModelCapabilities caps = ModelCapabilityResolver.resolve("embedding", modelDir, manifest, false);

    assertEquals(0, caps.trainedContextLength());
  }

  @Test
  @DisplayName("legacy pooling_config.json / prefix_config.json sidecars used as last-resort fallback")
  void legacySidecarFallback(@TempDir Path modelDir) throws IOException {
    Files.writeString(modelDir.resolve("pooling_config.json"), """
        {"pooling_mode": "mean"}
        """);
    Files.writeString(
        modelDir.resolve("prefix_config.json"),
        """
        {"document_prefix": "search_document: ", "query_prefix": "search_query: "}
        """);
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);

    ModelCapabilities caps = ModelCapabilityResolver.resolve("embedding", modelDir, manifest, false);

    assertEquals(ModelCapabilities.PoolingMode.MEAN, caps.poolingMode());
    assertEquals("search_document: ", caps.documentPrefix());
    assertEquals("search_query: ", caps.queryPrefix());
  }

  @Test
  @DisplayName("declared empty prefix is distinct from undeclared (null) prefix")
  void declaredEmptyPrefixDistinctFromUndeclared(@TempDir Path modelDir) {
    ModelManifest manifest =
        new ModelManifest(
            "model.onnx",
            null,
            null,
            null,
            null,
            new ModelManifest.Capabilities(null, null, null, null, null, "", null, null));

    ModelCapabilities caps = ModelCapabilityResolver.resolve("embedding", modelDir, manifest, false);

    assertEquals("", caps.documentPrefix(), "declared empty string must be preserved, not treated as absent");
    assertNull(caps.queryPrefix(), "genuinely undeclared prefix must stay null, not default to \"\"");
    assertTrue(
        caps.warnings().stream().anyMatch(w -> w.contains("prefix")),
        "an undeclared prefix should still surface a warning even though the other is declared");
  }

  @Test
  @DisplayName("missing everything: every fact falls through to WARN + UNKNOWN/0/null, no crash")
  void missingEverythingWarnsAndFallsBack(@TempDir Path modelDir) throws IOException {
    // No manifest file at all — loadOrDefault synthesizes the legacy convention with empty
    // capabilities; no ecosystem or legacy sidecar files present either.
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);

    ModelCapabilities caps = ModelCapabilityResolver.resolve("embedding", modelDir, manifest, false);

    assertEquals(ModelCapabilities.PoolingMode.UNKNOWN, caps.poolingMode());
    assertEquals(0, caps.trainedContextLength());
    assertEquals(0, caps.embeddingDimension());
    assertNull(caps.documentPrefix());
    assertNull(caps.queryPrefix());
    assertTrue(caps.labelMapping().isEmpty());
    assertFalse(caps.warnings().isEmpty(), "every undeclared fact should be named in warnings");
  }

  @Test
  @DisplayName("precision falls back to the legacy filename-substring heuristic with a warning")
  void precisionLegacyFilenameFallback(@TempDir Path modelDir) throws IOException {
    ModelManifest manifest =
        new ModelManifest("model_int8.onnx", "model_fp16.onnx", null, null, null);

    ModelCapabilities caps = ModelCapabilityResolver.resolve("ner", modelDir, manifest, false);

    assertEquals(ModelPrecision.INT8, caps.cpuPrecision());
    assertEquals(ModelPrecision.FP16, caps.gpuPrecision());
    assertTrue(
        caps.warnings().stream().anyMatch(w -> w.contains("precision") && w.contains("filename")),
        "legacy filename fallback should be named in a warning: " + caps.warnings());
  }

  @Test
  @DisplayName("strict mode throws when any capability fact is undeclared/ambiguous")
  void strictModeThrowsOnAnyWarning(@TempDir Path modelDir) {
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> ModelCapabilityResolver.resolve("embedding", modelDir, manifest, true));
    assertTrue(ex.getMessage().contains("capability_contract_strict"));
  }

  @Test
  @DisplayName("strict mode does not throw when every fact is fully declared")
  void strictModeDoesNotThrowWhenFullyDeclared(@TempDir Path modelDir) throws IOException {
    Files.writeString(modelDir.resolve("labels.json"), """
        { "id2label": { "0": "O" } }
        """);
    ModelManifest manifest =
        new ModelManifest(
            "model.onnx",
            "model_fp16.onnx",
            "tokenizer.json",
            "pooling_config.json",
            "labels.json",
            new ModelManifest.Capabilities("mean", 512, 384, "fp32", "fp16", "", "", null));

    ModelCapabilities caps = ModelCapabilityResolver.resolve("ner", modelDir, manifest, true);

    assertTrue(caps.warnings().isEmpty());
  }
}
