package io.justsearch.indexerworker.embed.onnx;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;

/**
 * Integration test for {@link OnnxEmbeddingEncoder} using a real ONNX model.
 *
 * <p>Skipped automatically if the model files are not present on disk. To run: place {@code
 * model.onnx} and {@code tokenizer.json} in {@code models/onnx/embedding/} relative to the repo
 * root.
 */
@DisplayName("OnnxEmbeddingEncoder (integration)")
final class OnnxEmbeddingEncoderIntegrationTest {

  private static final int MAX_SEQ_LEN = 512;
  private static Path modelDir;
  private static OnnxEmbeddingEncoder encoder;

  @BeforeAll
  static void setUp() throws Exception {
    // Try common model locations: walk up from CWD, then check env var override
    Path repoRoot = Path.of(System.getProperty("user.dir"));
    Path candidate = repoRoot;
    for (int i = 0; i < 5; i++) {
      Path modelsDir = candidate.resolve("models/onnx/embedding");
      if (Files.exists(modelsDir.resolve("model.onnx"))
          && Files.exists(modelsDir.resolve("tokenizer.json"))) {
        modelDir = modelsDir;
        break;
      }
      candidate = candidate.getParent();
      if (candidate == null) break;
    }

    // Also check explicit env var override
    if (modelDir == null) {
      String envPath = System.getenv("JUSTSEARCH_EMBED_ONNX_MODEL_PATH");
      if (envPath != null && !envPath.isBlank()) {
        Path envDir = Path.of(envPath);
        if (Files.exists(envDir.resolve("model.onnx"))
            && Files.exists(envDir.resolve("tokenizer.json"))) {
          modelDir = envDir;
        }
      }
    }

    assumeTrue(modelDir != null, "ONNX embedding model not found, skipping integration test");
    assumeTrue(
        Files.exists(modelDir.resolve("model.onnx")),
        "model.onnx not found in " + modelDir);
    assumeTrue(
        Files.exists(modelDir.resolve("tokenizer.json")),
        "tokenizer.json not found in " + modelDir);

    // Tempdoc 397 §14.28 U1: testFixtures helper wraps OrtSessionAssembler.buildManager — the
    // single production entry point. Pre-U1 this test called the now-deleted buildFallback.
    io.justsearch.ort.SessionHandle sessions =
        io.justsearch.ort.testing.InferenceCompositionRootTestHelper.cpuSessionFor(
            "embed-test", modelDir);
    io.justsearch.indexerworker.embed.onnx.EmbeddingAssembly assembly =
        OnnxEmbeddingEncoder.buildAssembly(sessions, modelDir, MAX_SEQ_LEN);
    encoder =
        new OnnxEmbeddingEncoder(assembly.sessions(), assembly.shape(), assembly.tokenizer());
  }

  @AfterAll
  static void tearDown() {
    if (encoder != null) {
      encoder.close();
    }
  }

  @Test
  @DisplayName("produces 768-dimensional embedding")
  void produces768DimensionalEmbedding() throws Exception {
    var result = encoder.embed("The quick brown fox jumps over the lazy dog.");
    assertEquals(768, result.vector().length);
    assertEquals(768, encoder.embeddingDimension());
  }

  @Test
  @DisplayName("embedding is L2-normalized (unit length)")
  void embeddingIsUnitLength() throws Exception {
    var result = encoder.embed("Machine learning is a subset of artificial intelligence.");
    double norm = 0.0;
    for (float v : result.vector()) {
      norm += (double) v * v;
    }
    assertEquals(1.0, Math.sqrt(norm), 1e-4, "Embedding should be L2-normalized to unit length");
  }

  @Test
  @DisplayName("different texts produce different embeddings")
  void differentTextsProduceDifferentEmbeddings() throws Exception {
    var result1 = encoder.embed("Quantum computing uses qubits.");
    var result2 = encoder.embed("The recipe calls for two cups of flour.");

    // Cosine similarity should be < 1.0 (different texts)
    double dot = 0.0;
    for (int i = 0; i < result1.vector().length; i++) {
      dot += (double) result1.vector()[i] * result2.vector()[i];
    }
    assertTrue(dot < 0.95, "Unrelated texts should have cosine similarity < 0.95, got " + dot);
  }

  @Test
  @DisplayName("similar texts produce similar embeddings")
  void similarTextsProduceSimilarEmbeddings() throws Exception {
    var result1 = encoder.embed("The cat sat on the mat.");
    var result2 = encoder.embed("A cat was sitting on a mat.");

    double dot = 0.0;
    for (int i = 0; i < result1.vector().length; i++) {
      dot += (double) result1.vector()[i] * result2.vector()[i];
    }
    assertTrue(dot > 0.7, "Similar texts should have cosine similarity > 0.7, got " + dot);
  }

  @Test
  @DisplayName("embedding is deterministic")
  void embeddingIsDeterministic() throws Exception {
    String text = "Deterministic embedding test input.";
    var result1 = encoder.embed(text);
    var result2 = encoder.embed(text);

    assertArrayEquals(
        result1.vector(), result2.vector(), 1e-6f, "Same text should produce identical embeddings");
  }

  @Test
  @DisplayName("short text produces single chunk")
  void shortTextProducesSingleChunk() throws Exception {
    var result = encoder.embed("Short text.");
    assertEquals(1, result.chunkCount());
    assertTrue(result.chunkVectors().isEmpty());
  }

  @Test
  @DisplayName("empty text produces valid embedding")
  void emptyTextProducesValidEmbedding() throws Exception {
    var result = encoder.embed("");
    assertNotNull(result.vector());
    assertEquals(768, result.vector().length);
    assertEquals(1, result.chunkCount());
  }

  // ---------------------------------------------------------------------------
  // embedBatchWithChunking tests
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("embedBatchWithChunking")
  class BatchWithChunking {

    @Test
    @DisplayName("short texts in batch match single-embed results")
    void shortTextsBatchMatchesSingle() throws Exception {
      String text1 = "The quick brown fox jumps over the lazy dog.";
      String text2 = "Machine learning is a subset of artificial intelligence.";

      var single1 = encoder.embed(text1);
      var single2 = encoder.embed(text2);
      var batchResults = encoder.embedBatchWithChunking(List.of(text1, text2));

      assertEquals(2, batchResults.size());
      // Batch inference pads to uniform length. The ONNX model's internal attention is
      // affected by padding tokens, causing FP32 divergence from single-text inference.
      // Cosine > 0.97 confirms the vectors are semantically equivalent.
      double cosine1 = cosineSimilarity(single1.vector(), batchResults.get(0).vector());
      double cosine2 = cosineSimilarity(single2.vector(), batchResults.get(1).vector());
      assertTrue(
          cosine1 > 0.97,
          "Batch result[0] should closely match single embed, cosine=" + cosine1);
      assertTrue(
          cosine2 > 0.97,
          "Batch result[1] should closely match single embed, cosine=" + cosine2);
      assertEquals(1, batchResults.get(0).chunkCount());
      assertEquals(1, batchResults.get(1).chunkCount());
    }

    @Test
    @DisplayName("long text in batch is chunked, not truncated")
    void longTextInBatchIsChunked() throws Exception {
      // Build a text that exceeds 512 tokens (~700 words should be >512 tokens)
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 200; i++) {
        sb.append("The field of artificial intelligence has evolved significantly. ");
      }
      String longText = sb.toString();

      // Single embed should chunk
      var singleResult = encoder.embed(longText);
      assertTrue(
          singleResult.chunkCount() > 1,
          "Long text should be chunked by single embed, got chunkCount="
              + singleResult.chunkCount());

      // Batch embed should also chunk with same result
      String shortText = "A short sentence.";
      var batchResults = encoder.embedBatchWithChunking(List.of(shortText, longText));

      assertEquals(2, batchResults.size());

      // The long text in batch should have the same chunk count as single
      assertEquals(
          singleResult.chunkCount(), batchResults.get(1).chunkCount(),
          "Batch chunk count should match single embed chunk count");

      // Primary vectors should be close (same chunking + pooling, but batch padding
      // affects individual chunk embeddings, which propagates through mean-pooling)
      double cosine = cosineSimilarity(singleResult.vector(), batchResults.get(1).vector());
      assertTrue(
          cosine > 0.99,
          "Long text: batch vs single cosine should be > 0.99, got " + cosine);

      // Chunk vectors should also be present
      assertFalse(batchResults.get(1).chunkVectors().isEmpty(),
          "Batch result for long text should have chunk vectors");
      assertEquals(
          singleResult.chunkVectors().size(), batchResults.get(1).chunkVectors().size(),
          "Chunk vector count should match");
    }

    @Test
    @DisplayName("mixed short and long texts in batch all produce correct results")
    void mixedBatchCorrectness() throws Exception {
      String short1 = "Quantum computing uses qubits for computation.";
      String short2 = "The recipe calls for two cups of flour.";

      // Generate a long text
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 150; i++) {
        sb.append("Neural networks learn representations from data. ");
      }
      String longText = sb.toString();

      // Get single-embed reference results
      var refShort1 = encoder.embed(short1);
      var refShort2 = encoder.embed(short2);
      var refLong = encoder.embed(longText);

      // Batch all three
      var batchResults = encoder.embedBatchWithChunking(List.of(short1, longText, short2));
      assertEquals(3, batchResults.size());

      // Short texts should closely match (batch padding affects model attention)
      double cos1 = cosineSimilarity(refShort1.vector(), batchResults.get(0).vector());
      double cos2 = cosineSimilarity(refShort2.vector(), batchResults.get(2).vector());
      assertTrue(cos1 > 0.97, "short1 batch should match single, cosine=" + cos1);
      assertTrue(cos2 > 0.97, "short2 batch should match single, cosine=" + cos2);

      // Long text should match via cosine (same chunks, batch padding affects slightly)
      double cosine = cosineSimilarity(refLong.vector(), batchResults.get(1).vector());
      assertTrue(cosine > 0.99,
          "Long text batch vs single cosine should be > 0.99, got " + cosine);
      assertEquals(refLong.chunkCount(), batchResults.get(1).chunkCount());
    }

    @Test
    @DisplayName("single-item batch delegates correctly")
    void singleItemBatch() throws Exception {
      String text = "Single item batch test.";
      var singleResult = encoder.embed(text);
      var batchResults = encoder.embedBatchWithChunking(List.of(text));

      assertEquals(1, batchResults.size());
      assertArrayEquals(singleResult.vector(), batchResults.get(0).vector(), 1e-6f);
    }

    @Test
    @DisplayName("empty batch returns empty list")
    void emptyBatch() throws Exception {
      var batchResults = encoder.embedBatchWithChunking(List.of());
      assertTrue(batchResults.isEmpty());
    }
  }

  private static double cosineSimilarity(float[] a, float[] b) {
    double dot = 0.0;
    double normA = 0.0;
    double normB = 0.0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      normA += (double) a[i] * a[i];
      normB += (double) b[i] * b[i];
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }
}
