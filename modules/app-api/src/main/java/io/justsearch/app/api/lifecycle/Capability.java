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

  String pendingReason();

  boolean required();

  String name();
}
