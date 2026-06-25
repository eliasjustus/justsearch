/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * How strongly a contribution is isolated from the host (tempdoc 560 §5 / P2: "isolation
 * proportional to trust; the process boundary is the cheapest isolation"). Ordered weakest →
 * strongest.
 */
public enum IsolationLevel {
  /** Runs in the host process. Only the host's own (CORE) code earns this. */
  IN_PROCESS,
  /** Runs in a child process — the cheapest real isolation (P2). The MCP-host's EXECUTABLE tools. */
  OUT_OF_PROCESS,
  /** Runs under an additional sandbox (SES compartment / capability attenuation) atop a boundary. */
  SANDBOXED,
  /** Cannot run — the required isolation is not available, so the contribution is refused. */
  DENIED
}
