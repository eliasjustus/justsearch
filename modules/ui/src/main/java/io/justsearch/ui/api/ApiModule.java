/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import java.util.Set;

/**
 * Tempdoc 583 §D.2a — the route-cohort registration seam.
 *
 * <p>A self-contained cohort of related routes that binds itself to the Javalin app and tears down
 * its own background work symmetrically. {@link LocalApiServer} holds its cohorts as a {@code
 * List<ApiModule>} and iterates them for register + shutdown, so a new cohort is wired by adding one
 * list entry rather than by threading a new field through the construction / route-binding / stop
 * sites (the three-site growth pattern 583 decomposed — see {@link ResourceApiModule}).
 *
 * <p>Honest scope (AHA): there is one implementer today ({@code ResourceApiModule}). This interface
 * is the convention for future cohorts, adopted now while the seam is cheap; the static
 * handler-passing {@code *Routes} classes (Status/Indexing/Debug/Ai/Inference/Knowledge/Agent) stay
 * as-is — forcing them into instances would be over-engineering.
 */
interface ApiModule {
  /** Bind this cohort's routes to {@code app}. Called once during {@code setupRoutes}. */
  void register(Javalin app);

  /**
   * Stop any background work this cohort started (SSE heartbeat schedulers, etc.). Called from
   * {@link LocalApiServer#stop()}. Default no-op for cohorts that hold no resources.
   */
  default void shutdown() {}

  /**
   * Tempdoc 583 §D.3a (owning-module dimension) — the route paths this module registered, for the
   * route manifest's {@code owningModule} attribution. Derived from the module's own registration
   * (single source — no parallel prefix table), so the manifest reports the REAL owner, not a guess.
   * Empty by default; route-owning modules capture their paths during {@link #register}.
   */
  default Set<String> ownedRoutePaths() {
    return Set.of();
  }

  /** The module's display name in the manifest (default = simple class name). */
  default String moduleName() {
    return getClass().getSimpleName();
  }
}
