/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.lifecycle;

/**
 * A system capability whose health can be queried. Capabilities are structurally acquired once
 * (their context transitions from null to non-null) and their operational health fluctuates
 * independently thereafter.
 *
 * <p>Used by capability gates (Javalin before-handlers) to determine whether a route group
 * should accept requests or return 503.
 */
public interface Capability {

  CapabilityHealth health();

  default boolean available() {
    return health() == CapabilityHealth.READY;
  }

  /**
   * The machine-readable cause of the current non-READY state — a {@link LifecycleReasonCode#code()}
   * — or {@code null} when READY.
   *
   * <p>Tempdoc 837: this slot is a CODE, not prose. Human sentences belong in {@link
   * #pendingDetail()}; putting them here deletes information at a type boundary the compiler cannot
   * see, because two consumers filter the slot with {@link LifecycleReasonCode#isKnown(String)} and
   * substitute a generic code for anything they do not recognize.
   */
  String pendingReason();

  /**
   * The human sentence accompanying {@link #pendingReason()} (an exception message, an elapsed
   * budget, a remedy paragraph), or {@code null} when there is none.
   *
   * <p>Tempdoc 837 §0.2: consumers that want prose — the Condition {@code message} rendered in
   * Health, the 503 debug body — read this; consumers that want a stable code read {@link
   * #pendingReason()}. Never carries the code itself.
   */
  default String pendingDetail() {
    return null;
  }

  boolean required();

  String name();
}
