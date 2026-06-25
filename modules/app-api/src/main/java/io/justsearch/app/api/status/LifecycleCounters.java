/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/**
 * Cumulative lifecycle counters surfaced on {@code /api/status}. Replaces the prior
 * {@code LlmStatusView.softRestarts} / {@code hardRestarts} (which were always null today
 * because no callsite populated them).
 *
 * <p>Tempdoc 412 Phase 3.
 *
 * <p>Stability: stable (API contract)
 */
public record LifecycleCounters(
    long softRestarts,
    long hardRestarts,
    long transitionsTotal) {}
