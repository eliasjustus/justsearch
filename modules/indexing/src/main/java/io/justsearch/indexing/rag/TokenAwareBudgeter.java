/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.rag;

import io.justsearch.core.util.Strings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Token-aware context budgeter that respects LLM token limits.
 *
 * <p>This budgeter uses actual token counts when available (via the llama-server /tokenize
 * endpoint), falling back to character-based estimation when tokenization is unavailable.
 *
 * <p>Usage:
 * <pre>{@code
 * TokenAwareBudgeter budgeter = new TokenAwareBudgeter(3000, inferenceManager::countTokens);
 * budgeter.appendSection("doc1.pdf", chunkContent);
 * String context = budgeter.build();
 * }</pre>
 */
public final class TokenAwareBudgeter {

  /** Separator between sections - delegates to canonical source. */
  public static final String SECTION_SEPARATOR = ContextBudgeter.SECTION_SEPARATOR;

  /** Default label used when a section label is missing. */
  public static final String DEFAULT_SOURCE_LABEL = "unknown";

  /** Conservative fallback: assume 4 characters per token on average. */
  private static final int FALLBACK_CHARS_PER_TOKEN = 4;

  /** Outcome of trying to append a section. */
  public enum AppendResult {
    /** Section content was blank, so nothing changed and callers may continue. */
    SKIPPED_EMPTY,
    /** A full section was appended and callers may continue. */
    APPENDED,
    /** The section was appended but its content had to be truncated to fit; callers should stop. */
    APPENDED_TRUNCATED,
    /** No more content can be appended without exceeding the budget; callers should stop. */
    STOPPED_BUDGET
  }

  private final int maxTokens;
  private final Function<String, Optional<Integer>> tokenCounter;
  private final StringBuilder sb = new StringBuilder();
  private final List<ContextBudgeter.Section> sections = new ArrayList<>();
  private int estimatedTokens = 0;
  private boolean first = true;
  private boolean tokenCounterAvailable = true; // Assume available until proven otherwise

  /**
   * Creates a token-aware budgeter.
   *
   * @param maxTokens maximum token budget for the context
   * @param tokenCounter function that counts tokens in a string, or empty if unavailable
   */
  public TokenAwareBudgeter(int maxTokens, Function<String, Optional<Integer>> tokenCounter) {
    if (maxTokens <= 0) {
      throw new IllegalArgumentException("maxTokens must be > 0");
    }
    this.maxTokens = maxTokens;
    this.tokenCounter = tokenCounter != null ? tokenCounter : s -> Optional.empty();
  }

  /**
   * Creates a token-aware budgeter with character-based fallback only.
   *
   * <p>Use this when no tokenizer is available. All estimates will use the
   * conservative 4 chars/token ratio.
   *
   * @param maxTokens maximum token budget for the context
   */
  public TokenAwareBudgeter(int maxTokens) {
    this(maxTokens, null);
    this.tokenCounterAvailable = false;
  }

  public int maxTokens() {
    return maxTokens;
  }

  public int estimatedTokens() {
    return estimatedTokens;
  }

  public int length() {
    return sb.length();
  }

  public boolean isEmpty() {
    return sb.isEmpty();
  }

  /**
   * Returns an unmodifiable list of sections that were successfully appended.
   *
   * <p>Matches {@link ContextBudgeter#sections()} semantics so callers can use the same
   * section/citation linkage regardless of whether char- or token-budgeting is used.
   */
  public List<ContextBudgeter.Section> sections() {
    return Collections.unmodifiableList(sections);
  }

  public String build() {
    return sb.toString();
  }

  /**
   * Appends one section as:
   *
   * <pre>
   * [From: label]
   * content
   * </pre>
   *
   * with {@link #SECTION_SEPARATOR} between sections.
   *
   * <p>The output will never exceed {@link #maxTokens()} tokens.
   */
  public AppendResult appendSection(String sourceLabel, String content) {
    if (content == null || content.isBlank()) {
      return AppendResult.SKIPPED_EMPTY;
    }

    String label = (sourceLabel == null || sourceLabel.isBlank()) ? DEFAULT_SOURCE_LABEL : sourceLabel;
    String header = "[From: " + label + "]\n";
    String sep = first ? "" : SECTION_SEPARATOR;

    // Calculate token budget remaining
    int remaining = maxTokens - estimatedTokens;
    if (remaining <= 0) {
      return AppendResult.STOPPED_BUDGET;
    }

    // Count tokens for overhead (separator + header)
    int overheadTokens = countOrEstimate(sep + header);
    if (overheadTokens >= remaining) {
      return AppendResult.STOPPED_BUDGET;
    }

    int availableForContent = remaining - overheadTokens;
    if (availableForContent <= 0) {
      return AppendResult.STOPPED_BUDGET;
    }

    // Count tokens for the content
    int contentTokens = countOrEstimate(content);

    if (contentTokens > availableForContent) {
      // Need to truncate content to fit
      String truncatedContent = truncateToTokenBudget(content, availableForContent);
      if (truncatedContent.isEmpty()) {
        return AppendResult.STOPPED_BUDGET;
      }

      sb.append(sep).append(header).append(truncatedContent);
      sections.add(new ContextBudgeter.Section(label, truncatedContent, true, sections.size()));
      estimatedTokens += overheadTokens + countOrEstimate(truncatedContent);
      first = false;
      return AppendResult.APPENDED_TRUNCATED;
    }

    // Full content fits
    sb.append(sep).append(header).append(content);
    sections.add(new ContextBudgeter.Section(label, content, false, sections.size()));
    estimatedTokens += overheadTokens + contentTokens;
    first = false;
    return AppendResult.APPENDED;
  }

  /**
   * Counts tokens using the tokenizer if available, otherwise estimates from character count.
   */
  private int countOrEstimate(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }

    if (tokenCounterAvailable) {
      Optional<Integer> count = tokenCounter.apply(text);
      if (count.isPresent()) {
        return count.get();
      }
      // Tokenizer failed; fall back to estimation for rest of session
      tokenCounterAvailable = false;
    }

    // Fallback: estimate from character count
    return Math.max(1, text.length() / FALLBACK_CHARS_PER_TOKEN);
  }

  /**
   * Truncates content to approximately fit within a token budget.
   *
   * <p>Uses binary search to find the longest prefix that fits. When using
   * character-based estimation, this is exact. When using the tokenizer,
   * this is approximate but conservative.
   */
  private String truncateToTokenBudget(String content, int maxContentTokens) {
    if (content.isEmpty() || maxContentTokens <= 0) {
      return "";
    }

    // For character-based estimation, we can calculate exactly
    if (!tokenCounterAvailable) {
      int maxChars = maxContentTokens * FALLBACK_CHARS_PER_TOKEN;
      if (content.length() <= maxChars) {
        return content;
      }
      return Strings.codePointSafePrefix(content, maxChars);
    }

    // Binary search for the longest prefix that fits
    int low = 0;
    int high = content.length();
    int bestFit = 0;

    while (low <= high) {
      int mid = (low + high) / 2;
      String prefix = content.substring(0, mid);
      int tokens = countOrEstimate(prefix);

      if (tokens <= maxContentTokens) {
        bestFit = mid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }

    // Ensure we don't cut in the middle of a word if possible
    if (bestFit > 0 && bestFit < content.length()) {
      int wordBoundary = content.lastIndexOf(' ', bestFit);
      if (wordBoundary > bestFit * 0.8) { // Don't lose more than 20%
        bestFit = wordBoundary;
      }
    }

    return bestFit > 0 ? Strings.codePointSafePrefix(content, bestFit) : "";
  }
}
