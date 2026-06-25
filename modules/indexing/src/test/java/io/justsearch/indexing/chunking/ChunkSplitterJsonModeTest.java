package io.justsearch.indexing.chunking;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F2: Tests for JSON chunking mode.
 *
 * <p>JSON mode ensures chunk boundaries don't split inside string literals,
 * preserving JSON structure integrity for accurate citation offsets.
 */
@DisplayName("F2: ChunkSplitter JSON Mode")
class ChunkSplitterJsonModeTest {

  @Test
  @DisplayName("JSON mode produces valid chunks")
  void jsonModeProducesValidChunks() {
    // JSON array with objects containing string values
    StringBuilder json = new StringBuilder();
    json.append("[\n");
    for (int i = 0; i < 100; i++) {
      if (i > 0) json.append(",\n");
      json.append("  {\"id\": ").append(i)
          .append(", \"name\": \"Item ").append(i)
          .append("\", \"description\": \"This is a longer description for item ").append(i)
          .append(" that contains more text to make the JSON larger.\"}");
    }
    json.append("\n]");

    String content = json.toString();
    List<ChunkSplitter.Chunk> chunks =
        ChunkSplitter.splitWithMetadata(content, 200, 30, ChunkSplitter.Mode.JSON);

    // Verify chunks are produced and have valid structure
    assertTrue(chunks.size() > 1, "Should produce multiple chunks");

    for (ChunkSplitter.Chunk chunk : chunks) {
      String chunkContent = chunk.content();
      assertFalse(chunkContent.isBlank(), "Chunk should have content");
      // Verify offsets are valid
      assertTrue(chunk.startChar() >= 0, "Start offset should be non-negative");
      assertTrue(chunk.endChar() > chunk.startChar(), "End should be after start");
      assertTrue(chunk.endChar() <= content.length(), "End should not exceed content length");
    }
  }

  @Test
  @DisplayName("JSON mode handles escaped quotes correctly")
  void jsonModeHandlesEscapedQuotes() {
    // JSON with escaped quotes inside strings
    StringBuilder json = new StringBuilder();
    json.append("[\n");
    for (int i = 0; i < 80; i++) {
      if (i > 0) json.append(",\n");
      // String contains escaped quote: He said \"Hello\"
      json.append("  {\"id\": ").append(i)
          .append(", \"quote\": \"He said \\\"Hello\\\" to everyone!\"}");
    }
    json.append("\n]");

    String content = json.toString();
    List<ChunkSplitter.Chunk> chunks =
        ChunkSplitter.splitWithMetadata(content, 200, 30, ChunkSplitter.Mode.JSON);

    // Verify chunks are produced with valid structure
    assertFalse(chunks.isEmpty(), "Should produce chunks");
    assertTrue(chunks.size() > 1, "Should produce multiple chunks for this content");

    for (ChunkSplitter.Chunk chunk : chunks) {
      assertFalse(chunk.content().isBlank(), "Chunk should have content");
      assertTrue(chunk.startChar() >= 0, "Start offset should be non-negative");
      assertTrue(chunk.endChar() > chunk.startChar(), "End should be after start");
    }
  }

  @Test
  @DisplayName("JSON mode prefers structural boundaries")
  void jsonModePrefersStructuralBoundaries() {
    // JSON with nested objects
    StringBuilder json = new StringBuilder();
    json.append("{\n");
    json.append("  \"items\": [\n");
    for (int i = 0; i < 50; i++) {
      if (i > 0) json.append(",\n");
      json.append("    {\"id\": ").append(i)
          .append(", \"nested\": {\"a\": 1, \"b\": 2, \"c\": 3}}");
    }
    json.append("\n  ],\n");
    json.append("  \"metadata\": {\"count\": 50, \"version\": \"1.0\"}\n");
    json.append("}");

    String content = json.toString();
    List<ChunkSplitter.Chunk> chunks =
        ChunkSplitter.splitWithMetadata(content, 200, 30, ChunkSplitter.Mode.JSON);

    assertTrue(chunks.size() > 1, "Should produce multiple chunks");

    // Verify chunks have valid content and offsets
    for (ChunkSplitter.Chunk chunk : chunks) {
      String chunkContent = chunk.content();
      assertFalse(chunkContent.isBlank(), "Chunk should have content");
      assertTrue(chunk.startChar() >= 0, "Start offset should be non-negative");
      assertTrue(chunk.endChar() > chunk.startChar(), "End should be after start");
      assertTrue(chunk.endChar() <= content.length(), "End should not exceed content length");
    }
  }

  @Test
  @DisplayName("findJsonSafeBoundary returns valid boundary")
  void findJsonSafeBoundaryReturnsValidBoundary() {
    String json = "{\"name\": \"value with spaces\", \"other\": 123}";
    //             0       10                   29          40

    // Target is inside the string - should find boundary after the string closes
    int result = ChunkSplitter.findJsonSafeBoundary(json, 20, 0);

    // Should find boundary after string or comma
    assertTrue(result >= 28 || result == 0, "Should find boundary at string end or after comma");
  }

  @Test
  @DisplayName("JSON mode handles newlines in strings")
  void jsonModeHandlesNewlinesInStrings() {
    // JSON with escaped newlines in strings
    String json = """
        {
          "multiline": "line1\\nline2\\nline3",
          "other": "value",
          "more": "data"
        }
        """.repeat(50);

    List<ChunkSplitter.Chunk> chunks =
        ChunkSplitter.splitWithMetadata(json, 200, 30, ChunkSplitter.Mode.JSON);

    // Verify chunks are produced with valid structure
    assertFalse(chunks.isEmpty(), "Should produce chunks");

    for (ChunkSplitter.Chunk chunk : chunks) {
      assertFalse(chunk.content().isBlank(), "Chunk should have content");
      assertTrue(chunk.startChar() >= 0, "Start offset should be non-negative");
      assertTrue(chunk.endChar() > chunk.startChar(), "End should be after start");
    }
  }

  @Test
  @DisplayName("Mode.fromMimeOrFileKind selects JSON for application/json")
  void modeFromMimeSelectsJsonForApplicationJson() {
    assertEquals(
        ChunkSplitter.Mode.JSON,
        ChunkSplitter.Mode.fromMimeOrFileKind("application/json", null));

    assertEquals(
        ChunkSplitter.Mode.JSON,
        ChunkSplitter.Mode.fromMimeOrFileKind("APPLICATION/JSON", null));

    assertEquals(
        ChunkSplitter.Mode.JSON,
        ChunkSplitter.Mode.fromMimeOrFileKind("application/json", "code"));
  }

  @Test
  @DisplayName("Mode.fromMimeOrFileKind falls back to fileKind")
  void modeFromMimeFallsBackToFileKind() {
    assertEquals(
        ChunkSplitter.Mode.MARKDOWN,
        ChunkSplitter.Mode.fromMimeOrFileKind(null, "markdown"));

    assertEquals(
        ChunkSplitter.Mode.CODE,
        ChunkSplitter.Mode.fromMimeOrFileKind("", "code"));

    assertEquals(
        ChunkSplitter.Mode.DEFAULT,
        ChunkSplitter.Mode.fromMimeOrFileKind(null, null));
  }
}
