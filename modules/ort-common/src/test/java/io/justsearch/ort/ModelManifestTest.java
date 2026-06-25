package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelManifestTest {

  @TempDir Path tempDir;

  @Test
  void loadParsesAllFields() throws Exception {
    Files.writeString(
        tempDir.resolve(ModelManifest.MANIFEST_FILE),
        """
        {
          "cpu": "model_int8.onnx",
          "gpu": "model_fp16.onnx",
          "tokenizer": "tok.json",
          "pooling_config": "pool.json",
          "label_config": "labels.json"
        }
        """);

    ModelManifest m = ModelManifest.load(tempDir);

    assertEquals("model_int8.onnx", m.cpu());
    assertEquals("model_fp16.onnx", m.gpu());
    assertEquals("tok.json", m.tokenizer());
    assertEquals("pool.json", m.poolingConfig());
    assertEquals("labels.json", m.labelConfig());
  }

  @Test
  void loadWithMissingGpuField() throws Exception {
    Files.writeString(
        tempDir.resolve(ModelManifest.MANIFEST_FILE),
        """
        { "cpu": "model.onnx" }
        """);

    ModelManifest m = ModelManifest.load(tempDir);

    assertEquals("model.onnx", m.cpu());
    assertEquals(null, m.gpu());
  }

  @Test
  void loadThrowsWhenManifestMissing() {
    assertThrows(UncheckedIOException.class, () -> ModelManifest.load(tempDir));
  }

  @Test
  void loadThrowsWhenCpuFieldMissing() throws Exception {
    Files.writeString(
        tempDir.resolve(ModelManifest.MANIFEST_FILE),
        """
        { "gpu": "model_fp16.onnx" }
        """);

    assertThrows(IllegalStateException.class, () -> ModelManifest.load(tempDir));
  }

  @Test
  void resolveModelPathReturnsCpuWhenGpuDisabled() throws Exception {
    Files.writeString(
        tempDir.resolve(ModelManifest.MANIFEST_FILE),
        """
        { "cpu": "model_int8.onnx", "gpu": "model_fp16.onnx" }
        """);

    ModelManifest m = ModelManifest.load(tempDir);

    assertEquals(tempDir.resolve("model_int8.onnx"), m.resolveModelPath(tempDir, false));
  }

  @Test
  void resolveModelPathReturnsGpuWhenGpuEnabled() throws Exception {
    Files.writeString(
        tempDir.resolve(ModelManifest.MANIFEST_FILE),
        """
        { "cpu": "model_int8.onnx", "gpu": "model_fp16.onnx" }
        """);

    ModelManifest m = ModelManifest.load(tempDir);

    assertEquals(tempDir.resolve("model_fp16.onnx"), m.resolveModelPath(tempDir, true));
  }

  @Test
  void resolveModelPathFallsToCpuWhenGpuFieldAbsent() throws Exception {
    Files.writeString(
        tempDir.resolve(ModelManifest.MANIFEST_FILE),
        """
        { "cpu": "model.onnx" }
        """);

    ModelManifest m = ModelManifest.load(tempDir);

    assertEquals(tempDir.resolve("model.onnx"), m.resolveModelPath(tempDir, true));
  }

  @Test
  void loadOrDefaultReturnsManifestWhenPresent() throws Exception {
    Files.writeString(
        tempDir.resolve(ModelManifest.MANIFEST_FILE),
        """
        { "cpu": "custom.onnx", "gpu": "custom_fp16.onnx" }
        """);

    ModelManifest m = ModelManifest.loadOrDefault(tempDir);

    assertEquals("custom.onnx", m.cpu());
    assertEquals("custom_fp16.onnx", m.gpu());
  }

  @Test
  void loadOrDefaultReturnsConventionWhenManifestMissing() {
    ModelManifest m = ModelManifest.loadOrDefault(tempDir);

    assertEquals("model.onnx", m.cpu());
    assertEquals("model_fp16.onnx", m.gpu());
    assertEquals("tokenizer.json", m.tokenizer());
    assertEquals("pooling_config.json", m.poolingConfig());
    assertEquals("config.json", m.labelConfig());
  }

  @Test
  void unknownFieldsAreIgnored() throws Exception {
    Files.writeString(
        tempDir.resolve(ModelManifest.MANIFEST_FILE),
        """
        { "cpu": "model.onnx", "future_field": "something" }
        """);

    ModelManifest m = ModelManifest.load(tempDir);

    assertEquals("model.onnx", m.cpu());
  }

  // resolveExistingModelFile — tempdoc 374 alpha.22 Bug S regression guard.
  // Pre-fix BertNerInference called resolveModelPath(_, false) which always
  // returns cpu = "model.onnx". On GPU_FULL Install AI only ships model_fp16.onnx,
  // so the path resolved to a non-existent file and ORT threw ORT_NO_SUCHFILE.

  @Test
  void resolveExistingModelFileReturnsGpuOnFp16OnlyLayout() throws Exception {
    // Mirrors the GPU_FULL Install AI layout: only model_fp16.onnx on disk.
    Files.writeString(tempDir.resolve("model_fp16.onnx"), "stub");
    ModelManifest m = new ModelManifest("model.onnx", "model_fp16.onnx", null, null, null);

    assertEquals(tempDir.resolve("model_fp16.onnx"), m.resolveExistingModelFile(tempDir));
  }

  @Test
  void resolveExistingModelFileReturnsCpuWhenOnlyCpuPresent() throws Exception {
    Files.writeString(tempDir.resolve("model.onnx"), "stub");
    ModelManifest m = new ModelManifest("model.onnx", "model_fp16.onnx", null, null, null);

    assertEquals(tempDir.resolve("model.onnx"), m.resolveExistingModelFile(tempDir));
  }

  @Test
  void resolveExistingModelFilePrefersGpuWhenBothExist() throws Exception {
    // Mirrors the pre-staged sandbox/dev layout: both variants on disk.
    Files.writeString(tempDir.resolve("model.onnx"), "stub");
    Files.writeString(tempDir.resolve("model_fp16.onnx"), "stub");
    ModelManifest m = new ModelManifest("model.onnx", "model_fp16.onnx", null, null, null);

    assertEquals(tempDir.resolve("model_fp16.onnx"), m.resolveExistingModelFile(tempDir));
  }

  @Test
  void resolveExistingModelFileFallsBackToLegacyName() {
    // Neither declared variant on disk — final fallback to legacy "model.onnx"
    // so callers without a manifest still hit the conventional name.
    ModelManifest m = new ModelManifest("custom.onnx", "custom_fp16.onnx", null, null, null);

    assertEquals(tempDir.resolve("model.onnx"), m.resolveExistingModelFile(tempDir));
  }

  @Test
  void resolveExistingModelFileReturnsCpuWhenGpuFieldNull() throws Exception {
    Files.writeString(tempDir.resolve("model.onnx"), "stub");
    ModelManifest m = new ModelManifest("model.onnx", null, null, null, null);

    assertEquals(tempDir.resolve("model.onnx"), m.resolveExistingModelFile(tempDir));
  }
}
