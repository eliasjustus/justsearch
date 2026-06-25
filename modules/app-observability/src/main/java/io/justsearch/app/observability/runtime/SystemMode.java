/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.runtime;

/**
 * Top-level system runtime mode discriminator.
 *
 * <p>Per slice 440 (Runtime mode STATE Resource): the rolled-up "what kind of system are we
 * right now" signal that the FE projects to render mode-aware affordances. Conservative v1
 * shape: two values matching the only system-mode discriminator the head process currently
 * has authority over ({@code justsearch.eval.mode} system property).
 *
 * <p>Future dimensions ({@code DEMO}, {@code DEGRADED}) are deferred until they have a
 * single authoritative source; per slice 440's flagged design questions, {@code DEGRADED}
 * specifically needs a compositional encoding ({@code degraded-because-X}) rather than a
 * flat enum value.
 */
public enum SystemMode {
  /** Default operating mode — production index, real corpus. */
  PRODUCTION,

  /** Eval mode — test corpus, debug endpoints enabled. Set via {@code justsearch.eval.mode}. */
  EVAL
}
