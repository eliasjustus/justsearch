package io.justsearch.indexing.chunking;

import io.justsearch.indexing.chunking.ChunkSplitter.Chunk;
import java.util.List;
import java.util.Objects;

/**
 * Test-only validation helper for the chunk-offset conservation property (tempdoc 554): wrapping a
 * {@code ChunkSplitter} result in this record validates that every chunk's offsets actually
 * reconstruct its content against the source, so {@link ChunkTilingPropertyTest} can assert the law
 * by construction. This is <i>not</i> a production type — the production splitter returns raw
 * {@code List<Chunk>}; this helper exists so a generated counterexample throws here in the test.
 *
 * <p>Invariants enforced for every chunk:
 *
 * <ul>
 *   <li><b>Bounds:</b> {@code 0 <= startChar < endChar <= source.length()}.
 *   <li><b>Reconstruction:</b> {@code source.substring(startChar, endChar).equals(content)} — the
 *       free-oracle law (the input <i>is</i> the spec).
 *   <li><b>Ordering:</b> start offsets are non-decreasing (chunk overlap is allowed by design, so this
 *       intentionally does <i>not</i> forbid overlap — only out-of-order or gapping-backwards chunks).
 * </ul>
 */
public record ChunkTiling(String source, List<Chunk> chunks) {

  public ChunkTiling {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(chunks, "chunks");
    chunks = List.copyOf(chunks);
    int prevStart = -1;
    for (Chunk c : chunks) {
      int s = c.startChar();
      int e = c.endChar();
      if (s < 0 || e > source.length() || s >= e) {
        throw new IllegalArgumentException(
            "chunk " + c.index() + " offsets out of bounds or empty: [" + s + "," + e + ") for"
                + " source length " + source.length());
      }
      if (!source.substring(s, e).equals(c.content())) {
        throw new IllegalArgumentException(
            "chunk " + c.index() + " offsets [" + s + "," + e + ") do not reconstruct its content");
      }
      if (s < prevStart) {
        throw new IllegalArgumentException(
            "chunk " + c.index() + " start offset " + s + " precedes the previous chunk's " + prevStart);
      }
      prevStart = s;
    }
  }

  /** Validates and wraps the chunks produced for {@code source}. Throws if any invariant is violated. */
  public static ChunkTiling of(String source, List<Chunk> chunks) {
    return new ChunkTiling(source == null ? "" : source, chunks);
  }
}
