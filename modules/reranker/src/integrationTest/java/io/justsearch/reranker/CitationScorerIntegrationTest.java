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
 * Integration tests for CitationScorer with a real ONNX cross-encoder model.
 *
 * <p>Requires model files at JUSTSEARCH_CITATION_SCORER_MODEL_PATH or default location:
 * models/citation-scorer/ms-marco-MiniLM-L2-v2
 */
class CitationScorerIntegrationTest {

  private static CitationScorer scorer;
  private static Path modelDir;

  @BeforeAll
  static void setUp() throws Exception {
    String modelPathEnv = System.getenv("JUSTSEARCH_CITATION_SCORER_MODEL_PATH");
    if (modelPathEnv == null || modelPathEnv.isBlank()) {
      modelPathEnv = System.getProperty("justsearch.citation.scorer.model_path");
    }
    if (modelPathEnv != null && !modelPathEnv.isBlank()) {
      modelDir = Path.of(modelPathEnv);
    } else {
      // Walk up from user.dir to find repo root (contains settings.gradle.kts)
      Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
      while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
        dir = dir.getParent();
      }
      if (dir != null) {
        modelDir = dir.resolve("models/citation-scorer/ms-marco-MiniLM-L2-v2");
      } else {
        modelDir = Path.of("models/citation-scorer/ms-marco-MiniLM-L2-v2");
      }
    }

    Path modelPath = modelDir.resolve("model.onnx");
    Path tokenizerPath = modelDir.resolve("tokenizer.json");

    assumeTrue(
        Files.exists(modelPath),
        "Model not found at " + modelPath + ". Set JUSTSEARCH_CITATION_SCORER_MODEL_PATH.");
    assumeTrue(Files.exists(tokenizerPath), "Tokenizer not found at " + tokenizerPath);

    // Tempdoc 397 §14.28 U1: testFixtures helper wraps OrtSessionAssembler.buildManager.
    io.justsearch.ort.SessionHandle sessions =
        io.justsearch.ort.testing.InferenceCompositionRootTestHelper.cpuSessionFor(
            "citation-it", modelDir);
    RerankerAssembly assembly = CitationScorer.buildAssembly(sessions, tokenizerPath, 512);
    scorer =
        new CitationScorer(assembly.sessions(), assembly.shape(), assembly.tokenizer());
  }

  @AfterAll
  static void tearDown() {
    if (scorer != null) {
      scorer.close();
    }
  }

  @Test
  void matchesSentencesToSupportingChunks() {
    List<String> sentences =
        List.of(
            "Machine learning enables systems to learn from data.",
            "The weather today is sunny.",
            "Neural networks process complex patterns.");

    List<String> chunks =
        List.of(
            "Machine learning is a subset of AI that allows computers to learn from data"
                + " without being explicitly programmed.",
            "Deep learning uses neural networks with many layers to recognize patterns.",
            "The recipe calls for two cups of flour and one cup of sugar.");

    List<String> chunkDocIds = List.of("doc1", "doc2", "doc3");

    CitationScorer.ScoringResult result = scorer.scoreAll(sentences, chunks, chunkDocIds, 0.5, 5000);

    assertEquals(3, result.sentencesTotal());
    assertTrue(result.sentencesMatched() >= 2, "At least 2 sentences should match");

    // ML sentence should match ML chunk (index 0)
    var mlMatch =
        result.matches().stream().filter(m -> m.sentenceIndex() == 0).findFirst().orElse(null);
    assertNotNull(mlMatch, "ML sentence should have a match");
    assertEquals(0, mlMatch.chunkIndex(), "ML sentence should match ML chunk");
    assertEquals("doc1", mlMatch.parentDocId());

    // Neural networks sentence should match deep learning chunk (index 1)
    var nnMatch =
        result.matches().stream().filter(m -> m.sentenceIndex() == 2).findFirst().orElse(null);
    assertNotNull(nnMatch, "Neural networks sentence should have a match");
    assertEquals(1, nnMatch.chunkIndex(), "Neural networks should match deep learning chunk");

    System.out.println("Citation scoring completed in " + result.latencyMs() + "ms");
    for (var match : result.matches()) {
      System.out.printf(
          "  Sentence %d → Chunk %d (score=%.4f): %s%n",
          match.sentenceIndex(), match.chunkIndex(), match.score(), match.sentenceText());
    }
  }

  @Test
  void scoresAreInSigmoidRange() {
    List<String> sentences = List.of("The system supports full-text search.");
    List<String> chunks =
        List.of("JustSearch provides full-text search capabilities using Apache Lucene.");
    List<String> chunkDocIds = List.of("doc1");

    CitationScorer.ScoringResult result = scorer.scoreAll(sentences, chunks, chunkDocIds, 0.0, 5000);

    assertFalse(result.matches().isEmpty(), "Should have at least one match with threshold 0");
    for (var match : result.matches()) {
      assertTrue(match.score() >= 0.0 && match.score() <= 1.0,
          "Score should be in [0,1] after sigmoid: " + match.score());
    }

    System.out.println("Score: " + result.matches().get(0).score());
  }

  @Test
  void skipsEmptyChunks() {
    List<String> sentences = List.of("Machine learning is powerful.");
    List<String> chunks = List.of("", "   ", "ML is a powerful technique for data analysis.");
    List<String> chunkDocIds = List.of("doc1", "doc2", "doc3");

    CitationScorer.ScoringResult result = scorer.scoreAll(sentences, chunks, chunkDocIds, 0.3, 5000);

    if (!result.matches().isEmpty()) {
      // If matched, it must be chunk index 2 (the non-empty one)
      assertEquals(2, result.matches().get(0).chunkIndex(),
          "Should only match non-empty chunk");
    }
  }

  @Test
  void handlesEmptyInput() {
    CitationScorer.ScoringResult result =
        scorer.scoreAll(List.of(), List.of("chunk"), List.of("doc1"), 0.5, 5000);
    assertEquals(0, result.sentencesTotal());
    assertTrue(result.matches().isEmpty());

    result = scorer.scoreAll(List.of("sentence"), List.of(), List.of(), 0.5, 5000);
    assertEquals(1, result.sentencesTotal());
    assertTrue(result.matches().isEmpty());
  }

  @Test
  void respectsDeadline() {
    // Many sentences and chunks to stress deadline
    List<String> sentences =
        List.of(
            "First sentence.", "Second sentence.", "Third sentence.",
            "Fourth sentence.", "Fifth sentence.", "Sixth sentence.",
            "Seventh sentence.", "Eighth sentence.", "Ninth sentence.",
            "Tenth sentence.");
    List<String> chunks =
        List.of(
            "First chunk content.", "Second chunk content.", "Third chunk content.",
            "Fourth chunk content.", "Fifth chunk content.");
    List<String> chunkDocIds = List.of("d1", "d2", "d3", "d4", "d5");

    // Very short deadline — should complete without hanging
    CitationScorer.ScoringResult result = scorer.scoreAll(sentences, chunks, chunkDocIds, 0.5, 1);
    assertTrue(result.latencyMs() < 5000, "Should not hang on tight deadline");
  }
}
