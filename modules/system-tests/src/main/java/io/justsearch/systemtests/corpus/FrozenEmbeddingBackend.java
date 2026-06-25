/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.corpus;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendException;
import io.justsearch.aibackend.backend.BackendRequest;
import io.justsearch.aibackend.backend.BackendResponse;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.EmbeddingRequest;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.EmbeddingResult;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.Provenance;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test-only embedding backend that returns pre-calculated vectors from a frozen manifest.
 *
 * <p>This backend enables fast, deterministic testing with <b>100% semantic fidelity</b> by
 * loading real embeddings that were pre-generated using llama.cpp (or another embedding model).
 *
 * <p><b>Behavior:</b>
 * <ul>
 *   <li>If a text matches a key in the manifest, returns the <b>exact</b> pre-calculated vector</li>
 *   <li>If a text is not found, throws {@link UnknownTextException} to enforce test coverage</li>
 *   <li>Supports both exact match and normalized match (lowercase, trimmed)</li>
 * </ul>
 *
 * <p><b>Manifest Format (JSON):</b>
 * <pre>{@code
 * {
 *   "dimension": 768,
 *   "model": "nomic-embed-text-v1.5",
 *   "vectors": {
 *     "database connection pooling": [0.123, -0.456, ...],
 *     "machine learning optimization": [0.789, 0.012, ...],
 *     ...
 *   }
 * }
 * }</pre>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * FrozenEmbeddingBackend backend = FrozenEmbeddingBackend.load(Path.of("corpus_vectors.json"));
 * EmbeddingResult result = backend.createSession().embed(
 *     new EmbeddingRequest("database connection pooling", "en", 768, Map.of())
 * );
 * }</pre>
 */
public final class FrozenEmbeddingBackend implements AiBackend {
  private static final Logger log = LoggerFactory.getLogger(FrozenEmbeddingBackend.class);
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final Map<String, List<Double>> vectors;
  private final Map<String, List<Double>> normalizedVectors;
  private final int dimension;
  private final String modelName;
  private final Provenance provenance;
  private final boolean strictMode;
  private final boolean simulateInt8;  // Simulate Int8 quantization loss

  /**
   * Creates a FrozenEmbeddingBackend with the specified vectors.
   *
   * @param vectors Map of text to embedding vector
   * @param dimension Expected embedding dimension
   * @param modelName Name of the model that generated the embeddings
   * @param strictMode If true, throws on unknown text; if false, returns zero vector
   */
  public FrozenEmbeddingBackend(
      Map<String, List<Double>> vectors,
      int dimension,
      String modelName,
      boolean strictMode) {
    this(vectors, dimension, modelName, strictMode, false);
  }

  /**
   * Creates a FrozenEmbeddingBackend with the specified vectors and Int8 simulation.
   *
   * @param vectors Map of text to embedding vector
   * @param dimension Expected embedding dimension
   * @param modelName Name of the model that generated the embeddings
   * @param strictMode If true, throws on unknown text; if false, returns zero vector
   * @param simulateInt8 If true, simulates Int8 quantization loss (like Lucene's codec)
   */
  public FrozenEmbeddingBackend(
      Map<String, List<Double>> vectors,
      int dimension,
      String modelName,
      boolean strictMode,
      boolean simulateInt8) {
    this.vectors = Map.copyOf(vectors);
    this.dimension = dimension;
    this.modelName = modelName;
    this.strictMode = strictMode;
    this.simulateInt8 = simulateInt8;
    this.provenance = new Provenance("frozen:" + modelName, "frozen", 0);

    // Build normalized lookup map
    this.normalizedVectors = new HashMap<>();
    for (var entry : vectors.entrySet()) {
      String normalized = normalize(entry.getKey());
      normalizedVectors.put(normalized, entry.getValue());
    }

    log.info("FrozenEmbeddingBackend loaded: {} vectors, dimension={}, model={}, simulateInt8={}",
        vectors.size(), dimension, modelName, simulateInt8);
  }

  /**
   * Loads a FrozenEmbeddingBackend from a JSON manifest file.
   *
   * @param manifestPath Path to the JSON manifest
   * @return Configured FrozenEmbeddingBackend
   * @throws IOException if the file cannot be read
   */
  public static FrozenEmbeddingBackend load(Path manifestPath) throws IOException {
    return load(manifestPath, true);
  }

  /**
   * Loads a FrozenEmbeddingBackend from a JSON manifest file.
   *
   * @param manifestPath Path to the JSON manifest
   * @param strictMode If true, throws on unknown text
   * @return Configured FrozenEmbeddingBackend
   * @throws IOException if the file cannot be read
   */
  public static FrozenEmbeddingBackend load(Path manifestPath, boolean strictMode) throws IOException {
    try (InputStream is = Files.newInputStream(manifestPath)) {
      return load(is, strictMode);
    }
  }

  /**
   * Loads a FrozenEmbeddingBackend from a JSON input stream.
   *
   * @param inputStream Input stream containing JSON manifest
   * @param strictMode If true, throws on unknown text
   * @return Configured FrozenEmbeddingBackend
   * @throws IOException if the stream cannot be read
   */
  public static FrozenEmbeddingBackend load(InputStream inputStream, boolean strictMode) throws IOException {
    var manifest = MAPPER.readValue(inputStream, new TypeReference<FrozenManifest>() {});
    return new FrozenEmbeddingBackend(
        manifest.vectors(),
        manifest.dimension(),
        manifest.model(),
        strictMode
    );
  }

  /**
   * Loads a FrozenEmbeddingBackend from a classpath resource.
   *
   * @param resourcePath Resource path (e.g., "/corpus/vectors.json")
   * @param strictMode If true, throws on unknown text
   * @return Configured FrozenEmbeddingBackend
   */
  public static FrozenEmbeddingBackend loadResource(String resourcePath, boolean strictMode) {
    return loadResource(resourcePath, strictMode, false);
  }

  /**
   * Loads a FrozenEmbeddingBackend from a classpath resource with Int8 simulation option.
   *
   * @param resourcePath Resource path (e.g., "/corpus/vectors.json")
   * @param strictMode If true, throws on unknown text
   * @param simulateInt8 If true, simulates Int8 quantization loss
   * @return Configured FrozenEmbeddingBackend
   */
  public static FrozenEmbeddingBackend loadResource(
      String resourcePath, boolean strictMode, boolean simulateInt8) {
    try (InputStream is = FrozenEmbeddingBackend.class.getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IllegalArgumentException("Resource not found: " + resourcePath);
      }
      var manifest = MAPPER.readValue(is, new TypeReference<FrozenManifest>() {});
      return new FrozenEmbeddingBackend(
          manifest.vectors(),
          manifest.dimension(),
          manifest.model(),
          strictMode,
          simulateInt8
      );
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load resource: " + resourcePath, e);
    }
  }

  /**
   * Creates a builder for programmatically constructing a FrozenEmbeddingBackend.
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public BackendResponse translate(BackendRequest request) throws BackendException {
    throw new BackendException("FrozenEmbeddingBackend does not support translation");
  }

  @Override
  public Session createSession() throws BackendException {
    return new FrozenSession();
  }

  @Override
  public Provenance provenance() {
    return provenance;
  }

  @Override
  public void close() throws BackendException {
    // No resources to clean up
  }

  /**
   * Returns the number of vectors in this backend.
   */
  public int vectorCount() {
    return vectors.size();
  }

  /**
   * Returns the embedding dimension.
   */
  public int dimension() {
    return dimension;
  }

  /**
   * Returns true if this backend contains an embedding for the given text.
   */
  public boolean contains(String text) {
    return vectors.containsKey(text) || normalizedVectors.containsKey(normalize(text));
  }

  /**
   * Returns the embedding for the given text, or null if not found.
   */
  public List<Double> getVector(String text) {
    List<Double> exact = vectors.get(text);
    if (exact != null) {
      return exact;
    }
    return normalizedVectors.get(normalize(text));
  }

  private String normalize(String text) {
    return text == null ? "" : text.toLowerCase(Locale.ROOT).strip();
  }

  private List<Double> zeroVector() {
    Double[] zeros = new Double[dimension];
    java.util.Arrays.fill(zeros, 0.0);
    return List.of(zeros);
  }

  /**
   * Simulates Int8 scalar quantization loss as used by Lucene's HNSW codec.
   *
   * <p>This mimics what happens when vectors are stored with Int8 quantization:
   * <ol>
   *   <li>Float32 values are scaled to fit in [-127, 127]</li>
   *   <li>Values are rounded to nearest integer (quantization loss)</li>
   *   <li>Values are scaled back to Float32 range</li>
   * </ol>
   *
   * <p>This introduces precision loss that can affect similarity calculations,
   * especially for vectors with small differences.
   *
   * @param vector The original Float32 vector
   * @return The vector after simulated Int8 quantization round-trip
   */
  private List<Double> quantizeToInt8(List<Double> vector) {
    if (!simulateInt8) {
      return vector;
    }

    // Find min/max for scaling (like Lucene's scalar quantization)
    double min = Double.MAX_VALUE;
    double max = Double.MIN_VALUE;
    for (Double v : vector) {
      if (v < min) min = v;
      if (v > max) max = v;
    }

    double range = max - min;
    if (range == 0) {
      return vector;  // All zeros or constant vector
    }

    // Quantize: scale to [0, 255], round, scale back
    // This simulates 8-bit unsigned quantization
    Double[] quantized = new Double[vector.size()];
    for (int i = 0; i < vector.size(); i++) {
      double normalized = (vector.get(i) - min) / range;  // [0, 1]
      int int8Value = (int) Math.round(normalized * 255);  // [0, 255]
      int8Value = Math.max(0, Math.min(255, int8Value));   // Clamp
      quantized[i] = (int8Value / 255.0) * range + min;    // Back to original range
    }

    return List.of(quantized);
  }

  /**
   * Session implementation that returns frozen embeddings.
   */
  private class FrozenSession implements Session {

    @Override
    public BackendResponse translate(BackendRequest request) throws BackendException {
      throw new BackendException("FrozenEmbeddingBackend does not support translation");
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) throws BackendException {
      String text = request.text();
      List<Double> vector = getVector(text);

      if (vector != null) {
        // Apply Int8 quantization if enabled (simulates Lucene codec loss)
        List<Double> resultVector = quantizeToInt8(vector);
        return new EmbeddingResult(
            resultVector,
            dimension,
            simulateInt8,  // degraded if quantized
            simulateInt8 ? "frozen_int8" : "frozen",
            Map.of("source", "frozen_manifest", "model", modelName,
                   "quantized", String.valueOf(simulateInt8))
        );
      }

      // Text not found in manifest
      if (strictMode) {
        throw new UnknownTextException(
            "Text not found in frozen manifest (strict mode enabled). " +
            "Add this text to the manifest or disable strict mode. Text: " + text
        );
      }

      // Non-strict mode: return zero vector with degraded flag
      log.warn("Text not found in frozen manifest, returning zero vector: {}", text);
      return new EmbeddingResult(
          zeroVector(),
          dimension,
          true,  // degraded
          "text_not_found",
          Map.of("source", "frozen_manifest", "missing_text", text)
      );
    }

    @Override
    public ChunkResponse summarizeChunk(ChunkRequest request) throws BackendException {
      throw new BackendException("FrozenEmbeddingBackend does not support summarization");
    }

    @Override
    public ReduceResponse reduceChunks(ReduceRequest request) throws BackendException {
      throw new BackendException("FrozenEmbeddingBackend does not support reduce");
    }
  }

  /**
   * Exception thrown when strict mode is enabled and an unknown text is requested.
   */
  public static class UnknownTextException extends BackendException {
    public UnknownTextException(String message) {
      super(message);
    }
  }

  /**
   * JSON manifest structure.
   */
  private record FrozenManifest(
      int dimension,
      String model,
      Map<String, List<Double>> vectors
  ) {}

  /**
   * Builder for programmatically constructing a FrozenEmbeddingBackend.
   */
  public static final class Builder {
    private final Map<String, List<Double>> vectors = new HashMap<>();
    private int dimension = 768;
    private String modelName = "test";
    private boolean strictMode = true;
    private boolean simulateInt8 = false;

    private Builder() {}

    /**
     * Sets the embedding dimension.
     */
    public Builder dimension(int dimension) {
      this.dimension = dimension;
      return this;
    }

    /**
     * Sets the model name.
     */
    public Builder modelName(String modelName) {
      this.modelName = Objects.requireNonNull(modelName);
      return this;
    }

    /**
     * Enables or disables strict mode.
     */
    public Builder strictMode(boolean strictMode) {
      this.strictMode = strictMode;
      return this;
    }

    /**
     * Enables Int8 quantization simulation.
     *
     * <p>When enabled, vectors are quantized to Int8 and back to Float32 before returning,
     * simulating the precision loss that occurs with Lucene's scalar-quantized HNSW codec.
     * This helps test that search relevance is maintained even with quantization.
     */
    public Builder simulateInt8(boolean simulateInt8) {
      this.simulateInt8 = simulateInt8;
      return this;
    }

    /**
     * Adds a vector for the given text.
     */
    public Builder addVector(String text, List<Double> vector) {
      vectors.put(Objects.requireNonNull(text), List.copyOf(vector));
      return this;
    }

    /**
     * Adds a vector from a float array.
     */
    public Builder addVector(String text, float[] vector) {
      List<Double> doubles = new java.util.ArrayList<>(vector.length);
      for (float f : vector) {
        doubles.add((double) f);
      }
      return addVector(text, doubles);
    }

    /**
     * Adds a vector from a double array.
     */
    public Builder addVector(String text, double[] vector) {
      List<Double> doubles = new java.util.ArrayList<>(vector.length);
      for (double d : vector) {
        doubles.add(d);
      }
      return addVector(text, doubles);
    }

    /**
     * Builds the FrozenEmbeddingBackend.
     */
    public FrozenEmbeddingBackend build() {
      return new FrozenEmbeddingBackend(vectors, dimension, modelName, strictMode, simulateInt8);
    }
  }
}
