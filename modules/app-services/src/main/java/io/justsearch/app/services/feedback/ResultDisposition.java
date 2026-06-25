/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

/**
 * Tempdoc 580 §17.3 (Track C P2) — the canonical record of <em>what came of a ranked result</em>:
 * the missing "outcome tier" of the projection spine. One disposition per (interactionId × result),
 * joinable by {@code interactionId} to the {@link FeatureSnapshot} that ranked it (the §17.4 join),
 * so the §17.5 label/eval projections derive from one source.
 *
 * <p>This is ONE canonical stream fed by multiple {@link Contributor}s (the §17.3 anti-fork design:
 * the agentic-citation harvest and the search-UI interaction both land here, not in two stores).
 */
public record ResultDisposition(
    String interactionId, String docId, Kind kind, Contributor contributor, long occurredAtMs) {

  /**
   * The graded disposition tiers (§17.3): {@code SHOWN < OPENED < DWELLED < CITED < ACTED_ON},
   * plus {@code REFINED_WITHOUT_OPENING} — the one <em>negative</em>/recall-failure signal that
   * escapes §16's recall-blind ceiling (the user saw the whole set and opened nothing). The
   * kind→graded-label mapping is the label projection's concern (§17.5 / P5), not encoded here.
   */
  public enum Kind {
    SHOWN,
    OPENED,
    DWELLED,
    CITED,
    ACTED_ON,
    REFINED_WITHOUT_OPENING
  }

  /** The §17.3 contributors that feed the one canonical disposition stream. */
  public enum Contributor {
    SEARCH_INTERACTION,
    AGENT_CITATION,
    EXPLICIT_RATING
  }
}
