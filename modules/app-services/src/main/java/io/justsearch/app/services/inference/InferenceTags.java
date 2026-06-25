/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.inference;

import io.justsearch.app.api.ConfigCode;
import io.justsearch.app.api.HealthCode;
import io.justsearch.app.api.StartupCode;
import io.justsearch.app.inference.TargetPhase;
import io.justsearch.app.api.TransitionCode;
import io.justsearch.app.inference.telemetry.RequestKind;
import io.justsearch.app.inference.telemetry.RequestOutcome;
import io.justsearch.app.inference.telemetry.StartupReason;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Typed tag schemas for {@code inference.*} metrics. Each schema wraps one of the bounded
 * enums in {@code app-inference} and projects to OTel {@link Attributes} for the catalog to
 * forward to the SDK.
 *
 * <p>Tempdoc 412 Phase 4. Mirrors the
 * {@link io.justsearch.indexerworker.services.IndexRuntimeTags} shape from tempdoc 417.
 */
public final class InferenceTags {

  private InferenceTags() {}

  // ==================== Tag keys (snake_case for wire format) ====================
  static final String FROM_PHASE_KEY = "from_phase";
  static final String TO_PHASE_KEY = "to_phase";
  static final String PHASE_KEY = "phase";
  static final String REASON_KEY = "reason";
  static final String CODE_KEY = "code";
  static final String SEVERITY_KEY = "severity";
  static final String RESTART_REQUIRED_KEY = "restart_required";
  static final String KIND_KEY = "kind";
  static final String OUTCOME_KEY = "outcome";

  static final Set<String> TRANSITION_KEYS = unmodifiable(FROM_PHASE_KEY, TO_PHASE_KEY, REASON_KEY);
  static final Set<String> STARTUP_ATTEMPT_KEYS = unmodifiable(PHASE_KEY, REASON_KEY);
  static final Set<String> STARTUP_DURATION_KEYS = unmodifiable(PHASE_KEY);
  static final Set<String> STARTUP_FAILURE_KEYS = unmodifiable(PHASE_KEY, CODE_KEY);
  // Tempdoc 518 fix B: TRANSITION_FAILURE_KEYS removed; the dropped
  // `inference.transition.failure_total` metric was an over-correction for Bug D.
  // Per-event-method routing + a String wireCode tag on the existing metrics is correct.
  static final Set<String> CONFIG_APPLY_KEYS = unmodifiable(RESTART_REQUIRED_KEY);
  static final Set<String> CONFIG_FAILURE_KEYS = unmodifiable(CODE_KEY);
  static final Set<String> HEALTH_FAILURE_KEYS = unmodifiable(CODE_KEY, SEVERITY_KEY);
  static final Set<String> REQUEST_QUEUE_KEYS = unmodifiable(KIND_KEY);
  static final Set<String> REQUEST_DURATION_KEYS = unmodifiable(KIND_KEY, OUTCOME_KEY);

  private static Set<String> unmodifiable(String... keys) {
    Set<String> ks = new LinkedHashSet<>();
    for (String k : keys) ks.add(k);
    return Set.copyOf(ks);
  }

  // ==================== Tag schemas ====================

  /** Phase-transition tag set (used by {@code inference.transition.*}). */
  public record TransitionTags(String fromPhase, String toPhase, TransitionReason reason)
      implements TagSchema {
    public TransitionTags {
      Objects.requireNonNull(fromPhase, "fromPhase");
      Objects.requireNonNull(toPhase, "toPhase");
      Objects.requireNonNull(reason, "reason");
    }

    public static TransitionTags of(String fromPhase, String toPhase, TransitionReason reason) {
      return new TransitionTags(fromPhase, toPhase, reason);
    }

    @Override
    public Set<String> allowedKeys() {
      return TRANSITION_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(
          AttributeKey.stringKey(FROM_PHASE_KEY), fromPhase,
          AttributeKey.stringKey(TO_PHASE_KEY), toPhase,
          AttributeKey.stringKey(REASON_KEY), reason.wireValue());
    }
  }

  /** Tag schema for {@code inference.startup.attempt_total}. */
  public record StartupAttemptTags(TargetPhase phase, StartupReason reason) implements TagSchema {
    public StartupAttemptTags {
      Objects.requireNonNull(phase, "phase");
      Objects.requireNonNull(reason, "reason");
    }

    @Override
    public Set<String> allowedKeys() {
      return STARTUP_ATTEMPT_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(
          AttributeKey.stringKey(PHASE_KEY), phase.wireValue(),
          AttributeKey.stringKey(REASON_KEY), reason.wireValue());
    }
  }

  /** Tag schema for {@code inference.startup.duration_ms}. */
  public record StartupDurationTags(TargetPhase phase) implements TagSchema {
    public StartupDurationTags {
      Objects.requireNonNull(phase, "phase");
    }

    @Override
    public Set<String> allowedKeys() {
      return STARTUP_DURATION_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(PHASE_KEY), phase.wireValue());
    }
  }

  /**
   * Tag schema for {@code inference.startup.failure_total}. Tempdoc 518 fix B — broadened
   * {@code code} from {@link StartupCode} to {@link String wireCode} so the metric can
   * record the underlying wireCode of any {@link io.justsearch.app.api.InferenceFailure}
   * sub-record (StartupCode / TransitionCode / ConfigCode / HealthCode). Resolves
   * observations.md item #99 (Bug D) without needing a separate metric per sub-record type:
   * the metric reflects the EVENT CONTEXT (which {@code onXxxFailure} was called), the tag
   * reflects the underlying wireCode. The union of all four code enums is ~20 wireCodes —
   * well below any reasonable cardinality concern.
   */
  public record StartupFailureTags(TargetPhase phase, String wireCode) implements TagSchema {
    public StartupFailureTags {
      Objects.requireNonNull(phase, "phase");
      Objects.requireNonNull(wireCode, "wireCode");
    }

    /** Convenience constructor for the typed {@link StartupCode} case. */
    public static StartupFailureTags of(TargetPhase phase, StartupCode code) {
      return new StartupFailureTags(phase, code.wireValue());
    }

    @Override
    public Set<String> allowedKeys() {
      return STARTUP_FAILURE_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(
          AttributeKey.stringKey(PHASE_KEY), phase.wireValue(),
          AttributeKey.stringKey(CODE_KEY), wireCode);
    }
  }

  /** Tag schema for {@code inference.config.apply_total}. */
  public record ConfigApplyTags(boolean restartRequired) implements TagSchema {
    @Override
    public Set<String> allowedKeys() {
      return CONFIG_APPLY_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(
          AttributeKey.stringKey(RESTART_REQUIRED_KEY), Boolean.toString(restartRequired));
    }
  }

  /** Tag schema for {@code inference.config.apply_failure_total}. Code may be ConfigCode or TransitionCode. */
  public record ConfigFailureTags(String wireCode) implements TagSchema {
    public ConfigFailureTags {
      Objects.requireNonNull(wireCode, "wireCode");
    }

    public static ConfigFailureTags of(ConfigCode code) {
      return new ConfigFailureTags(code.wireValue());
    }

    public static ConfigFailureTags of(TransitionCode code) {
      return new ConfigFailureTags(code.wireValue());
    }

    @Override
    public Set<String> allowedKeys() {
      return CONFIG_FAILURE_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(CODE_KEY), wireCode);
    }
  }

  /** Severity of a health failure: SINGLE = one probe failure; RESTART_TRIGGERED = threshold exceeded. */
  public enum HealthSeverity {
    SINGLE("single"),
    RESTART_TRIGGERED("restart_triggered");

    private final String wireValue;

    HealthSeverity(String wireValue) {
      this.wireValue = wireValue;
    }

    public String wireValue() {
      return wireValue;
    }
  }

  /**
   * Tag schema for {@code inference.health.failure_total}. Tempdoc 518 fix B — broadened
   * {@code code} to {@link String wireCode} (parallel to {@link StartupFailureTags}).
   */
  public record HealthFailureTags(String wireCode, HealthSeverity severity) implements TagSchema {
    public HealthFailureTags {
      Objects.requireNonNull(wireCode, "wireCode");
      Objects.requireNonNull(severity, "severity");
    }

    /** Convenience constructor for the typed {@link HealthCode} case. */
    public static HealthFailureTags of(HealthCode code, HealthSeverity severity) {
      return new HealthFailureTags(code.wireValue(), severity);
    }

    @Override
    public Set<String> allowedKeys() {
      return HEALTH_FAILURE_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(
          AttributeKey.stringKey(CODE_KEY), wireCode,
          AttributeKey.stringKey(SEVERITY_KEY), severity.wireValue());
    }
  }

  /** Tag schema for {@code inference.request.queue_wait_ms}. */
  public record RequestQueueTags(RequestKind kind) implements TagSchema {
    public RequestQueueTags {
      Objects.requireNonNull(kind, "kind");
    }

    @Override
    public Set<String> allowedKeys() {
      return REQUEST_QUEUE_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(KIND_KEY), kind.wireValue());
    }
  }

  /** Tag schema for {@code inference.request.duration_ms}. */
  public record RequestDurationTags(RequestKind kind, RequestOutcome outcome) implements TagSchema {
    public RequestDurationTags {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(outcome, "outcome");
    }

    @Override
    public Set<String> allowedKeys() {
      return REQUEST_DURATION_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(
          AttributeKey.stringKey(KIND_KEY), kind.wireValue(),
          AttributeKey.stringKey(OUTCOME_KEY), outcome.wireValue());
    }
  }
}
