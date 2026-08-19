/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import java.util.function.BooleanSupplier;

/**
 * Stage 4 of an install run: prove the thing that was installed actually answers.
 *
 * <p>The stage is the decision around the smoke test, not the smoke test itself — whether it is owed
 * at all, the cancellation checkpoint before committing a minute to it, and the phase the surface
 * shows while it runs. The test itself stays where it is (it brackets a {@code RuntimeReconciler}
 * procedure and waits for the engine's answer in cancellation-polling slices) and is reached through
 * a supplier, so both its reconciler bracketing and its sliced wait are untouched by this
 * decomposition.
 */
final class ValidationStage {

  /**
   * How validation ended, and therefore whether the run may go on to claim completion.
   *
   * <ul>
   *   <li>{@code SKIPPED} — not owed on this profile or under this policy. The run completes.
   *   <li>{@code PASSED} — the engine answered. The run completes.
   *   <li>{@code CANCELLED} — the user (or an op-lease drain) stopped the run first. Terminal state
   *       is already {@code cancelled}; the run must not claim completion.
   *   <li>{@code FAILED} — the smoke test reported failure and has already set the terminal state.
   * </ul>
   */
  enum Verdict {
    SKIPPED,
    PASSED,
    CANCELLED,
    FAILED;

    /** Whether the run may proceed to its completion bookkeeping. */
    boolean allowsCompletion() {
      return this == SKIPPED || this == PASSED;
    }
  }

  private final BooleanSupplier cancelRequested;
  private final Runnable onCancelled;
  private final Runnable onSmokeTestStarting;
  private final BooleanSupplier smokeTest;

  ValidationStage(
      BooleanSupplier cancelRequested,
      Runnable onCancelled,
      Runnable onSmokeTestStarting,
      BooleanSupplier smokeTest) {
    this.cancelRequested = cancelRequested;
    this.onCancelled = onCancelled;
    this.onSmokeTestStarting = onSmokeTestStarting;
    this.smokeTest = smokeTest;
  }

  /**
   * @param owed whether a smoke test is owed at all — a chat model was installed and policy permits
   *     using it
   */
  Verdict run(boolean owed) {
    if (!owed) {
      return Verdict.SKIPPED;
    }
    // Checked before the test, not inside it: the test can spend a whole minute waiting for the
    // engine, and a run cancelled just before it must not pay that minute before saying so.
    if (cancelRequested.getAsBoolean()) {
      onCancelled.run();
      return Verdict.CANCELLED;
    }
    onSmokeTestStarting.run();
    return smokeTest.getAsBoolean() ? Verdict.PASSED : Verdict.FAILED;
  }
}
