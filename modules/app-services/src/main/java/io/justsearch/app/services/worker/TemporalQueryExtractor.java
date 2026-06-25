/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 385: Lightweight deterministic extraction of date references from query text.
 *
 * <p>Covers the 84% of temporal queries that mention explicit dates in standard formats. Does NOT
 * handle relative dates ("last week", "recently") — those are ambiguous without a reference date.
 *
 * <p>Extracted dates are converted to a {@link KnowledgeSearchRequest.TimeRangeMs} suitable for
 * injection as a {@code metaPublishedAt} boost filter.
 */
final class TemporalQueryExtractor {

  private TemporalQueryExtractor() {}

  /** Result of temporal extraction from a query. */
  record TemporalExtraction(
      List<LocalDate> dates, KnowledgeSearchRequest.TimeRangeMs suggestedRange) {

    TemporalExtraction {
      dates = dates == null ? List.of() : List.copyOf(dates);
    }

    /** Convenience: true if any dates were extracted. */
    boolean hasDates() {
      return !dates.isEmpty();
    }
  }

  // -- Patterns --

  private static final String MONTHS =
      "January|February|March|April|May|June|July|August"
          + "|September|October|November|December";

  /** "October 7, 2023" or "October 7 2023" — day-precision, US style. */
  private static final Pattern P_MDY =
      Pattern.compile("(?i)\\b(" + MONTHS + ")\\s+(\\d{1,2}),?\\s+(\\d{4})\\b");

  /** "7 October 2023" — day-precision, European style. */
  private static final Pattern P_DMY =
      Pattern.compile("(?i)\\b(\\d{1,2})\\s+(" + MONTHS + ")\\s+(\\d{4})\\b");

  /** "2023-10-07" — ISO 8601. */
  private static final Pattern P_ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");

  /** "October 2023" — month-level precision. Lower priority (checked last). */
  private static final Pattern P_MY = Pattern.compile("(?i)\\b(" + MONTHS + ")\\s+(\\d{4})\\b");

  // -- Public API --

  /**
   * Extract date references from query text.
   *
   * @param queryText the raw query string
   * @return extraction with parsed dates and a suggested temporal range (null range if no dates)
   */
  static TemporalExtraction extract(String queryText) {
    if (queryText == null || queryText.isBlank()) {
      return new TemporalExtraction(List.of(), null);
    }

    Set<LocalDate> dateSet = new LinkedHashSet<>();

    // Priority 1: Month DD, YYYY
    extractMdy(queryText, dateSet);
    // Priority 2: DD Month YYYY
    extractDmy(queryText, dateSet);
    // Priority 3: YYYY-MM-DD
    extractIso(queryText, dateSet);
    // Priority 4: Month YYYY (month-level, may overlap with P_MDY matches)
    extractMy(queryText, dateSet);

    List<LocalDate> dates = new ArrayList<>(dateSet);
    if (dates.isEmpty()) {
      return new TemporalExtraction(List.of(), null);
    }

    KnowledgeSearchRequest.TimeRangeMs range = computeRange(dates);
    return new TemporalExtraction(dates, range);
  }

  // -- Extractors --

  private static void extractMdy(String text, Set<LocalDate> out) {
    Matcher m = P_MDY.matcher(text);
    while (m.find()) {
      tryParseDate(out, parseMonth(m.group(1)), parseIntSafe(m.group(2)), parseIntSafe(m.group(3)));
    }
  }

  private static void extractDmy(String text, Set<LocalDate> out) {
    Matcher m = P_DMY.matcher(text);
    while (m.find()) {
      tryParseDate(out, parseMonth(m.group(2)), parseIntSafe(m.group(1)), parseIntSafe(m.group(3)));
    }
  }

  private static void extractIso(String text, Set<LocalDate> out) {
    Matcher m = P_ISO.matcher(text);
    while (m.find()) {
      tryParseDate(
          out, parseIntSafe(m.group(2)), parseIntSafe(m.group(3)), parseIntSafe(m.group(1)));
    }
  }

  private static void extractMy(String text, Set<LocalDate> out) {
    Matcher m = P_MY.matcher(text);
    while (m.find()) {
      // Month-level: use 1st of the month
      tryParseDate(out, parseMonth(m.group(1)), 1, parseIntSafe(m.group(2)));
    }
  }

  // -- Helpers --

  private static void tryParseDate(Set<LocalDate> out, int month, int day, int year) {
    if (month < 1 || month > 12 || day < 1 || year < 1900 || year > 2100) return;
    try {
      out.add(LocalDate.of(year, month, day));
    } catch (DateTimeException ignored) {
      // Invalid date (e.g., Feb 30) — skip
    }
  }

  private static int parseMonth(String monthName) {
    if (monthName == null || monthName.isBlank()) return -1;
    try {
      return Month.valueOf(monthName.toUpperCase(Locale.ROOT)).getValue();
    } catch (IllegalArgumentException e) {
      return -1;
    }
  }

  private static int parseIntSafe(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Compute a temporal range from extracted dates with ±1 day tolerance for publication date
   * imprecision.
   */
  private static KnowledgeSearchRequest.TimeRangeMs computeRange(List<LocalDate> dates) {
    LocalDate min = dates.getFirst();
    LocalDate max = dates.getFirst();
    for (LocalDate d : dates) {
      if (d.isBefore(min)) min = d;
      if (d.isAfter(max)) max = d;
    }

    long fromMs = min.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    long toMs =
        max.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            + 86_400_000L
            - 1; // end of day

    return new KnowledgeSearchRequest.TimeRangeMs(fromMs, toMs);
  }
}
