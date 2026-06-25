/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

/**
 * Per-metric exemplar policy declared on a {@link MetricDefinition}.
 *
 * <p>{@link #TRACE_BASED} (the default) records exemplars when a sampled span is active at emit
 * time — same semantics as the OTel SDK's {@code ExemplarFilter.traceBased()}. {@link #OFF}
 * disables exemplars for hot-path metrics where the per-record overhead matters
 * (e.g., {@code index.runtime.write_barrier_wait_us}).
 *
 * <p>Implementation note: per-metric exemplar suppression is enforced at NDJSON export time
 * because the public OTel Java SDK doesn't expose per-View {@code ExemplarReservoirSupplier}
 * (verified against 1.60.1, 2026-04-25). When that API stabilizes, this enum can drive a real
 * per-View reservoir registration.
 */
public enum Exemplars {
  TRACE_BASED,
  OFF
}
