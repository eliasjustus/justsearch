package io.justsearch.indexing.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ContextBudgeter")
class ContextBudgeterTest {

  @Test
  @DisplayName("counts header + separator overhead and truncates to maxChars")
  void countsOverheadAndTruncates() {
    // Choose a budget that leaves at least 1 char for the second section after separator+header.
    ContextBudgeter b = new ContextBudgeter(41);

    // First section: no separator
    assertEquals(ContextBudgeter.AppendResult.APPENDED, b.appendSection("a.txt", "hello"));
    assertEquals("[From: a.txt]\nhello", b.build());

    // Second section: will require separator + header and should truncate content to fit exactly.
    ContextBudgeter.AppendResult r = b.appendSection("b.txt", "WORLDWORLDWORLD");
    assertEquals(ContextBudgeter.AppendResult.APPENDED_TRUNCATED, r);

    assertEquals(41, b.build().length(), "Output must never exceed maxChars");
    assertTrue(b.build().contains(ContextBudgeter.SECTION_SEPARATOR), "Second section should include separator");
    assertTrue(b.build().contains("[From: b.txt]\n"), "Second section should include header");
  }

  @Test
  @DisplayName("stops when remaining budget cannot fit separator+header")
  void stopsWhenHeaderDoesNotFit() {
    ContextBudgeter b = new ContextBudgeter(10);
    // Header alone is longer than 10, so nothing can be appended.
    assertEquals(ContextBudgeter.AppendResult.STOPPED_BUDGET, b.appendSection("a.txt", "hi"));
    assertEquals("", b.build());
  }

  @Test
  @DisplayName("truncation never orphans a surrogate pair (tempdoc 554 §B.1)")
  void truncationIsSurrogateSafe() {
    // Header "[From: x]\n" = 10 chars overhead; budget 13 leaves 3 chars for content.
    // Content "ab😀" is 4 UTF-16 units, so it truncates — and the cut at 3 would split the emoji.
    ContextBudgeter b = new ContextBudgeter(13);
    ContextBudgeter.AppendResult r = b.appendSection("x", "ab😀");

    assertEquals(ContextBudgeter.AppendResult.APPENDED_TRUNCATED, r);
    String out = b.build();
    assertTrue(out.length() <= 13, "budget still respected");
    assertFalse(
        Character.isHighSurrogate(out.charAt(out.length() - 1)),
        "output must not end in an orphaned high surrogate");
    assertTrue(out.endsWith("ab"), "the whole emoji pair is dropped, leaving a valid prefix");
  }
}
