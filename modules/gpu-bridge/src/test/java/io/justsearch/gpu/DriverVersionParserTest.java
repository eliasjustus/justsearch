package io.justsearch.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link DriverVersionParser} — the single home for the {@code major.minor}
 * driver-version parse shared by the NVML probe and the nvidia-smi fallback.
 */
final class DriverVersionParserTest {

  @ParameterizedTest
  @CsvSource({
    "591.59, 591, 59",
    "570.0, 570, 0",
    "0.0, 0, 0",
    // Trailing build component / suffix is ignored — only the leading major.minor is read.
    "535.183.01, 535, 183",
    "576.88.7-beta, 576, 88",
    // Surrounding whitespace is trimmed.
    "'  591.59  ', 591, 59"
  })
  void parsesMajorMinor(String raw, int major, int minor) {
    assertArrayEquals(new int[] {major, minor}, DriverVersionParser.parse(raw));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "   ", // blank
        "591", // no dot
        ".59", // leading dot (no major)
        "591.", // trailing dot (no minor)
        "abc.def", // non-numeric major and minor
        "abc.59", // non-numeric major
        "unknown",
        "N/A"
      })
  void returnsNullForMalformed(String raw) {
    assertNull(DriverVersionParser.parse(raw));
  }

  @Test
  void minorStopsAtFirstNonDigit() {
    // "591.59abc" → minor is "59"; the trailing non-digits are ignored, not rejected.
    assertArrayEquals(new int[] {591, 59}, DriverVersionParser.parse("591.59abc"));
  }
}
