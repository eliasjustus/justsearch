package io.justsearch.indexerworker.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.rag.ChunkOffsetMath.HeadingInfo;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Property-style tests for the {@link ChunkOffsetMath} seam (governance/logic-seams.v1.json), the
 * pure law-core extracted from ChunkDocumentWriter. Plain JUnit + seeded generators (no jqwik — see
 * HybridFusionUtilsPropertyTest for the supply-chain rationale, tempdoc 555 §10).
 */
class ChunkOffsetMathPropertyTest {

  private static final int ITERS = 300;

  /** Independent reference implementation of the line-number law (no Math.min / loop reuse). */
  private static int referenceLine(String content, int offset) {
    if (content == null || content.isEmpty() || offset <= 0) return 1;
    int clamped = Math.min(offset, content.length());
    int newlines = 0;
    for (int i = 0; i < clamped; i++) if (content.charAt(i) == '\n') newlines++;
    return 1 + newlines;
  }

  @Test
  @DisplayName("line law: equals 1 + newlines before min(offset,len), over random content/offsets")
  void calculateLineNumber_matchesReferenceAndIsMonotone() {
    Random rnd = new Random(20260603L);
    for (int it = 0; it < ITERS; it++) {
      int len = rnd.nextInt(60);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < len; i++) sb.append(rnd.nextInt(4) == 0 ? '\n' : (char) ('a' + rnd.nextInt(5)));
      String content = sb.toString();

      int prev = Integer.MIN_VALUE;
      for (int offset = -2; offset <= len + 3; offset++) {
        int actual = ChunkOffsetMath.calculateLineNumber(content, offset);
        assertEquals(referenceLine(content, offset), actual, "line must equal 1 + preceding newlines");
        assertTrue(actual >= prev, "line number must be non-decreasing in offset");
        prev = actual;
      }
      // offset at/below 0 is always line 1
      assertEquals(1, ChunkOffsetMath.calculateLineNumber(content, 0));
      assertEquals(1, ChunkOffsetMath.calculateLineNumber(content, -5));
    }
  }

  @Test
  @DisplayName("line law: each newline crossed increments the line by exactly one")
  void calculateLineNumber_eachNewlineAddsOne() {
    String content = "a\nb\nc\nd"; // newlines at indices 1,3,5
    assertEquals(1, ChunkOffsetMath.calculateLineNumber(content, 1)); // before first '\n'
    assertEquals(2, ChunkOffsetMath.calculateLineNumber(content, 2)); // after first '\n'
    assertEquals(3, ChunkOffsetMath.calculateLineNumber(content, 4));
    assertEquals(4, ChunkOffsetMath.calculateLineNumber(content, 6));
  }

  @Test
  @DisplayName("heading law: returns the LAST markdown heading starting before the offset, else NONE")
  void findPrecedingHeading_returnsNearestPrior() {
    String content = "intro\n# First\nbody\n## Second\nmore text here";
    int idxFirst = content.indexOf("# First");
    int idxSecond = content.indexOf("## Second");

    // before any heading → NONE
    assertEquals(HeadingInfo.NONE, ChunkOffsetMath.findPrecedingHeading(content, idxFirst));
    // after the first, before the second → First (level 1)
    HeadingInfo h1 = ChunkOffsetMath.findPrecedingHeading(content, idxSecond);
    assertEquals(1, h1.level());
    assertEquals("First", h1.text());
    // after both → Second (level 2) is the nearest prior
    HeadingInfo h2 = ChunkOffsetMath.findPrecedingHeading(content, content.length());
    assertEquals(2, h2.level());
    assertEquals("Second", h2.text());
  }

  @Test
  @DisplayName("heading law: offset<=0 or no heading yields NONE")
  void findPrecedingHeading_edgeCases() {
    assertEquals(HeadingInfo.NONE, ChunkOffsetMath.findPrecedingHeading("# H\ntext", 0));
    assertEquals(HeadingInfo.NONE, ChunkOffsetMath.findPrecedingHeading("no headings here at all", 23));
    assertEquals(HeadingInfo.NONE, ChunkOffsetMath.findPrecedingHeading(null, 5));
  }
}
