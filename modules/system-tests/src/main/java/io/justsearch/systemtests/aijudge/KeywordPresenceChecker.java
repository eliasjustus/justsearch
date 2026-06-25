/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.aijudge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI Judge component that checks for keyword presence in generated text.
 *
 * <p>This is a fast, deterministic quality check for AI-generated summaries.
 * Unlike ROUGE or embedding similarity, keyword checking is:
 * <ul>
 *   <li>Fast (< 1ms)</li>
 *   <li>Deterministic (no model required)</li>
 *   <li>Human-interpretable (clear pass/fail criteria)</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * KeywordPresenceChecker checker = new KeywordPresenceChecker();
 * Set<String> expected = Set.of("database", "connection", "pooling");
 * KeywordResult result = checker.check(summary, expected);
 *
 * assertTrue(result.coverage() >= 0.5, "Summary should mention 50% of keywords");
 * }</pre>
 */
public final class KeywordPresenceChecker {

  /**
   * Checks how many expected keywords appear in the text.
   *
   * @param text The text to check (e.g., AI-generated summary)
   * @param expectedKeywords Set of keywords that should appear
   * @return Result with coverage metrics
   */
  public KeywordResult check(String text, Set<String> expectedKeywords) {
    if (text == null || text.isBlank()) {
      return new KeywordResult(Set.of(), expectedKeywords, 0.0, "empty text");
    }

    if (expectedKeywords == null || expectedKeywords.isEmpty()) {
      return new KeywordResult(Set.of(), Set.of(), 1.0, "no keywords expected");
    }

    String normalizedText = text.toLowerCase(Locale.ROOT);
    Set<String> found = new HashSet<>();
    Set<String> missing = new HashSet<>();

    for (String keyword : expectedKeywords) {
      String normalizedKeyword = keyword.toLowerCase(Locale.ROOT);
      if (containsWord(normalizedText, normalizedKeyword)) {
        found.add(keyword);
      } else {
        missing.add(keyword);
      }
    }

    double coverage = (double) found.size() / expectedKeywords.size();
    String message = found.size() == expectedKeywords.size()
        ? "all keywords found"
        : "missing: " + missing;

    return new KeywordResult(found, missing, coverage, message);
  }

  /**
   * Checks keyword presence with stemming/lemmatization approximation.
   *
   * <p>This version considers variations like:
   * <ul>
   *   <li>database → databases</li>
   *   <li>optimize → optimization, optimizing</li>
   * </ul>
   *
   * @param text The text to check
   * @param expectedKeywords Keywords (can include stem forms)
   * @return Result with coverage metrics
   */
  public KeywordResult checkWithVariants(String text, Set<String> expectedKeywords) {
    if (text == null || text.isBlank()) {
      return new KeywordResult(Set.of(), expectedKeywords, 0.0, "empty text");
    }

    String normalizedText = text.toLowerCase(Locale.ROOT);
    Set<String> found = new HashSet<>();
    Set<String> missing = new HashSet<>();

    for (String keyword : expectedKeywords) {
      boolean matched = false;

      // Support "||"-delimited alternatives: "not found||not specified" matches if either appears
      String[] alternatives = keyword.split("\\|\\|");
      for (String alt : alternatives) {
        String base = alt.trim().toLowerCase(Locale.ROOT);
        List<String> variants = generateVariants(base);
        for (String variant : variants) {
          if (containsWord(normalizedText, variant)) {
            matched = true;
            break;
          }
        }
        if (matched) break;
      }

      if (matched) {
        found.add(keyword);
      } else {
        missing.add(keyword);
      }
    }

    double coverage = (double) found.size() / expectedKeywords.size();
    return new KeywordResult(found, missing, coverage,
        missing.isEmpty() ? "all keywords found" : "missing: " + missing);
  }

  /**
   * Extracts important keywords from source text.
   *
   * <p>Simple extraction based on:
   * <ul>
   *   <li>Word frequency</li>
   *   <li>Capitalization (proper nouns)</li>
   *   <li>Length (filtering stopwords)</li>
   * </ul>
   *
   * @param text Source text
   * @param maxKeywords Maximum keywords to extract
   * @return Set of extracted keywords
   */
  public Set<String> extractKeywords(String text, int maxKeywords) {
    if (text == null || text.isBlank()) {
      return Set.of();
    }

    // Simple stopwords
    Set<String> stopwords = Set.of(
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "must", "shall", "can", "need", "dare",
        "to", "of", "in", "for", "on", "with", "at", "by", "from", "as",
        "into", "through", "during", "before", "after", "above", "below",
        "between", "under", "again", "further", "then", "once", "here",
        "there", "when", "where", "why", "how", "all", "each", "few",
        "more", "most", "other", "some", "such", "no", "nor", "not",
        "only", "own", "same", "so", "than", "too", "very", "just",
        "and", "but", "if", "or", "because", "until", "while", "this",
        "that", "these", "those", "it", "its"
    );

    // Tokenize and count
    String[] words = text.toLowerCase(Locale.ROOT).split("\\W+");
    java.util.Map<String, Integer> counts = new java.util.HashMap<>();

    for (String word : words) {
      if (word.length() >= 3 && !stopwords.contains(word)) {
        counts.merge(word, 1, Integer::sum);
      }
    }

    // Sort by frequency and take top N
    return counts.entrySet().stream()
        .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
        .limit(maxKeywords)
        .map(java.util.Map.Entry::getKey)
        .collect(java.util.stream.Collectors.toSet());
  }

  private boolean containsWord(String text, String word) {
    // Normalize letter-digit boundaries: "approximately10" → "approximately 10"
    // This handles LLM outputs that concatenate words with numbers (e.g., "in2013", "at12-16")
    String normalizedText = LETTER_DIGIT_BOUNDARY.matcher(text).replaceAll("$1 $2");
    normalizedText = DIGIT_LETTER_BOUNDARY.matcher(normalizedText).replaceAll("$1 $2");

    // Use word boundary matching
    String pattern = "\\b" + Pattern.quote(word) + "\\b";
    return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(normalizedText).find();
  }

  /** Matches a letter immediately followed by a digit (e.g., "y1" in "approximately10"). */
  private static final Pattern LETTER_DIGIT_BOUNDARY =
      Pattern.compile("([a-zA-Z])(\\d)");

  /** Matches a digit immediately followed by a letter (e.g., "3B" in "Qwen3B"). */
  private static final Pattern DIGIT_LETTER_BOUNDARY =
      Pattern.compile("(\\d)([a-zA-Z])");

  private List<String> generateVariants(String base) {
    List<String> variants = new ArrayList<>();
    variants.add(base);

    // Common suffixes
    if (base.endsWith("e")) {
      variants.add(base + "s");       // database → databases
      variants.add(base + "d");       // configure → configured
      variants.add(base.substring(0, base.length() - 1) + "ing"); // configure → configuring
    } else if (base.endsWith("y")) {
      variants.add(base.substring(0, base.length() - 1) + "ies"); // query → queries
      variants.add(base.substring(0, base.length() - 1) + "ied"); // query → queried
    } else {
      variants.add(base + "s");       // model → models
      variants.add(base + "ed");      // model → modeled
      variants.add(base + "ing");     // model → modeling
    }

    // Common derivations
    if (base.endsWith("ize")) {
      variants.add(base.substring(0, base.length() - 3) + "ization");
    }
    if (base.endsWith("ate")) {
      variants.add(base.substring(0, base.length() - 1) + "ion");
    }

    return variants;
  }

  // === Result types ===

  /**
   * Result of keyword presence check.
   */
  public record KeywordResult(
      Set<String> found,
      Set<String> missing,
      double coverage,
      String message
  ) {
    /**
     * Returns true if coverage meets the threshold.
     */
    public boolean meetsThreshold(double threshold) {
      return coverage >= threshold;
    }

    /**
     * Returns true if all keywords were found.
     */
    public boolean allFound() {
      return missing.isEmpty();
    }

    /**
     * Returns count of found keywords.
     */
    public int foundCount() {
      return found.size();
    }

    /**
     * Returns count of missing keywords.
     */
    public int missingCount() {
      return missing.size();
    }
  }
}
