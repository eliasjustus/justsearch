/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

/** Loop-control signal from {@code AgentStepRunner.executeIteration} (tempdoc 240 W8). */
record IterationOutcome(boolean terminated, boolean success) {
  static IterationOutcome cont() {
    return new IterationOutcome(false, false);
  }

  static IterationOutcome terminated(boolean success) {
    return new IterationOutcome(true, success);
  }
}
