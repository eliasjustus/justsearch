package io.justsearch.systemtests.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendRegistry;
import io.justsearch.aibackend.local.LocalIntentTranslatorConfig;
import io.justsearch.systemtests.aijudge.SemanticSimilarityChecker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Summarization Fidelity Test.
 *
 * <p>Verifies that the LLM can produce semantically coherent summaries
 * that capture the key information from the source text.
 *
 * <p>Uses {@link SemanticSimilarityChecker} with the Nomic embedding model
 * to compare generated summaries against pre-written "golden" summaries.
 */
@Tag("ai")
@DisplayName("Summarization Fidelity")
class SummarizationFidelityTest {
  private static final Logger log = LoggerFactory.getLogger(SummarizationFidelityTest.class);

  /**
   * Minimum similarity score to pass (0.0 - 1.0).
   *
   * <p>Threshold allows for paraphrasing while catching completely wrong outputs.
   */
  private static final double SIMILARITY_THRESHOLD = 0.50;

  private static AiBackend generationBackend;
  private static SemanticSimilarityChecker similarityChecker;

  // Test document: A technical explanation about Java Garbage Collection
  private static final String SOURCE_TEXT = """
      Java's garbage collection is an automatic memory management feature.
      The JVM periodically identifies objects that are no longer reachable
      from any live thread and reclaims their memory. The most common
      garbage collectors include G1GC, ZGC, and the older CMS collector.
      G1GC divides the heap into regions and prioritizes collecting regions
      with the most garbage first. ZGC is designed for low-latency applications
      and can handle heaps up to 16 terabytes with pause times under 10ms.
      Developers can tune GC behavior using JVM flags like -Xmx, -Xms,
      and -XX:+UseG1GC to optimize for throughput or latency.
      """;

  // Golden summary: What a good summary should capture
  private static final String GOLDEN_SUMMARY = """
      Java uses automatic garbage collection to manage memory.
      The JVM reclaims memory from unreachable objects.
      Common collectors include G1GC and ZGC, each optimized for different use cases.
      """;

  @BeforeAll
  static void setup() {
    try {
      Path modelPath = AiQualityTestConfig.findQwenModel();
      if (!Files.exists(modelPath)) {
          throw new IllegalStateException("Qwen model not found at " + modelPath);
      }
      if (!AiQualityTestConfig.isNativeAvailable()) {
          throw new IllegalStateException("Native library not available. Build: ./gradlew :modules:ai-engine-native:buildBridge");
      }

      LocalIntentTranslatorConfig config = AiQualityTestConfig
          .deterministicConfig(modelPath)
          .build();

      generationBackend = new BackendRegistry().resolve("deterministic", config).orElseThrow(() -> new IllegalStateException("No backend provider available")).create(config);
      similarityChecker = SemanticSimilarityChecker.createWithFallback();
      log.info("SummarizationFidelityTest initialized with model: {}", modelPath);
    } catch (Exception e) {
      if (e instanceof IllegalStateException) {
          throw (IllegalStateException) e;
      }
      throw new RuntimeException("Failed to initialize AI backends", e);
    }
  }

  @AfterAll
  static void cleanup() {
    if (generationBackend != null) {
      try {
        generationBackend.close();
      } catch (Exception e) {
        log.debug("Error closing generation backend", e);
      }
    }
    if (similarityChecker != null) {
      similarityChecker.close();
    }
  }

  @Test
  @DisplayName("Generated summary is semantically similar to golden summary")
  void summaryMatchesGoldenReference() throws Exception {
    try (AiBackend.Session session = generationBackend.createSession()) {
      // Generate summary
      AiBackend.ChunkRequest request = new AiBackend.ChunkRequest(
          UUID.randomUUID(),
          0,
          SOURCE_TEXT,
          0,
          100, // max tokens
          Locale.ENGLISH.toLanguageTag()
      );

      AiBackend.ChunkResponse response = session.summarizeChunk(request);
      String generatedSummary = response.summaryText();

      assertNotNull(generatedSummary, "Summary should not be null");
      assertFalse(generatedSummary.isBlank(), "Summary should not be blank");

      log.info("Generated summary: {}", generatedSummary);

      // Compare with golden summary
      SemanticSimilarityChecker.SimilarityResult result =
          similarityChecker.evaluate(generatedSummary, GOLDEN_SUMMARY, SIMILARITY_THRESHOLD);

      log.info("Similarity: {} (threshold: {}, method: {})",
          result.similarityPercent(), SIMILARITY_THRESHOLD, result.method());

      assertTrue(result.isSimilar(),
          String.format("Summary similarity %.2f is below threshold %.2f. Generated: %s",
              result.similarity(), SIMILARITY_THRESHOLD, generatedSummary));
    }
  }

  @Test
  @DisplayName("Summary captures key technical terms")
  void summaryCapturesKeyTerms() throws Exception {
    try (AiBackend.Session session = generationBackend.createSession()) {
      AiBackend.ChunkRequest request = new AiBackend.ChunkRequest(
          UUID.randomUUID(),
          0,
          SOURCE_TEXT,
          0,
          100,
          Locale.ENGLISH.toLanguageTag()
      );

      AiBackend.ChunkResponse response = session.summarizeChunk(request);
      String summary = response.summaryText().toLowerCase(Locale.ROOT);

      // The summary should mention at least some key concepts
      boolean mentionsGC = summary.contains("garbage") || summary.contains("gc");
      boolean mentionsMemory = summary.contains("memory");
      boolean mentionsJava = summary.contains("java") || summary.contains("jvm");

      int keyTermsFound = (mentionsGC ? 1 : 0) + (mentionsMemory ? 1 : 0) + (mentionsJava ? 1 : 0);

      log.info("Key terms found: {} (GC={}, Memory={}, Java={})",
          keyTermsFound, mentionsGC, mentionsMemory, mentionsJava);

      assertTrue(keyTermsFound >= 2,
          "Summary should mention at least 2 key concepts. Found: " + keyTermsFound);
    }
  }

  @Test
  @DisplayName("Summary is shorter than source text")
  void summaryIsConcise() throws Exception {
    try (AiBackend.Session session = generationBackend.createSession()) {
      AiBackend.ChunkRequest request = new AiBackend.ChunkRequest(
          UUID.randomUUID(),
          0,
          SOURCE_TEXT,
          0,
          100,
          Locale.ENGLISH.toLanguageTag()
      );

      AiBackend.ChunkResponse response = session.summarizeChunk(request);
      String summary = response.summaryText();

      int sourceWords = SOURCE_TEXT.split("\\s+").length;
      int summaryWords = summary.split("\\s+").length;

      log.info("Source: {} words, Summary: {} words ({}% reduction)",
          sourceWords, summaryWords, 100 - (summaryWords * 100 / sourceWords));

      assertTrue(summaryWords < sourceWords,
          "Summary should be shorter than source. Source: " + sourceWords + ", Summary: " + summaryWords);
      assertTrue(summaryWords >= 5,
          "Summary should have at least 5 words. Got: " + summaryWords);
    }
  }
}
