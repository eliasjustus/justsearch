package io.justsearch.indexing.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 */
class ChunkTilingPropertyTest {

  @Property(tries = 400)
  void splitterOutputAlwaysFormsAValidTiling(
      @ForAll @StringLength(max = 300) String content,
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
  }
}
