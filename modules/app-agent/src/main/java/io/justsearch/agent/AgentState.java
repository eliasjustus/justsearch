/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

/**
 * Agent execution states for state-machine orchestration (Direction E, tempdoc 227).
 *
 * <p>The state machine separates LLM-as-task-executor from LLM-as-router. Routing transitions
 * (SEARCHING → DECIDING) are determined by code, not the model. The model is invoked only for
 * bounded sub-tasks: generating tool arguments and summarizing results.
 *
 * <p>Current implementation covers the PRIMARY agent's SEARCHING → DECIDING transition.
 * EXECUTING and COMPLETING states for the Organizer agent are reserved for future work.
 */
public enum AgentState {

  /**
   * PRIMARY is collecting information via search/browse tools. Transitions to DECIDING after
   * {@link AgentLoopService#PRIMARY_FORCE_COMMIT_ITERATIONS} tool-use rounds without a handoff.
   */
  SEARCHING,

  /**
   * PRIMARY must commit on its next turn: available tools are restricted to handoff-only and
   * {@code tool_choice: "required"} is applied. The model generates handoff arguments (the
   * routing decision is made by code, not the model).
   */
  DECIDING,
}
