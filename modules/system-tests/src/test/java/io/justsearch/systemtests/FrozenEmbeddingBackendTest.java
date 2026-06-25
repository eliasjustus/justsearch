package io.justsearch.systemtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.aibackend.backend.BackendException;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.EmbeddingRequest;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.EmbeddingResult;
import io.justsearch.systemtests.corpus.FrozenEmbeddingBackend;
import io.justsearch.systemtests.corpus.FrozenEmbeddingBackend.UnknownTextException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for FrozenEmbeddingBackend.
 */
@DisplayName("FrozenEmbeddingBackend")
class FrozenEmbeddingBackendTest {

  @Nested
  @DisplayName("Builder API")
  class BuilderTests {

    @Test
    @DisplayName("Builder creates backend with specified dimension")
    void builderCreatesDimension() {
      FrozenEmbeddingBackend backend = FrozenEmbeddingBackend.builder()
          .dimension(512)
          .modelName("test-model")
          .build();

      assertEquals(512, backend.dimension());
      assertEquals(0, backend.vectorCount());
    }

    @Test
    @DisplayName("Builder adds vectors correctly")
    void builderAddsVectors() {
      List<Double> vector = List.of(0.1, 0.2, 0.3);

      FrozenEmbeddingBackend backend = FrozenEmbeddingBackend.builder()
          .dimension(3)
          .addVector("hello world", vector)
          .build();

      assertEquals(1, backend.vectorCount());
      assertTrue(backend.contains("hello world"));
      assertEquals(vector, backend.getVector("hello world"));
    }

    @Test
    @DisplayName("Builder adds vectors from float array")
    void builderAddsFloatVectors() {
      float[] floatVector = {0.1f, 0.2f, 0.3f};

      FrozenEmbeddingBackend backend = FrozenEmbeddingBackend.builder()
          .dimension(3)
          .addVector("test", floatVector)
          .build();

      List<Double> result = backend.getVector("test");
      assertNotNull(result);
      assertEquals(3, result.size());
      assertEquals(0.1, result.get(0), 0.0001);
    }

    @Test
    @DisplayName("Builder adds vectors from double array")
    void builderAddsDoubleVectors() {
      double[] doubleVector = {0.5, 0.6, 0.7};

      FrozenEmbeddingBackend backend = FrozenEmbeddingBackend.builder()
          .dimension(3)
          .addVector("test", doubleVector)
          .build();

      List<Double> result = backend.getVector("test");
      assertNotNull(result);
      assertEquals(0.5, result.get(0), 0.0001);
    }
  }

  @Nested
  @DisplayName("Embedding Session")
  class SessionTests {

    @Test
    @DisplayName("Session returns correct embedding for known text")
    void sessionReturnsKnownEmbedding() throws BackendException {
      List<Double> expectedVector = List.of(0.1, 0.2, 0.3, 0.4);

      FrozenEmbeddingBackend backend = FrozenEmbeddingBackend.builder()
          .dimension(4)
          .addVector("hello", expectedVector)
          .strictMode(true)
          .build();

      try (var session = backend.createSession()) {
        EmbeddingRequest request = new EmbeddingRequest("hello", "en", 4, Map.of());
        EmbeddingResult result = session.embed(request);

        assertNotNull(result);
        assertFalse(result.degraded());
        assertEquals(expectedVector, result.vector());
        assertEquals(4, result.dimension());
        assertEquals("frozen", result.reason());
      }
    }

    @Test
    @DisplayName("Session throws on unknown text in strict mode")
    void sessionThrowsInStrictMode() throws BackendException {
      FrozenEmbeddingBackend backend = FrozenEmbeddingBackend.builder()
          .dimension(4)
          .addVector("known", List.of(0.1, 0.2, 0.3, 0.4))
          .strictMode(true)
          .build();

      try (var session = backend.createSession()) {
        EmbeddingRequest request = new EmbeddingRequest("unknown text", "en", 4, Map.of());
        assertThrows(UnknownTextException.class, () -> session.embed(request));
      }
    }

    @Test
    @DisplayName("Session returns zero vector in non-strict mode")
    void sessionReturnsZeroVectorNonStrict() throws BackendException {
      FrozenEmbeddingBackend backend = FrozenEmbeddingBackend.builder()
          .dimension(4)
          .strictMode(false)
          .build();

      try (var session = backend.createSession()) {
        EmbeddingRequest request = new EmbeddingRequest("unknown", "en", 4, Map.of());
        EmbeddingResult result = session.embed(request);

        assertNotNull(result);
        assertTrue(result.degraded());
        assertEquals("text_not_found", result.reason());

        // All zeros
        for (Double v : result.vector()) {
          assertEquals(0.0, v, 0.0001);
        }
      }
    }

    @Test
    @DisplayName("Session finds text with normalized lookup")
    void sessionFindsNormalizedText() throws BackendException {
      FrozenEmbeddingBackend backend = FrozenEmbeddingBackend.builder()
          .dimension(3)
          .addVector("Hello World", List.of(0.1, 0.2, 0.3))
          .strictMode(true)
          .build();

      try (var session = backend.createSession()) {
        // Should find via normalized lookup (lowercase, trimmed)
        EmbeddingRequest request = new EmbeddingRequest("  hello world  ", "en", 3, Map.of());
        EmbeddingResult result = session.embed(request);

        assertNotNull(result);
        assertFalse(result.degraded());
      }
    }
  }

  @Nested
  @DisplayName("Provenance")
  class ProvenanceTests {

    @Test
    @DisplayName("Provenance includes model name in modelFileSha256 field")
    void provenanceIncludesModelName() {
      FrozenEmbeddingBackend backend = FrozenEmbeddingBackend.builder()
          .modelName("test-nomic-embed")
          .dimension(768)
          .build();

      var provenance = backend.provenance();
      assertNotNull(provenance);
      // The frozen backend stores "frozen:modelName" in modelFileSha256
      assertTrue(provenance.modelFileSha256().contains("test-nomic-embed"),
          "Expected modelFileSha256 to contain model name, got: " + provenance.modelFileSha256());
      assertEquals("frozen", provenance.backend());
    }
  }

  @Nested
  @DisplayName("Unsupported Operations")
  class UnsupportedOperationsTests {

    @Test
    @DisplayName("translate throws BackendException")
    void translateThrows() {
      FrozenEmbeddingBackend backend = FrozenEmbeddingBackend.builder()
          .dimension(3)
          .build();

      assertThrows(BackendException.class, () -> backend.translate(null));
    }

    @Test
    @DisplayName("summarizeChunk throws BackendException")
    void summarizeChunkThrows() throws BackendException {
      FrozenEmbeddingBackend backend = FrozenEmbeddingBackend.builder()
          .dimension(3)
          .build();

      try (var session = backend.createSession()) {
        assertThrows(BackendException.class, () -> session.summarizeChunk(null));
      }
    }
  }

  @Nested
  @DisplayName("Int8 Quantization Simulation")
  class Int8QuantizationTests {

    @Test
    @DisplayName("Int8 simulation returns modified vectors")
    void int8SimulationModifiesVectors() throws BackendException {
      // Create vectors with values that will show quantization loss
      List<Double> originalVector = List.of(0.123456789, -0.987654321, 0.555555555);

      FrozenEmbeddingBackend withoutInt8 = FrozenEmbeddingBackend.builder()
          .dimension(3)
          .addVector("test", originalVector)
          .simulateInt8(false)
          .build();

      FrozenEmbeddingBackend withInt8 = FrozenEmbeddingBackend.builder()
          .dimension(3)
          .addVector("test", originalVector)
          .simulateInt8(true)
          .build();

      var request = new EmbeddingRequest("test", "en", 3, Map.of());

      EmbeddingResult resultWithout = withoutInt8.createSession().embed(request);
      EmbeddingResult resultWith = withInt8.createSession().embed(request);

      // Without Int8: exact values preserved
      assertEquals(originalVector.get(0), resultWithout.vector().get(0), 0.00001,
          "Without Int8, values should be exact");

      // With Int8: values should be quantized (rounded)
      // The quantized value will be slightly different due to Int8 round-trip
      assertFalse(Math.abs(originalVector.get(0) - resultWith.vector().get(0)) < 0.00001,
          "With Int8, values should differ due to quantization");

      // But similarity should still be preserved (cosine similarity > 0.99)
      double similarity = cosineSimilarity(
          resultWithout.vector().stream().mapToDouble(Double::doubleValue).toArray(),
          resultWith.vector().stream().mapToDouble(Double::doubleValue).toArray()
      );
      assertTrue(similarity > 0.99,
          "Int8 quantization should preserve vector direction, similarity=" + similarity);
    }

    @Test
    @DisplayName("Int8 simulation marks result as degraded")
    void int8SimulationMarksDegraded() throws BackendException {
      FrozenEmbeddingBackend backend = FrozenEmbeddingBackend.builder()
          .dimension(3)
          .addVector("test", List.of(0.1, 0.2, 0.3))
          .simulateInt8(true)
          .build();

      var result = backend.createSession().embed(new EmbeddingRequest("test", "en", 3, Map.of()));

      assertTrue(result.degraded(), "Int8 simulated result should be marked degraded");
      assertEquals("frozen_int8", result.reason(),
          "Reason should indicate Int8 mode");
    }

    @Test
    @DisplayName("Without Int8, result is not degraded")
    void withoutInt8NotDegraded() throws BackendException {
      FrozenEmbeddingBackend backend = FrozenEmbeddingBackend.builder()
          .dimension(3)
          .addVector("test", List.of(0.1, 0.2, 0.3))
          .simulateInt8(false)
          .build();

      var result = backend.createSession().embed(new EmbeddingRequest("test", "en", 3, Map.of()));

      assertFalse(result.degraded(), "Non-Int8 result should not be degraded");
      assertEquals("frozen", result.reason());
    }

    private double cosineSimilarity(double[] a, double[] b) {
      double dotProduct = 0.0;
      double normA = 0.0;
      double normB = 0.0;
      for (int i = 0; i < a.length; i++) {
        dotProduct += a[i] * b[i];
        normA += a[i] * a[i];
        normB += b[i] * b[i];
      }
      return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
  }
}
