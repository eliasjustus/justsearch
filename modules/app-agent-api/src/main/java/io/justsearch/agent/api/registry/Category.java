/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Information-shape axis on a {@link Resource}.
 *
 * <p>Per slice 444a (Resource Category substrate) + {@code 50-decisions/06-resource-category.md}:
 * orthogonal to {@link SubscriptionMode} (transport). Each Category constrains which
 * SubscriptionMode values are valid; the constraint matrix lives in
 * {@code 20-systems/01-resources.md} §"Category × SubscriptionMode matrix".
 *
 * <p>The vocabulary is closed at five values; adding a new value follows shape governance
 * per {@code 10-kernel/04-shape-governance.md} §"Vocabulary Governance".
 *
 * <p>Slice 448 phase 6 (2026-05-07) retired LOG_TAIL per CONFLICT-LEDGER C-012 path-b.
 * Operator-trace surfaces are modeled as the sibling
 * {@link DiagnosticChannel} primitive instead — the asymmetries documented in
 * {@code slices/446-head-log-log-tail-resource.md} §A made LOG_TAIL a structural
 * mismatch with the other Resource Categories.
 */
public enum Category {
  /**
   * Current value of one thing. No retained history; updates replace prior value. Examples:
   * settings, runtime mode, capability flags.
   */
  STATE,

  /**
   * Live typed events with bounded recent-window retention. Examples: HealthEvent (slice 430),
   * agent session events. Requires {@link HistoryPolicy} declaring retention semantics.
   */
  EVENT_STREAM,

  /**
   * Durable past events; query-shaped reads. Examples: ingestion ledger, operation history.
   * Append-only; reads are typically paginated. Requires {@link HistoryPolicy}.
   */
  HISTORY,

  /**
   * Current state of a collection of items. Examples: job queue, library sources. Snapshot of
   * items plus per-item delta updates.
   */
  TABULAR,

  /**
   * Sliding-window numeric metric. Examples: job-queue depth trend, indexing throughput,
   * GPU utilization. Receiver semantics is snapshot-of-window with regular sample cadence
   * (subscribers get the current N samples in one frame, not a stream of discrete events).
   *
   * <p>Per slice 3a.1.4: distinct from EVENT_STREAM in update cadence (regular vs
   * irregular), wire economy (one frame carries N samples vs N frames carrying one sample
   * each), and FE rendering (window-aware normalization vs per-event treatment). Forbids
   * {@link HistoryPolicy} — the {@code values} array on the wire payload carries the
   * window implicitly.
   *
   * <p>Wire payload type: {@code TimeseriesSnapshot}
   * ({@code modules/app-observability/.../metrics/TimeseriesSnapshot.java}).
   */
  TIMESERIES
}
