package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.*;

import ai.djl.modality.nlp.DefaultVocabulary;
import ai.djl.modality.nlp.Vocabulary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the IDF-weighted SPLADE query encoder.
 *
 * <p>Uses {@code encodeFromTokenIds(long[])} to test the core logic without requiring a real
 * tokenizer. IDF table is written to a temp file for loading tests.
 */
@DisplayName("SpladeIdfQueryEncoder")
class SpladeIdfQueryEncoderTest {

  /**
   * Minimal vocabulary: indices 0-4 are BERT special tokens, 5-9 are real tokens. Matches the
   * SKIP_TOKEN_IDS filter in SpladeIdfQueryEncoder.
   */
  private static final List<String> VOCAB_TOKENS =
      List.of(
          "[PAD]", // 0
          "[unused1]", // 1
          "[unused2]", // 2
          "[unused3]", // 3
          "[unused4]", // 4
          "hello", // 5
          "world", // 6
          "search", // 7
          "engine", // 8
          "test" // 9
          );

  @TempDir Path tempDir;

  private Vocabulary vocabulary;

  @BeforeEach
  void setUp() {
    vocabulary =
        DefaultVocabulary.builder()
            .add(VOCAB_TOKENS)
            .optUnknownToken("[UNK]")
            .build();
  }

  /** Writes an IDF JSON file and creates an encoder using encodeFromTokenIds for testing. */
  private SpladeIdfQueryEncoder createEncoder(Map<String, Float> idfEntries) throws IOException {
    String json = toJson(idfEntries);
    Path idfPath = tempDir.resolve("idf.json");
    Files.writeString(idfPath, json);
    return new SpladeIdfQueryEncoder(idfPath, null, vocabulary);
  }

  private static String toJson(Map<String, Float> map) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (var entry : map.entrySet()) {
      if (!first) sb.append(",");
      sb.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
      first = false;
    }
    sb.append("}");
    return sb.toString();
  }

  @Nested
  @DisplayName("IDF table loading")
  class IdfLoading {

    @Test
    @DisplayName("loads valid IDF JSON file")
    void loadsValidIdfJson() throws IOException {
      SpladeIdfQueryEncoder encoder =
          createEncoder(
              Map.of("hello", 3.5f, "world", 2.1f, "search", 4.0f));

      // Verify encoding works (table was loaded)
      Map<String, Float> result = encoder.encodeFromTokenIds(new long[] {5});
      assertEquals(3.5f, result.get("hello"), 1e-5f);
    }

    @Test
    @DisplayName("throws on missing file")
    void throwsOnMissingFile() {
      Path missing = tempDir.resolve("nonexistent.json");
      assertThrows(
          IOException.class,
          () -> new SpladeIdfQueryEncoder(missing, null, vocabulary));
    }

    @Test
    @DisplayName("handles empty JSON object")
    void handlesEmptyJson() throws IOException {
      SpladeIdfQueryEncoder encoder = createEncoder(Map.of());

      Map<String, Float> result = encoder.encodeFromTokenIds(new long[] {5, 6, 7});
      assertTrue(result.isEmpty(), "Empty IDF table should produce empty results");
    }
  }

  @Nested
  @DisplayName("query encoding from token IDs")
  class QueryEncoding {

    @Test
    @DisplayName("single token returns its IDF weight")
    void singleTokenReturnsIdfWeight() throws IOException {
      SpladeIdfQueryEncoder encoder =
          createEncoder(Map.of("hello", 3.5f, "world", 2.1f));

      Map<String, Float> result = encoder.encodeFromTokenIds(new long[] {5});
      assertEquals(1, result.size());
      assertEquals(3.5f, result.get("hello"), 1e-5f);
    }

    @Test
    @DisplayName("multiple tokens return unique weights")
    void multipleTokensReturnUniqueWeights() throws IOException {
      SpladeIdfQueryEncoder encoder =
          createEncoder(
              Map.of("hello", 3.5f, "world", 2.1f, "search", 4.0f));

      Map<String, Float> result = encoder.encodeFromTokenIds(new long[] {5, 6, 7});
      assertEquals(3, result.size());
      assertEquals(3.5f, result.get("hello"), 1e-5f);
      assertEquals(2.1f, result.get("world"), 1e-5f);
      assertEquals(4.0f, result.get("search"), 1e-5f);
    }

    @Test
    @DisplayName("duplicate tokens are deduplicated")
    void duplicateTokensDeduped() throws IOException {
      SpladeIdfQueryEncoder encoder =
          createEncoder(Map.of("hello", 3.5f, "world", 2.1f));

      Map<String, Float> result = encoder.encodeFromTokenIds(new long[] {5, 5, 6});
      assertEquals(2, result.size(), "Duplicate token ID should not create duplicate entry");
    }

    @Test
    @DisplayName("special tokens are filtered")
    void specialTokensFiltered() throws IOException {
      SpladeIdfQueryEncoder encoder =
          createEncoder(
              Map.of("[PAD]", 1.0f, "hello", 3.5f));

      // IDs: 0=[PAD] (special), 5=hello (kept)
      Map<String, Float> result = encoder.encodeFromTokenIds(new long[] {0, 5});
      assertEquals(1, result.size());
      assertTrue(result.containsKey("hello"));
      assertFalse(result.containsKey("[PAD]"));
    }

    @Test
    @DisplayName("token not in IDF table is omitted")
    void unknownTokenOmitted() throws IOException {
      // IDF table has "hello" but not "world"
      SpladeIdfQueryEncoder encoder = createEncoder(Map.of("hello", 3.5f));

      Map<String, Float> result = encoder.encodeFromTokenIds(new long[] {5, 6});
      assertEquals(1, result.size());
      assertTrue(result.containsKey("hello"));
      assertFalse(result.containsKey("world"));
    }

    @Test
    @DisplayName("empty input returns empty map")
    void emptyInputReturnsEmptyMap() throws IOException {
      SpladeIdfQueryEncoder encoder =
          createEncoder(Map.of("hello", 3.5f));

      Map<String, Float> result = encoder.encodeFromTokenIds(new long[0]);
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("output is compatible with pruneByBeta")
    void outputCompatibleWithPruneByBeta() throws IOException {
      SpladeIdfQueryEncoder encoder =
          createEncoder(
              Map.of("hello", 4.0f, "world", 3.0f, "search", 2.0f, "engine", 1.0f));

      Map<String, Float> result = encoder.encodeFromTokenIds(new long[] {5, 6, 7, 8});
      assertEquals(4, result.size());

      // Should be compatible with the pruning function
      Map<String, Float> pruned = SpladeEncoder.pruneByBeta(result, 0.5f);
      assertEquals(2, pruned.size());
      assertTrue(pruned.containsKey("hello"));
      assertTrue(pruned.containsKey("world"));
    }
  }
}
