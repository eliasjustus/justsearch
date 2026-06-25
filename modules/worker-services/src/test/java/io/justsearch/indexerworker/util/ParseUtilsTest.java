package io.justsearch.indexerworker.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ParseUtils}. */
class ParseUtilsTest {

  @Nested
  class ParseIntSafeTests {

    @Test
    void nullValue_returnsDefault() {
      assertEquals(42, ParseUtils.parseIntSafe(null, 42));
    }

    @Test
    void emptyString_returnsDefault() {
      assertEquals(42, ParseUtils.parseIntSafe("", 42));
    }

    @Test
    void blankString_returnsDefault() {
      assertEquals(42, ParseUtils.parseIntSafe("   ", 42));
    }

    @Test
    void validInteger_returnsParsedValue() {
      assertEquals(123, ParseUtils.parseIntSafe("123", 0));
    }

    @Test
    void negativeInteger_returnsParsedValue() {
      assertEquals(-456, ParseUtils.parseIntSafe("-456", 0));
    }

    @Test
    void integerWithWhitespace_trimsThenParses() {
      assertEquals(789, ParseUtils.parseIntSafe("  789  ", 0));
    }

    @Test
    void invalidString_returnsDefault() {
      assertEquals(42, ParseUtils.parseIntSafe("not a number", 42));
    }

    @Test
    void floatString_returnsDefault() {
      assertEquals(42, ParseUtils.parseIntSafe("3.14", 42));
    }

    @Test
    void overflowValue_returnsDefault() {
      assertEquals(42, ParseUtils.parseIntSafe("9999999999999999999", 42));
    }

    @Test
    void zeroValue_returnsZero() {
      assertEquals(0, ParseUtils.parseIntSafe("0", 99));
    }

    @Test
    void maxInt_parsesCorrectly() {
      assertEquals(Integer.MAX_VALUE, ParseUtils.parseIntSafe(String.valueOf(Integer.MAX_VALUE), 0));
    }

    @Test
    void minInt_parsesCorrectly() {
      assertEquals(Integer.MIN_VALUE, ParseUtils.parseIntSafe(String.valueOf(Integer.MIN_VALUE), 0));
    }
  }

  @Nested
  class ExtractFilenameTests {

    @Test
    void nullPath_returnsUnknown() {
      assertEquals("unknown", ParseUtils.extractFilename(null));
    }

    @Test
    void emptyPath_returnsEmpty() {
      assertEquals("", ParseUtils.extractFilename(""));
    }

    @Test
    void filenameOnly_returnsFilename() {
      assertEquals("file.txt", ParseUtils.extractFilename("file.txt"));
    }

    @Test
    void unixPath_extractsFilename() {
      assertEquals("file.txt", ParseUtils.extractFilename("/home/user/docs/file.txt"));
    }

    @Test
    void windowsPath_extractsFilename() {
      assertEquals("file.txt", ParseUtils.extractFilename("C:\\Users\\docs\\file.txt"));
    }

    @Test
    void mixedSeparators_extractsFilename() {
      // Takes the max of both slash positions
      assertEquals("file.txt", ParseUtils.extractFilename("/home/user\\docs/file.txt"));
    }

    @Test
    void trailingSlash_returnsEmpty() {
      assertEquals("", ParseUtils.extractFilename("/home/user/docs/"));
    }

    @Test
    void rootPath_extractsFilename() {
      assertEquals("file.txt", ParseUtils.extractFilename("/file.txt"));
    }

    @Test
    void dotInFilename_preservesDot() {
      assertEquals("my.config.json", ParseUtils.extractFilename("/etc/my.config.json"));
    }

    @Test
    void spaceInFilename_preservesSpace() {
      assertEquals("my file.txt", ParseUtils.extractFilename("/home/my file.txt"));
    }
  }

  @Nested
  class TrimToLengthTests {

    @Test
    void nullContent_returnsEmpty() {
      assertEquals("", ParseUtils.trimToLength(null, 100));
    }

    @Test
    void contentWithinLimit_returnsUnchanged() {
      assertEquals("hello", ParseUtils.trimToLength("hello", 100));
    }

    @Test
    void contentExactlyAtLimit_returnsUnchanged() {
      assertEquals("hello", ParseUtils.trimToLength("hello", 5));
    }

    @Test
    void contentExceedsLimit_truncates() {
      assertEquals("hel", ParseUtils.trimToLength("hello", 3));
    }

    @Test
    void emptyContent_returnsEmpty() {
      assertEquals("", ParseUtils.trimToLength("", 100));
    }

    @Test
    void zeroLimit_returnsEmpty() {
      assertEquals("", ParseUtils.trimToLength("hello", 0));
    }

    @Test
    void largeContent_truncatesToLimit() {
      String large = "a".repeat(10000);
      String result = ParseUtils.trimToLength(large, 100);
      assertEquals(100, result.length());
      assertEquals("a".repeat(100), result);
    }

    @Test
    void unicodeContent_truncatesByJavaCharacter() {
      // Basic multilingual plane characters (1 Java char each)
      String unicode = "äöüßé";
      String result = ParseUtils.trimToLength(unicode, 3);
      assertEquals(3, result.length());
      assertEquals("äöü", result);
    }
  }
}
