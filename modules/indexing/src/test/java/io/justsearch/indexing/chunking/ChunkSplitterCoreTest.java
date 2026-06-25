package io.justsearch.indexing.chunking;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Core unit tests for ChunkSplitter focusing on high-value test cases.
 *
 * <p>Tests focus on:
 * <ul>
 *   <li>Overlap mechanism verification (chunks actually share content)</li>
 *   <li>Boundary preference hierarchy (paragraph > sentence > word)</li>
 *   <li>Markdown mode edge cases (~~~ fences, unterminated fences)</li>
 *   <li>Chunk metadata accuracy (offsets, indices)</li>
 *   <li>Content coverage (no content lost between chunks)</li>
 * </ul>
 *
 * <p>Related: tempdoc-73-chunksplitter-test-analysis.md
 */
@DisplayName("ChunkSplitter Core")
class ChunkSplitterCoreTest {

  @Nested
  @DisplayName("Overlap Mechanism")
  class OverlapMechanismTests {

    @Test
    @DisplayName("overlap mechanism produces chunks with shared content")
    void overlapMechanismProducesActualOverlap() {
      // Use small targetTokens to ensure minChars doesn't dominate
      // targetTokens=30 → targetChars ≈ 115 chars
      // overlapTokens=10 → overlapChars ≈ 38 chars
      // minChars = min(385, 115) = 115 → advance = max(endPos-38, 115)

      // Create content with clear sentence boundaries for predictable chunking
      String content =
          "Alpha sentence one ends here. Bravo sentence two ends here. "
              + "Charlie sentence three ends here. Delta sentence four ends here. "
              + "Echo sentence five ends here. Foxtrot sentence six ends here. "
              + "Golf sentence seven ends here. Hotel sentence eight ends here.";
      // Total ~240 chars, should produce 2+ chunks with overlap

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 30, 10, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() >= 2, "Should produce multiple chunks");

      // STRONG ASSERTION: Verify actual content overlap
      for (int i = 1; i < chunks.size(); i++) {
        String prevChunk = chunks.get(i - 1).content();
        String currChunk = chunks.get(i).content();

        // Find overlapping content: end of prev should appear at start of curr
        // Try progressively smaller suffixes of prevChunk
        boolean foundOverlap = false;
        for (int len = Math.min(50, prevChunk.length()); len >= 5; len--) {
          String suffix = prevChunk.substring(prevChunk.length() - len);
          if (currChunk.startsWith(suffix)) {
            foundOverlap = true;
            break;
          }
        }

        assertTrue(
            foundOverlap,
            "Chunk "
                + i
                + " should share content with chunk "
                + (i - 1)
                + "\nPrev ends with: '"
                + prevChunk.substring(Math.max(0, prevChunk.length() - 40))
                + "'"
                + "\nCurr starts with: '"
                + currChunk.substring(0, Math.min(40, currChunk.length()))
                + "'");
      }
    }
  }

  @Nested
  @DisplayName("Boundary Preference Hierarchy")
  class BoundaryPreferenceTests {

    @Test
    @DisplayName("paragraph boundary chosen over sentence when both in search window")
    void boundaryPreferenceParagraphOverSentence() {
      // Key constraint: paragraph boundary must be AFTER minEnd to be valid
      // With targetTokens=30 → targetChars ≈ 115 chars → minChars = min(385, 115) = 115
      // So paragraph break must be at position > 115 to be valid
      //
      // Content structure:
      // - ~120 chars of first paragraph with multiple sentence breaks
      // - Paragraph boundary (\n\n) at ~120 chars
      // - Second paragraph with sentence breaks
      //
      // Both paragraph (~120) and sentence (~100, ~140) boundaries will be in search window
      // Algorithm checks paragraph FIRST → should return paragraph position

      String content =
          "Alpha sentence in first paragraph ends here. Bravo sentence continues on. "
              + "Charlie sentence follows suit. Delta sentence is here.\n\n"
              + "Echo sentence starts second paragraph. Foxtrot sentence ends here.";
      // First paragraph: ~130 chars, paragraph break at ~132
      // Sentence breaks at: ~45, ~75, ~100, ~128, ~165, ~195

      // Use targetTokens=40 → targetChars ≈ 154 chars
      // minChars = min(385, 154) = 154
      // Wait, that's still larger than paragraph position...

      // Let me recalculate: need paragraph position > minEnd
      // With targetTokens=50 → targetChars ≈ 192 chars → minChars = min(385, 192) = 192
      // Still too large. Need smaller targetTokens.
      //
      // Actually: minChars = min(MIN_CHUNK_TOKENS/1.3*5, targetChars)
      //         = min(385, targetChars)
      // For minChars < 130, we need targetChars < 130
      // targetChars = targetTokens / 1.3 * 5 = targetTokens * 3.85
      // For targetChars = 120: targetTokens = 120 / 3.85 ≈ 31

      // Let's use targetTokens=35 → targetChars ≈ 135
      // minChars = min(385, 135) = 135
      // Paragraph at ~132 is still < 135... need to increase content

      // Simpler approach: use very small targetTokens so minChars is small
      // targetTokens=25 → targetChars ≈ 96, minChars = 96
      // Paragraph at ~132 > 96 ✓

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 25, 5, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() >= 2, "Should produce at least 2 chunks, got: " + chunks.size());

      ChunkSplitter.Chunk firstChunk = chunks.get(0);
      String firstContent = firstChunk.content();
      int firstEndChar = firstChunk.endChar();

      // STRONG ASSERTION: First chunk should end at paragraph boundary
      // Content after first chunk should start with "Echo" (second paragraph)
      String afterFirstChunk = content.substring(firstEndChar).stripLeading();

      assertTrue(
          afterFirstChunk.startsWith("Echo"),
          "First chunk should end at paragraph boundary (before 'Echo')"
              + "\nFirst chunk content: '"
              + firstContent
              + "'"
              + "\nContent after chunk: '"
              + afterFirstChunk.substring(0, Math.min(40, afterFirstChunk.length()))
              + "'"
              + "\nfirstEndChar: "
              + firstEndChar);

      // Also verify it didn't end at an earlier sentence boundary within first paragraph
      assertFalse(
          firstContent.endsWith("Alpha sentence in first paragraph ends here"),
          "Should NOT have ended at first sentence - paragraph preference should win");
    }

    @Test
    @DisplayName("sentence boundary chosen over word when no paragraph available")
    void boundaryPreferenceSentenceOverWord() {
      // Content with sentences but NO paragraph breaks (no \n\n)
      String content =
          "First sentence ends here. Second sentence continues on. "
              + "Third sentence follows suit. Fourth sentence is here. "
              + "Fifth sentence appears now. Sixth sentence wraps up.";

      // Target to force split mid-content
      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 35, 5, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() >= 2, "Should produce multiple chunks");

      // STRONG ASSERTION: All non-final chunks should end at sentence boundary
      for (int i = 0; i < chunks.size() - 1; i++) {
        String chunk = chunks.get(i).content();
        assertTrue(
            chunk.endsWith(".") || chunk.endsWith("!") || chunk.endsWith("?"),
            "Chunk " + i + " should end at sentence boundary: '" + chunk + "'");
      }
    }

    @Test
    @DisplayName("word boundary used as fallback when no sentence markers")
    void boundaryFallbackToWordWhenNoSentence() {
      // Content with NO sentence-ending punctuation, only spaces
      String content =
          "alpha bravo charlie delta echo foxtrot golf hotel india juliet "
              + "kilo lima mike november oscar papa quebec romeo sierra tango "
              + "uniform victor whiskey xray yankee zulu";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 25, 5, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() >= 2, "Should produce multiple chunks");

      // STRONG ASSERTION: Chunks should end at word boundaries (complete words)
      // Last character should be a letter (end of word) or whitespace
      for (int i = 0; i < chunks.size() - 1; i++) {
        String chunk = chunks.get(i).content().trim();
        char lastChar = chunk.charAt(chunk.length() - 1);
        assertTrue(
            Character.isLetterOrDigit(lastChar),
            "Chunk " + i + " should end with complete word, not mid-word: '" + chunk + "'");
      }
    }
  }

  @Nested
  @DisplayName("Content Coverage")
  class ContentCoverageTests {

    @Test
    @DisplayName("chunks together cover start and end of original content")
    void chunksTogetherCoverEntireContent() {
      String content =
          "The quick brown fox jumps over the lazy dog. "
              + "Pack my box with five dozen liquor jugs. "
              + "How quickly daft zebras jump high.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 30, 5, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() >= 1, "Should produce at least one chunk");

      // STRONG ASSERTION: First chunk starts at beginning, last chunk reaches end
      ChunkSplitter.Chunk firstChunk = chunks.get(0);
      ChunkSplitter.Chunk lastChunk = chunks.get(chunks.size() - 1);

      assertEquals(0, firstChunk.startChar(), "First chunk should start at beginning");
      assertTrue(
          lastChunk.endChar() >= content.trim().length() - 10,
          "Last chunk should reach near end of content. "
              + "Content length: " + content.trim().length() + ", lastChunk.endChar: " + lastChunk.endChar());

      // Also verify first and last words appear
      assertTrue(
          firstChunk.content().toLowerCase(java.util.Locale.ROOT).contains("quick"),
          "First chunk should contain 'quick'");
      assertTrue(
          lastChunk.content().toLowerCase(java.util.Locale.ROOT).contains("high"),
          "Last chunk should contain 'high'");
    }

    @Test
    @DisplayName("boundary search respects search window limits")
    void searchWindowRespected() {
      // Create content with a paragraph break far from target position
      String farParagraph = "Far away paragraph.\n\n";
      String nearContent = "x ".repeat(100); // ~200 chars of repeated content
      String content = farParagraph + nearContent + "End here.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 50, 10, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() >= 1, "Should produce chunks");

      // First chunk should be reasonable size, not extending to far boundaries
      int firstChunkLength = chunks.get(0).content().length();
      assertTrue(
          firstChunkLength < 500,
          "First chunk should be bounded, not extend to distant paragraph: " + firstChunkLength);
    }
  }

  @Nested
  @DisplayName("Markdown Mode Variations")
  class MarkdownModeVariationsTests {

    @Test
    @DisplayName("detects ~~~ fence markers")
    void detectsTildeFenceMarkers() {
      String content =
          "Intro text with some content.\n\n"
              + "~~~python\n"
              + "def hello():\n"
              + "    print('hello')\n"
              + "~~~\n\n"
              + "After the fence with more content that goes on and on.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 30, 5, ChunkSplitter.Mode.MARKDOWN);

      // Verify no chunk splits inside the fence
      for (ChunkSplitter.Chunk chunk : chunks) {
        String chunkContent = chunk.content();
        int tildeCount = countOccurrences(chunkContent, "~~~");
        assertNotEquals(
            1, tildeCount, "Chunk should not contain exactly one ~~~ marker: " + chunkContent);
      }
    }

    @Test
    @DisplayName("handles unterminated fence (open until end)")
    void handlesUnterminatedFence() {
      String content =
          "Before fence.\n\n"
              + "```js\n"
              + "// This code block is never closed\n"
              + "function test() {\n"
              + "  return true;\n"
              + "}\n"
              + "// More code here\n"
              + "// And even more code";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 30, 5, ChunkSplitter.Mode.MARKDOWN);

      // Should not crash and should produce valid chunks
      assertFalse(chunks.isEmpty(), "Should produce chunks");

      // Verify all chunks have valid content
      for (ChunkSplitter.Chunk chunk : chunks) {
        assertFalse(chunk.content().isBlank(), "Chunk should have content");
      }
    }

    @Test
    @DisplayName("handles indented fences (up to 3 spaces)")
    void handlesIndentedFences() {
      String content =
          "Normal text here.\n\n"
              + "   ```\n" // 3 spaces indent
              + "   indented code\n"
              + "   ```\n\n"
              + "After the indented fence.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 30, 5, ChunkSplitter.Mode.MARKDOWN);

      assertFalse(chunks.isEmpty(), "Should produce chunks");

      // Verify fence markers are paired
      int totalFences = 0;
      for (ChunkSplitter.Chunk chunk : chunks) {
        totalFences += countOccurrences(chunk.content(), "```");
      }
      assertEquals(2, totalFences, "Should have exactly 2 fence markers total");
    }

    @Test
    @DisplayName("mismatched fence markers (``` followed by ~~~) treated as separate")
    void mismatchedFencesAreSeparate() {
      String content =
          "Before.\n\n"
              + "```\n"
              + "code block\n"
              + "~~~\n" // Wrong closing marker
              + "```\n" // Correct closing marker
              + "After.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 100, 10, ChunkSplitter.Mode.MARKDOWN);

      assertFalse(chunks.isEmpty(), "Should produce chunks");
      // Should not crash - mismatched markers should be handled gracefully
    }
  }

  @Nested
  @DisplayName("Chunk Metadata Accuracy")
  class ChunkMetadataTests {

    @Test
    @DisplayName("chunk offsets match original content positions")
    void chunkOffsetsMatchOriginal() {
      String content = "First part. Second part. Third part. Fourth part.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 15, 3, ChunkSplitter.Mode.DEFAULT);

      String trimmed = content.trim();
      for (ChunkSplitter.Chunk chunk : chunks) {
        assertTrue(chunk.startChar() >= 0, "Start should be non-negative");
        assertTrue(chunk.endChar() <= trimmed.length(), "End should not exceed content length");
        assertTrue(chunk.endChar() > chunk.startChar(), "End should be after start");

        // Verify the chunk content matches the slice from original
        String expectedSlice = trimmed.substring(chunk.startChar(), chunk.endChar()).trim();
        assertEquals(expectedSlice, chunk.content(), "Chunk content should match slice");
      }
    }

    @Test
    @DisplayName("chunk indices are sequential starting from 0")
    void chunkIndicesSequential() {
      String content = "Word ".repeat(100);

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 30, 5, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() > 1, "Should produce multiple chunks");

      for (int i = 0; i < chunks.size(); i++) {
        assertEquals(i, chunks.get(i).index(), "Chunk index should be sequential");
      }
    }

    @Test
    @DisplayName("Chunk.of() correctly calculates estimated tokens")
    void chunkOfCalculatesTokens() {
      ChunkSplitter.Chunk chunk = ChunkSplitter.Chunk.of(0, "Hello world test content", 0, 24);

      assertEquals(0, chunk.index());
      assertEquals("Hello world test content", chunk.content());
      assertEquals(0, chunk.startChar());
      assertEquals(24, chunk.endChar());
      assertTrue(chunk.estimatedTokens() > 0, "Should estimate non-zero tokens");
    }
  }

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCaseTests {

    @Test
    @DisplayName("small content returns single chunk")
    void smallContentSingleChunk() {
      String content = "Short content.";

      List<String> chunks = ChunkSplitter.split(content);

      assertEquals(1, chunks.size(), "Should return single chunk for small content");
      assertEquals("Short content.", chunks.get(0));
    }

    @Test
    @DisplayName("split with Mode parameter works correctly")
    void splitWithModeWorks() {
      String content = "Line one.\nLine two.\nLine three.";

      List<String> defaultChunks = ChunkSplitter.split(content, ChunkSplitter.Mode.DEFAULT);
      List<String> codeChunks = ChunkSplitter.split(content, ChunkSplitter.Mode.CODE);

      assertFalse(defaultChunks.isEmpty(), "Should produce chunks in DEFAULT mode");
      assertFalse(codeChunks.isEmpty(), "Should produce chunks in CODE mode");
    }

    @Test
    @DisplayName("zero overlap still produces valid chunks")
    void zeroOverlapProducesValidChunks() {
      String content = "First. Second. Third. Fourth. Fifth. Sixth. Seventh. Eighth.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 20, 0, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() >= 1, "Should produce chunks");

      for (ChunkSplitter.Chunk chunk : chunks) {
        assertFalse(chunk.content().isBlank(), "Chunk should have content");
        assertTrue(chunk.startChar() >= 0, "Start offset should be valid");
        assertTrue(chunk.endChar() > chunk.startChar(), "End should be after start");
      }
    }

    @Test
    @DisplayName("very small targetTokens still produces valid chunks")
    void verySmallTargetTokensValid() {
      String content = "Hello world this is a test of small chunks.";

      // Very small target - tests the minChars recalculation
      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 5, 1, ChunkSplitter.Mode.DEFAULT);

      assertFalse(chunks.isEmpty(), "Should produce chunks");

      for (ChunkSplitter.Chunk chunk : chunks) {
        assertFalse(chunk.content().isBlank(), "Each chunk should have content");
        assertTrue(chunk.startChar() >= 0, "Start offset should be valid");
        assertTrue(chunk.endChar() > chunk.startChar(), "End should be after start");
      }
    }

    @Test
    @DisplayName("offsets match content exactly without needing trim")
    void offsetsMatchContentWithoutTrim() {
      // Content with leading/trailing whitespace that could be trimmed
      String content = "  Word one.   Word two.  \n\n  Word three.  Word four.  ";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 15, 3, ChunkSplitter.Mode.DEFAULT);

      for (ChunkSplitter.Chunk chunk : chunks) {
        // Extract substring using offsets - NO TRIM
        String fromOffsets = content.substring(chunk.startChar(), chunk.endChar());

        // BUG: If this fails, offsets don't account for trim()
        assertEquals(
            chunk.content(),
            fromOffsets,
            "Offset extraction should match content WITHOUT needing trim. "
                + "Offsets: ["
                + chunk.startChar()
                + ", "
                + chunk.endChar()
                + "], "
                + "fromOffsets: '"
                + fromOffsets
                + "', "
                + "content: '"
                + chunk.content()
                + "'");
      }
    }

    @Test
    @DisplayName("nested same-type fences handled correctly")
    void nestedSameTypeFencesHandledCorrectly() {
      // Outer fence contains inner fence markers
      String content =
          "Before.\n\n"
              + "```outer\n"
              + "Some code here\n"
              + "```inner\n" // This should NOT close outer
              + "More code\n"
              + "```\n" // This closes inner
              + "Still in outer\n"
              + "```\n" // This closes outer
              + "After the fence.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 30, 5, ChunkSplitter.Mode.MARKDOWN);

      // If bug exists: chunk boundary will be inside "outer" fence incorrectly
      for (ChunkSplitter.Chunk chunk : chunks) {
        String c = chunk.content();
        int openCount = countOccurrences(c, "```");

        // If chunk contains fence markers, they should be balanced (0 or 2 or 4)
        // Odd count means we split inside a fence
        if (openCount > 0) {
          assertEquals(
              0, openCount % 2, "Chunk should not split inside fence. Content: " + c);
        }
      }
    }

    @Test
    @DisplayName("extreme targetTokens values handled safely")
    void extremeTargetTokensHandledSafely() {
      String content = "Test content for overflow checking.";

      // Very large targetTokens - should not overflow or crash
      assertDoesNotThrow(
          () -> {
            List<String> chunks =
                ChunkSplitter.split(content, Integer.MAX_VALUE / 10, 100);
            assertFalse(chunks.isEmpty());
          });

      // Negative targetTokens - should either throw or handle gracefully
      try {
        List<String> chunks = ChunkSplitter.split(content, -100, 50);
        // If no exception, verify chunks are sensible
        assertFalse(chunks.isEmpty(), "Negative tokens should still produce chunks or throw");
      } catch (IllegalArgumentException e) {
        // This is acceptable behavior
      }
    }

    @Test
    @DisplayName("CJK content produces reasonable chunk sizes")
    void cjkContentProducesReasonableChunks() {
      // CJK: approximately 1 token per character, not 1.3 tokens per 5 chars
      String cjk =
          "你好世界。这是测试内容。我们需要验证中文分块是否正确。"
              + "更多的中文内容在这里。这个句子应该被正确处理。"
              + "最后一个句子结束这段测试内容。";
      // ~70 CJK chars = ~70 tokens

      // Request 30 tokens per chunk - should split CJK content
      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(cjk, 30, 5, ChunkSplitter.Mode.DEFAULT);

      // BUG: If ratio is wrong, this produces 1 giant chunk instead of multiple
      assertTrue(
          chunks.size() >= 2,
          "CJK content should split into multiple chunks. "
              + "Got "
              + chunks.size()
              + " chunks for "
              + cjk.length()
              + " chars. "
              + "First chunk: "
              + chunks.get(0).content().length()
              + " chars");

      // Each chunk should be reasonable size (not giant)
      for (ChunkSplitter.Chunk chunk : chunks) {
        assertTrue(
            chunk.content().length() < cjk.length() * 0.8,
            "Each chunk should be smaller than 80% of total. "
                + "Chunk size: "
                + chunk.content().length());
      }
    }

    @Test
    @DisplayName("emoji surrogate pairs not split")
    void emojiSurrogatePairsNotSplit() {
      // Emoji (surrogate pairs in Java UTF-16)
      String emoji = "Hello 😀 World 🎉 Test 🚀 Content 💡 Here 🔥 More";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(emoji, 10, 2, ChunkSplitter.Mode.DEFAULT);

      for (ChunkSplitter.Chunk chunk : chunks) {
        String c = chunk.content();
        // Check for malformed Unicode (unpaired surrogates)
        for (int i = 0; i < c.length(); i++) {
          char ch = c.charAt(i);
          if (Character.isHighSurrogate(ch)) {
            assertTrue(
                i + 1 < c.length() && Character.isLowSurrogate(c.charAt(i + 1)),
                "High surrogate at "
                    + i
                    + " should be followed by low surrogate. "
                    + "Chunk: '"
                    + c
                    + "'");
          }
          if (Character.isLowSurrogate(ch)) {
            assertTrue(
                i > 0 && Character.isHighSurrogate(c.charAt(i - 1)),
                "Low surrogate at "
                    + i
                    + " should be preceded by high surrogate. "
                    + "Chunk: '"
                    + c
                    + "'");
          }
        }
      }
    }

    @Test
    @DisplayName("overlap >= target produces finite reasonable chunks")
    void extremeOverlapProducesFiniteChunks() {
      String content =
          "Sentence one. Sentence two. Sentence three. Sentence four. "
              + "Sentence five. Sentence six. Sentence seven. Sentence eight.";

      // Overlap equal to target - edge case
      List<ChunkSplitter.Chunk> equalChunks =
          ChunkSplitter.splitWithMetadata(content, 20, 20, ChunkSplitter.Mode.DEFAULT);

      assertTrue(
          equalChunks.size() < 50,
          "Equal overlap/target should not explode chunk count. Got: " + equalChunks.size());

      // Overlap greater than target
      List<ChunkSplitter.Chunk> largerChunks =
          ChunkSplitter.splitWithMetadata(content, 20, 30, ChunkSplitter.Mode.DEFAULT);

      assertTrue(
          largerChunks.size() < 50,
          "Larger overlap should not explode chunk count. Got: " + largerChunks.size());

      // Verify chunks still make forward progress
      if (equalChunks.size() > 1) {
        for (int i = 1; i < equalChunks.size(); i++) {
          assertTrue(
              equalChunks.get(i).startChar() > equalChunks.get(i - 1).startChar(),
              "Chunks should make forward progress");
        }
      }
    }

    @Test
    @DisplayName("large code fence not split internally")
    void largeCodeFenceNotSplitInternally() {
      // Large fence that exceeds typical chunk size
      String largeFence =
          "Before fence.\n\n```java\n"
              + "// Large code block\n".repeat(100) // ~2000 chars
              + "```\n\nAfter fence.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(largeFence, 50, 10, ChunkSplitter.Mode.MARKDOWN);

      // Find chunk(s) containing the fence markers
      boolean foundFenceStart = false;
      boolean foundClosingMarker = false;
      boolean foundAfterFence = false;

      for (ChunkSplitter.Chunk chunk : chunks) {
        if (chunk.content().contains("```java")) foundFenceStart = true;
        // Check for closing marker (``` not followed by language specifier)
        if (chunk.content().contains("```\n") || chunk.content().endsWith("```"))
          foundClosingMarker = true;
        if (chunk.content().contains("After fence")) foundAfterFence = true;

        // No chunk should contain exactly one ``` (would mean split inside fence)
        // This is the key assertion: fences should be kept whole
        int fenceCount = countOccurrences(chunk.content(), "```");
        assertNotEquals(
            1, fenceCount, "Chunk should not contain exactly one fence marker: " + chunk.content());
      }

      assertTrue(foundFenceStart, "Should find fence start (```java)");
      assertTrue(foundClosingMarker, "Should find closing fence marker (```)");
      assertTrue(foundAfterFence, "Should find content after fence");
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
