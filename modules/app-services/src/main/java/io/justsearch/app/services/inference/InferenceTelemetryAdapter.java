/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.inference;

import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.inference.RuntimeIdentity;
import io.justsearch.app.inference.TargetPhase;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import io.justsearch.app.inference.telemetry.RequestKind;
import io.justsearch.app.inference.telemetry.RequestOutcome;
import io.justsearch.app.inference.telemetry.StartupReason;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.app.services.inference.InferenceTags.ConfigApplyTags;
import io.justsearch.app.services.inference.InferenceTags.ConfigFailureTags;
import io.justsearch.app.services.inference.InferenceTags.HealthFailureTags;
import io.justsearch.app.services.inference.InferenceTags.HealthSeverity;
import io.justsearch.app.services.inference.InferenceTags.RequestDurationTags;
import io.justsearch.app.services.inference.InferenceTags.RequestQueueTags;
import io.justsearch.app.services.inference.InferenceTags.StartupAttemptTags;
import io.justsearch.app.services.inference.InferenceTags.StartupDurationTags;
import io.justsearch.app.services.inference.InferenceTags.StartupFailureTags;
import io.justsearch.app.services.inference.InferenceTags.TransitionTags;
import io.justsearch.telemetry.catalog.EmptyTags;
import java.time.Duration;
import java.util.Objects;

/**
 * Bridges {@link InferenceTelemetryEvents} (defined in {@code app-inference}, no telemetry
 * dependency) to the typed {@link InferenceMetricCatalog}. Only this class touches catalog
 * instruments; the rest of the inference codebase stays free of telemetry imports.
 *
 * <p>Tempdoc 412 follow-up: signatures take phase-name strings (bounded by {@code Mode}
 * enum names) rather than the obsolete {@code InferenceRuntime}-typed Optionals; phase-typed
 * runtime scaffold was deleted as dead code.
 */
public final class InferenceTelemetryAdapter implements InferenceTelemetryEvents {

  private final InferenceMetricCatalog catalog;

  public InferenceTelemetryAdapter(InferenceMetricCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  @Override
  public void onTransition(
      String fromPhase, String toPhase, TransitionReason reason, Duration elapsed) {
    String from = fromPhase == null ? "OFFLINE" : fromPhase;
    String to = toPhase == null ? "OFFLINE" : toPhase;
    TransitionReason r = reason == null ? TransitionReason.UNKNOWN : reason;
    TransitionTags tags = TransitionTags.of(from, to, r);
    catalog.transitionTotal.increment(tags);
    if (elapsed != null && !elapsed.isNegative()) {
      catalog.transitionDurationMs.record(elapsed.toMillis(), tags);
    }
  }

  @Override
  public void onStartupAttempt(InferenceConfig schema, StartupReason reason, TargetPhase target) {
    if (target == null || reason == null) return;
    catalog.startupAttemptTotal.increment(new StartupAttemptTags(target, reason));
  }

  @Override
  public void onStartupComplete(
      InferenceConfig schema, Duration elapsed, RuntimeIdentity identity, TargetPhase target) {
    if (target == null || elapsed == null || elapsed.isNegative()) return;
    catalog.startupDurationMs.record(elapsed.toMillis(), new StartupDurationTags(target));
  }

  @Override
  public void onStartupFailure(InferenceFailure failure) {
    if (failure == null) return;
    // Tempdoc 518 fix B: route by event-method context (this method = startup), not by
    // sub-record type. The failure's wireCode flows through as a String tag value. Resolves
    // observations.md #99 (Bug D synthesis) without misattributing — e.g., switchToOnlineMode's
    // ConfigFailure stays on inference.startup.failure_total, where it belongs.
    catalog.startupFailureTotal.increment(
        new StartupFailureTags(TargetPhase.ONLINE, failure.wireCode()));
  }

  @Override
  public void onHealthFailure(
      InferenceFailure.HealthFailure failure, int consecutiveCount, boolean restartTriggered) {
    if (failure == null) return;
    HealthSeverity severity =
        restartTriggered ? HealthSeverity.RESTART_TRIGGERED : HealthSeverity.SINGLE;
    // Tempdoc 518 fix B: tag uses String wireCode for parity with the other failure tags.
    catalog.healthFailureTotal.increment(HealthFailureTags.of(failure.code(), severity));
  }

  @Override
  public void onHealthRecovered(int previousFailureCount) {
    catalog.healthRecoveredTotal.increment(EmptyTags.INSTANCE);
  }

  @Override
  public void onConfigApplyAttempt(
      InferenceConfig oldSchema, InferenceConfig newSchema, boolean restartRequired) {
    catalog.configApplyTotal.increment(new ConfigApplyTags(restartRequired));
  }

  @Override
  public void onConfigApplyComplete(Duration elapsed) {
    // No specific metric for "apply complete" beyond the apply_total counter; the
    // duration is captured by the transition path's transition_duration_ms.
  }

  @Override
  public void onConfigApplyFailure(InferenceFailure failure) {
    if (failure == null) return;
    ConfigFailureTags tags;
    if (failure instanceof InferenceFailure.ConfigFailure cf) {
      tags = ConfigFailureTags.of(cf.code());
    } else if (failure instanceof InferenceFailure.TransitionFailure tf) {
      tags = ConfigFailureTags.of(tf.code());
    } else {
      tags = new ConfigFailureTags(failure.wireCode());
    }
    catalog.configApplyFailureTotal.increment(tags);
  }

  @Override
  public void onRequestEnqueued(RequestKind kind) {
    // No counter for enqueue; queue_wait_ms is recorded on onRequestStarted.
  }

  @Override
  public void onRequestStarted(RequestKind kind, Duration waitedMs) {
    if (kind == null || waitedMs == null || waitedMs.isNegative()) return;
    catalog.requestQueueWaitMs.record(waitedMs.toMillis(), new RequestQueueTags(kind));
  }

  @Override
  public void onRequestCompleted(RequestKind kind, Duration totalMs, RequestOutcome outcome) {
    if (kind == null || outcome == null || totalMs == null || totalMs.isNegative()) return;
    catalog.requestDurationMs.record(totalMs.toMillis(), new RequestDurationTags(kind, outcome));
  }
}
