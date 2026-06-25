package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for CrossEncoderReranker.
 *
 * <p>These tests require a model to be present at the path specified by JUSTSEARCH_RERANK_MODEL_PATH
 * or in the default location: models/reranker/ms-marco-MiniLM-L6-v2
 */
class CrossEncoderRerankerIntegrationTest {

  private static CrossEncoderReranker reranker;
  private static Path modelDir;

  @BeforeAll
  static void setUp() throws Exception {
    // Find model directory
    String modelPathEnv = System.getenv("JUSTSEARCH_RERANK_MODEL_PATH");
    if (modelPathEnv != null && !modelPathEnv.isBlank()) {
      modelDir = Path.of(modelPathEnv);
    } else {
      // Try default location relative to project root
      modelDir = Path.of("models/reranker/ms-marco-MiniLM-L6-v2");
      if (!Files.exists(modelDir)) {
        // Try from working directory
        modelDir = Path.of(System.getProperty("user.dir"))
            .resolve("models/reranker/ms-marco-MiniLM-L6-v2");
      }
    }

    Path modelPath = modelDir.resolve("model.onnx");
    Path tokenizerPath = modelDir.resolve("tokenizer.json");

    assumeTrue(Files.exists(modelPath),
        "Model not found at " + modelPath + ". Set JUSTSEARCH_RERANK_MODEL_PATH or download the model.");
    assumeTrue(Files.exists(tokenizerPath),
        "Tokenizer not found at " + tokenizerPath);

    // Tempdoc 397 §14.28 U1: session construction for tests routes through the testFixtures
    // helper, which wraps OrtSessionAssembler.buildManager (the single production entry point).
    io.justsearch.ort.SessionHandle sessions =
        io.justsearch.ort.testing.InferenceCompositionRootTestHelper.cpuSessionFor(
            "reranker-it", modelDir);
    RerankerAssembly assembly = CrossEncoderReranker.buildAssembly(sessions, tokenizerPath, 512);
    reranker =
        new CrossEncoderReranker(assembly.sessions(), assembly.shape(), assembly.tokenizer());
  }

  @AfterAll
  static void tearDown() {
    if (reranker != null) {
      reranker.close();
    }
  }

  @Test
  void reranksDocumentsByRelevance() {
    String query = "What is machine learning?";
    List<String> documents = List.of(
        "Machine learning is a subset of artificial intelligence that enables systems to learn from data.",
        "The weather today is sunny with clear skies.",
        "Deep learning uses neural networks with many layers to process complex patterns.",
        "Cooking pasta requires boiling water and adding salt."
    );

    CrossEncoderReranker.RerankedResult result = reranker.rerank(query, documents, 5000);

    assertFalse(result.skipped(), "Reranking should not be skipped");
    assertEquals(4, result.sortedIndices().size(), "Should return indices for all documents");
    assertEquals(4, result.scores().size(), "Should return scores for all documents");

    // The ML-related documents (0 and 2) should rank higher than unrelated ones (1 and 3)
    List<Integer> topTwo = result.sortedIndices().subList(0, 2);
    assertTrue(topTwo.contains(0) || topTwo.contains(2),
        "At least one ML document should be in top 2");

    System.out.println("Reranking completed in " + result.latencyMs() + "ms");
    System.out.println("Sorted indices: " + result.sortedIndices());
    System.out.println("Scores: " + result.scores());
  }

  @Test
  void handlesEmptyDocumentList() {
    CrossEncoderReranker.RerankedResult result = reranker.rerank("query", List.of(), 1000);

    assertFalse(result.skipped());
    assertTrue(result.sortedIndices().isEmpty());
    assertTrue(result.scores().isEmpty());
  }

  @Test
  void handlesSingleDocument() {
    String query = "test query";
    List<String> documents = List.of("This is a test document about testing.");

    CrossEncoderReranker.RerankedResult result = reranker.rerank(query, documents, 5000);

    assertFalse(result.skipped());
    assertEquals(1, result.sortedIndices().size());
    assertEquals(0, result.sortedIndices().get(0));
    assertEquals(1, result.scores().size());
  }

  @Test
  void respectsDeadline() {
    String query = "test";
    List<String> documents = List.of(
        "Document one",
        "Document two",
        "Document three"
    );

    // Very short deadline should cause skip (or at least not hang)
    CrossEncoderReranker.RerankedResult result = reranker.rerank(query, documents, 1);

    // Either it completes very fast or it skips - both are acceptable
    assertTrue(result.latencyMs() < 5000, "Should not take more than 5 seconds");
  }
}
