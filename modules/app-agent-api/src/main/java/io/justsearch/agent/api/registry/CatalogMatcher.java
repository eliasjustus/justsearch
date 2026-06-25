/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Approximate matching over catalog entries (tempdoc 499 §4.3).
 *
 * <p>Combines three strategies: Damerau-Levenshtein edit distance (typos),
 * token-set Jaccard similarity (structural mismatches), and shared-prefix
 * ratio (truncation). Results are ranked by combined confidence score.
 */
public interface CatalogMatcher {

  <T> List<ResolutionResult.Suggestion<T>> findAlternatives(
      String attemptedId, List<T> candidates, Function<T, String> idExtractor,
      int maxResults);

  static CatalogMatcher defaultMatcher() {
    return new DefaultCatalogMatcher();
  }

  static CatalogMatcher noop() {
    return new CatalogMatcher() {
      @Override
      public <T> List<ResolutionResult.Suggestion<T>> findAlternatives(
          String attemptedId, List<T> candidates, Function<T, String> idExtractor,
          int maxResults) {
        return List.of();
      }
    };
  }
}

final class DefaultCatalogMatcher implements CatalogMatcher {

  private static final double MIN_CONFIDENCE = 0.25;

  @Override
  public <T> List<ResolutionResult.Suggestion<T>> findAlternatives(
      String attemptedId, List<T> candidates, Function<T, String> idExtractor,
      int maxResults) {
    if (attemptedId == null || attemptedId.isEmpty() || candidates.isEmpty()) {
      return List.of();
    }

    var scored = new ArrayList<ResolutionResult.Suggestion<T>>();

    for (T candidate : candidates) {
      String candidateId = idExtractor.apply(candidate);
      double confidence = computeConfidence(attemptedId, candidateId);
      if (confidence >= MIN_CONFIDENCE) {
        String rationale = buildRationale(attemptedId, candidateId);
        scored.add(new ResolutionResult.Suggestion<>(candidate, candidateId, confidence, rationale));
      }
    }

    scored.sort(Comparator.comparingDouble(ResolutionResult.Suggestion<T>::confidence).reversed());

    return scored.size() <= maxResults
        ? List.copyOf(scored)
        : List.copyOf(scored.subList(0, maxResults));
  }

  static double computeConfidence(String query, String candidate) {
    double dlScore = editDistanceScore(query, candidate);
    double tokenScore = tokenOverlapScore(query, candidate);
    double prefixScore = prefixScore(query, candidate);
    return 0.50 * dlScore + 0.30 * tokenScore + 0.20 * prefixScore;
  }

  private static double editDistanceScore(String a, String b) {
    int distance = damerauLevenshtein(a, b);
    int maxLen = Math.max(a.length(), b.length());
    if (maxLen == 0) return 1.0;
    return Math.max(0.0, 1.0 - (double) distance / maxLen);
  }

  private static double tokenOverlapScore(String a, String b) {
    Set<String> tokensA = tokenize(a);
    Set<String> tokensB = tokenize(b);
    if (tokensA.isEmpty() && tokensB.isEmpty()) return 1.0;

    Set<String> intersection = new HashSet<>(tokensA);
    intersection.retainAll(tokensB);

    Set<String> union = new HashSet<>(tokensA);
    union.addAll(tokensB);

    if (union.isEmpty()) return 0.0;
    return (double) intersection.size() / union.size();
  }

  private static double prefixScore(String a, String b) {
    int maxLen = Math.max(a.length(), b.length());
    if (maxLen == 0) return 1.0;
    int shared = 0;
    int minLen = Math.min(a.length(), b.length());
    for (int i = 0; i < minLen; i++) {
      if (a.charAt(i) == b.charAt(i)) shared++;
      else break;
    }
    return (double) shared / maxLen;
  }

  private static Set<String> tokenize(String id) {
    var tokens = new HashSet<String>();
    for (String part : id.split("[.\\-]")) {
      if (!part.isEmpty()) tokens.add(part);
    }
    return tokens;
  }

  static int damerauLevenshtein(String a, String b) {
    int lenA = a.length();
    int lenB = b.length();

    if (lenA == 0) return lenB;
    if (lenB == 0) return lenA;

    int[][] d = new int[lenA + 1][lenB + 1];

    for (int i = 0; i <= lenA; i++) d[i][0] = i;
    for (int j = 0; j <= lenB; j++) d[0][j] = j;

    for (int i = 1; i <= lenA; i++) {
      for (int j = 1; j <= lenB; j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;

        d[i][j] = Math.min(
            Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
            d[i - 1][j - 1] + cost);

        if (i > 1 && j > 1
            && a.charAt(i - 1) == b.charAt(j - 2)
            && a.charAt(i - 2) == b.charAt(j - 1)) {
          d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + cost);
        }
      }
    }

    return d[lenA][lenB];
  }

  private static String buildRationale(String query, String candidate) {
    int dl = damerauLevenshtein(query, candidate);
    double tokenJaccard = tokenOverlapScore(query, candidate);
    return "edit-distance=" + dl + ", token-overlap=" + String.format("%.2f", tokenJaccard);
  }
}
