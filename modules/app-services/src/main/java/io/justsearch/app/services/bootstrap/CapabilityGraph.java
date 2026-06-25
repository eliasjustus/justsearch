/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.lifecycle.WorkerCapability;

/**
 * Tempdoc 519 §7 / Step 7: typed phase output for the capability phase. Holds the bootstrap's
 * two capability handles — {@link WorkerCapability} (Worker process readiness + health) and
 * {@link InferenceCapability} (LLM runtime readiness).
 *
 * <p>Produced by CapabilityPhase.run() in the final phase chain; consumed by ServicePhase /
 * ApiPhase / status controllers that need to render capability state without coupling to the
 * bootstrap as a locator.
 *
 * <p>Step 8 will populate this on the bootstrap and route status controllers through it.
 */
public record CapabilityGraph(WorkerCapability worker, InferenceCapability inference) {

  /** Returns a "neither available" capability graph for the test-only / SearchPort-only path. */
  public static CapabilityGraph unavailable() {
    return new CapabilityGraph(new WorkerCapability(), new InferenceCapability(false));
  }
}
