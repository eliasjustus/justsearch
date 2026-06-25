/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 385: Rule-based classification of the expected answer format for a query.
 *
 * <p>This is separate from {@link QueryClassifier} (which classifies query intent:
 * INFORMATIONAL/NAVIGATIONAL/EXACT_MATCH). This classifier determines what kind of answer the query
 * expects: a yes/no comparison, a temporal analysis, or an entity/fact inference.
 *
 * <p>The classification is surfaced as {@code expectedAnswerType} on the search response, allowing
 * consumers (LLM prompts, agent tools) to constrain their answer format.
 */
final class AnswerTypeClassifier {

  private AnswerTypeClassifier() {}

  /** Expected answer type for a query. */
  enum AnswerType {
    /** Query compares 2+ sources/claims — expects yes/no answer. */
    COMPARISON,
    /** Query references 2+ time points — expects temporal analysis. */
    TEMPORAL,
    /** Default: query asks for an entity, fact, or explanation. */
    INFERENCE
  }

  private static final List<Pattern> COMPARISON_PATTERNS =
      List.of(
          // "does...while" removed (too greedy — "does this work while I'm away?" false-positives).
          // The suggest...while pattern below covers the actual MultiHop-RAG comparison structure.
          Pattern.compile("(?i)\\bdo\\s+both\\b"),
          Pattern.compile("(?i)\\bcompared\\s+to\\b"),
          Pattern.compile("(?i)\\bdifference\\s+between\\b"),
          Pattern.compile("(?i)\\bin\\s+contrast\\b"),
          Pattern.compile("(?i)\\bon\\s+the\\s+other\\s+hand\\b"),
          Pattern.compile("(?i)\\bwhereas\\b"),
          Pattern.compile("(?i)\\bagree\\s+(?:on|that|about)\\b"),
          Pattern.compile("(?i)\\bdisagree\\b"),
          Pattern.compile("(?i)\\bsuggest\\s+.+\\s+while\\b"));

  /**
   * Classify the expected answer type for a query.
   *
   * @param query the raw query text
   * @param detectedDateCount number of dates extracted by {@link TemporalQueryExtractor}
   * @return the classified answer type
   */
  static AnswerType classify(String query, int detectedDateCount) {
    if (query == null || query.isBlank()) return AnswerType.INFERENCE;

    for (Pattern p : COMPARISON_PATTERNS) {
      if (p.matcher(query).find()) return AnswerType.COMPARISON;
    }

    if (detectedDateCount >= 2) return AnswerType.TEMPORAL;

    return AnswerType.INFERENCE;
  }
}
