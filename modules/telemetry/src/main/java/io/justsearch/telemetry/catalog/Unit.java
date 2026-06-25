/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

/**
 * Typed unit annotation for {@link MetricDefinition}.
 *
 * <p>Carrying the unit on the metric (rather than via metric-name suffix conventions like
 * {@code _ms}) makes the unit a first-class property the catalog can validate and downstream
 * tooling can reason about. Wire format is unchanged — exporters still emit metric names verbatim.
 */
public enum Unit {
  /** No unit (counter, ratio, dimensionless gauge). */
  NONE,
  COUNT,
  /** Wall-clock duration, milliseconds. */
  MILLISECONDS,
  /** Wall-clock duration, microseconds. */
  MICROSECONDS,
  /** Wall-clock duration, nanoseconds. */
  NANOSECONDS,
  BYTES,
  RATIO
}
