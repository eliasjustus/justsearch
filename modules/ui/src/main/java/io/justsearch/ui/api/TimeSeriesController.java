/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.telemetry.RrdMetricStore;
import io.justsearch.telemetry.RrdMetricStore.TimeSeriesResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controller for the {@code /api/debug/metrics/timeseries} endpoint.
 *
 * <p>Provides time-series query access to curated metrics stored in RRD format.
 */
public final class TimeSeriesController {

  /**
   * Pattern for relative time specs. Accepts both "-1h" and "1h" forms.
   * The leading "-" is optional; both are interpreted as "N time units ago".
   */
  private static final Pattern RELATIVE_TIME = Pattern.compile("^-?(\\d+)([hmd])$");

  /** Sentinel value indicating time range overflow. */
  private static final long TIME_OVERFLOW = -1;
  /** Sentinel value indicating invalid timestamp format. */
  private static final long TIME_INVALID = -2;

  /** Maximum values for bounds validation (1 year). */
  private static final long MAX_MINUTES = 525600;
  private static final long MAX_HOURS = 8760;
  private static final long MAX_DAYS = 365;

  /** Step seconds (matches RrdMetricStore.STEP_SECONDS). */
  private static final long STEP_SECONDS = 60;

  private final Supplier<RrdMetricStore> rrdStoreSupplier;

  /**
   * Creates a new controller.
   *
   * @param rrdStoreSupplier supplier that returns the RRD metric store, or null if unavailable
   */
  public TimeSeriesController(Supplier<RrdMetricStore> rrdStoreSupplier) {
    this.rrdStoreSupplier = rrdStoreSupplier;
  }

  /**
   * Handles GET /api/debug/metrics/timeseries.
   *
   * <p>Query params:
   * <ul>
   *   <li>{@code metric} (required) - metric name
   *   <li>{@code start} - ISO timestamp or relative ("-1h", "-24h", "-7d"), default "-1h"
   *   <li>{@code end} - ISO timestamp or "now", default "now"
   * </ul>
   */
  public void handleGetTimeSeries(Context ctx) {
    // H5: Capture time once at handler start for consistent calculations
    final long nowEpoch = Instant.now().getEpochSecond();

    RrdMetricStore rrdStore;
    try {
      rrdStore = rrdStoreSupplier.get();
    } catch (Exception e) {
      rrdStore = null;
    }

    if (rrdStore == null) {
      ctx.status(503).json(errorResponse("RRD metric store unavailable", "STORE_UNAVAILABLE"));
      return;
    }

    String metric = ctx.queryParam("metric");
    if (metric == null || metric.isBlank()) {
      // L1: Don't include available_metrics in error responses
      ctx.status(400).json(errorResponse("Missing required parameter: metric", "MISSING_METRIC"));
      return;
    }

    if (!rrdStore.getCuratedMetrics().contains(metric)) {
      // L1: Don't include available_metrics in error responses
      ctx.status(404).json(errorResponse("Unknown metric", "UNKNOWN_METRIC"));
      return;
    }

    String startParam = ctx.queryParam("start");
    String endParam = ctx.queryParam("end");

    long endEpoch = parseTimeSpec(endParam, "now", nowEpoch, nowEpoch);
    long startEpoch = parseTimeSpec(startParam, "-1h", nowEpoch - 3600, nowEpoch);

    // C2: Check for overflow
    if (startEpoch == TIME_OVERFLOW || endEpoch == TIME_OVERFLOW) {
      ctx.status(400).json(errorResponse("Time range too large (max 1 year)", "TIME_RANGE_OVERFLOW"));
      return;
    }

    // C3: Check for invalid timestamp format
    if (startEpoch == TIME_INVALID || endEpoch == TIME_INVALID) {
      ctx.status(400).json(errorResponse(
          "Invalid timestamp format. Use ISO-8601 or relative (-1h, -24h, -7d)",
          "INVALID_TIMESTAMP"));
      return;
    }

    if (startEpoch >= endEpoch) {
      ctx.status(400).json(errorResponse("start must be before end", "INVALID_RANGE"));
      return;
    }

    TimeSeriesResult result = rrdStore.query(metric, startEpoch, endEpoch);
    if (result == null) {
      // M4: 503 not 500 - store temporarily unavailable
      ctx.status(503).json(errorResponse("Time-series store temporarily unavailable", "QUERY_FAILED"));
      return;
    }

    // M5: Include response metadata
    ctx.json(new TimeSeriesResponse(
        result.metric(),
        startEpoch,
        endEpoch,
        STEP_SECONDS,
        result.timestamps(),
        result.values()
    ));
  }

  /**
   * Handles GET /api/debug/metrics/timeseries/available.
   *
   * <p>Returns the list of curated metrics available for time-series queries.
   */
  public void handleGetAvailable(Context ctx) {
    RrdMetricStore rrdStore;
    try {
      rrdStore = rrdStoreSupplier.get();
    } catch (Exception e) {
      rrdStore = null;
    }

    if (rrdStore == null) {
      ctx.status(503).json(errorResponse("RRD metric store unavailable", "STORE_UNAVAILABLE"));
      return;
    }

    ctx.json(Map.of("metrics", rrdStore.getCuratedMetrics()));
  }

  /**
   * Parses a time specification string.
   *
   * @param spec the time spec (ISO timestamp, relative like "-1h", or "now")
   * @param defaultSpec default if spec is null or blank
   * @param referenceEpoch reference epoch for fallback (unused after fix — retained for API stability)
   * @param nowEpoch current time epoch for "now" and relative calculations
   * @return epoch seconds, or TIME_OVERFLOW (-1) for overflow, or TIME_INVALID (-2) for invalid format
   */
  @SuppressWarnings("PMD.UnusedFormalParameter") // referenceEpoch retained for caller contract stability
  private static long parseTimeSpec(String spec, String defaultSpec, long referenceEpoch, long nowEpoch) {
    if (spec == null || spec.isBlank()) {
      spec = defaultSpec;
    }

    if ("now".equalsIgnoreCase(spec)) {
      return nowEpoch;
    }

    // Try relative format: -1h, -24h, -7d, -30m
    Matcher m = RELATIVE_TIME.matcher(spec);
    if (m.matches()) {
      long value;
      try {
        value = Long.parseLong(m.group(1));
      } catch (NumberFormatException e) {
        return TIME_OVERFLOW; // Number too large for long
      }

      String unit = m.group(2);

      // C2: Validate reasonable bounds (max 1 year)
      long maxValue = switch (unit) {
        case "m" -> MAX_MINUTES;
        case "h" -> MAX_HOURS;
        case "d" -> MAX_DAYS;
        default -> MAX_DAYS;
      };
      if (value > maxValue || value < 0) {
        return TIME_OVERFLOW;
      }

      Duration duration = switch (unit) {
        case "m" -> Duration.ofMinutes(value);
        case "h" -> Duration.ofHours(value);
        case "d" -> Duration.ofDays(value);
        default -> Duration.ZERO;
      };
      return nowEpoch - duration.toSeconds();
    }

    // Try ISO timestamp
    try {
      return Instant.parse(spec).getEpochSecond();
    } catch (Exception e) {
      // C3: Return sentinel value for invalid timestamp (not silent fallback)
      return TIME_INVALID;
    }
  }

  /**
   * M6: Standardized error response helper.
   */
  private static Map<String, Object> errorResponse(String error, String errorCode) {
    return Map.of(
        "error", error,
        "errorCode", errorCode,
        "timestamp", Instant.now().toString()
    );
  }

  /**
   * M5: Response record for time-series queries with metadata.
   */
  @SuppressWarnings("ArrayRecordComponent") // L3: Intentional for API performance
  public record TimeSeriesResponse(
      String metric,
      long queryStart,
      long queryEnd,
      long stepSeconds,
      long[] timestamps,
      double[] values
  ) {}
}
