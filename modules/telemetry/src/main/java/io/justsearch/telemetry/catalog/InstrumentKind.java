/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

/** OTel instrument kind for a {@link MetricDefinition}. */
public enum InstrumentKind {
  /** Synchronous counter ({@code LongCounter}). */
  COUNTER,
  /** Synchronous histogram ({@code LongHistogram}). */
  HISTOGRAM,
  /** Asynchronous gauge backed by a callback supplier. */
  GAUGE,
  /** Asynchronous counter ({@code ObservableLongCounter}) backed by a callback supplier. */
  OBSERVABLE_COUNTER
}
