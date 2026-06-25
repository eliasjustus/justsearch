/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Sealed taxonomy of inference-runtime failure values. The single canonical wire-form
 * ({@link #wireCode()}) is used as a metric tag value, status-record {@code error} field, and
 * structured log key — eliminating the prior fragmentation between
 * {@code ModeTransitionException.Reason} (15-value flat enum), per-metric reason tags, and
 * free-form status strings.
 *
 * <p>Tempdoc 412 Phase 1: replaces {@code ModeTransitionException}'s flat reason enum with a
 * sealed interface whose sub-records group by failure category. Pattern matching at use sites
 * is exhaustive.
 *
 * <p>Each subtype carries:
 * <ul>
 *   <li>a typed {@code code} enum (StartupCode/HealthCode/ConfigCode/TransitionCode),
 *   <li>a human-readable {@code detail} string,
 *   <li>an optional {@code cause} {@link Throwable} (config validation has no cause).
 * </ul>
 */
public sealed interface InferenceFailure
    permits InferenceFailure.StartupFailure,
        InferenceFailure.HealthFailure,
        InferenceFailure.ConfigFailure,
        InferenceFailure.TransitionFailure {

  /**
   * Canonical snake_case wire form of the underlying code enum. Used uniformly as metric tag
   * value, structured log key, and status-record {@code error} field.
   */
  String wireCode();

  /** Human-readable detail; never null (empty string when not provided). */
  String detail();

  /** Optional underlying exception. Empty for {@link ConfigFailure} (validation, not exception). */
  Optional<Throwable> cause();

  /** Startup-side failure: server failed to start or come up healthy. */
  record StartupFailure(StartupCode code, String detail, Throwable causeOrNull)
      implements InferenceFailure {
    public StartupFailure {
      Objects.requireNonNull(code, "code");
      detail = detail == null ? "" : detail;
    }

    @Override
    public String wireCode() {
      return code.wireValue();
    }

    @Override
    public Optional<Throwable> cause() {
      return Optional.ofNullable(causeOrNull);
    }
  }

  /** Health-side failure: probe timed out or process died after health was established. */
  record HealthFailure(HealthCode code, String detail, Throwable causeOrNull)
      implements InferenceFailure {
    public HealthFailure {
      Objects.requireNonNull(code, "code");
      detail = detail == null ? "" : detail;
    }

    @Override
    public String wireCode() {
      return code.wireValue();
    }

    @Override
    public Optional<Throwable> cause() {
      return Optional.ofNullable(causeOrNull);
    }
  }

  /** Config-validation failure: caught before any server lifecycle action. */
  record ConfigFailure(ConfigCode code, String detail) implements InferenceFailure {
    public ConfigFailure {
      Objects.requireNonNull(code, "code");
      detail = detail == null ? "" : detail;
    }

    @Override
    public String wireCode() {
      return code.wireValue();
    }

    @Override
    public Optional<Throwable> cause() {
      return Optional.empty();
    }
  }

  /** Transition-orchestration failure: holder swap failed during apply / restart / rollback. */
  record TransitionFailure(TransitionCode code, String detail, Throwable causeOrNull)
      implements InferenceFailure {
    public TransitionFailure {
      Objects.requireNonNull(code, "code");
      detail = detail == null ? "" : detail;
    }

    @Override
    public String wireCode() {
      return code.wireValue();
    }

    @Override
    public Optional<Throwable> cause() {
      return Optional.ofNullable(causeOrNull);
    }
  }
}
