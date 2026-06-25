package io.justsearch.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Strings.codePointSafePrefix")
class StringsTest {

  // U+1F600 GRINNING FACE = surrogate pair "😀" (2 UTF-16 code units).
  private static final String EMOJI = "😀";

  @Test
  @DisplayName("returns the whole string when it fits the budget")
  void fitsWithinBudget() {
    assertEquals("hello", Strings.codePointSafePrefix("hello", 5));
    assertEquals("hello", Strings.codePointSafePrefix("hello", 99));
  }

  @Test
  @DisplayName("never exceeds the budget (length-monotone)")
  void neverExceedsBudget() {
    assertTrue(Strings.codePointSafePrefix("abcdef", 3).length() <= 3);
    assertEquals("abc", Strings.codePointSafePrefix("abcdef", 3));
  }

  @Test
  @DisplayName("backs off one when the cut would orphan a high surrogate")
  void doesNotSplitSurrogatePair() {
    // "ab" + emoji(2 units) = 4 units. Budget 3 would cut between the high and low surrogate.
    String s = "ab" + EMOJI;
    String got = Strings.codePointSafePrefix(s, 3);
    assertEquals("ab", got, "must drop the whole pair, not orphan the high surrogate");
    assertFalse(Character.isHighSurrogate(got.charAt(got.length() - 1)), "no lone high surrogate");
  }

  @Test
  @DisplayName("keeps a complete surrogate pair when the budget includes both units")
  void keepsCompletePair() {
    String s = "ab" + EMOJI + "cd";
    assertEquals("ab" + EMOJI, Strings.codePointSafePrefix(s, 4));
  }

  @Test
  @DisplayName("the prefix is always a valid String (no lone surrogate) for any budget")
  void prefixAlwaysValidAcrossBudgets() {
    String s = "x" + EMOJI + "y" + EMOJI + "z" + EMOJI;
    for (int b = 0; b <= s.length() + 2; b++) {
      String got = Strings.codePointSafePrefix(s, b);
      assertTrue(got.length() <= Math.max(0, b), "budget respected at b=" + b);
      if (!got.isEmpty()) {
        char last = got.charAt(got.length() - 1);
        assertFalse(Character.isHighSurrogate(last), "no orphan high surrogate at b=" + b);
      }
      // And it is a genuine prefix of the source.
      assertTrue(s.startsWith(got), "must be a prefix at b=" + b);
    }
  }

  @Test
  @DisplayName("null / non-positive budget yield empty")
  void edgeInputs() {
    assertEquals("", Strings.codePointSafePrefix(null, 5));
    assertEquals("", Strings.codePointSafePrefix("abc", 0));
    assertEquals("", Strings.codePointSafePrefix("abc", -1));
  }
}
