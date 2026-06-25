package io.justsearch.indexerworker.text;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link TextQualityAnalyzer}.
 */
class TextQualityAnalyzerTest {

  @Nested
  @DisplayName("isGarbageText()")
  class IsGarbageTextTests {

    @Test
    @DisplayName("null text is garbage")
    void nullTextIsGarbage() {
      assertTrue(TextQualityAnalyzer.isGarbageText(null));
    }

    @Test
    @DisplayName("empty text is garbage")
    void emptyTextIsGarbage() {
      assertTrue(TextQualityAnalyzer.isGarbageText(""));
    }

    @Test
    @DisplayName("short text (< 100 chars) is garbage")
    void shortTextIsGarbage() {
      String shortText = "This is too short to be useful.";
      assertTrue(shortText.length() < TextQualityAnalyzer.MIN_GOOD_TEXT_LENGTH);
      assertTrue(TextQualityAnalyzer.isGarbageText(shortText));
    }

    @Test
    @DisplayName("text with low alphanumeric ratio is garbage")
    void lowAlphanumericRatioIsGarbage() {
      // 100+ characters but mostly symbols
      String symbolText = "!@#$%^&*()_+{}|:<>?~`-=[]\\;',./".repeat(10);
      assertTrue(symbolText.length() >= TextQualityAnalyzer.MIN_GOOD_TEXT_LENGTH);
      assertTrue(TextQualityAnalyzer.isGarbageText(symbolText));
    }

    @Test
    @DisplayName("text with font encoding markers (cid:) is garbage")
    void fontEncodingMarkersIsGarbage() {
      String cidText = "Some text followed by font encoding (cid:123) and more characters " +
          "to make this over 100 characters long so it passes the length check easily.";
      assertTrue(cidText.length() >= TextQualityAnalyzer.MIN_GOOD_TEXT_LENGTH);
      assertTrue(TextQualityAnalyzer.isGarbageText(cidText));
    }

    @Test
    @DisplayName("text with Unicode replacement character is garbage")
    void unicodeReplacementCharIsGarbage() {
      String replacementText = "Normal text with \uFFFD replacement characters scattered " +
          "throughout the document content to trigger the garbage detection heuristic.";
      assertTrue(replacementText.length() >= TextQualityAnalyzer.MIN_GOOD_TEXT_LENGTH);
      assertTrue(TextQualityAnalyzer.isGarbageText(replacementText));
    }

    @Test
    @DisplayName("normal English text is NOT garbage")
    void normalEnglishTextIsNotGarbage() {
      String normalText = "The quick brown fox jumps over the lazy dog. " +
          "This is a perfectly normal sentence with good alphanumeric content. " +
          "It should pass all quality checks without any issues at all.";
      assertTrue(normalText.length() >= TextQualityAnalyzer.MIN_GOOD_TEXT_LENGTH);
      assertFalse(TextQualityAnalyzer.isGarbageText(normalText));
    }

    @Test
    @DisplayName("text with numbers is NOT garbage")
    void textWithNumbersIsNotGarbage() {
      String numbersText = "Invoice 12345 dated 2023-12-01 for amount 999.99 USD. " +
          "Customer ID: ABC123. Order reference: ORD-2023-456789. " +
          "This document contains typical business data.";
      assertTrue(numbersText.length() >= TextQualityAnalyzer.MIN_GOOD_TEXT_LENGTH);
      assertFalse(TextQualityAnalyzer.isGarbageText(numbersText));
    }

    @Test
    @DisplayName("text with acceptable punctuation is NOT garbage")
    void textWithPunctuationIsNotGarbage() {
      String punctuatedText = "Hello, World! This is a test. Does it work? Yes, it does. " +
          "Here's some more content: (parentheses), [brackets], and \"quotes\". " +
          "The ratio should still be acceptable.";
      assertTrue(punctuatedText.length() >= TextQualityAnalyzer.MIN_GOOD_TEXT_LENGTH);
      assertFalse(TextQualityAnalyzer.isGarbageText(punctuatedText));
    }

    @Test
    @DisplayName("non-ASCII accented text (properly decoded) is NOT garbage")
    void properlyDecodedAccentedTextIsNotGarbage() {
      // Verifies that non-ASCII accented text (as would appear after correct
      // decoding of Windows-1252 or ISO-8859-1 files) is not falsely flagged.
      String text = "Le r\u00E9sum\u00E9 du caf\u00E9 est tr\u00E8s int\u00E9ressant. "
          + "Les donn\u00E9es montrent que la qualit\u00E9 na\u00EFve du syst\u00E8me "
          + "d\u00E9passe les attentes pr\u00E9vues pour cette p\u00E9riode.";
      assertTrue(text.length() >= TextQualityAnalyzer.MIN_GOOD_TEXT_LENGTH);
      assertFalse(TextQualityAnalyzer.isGarbageText(text));
    }

    @Test
    @DisplayName("text exactly at minimum length boundary")
    void textAtMinimumLengthBoundary() {
      // Exactly 99 chars should be garbage, 100 should be evaluated further
      String text99 = "a".repeat(99);
      String text100 = "a".repeat(100);

      assertEquals(99, text99.length());
      assertEquals(100, text100.length());

      assertTrue(TextQualityAnalyzer.isGarbageText(text99));
      assertFalse(TextQualityAnalyzer.isGarbageText(text100)); // All 'a' = 100% alphanumeric
    }
  }

  @Nested
  @DisplayName("getAlphanumericRatio()")
  class GetAlphanumericRatioTests {

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("null and empty strings return 0.0")
    void nullAndEmptyReturnZero(String input) {
      assertEquals(0.0, TextQualityAnalyzer.getAlphanumericRatio(input));
    }

    @Test
    @DisplayName("all letters returns 1.0")
    void allLettersReturnsOne() {
      assertEquals(1.0, TextQualityAnalyzer.getAlphanumericRatio("HelloWorld"));
    }

    @Test
    @DisplayName("all digits returns 1.0")
    void allDigitsReturnsOne() {
      assertEquals(1.0, TextQualityAnalyzer.getAlphanumericRatio("1234567890"));
    }

    @Test
    @DisplayName("all symbols returns 0.0")
    void allSymbolsReturnsZero() {
      assertEquals(0.0, TextQualityAnalyzer.getAlphanumericRatio("!@#$%^&*()"));
    }

    @Test
    @DisplayName("mixed content returns correct ratio")
    void mixedContentReturnsCorrectRatio() {
      // "Hello World!" = 10 alphanumeric, 12 total (including space and !)
      double ratio = TextQualityAnalyzer.getAlphanumericRatio("Hello World!");
      assertEquals(10.0 / 12.0, ratio, 0.001);
    }
  }
}
