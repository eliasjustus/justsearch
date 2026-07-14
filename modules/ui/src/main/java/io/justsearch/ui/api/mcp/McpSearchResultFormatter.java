/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.ExcerptRegion;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.MatchSpan;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tempdoc 725 (design #2, increment W1) — turns the raw per-hit retrieval signals ({@code
 * matchSpans} / {@code excerptRegions}) into a legible rendering for the {@code justsearch_search}
 * MCP tool: which terms actually drove the match, and a preview window anchored on them instead of
 * a blind head-of-field truncation.
 *
 * <p>Pure, stateless helpers so the filtering/windowing rules are unit-testable without a live
 * backend. Consumers ({@link McpToolSurface}, {@link McpEvidenceProjection}) build hits via the
 * same {@code KnowledgeSearchResponse} records production uses — this class never re-derives the
 * evidence, it only filters/windows what the worker already produced.
 *
 * <p>Package-private: same-package helper, not part of the public API surface.
 */
final class McpSearchResultFormatter {

  private McpSearchResultFormatter() {}

  /** Cap on distinct informative terms surfaced per hit (first-seen order). */
  static final int MAX_INFORMATIVE_TERMS = 4;

  /** Terms of this length or shorter are dropped as non-distinctive. */
  static final int MIN_INFORMATIVE_TERM_LENGTH = 4;

  /** Appended verbatim whenever a rendered preview is a cut window of a larger source text. */
  static final String TRUNCATION_REMEDY = " (preview window; full text at the path above)";

  /** Window size for an excerpt-region-anchored preview (case a). */
  static final int REGION_WINDOW_CHARS = 300;

  /** Window size for a content_preview-anchored preview (case b). */
  static final int PREVIEW_WINDOW_CHARS = 200;

  // English stopwords. This is a text-legibility heuristic applied to the terms the (already
  // locale-invariant, ADR-0043) search pipeline already matched — not a per-language search lever;
  // no analyzer/field/spelling artifact is introduced.
  private static final Set<String> STOPWORDS =
      Set.of(
          "the", "a", "an", "of", "in", "on", "at", "to", "for", "and", "or", "is", "was", "are",
          "were", "be", "been", "with", "by", "as", "it", "its", "this", "that", "from", "has",
          "have", "had", "not", "but", "if", "then", "than", "so", "no", "do", "does", "did",
          "what", "which", "who", "whom", "when", "where", "why", "how", "all", "any", "both",
          "each", "more", "most", "other", "some", "such", "only", "own", "same", "s", "t", "can",
          "will", "just", "should", "now");

  /**
   * Strips newlines/control chars from corpus-sourced text before it is echoed back to an agent
   * (defensive against the echo-injection shape — matched terms are quoted corpus content, not
   * agent-authored text). Also strips {@code "} and {@code \} (tempdoc 725 review fix): a
   * corpus term containing a double quote would otherwise escape the quoted span in the rendered
   * {@code Matched: "term"} line — {@code "} breaks out of the quotes, {@code \} is stripped
   * alongside it for the same reason (no escaping scheme is defined for this plain-text render).
   */
  static String sanitize(String s) {
    if (s == null || s.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isISOControl(c) || c == '"' || c == '\\') {
        continue;
      }
      sb.append(c);
    }
    return sb.toString();
  }

  /**
   * Filters {@code spans} to the informative subset: lowercase-dedup by term, drop terms of length
   * &lt;= 3, drop English stopwords, cap at {@link #MAX_INFORMATIVE_TERMS} distinct terms kept in
   * first-seen order.
   */
  static List<MatchSpan> filterInformative(List<MatchSpan> spans) {
    if (spans == null || spans.isEmpty()) {
      return List.of();
    }
    List<MatchSpan> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (MatchSpan span : spans) {
      String term = sanitize(span.term());
      if (term.isBlank()) {
        continue;
      }
      String lower = term.toLowerCase(Locale.ROOT);
      if (lower.length() < MIN_INFORMATIVE_TERM_LENGTH) {
        continue;
      }
      if (STOPWORDS.contains(lower)) {
        continue;
      }
      if (!seen.add(lower)) {
        continue;
      }
      out.add(span);
      if (out.size() >= MAX_INFORMATIVE_TERMS) {
        break;
      }
    }
    return out;
  }

  /** Sanitized term strings from an already-{@link #filterInformative(List)} span list. */
  static List<String> informativeTerms(List<MatchSpan> informativeSpans) {
    List<String> out = new ArrayList<>(informativeSpans.size());
    for (MatchSpan s : informativeSpans) {
      out.add(sanitize(s.term()));
    }
    return out;
  }

  /**
   * Every span whose term passes the same informativeness filter as {@link
   * #filterInformative(List)} (min length, not a stopword) — but, unlike that method, WITHOUT the
   * term-level dedup. {@code filterInformative}'s dedup is right for the "Matched:" term list
   * (an agent doesn't need to see "cavby8" listed twice), but wrong for window placement: a
   * title-echo occurrence of a term near the head of a region and a payload occurrence of the
   * SAME term deep in the body are different candidates for where to anchor the preview window
   * (tempdoc 725 W2, live-validated on doc cavby8 — {@code filterInformative} collapsed both
   * occurrences to the char-4 title echo, so the window never reached the char-336 payload
   * sentence). Feed this list, not {@code filterInformative}'s, to {@link #bestWindow}.
   */
  static List<MatchSpan> informativeOccurrences(List<MatchSpan> spans) {
    if (spans == null || spans.isEmpty()) {
      return List.of();
    }
    List<MatchSpan> out = new ArrayList<>();
    for (MatchSpan span : spans) {
      String term = sanitize(span.term());
      if (term.isBlank()) {
        continue;
      }
      String lower = term.toLowerCase(Locale.ROOT);
      if (lower.length() < MIN_INFORMATIVE_TERM_LENGTH) {
        continue;
      }
      if (STOPWORDS.contains(lower)) {
        continue;
      }
      out.add(span);
    }
    return out;
  }

  /**
   * Picks the excerpt region whose nested spans contain the most informative terms; ties resolve
   * to the first region (input order preserved). Returns {@code null} for an empty/null list.
   */
  static ExcerptRegion selectBestRegion(List<ExcerptRegion> regions) {
    if (regions == null || regions.isEmpty()) {
      return null;
    }
    ExcerptRegion best = null;
    int bestCount = -1;
    for (ExcerptRegion region : regions) {
      int count = filterInformative(region.matchSpans()).size();
      if (count > bestCount) {
        bestCount = count;
        best = region;
      }
    }
    return best;
  }

  /** A rendered preview window: the windowed text, and whether it is a cut of a larger source. */
  record Window(String text, boolean truncated) {}

  /**
   * A window of up to {@code maxLen} chars starting at {@code startOffset}. Used as the head-window
   * fallback when no informative spans are available to drive {@link #bestWindow} (the excerpt
   * region already brackets the match, so a head window still starts on-topic rather than at an
   * arbitrary field offset).
   */
  static Window windowStartingAt(String text, int startOffset, int maxLen) {
    String safe = text == null ? "" : text;
    if (safe.length() <= maxLen) {
      return new Window(safe, false);
    }
    int start = Math.max(0, Math.min(startOffset, safe.length() - 1));
    int end = Math.min(safe.length(), start + maxLen);
    boolean truncated = start > 0 || end < safe.length();
    return new Window(safe.substring(start, end), truncated);
  }

  /**
   * A window of up to {@code maxLen} chars centered on {@code centerOffset} (case b: the raw
   * content_preview field value, so the window is centered rather than merely started there).
   */
  static Window windowCentered(String text, int centerOffset, int maxLen) {
    String safe = text == null ? "" : text;
    if (safe.length() <= maxLen) {
      return new Window(safe, false);
    }
    int start = centeredStart(safe, centerOffset, maxLen);
    int end = Math.min(safe.length(), start + maxLen);
    boolean truncated = start > 0 || end < safe.length();
    return new Window(safe.substring(start, end), truncated);
  }

  /** Shared start-offset math for a {@code maxLen}-wide window centered on {@code centerOffset}. */
  private static int centeredStart(String safe, int centerOffset, int maxLen) {
    int half = maxLen / 2;
    int start = Math.max(0, centerOffset - half);
    if (start + maxLen > safe.length()) {
      start = Math.max(0, safe.length() - maxLen);
    }
    return start;
  }

  /**
   * Picks the window (of up to {@code maxLen} chars, centered per {@link #windowCentered}) that
   * covers the most of {@code spans}; ties resolve to the window centered on the LATER span
   * (higher {@code startChar}) — a later occurrence tends to sit in body content rather than a
   * title echo near the head of the text (tempdoc 725 W2). Returns {@code null} for a null/empty
   * {@code spans} list; callers fall back to a head window in that case.
   */
  static Window bestWindow(String text, List<MatchSpan> spans, int maxLen) {
    if (spans == null || spans.isEmpty()) {
      return null;
    }
    String safe = text == null ? "" : text;
    int bestAnchor = spans.get(0).startChar();
    int bestCount = -1;
    for (MatchSpan span : spans) {
      int anchor = span.startChar();
      int start = centeredStart(safe, anchor, maxLen);
      int end = Math.min(safe.length(), start + maxLen);
      int count = 0;
      for (MatchSpan other : spans) {
        int otherStart = other.startChar();
        if (otherStart >= start && otherStart < end) {
          count++;
        }
      }
      if (count > bestCount || (count == bestCount && anchor > bestAnchor)) {
        bestCount = count;
        bestAnchor = anchor;
      }
    }
    return windowCentered(safe, bestAnchor, maxLen);
  }
}
