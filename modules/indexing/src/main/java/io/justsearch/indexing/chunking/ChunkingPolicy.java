/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.chunking;

/**
 * The four numbers that decide how a document becomes chunk documents.
 *
 * <p>Tempdoc 916 Part 1 (decision-review lane E). Before this record the four values were
 * {@code public static final} constants read directly at every call site, which made the chunk
 * granularity a recompile-only decision. They are still constants — {@link #DEFAULT} is exactly
 * the shipped triple plus the writer threshold — but a caller that has resolved configuration can
 * now hand a different policy to {@link ChunkSplitter} for the duration of a measurement campaign.
 *
 * <p>This record is a pure value object on purpose: {@code modules/indexing} depends only on
 * {@code modules:core}, so the configuration read lives in the Worker-side caller
 * ({@code ChunkDocumentWriter}) rather than here.
 *
 * @param targetTokens target chunk size in estimated tokens
 * @param overlapTokens overlap between adjacent chunks in estimated tokens
 * @param minTokens floor on how far the splitter advances between chunks; also the earliest
 *     position a boundary search may accept. This is NOT a cosmetic guard — because the advance is
 *     {@code max(chunkLength - overlap, minChars)}, a {@code minTokens} close to
 *     {@code targetTokens} silently caps the effective overlap (see tempdoc 916 §K.2)
 * @param thresholdChars documents shorter than this are not chunked at all
 */
public record ChunkingPolicy(
    int targetTokens, int overlapTokens, int minTokens, int thresholdChars) {

  /** Shipped chunking threshold: documents below this length are indexed whole. */
  public static final int DEFAULT_THRESHOLD_CHARS = 2000;

  /** The shipped policy. Every unset configuration key resolves to the matching field here. */
  public static final ChunkingPolicy DEFAULT =
      new ChunkingPolicy(
          ChunkSplitter.DEFAULT_CHUNK_TOKENS,
          ChunkSplitter.DEFAULT_OVERLAP_TOKENS,
          ChunkSplitter.MIN_CHUNK_TOKENS,
          DEFAULT_THRESHOLD_CHARS);

  // Clamped to zero, NOT to one. ChunkSplitter.tokensToChars already maps every non-positive token
  // count to a single character, so flooring at 0 leaves the degenerate inputs behaving exactly as
  // they did before this record existed — which is what makes the pre-916 int overloads a pure
  // pass-through rather than a subtle behaviour change for callers that pass 0 or a negative.
  public ChunkingPolicy {
    targetTokens = Math.max(0, targetTokens);
    overlapTokens = Math.max(0, overlapTokens);
    minTokens = Math.max(0, minTokens);
    thresholdChars = Math.max(0, thresholdChars);
  }

  /** True when this policy is the shipped one, i.e. no campaign override is in effect. */
  public boolean isDefault() {
    return DEFAULT.equals(this);
  }
}
