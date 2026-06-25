package io.justsearch.indexing.chunking;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChunkSplitter content-aware modes")
final class ChunkSplitterContentAwareTest {

  @Test
  @DisplayName("MARKDOWN mode avoids splitting inside fenced code blocks when possible")
  void markdownAvoidsSplittingInsideCodeFence() {
    String prefix = "Intro paragraph.\n\n" + "a ".repeat(90);
    String fence =
        "\n```js\n"
            + "console.log('hello');\n"
            + "console.log('world');\n"
            + "```\n";
    String suffix = "\nAfter the fence.\n\n" + "b ".repeat(120);
    String content = prefix + fence + suffix;

    List<ChunkSplitter.Chunk> chunks =
        ChunkSplitter.splitWithMetadata(content, 50, 0, ChunkSplitter.Mode.MARKDOWN);

    assertTrue(chunks.size() >= 2, "Expected multiple chunks");

    int totalFenceMarkers = 0;
    for (ChunkSplitter.Chunk c : chunks) {
      String chunk = c.content();
      int markers = countOccurrences(chunk, "```");
      totalFenceMarkers += markers;
      assertNotEquals(
          1,
          markers,
          "Chunk should not contain exactly one fence marker (split inside code fence):\n"
              + chunk);
    }
    assertEquals(2, totalFenceMarkers, "Expected exactly two ``` markers across all chunks");
  }

  @Test
  @DisplayName("CODE mode prefers newline boundaries (no mid-line chunk ends)")
  void codePrefersNewlineBoundaries() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      sb.append("public void method").append(i).append("() { int x = ").append(i).append("; }\n");
    }
    String content = sb.toString();

    List<ChunkSplitter.Chunk> chunks =
        ChunkSplitter.splitWithMetadata(content, 60, 10, ChunkSplitter.Mode.CODE);

    assertTrue(chunks.size() >= 2, "Expected multiple chunks");
    for (ChunkSplitter.Chunk c : chunks) {
      // Verify chunk ends at a line boundary by checking that:
      // 1. chunk.content() ends with '}' (complete statement), OR
      // 2. The character after endChar in original content is '\n' or end-of-string
      // Note: offsets now match trimmed content, so we check content structure
      String chunkContent = c.content();
      boolean endsWithStatement = chunkContent.endsWith("}");
      boolean atContentEnd = c.endChar() >= content.trim().length();
      boolean followedByNewline =
          c.endChar() < content.length() && content.charAt(c.endChar()) == '\n';

      assertTrue(
          endsWithStatement || atContentEnd || followedByNewline,
          "Expected chunk to end at line boundary. Content ends with: '"
              + chunkContent.substring(Math.max(0, chunkContent.length() - 10))
              + "'");
    }
  }

  private static int countOccurrences(String text, String needle) {
    if (text == null || text.isEmpty() || needle == null || needle.isEmpty()) {
      return 0;
    }
    int count = 0;
    int idx = 0;
    while (true) {
      int next = text.indexOf(needle, idx);
      if (next < 0) break;
      count++;
      idx = next + needle.length();
    }
    return count;
  }
}
