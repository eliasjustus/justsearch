package io.justsearch.indexing.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.indexing.chunking.ChunkSplitter.Chunk;
import io.justsearch.indexing.chunking.ChunkSplitter.Mode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

/**
 * The chunk-offset <b>conservation law</b> verified over generated content, budgets, and modes
 * (tempdoc 554 — property floor; oracle class: free / input-is-spec). The test-only
 * {@link ChunkTiling} helper wraps a {@link ChunkSplitter#splitWithMetadata} result and validates
 * that every chunk's offsets reconstruct its content; a violation throws and fails the property. The
 * explicit assertion below is redundant with the helper's invariant but documents the law.
 *
 * <p>Also verifies the end-of-text overlap-tail law (tempdoc 749): once a chunk's end reaches the
 * text's end, every further window would be a suffix contained in that chunk, so no such window may
 * be emitted. {@code max = 2500} on the generated content raises the chance a run lands a chunk end
 * exactly at text end, exercising that guard.
 */
class ChunkTilingPropertyTest {

  @Property(tries = 400)
  void splitterOutputAlwaysFormsAValidTiling(
      @ForAll @StringLength(max = 2500) String content,
      @ForAll @IntRange(min = 1, max = 40) int targetTokens,
      @ForAll @IntRange(min = 0, max = 15) int overlapTokens,
      @ForAll Mode mode) {
    ChunkTiling tiling =
        ChunkTiling.of(content, ChunkSplitter.splitWithMetadata(content, targetTokens, overlapTokens, mode));
    for (Chunk c : tiling.chunks()) {
      assertEquals(
          tiling.source().substring(c.startChar(), c.endChar()),
          c.content(),
          "chunk offsets must reconstruct content");
    }
    for (int i = 1; i < tiling.chunks().size(); i++) {
      Chunk prev = tiling.chunks().get(i - 1);
      Chunk curr = tiling.chunks().get(i);
      boolean contained = curr.startChar() >= prev.startChar() && curr.endChar() <= prev.endChar();
      assertFalse(
          contained,
          "chunk "
              + i
              + " ["
              + curr.startChar()
              + ","
              + curr.endChar()
              + ") is fully contained within predecessor ["
              + prev.startChar()
              + ","
              + prev.endChar()
              + ")");
    }
  }
}
