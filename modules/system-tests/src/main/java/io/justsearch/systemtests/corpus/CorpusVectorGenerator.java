/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.corpus;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates and manages corpus vectors for the Golden Corpus.
 *
 * <p>This tool can:
 * <ul>
 *   <li>Generate deterministic test vectors with controlled similarity properties</li>
 *   <li>Generate vectors using a real embedding model (when available)</li>
 *   <li>Export vectors to JSON manifest format</li>
 *   <li>Compute cosine similarity between vectors</li>
 * </ul>
 *
 * <p><b>Deterministic Vector Generation:</b>
 * For testing without a real model, this class generates vectors with controlled properties:
 * <ul>
 *   <li>Similar texts get similar vectors (high cosine similarity)</li>
 *   <li>Dissimilar texts get orthogonal vectors (low cosine similarity)</li>
 * </ul>
 */
public final class CorpusVectorGenerator {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private final int dimension;
  private final Map<String, List<Double>> vectors = new HashMap<>();
  private final Map<String, String> textCategories = new HashMap<>();

  /**
   * Creates a new CorpusVectorGenerator with the specified dimension.
   *
   * @param dimension The embedding dimension (e.g., 768 for nomic-embed)
   */
  public CorpusVectorGenerator(int dimension) {
    this.dimension = dimension;
  }

  /**
   * Adds a text with a deterministic vector based on its category.
   *
   * <p>Texts in the same category will have similar vectors; texts in different
   * categories will have orthogonal vectors. This allows testing semantic similarity
   * without a real embedding model.
   *
   * @param text The text to add
   * @param category Category for similarity grouping (e.g., "database", "ml", "animals")
   * @param seed Seed for random variation within category
   */
  public void addText(String text, String category, int seed) {
    List<Double> vector = generateCategoryVector(category, seed);
    vectors.put(text, vector);
    textCategories.put(text, category);
  }

  /**
   * Adds a text with an explicit vector.
   *
   * @param text The text
   * @param vector The embedding vector
   */
  public void addText(String text, List<Double> vector) {
    if (vector.size() != dimension) {
      throw new IllegalArgumentException(
          "Vector dimension mismatch: expected " + dimension + ", got " + vector.size());
    }
    vectors.put(text, List.copyOf(vector));
  }

  /**
   * Generates a vector for a category with deterministic similarity properties.
   *
   * <p>The algorithm assigns each category a "base direction" in the vector space,
   * then adds controlled noise. Texts in the same category will have similar vectors
   * (high cosine similarity); different categories will be more orthogonal.
   *
   * @param category The category name
   * @param seed Seed for variation within the category
   * @return Normalized embedding vector
   */
  public List<Double> generateCategoryVector(String category, int seed) {
    // Use category hash to determine base direction
    int categoryHash = category.hashCode();
    Random categoryRng = new Random(categoryHash);

    // Generate base direction for this category
    double[] base = new double[dimension];
    for (int i = 0; i < dimension; i++) {
      base[i] = categoryRng.nextGaussian();
    }

    // Add seed-specific variation (smaller magnitude)
    Random seedRng = new Random(categoryHash + seed);
    for (int i = 0; i < dimension; i++) {
      base[i] += seedRng.nextGaussian() * 0.1;
    }

    // Normalize to unit vector
    return normalize(base);
  }

  /**
   * Generates a vector that is similar to existing vectors for the same category
   * but distinct (for testing recall with multiple relevant documents).
   */
  public List<Double> generateSimilarVector(String category, double similarity, int seed) {
    List<Double> categoryBase = generateCategoryVector(category, 0);

    // Generate a random orthogonal component
    Random rng = new Random(seed);
    double[] orthogonal = new double[dimension];
    for (int i = 0; i < dimension; i++) {
      orthogonal[i] = rng.nextGaussian();
    }

    // Project out the category direction to make it orthogonal
    double dot = 0;
    for (int i = 0; i < dimension; i++) {
      dot += orthogonal[i] * categoryBase.get(i);
    }
    for (int i = 0; i < dimension; i++) {
      orthogonal[i] -= dot * categoryBase.get(i);
    }

    // Combine: similarity * category + (1-similarity) * orthogonal
    double[] result = new double[dimension];
    double orthScale = Math.sqrt(1 - similarity * similarity);
    for (int i = 0; i < dimension; i++) {
      result[i] = similarity * categoryBase.get(i) + orthScale * orthogonal[i];
    }

    return normalize(result);
  }

  /**
   * Computes cosine similarity between two vectors.
   */
  public static double cosineSimilarity(List<Double> a, List<Double> b) {
    if (a.size() != b.size()) {
      throw new IllegalArgumentException("Vector dimensions must match");
    }

    double dot = 0, normA = 0, normB = 0;
    for (int i = 0; i < a.size(); i++) {
      dot += a.get(i) * b.get(i);
      normA += a.get(i) * a.get(i);
      normB += b.get(i) * b.get(i);
    }

    double denom = Math.sqrt(normA) * Math.sqrt(normB);
    return denom == 0 ? 0 : dot / denom;
  }

  /**
   * Returns the vector for a text, or null if not present.
   */
  public List<Double> getVector(String text) {
    return vectors.get(text);
  }

  /**
   * Returns all texts and their vectors.
   */
  public Map<String, List<Double>> getVectors() {
    return Map.copyOf(vectors);
  }

  /**
   * Exports the vectors to a JSON manifest file.
   *
   * @param outputPath Path to write the manifest
   * @param modelName Name of the model (for documentation)
   * @throws IOException if writing fails
   */
  public void exportManifest(Path outputPath, String modelName) throws IOException {
    var manifest = Map.of(
        "dimension", dimension,
        "model", modelName,
        "vectors", vectors
    );
    Files.writeString(outputPath, MAPPER.writeValueAsString(manifest));
  }

  /**
   * Creates a FrozenEmbeddingBackend from the current vectors.
   *
   * @param strictMode If true, throws on unknown text
   * @return Configured FrozenEmbeddingBackend
   */
  public FrozenEmbeddingBackend toBackend(boolean strictMode) {
    return new FrozenEmbeddingBackend(vectors, dimension, "generated", strictMode);
  }

  private List<Double> normalize(double[] vector) {
    double norm = 0;
    for (double v : vector) {
      norm += v * v;
    }
    double magnitude = Math.sqrt(norm);

    List<Double> result = new ArrayList<>(dimension);
    for (double v : vector) {
      result.add(magnitude == 0 ? 0 : v / magnitude);
    }
    return List.copyOf(result);
  }

  /**
   * Creates a standard Golden Corpus generator with predefined categories.
   *
   * <p>Categories:
   * <ul>
   *   <li>lexical_database - Documents with database keywords</li>
   *   <li>semantic_animals - Documents about animals/pets</li>
   *   <li>hybrid_ml - Documents about machine learning</li>
   * </ul>
   */
  public static CorpusVectorGenerator createGoldenCorpusGenerator(int dimension) {
    CorpusVectorGenerator gen = new CorpusVectorGenerator(dimension);

    // === LEXICAL TRUTH: Documents with exact keywords ===
    // These should rank high in BM25 but may not be semantically relevant

    // "database" category - lexical matches for DB queries
    gen.addText("database connection pooling jdbc performance", "database", 1);
    gen.addText("database connection pool configuration settings", "database", 2);
    gen.addText("sql database query optimization index", "database", 3);

    // === SEMANTIC TRUTH: Documents with synonyms but no keyword overlap ===
    // These should rank high in vector search but not BM25

    // "animals" category - semantic matches for pet/animal queries
    gen.addText("canine behavior training techniques for dogs", "animals", 1);
    gen.addText("puppy obedience lessons and rewards", "animals", 2);
    gen.addText("feline care tips for indoor cats", "animals", 3);

    // === HYBRID TRAP: Documents that rank medium in both ===
    // These should be boosted by RRF fusion

    // "ml" category - moderate matches for machine learning
    gen.addText("machine learning model optimization techniques", "ml", 1);
    gen.addText("neural network hyperparameter tuning guide", "ml", 2);
    gen.addText("deep learning training efficiency tips", "ml", 3);

    // Query vectors (what users might search for)
    gen.addText("database connection pooling", "database", 100);  // Lexical query
    gen.addText("dog training tips", "animals", 100);              // Semantic query (should match "canine")
    gen.addText("ml model tuning", "ml", 100);                    // Hybrid query

    return gen;
  }
}
