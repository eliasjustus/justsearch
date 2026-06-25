package io.justsearch.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F3: Drift-prevention test for DocumentService.SECTION_SEPARATOR.
 *
 * <p>Validates that the separator constant in DocumentService matches the canonical value defined
 * in the indexing module (ContextBudgeter.SECTION_SEPARATOR).
 *
 * <p>This test prevents drift where the API module's separator could diverge from the indexing
 * module's separator, breaking RAG context formatting.
 *
 * <p>Note: Due to module boundaries, this test asserts the literal value rather than using
 * assertSame. A corresponding test in the indexing module validates the canonical source.
 */
@DisplayName("DocumentService separator drift prevention")
class SeparatorConstantDriftTest {

  /**
   * The canonical separator value that must match ContextBudgeter.SECTION_SEPARATOR.
   *
   * <p>If this test fails, it means someone changed DocumentService.SECTION_SEPARATOR without
   * updating the indexing module, or vice versa.
   */
  private static final String CANONICAL_SEPARATOR = "\n\n---\n\n";

  @Test
  @DisplayName("DocumentService.SECTION_SEPARATOR matches canonical value")
  void documentServiceSeparatorMatchesCanonical() {
    assertEquals(
        CANONICAL_SEPARATOR,
        DocumentService.SECTION_SEPARATOR,
        "DocumentService.SECTION_SEPARATOR must be '\\n\\n---\\n\\n' to match ContextBudgeter.SECTION_SEPARATOR");
  }

  @Test
  @DisplayName("Separator is a valid markdown horizontal rule")
  void separatorIsValidMarkdownHorizontalRule() {
    // The separator should contain "---" which renders as an HR in markdown
    String sep = DocumentService.SECTION_SEPARATOR;
    assertEquals("\n\n---\n\n", sep, "Separator must be newlines + HR + newlines");

    // Verify structure: 2 newlines, 3 dashes, 2 newlines
    String[] parts = sep.split("---", -1);
    assertEquals(2, parts.length, "Separator must split into exactly 2 parts on '---'");
    assertEquals("\n\n", parts[0], "Pre-HR must be two newlines");
    assertEquals("\n\n", parts[1], "Post-HR must be two newlines");
  }
}
