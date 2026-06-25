package io.justsearch.indexerworker.rag;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.chunking.ChunkSplitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F2: Integration tests for ChunkDocumentWriter mode selection with CSV and JSON.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>CSV mode is selected for text/csv MIME type</li>
 *   <li>JSON mode is selected for application/json MIME type</li>
 *   <li>Chunk offsets remain stable and correct with structured modes</li>
 * </ul>
 */
@DisplayName("F2: ChunkDocumentWriter Structured Modes")
class ChunkDocumentWriterStructuredModesTest {

  @Test
  @DisplayName("CSV mode selected for text/csv MIME type")
  void csvModeSelectedForTextCsvMime() {
    ChunkSplitter.Mode mode = ChunkSplitter.Mode.fromMimeOrFileKind("text/csv", null);
    assertEquals(ChunkSplitter.Mode.CSV, mode);
  }

  @Test
  @DisplayName("CSV mode selected for text/csv MIME type (case insensitive)")
  void csvModeSelectedForTextCsvMimeCaseInsensitive() {
    ChunkSplitter.Mode mode = ChunkSplitter.Mode.fromMimeOrFileKind("TEXT/CSV", "code");
    assertEquals(ChunkSplitter.Mode.CSV, mode);
  }

  @Test
  @DisplayName("JSON mode selected for application/json MIME type")
  void jsonModeSelectedForApplicationJsonMime() {
    ChunkSplitter.Mode mode = ChunkSplitter.Mode.fromMimeOrFileKind("application/json", null);
    assertEquals(ChunkSplitter.Mode.JSON, mode);
  }

  @Test
  @DisplayName("JSON mode selected for application/json MIME type (case insensitive)")
  void jsonModeSelectedForApplicationJsonMimeCaseInsensitive() {
    ChunkSplitter.Mode mode = ChunkSplitter.Mode.fromMimeOrFileKind("APPLICATION/JSON", "markdown");
    assertEquals(ChunkSplitter.Mode.JSON, mode);
  }

  @Test
  @DisplayName("MIME type takes precedence over fileKind")
  void mimeTypeTakesPrecedenceOverFileKind() {
    // CSV MIME should override code fileKind
    ChunkSplitter.Mode csvMode = ChunkSplitter.Mode.fromMimeOrFileKind("text/csv", "code");
    assertEquals(ChunkSplitter.Mode.CSV, csvMode);

    // JSON MIME should override markdown fileKind
    ChunkSplitter.Mode jsonMode = ChunkSplitter.Mode.fromMimeOrFileKind("application/json", "markdown");
    assertEquals(ChunkSplitter.Mode.JSON, jsonMode);
  }

  @Test
  @DisplayName("Falls back to fileKind when MIME not recognized")
  void fallsBackToFileKindWhenMimeNotRecognized() {
    ChunkSplitter.Mode markdownMode = ChunkSplitter.Mode.fromMimeOrFileKind("text/plain", "markdown");
    assertEquals(ChunkSplitter.Mode.MARKDOWN, markdownMode);

    ChunkSplitter.Mode codeMode = ChunkSplitter.Mode.fromMimeOrFileKind("application/octet-stream", "code");
    assertEquals(ChunkSplitter.Mode.CODE, codeMode);
  }

  @Test
  @DisplayName("Falls back to DEFAULT when no MIME or fileKind")
  void fallsBackToDefaultWhenNoMimeOrFileKind() {
    ChunkSplitter.Mode mode = ChunkSplitter.Mode.fromMimeOrFileKind(null, null);
    assertEquals(ChunkSplitter.Mode.DEFAULT, mode);

    ChunkSplitter.Mode mode2 = ChunkSplitter.Mode.fromMimeOrFileKind("", "");
    assertEquals(ChunkSplitter.Mode.DEFAULT, mode2);
  }

  @Test
  @DisplayName("CSV chunking preserves stable offsets for citations")
  void csvChunkingPreservesStableOffsets() {
    StringBuilder csv = new StringBuilder();
    csv.append("id,name,value\n");
    for (int i = 0; i < 100; i++) {
      csv.append(i).append(",name").append(i).append(",value").append(i).append("\n");
    }

    String content = csv.toString();
    var chunks = ChunkSplitter.splitWithMetadata(content, 100, 20, ChunkSplitter.Mode.CSV);

    assertTrue(chunks.size() > 1, "Should produce multiple chunks");

    // Verify offsets are correct
    for (var chunk : chunks) {
      int start = chunk.startChar();
      int end = chunk.endChar();

      assertTrue(start >= 0, "Start offset should be non-negative");
      assertTrue(end > start, "End offset should be greater than start");
      assertTrue(end <= content.length(), "End offset should not exceed content length");

      // Verify content matches offsets (trimmed comparison)
      String expectedContent = content.substring(start, end).trim();
      assertEquals(expectedContent, chunk.content(), "Chunk content should match offset range (trimmed)");
    }
  }

  @Test
  @DisplayName("JSON chunking preserves stable offsets for citations")
  void jsonChunkingPreservesStableOffsets() {
    StringBuilder json = new StringBuilder();
    json.append("[\n");
    for (int i = 0; i < 50; i++) {
      if (i > 0) json.append(",\n");
      json.append("  {\"id\": ").append(i)
          .append(", \"name\": \"Item ").append(i)
          .append("\", \"data\": [1, 2, 3, 4, 5]}");
    }
    json.append("\n]");

    String content = json.toString();
    var chunks = ChunkSplitter.splitWithMetadata(content, 100, 20, ChunkSplitter.Mode.JSON);

    assertTrue(chunks.size() > 1, "Should produce multiple chunks");

    // Verify offsets are correct
    for (var chunk : chunks) {
      int start = chunk.startChar();
      int end = chunk.endChar();

      assertTrue(start >= 0, "Start offset should be non-negative");
      assertTrue(end > start, "End offset should be greater than start");
      assertTrue(end <= content.length(), "End offset should not exceed content length");

      // Verify content matches offsets (trimmed comparison)
      String expectedContent = content.substring(start, end).trim();
      assertEquals(expectedContent, chunk.content(), "Chunk content should match offset range (trimmed)");
    }
  }
}
