/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.telemetry;

import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.inference.RuntimeIdentity;
import io.justsearch.app.inference.TargetPhase;
import java.time.Duration;

/**
 * No-op implementation of {@link InferenceTelemetryEvents}. Returned by
 * {@link InferenceTelemetryEvents#noop()}; cached as a singleton.
 *
 * <p>Tempdoc 412 (with follow-up reshape: phase-name strings, no {@code InferenceRuntime}).
 */
public final class NoopInferenceTelemetryEvents implements InferenceTelemetryEvents {

  /** Singleton instance. */
  public static final NoopInferenceTelemetryEvents INSTANCE = new NoopInferenceTelemetryEvents();

  private NoopInferenceTelemetryEvents() {}

  @Override
  public void onTransition(
      String fromPhase, String toPhase, TransitionReason reason, Duration elapsed) {}

  @Override
  public void onStartupAttempt(InferenceConfig schema, StartupReason reason, TargetPhase target) {}

  @Override
  public void onStartupComplete(
      InferenceConfig schema, Duration elapsed, RuntimeIdentity identity, TargetPhase target) {}

  @Override
  public void onStartupFailure(InferenceFailure failure) {}

  @Override
  public void onHealthFailure(
      InferenceFailure.HealthFailure failure, int consecutiveCount, boolean restartTriggered) {}

  @Override
  public void onHealthRecovered(int previousFailureCount) {}

  @Override
  public void onConfigApplyAttempt(
      InferenceConfig oldSchema, InferenceConfig newSchema, boolean restartRequired) {}

  @Override
  public void onConfigApplyComplete(Duration elapsed) {}

  @Override
  public void onConfigApplyFailure(InferenceFailure failure) {}

  @Override
  public void onRequestEnqueued(RequestKind kind) {}

  @Override
  public void onRequestStarted(RequestKind kind, Duration waitedMs) {}

  @Override
  public void onRequestCompleted(RequestKind kind, Duration totalMs, RequestOutcome outcome) {}
}
