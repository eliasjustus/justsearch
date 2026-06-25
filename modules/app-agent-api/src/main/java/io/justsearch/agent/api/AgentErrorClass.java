/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

/** Error classes used to drive retry policy and telemetry dimensions. */
public enum AgentErrorClass {
  TRANSIENT,
  PERMANENT,
  BUDGET,
  POLICY,
  TOOL_CONTRACT,
  CANCELLED
}
