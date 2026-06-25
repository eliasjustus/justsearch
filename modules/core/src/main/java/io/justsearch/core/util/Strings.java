/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.util;

/**
 * Small string utilities shared across the core/indexing modules.
 *
 * <p>The defining concern here is the <b>conservation/prefix law</b> for budget-bounded truncation:
 * a truncated prefix must (a) never exceed its character budget and (b) be a <i>valid</i> prefix —
 * never ending mid-surrogate (a lone high surrogate is not a valid {@code String} character and
 * corrupts downstream UTF-8 / rendering). Char-based {@code substring(0, n)} satisfies (a) but not
 * (b); {@link #codePointSafePrefix} satisfies both. (Tempdoc 554 §B.1: the bug-class fix for the
 * RAG-budget truncation sites.)
 */
public final class Strings {

  private Strings() {}

  /**
   * Returns the longest prefix of {@code s} with at most {@code maxChars} UTF-16 code units that does
   * <b>not</b> split a surrogate pair at its boundary.
   *
   * <p>Length-monotone: the result length is {@code min(s.length(), maxChars)} or one less (only when
   * cutting there would orphan a high surrogate), so every {@code length() <= budget} invariant a
   * caller relied on with raw {@code substring} is preserved — the result can only be shorter, never
   * longer.
   *
   * @param s the source string (a {@code null} or empty source yields {@code ""})
   * @param maxChars the maximum number of UTF-16 code units to keep ({@code <= 0} yields {@code ""})
   * @return a surrogate-safe prefix
   */
  public static String codePointSafePrefix(String s, int maxChars) {
    if (s == null || maxChars <= 0) {
      return "";
    }
    if (s.length() <= maxChars) {
      return s;
    }
    int n = maxChars;
    // If the cut lands between a high surrogate (at n-1) and its low surrogate (at n), the
    // prefix would end in an orphaned high surrogate — back off one to drop the whole pair.
    if (Character.isHighSurrogate(s.charAt(n - 1)) && Character.isLowSurrogate(s.charAt(n))) {
      n--;
    }
    return s.substring(0, n);
  }
}
