/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

/**
 * Marker interface for the project's telemetry handle. Tempdoc 417 Phase 3e collapsed the
 * historic {@code counter/timer/gauge/histogram/meter} surface — every emit path now flows
 * through {@link io.justsearch.telemetry.catalog.MetricCatalog} typed instruments, so this
 * interface is intentionally empty. Production callsites that need to construct catalogs cast
 * to {@link LocalTelemetry} and call {@link LocalTelemetry#registry()}; non-production paths
 * (tests, no-op stubs) wire {@link io.justsearch.telemetry.catalog.NoopMetricRegistry} into
 * their catalogs and never touch this interface.
 *
 * <p>Kept (rather than deleted entirely) so that {@code Telemetry} parameters across the
 * codebase remain valid — they propagate the {@link LocalTelemetry} handle without leaking
 * the concrete type into module APIs that should not depend on it.
 */
public interface Telemetry extends AutoCloseable {
  // Tempdoc 417 Phase 3c: meter(String scope) retired. Use catalog instead.
  // Tempdoc 417 Phase 3d: histogram(...) default retired. Use HistogramMetric.record(...).
  // Tempdoc 417 Phase 3e: counter/timer/gauge abstract methods retired. Use catalog instruments.
}
