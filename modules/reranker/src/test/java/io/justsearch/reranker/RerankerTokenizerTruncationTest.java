/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 836 §1.3(b) / §5.5 — pair truncation is detected instead of applied invisibly.
 *
 * <p>The packing step keeps the FIRST {@code maxLength} tokens, so an overlong pair loses its
 * trailing {@code [SEP]} and its second segment's {@code token_type_ids} are cut mid-run: the model
 * is handed a malformed pair and returns a number for it anyway. Measured (§9 P1): a default-sized
 * indexed chunk already overflows the 512-token window on real prose, and nothing reported it.
 */
@DisplayName("RerankerTokenizer — pair truncation detection")
class RerankerTokenizerTruncationTest {

  private static long[] ids(int length) {
    long[] out = new long[length];
    for (int i = 0; i < length; i++) {
      out[i] = i + 1;
    }
    return out;
  }

  @Test
  @DisplayName("a pair that fits reports no truncation and is copied verbatim")
  void fittingPairIsUntouched() {
    long[][] rows = {ids(10)};

    var batch = RerankerTokenizer.pack(rows, rows, rows, 16);

    assertEquals(0, batch.truncatedPairs());
    assertEquals(10, batch.longestPairTokens());
    assertEquals(1, batch.inputIds()[0][0], "first token preserved");
    assertEquals(10, batch.inputIds()[0][9], "last real token preserved");
    assertEquals(0, batch.inputIds()[0][10], "the remainder is padding");
  }

  @Test
  @DisplayName("an overlong pair is counted, and the tokens beyond the window are the ones lost")
  void overlongPairIsCounted() {
    long[][] rows = {ids(600)};

    var batch = RerankerTokenizer.pack(rows, rows, rows, 512);

    assertEquals(1, batch.truncatedPairs(), "the cut must be visible to the caller");
    assertEquals(600, batch.longestPairTokens());
    assertEquals(512, batch.inputIds()[0][511], "the prefix is what survives");
  }

  @Test
  @DisplayName("truncation is counted per pair, so a mixed batch reports how many were cut")
  void mixedBatchCountsPerPair() {
    long[][] rows = {ids(10), ids(600), ids(40), ids(513)};

    var batch = RerankerTokenizer.pack(rows, rows, rows, 512);

    assertEquals(2, batch.truncatedPairs());
    assertEquals(600, batch.longestPairTokens());
    assertEquals(4, batch.batchSize());
  }

  @Test
  @DisplayName("a pair exactly at the limit is not truncated (the boundary is inclusive)")
  void exactlyAtLimitIsNotTruncated() {
    long[][] rows = {ids(512)};

    var batch = RerankerTokenizer.pack(rows, rows, rows, 512);

    assertEquals(0, batch.truncatedPairs());
  }

  @Test
  @DisplayName("the strict path raises rather than silently scoring a malformed pair")
  void strictPathRaises() {
    long[][] rows = {ids(10), ids(600)};
    var batch = RerankerTokenizer.pack(rows, rows, rows, 512);

    var thrown =
        assertThrows(
            RerankerTokenizer.PairTooLongException.class,
            () -> RerankerTokenizer.requireUntruncated(batch, 512));

    assertTrue(
        thrown.getMessage().contains("600"),
        "the message must name the overflow, so the cause is legible: " + thrown.getMessage());
  }

  @Test
  @DisplayName("the strict path passes an untruncated batch through unchanged")
  void strictPathPassesFittingBatch() {
    long[][] rows = {ids(10), ids(512)};
    var batch = RerankerTokenizer.pack(rows, rows, rows, 512);

    assertSame(batch, RerankerTokenizer.requireUntruncated(batch, 512));
  }
}
