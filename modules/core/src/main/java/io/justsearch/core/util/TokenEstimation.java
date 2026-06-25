/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.util;

/**
 * Token estimation and truncation utilities for LLM context management.
 *
 * <p>Provides pure static methods for estimating token counts, computing safe input budgets,
 * and truncating content to fit within token limits. These utilities are shared across
 * modules that need to manage LLM context windows.
 *
 * <p>All methods are pure functions with no external state dependencies.
 */
public final class TokenEstimation {

  // Token management constants
  private static final int FIRST_PORTION_TOKENS = 2000;
  private static final int LAST_PORTION_TOKENS = 800;
  private static final int FULL_COVERAGE_OVERHEAD_TOKENS = 256;
  private static final int FULL_COVERAGE_SAFETY_TOKENS = 256;
  private static final int MIN_CONTEXT = 512;
  private static final int MIN_BUDGET = 256;

  private TokenEstimation() {
    // Utility class - no instantiation
  }

  // ==========================================================================
  // Token Estimation
  // ==========================================================================

  /**
   * Estimate tokens from text using heuristics.
   *
   * <p>Uses a combination of word-based (~1.3 tokens/word) and character-based estimates,
   * with adjustments for dense content (JSON, minified code) and CJK text.
   *
   * @param text the text to estimate tokens for
   * @return estimated token count, or 0 if text is null/empty
   */
  public static int estimateTokens(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    // We intentionally over-estimate rather than under-estimate to avoid llama-server 400s
    // (prompt too large). Word-based estimates fail badly for dense/minified content (e.g. JSON).
    final int len = text.length();

    int words = 0;
    int whitespace = 0;
    int nonAscii = 0;
    boolean inWord = false;
    for (int i = 0; i < len; i++) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) {
        whitespace++;
        inWord = false;
      } else if (!inWord) {
        inWord = true;
        words++;
      }
      if (c > 0x7F) {
        nonAscii++;
      }
    }

    int wordEstimate = (int) Math.ceil(words * 1.3);

    double whitespaceRatio = len == 0 ? 0.0 : (double) whitespace / (double) len;
    double nonAsciiRatio = len == 0 ? 0.0 : (double) nonAscii / (double) len;

    // Default heuristic for typical English-like text.
    int charEstimate = (int) Math.ceil(len / 4.0);

    // Dense/minified text (very low whitespace) tends to tokenize into more tokens per char.
    if (whitespaceRatio < 0.02) {
      charEstimate = (int) Math.ceil(len / 3.0);
    }

    // CJK / highly non-ASCII dense text can approach ~1 char per token.
    if (nonAsciiRatio > 0.5 && whitespaceRatio < 0.05) {
      charEstimate = len;
    }

    return Math.max(wordEstimate, charEstimate);
  }

  // ==========================================================================
  // Budget Calculation
  // ==========================================================================

  /**
   * Compute a safe input token budget given the server context window and the requested output budget.
   *
   * <p>This is a best-effort guard rail to avoid llama-server 400s when input + output exceeds n_ctx.
   * Uses formula: {@code raw = (ctx - out - 256 - 256) * 0.9}, with floor of 256.
   *
   * @param nCtx the context window size in tokens
   * @param outputMaxTokens the maximum output tokens reserved
   * @return safe input budget in tokens (minimum 256)
   */
  public static int computeSafeInputBudgetTokens(int nCtx, int outputMaxTokens) {
    int ctx = Math.max(MIN_CONTEXT, nCtx);
    int out = Math.max(0, outputMaxTokens);
    double raw = (ctx - out - FULL_COVERAGE_OVERHEAD_TOKENS - FULL_COVERAGE_SAFETY_TOKENS) * 0.9;
    return (int) Math.floor(Math.max(MIN_BUDGET, raw));
  }

  // ==========================================================================
  // Truncation Strategies
  // ==========================================================================

  /**
   * Truncate content using first/last strategy with warning marker.
   * Used for non-RAG content where document structure matters.
   *
   * @param content the content to truncate
   * @param maxContextTokens maximum token budget
   * @return truncation result with content and metadata
   */
  public static TruncationResult truncateIfNeeded(String content, int maxContextTokens) {
    if (content == null) {
      return new TruncationResult(null, false, 0, 0, -1);
    }
    int cap = Math.max(MIN_BUDGET, maxContextTokens);
    int tokens = estimateTokens(content);
    if (tokens <= cap) {
      return new TruncationResult(content, false, tokens, tokens, -1);
    }

    // Split content - keep beginning and end
    String[] words = content.split("\\s+");
    int totalWords = words.length;
    int lastTokens = Math.min(LAST_PORTION_TOKENS, Math.max(128, (int) Math.floor(cap * 0.25)));
    int firstTokens = Math.min(FIRST_PORTION_TOKENS, Math.max(128, cap - lastTokens));
    int firstWords = (int) (firstTokens / 1.3);
    int lastWords = (int) (lastTokens / 1.3);

    StringBuilder result = new StringBuilder();

    // First portion
    for (int i = 0; i < Math.min(firstWords, totalWords); i++) {
      result.append(words[i]).append(" ");
    }

    result.append("\n\n[... content truncated ...]\n\n");

    // Last portion
    int startLast = Math.max(firstWords, totalWords - lastWords);
    for (int i = startLast; i < totalWords; i++) {
      result.append(words[i]).append(" ");
    }

    return new TruncationResult(result.toString().trim(), true, tokens, cap, -1);
  }

  // ==========================================================================
  // Result Types
  // ==========================================================================

  /**
   * Result of truncation with metadata.
   *
   * @param content the truncated content (or original if not truncated)
   * @param truncated true if content was truncated
   * @param originalTokens estimated tokens in original content
   * @param usedTokens tokens in truncated content
   * @param sectionsKept number of sections kept after truncation (-1 if not applicable)
   */
  public record TruncationResult(
      String content,
      boolean truncated,
      int originalTokens,
      int usedTokens,
      int sectionsKept) {}
}
