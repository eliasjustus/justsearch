/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.aijudge;

import java.io.Closeable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI Judge component that computes semantic similarity using real embeddings.
 *
 * <p>This checker uses the actual embedding model (nomic-embed-text) to compute
 * cosine similarity between texts. Unlike keyword matching, this captures:
 * <ul>
 *   <li>Synonyms ("dog" vs "canine")</li>
 *   <li>Paraphrases ("fast car" vs "quick automobile")</li>
 *   <li>Semantic equivalence ("The cat sat" vs "A feline was sitting")</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * try (SemanticSimilarityChecker checker = SemanticSimilarityChecker.create()) {
 *   SimilarityResult result = checker.compare(
 *       "The database uses connection pooling for efficiency",
 *       "DB connections are pooled to improve performance"
 *   );
 *   assertTrue(result.similarity() > 0.7, "Texts should be semantically similar");
 * }
 * }</pre>
 *
 * <p><b>Note:</b> This requires the embedding model to be available.
 * Use {@link #createWithFallback()} for graceful degradation.
 */
public final class SemanticSimilarityChecker implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(SemanticSimilarityChecker.class);

  /** Default similarity threshold for "similar enough". */
  public static final double DEFAULT_THRESHOLD = 0.7;

  /** Embedding dimension for nomic-embed-text. */
  private static final int DEFAULT_DIMENSION = 768;

  private final int dimension;

  private SemanticSimilarityChecker(int dimension) {
    this.dimension = dimension;
  }

  /**
   * Creates a SemanticSimilarityChecker.
   *
   * <p>The native FFM embedding backend has been removed. This checker now always
   * operates in stub mode using word-overlap similarity.
   *
   * @return Configured checker in stub mode
   */
  public static SemanticSimilarityChecker create() {
    log.info("SemanticSimilarityChecker created in stub mode (native backend removed)");
    return new SemanticSimilarityChecker(DEFAULT_DIMENSION);
  }

  /**
   * Creates a SemanticSimilarityChecker with fallback to stub mode.
   *
   * <p>Always returns stub mode since native FFM backend was removed.
   *
   * @return Checker in stub mode
   */
  public static SemanticSimilarityChecker createWithFallback() {
    return create();
  }

  /**
   * Computes semantic similarity between two texts.
   *
   * @param text1 First text
   * @param text2 Second text
   * @return Similarity result
   */
  public SimilarityResult compare(String text1, String text2) {
    return compareStub(text1, text2);
  }

  /**
   * Computes similarity between generated text and reference text.
   *
   * @param generated AI-generated text (e.g., summary)
   * @param reference Reference text (e.g., original document or golden summary)
   * @param threshold Minimum similarity to pass
   * @return Similarity result with pass/fail
   */
  public SimilarityResult evaluate(String generated, String reference, double threshold) {
    SimilarityResult result = compare(generated, reference);
    return new SimilarityResult(
        result.similarity(),
        result.similarity() >= threshold,
        result.method(),
        result.error()
    );
  }

  /**
   * Returns true if the checker has a real embedding model available.
   */
  public boolean isAvailable() {
    return false;
  }

  /**
   * Returns the embedding dimension.
   */
  public int dimension() {
    return dimension;
  }

  private SimilarityResult compareStub(String text1, String text2) {
    // Deterministic fallback using character-level Jaccard similarity
    if (text1 == null || text2 == null) {
      return new SimilarityResult(0.0, false, "stub", "null input");
    }

    String norm1 = text1.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    String norm2 = text2.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();

    if (norm1.equals(norm2)) {
      return new SimilarityResult(1.0, true, "stub", null);
    }

    // Word-level Jaccard
    Set<String> words1 = new HashSet<>(Arrays.asList(norm1.split("\\s+")));
    Set<String> words2 = new HashSet<>(Arrays.asList(norm2.split("\\s+")));

    Set<String> intersection = new HashSet<>(words1);
    intersection.retainAll(words2);

    Set<String> union = new HashSet<>(words1);
    union.addAll(words2);

    double jaccard = union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    return new SimilarityResult(
        jaccard,
        jaccard >= DEFAULT_THRESHOLD,
        "stub-jaccard",
        "model unavailable, using word overlap"
    );
  }

  @Override
  public void close() {
    // No resources to release in stub mode
  }

  // === Result types ===

  /**
   * Result of semantic similarity comparison.
   */
  public record SimilarityResult(
      double similarity,
      boolean similar,
      String method,
      String error
  ) {
    /**
     * Returns true if texts are semantically similar.
     */
    public boolean isSimilar() {
      return similar;
    }

    /**
     * Returns true if comparison used real embeddings (not stub).
     */
    public boolean usedEmbedding() {
      return "embedding".equals(method);
    }

    /**
     * Returns similarity as percentage string.
     */
    public String similarityPercent() {
      return String.format("%.1f%%", similarity * 100);
    }
  }
}
