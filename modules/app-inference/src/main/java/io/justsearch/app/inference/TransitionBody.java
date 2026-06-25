/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;
import io.justsearch.app.api.ConfigCode;
import io.justsearch.app.api.HealthCode;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.StartupCode;
import io.justsearch.app.api.TransitionCode;

/**
 * The work a transition does, executed under the {@link TransitionRunner}'s lock.
 *
 * <p>The body receives the prior {@link InferenceRuntimeView} (read-only) and returns a
 * {@link TransitionOutcome} indicating success (with the new view to install) or failure (with
 * the rollback view). The body is also responsible for emitting context-specific typed events
 * (e.g., {@code events.onStartupAttempt}, {@code events.onStartupComplete} or
 * {@code events.onConfigApplyAttempt} / {@code onConfigApplyComplete} / {@code onConfigApplyFailure}).
 * The runner emits the umbrella {@code events.onTransition} on success or failure exactly once.
 *
 * <p>Bodies may throw {@link ModeTransitionException} — the runner treats this the same as
 * a {@link TransitionOutcome.Failure} return (rolls back, records the failure on the view,
 * emits the transition event, and re-throws). Most bodies should prefer returning
 * {@code Failure} explicitly so the failure category is typed; thrown {@code ModeTransitionException}s
 * are a compatibility bridge that will be removed when P3 lands.
 *
 * <p>Tempdoc 518 P1.
 */
@FunctionalInterface
public interface TransitionBody {

  TransitionOutcome execute(InferenceRuntimeView priorView) throws ModeTransitionException;
}
