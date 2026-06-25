package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 374 alpha.20 Bug L regression coverage for {@link EmbeddingFingerprint}.
 *
 * <p>Round-10 sandbox: post-cold-restart, EmbeddingService and EmbeddingFingerprint
 * disagreed on whether the model existed. Same JVM, 3ms apart — `EmbeddingService
 * created … modelPath=…` followed by `No embedding model found`. Pre-alpha.20
 * EmbeddingFingerprint called {@code EmbeddingOnnxModelDiscovery.resolve(null)} which
 * read sysprop/env-var only and missed the resolved-config snapshot. Alpha.20 routes
 * through {@code EmbeddingConfig.fromEnv().modelPath()} which already consults the
 * snapshot.
 */
@DisplayName("EmbeddingFingerprint cold-restart resolution (374 alpha.20 Bug L)")
class EmbeddingFingerprintTest {

  private static final String KEY_MODEL_PATH = "justsearch.embed.onnx.model_path";

  @TempDir Path tmp;

  private ConfigStore previousStore;
  private String previousModelPath;

  @BeforeEach
  void capturePrev() {
    previousStore = ConfigStore.globalOrNull();
    previousModelPath = System.getProperty(KEY_MODEL_PATH);
    EmbeddingFingerprint.invalidate();
  }

  @AfterEach
  void restorePrev() {
    if (previousModelPath == null) {
      System.clearProperty(KEY_MODEL_PATH);
    } else {
      System.setProperty(KEY_MODEL_PATH, previousModelPath);
    }
    TestResolvedConfigHelper.restoreGlobal(previousStore);
    EmbeddingFingerprint.invalidate();
  }

  /**
   * The fix path: with the model path in the resolved config (mirroring how the
   * worker snapshot carries it on cold restart), {@code EmbeddingFingerprint}
   * resolves through {@code EmbeddingConfig.fromEnv().modelPath()} and finds the
   * model file. Pre-alpha.20 this would have failed because
   * {@code EmbeddingOnnxModelDiscovery.resolve(null)} bypasses the snapshot.
   */
  @Test
  @DisplayName("model path from resolved config → fingerprint computes (Bug L fix)")
  void resolvedConfigModelPath_fingerprintComputes() throws IOException {
    Path modelDir = tmp.resolve("models").resolve("onnx").resolve("gte-multilingual-base");
    Files.createDirectories(modelDir);
    Files.writeString(modelDir.resolve("model_fp16.onnx"), "stub-fp16-onnx-bytes");
    Files.writeString(modelDir.resolve("tokenizer.json"), "stub");
    Files.writeString(
        modelDir.resolve("model_manifest.json"),
        "{\"cpu\":\"model_fp16.onnx\",\"gpu\":\"model_fp16.onnx\"}");

    System.setProperty(KEY_MODEL_PATH, modelDir.toString());
    TestResolvedConfigHelper.storeFromEnvironment();

    java.util.Optional<Path> modelPath = EmbeddingFingerprint.modelPath();
    assertTrue(
        modelPath.isPresent(),
        "EmbeddingFingerprint must find the model when EmbeddingConfig.modelPath() resolves it"
            + " — pre-alpha.20 this missed the resolved-config snapshot and returned empty"
            + " on cold restart (374 alpha.20 Bug L).");
    assertEquals(
        modelDir.resolve("model_fp16.onnx").toAbsolutePath().normalize(),
        modelPath.get().toAbsolutePath().normalize(),
        "fingerprint must point at model_fp16.onnx (the GPU_FULL variant), per alpha.16 Bug C");

    java.util.Optional<String> fingerprint = EmbeddingFingerprint.get();
    assertTrue(fingerprint.isPresent(), "fingerprint must compute for an existing model file");
    assertNotNull(fingerprint.get());
    assertFalse(fingerprint.get().isBlank());
  }

  /**
   * Pre-fix the same code path returned null when the file was missing; post-fix
   * does the same. No new failure mode introduced.
   */
  @Test
  @DisplayName("model path resolved but file missing → returns empty (no exception)")
  void resolvedConfigModelPath_butFileMissing_returnsEmpty() throws IOException {
    Path modelDir = tmp.resolve("models").resolve("onnx").resolve("gte-multilingual-base");
    Files.createDirectories(modelDir);
    // Deliberately NOT creating model_fp16.onnx or model.onnx.
    Files.writeString(modelDir.resolve("tokenizer.json"), "stub");

    System.setProperty(KEY_MODEL_PATH, modelDir.toString());
    TestResolvedConfigHelper.storeFromEnvironment();

    java.util.Optional<Path> modelPath = EmbeddingFingerprint.modelPath();
    assertFalse(
        modelPath.isPresent(),
        "EmbeddingFingerprint must return empty when no model file exists — no exception."
            + " manifest.resolveExistingModelFile returns the manifest's nominal cpu/gpu file"
            + " name, but the existence check filters to actually-present files.");
  }
}
