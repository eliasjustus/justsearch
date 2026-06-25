package io.justsearch.benchmarks.util;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BenchmarkCli}. */
class BenchmarkCliTest {

  @Nested
  class ParseStringTests {
    @Test
    void parseString_extractsValue() {
      Optional<String> result = BenchmarkCli.parseString("--corpus=/path/to/file.ndjson", "corpus");
      assertTrue(result.isPresent());
      assertEquals("/path/to/file.ndjson", result.get());
    }

    @Test
    void parseString_returnsEmptyForNonMatchingKey() {
      Optional<String> result = BenchmarkCli.parseString("--corpus=/path/to/file.ndjson", "output");
      assertTrue(result.isEmpty());
    }

    @Test
    void parseString_handlesEmptyValue() {
      Optional<String> result = BenchmarkCli.parseString("--corpus=", "corpus");
      assertTrue(result.isPresent());
      assertEquals("", result.get());
    }

    @Test
    void parseString_handlesValueWithEquals() {
      Optional<String> result = BenchmarkCli.parseString("--query=a=b=c", "query");
      assertTrue(result.isPresent());
      assertEquals("a=b=c", result.get());
    }

    @Test
    void parseString_handlesSpacesInValue() {
      Optional<String> result = BenchmarkCli.parseString("--path=my file.txt", "path");
      assertTrue(result.isPresent());
      assertEquals("my file.txt", result.get());
    }

    @Test
    void parseString_isCaseSensitive() {
      Optional<String> result = BenchmarkCli.parseString("--Corpus=value", "corpus");
      assertTrue(result.isEmpty());
    }
  }

  @Nested
  class ParseIntTests {
    @Test
    void parseInt_extractsValidInteger() {
      Optional<Integer> result = BenchmarkCli.parseInt("--count=42", "count");
      assertTrue(result.isPresent());
      assertEquals(42, result.get());
    }

    @Test
    void parseInt_handlesNegativeInteger() {
      Optional<Integer> result = BenchmarkCli.parseInt("--offset=-10", "offset");
      assertTrue(result.isPresent());
      assertEquals(-10, result.get());
    }

    @Test
    void parseInt_returnsEmptyForInvalidInteger() {
      // Capture stderr to verify warning is printed
      PrintStream originalErr = System.err;
      ByteArrayOutputStream errContent = new ByteArrayOutputStream();
      System.setErr(new PrintStream(errContent));

      try {
        Optional<Integer> result = BenchmarkCli.parseInt("--count=abc", "count");
        // Optional.map() returns empty when mapper returns null
        assertTrue(result.isEmpty());
        assertTrue(errContent.toString(UTF_8).contains("Warning"));
      } finally {
        System.setErr(originalErr);
      }
    }

    @Test
    void parseInt_returnsEmptyForNonMatchingKey() {
      Optional<Integer> result = BenchmarkCli.parseInt("--count=42", "size");
      assertTrue(result.isEmpty());
    }

    @Test
    void parseInt_handlesFloatAsInvalid() {
      PrintStream originalErr = System.err;
      ByteArrayOutputStream errContent = new ByteArrayOutputStream();
      System.setErr(new PrintStream(errContent));

      try {
        Optional<Integer> result = BenchmarkCli.parseInt("--count=3.14", "count");
        // Optional.map() returns empty when mapper returns null
        assertTrue(result.isEmpty());
      } finally {
        System.setErr(originalErr);
      }
    }
  }

  @Nested
  class ParsePositiveIntCsvTests {
    @Test
    void parsePositiveIntCsv_parsesValidList() {
      List<Integer> result = BenchmarkCli.parsePositiveIntCsv("10,100,1000");
      assertEquals(List.of(10, 100, 1000), result);
    }

    @Test
    void parsePositiveIntCsv_handlesSpaces() {
      List<Integer> result = BenchmarkCli.parsePositiveIntCsv(" 10 , 100 , 1000 ");
      assertEquals(List.of(10, 100, 1000), result);
    }

    @Test
    void parsePositiveIntCsv_filtersNonPositive() {
      // This is critical: matches FilteredKnnBench.parseSizes() behavior
      List<Integer> result = BenchmarkCli.parsePositiveIntCsv("0,10,-5,100,0");
      assertEquals(List.of(10, 100), result);
    }

    @Test
    void parsePositiveIntCsv_filtersZero() {
      List<Integer> result = BenchmarkCli.parsePositiveIntCsv("0,1,2");
      assertEquals(List.of(1, 2), result);
    }

    @Test
    void parsePositiveIntCsv_handlesEmptyString() {
      List<Integer> result = BenchmarkCli.parsePositiveIntCsv("");
      assertTrue(result.isEmpty());
    }

    @Test
    void parsePositiveIntCsv_handlesNull() {
      List<Integer> result = BenchmarkCli.parsePositiveIntCsv(null);
      assertTrue(result.isEmpty());
    }

    @Test
    void parsePositiveIntCsv_handlesBlankString() {
      List<Integer> result = BenchmarkCli.parsePositiveIntCsv("   ");
      assertTrue(result.isEmpty());
    }

    @Test
    void parsePositiveIntCsv_ignoresEmptyElements() {
      List<Integer> result = BenchmarkCli.parsePositiveIntCsv("10,,100,,,1000");
      assertEquals(List.of(10, 100, 1000), result);
    }

    @Test
    void parsePositiveIntCsv_ignoresInvalidIntegers() {
      PrintStream originalErr = System.err;
      ByteArrayOutputStream errContent = new ByteArrayOutputStream();
      System.setErr(new PrintStream(errContent));

      try {
        List<Integer> result = BenchmarkCli.parsePositiveIntCsv("10,abc,100,xyz,1000");
        assertEquals(List.of(10, 100, 1000), result);
        assertTrue(errContent.toString(UTF_8).contains("Warning"));
      } finally {
        System.setErr(originalErr);
      }
    }

    @Test
    void parsePositiveIntCsv_handlesSingleValue() {
      List<Integer> result = BenchmarkCli.parsePositiveIntCsv("42");
      assertEquals(List.of(42), result);
    }
  }

  @Nested
  class ParseNonNegativeIntCsvTests {
    @Test
    void parseNonNegativeIntCsv_parsesValidList() {
      List<Integer> result = BenchmarkCli.parseNonNegativeIntCsv("10,100,1000");
      assertEquals(List.of(10, 100, 1000), result);
    }

    @Test
    void parseNonNegativeIntCsv_allowsZero() {
      // Critical: 0 should be allowed (unlike parsePositiveIntCsv)
      List<Integer> result = BenchmarkCli.parseNonNegativeIntCsv("0,10,100");
      assertEquals(List.of(0, 10, 100), result);
    }

    @Test
    void parseNonNegativeIntCsv_filtersNegative() {
      List<Integer> result = BenchmarkCli.parseNonNegativeIntCsv("-5,0,10,-10,100");
      assertEquals(List.of(0, 10, 100), result);
    }

    @Test
    void parseNonNegativeIntCsv_handlesSpaces() {
      List<Integer> result = BenchmarkCli.parseNonNegativeIntCsv(" 0 , 50 , 100 ");
      assertEquals(List.of(0, 50, 100), result);
    }

    @Test
    void parseNonNegativeIntCsv_handlesEmptyString() {
      List<Integer> result = BenchmarkCli.parseNonNegativeIntCsv("");
      assertTrue(result.isEmpty());
    }

    @Test
    void parseNonNegativeIntCsv_handlesNull() {
      List<Integer> result = BenchmarkCli.parseNonNegativeIntCsv(null);
      assertTrue(result.isEmpty());
    }

    @Test
    void parseNonNegativeIntCsv_handlesBlankString() {
      List<Integer> result = BenchmarkCli.parseNonNegativeIntCsv("   ");
      assertTrue(result.isEmpty());
    }

    @Test
    void parseNonNegativeIntCsv_ignoresEmptyElements() {
      List<Integer> result = BenchmarkCli.parseNonNegativeIntCsv("0,,50,,,100");
      assertEquals(List.of(0, 50, 100), result);
    }

    @Test
    void parseNonNegativeIntCsv_ignoresInvalidIntegers() {
      PrintStream originalErr = System.err;
      ByteArrayOutputStream errContent = new ByteArrayOutputStream();
      System.setErr(new PrintStream(errContent));

      try {
        List<Integer> result = BenchmarkCli.parseNonNegativeIntCsv("0,abc,50,xyz,100");
        assertEquals(List.of(0, 50, 100), result);
        assertTrue(errContent.toString(UTF_8).contains("Warning"));
      } finally {
        System.setErr(originalErr);
      }
    }

    @Test
    void parseNonNegativeIntCsv_handlesSingleZero() {
      List<Integer> result = BenchmarkCli.parseNonNegativeIntCsv("0");
      assertEquals(List.of(0), result);
    }
  }

  @Nested
  class ParseFlagTests {
    @Test
    void parseFlag_returnsTrueForMatchingFlag() {
      assertTrue(BenchmarkCli.parseFlag("--verbose", "verbose"));
    }

    @Test
    void parseFlag_returnsFalseForNonMatchingFlag() {
      assertFalse(BenchmarkCli.parseFlag("--verbose", "debug"));
    }

    @Test
    void parseFlag_returnsFalseForValueArg() {
      // --verbose=true is NOT a flag, it's a key-value pair
      assertFalse(BenchmarkCli.parseFlag("--verbose=true", "verbose"));
    }

    @Test
    void parseFlag_isCaseSensitive() {
      assertFalse(BenchmarkCli.parseFlag("--Verbose", "verbose"));
    }

    @Test
    void parseFlag_requiresDoubleDash() {
      assertFalse(BenchmarkCli.parseFlag("-verbose", "verbose"));
    }
  }
}
