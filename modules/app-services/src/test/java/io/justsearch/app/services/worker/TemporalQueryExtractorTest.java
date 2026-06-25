package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.TemporalQueryExtractor.TemporalExtraction;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("TemporalQueryExtractor — deterministic date extraction (385 #9)")
class TemporalQueryExtractorTest {

  @Nested
  @DisplayName("Month DD, YYYY format")
  class MonthDayYear {

    @Test
    void basicDate() {
      var result = TemporalQueryExtractor.extract("published on October 7, 2023");
      assertEquals(1, result.dates().size());
      assertEquals(LocalDate.of(2023, 10, 7), result.dates().getFirst());
    }

    @Test
    void withoutComma() {
      var result = TemporalQueryExtractor.extract("released January 1 2020");
      assertEquals(1, result.dates().size());
      assertEquals(LocalDate.of(2020, 1, 1), result.dates().getFirst());
    }

    @Test
    void caseInsensitive() {
      var result = TemporalQueryExtractor.extract("happened in DECEMBER 31, 1999");
      assertEquals(1, result.dates().size());
      assertEquals(LocalDate.of(1999, 12, 31), result.dates().getFirst());
    }
  }

  @Nested
  @DisplayName("DD Month YYYY format")
  class DayMonthYear {

    @Test
    void europeanStyle() {
      var result = TemporalQueryExtractor.extract("on 7 October 2023 the report was published");
      assertTrue(result.dates().contains(LocalDate.of(2023, 10, 7)));
    }

    @Test
    void endOfYear() {
      var result = TemporalQueryExtractor.extract("31 December 1999 was a milestone");
      assertTrue(result.dates().contains(LocalDate.of(1999, 12, 31)));
    }
  }

  @Nested
  @DisplayName("ISO 8601 format")
  class IsoDate {

    @Test
    void basicIso() {
      var result = TemporalQueryExtractor.extract("date is 2023-10-07");
      assertEquals(1, result.dates().size());
      assertEquals(LocalDate.of(2023, 10, 7), result.dates().getFirst());
    }

    @Test
    void startOfYear() {
      var result = TemporalQueryExtractor.extract("from 2020-01-01 to 2020-12-31");
      assertEquals(2, result.dates().size());
      assertTrue(result.dates().contains(LocalDate.of(2020, 1, 1)));
      assertTrue(result.dates().contains(LocalDate.of(2020, 12, 31)));
    }
  }

  @Nested
  @DisplayName("Month YYYY format")
  class MonthYear {

    @Test
    void monthLevelPrecision() {
      var result = TemporalQueryExtractor.extract("in October 2023 the law changed");
      assertEquals(1, result.dates().size());
      assertEquals(LocalDate.of(2023, 10, 1), result.dates().getFirst());
    }

    @Test
    void startOfYear() {
      var result = TemporalQueryExtractor.extract("January 2020 was eventful");
      assertEquals(1, result.dates().size());
      assertEquals(LocalDate.of(2020, 1, 1), result.dates().getFirst());
    }
  }

  @Nested
  @DisplayName("May disambiguation")
  class MayAmbiguity {

    @Test
    void mayAsMonthWithYear() {
      var result = TemporalQueryExtractor.extract("what happened in May 2023?");
      assertTrue(result.hasDates(), "May followed by 4-digit year should be recognized as a date");
      assertEquals(LocalDate.of(2023, 5, 1), result.dates().getFirst());
    }

    @Test
    void mayAsMonthWithDayYear() {
      var result = TemporalQueryExtractor.extract("on May 15, 2023 the policy changed");
      assertTrue(result.hasDates());
      assertEquals(LocalDate.of(2023, 5, 15), result.dates().getFirst());
    }

    @Test
    void mayAsVerbDoesNotMatch() {
      var result = TemporalQueryExtractor.extract("this may return unexpected results");
      assertFalse(result.hasDates(), "'may return' should not produce a date");
    }

    @Test
    void mayDayDoesNotMatch() {
      var result = TemporalQueryExtractor.extract("what is May Day?");
      assertFalse(result.hasDates(), "'May Day' should not produce a date");
    }
  }

  @Nested
  @DisplayName("Multiple dates")
  class MultipleDates {

    @Test
    void twoExplicitDates() {
      var result =
          TemporalQueryExtractor.extract(
              "Between October 7, 2023 and October 30, 2023 was there a change?");
      assertEquals(2, result.dates().size());
      assertTrue(result.dates().contains(LocalDate.of(2023, 10, 7)));
      assertTrue(result.dates().contains(LocalDate.of(2023, 10, 30)));
    }

    @Test
    void rangeSpansBothDates() {
      var result =
          TemporalQueryExtractor.extract("from January 1, 2023 to December 31, 2023");
      assertNotNull(result.suggestedRange());
      // Range should start before Jan 1 and end after Dec 31
      long jan1Epoch =
          LocalDate.of(2023, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
      long dec31Epoch =
          LocalDate.of(2023, 12, 31)
              .atStartOfDay(java.time.ZoneOffset.UTC)
              .toInstant()
              .toEpochMilli();
      assertTrue(result.suggestedRange().fromMs() < jan1Epoch, "Range start should be before Jan 1");
      assertTrue(result.suggestedRange().toMs() > dec31Epoch, "Range end should be after Dec 31");
    }
  }

  @Nested
  @DisplayName("No dates in query")
  class NoDateInQuery {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "does the TechCrunch article suggest changes?",
          "what is the best programming language",
          "how to make pancakes"
        })
    void noDatesMeansEmptyResult(String query) {
      var result = TemporalQueryExtractor.extract(query);
      assertFalse(result.hasDates());
      assertNull(result.suggestedRange());
    }
  }

  @Nested
  @DisplayName("Invalid date rejection")
  class InvalidDateRejection {

    @Test
    void february30Rejected() {
      var result = TemporalQueryExtractor.extract("on February 30, 2023 something happened");
      assertFalse(result.hasDates(), "February 30 is not a valid date");
    }

    @Test
    void february29OnLeapYear() {
      var result = TemporalQueryExtractor.extract("on February 29, 2024 something happened");
      assertTrue(result.hasDates(), "February 29, 2024 is valid (leap year)");
    }
  }

  @Nested
  @DisplayName("Range computation")
  class RangeComputation {

    @Test
    void singleDateProducesRange() {
      var result = TemporalQueryExtractor.extract("on October 7, 2023");
      assertNotNull(result.suggestedRange());
      long oct7Epoch =
          LocalDate.of(2023, 10, 7).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
      assertTrue(result.suggestedRange().fromMs() < oct7Epoch);
      assertTrue(result.suggestedRange().toMs() > oct7Epoch);
    }
  }

  @Nested
  @DisplayName("Null and blank handling")
  class NullAndBlankHandling {

    @Test
    void nullQuery() {
      var result = TemporalQueryExtractor.extract(null);
      assertFalse(result.hasDates());
      assertNull(result.suggestedRange());
    }

    @Test
    void blankQuery() {
      var result = TemporalQueryExtractor.extract("   ");
      assertFalse(result.hasDates());
    }
  }

  @Nested
  @DisplayName("Deduplication")
  class Deduplication {

    @Test
    void sameDateDifferentFormatsDeduplicates() {
      // "October 7, 2023" and "2023-10-07" are the same date
      var result =
          TemporalQueryExtractor.extract("October 7, 2023 and also 2023-10-07");
      assertEquals(1, result.dates().size(), "Same date in different formats should deduplicate");
    }
  }
}
