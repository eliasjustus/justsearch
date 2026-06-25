/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.metrics;

/**
 * Rendering-shape hint on a {@link MetricRef} cross-link.
 *
 * <p>Per slice 3a.1.4 §B.4: the FE Resource-view renderer registry dispatches by
 * {@code (Category, hint?, density?)}; the hint discriminates which TIMESERIES renderer
 * variant to pick when more than one is registered. Default hint at consumer site is
 * {@link #SPARK} (the canonical sparkline ships with the slice).
 *
 * <p>The vocabulary is open in the registry sense (plugins may register handlers for new
 * hints) but closed in the wire-vocabulary sense — adding a new value to this enum is a
 * shape-governance change per {@code 10-kernel/04-shape-governance.md} §"Vocabulary
 * Governance".
 */
public enum RenderHint {
  /** Inline trend visualization — small SVG line chart. Default for TIMESERIES. */
  SPARK,
  /** Single-value gauge with range context — current value vs window min/max. */
  GAUGE,
  /** Histogram bin distribution over the window. */
  HISTOGRAM
}
