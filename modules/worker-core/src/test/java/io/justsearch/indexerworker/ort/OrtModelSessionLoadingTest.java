package io.justsearch.indexerworker.ort;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.ort.ModelManifest;
import io.justsearch.ort.OrtSessionAssembler;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration tests that load real ONNX models with production session options.
 *
 * <p>Tagged {@code experiment} so they are excluded from default test runs (models may not be
 * present on disk, especially in CI without LFS checkout). Run explicitly with:
 *
 * <pre>{@code
 * ./gradlew.bat :modules:worker-core:test -PincludeTags=experiment
 * }</pre>
 *
 * <p>Each test uses {@code assumeTrue(modelFile.exists())} to skip gracefully when model files are
 * absent.
 */
@DisplayName("ORT Model Session Loading — integration")
@Tag("experiment")
final class OrtModelSessionLoadingTest {

  /** Resolves the repo root by walking up from user.dir looking for the models/ directory. */
  private static Path findRepoRoot() {
    Path candidate = Path.of(System.getProperty("user.dir"));
    for (int i = 0; i < 5; i++) {
      if (Files.isDirectory(candidate.resolve("models"))) {
        return candidate;
      }
      candidate = candidate.getParent();
      if (candidate == null) break;
    }
    return null;
  }

  @Test
  @DisplayName("SPLADE model loads on CPU and produces [1, seqLen, 30522] output")
  void spladeModelLoadsCpu() throws Exception {
    Path repoRoot = findRepoRoot();
    assumeTrue(repoRoot != null, "Repo root not found");
    Path modelDir = repoRoot.resolve("models/splade/naver-splade-v3");
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
    Path modelPath = modelDir.resolve(manifest.cpu());
    assumeTrue(Files.exists(modelPath), "SPLADE model not found: " + modelPath);

    OrtEnvironment env = OrtEnvironment.getEnvironment();
    try (OrtSession session = createProductionSession(env, modelPath)) {
      // Verify inputs
      Set<String> inputNames = session.getInputNames();
      assertTrue(inputNames.contains("input_ids"), "Missing input_ids");
      assertTrue(inputNames.contains("attention_mask"), "Missing attention_mask");

      // Run dummy inference
      float[][][] output = runDummyInference(env, session, inputNames);

      // SPLADE MLM logits: [1, seqLen, vocabSize]
      // The distilled PRESPARSE model has different outputs — check which format
      Set<String> outputNames = session.getOutputNames();
      if (outputNames.contains("output_idx")) {
        // PRESPARSE format — output is int64, not float[][][], so runDummyInference returns null.
        // The session loaded and inference completed without exception — that's the assertion.
        assertNull(output, "PRESPARSE model should return non-float output (null from helper)");
      } else {
        // MLM_LOGITS format
        assertEquals(1, output.length, "batch dim");
        assertEquals(3, output[0].length, "seq_len dim");
        assertEquals(30_522, output[0][0].length, "vocab_size dim");
      }
    }
  }

  @Test
  @DisplayName("Embedding model loads on CPU and produces [1, seqLen, dim] output")
  void embeddingModelLoadsCpu() throws Exception {
    Path repoRoot = findRepoRoot();
    assumeTrue(repoRoot != null, "Repo root not found");
    Path modelDir = repoRoot.resolve("models/onnx/embeddinggemma-300m");
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
    Path modelPath = modelDir.resolve(manifest.cpu());
    assumeTrue(Files.exists(modelPath), "Embedding model not found: " + modelPath);

    OrtEnvironment env = OrtEnvironment.getEnvironment();
    try (OrtSession session = createProductionSession(env, modelPath)) {
      Set<String> inputNames = session.getInputNames();
      assertTrue(inputNames.contains("input_ids"), "Missing input_ids");
      assertTrue(inputNames.contains("attention_mask"), "Missing attention_mask");

      float[][][] output = runDummyInference(env, session, inputNames);
      assertEquals(1, output.length, "batch dim");
      assertEquals(3, output[0].length, "seq_len dim");
      // Embedding dimension varies by model (768 for nomic, varies for gemma)
      assertTrue(output[0][0].length > 0, "embedding dim should be positive");
    }
  }

  @Test
  @DisplayName("NER model loads on CPU and produces [1, seqLen, 9] output")
  void nerModelLoadsCpu() throws Exception {
    Path repoRoot = findRepoRoot();
    assumeTrue(repoRoot != null, "Repo root not found");
    Path modelDir = repoRoot.resolve("models/onnx/ner");
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
    Path modelPath = modelDir.resolve(manifest.cpu());
    assumeTrue(Files.exists(modelPath), "NER model not found: " + modelPath);

    OrtEnvironment env = OrtEnvironment.getEnvironment();
    try (OrtSession session = createProductionSession(env, modelPath)) {
      Set<String> inputNames = session.getInputNames();
      assertTrue(inputNames.contains("input_ids"), "Missing input_ids");
      assertTrue(inputNames.contains("attention_mask"), "Missing attention_mask");

      float[][][] output = runDummyInference(env, session, inputNames);
      assertEquals(1, output.length, "batch dim");
      assertEquals(3, output[0].length, "seq_len dim");
      assertEquals(9, output[0][0].length, "num_labels dim (BIO tags)");
    }
  }

  @Test
  @DisplayName("SPLADE FP16 model loads on CPU (format validation)")
  void spladefp16ModelLoadsCpu() throws Exception {
    Path repoRoot = findRepoRoot();
    assumeTrue(repoRoot != null, "Repo root not found");
    Path modelDir = repoRoot.resolve("models/splade/naver-splade-v3");
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
    if (manifest.gpu() == null) return; // No FP16 variant
    Path fp16Path = modelDir.resolve(manifest.gpu());
    assumeTrue(Files.exists(fp16Path), "SPLADE FP16 model not found: " + fp16Path);

    OrtEnvironment env = OrtEnvironment.getEnvironment();
    try (OrtSession session = createProductionSession(env, fp16Path)) {
      assertFalse(session.getInputNames().isEmpty(), "Model should have inputs");
      assertFalse(session.getOutputNames().isEmpty(), "Model should have outputs");
    }
  }

  @Test
  @DisplayName("NER FP16 model loads on CPU or fails with known ORT error")
  void nerFp16ModelLoadsOrFailsGracefully() throws Exception {
    Path repoRoot = findRepoRoot();
    assumeTrue(repoRoot != null, "Repo root not found");
    Path modelDir = repoRoot.resolve("models/onnx/ner");
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
    if (manifest.gpu() == null) return;
    Path fp16Path = modelDir.resolve(manifest.gpu());
    assumeTrue(Files.exists(fp16Path), "NER FP16 model not found: " + fp16Path);

    OrtEnvironment env = OrtEnvironment.getEnvironment();
    try (OrtSession session = createProductionSession(env, fp16Path)) {
      // FP16 model loaded on CPU — valid format
      assertFalse(session.getInputNames().isEmpty(), "Model should have inputs");
      assertFalse(session.getOutputNames().isEmpty(), "Model should have outputs");
    } catch (ai.onnxruntime.OrtException e) {
      // FP16 models with fused ops (SimplifiedLayerNormFusion) can't load on CPU EP.
      // This is expected — the FP16→FP32 fallback in production handles this case.
      assertTrue(
          e.getMessage().contains("GetIndexFromName")
              || e.getMessage().contains("LayerNorm"),
          "Expected known FP16 CPU incompatibility, got: " + e.getMessage());
    }
  }

  @Test
  @DisplayName("SPLADE multilingual PRESPARSE model loads, infers, and closes without crash")
  void spladeMultilingualPresparseCloseSession() throws Exception {
    Path repoRoot = findRepoRoot();
    assumeTrue(repoRoot != null, "Repo root not found");
    Path modelPath =
        repoRoot.resolve("tmp/splade-multilingual-staging/presparse/model.onnx");
    assumeTrue(Files.exists(modelPath), "PRESPARSE model not found: " + modelPath);

    OrtEnvironment env = OrtEnvironment.getEnvironment();

    // Run 3 create/infer/close cycles to test session lifecycle stability
    for (int cycle = 0; cycle < 3; cycle++) {
      OrtSession session = createProductionSession(env, modelPath);
      try {
        // Verify PRESPARSE outputs
        Set<String> outputNames = session.getOutputNames();
        assertTrue(outputNames.contains("output_idx"), "Missing output_idx");
        assertTrue(outputNames.contains("output_weights"), "Missing output_weights");

        // Run dummy inference
        int seqLen = 6;
        long[] inputIds = {101, 7592, 2088, 102, 0, 0};
        long[] attentionMask = {1, 1, 1, 1, 0, 0};
        long[] tokenTypeIds = {0, 0, 0, 0, 0, 0};
        long[] shape = {1, seqLen};

        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape));
        inputs.put(
            "attention_mask",
            OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape));
        inputs.put(
            "token_type_ids",
            OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape));

        try (OrtSession.Result result = session.run(inputs)) {
          OnnxTensor idxTensor = (OnnxTensor) result.get("output_idx").orElseThrow();
          OnnxTensor weightTensor = (OnnxTensor) result.get("output_weights").orElseThrow();
          long[] idxShape = idxTensor.getInfo().getShape();
          long[] weightShape = weightTensor.getInfo().getShape();
          assertEquals(2, idxShape.length, "output_idx should be 2D [cycle " + cycle + "]");
          assertEquals(256, idxShape[1], "output_idx k=256 [cycle " + cycle + "]");
          assertEquals(2, weightShape.length, "output_weights 2D [cycle " + cycle + "]");
          assertEquals(256, weightShape[1], "output_weights k=256 [cycle " + cycle + "]");
        } finally {
          for (OnnxTensor tensor : inputs.values()) {
            tensor.close();
          }
        }
      } finally {
        // THE CRITICAL TEST: closeSession() must not crash
        session.close();
      }
    }
  }

  @Test
  @DisplayName("NER multilingual model loads, infers, and closes without crash")
  void nerMultilingualCloseSession() throws Exception {
    Path repoRoot = findRepoRoot();
    assumeTrue(repoRoot != null, "Repo root not found");
    Path modelDir = repoRoot.resolve("models/onnx/ner");
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
    Path modelPath = modelDir.resolve(manifest.cpu());
    assumeTrue(Files.exists(modelPath), "NER model not found: " + modelPath);

    OrtEnvironment env = OrtEnvironment.getEnvironment();
    for (int cycle = 0; cycle < 3; cycle++) {
      OrtSession session = createProductionSession(env, modelPath);
      try {
        float[][][] output = runDummyInference(env, session, session.getInputNames());
        assertNotNull(output, "NER should produce float output [cycle " + cycle + "]");
      } finally {
        session.close(); // THE CRITICAL TEST
      }
    }
  }

  @Test
  @DisplayName("SPLADE multilingual PRESPARSE FP16 loads on GPU, infers, and closes without crash")
  void spladeMultilingualPresparseGpuCloseSession() throws Exception {
    Path repoRoot = findRepoRoot();
    assumeTrue(repoRoot != null, "Repo root not found");
    Path fp16Path =
        repoRoot.resolve("tmp/splade-multilingual-staging/presparse/model_fp16.onnx");
    assumeTrue(Files.exists(fp16Path), "PRESPARSE FP16 model not found: " + fp16Path);

    OrtEnvironment env = OrtEnvironment.getEnvironment();
    io.justsearch.ort.GpuSessionConfig gpuConfig =
        new io.justsearch.ort.GpuSessionConfig(0, 2048L * 1024 * 1024);
    OrtSession session;
    try {
      session = OrtSessionAssembler.verifyModelSession(env, fp16Path, gpuConfig);
    } catch (OrtException e) {
      assumeTrue(false, "GPU not available: " + e.getMessage());
      return;
    }

    try {
      int seqLen = 6;
      long[] inputIds = {101, 7592, 2088, 102, 0, 0};
      long[] attentionMask = {1, 1, 1, 1, 0, 0};
      long[] tokenTypeIds = {0, 0, 0, 0, 0, 0};
      long[] shape = {1, seqLen};
      Map<String, OnnxTensor> inputs = new HashMap<>();
      inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape));
      inputs.put(
          "attention_mask",
          OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape));
      inputs.put(
          "token_type_ids",
          OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape));

      try (OrtSession.Result result = session.run(inputs)) {
        OnnxTensor idxTensor = (OnnxTensor) result.get("output_idx").orElseThrow();
        long[] idxShape = idxTensor.getInfo().getShape();
        assertEquals(2, idxShape.length, "output_idx should be 2D");
        assertEquals(256, idxShape[1], "output_idx should have k=256 columns");
      } finally {
        for (OnnxTensor tensor : inputs.values()) {
          tensor.close();
        }
      }
    } finally {
      session.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Creates an ORT session with the same non-GPU production options used in the real encoders. */
  private static OrtSession createProductionSession(OrtEnvironment env, Path modelPath)
      throws OrtException {
    return OrtSessionAssembler.verifyModelSession(env, modelPath, null);
  }

  /**
   * Runs a minimal dummy inference with [CLS] test [SEP] tokens. Returns the output as a 3D float
   * array, or null if the model uses a non-standard output format.
   */
  private static float[][][] runDummyInference(
      OrtEnvironment env, OrtSession session, Set<String> inputNames) throws Exception {
    int seqLen = 3;
    long[] inputIds = {101, 2023, 102}; // CLS, "this", SEP
    long[] attentionMask = {1, 1, 1};
    long[] shape = {1, seqLen};

    Map<String, OnnxTensor> inputs = new HashMap<>();
    OnnxTensor tokenTypeTensor = null;
    try {
      inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape));
      inputs.put(
          "attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape));

      if (inputNames.contains("token_type_ids")) {
        tokenTypeTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[seqLen]), shape);
        inputs.put("token_type_ids", tokenTypeTensor);
      }

      try (OrtSession.Result result = session.run(inputs)) {
        Object value = result.get(0).getValue();
        if (value instanceof float[][][] arr) {
          return arr;
        }
        // Non-standard output (e.g., PRESPARSE int64 indices) — return null
        return null;
      }
    } finally {
      for (OnnxTensor tensor : inputs.values()) {
        tensor.close();
      }
    }
  }
}
