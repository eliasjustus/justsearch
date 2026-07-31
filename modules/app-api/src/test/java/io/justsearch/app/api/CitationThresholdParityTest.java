/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tempdoc 799 §Q — the citation cutoff must mean the SAME thing on the RAG and agent paths.
 *
 * <p>Tempdoc 565 §15.A collapsed a 0.45/0.5 drift onto one constant. When 799 wired the cutoff to
 * {@code justsearch.citation.match_threshold}, each matcher clamped the configured value locally
 * and the two clamps disagreed: {@code StreamingCitationMatcher} floored at {@code 0.01}, while
 * {@code AgentCitationResolver} fell back to the default. A configured {@code 0} therefore meant
 * {@code 0.01} on one path and {@code 0.5} on the other — a wider divergence than the one §15.A
 * removed, shipped by the change whose commit message argued it could not happen.
 *
 * <p>Sharing the constant was not sufficient; the interpretation of out-of-range values has to be
 * shared too. This test pins that interpretation. It lives in {@code app-api} because that is the
 * only module both consumers depend on.
 */
class CitationThresholdParityTest {

  @ParameterizedTest
  @ValueSource(doubles = {0.0, -1.0, -0.0001, 1.0001, 9.0, Double.NaN})
  @DisplayName("out-of-range configured values resolve to the default, not to a silent floor")
  void outOfRangeResolvesToDefault(double configured) {
    assertEquals(
        DocumentService.DEFAULT_CITATION_SIMILARITY_THRESHOLD,
        DocumentService.effectiveCitationThreshold(configured),
        1e-12,
        "an unusable cutoff must behave like 'unset', not like 'cite almost everything' — the old "
            + "RAG-path floor of 0.01 turned a configured 0 into near-zero grounding");
  }

  @ParameterizedTest
  @ValueSource(doubles = {0.005, 0.01, 0.45, 0.5, 0.83, 1.0})
  @DisplayName("in-range configured values pass through untouched on both paths")
  void inRangePassesThrough(double configured) {
    assertEquals(configured, DocumentService.effectiveCitationThreshold(configured), 1e-12);
  }

  @ParameterizedTest
  @ValueSource(doubles = {0.0, -1.0, 9.0})
  @DisplayName("the old RAG-path floor of 0.01 is specifically excluded as a possible result")
  void neverSilentlyFloorsToNearZero(double configured) {
    // The regression this pins: the previous RAG clamp `max(0.01, min(1.0, t))` turned a
    // configured 0 into 0.01, i.e. "count almost anything as grounded".
    assertNotEquals(0.01, DocumentService.effectiveCitationThreshold(configured), 1e-12);
  }

  // NOTE: parity is asserted per-path in the CONSUMER modules, not here — asserting it in this
  // module could only call this same function twice, which is tautological and would pass even if
  // a matcher re-applied a local clamp afterwards. The real assertions are:
  //   - RAG path:   StreamingCitationMatcherTest#configuredZeroResolvesToDefaultNotFloor
  //   - agent path: AgentCitationResolverThresholdTest
  // Each observes the value its matcher actually hands to DocumentService.matchCitations.
}
