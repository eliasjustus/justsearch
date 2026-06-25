package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 561 P-A5 — the producer-owned excerpt boundary. The Worker that mints
 * {@code ContextCitation.excerpt} must snap to a WORD boundary so the FE can render it verbatim
 * without re-windowing (the cross-process residue of 559 §5 / observations C-2).
 */
@DisplayName("RagContextOps — P-A5 producer-owned word-boundary excerpt clamp")
final class RagContextOpsExcerptBoundaryTest {

  @Test
  @DisplayName("short text is returned verbatim (no truncation, no ellipsis)")
  void shortTextVerbatim() {
    String s = "a clean short excerpt";
    assertEquals(s, RagContextOps.clampExcerptToWordBoundary(s, 240));
  }

  @Test
  @DisplayName("null/empty are safe")
  void nullSafe() {
    assertEquals("", RagContextOps.clampExcerptToWordBoundary(null, 240));
    assertEquals("", RagContextOps.clampExcerptToWordBoundary("", 240));
  }

  @Test
  @DisplayName("long text truncates at a WORD boundary, never mid-word")
  void truncatesAtWordBoundary() {
    // 10-char words separated by spaces; cap at 25 → must cut at the space before a word, not inside.
    String text = "alphaword1 betaword22 gammaword3 deltaword4";
    String out = RagContextOps.clampExcerptToWordBoundary(text, 25);
    assertTrue(out.endsWith("..."), "truncated excerpt ends with an ellipsis");
    String body = out.substring(0, out.length() - 3); // strip the "..." ellipsis
    // the body is a prefix of the original made of WHOLE words — the next original char after the
    // body must be a space (we cut at a boundary), proving no mid-word split.
    assertTrue(
        text.startsWith(body),
        "the kept body is a clean prefix of the source: <" + body + ">");
    assertFalse(body.isBlank(), "the body is non-empty");
    char nextInSource = text.charAt(body.length());
    assertTrue(
        Character.isWhitespace(nextInSource),
        "the cut landed at a word boundary (next source char is whitespace)");
  }

  @Test
  @DisplayName("a single very long token still truncates (hard cut within lookback) + ellipsis")
  void longTokenHardCut() {
    String text = "x".repeat(300); // no whitespace at all
    String out = RagContextOps.clampExcerptToWordBoundary(text, 240);
    assertTrue(out.endsWith("..."));
    // hard cut at maxLen (240) since there is no whitespace to snap to.
    assertEquals(240 + 3, out.length(), "240 chars + the \"...\" ellipsis");
  }
}
