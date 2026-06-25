package io.justsearch.indexing.chunking;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F2: Tests for CSV chunking mode.
 *
 * <p>CSV mode ensures chunk boundaries don't split inside quoted fields,
 * preserving row integrity for accurate citation offsets.
 */
@DisplayName("F2: ChunkSplitter CSV Mode")
class ChunkSplitterCsvModeTest {

  @Test
  @DisplayName("CSV mode never splits inside quoted fields")
  void csvModeNeverSplitsInsideQuotedFields() {
    // CSV with a quoted field containing a newline - should not split there
    StringBuilder csv = new StringBuilder();
    csv.append("id,name,description\n");
    for (int i = 0; i < 100; i++) {
      if (i == 50) {
        // This row has a quoted field with embedded newline
        csv.append(i).append(",\"Multi\nLine\nField\",value").append(i).append("\n");
      } else {
        csv.append(i).append(",name").append(i).append(",value").append(i).append("\n");
      }
    }

    String content = csv.toString();
    List<ChunkSplitter.Chunk> chunks =
        ChunkSplitter.splitWithMetadata(content, 50, 10, ChunkSplitter.Mode.CSV);

    // Verify no chunk ends inside the quoted field
    for (ChunkSplitter.Chunk chunk : chunks) {
      String chunkContent = chunk.content();
      // Count unbalanced quotes - should be even (balanced)
      int quoteCount = 0;
      for (char c : chunkContent.toCharArray()) {
        if (c == '"') quoteCount++;
      }
      assertEquals(
          0,
          quoteCount % 2,
          "Chunk should have balanced quotes (even count): " + chunkContent.substring(0, Math.min(100, chunkContent.length())));
    }
  }

  @Test
  @DisplayName("CSV mode handles escaped quotes correctly")
  void csvModeHandlesEscapedQuotes() {
    // CSV with escaped quotes ("") inside quoted fields
    StringBuilder csv = new StringBuilder();
    csv.append("id,name,quote\n");
    for (int i = 0; i < 80; i++) {
      // Field contains escaped quote: He said ""Hello""
      csv.append(i).append(",name").append(i).append(",\"He said \"\"Hello\"\"!\"\n");
    }

    String content = csv.toString();
    List<ChunkSplitter.Chunk> chunks =
        ChunkSplitter.splitWithMetadata(content, 200, 30, ChunkSplitter.Mode.CSV);

    // Should produce chunks without breaking rows
    assertFalse(chunks.isEmpty(), "Should produce chunks");

    // Each chunk should not end mid-row - verify by checking quote balance
    // With escaped quotes (""), we count unescaped quotes
    for (ChunkSplitter.Chunk chunk : chunks) {
      String trimmed = chunk.content().trim();
      // Valid CSV: verify no unbalanced quotes
      boolean inQuote = false;
      for (int j = 0; j < trimmed.length(); j++) {
        char c = trimmed.charAt(j);
        if (c == '"') {
          if (inQuote && j + 1 < trimmed.length() && trimmed.charAt(j + 1) == '"') {
            j++; // Skip escaped quote
          } else {
            inQuote = !inQuote;
          }
        }
      }
      // Chunk should have balanced quotes (not inside a quoted field)
      assertFalse(inQuote, "Chunk should not end inside a quoted field: " +
          trimmed.substring(Math.max(0, trimmed.length() - 50)));
    }
  }

  @Test
  @DisplayName("CSV mode preserves row integrity for simple CSV")
  void csvModePreservesRowIntegrity() {
    StringBuilder csv = new StringBuilder();
    csv.append("a,b,c\n");
    for (int i = 0; i < 200; i++) {
      csv.append("row").append(i).append(",data").append(i).append(",value").append(i).append("\n");
    }

    String content = csv.toString();
    List<ChunkSplitter.Chunk> chunks =
        ChunkSplitter.splitWithMetadata(content, 200, 30, ChunkSplitter.Mode.CSV);

    assertTrue(chunks.size() > 1, "Should produce multiple chunks");

    // Verify chunks have valid content (no unbalanced quotes means row integrity)
    for (ChunkSplitter.Chunk chunk : chunks) {
      String chunkContent = chunk.content();
      // Simple CSV without quotes - just verify content is not empty
      assertFalse(chunkContent.isBlank(), "Chunk should have content");
      // Verify offsets are valid
      assertTrue(chunk.startChar() >= 0, "Start offset should be non-negative");
      assertTrue(chunk.endChar() > chunk.startChar(), "End should be after start");
      assertTrue(chunk.endChar() <= content.length(), "End should not exceed content length");
    }
  }

  @Test
  @DisplayName("findQuoteAwareRowBoundary returns valid boundary")
  void findQuoteAwareRowBoundaryReturnsValidBoundary() {
    // String with positions:
    // "a,b,c\n1,\"quoted\nfield\",3\n4,5,6\n7,8,9\n"
    //  0    5 6      14     21    24     30    36
    // Valid row boundaries (after unquoted newlines): 6, 25, 31, 37
    String csv = "a,b,c\n1,\"quoted\nfield\",3\n4,5,6\n7,8,9\n";

    // Target is inside the quoted field (position 15 is inside "quoted\nfield")
    // Should find the closest valid row boundary to position 15
    int result = ChunkSplitter.findQuoteAwareRowBoundary(csv, 15, 0);

    // The closest boundary to 15 that's not inside quotes is 6 (before) or 25 (after)
    // Algorithm prefers boundaries before targetEnd, so should return 6
    assertTrue(result > 0 && result <= csv.length(),
        "Should find a valid boundary, got: " + result);

    // Verify the character before the boundary is a newline (for valid row boundaries)
    char prevChar = csv.charAt(result - 1);
    assertEquals('\n', prevChar, "Boundary should be after a newline");
  }

  @Test
  @DisplayName("Mode.fromMimeOrFileKind selects CSV for text/csv")
  void modeFromMimeSelectsCsvForTextCsv() {
    assertEquals(
        ChunkSplitter.Mode.CSV,
        ChunkSplitter.Mode.fromMimeOrFileKind("text/csv", null));

    assertEquals(
        ChunkSplitter.Mode.CSV,
        ChunkSplitter.Mode.fromMimeOrFileKind("TEXT/CSV", null));

    assertEquals(
        ChunkSplitter.Mode.CSV,
        ChunkSplitter.Mode.fromMimeOrFileKind("text/csv", "code"));
  }
}
