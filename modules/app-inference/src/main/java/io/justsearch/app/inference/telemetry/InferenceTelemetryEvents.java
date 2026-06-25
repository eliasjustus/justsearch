/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.telemetry;

import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.inference.RuntimeIdentity;
import io.justsearch.app.inference.TargetPhase;
import java.time.Duration;

/**
 * Observability surface for {@link io.justsearch.app.inference.InferenceLifecycleManager}.
 * Defines the full set of observable events; no other emit path exists. Implementations live
 * outside {@code app-inference} (see {@code InferenceTelemetryAdapter} in {@code app-services})
 * so domain code stays free of telemetry dependencies. Default implementation is
 * {@link NoopInferenceTelemetryEvents}.
 *
 * <p>Tempdoc 412 introduced the contract; tempdoc 412 follow-up reshaped {@code onTransition}
 * to take phase names as bounded {@code String}s (drawn from the {@code Mode} enum's
 * {@code name()} values: {@code OFFLINE} / {@code ONLINE} / {@code INDEXING}) rather than
 * the original {@code Optional<InferenceRuntime>} signature. The phase-typed runtime
 * scaffold was deleted as dead code; the holder rewrite that would have justified it is
 * deferred to a separate tempdoc.
 *
 * <h3>Contract guarantees</h3>
 *
 * <ul>
 *   <li>Each logical mode change fires exactly one {@link #onTransition}; the intermediate
 *       {@code TRANSITIONING} half-event is suppressed by the holder.
 *   <li>Failure events ({@link #onStartupFailure}, {@link #onConfigApplyFailure},
 *       {@link #onHealthFailure}) carry typed {@link InferenceFailure} values; their
 *       {@code wireCode()} is the canonical metric tag value.
 *   <li>Health-check failures fire one event per probe failure; the {@code restartTriggered}
 *       flag distinguishes single failures from those that exceeded the consecutive-failures
 *       threshold and triggered crash recovery.
 *   <li>{@link #onRequestCompleted} fires for every request that crossed the request lock,
 *       regardless of outcome.
 * </ul>
 */
public interface InferenceTelemetryEvents {

  // ==================== Lifecycle ====================

  /**
   * Logical mode change completed. {@code fromPhase} / {@code toPhase} are bounded
   * {@code Mode}-name strings ({@code OFFLINE} / {@code ONLINE} / {@code INDEXING}); the
   * intermediate {@code TRANSITIONING} state is suppressed by the holder. {@code elapsed}
   * is the wall-clock time from {@code beginTransition} to {@code complete}.
   */
  void onTransition(String fromPhase, String toPhase, TransitionReason reason, Duration elapsed);

  // ==================== Startup ====================

  /** Mode-transition method begun. Fires before VRAM checks or process start. */
  void onStartupAttempt(InferenceConfig schema, StartupReason reason, TargetPhase target);

  /** Server started and reached healthy state; identity is now stable. */
  void onStartupComplete(
      InferenceConfig schema, Duration elapsed, RuntimeIdentity identity, TargetPhase target);

  /**
   * Mode-transition method failed. Tempdoc 518 P3: generalized to accept any {@link
   * InferenceFailure} sub-record so the adapter can route per-category without the prior
   * Bug D synthesis (which forced non-{@code StartupFailure} sub-records through
   * {@code StartupCode.UNKNOWN}). Implementations pattern-match on the sub-record and emit
   * to the appropriate metric ({@code inference.startup.failure_total} for
   * {@code StartupFailure}; {@code inference.transition.failure_total} for
   * {@code TransitionFailure}; etc.). Resolves observations.md item #99.
   */
  void onStartupFailure(InferenceFailure failure);

  // ==================== Health ====================

  /**
   * Periodic health probe failed. {@code consecutiveCount} is the running count of
   * back-to-back failures; {@code restartTriggered} is true when the threshold was reached
   * and crash recovery is now firing.
   */
  void onHealthFailure(
      InferenceFailure.HealthFailure failure, int consecutiveCount, boolean restartTriggered);

  /** Periodic health recovered after one or more failures. */
  void onHealthRecovered(int previousFailureCount);

  // ==================== Config apply ====================

  /** {@code applyConfig} call begun. */
  void onConfigApplyAttempt(
      InferenceConfig oldSchema, InferenceConfig newSchema, boolean restartRequired);

  /** {@code applyConfig} succeeded. */
  void onConfigApplyComplete(Duration elapsed);

  /** {@code applyConfig} failed at any stage (validation, server start, rollback). */
  void onConfigApplyFailure(InferenceFailure failure);

  // ==================== Request ====================

  /** Request entered the request-lock queue. */
  void onRequestEnqueued(RequestKind kind);

  /** Request acquired the request-lock and started processing. */
  void onRequestStarted(RequestKind kind, Duration waitedMs);

  /** Request completed (or errored, or was cancelled). */
  void onRequestCompleted(RequestKind kind, Duration totalMs, RequestOutcome outcome);

  // ==================== Default no-op ====================

  /** Returns the cached no-op singleton. Suitable for tests and pre-telemetry bootstrap. */
  static InferenceTelemetryEvents noop() {
    return NoopInferenceTelemetryEvents.INSTANCE;
  }
}
