/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Identifies the invocation surface(s) an Operation is exposed to.
 *
 * <p>Per tempdoc 429 §6: an Operation's {@code executors} set declares which
 * surfaces may invoke it. The {@code AgentOperationEmitter} filters by
 * {@code executors.contains(AGENT)}; the {@code UIOperationEmitter} filters by
 * {@code executors.contains(UI)}; etc.
 */
public enum ExecutorTag {
  /** Frontend UI shell (button, form, command palette, etc.). */
  UI,
  /** Agent loop (LLM tool-call surface). */
  AGENT,
  /** Command-line / scripted invocation (V1.5+; reserved). */
  CLI
}
