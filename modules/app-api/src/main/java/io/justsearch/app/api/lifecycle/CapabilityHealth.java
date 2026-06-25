/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.lifecycle;

/**
 * Operational health state of a system capability (Worker, Inference).
 *
 * <p>Health fluctuates within a structurally acquired capability. The transition from PENDING
 * to any other state is the structural acquisition (irreversible). Subsequent transitions
 * are health fluctuations.
 */
public enum CapabilityHealth {
  PENDING,
  READY,
  DEGRADED,
  RECOVERING,
  OFFLINE
}
