/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 916 Part 1 — the sweepable chunking policy and what it does and does not change. */
final class ChunkingPolicyTest {

  /** ~14k chars of Latin prose with sentence and paragraph boundaries the splitter can find. */
  private static String corpus() {
    StringBuilder sb = new StringBuilder();
    for (int p = 0; p < 40; p++) {
      for (int s = 0; s < 6; s++) {
        sb.append("Paragraph ")
            .append(p)
            .append(" sentence ")
            .append(s)
            .append(" carries enough words to make the estimated token count meaningful. ");
      }
      sb.append("\n\n");
    }
    return sb.toString();
  }

  private static List<String> spans(List<ChunkSplitter.Chunk> chunks) {
    return chunks.stream()
        .map(c -> c.index() + ":" + c.startChar() + "-" + c.endChar())
        .collect(Collectors.toList());
  }

  @Test
  @DisplayName("DEFAULT equals the shipped constants")
  void defaultMatchesShippedConstants() {
    assertEquals(ChunkSplitter.DEFAULT_CHUNK_TOKENS, ChunkingPolicy.DEFAULT.targetTokens());
    assertEquals(ChunkSplitter.DEFAULT_OVERLAP_TOKENS, ChunkingPolicy.DEFAULT.overlapTokens());
    assertEquals(ChunkSplitter.MIN_CHUNK_TOKENS, ChunkingPolicy.DEFAULT.minTokens());
    assertEquals(2000, ChunkingPolicy.DEFAULT.thresholdChars());
    assertTrue(ChunkingPolicy.DEFAULT.isDefault());
  }

  /**
   * The bit-identity claim the campaign rests on: an un-swept build must split exactly as it does
   * today. Asserted against the pre-916 entry point (the int overload), not against a hand-written
   * expected list — a copied expectation would pass if both sides were wrong in the same way.
   */
  @Test
  @DisplayName("splitting under DEFAULT is identical to the pre-916 int overload")
  void defaultPolicyReproducesPre916() {
    String text = corpus();
    for (ChunkSplitter.Mode mode :
        List.of(ChunkSplitter.Mode.DEFAULT, ChunkSplitter.Mode.MARKDOWN, ChunkSplitter.Mode.CODE)) {
      List<ChunkSplitter.Chunk> pre916 =
          ChunkSplitter.splitWithMetadata(
              text, ChunkSplitter.DEFAULT_CHUNK_TOKENS, ChunkSplitter.DEFAULT_OVERLAP_TOKENS, mode);
      List<ChunkSplitter.Chunk> viaPolicy =
          ChunkSplitter.splitWithMetadata(text, ChunkingPolicy.DEFAULT, mode);
      assertEquals(pre916, viaPolicy, "mode " + mode + " must be untouched by the policy overload");

      assertEquals(
          ChunkSplitter.split(
              text, ChunkSplitter.DEFAULT_CHUNK_TOKENS, ChunkSplitter.DEFAULT_OVERLAP_TOKENS, mode),
          ChunkSplitter.split(text, ChunkingPolicy.DEFAULT, mode));
    }
  }

  @Test
  @DisplayName("a null policy falls back to DEFAULT rather than throwing")
  void nullPolicyFallsBack() {
    String text = corpus();
    assertEquals(
        ChunkSplitter.splitWithMetadata(text, ChunkingPolicy.DEFAULT, ChunkSplitter.Mode.DEFAULT),
        ChunkSplitter.splitWithMetadata(text, null, ChunkSplitter.Mode.DEFAULT));
  }

  @Test
  @DisplayName("a smaller target produces strictly more chunks")
  void smallerTargetChunksMore() {
    String text = corpus();
    int at500 =
        ChunkSplitter.splitWithMetadata(text, ChunkingPolicy.DEFAULT, ChunkSplitter.Mode.DEFAULT)
            .size();
    int at128 =
        ChunkSplitter.splitWithMetadata(
                text, new ChunkingPolicy(128, 0, 26, 2000), ChunkSplitter.Mode.DEFAULT)
            .size();
    assertTrue(at128 > at500, "128-token chunks (" + at128 + ") must exceed 500 (" + at500 + ")");
  }

  /**
   * Tempdoc 916 §K.2, stated executably. The splitter advances by
   * {@code max(chunkLength - overlapChars, minChars)}, so with the shipped {@code minTokens = 100}
   * a 128-token target cannot express a 50-token overlap: the floor eats it, and the 128/25 and
   * 128/50 arms collapse onto nearly the same boundaries. That is why {@code min_tokens} is a
   * campaign key and not a constant — without it, four of the twelve arms would be confounded.
   */
  @Test
  @DisplayName("min_tokens silently caps overlap at target 128 — the reason it is a key")
  void minTokensCapsOverlapAtSmallTargets() {
    String text = corpus();

    // Measured on this fixture (tempdoc 916 §K.2): at target 128 a requested 50-token overlap is
    // delivered as ~133 chars instead of ~190 — 70% of what the arm asked for — and the arm emits
    // 57 chunks where an unbound floor emits 79.
    double shippedFloor = meanOverlapChars(text, new ChunkingPolicy(128, 50, 100, 2000));
    double scaledFloor = meanOverlapChars(text, new ChunkingPolicy(128, 50, 26, 2000));
    assertTrue(
        shippedFloor < scaledFloor * 0.85,
        "min_tokens=100 must measurably suppress a 50-token overlap at target 128 (got "
            + shippedFloor
            + " vs "
            + scaledFloor
            + " chars)");
    assertTrue(
        ChunkSplitter.splitWithMetadata(
                    text, new ChunkingPolicy(128, 50, 100, 2000), ChunkSplitter.Mode.DEFAULT)
                .size()
            < ChunkSplitter.splitWithMetadata(
                    text, new ChunkingPolicy(128, 50, 26, 2000), ChunkSplitter.Mode.DEFAULT)
                .size(),
        "the suppressed overlap also suppresses the chunk count, which is the metric the sweep "
            + "reads");

    // The distortion is confined to the small-target arms: at 256, 384 and the incumbent 500 the
    // floor is already below chunkLength - overlap, so scaling it changes nothing at all. That
    // asymmetry is exactly what would have confounded the 12-arm matrix.
    for (int target : new int[] {256, 384, 500}) {
      assertEquals(
          spans(
              ChunkSplitter.splitWithMetadata(
                  text, new ChunkingPolicy(target, 50, 100, 2000), ChunkSplitter.Mode.DEFAULT)),
          spans(
              ChunkSplitter.splitWithMetadata(
                  text,
                  new ChunkingPolicy(target, 50, Math.max(1, target / 5), 2000),
                  ChunkSplitter.Mode.DEFAULT)),
          "at target " + target + " the shipped floor is inert, so min_tokens is a no-op there");
    }
  }

  /** Mean chars of textual overlap actually delivered between consecutive chunks. */
  private static double meanOverlapChars(String text, ChunkingPolicy policy) {
    List<ChunkSplitter.Chunk> chunks =
        ChunkSplitter.splitWithMetadata(text, policy, ChunkSplitter.Mode.DEFAULT);
    assertTrue(chunks.size() > 3, "need several chunks to average over");
    long total = 0;
    for (int i = 0; i + 1 < chunks.size(); i++) {
      total += Math.max(0, chunks.get(i).endChar() - chunks.get(i + 1).startChar());
    }
    return (double) total / (chunks.size() - 1);
  }

  /**
   * Negatives are floored at ZERO, not at one. {@code tokensToChars} already maps every
   * non-positive token count to a single character, so flooring at 0 keeps the degenerate inputs
   * behaving exactly as they did before this record existed — and that is what makes the pre-916
   * int overloads a pure pass-through rather than a quiet behaviour change.
   */
  @Test
  @DisplayName("the compact constructor floors negatives at zero, preserving degenerate behaviour")
  void clamping() {
    ChunkingPolicy p = new ChunkingPolicy(-3, -5, -7, -1);
    assertEquals(0, p.targetTokens());
    assertEquals(0, p.overlapTokens());
    assertEquals(0, p.minTokens());
    assertEquals(0, p.thresholdChars());
    assertFalse(p.isDefault());

    String text = corpus();
    for (int[] degenerate : new int[][] {{0, 0}, {0, 50}, {-3, -5}, {1, 0}}) {
      assertEquals(
          ChunkSplitter.splitWithMetadata(
              text,
              new ChunkingPolicy(
                  degenerate[0], degenerate[1], ChunkSplitter.MIN_CHUNK_TOKENS, 2000),
              ChunkSplitter.Mode.DEFAULT),
          ChunkSplitter.splitWithMetadata(
              text, degenerate[0], degenerate[1], ChunkSplitter.Mode.DEFAULT),
          "degenerate (" + degenerate[0] + ", " + degenerate[1] + ") must route identically");
    }
  }
}
