/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

/**
 * Per-metric RRD archive policy. Metrics declared with {@link
 * MetricDefinition.Builder#archivedTo(RrdArchive)} are recorded by
 * {@code RrdMetricStore} for time-series trend analysis; un-declared metrics are exported to
 * NDJSON only.
 *
 * <p>Tempdoc 417 Phase 3a: reintroduces the type that F5 deleted (Phase 1 carved it out as
 * unwired API surface; Phase 3 wires it).
 *
 * <p>Today only one tier is defined — {@link #STANDARD} matches the existing
 * {@code RrdMetricStore} 3-archive layout (5-min/24h, 1-hour/7d, 1-day/90d). Future variants
 * (e.g., {@code HIGH_RES}, {@code LONG_RETENTION}) can extend without breaking declarations.
 */
public enum RrdArchive {
  /**
   * Standard 3-tier RRD archive: 5-minute resolution for 24 hours, 1-hour resolution for 7 days,
   * 1-day resolution for 90 days. Matches {@code RrdMetricStore}'s baseline configuration.
   */
  STANDARD
}
