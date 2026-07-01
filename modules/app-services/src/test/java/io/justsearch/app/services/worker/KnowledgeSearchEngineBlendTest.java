/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 643: pure-math tests for the judge-stage refinement floor
 * ({@link KnowledgeSearchEngine#blendPreRerankAndCrossEncoder}).
 *
 * <p>Mirrors the testability pattern of D-004's {@code HybridSearchOpsTest} arbitration-math
 * tests — the helper is package-private static and pure, so it is exercised directly with
 * hand-built score arrays rather than through the full search pipeline.
 */
@DisplayName("KnowledgeSearchEngine.blendPreRerankAndCrossEncoder")
class KnowledgeSearchEngineBlendTest {

  @Test
  @DisplayName("alpha=0 reduces to today's CE-only order (min-max normalization preserves order)")
  void alphaZero_reducesToCrossEncoderOnlyOrder() {
    float[] preRerank = {3.0f, 2.0f, 1.0f};
    float[] ce = {1.0f, 5.0f, 0.5f}; // raw CE order: doc1 > doc0 > doc2

    List<Integer> order = KnowledgeSearchEngine.blendPreRerankAndCrossEncoder(preRerank, ce, 0.0);

    assertEquals(List.of(1, 0, 2), order);
  }

  @Test
  @DisplayName("alpha=1 ignores the CE entirely (pure pre-rerank/fusion order)")
  void alphaOne_reducesToPreRerankOnlyOrder() {
    float[] preRerank = {3.0f, 2.0f, 1.0f}; // fusion order: doc0 > doc1 > doc2
    float[] ce = {1.0f, 5.0f, 0.5f}; // CE would prefer doc1 — must be ignored at alpha=1

    List<Integer> order = KnowledgeSearchEngine.blendPreRerankAndCrossEncoder(preRerank, ce, 1.0);

    assertEquals(List.of(0, 1, 2), order);
  }

  @Test
  @DisplayName(
      "the floor: a marginal CE preference is overridden by a strong fusion signal at alpha=0.5")
  void midAlpha_floorSuppressesMarginalCeFlip() {
    // doc0 clearly leads fusion (3.0 vs 2.0 vs 1.0); the CE only MARGINALLY prefers doc1 over
    // doc0 (2.1 vs 2.0) -- exactly the low-confidence CE call the refinement floor exists to
    // bound. At alpha=0 the CE's marginal preference still wins outright (today's behavior);
    // at alpha=0.5 the floor's fusion weight is enough to keep doc0 on top.
    float[] preRerank = {3.0f, 2.0f, 1.0f};
    float[] ce = {2.0f, 2.1f, 1.0f};

    List<Integer> ceOnly = KnowledgeSearchEngine.blendPreRerankAndCrossEncoder(preRerank, ce, 0.0);
    assertEquals(List.of(1, 0, 2), ceOnly, "sanity: CE-only order prefers doc1");

    List<Integer> blended =
        KnowledgeSearchEngine.blendPreRerankAndCrossEncoder(preRerank, ce, 0.5);
    assertEquals(List.of(0, 1, 2), blended, "the floor keeps fusion's leader on top");
  }

  @Test
  @DisplayName("a strong CE preference still wins at alpha=0.5 (the floor only bounds, not mutes)")
  void midAlpha_strongCePreferenceStillWins() {
    // Unlike the marginal-flip case above, a DECISIVE CE preference (5.0 vs 1.0) should still be
    // able to overcome a moderate fusion lead at a balanced blend weight -- the floor bounds the
    // CE's influence, it does not silence it.
    float[] preRerank = {3.0f, 2.0f, 1.0f};
    float[] ce = {1.0f, 5.0f, 0.5f};

    List<Integer> blended =
        KnowledgeSearchEngine.blendPreRerankAndCrossEncoder(preRerank, ce, 0.5);

    assertEquals(List.of(1, 0, 2), blended);
  }

  @Test
  @DisplayName("empty window returns an empty order")
  void emptyWindow_returnsEmptyList() {
    List<Integer> order =
        KnowledgeSearchEngine.blendPreRerankAndCrossEncoder(new float[0], new float[0], 0.5);
    assertEquals(List.of(), order);
  }

  @Test
  @DisplayName("single-candidate window is trivially [0] regardless of alpha")
  void singleCandidate_isTrivial() {
    float[] preRerank = {1.0f};
    float[] ce = {7.0f};
    assertEquals(
        List.of(0), KnowledgeSearchEngine.blendPreRerankAndCrossEncoder(preRerank, ce, 0.0));
    assertEquals(
        List.of(0), KnowledgeSearchEngine.blendPreRerankAndCrossEncoder(preRerank, ce, 1.0));
  }

  @Test
  @DisplayName("flat (tied) scores on both axes preserve pre-rerank position order (stable sort)")
  void flatScores_preservesOriginalOrder() {
    float[] preRerank = {1.0f, 1.0f, 1.0f};
    float[] ce = {2.0f, 2.0f, 2.0f};

    List<Integer> order = KnowledgeSearchEngine.blendPreRerankAndCrossEncoder(preRerank, ce, 0.5);

    assertEquals(List.of(0, 1, 2), order);
  }
}
