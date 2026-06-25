/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 385: Deterministic extraction of publication source references from structured queries.
 *
 * <p>Queries like "Does the TechCrunch article on Twitch's subscription split suggest..." contain
 * source references ("TechCrunch") that can be extracted without an LLM call. This class identifies
 * mentioned publication names and produces a topic remainder (the query with source references
 * stripped) for use as a source-filtered sub-query.
 *
 * <p>Two-phase approach ported from {@code scripts/jseval/jseval/agent_retrieval_eval.py}:
 *
 * <ol>
 *   <li>Multi-word sources: case-insensitive substring match against known vocabulary
 *   <li>Single-word sources: match only in syntactic publication-context patterns to avoid false
 *       positives (e.g., "November" matching as a source)
 * </ol>
 */
final class StructuredQueryAnalyzer {

  private StructuredQueryAnalyzer() {}

  /** Result of structured query analysis. */
  record StructuredQueryAnalysis(List<String> detectedSources, String topicRemainder) {

    StructuredQueryAnalysis {
      detectedSources = detectedSources == null ? List.of() : List.copyOf(detectedSources);
      topicRemainder = topicRemainder == null ? "" : topicRemainder;
    }
  }

  // -- Constants --

  /**
   * Well-known publication names not necessarily in the user's corpus. Used to recognize source
   * mentions in "null queries" (queries referencing sources absent from the index). Only multi-word
   * entries are used for substring matching; single-word entries require context patterns.
   */
  private static final List<String> EXTRA_KNOWN_SOURCES =
      List.of(
          "financial times",
          "the guardian",
          "the washington post",
          "al jazeera",
          "the telegraph",
          "daily mail",
          "sky news",
          "associated press",
          "usa today",
          "abc news",
          "nbc news",
          "politico",
          "the atlantic",
          "the economist",
          "rolling stone",
          "billboard",
          "times of india",
          "hindustan times",
          "reuters",
          "bloomberg");

  /**
   * Regex for single-word publication sources in syntactic publication context. Five alternation
   * branches (branches 1-3 ported from Python {@code _SINGLE_WORD_CONTEXT}, branches 4-5 added):
   *
   * <ol>
   *   <li>{@code article/report from/by/in CapWord} — captures group 1
   *   <li>{@code from/by/according to [an] [article from/by/in] CapWord article/report} — group 2
   *   <li>{@code 'Quoted Name' article/report} — group 3
   *   <li>{@code the CapWord article/report on/about} — group 4
   *   <li>{@code CapWord reported/said/noted/claimed/found that} — group 5
   * </ol>
   */
  private static final Pattern SINGLE_WORD_CONTEXT =
      Pattern.compile(
          "(?:article|report|piece|story|coverage|analysis)"
              + "(?:\\s+(?:from|by|in|published\\s+(?:by|in)))\\s+([A-Z][A-Za-z]+)"
              + "|(?:from|by|according\\s+to)\\s+(?:an?\\s+)?(?:article\\s+(?:from|by|in)\\s+)?"
              + "([A-Z][A-Za-z]+?)(?:\\s+(?:article|report|,|and|about|detailing))"
              + "|'([A-Z][A-Za-z\\s]+?)'\\s*(?:article|report)"
              + "|\\bthe\\s+([A-Z][A-Za-z]+)\\s+(?:article|report|piece|story)\\s+"
              + "(?:on|about|regarding|detailing|describing)\\b"
              + "|\\b([A-Z][A-Za-z]+)\\s+(?:reported|said|noted|claimed|found|stated)\\s+that\\b");

  /**
   * Template for matching source reference phrases like "the TechCrunch article on". The {@code %s}
   * placeholder is replaced with the escaped source name at runtime.
   */
  private static final String SOURCE_PHRASE_TEMPLATE =
      "(?:the|a|an)\\s+%s\\s+(?:article|report|piece|story|coverage)\\s+(?:on|about|"
          + "regarding|detailing|describing)\\s*";

  // -- Public API --

  /**
   * Analyze a query for structured source references.
   *
   * @param queryText the raw query string
   * @param corpusSources lowercased set of known {@code meta_source} values from the index
   * @return analysis with detected sources and the topic remainder
   */
  static StructuredQueryAnalysis analyze(String queryText, Set<String> corpusSources) {
    if (queryText == null || queryText.isBlank()) {
      return new StructuredQueryAnalysis(List.of(), "");
    }
    if (corpusSources == null) {
      corpusSources = Set.of();
    }

    String qLower = queryText.toLowerCase(Locale.ROOT);
    Set<String> found = new LinkedHashSet<>();

    // Phase 1: Multi-word sources — safe substring match
    List<String> multiWordVocab = collectMultiWordSources(corpusSources);
    for (String src : multiWordVocab) {
      if (qLower.contains(src)) {
        found.add(src);
      }
    }

    // Phase 2: Single-word sources — require publication context
    Matcher m = SINGLE_WORD_CONTEXT.matcher(queryText);
    while (m.find()) {
      for (int g = 1; g <= 5; g++) {
        String captured = m.group(g);
        if (captured != null) {
          String normalized = captured.toLowerCase(Locale.ROOT).strip();
          if (!normalized.isBlank()) {
            found.add(normalized);
          }
          break;
        }
      }
    }

    // Phase 3: Filter to sources that exist in the corpus or extra known list
    List<String> detectedSources = new ArrayList<>();
    Set<String> allKnown = allKnownSources(corpusSources);
    for (String src : found) {
      if (sourceInKnownSet(src, allKnown)) {
        detectedSources.add(src);
      }
    }

    // Phase 4: Build topic remainder by stripping source reference phrases
    String topicRemainder = buildTopicRemainder(queryText, detectedSources);

    return new StructuredQueryAnalysis(detectedSources, topicRemainder);
  }

  // -- Internals --

  /** Collect all multi-word (2+ tokens) sources from corpus + extra known list. */
  private static List<String> collectMultiWordSources(Set<String> corpusSources) {
    List<String> result = new ArrayList<>();
    for (String s : corpusSources) {
      if (isMultiWord(s)) result.add(s);
    }
    for (String s : EXTRA_KNOWN_SOURCES) {
      if (isMultiWord(s) && !corpusSources.contains(s)) result.add(s);
    }
    // Sort by length descending so longer names match first ("the new york times" before "new york")
    result.sort((a, b) -> Integer.compare(b.length(), a.length()));
    return result;
  }

  private static boolean isMultiWord(String s) {
    return s != null && s.indexOf(' ') >= 0;
  }

  /** Combine corpus sources with extra known sources into a single lookup set. */
  private static Set<String> allKnownSources(Set<String> corpusSources) {
    Set<String> all = new LinkedHashSet<>(corpusSources);
    for (String s : EXTRA_KNOWN_SOURCES) {
      all.add(s.toLowerCase(Locale.ROOT).strip());
    }
    return Collections.unmodifiableSet(all);
  }

  /**
   * Check if a source name matches any known source via equality or prefix/containment. Handles
   * cases like "fortune" matching "fortune magazine" in the corpus.
   */
  private static boolean sourceInKnownSet(String src, Set<String> known) {
    if (src.length() < 3) return false; // avoid trivial substring matches ("ab" in "fabrication")
    if (known.contains(src)) return true;
    for (String ks : known) {
      if (ks.startsWith(src) || ks.contains(src)) return true;
    }
    return false;
  }

  /**
   * Strip source reference phrases from the query to produce a topic-focused search string.
   * Removes patterns like "the TechCrunch article on" and bare source names.
   */
  private static String buildTopicRemainder(String queryText, List<String> sources) {
    if (sources.isEmpty()) return queryText;

    String result = queryText;
    for (String src : sources) {
      String escaped = Pattern.quote(src);

      // Try to strip the full phrase pattern first: "the [Source] article on"
      Pattern phrasePattern =
          Pattern.compile(
              String.format(SOURCE_PHRASE_TEMPLATE, escaped), Pattern.CASE_INSENSITIVE);
      result = phrasePattern.matcher(result).replaceAll(" ");

      // Also strip bare source name occurrences (case-insensitive)
      result =
          Pattern.compile("(?i)\\b" + escaped + "\\b").matcher(result).replaceAll(" ");
    }

    // Normalize whitespace
    return result.replaceAll("\\s{2,}", " ").strip();
  }
}
