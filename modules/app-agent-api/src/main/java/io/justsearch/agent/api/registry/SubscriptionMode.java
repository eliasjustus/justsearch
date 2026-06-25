/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Subscription mode for a Resource entry (Operation primitive uses ONE_SHOT semantics implicitly).
 *
 * <p>Per tempdoc 429 §6 + 421-data-plane.md: distinguishes Resource variants. Used by
 * downstream slices (430 ships HealthEvent with {@code SSE_STREAM}; future Table Resources
 * will ship with {@code ONE_SHOT} or {@code POLLING}).
 */
public enum SubscriptionMode {
  /** Single fetch, no streaming. Used for static tabular data. */
  ONE_SHOT,
  /** Server-sent events stream with backend-pushed updates. Used for log + event streams. */
  SSE_STREAM,
  /** Periodic polling at a declared interval. Used when push isn't feasible. */
  POLLING
}
