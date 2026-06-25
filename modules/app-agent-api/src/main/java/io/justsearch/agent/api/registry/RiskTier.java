/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Risk tier for an Operation. Drives confirmation requirements and approval gating.
 *
 * <p>Bit-for-bit-preserves the {@code SafetyLevel} mapping per tempdoc 429 §A.2:
 * READ_ONLY → LOW, WRITE → MEDIUM, DESTRUCTIVE → HIGH. The agent loop's
 * approval-gating, retry-logic, and telemetry branch on these values.
 */
public enum RiskTier {
  /** Read-only operations: auto-approve in the agent loop, eligible for transient retry. */
  LOW,
  /** Write operations: require user approval, no auto-retry on failure. */
  MEDIUM,
  /** Destructive operations: require user approval, may require typed confirmation. */
  HIGH
}
