/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

/**
 * Lifecycle phase for a threshold-based signal.
 *
 * <p>Per tempdoc 430 §A.1 + Prometheus alerting state machine (PENDING / FIRING /
 * RESOLVED): PENDING means the predicate has held for less than {@code for}, FIRING means
 * the predicate has held continuously past {@code for}, RESOLVED means
 * {@code keep_firing_for} has elapsed since the predicate went false. The
 * {@link io.justsearch.app.observability.health.ThresholdState} body carries the phase
 * so the FE can render pending/firing differently from a resolved transition.
 */
public enum ThresholdPhase {
  PENDING,
  FIRING,
  RESOLVED
}
