/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Register F-054: the reranker distinguishes a budget pre-check from an inference failure, and
 * {@code skipped} is derived from that cause rather than stored beside it.
 */
@DisplayName("RerankSkipCause: why the cross-encoder did not score")
class RerankSkipCauseTest {

  @Test
  @DisplayName("only a budget pre-check is a deadline-shaped skip")
  void budgetPrecheckSplit() {
    assertTrue(RerankSkipCause.TOKENIZE_BUDGET_EXCEEDED.isBudgetPrecheck());
    assertTrue(RerankSkipCause.PREP_BUDGET_EXCEEDED.isBudgetPrecheck());
    // The whole point of F-054: an inference failure is NOT fixable by a larger deadline, so it
    // must never classify as a budget pre-check.
    assertFalse(RerankSkipCause.INFERENCE_FAILED.isBudgetPrecheck());
    assertFalse(RerankSkipCause.NONE.isBudgetPrecheck());
  }

  @Test
  @DisplayName("every cause except NONE is a skip")
  void skipSplit() {
    assertFalse(RerankSkipCause.NONE.isSkip());
    for (RerankSkipCause cause : RerankSkipCause.values()) {
      if (cause != RerankSkipCause.NONE) {
        assertTrue(cause.isSkip(), cause.name());
      }
    }
  }

  @Test
  @DisplayName("RerankedResult.skipped() is derived from the cause — one authority, never two")
  void skippedIsDerived() {
    assertFalse(
        new CrossEncoderReranker.RerankedResult(
                List.of(0), List.of(1.0f), RerankSkipCause.NONE, 5)
            .skipped());
    assertTrue(
        new CrossEncoderReranker.RerankedResult(
                List.of(0), List.of(), RerankSkipCause.INFERENCE_FAILED, 5)
            .skipped());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CrossEncoderReranker.RerankedResult(List.of(), List.of(), null, 0));
  }

  @Test
  @DisplayName("F-054: an arena exhaustion names the arena knob, a generic failure does not")
  void arenaExhaustionNamesTheKnob() {
    String arena = CrossEncoderReranker.inferenceFailureMessage(true);
    assertTrue(arena.contains("JUSTSEARCH_RERANK_GPU_MEM_MB"), arena);
    // The remedy the campaign had to find by hand was the arena size, NOT the deadline — the
    // message must say so rather than leaving the reader at the mislabelled knob.
    assertTrue(arena.contains("NOT a deadline miss"), arena);

    String generic = CrossEncoderReranker.inferenceFailureMessage(false);
    assertFalse(generic.contains("JUSTSEARCH_RERANK_GPU_MEM_MB"), generic);
    assertEquals("Rerank inference failed", generic);
  }
}
