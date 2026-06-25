/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

/**
 * Per-occurrence severity carried on every {@link HealthEvent}.
 *
 * <p>Per tempdoc 430 §B.A: severity is a wire field, not a catalog field. Two emissions
 * of the same id can have different severities — e.g., {@code worker.throughput.stalled}
 * is WARNING under normal workload and ERROR under critical workload. The display
 * catalog provides a {@code severityHint} per {@code (id, reason)} pair as an optional
 * render-time fallback only.
 *
 * <p>Aligns with k8s {@code clusterv1.Condition.Severity} (removed in v1beta2 to match
 * upstream's per-occurrence convention) and CloudEvents 1.0 / OpenTelemetry
 * {@code LogRecord.SeverityNumber} discipline of severity-as-wire.
 */
public enum Severity {
  INFO,
  WARNING,
  ERROR
}
