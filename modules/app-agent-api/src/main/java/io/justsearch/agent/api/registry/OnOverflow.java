/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Behavior when a {@link HistoryPolicy}'s bound is reached.
 *
 * <p>Per slice 444a §B.A.3: semantics vary by {@link HistoryPolicy.Mode}.
 *
 * <ul>
 *   <li>{@link HistoryPolicy.Mode#RING_BUFFER} — all three values are sensible
 *       ({@link #EVICT_OLDEST} is typical; {@link #BACKPRESSURE} blocks the producer;
 *       {@link #DROP_NEWEST} is rare).
 *   <li>{@link HistoryPolicy.Mode#DURABLE} — declared informationally; the underlying
 *       store enforces overflow behavior (database retention sweep, capped table policy).
 *   <li>{@link HistoryPolicy.Mode#EXTERNAL} — {@link #BACKPRESSURE} is forbidden by the
 *       compact constructor; backpressure on an external system the Resource doesn't
 *       manage is semantically nonsensical.
 * </ul>
 */
public enum OnOverflow {
  /** Evict the oldest entry to make room for the newest. Typical RING_BUFFER behavior. */
  EVICT_OLDEST,

  /** Block the producer until consumer drains. Use when downstream slowness must surface upstream. */
  BACKPRESSURE,

  /** Drop the newest entry, preserving the existing buffer. Rare; primarily for sampling-style stores. */
  DROP_NEWEST
}
