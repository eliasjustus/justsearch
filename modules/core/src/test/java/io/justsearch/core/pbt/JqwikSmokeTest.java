package io.justsearch.core.pbt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.util.Strings;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

/**
 * Property test for {@link Strings#codePointSafePrefix}: it obeys its conservation/prefix law over
 * generated inputs (including adversarial Unicode) — never exceeds the budget, is always a genuine
 * prefix, and never ends in an orphaned high surrogate. Also doubles as the jqwik toolchain smoke.
 * (Tempdoc 554 — the property-test floor; runs in the normal suite.)
 */
class JqwikSmokeTest {

  @Property(tries = 500)
  void codePointSafePrefix_neverExceedsBudget_andNeverOrphansASurrogate(
      @ForAll @StringLength(max = 64) String s, @ForAll @IntRange(min = -3, max = 70) int budget) {
    String got = Strings.codePointSafePrefix(s, budget);

    // Conservation: never longer than the (clamped) budget.
    assertTrue(got.length() <= Math.max(0, budget), "budget respected");
    // Prefix: the result is a genuine prefix of the source.
    assertTrue(s.startsWith(got), "is a prefix");
    // Validity: never ends in an orphaned high surrogate.
    if (!got.isEmpty()) {
      assertTrue(
          !Character.isHighSurrogate(got.charAt(got.length() - 1)), "no orphan high surrogate");
    }
  }
}
